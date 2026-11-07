package io.github.igorcv88.oneuiliquidglass.hooks;

import android.app.Notification;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import io.github.igorcv88.oneuiliquidglass.Config;
import io.github.igorcv88.oneuiliquidglass.config.NotificationIconPolicy;
import io.github.igorcv88.oneuiliquidglass.config.NotificationIconStore;
import io.github.igorcv88.oneuiliquidglass.config.ConfigClient;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One card's notification small-icon lease. Resource lookup and PNG decoding run off the main
 * thread; the hot pre-draw path compares only view/drawable identity and the current ink colour.
 * Never walks or replaces a conversation avatar, media art or large notification picture.
 */
final class NotificationIconTreatment {
    private static final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "OULG-notification-icons"); t.setDaemon(true); return t;
    });
    /** Lazy so pure alpha/source tests do not instantiate the Android main looper. */
    private static final class MainThread {
        static final Handler handler = new Handler(Looper.getMainLooper());
    }
    private static final Set<ImageView> managed = Collections.newSetFromMap(new WeakHashMap<>());
    private static final java.util.Set<String> reported = new java.util.HashSet<>();
    private static final Set<String> entryProbed = Collections.synchronizedSet(new java.util.HashSet<>());
    private static final int SIZE = 128, SAMPLE = 24;
    private static final int CACHE_LIMIT = 48;
    private static final java.util.LinkedHashMap<String, Resolved> sourceCache =
            new java.util.LinkedHashMap<String, Resolved>(CACHE_LIMIT, .75f, true) {
                @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, Resolved> eldest) {
                    return size() > CACHE_LIMIT;
                }
            };
    private static final long SOURCE_RECHECK_MS = 250;
    private static final String[] ENTRY_FIELDS = {"mEntry", "mNotificationEntry", "notificationEntry", "entry"};
    private static final String[] ENTRY_METHODS = {"getEntry", "getNotificationEntry"};
    private static final String[] SBN_METHODS = {"getSbn", "getStatusBarNotification"};
    private static final String[] SBN_FIELDS = {"mSbn", "sbn", "mStatusBarNotification", "statusBarNotification"};

    static boolean managed(ImageView image) { return managed.contains(image); }

    private static final class Lease {
        final WeakReference<ImageView> view;
        final Drawable nativeDrawable, background;
        final ColorStateList tint;
        final ColorFilter imageFilter;
        final int bgAlpha;
        final Drawable installed;
        int color;

        Lease(ImageView v, Drawable replacement, int ink) {
            view = new WeakReference<>(v);
            nativeDrawable = v.getDrawable();
            background = v.getBackground();
            bgAlpha = background == null ? 255 : background.getAlpha();
            tint = v.getImageTintList();
            imageFilter = v.getColorFilter();
            installed = replacement;
            color = ink;
            v.setImageTintList(null);
            v.clearColorFilter();
            v.setImageDrawable(replacement);
            if (background != null) background.mutate().setAlpha(0);
            managed.add(v);
        }

        boolean current() {
            ImageView v = view.get();
            return v != null && v.getDrawable() == installed;
        }

        void tone(int ink) {
            if (ink == color) return;
            ImageView v = view.get();
            if (v == null || v.getDrawable() != installed) return;
            color = ink;
            installed.setColorFilter(new PorterDuffColorFilter(ink, PorterDuff.Mode.SRC_IN));
            v.invalidate();
        }

        void scale(float fraction) {
            if (installed instanceof NotificationGlyphDrawable)
                ((NotificationGlyphDrawable) installed).scale(fraction);
        }

        void restore() {
            ImageView v = view.get();
            if (v == null) return;
            managed.remove(v);
            if (v.getDrawable() == installed) {
                v.setImageDrawable(nativeDrawable);
                if (v.getImageTintList() == null) v.setImageTintList(tint);
                v.setColorFilter(imageFilter);
            }
            if (background != null && v.getBackground() == background && background.getAlpha() == 0)
                background.setAlpha(bgAlpha);
        }
    }

    static final class Resolved {
        final Bitmap bitmap;
        final String source;
        final boolean officialMatched;
        Resolved(Bitmap bitmap, String source, boolean officialMatched) {
            this.bitmap = bitmap; this.source = source; this.officialMatched = officialMatched;
        }
    }

    private final List<Lease> active = new ArrayList<>();
    private final List<WeakReference<ImageView>> candidates = new ArrayList<>();
    // A row layout/reinflation is the trigger for discovering new icon views. Avoid walking the
    // entire notification view tree on every pre-draw when a template contains no small icon.
    private final View.OnLayoutChangeListener layoutObserver = (view, left, top, right, bottom,
            oldLeft, oldTop, oldRight, oldBottom) -> candidatesDirty = true;
    private boolean candidatesDirty;
    private WeakReference<View> bound = new WeakReference<>(null);
    private Object entry;
    private Object notificationIdentity;
    private String pkg;
    private int selectedMode = -1;
    private long configRevision = -1;
    private boolean pending;
    private Resolved resolved;
    private volatile long revision;
    private String lastSkip;
    private boolean candidatesReported;
    private WeakReference<View> missingRow = new WeakReference<>(null);
    private long nextSourceCheck;

    private void skip(String reason) {
        if (Probe.trace && !reason.equals(lastSkip))
            Probe.log("ICON_TREAT_SKIP", "reason=" + reason + " pkg=" + pkg + " mode=" + selectedMode);
        lastSkip = reason;
    }

    /** Called as part of the existing row's pre-draw, without installing an extra watcher. */
    void sync(View row, int mode, boolean lightBackdrop) {
        if (row == null || mode == NotificationIconPolicy.OFF) { restore(); skip("inactive"); return; }
        long now = SystemClock.uptimeMillis();
        long currentRevision = ConfigClient.revision();
        if (missingRow.get() == row && now < nextSourceCheck
                && configRevision == -1) return;
        boolean refreshSource = bound.get() != row || selectedMode != mode
                || configRevision != currentRevision || now >= nextSourceCheck;
        Object currentEntry = entry;
        Object currentIdentity = notificationIdentity;
        StatusBarNotification sbn = null;
        if (refreshSource) {
            nextSourceCheck = now + SOURCE_RECHECK_MS;
            currentEntry = findEntry(row);
            sbn = findSbn(currentEntry);
            if (sbn == null) sbn = findSbn(row);
            if (currentEntry == null && sbn == null) {
                restore(); missingRow = new WeakReference<>(row);
                skip("entryUnavailable"); probeEntry(row);
                return;
            }
            if (sbn == null || sbn.getNotification() == null) {
                restore(); missingRow = new WeakReference<>(row);
                skip("sbnUnavailable"); probeEntry(row); return;
            }
            missingRow.clear();
            // Retained rows/entries may receive a different Notification and small icon.
            currentIdentity = sbn.getNotification();
        }
        if (bound.get() != row || entry != currentEntry || selectedMode != mode
                || configRevision != currentRevision || notificationIdentity != currentIdentity) {
            restore();
            bound = new WeakReference<>(row);
            entry = currentEntry;
            notificationIdentity = currentIdentity;
            selectedMode = mode;
            configRevision = currentRevision;
            lastSkip = null;
            candidatesReported = false;
            row.addOnLayoutChangeListener(layoutObserver);
            findCandidates(row);
            schedule(row, currentEntry, currentIdentity, sbn, mode);
        }
        if (resolved == null) { if (!pending) skip("noSource"); return; }
        if (resolved.bitmap == null) { skip("existingMono"); return; }
        io.github.igorcv88.oneuiliquidglass.glass.Tuning tuning = io.github.igorcv88.oneuiliquidglass.glass.Tuning.get();
        int ink = tuning.notificationIconInkMode == 1 ? tuning.notificationIconFixedInk
                : tuning.notificationIconInkMode == 2
                    ? (lightBackdrop ? tuning.notificationIconLightInk : tuning.notificationIconDarkInk)
                    : (lightBackdrop ? Color.BLACK : Color.WHITE);
        boolean stale = false;
        for (Lease l : active) if (!l.current()) { l.restore(); stale = true; }
        if (stale) active.removeIf(l -> !l.current());
        if (candidatesDirty || !candidates.isEmpty() && allCandidatesGone()) findCandidates(row);
        if (candidates.isEmpty()) { skip("noIconView"); return; }
        for (WeakReference<ImageView> ref : candidates) {
            ImageView image = ref.get();
            if (image == null || !image.isAttachedToWindow() || image.getDrawable() == null) continue;
            Lease found = null;
            for (Lease l : active) if (l.view.get() == image) { found = l; break; }
            if (found != null) {
                found.tone(ink);
                found.scale(tuning.notificationIconScale);
                continue;
            }
            // A new SystemUI rebind: foreground's old swapped record must not leak into our lease.
            Foreground.glyph(image, false, false);
            NotificationGlyphDrawable replacement = new NotificationGlyphDrawable(resolved.bitmap,
                    tuning.notificationIconScale);
            replacement.setColorFilter(new PorterDuffColorFilter(ink, PorterDuff.Mode.SRC_IN));
            active.add(new Lease(image, replacement, ink));
            if (Probe.trace) Probe.log("ICON_TREAT_APPLIED", "pkg=" + pkg + " source=" + resolved.source
                    + " " + Foreground.describe(image) + " ink=" + Integer.toHexString(ink));
        }
    }

    /** Samsung variants differ in whether their row exposes a field or a getter. */
    static Object findEntry(Object row) {
        for (String name : ENTRY_FIELDS) {
            Object value = Reflect.read(row, name);
            if (value != null) return value;
        }
        for (String name : ENTRY_METHODS) {
            Object value = callOrNull(row, name);
            if (value != null) return value;
        }
        // One UI 9 uses an entry-adapter-backed row. The adapter itself exposes the SBN;
        // the row no longer provides mEntry/getEntry, as confirmed by the device trace.
        Object adapter = Reflect.read(row, "mEntryAdapter");
        if (adapter != null) return adapter;
        return null;
    }

    private static Object callOrNull(Object value, String name) {
        if (value == null) return null;
        try { return Reflect.call(value, name); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) { return null; }
    }

    /** A few firmware rows expose SBN directly; prefer it even when an entry wrapper is absent. */
    private static StatusBarNotification findSbn(Object value) {
        if (value instanceof StatusBarNotification) return (StatusBarNotification) value;
        for (String name : SBN_METHODS) {
            Object result = callOrNull(value, name);
            if (result instanceof StatusBarNotification) return (StatusBarNotification) result;
        }
        for (String name : SBN_FIELDS) {
            Object result = Reflect.read(value, name);
            if (result instanceof StatusBarNotification) return (StatusBarNotification) result;
        }
        return null;
    }

    /** Only class/member names, once per firmware row class while trace is explicitly enabled. */
    private static void probeEntry(View row) {
        if (!Probe.trace || !entryProbed.add(row.getClass().getName())) return;
        StringBuilder names = new StringBuilder();
        for (Class<?> c = row.getClass(); c != null && names.length() < 500; c = c.getSuperclass()) {
            try {
                for (Field field : c.getDeclaredFields()) {
                    String name = field.getName().toLowerCase(Locale.ROOT);
                    if (name.contains("entry") || name.contains("sbn") || name.contains("notif")) {
                        if (names.length() > 0) names.append(',');
                        names.append(field.getName());
                        if (names.length() >= 500) break;
                    }
                }
            } catch (RuntimeException | LinkageError ignored) { }
        }
        Probe.log("ICON_TREAT_ENTRY_PROBE", "row=" + row.getClass().getName()
                + " mEntry=" + (Reflect.field(row.getClass(), "mEntry") != null)
                + " getEntry=" + (Reflect.method(row.getClass(), "getEntry") != null)
                + " fields=" + names);
        Object adapter = Reflect.read(row, "mEntryAdapter");
        if (adapter != null) {
            // Restricted to type/member names. An adapter's private fields may differ by build.
            StringBuilder methods = new StringBuilder();
            for (String name : SBN_METHODS) if (Reflect.method(adapter.getClass(), name) != null) {
                if (methods.length() > 0) methods.append(',');
                methods.append(name);
            }
            StringBuilder fields = new StringBuilder();
            for (String name : SBN_FIELDS) if (Reflect.field(adapter.getClass(), name) != null) {
                if (fields.length() > 0) fields.append(',');
                fields.append(name);
            }
            Probe.log("ICON_TREAT_ADAPTER_PROBE", "type=" + adapter.getClass().getName()
                    + " sbnMethods=" + methods + " sbnFields=" + fields);
        }
    }

    private boolean allCandidatesGone() {
        for (WeakReference<ImageView> ref : candidates) {
            ImageView v = ref.get();
            if (v != null && v.isAttachedToWindow()) return false;
        }
        return true;
    }

    private void findCandidates(View root) {
        candidatesDirty = false;
        candidates.clear();
        find(root, root, 0);
        if (Probe.trace && !candidatesReported) {
            candidatesReported = true;
            Probe.log("ICON_TREAT_CANDIDATES", "pkg=" + pkg + " count=" + candidates.size()
                    + " row=" + root.getClass().getSimpleName());
            if (candidates.isEmpty()) surveyImages(root, root, 0, new int[] {0});
        }
    }

    private void find(View root, View child, int depth) {
        if (depth > 18 || candidates.size() >= 12) return;
        if (child != root && child.getClass().getName().endsWith("ExpandableNotificationRow")) return;
        if (child instanceof ImageView && Foreground.notificationIconCandidate(child,
                root.getContext().getPackageName()))
            candidates.add(new WeakReference<>((ImageView) child));
        if (child instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) child;
            for (int i = 0; i < g.getChildCount(); i++) find(root, g.getChildAt(i), depth + 1);
        }
    }

    /** Trace-only bounded inventory of resource ids, never bitmap or message contents. */
    private void surveyImages(View root, View child, int depth, int[] count) {
        if (depth > 18 || count[0] >= 12) return;
        if (child != root && child.getClass().getName().endsWith("ExpandableNotificationRow")) return;
        if (child instanceof ImageView) {
            count[0]++;
            Probe.log("ICON_TREAT_VIEW", Foreground.describe(child));
        }
        if (child instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) child;
            for (int i = 0; i < group.getChildCount(); i++)
                surveyImages(root, group.getChildAt(i), depth + 1, count);
        }
    }

    private void schedule(View row, Object currentEntry, Object currentIdentity,
                          StatusBarNotification sbn, int mode) {
        final String targetPkg = sbn.getPackageName();
        if (!NotificationIconStore.validPackage(targetPkg)) return;
        pkg = targetPkg;
        // For mode 2, a pixel-level comparison is performed from a one-off snapshot, never each draw.
        int[] shown = null;
        for (WeakReference<ImageView> ref : candidates) {
            ImageView v = ref.get();
            if (v != null && v.getDrawable() != null) { shown = pixels(v.getDrawable(), SAMPLE); break; }
        }
        final int[] snapshot = shown;
        final Notification notification = sbn.getNotification();
        final Context context = row.getContext().getApplicationContext();
        final String cacheKey = sourceKey(targetPkg, notification, mode, configRevision);
        final long ticket = ++revision;
        pending = true;
        Resolved cached;
        synchronized (sourceCache) { cached = sourceCache.get(cacheKey); }
        if (cached != null) {
            pending = false;
            resolved = cached;
            if (Probe.trace) Probe.log("ICON_TREAT_CACHE", "pkg=" + targetPkg + " source=" + cached.source);
            return;
        }
        worker.execute(() -> {
            Resolved next = null;
            boolean eligible = false;
            try {
                PackageManager pm = context.getPackageManager();
                ApplicationInfo info = pm.getApplicationInfo(targetPkg, 0);
                boolean system = NotificationIconPolicy.systemService(targetPkg,
                        (info.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0);
                Drawable official = pm.getApplicationIcon(info);
                boolean match = snapshot != null && sameOfficial(snapshot, pixels(official, SAMPLE));
                // A user's explicitly imported PNG is an opt-in for this specific package,
                // even when the package is not installed as a system application.
                Bitmap user = local(context, targetPkg);
                eligible = user != null || NotificationIconPolicy.eligible(mode, system, match);
                if (user != null) next = new Resolved(user, "user", match);
                else if (eligible) next = resolve(context, targetPkg, official, notification, snapshot, match);
            } catch (PackageManager.NameNotFoundException | RuntimeException | LinkageError e) {
                if (Probe.trace) Probe.log("ICON_TREAT_SOURCE_FAILED", "pkg=" + targetPkg + " error=" + e.getClass().getSimpleName());
            }
            boolean report = eligible && next == null;
            if (report && revision == ticket) reportMissing(context, targetPkg);
            Resolved answer = next;
            if (answer != null && revision == ticket) {
                synchronized (sourceCache) { sourceCache.put(cacheKey, answer); }
            }
            MainThread.handler.post(() -> {
                if (revision != ticket || entry != currentEntry
                        || notificationIdentity != currentIdentity || bound.get() != row) return;
                pending = false;
                resolved = answer;
                if (Probe.trace) Probe.log("ICON_TREAT_ROUTE", "pkg=" + targetPkg + " mode=" + mode
                        + " source=" + (answer == null ? report ? "missing" : "preserve" : answer.source)
                        + " officialMatch=" + (answer != null && answer.officialMatched));
                if (answer != null) row.invalidate();
            });
        });
    }

    static String sourceKey(String pkg, Notification notification, int mode, long configRevision) {
        return pkg + ':' + mode + ':' + configRevision + ':'
                + (notification.getSmallIcon() == null ? "none" : notification.getSmallIcon().toString());
    }

    /** Shared source policy for Samsung's separate status-bar and collapsed-shelf icon views.
     * Must be called on the icon worker, never in a hook or pre-draw callback. */
    static Resolved resolveCompact(Context context, StatusBarNotification sbn, int mode,
                                   int[] nativeArtwork, long configRevision) {
        String pkg = sbn.getPackageName();
        if (!NotificationIconStore.validPackage(pkg) || sbn.getNotification() == null) return null;
        String key = sourceKey(pkg, sbn.getNotification(), mode, configRevision);
        synchronized (sourceCache) {
            Resolved hit = sourceCache.get(key);
            if (hit != null) return hit;
        }
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            boolean system = NotificationIconPolicy.systemService(pkg,
                    (info.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0);
            Drawable official = pm.getApplicationIcon(info);
            boolean match = nativeArtwork != null && sameOfficial(nativeArtwork, pixels(official, SAMPLE));
            Bitmap custom = local(context, pkg);
            Resolved answer = custom != null ? new Resolved(custom, "user", match)
                    : NotificationIconPolicy.eligible(mode, system, match)
                    ? resolve(context, pkg, official, sbn.getNotification(), nativeArtwork, match) : null;
            if (answer != null) synchronized (sourceCache) { sourceCache.put(key, answer); }
            return answer;
        } catch (PackageManager.NameNotFoundException | RuntimeException | LinkageError e) {
            if (Probe.trace) Probe.log("ICON_TREAT_COMPACT_SOURCE_FAILED", "pkg=" + pkg
                    + " error=" + e.getClass().getSimpleName());
            return null;
        }
    }

    private static void reportMissing(Context context, String pkg) {
        synchronized (reported) { if (!reported.add(pkg)) return; }
        try { context.getContentResolver().call(Uri.parse("content://" + Config.AUTHORITY),
                "reportMissingNotificationIcon", pkg, null); }
        catch (RuntimeException e) { if (Probe.trace) Probe.error("ICON_TREAT_REPORT_FAILED", e); }
    }

    /** Official mono, small-icon glyph, native transparent glyph, then the bundled library. */
    private static Resolved resolve(Context context, String pkg, Drawable official,
                                    Notification notification, int[] shown, boolean matched) {
        if (official instanceof AdaptiveIconDrawable) {
            Drawable mono = ((AdaptiveIconDrawable) official).getMonochrome();
            if (mono != null) {
                Bitmap mask = mask(mono, false);
                if (mask != null) return new Resolved(mask, "officialMono", matched);
            }
        }
        if (notification.getSmallIcon() != null) {
            try {
                Drawable small = notification.getSmallIcon().loadDrawable(context);
                Bitmap mask = mask(small, true);
                if (mask != null) return new Resolved(mask, "smallIcon", matched);
            } catch (RuntimeException | LinkageError e) {
                if (Probe.trace) Probe.log("ICON_TREAT_SMALL_FAILED", "pkg=" + pkg + " error=" + e.getClass().getSimpleName());
            }
        }
        if (shown != null && Foreground.oneColourGlyph(shown) != 0) {
            // Existing transparent glyph: already well integrated. No replacement and no missing report.
            return new Resolved(null, "existingMono", matched);
        }
        Bitmap bundled = builtIn(context, pkg);
        return bundled == null ? null : new Resolved(bundled, "library", matched);
    }

    private static Bitmap local(Context context, String pkg) {
        try (InputStream in = context.getContentResolver().openInputStream(NotificationIconStore.uri(Config.AUTHORITY, pkg))) {
            return in == null ? null : bitmapMask(in);
        } catch (IOException | RuntimeException e) { return null; }
    }

    private static Bitmap builtIn(Context context, String pkg) {
        try {
            Context app = context.createPackageContext("io.github.igorcv88.oneuiliquidglass", 0);
            try (InputStream in = app.getAssets().open("notification_icons/" + pkg + ".png")) { return bitmapMask(in); }
        } catch (PackageManager.NameNotFoundException | IOException | RuntimeException e) { return null; }
    }

    private static Bitmap bitmapMask(InputStream in) {
        Bitmap original = BitmapFactory.decodeStream(in);
        if (original == null) return null;
        try {
            Bitmap scaled = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
            float factor = Math.min(SIZE / (float) original.getWidth(),
                    SIZE / (float) original.getHeight());
            float width = original.getWidth() * factor, height = original.getHeight() * factor;
            Canvas canvas = new Canvas(scaled);
            canvas.drawBitmap(original, null, new android.graphics.RectF((SIZE - width) / 2,
                    (SIZE - height) / 2, (SIZE + width) / 2, (SIZE + height) / 2),
                    new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
            Bitmap normalized = whiteMask(scaled);
            scaled.recycle();
            return normalized;
        } finally { original.recycle(); }
    }

    private static Bitmap mask(Drawable d, boolean requireSmallSilhouette) {
        if (d == null) return null;
        int[] px = pixels(d, SIZE);
        if (px == null || requireSmallSilhouette && !silhouette(px)) return null;
        return whiteMask(px, SIZE);
    }

    private static Bitmap whiteMask(Bitmap source) {
        int[] px = new int[source.getWidth() * source.getHeight()];
        source.getPixels(px, 0, source.getWidth(), 0, 0, source.getWidth(), source.getHeight());
        return whiteMask(px, source.getWidth());
    }

    private static Bitmap whiteMask(int[] pixels, int width) {
        int[] mono = new int[pixels.length];
        for (int i = 0; i < pixels.length; i++) mono[i] = (pixels[i] & 0xff000000) | 0x00ffffff;
        return Bitmap.createBitmap(mono, width, pixels.length / width, Bitmap.Config.ARGB_8888);
    }

    private static int[] pixels(Drawable d, int size) {
        if (d == null) return null;
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Drawable copy = d.getConstantState() == null ? d : d.getConstantState().newDrawable().mutate();
        android.graphics.Rect bounds = copy.copyBounds();
        try {
            copy.setBounds(0, 0, size, size);
            copy.draw(new Canvas(b));
            int[] px = new int[size * size];
            b.getPixels(px, 0, size, 0, 0, size, size);
            return px;
        } catch (RuntimeException | LinkageError e) { return null; }
        finally { copy.setBounds(bounds); b.recycle(); }
    }

    /** Reject opaque backgrounds and circular full-disc icons (no useful alpha silhouette). */
    static boolean silhouette(int[] pixels) {
        if (pixels == null || pixels.length == 0) return false;
        int clear = 0, covered = 0;
        for (int c : pixels) { if ((c >>> 24) < 26) clear++; if ((c >>> 24) >= 128) covered++; }
        return clear * 100 >= pixels.length * 15 && covered * 100 >= pixels.length * 3
                && covered * 100 <= pixels.length * 65;
    }

    /** High-confidence exact-source comparison: uncertain matches leave user icon packs alone. */
    static boolean sameOfficial(int[] shown, int[] official) {
        if (shown == null || official == null || shown.length != official.length) return false;
        long difference = 0;
        for (int i = 0; i < shown.length; i++) {
            int a = shown[i], b = official[i];
            difference += Math.abs((a >>> 24) - (b >>> 24));
            difference += Math.abs((a >> 16 & 255) - (b >> 16 & 255));
            difference += Math.abs((a >> 8 & 255) - (b >> 8 & 255));
            difference += Math.abs((a & 255) - (b & 255));
        }
        return difference <= shown.length * 4L * 5L;
    }

    void restore() {
        if (entry == null && bound.get() == null && active.isEmpty() && !pending) return;
        revision++;
        for (Lease lease : active) lease.restore();
        active.clear(); candidates.clear();
        View root = bound.get();
        if (root != null) root.removeOnLayoutChangeListener(layoutObserver);
        candidatesDirty = false;
        bound.clear(); entry = null; notificationIdentity = null; pkg = null;
        selectedMode = -1; configRevision = -1; resolved = null; pending = false;
    }
}
