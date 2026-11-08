package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import android.content.res.ColorStateList;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.VectorDrawable;
import android.view.View;
import android.widget.ImageView;
import java.lang.ref.WeakReference;

/** The eligible Now Bar's native AdaptiveIconDrawable already contains a monochrome glyph. */
final class NowBarIconLease {
    interface InstalledIconSource {
        Drawable resolve(ImageView target, String packageName) throws PackageManager.NameNotFoundException;
    }

    private final InstalledIconSource installedIconSource;

    NowBarIconLease() {
        this((target, packageName) -> target.getContext().getPackageManager().getApplicationIcon(packageName));
    }

    NowBarIconLease(InstalledIconSource installedIconSource) {
        this.installedIconSource = installedIconSource;
    }

    private WeakReference<ImageView> image = new WeakReference<>(null);
    // The ImageView stops owning the native drawable as soon as the monochrome glyph replaces it.
    // Keep it strongly for the active lease; only views/card references remain weak.
    private Drawable original;
    private String activePackage;
    private WeakReference<Drawable> applied = new WeakReference<>(null);
    private WeakReference<View> disc = new WeakReference<>(null);
    private ColorStateList nativeDisc, nativeTint;
    private Drawable nativeIconBackground;
    private int nativeIconBackgroundAlpha;
    /** DZIF has a SEPARATE MaterialCardView for the icon in the expanded Now Bar. */
    private WeakReference<View> expandedDisc = new WeakReference<>(null);
    private ColorStateList nativeExpandedDisc;
    private long generation, nextSample;
    private Boolean lightBackdrop;
    private int ink = Color.WHITE;
    private WeakReference<ImageView> rejectedView = new WeakReference<>(null);
    private WeakReference<Drawable> rejectedDrawable = new WeakReference<>(null);
    private String rejectedPackage;

    private void contrast(ImageView target) {
        long now = android.os.SystemClock.uptimeMillis();
        if (!target.isAttachedToWindow() || !target.isHardwareAccelerated() || !target.isShown()
                || target.getWidth() == 0 || now < nextSample) return;
        nextSample = now + 1000;
        long epoch = generation;
        io.github.igorcv88.oneuiliquidglass.glass.ShadeTone.measure(target, luma -> {
            ImageView current = image.get();
            if (epoch != generation || current != target || !Double.isFinite(luma)
                    || !current.isAttachedToWindow() || current.getDrawable() != applied.get()) return;
            boolean light = io.github.igorcv88.oneuiliquidglass.glass.ShadeTone.lightBackdrop((float) luma, lightBackdrop);
            applyTone(light);
        });
    }

    void applyTone(boolean light) {
        lightBackdrop = light;
        int color = light ? Color.BLACK : Color.WHITE;
        Drawable glyph = applied.get();
        if (glyph != null && color != ink) {
            ink = color;
            glyph.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
            ImageView target = image.get();
            if (target != null) target.invalidate();
        }
    }

    ImageView target() { return image.get(); }

    void sync(ImageView target, boolean glass) { sync(target, glass, null); }

    /** The plugin can flatten an app's launcher into BitmapDrawable/VectorDrawable. When it
     * does, try the installed package's official monochrome adaptive layer. Never tint the
     * flattened bitmap itself: that would turn a colourful square into a solid square. */
    void sync(ImageView target, boolean glass, String packageName) {
        if (!glass || target == null) { restore(); return; }
        if (image.get() == target && target.getDrawable() == applied.get() && applied.get() != null
                && java.util.Objects.equals(activePackage, packageName)) {
            if (target.getImageTintList() != null) {
                nativeTint = target.getImageTintList();
                target.setImageTintList(null);
            }
            transparentDisc(target);
            transparentIconBackground(target);
            contrast(target);
            return;
        }
        restore();
        Drawable nativeIcon = target.getDrawable();
        if (nativeIcon == null) return; // A blank Samsung placeholder must remain blank.
        if (rejectedView.get() == target && rejectedDrawable.get() == nativeIcon
                && java.util.Objects.equals(rejectedPackage, packageName)) return;
        Drawable glyph = nativeIcon instanceof AdaptiveIconDrawable
                ? ((AdaptiveIconDrawable) nativeIcon).getMonochrome() : null;
        boolean fromApp = false;
        if ((glyph == null || glyph.getConstantState() == null) && packageName != null) {
            try {
                Drawable launcher = installedIconSource.resolve(target, packageName);
                if (launcher instanceof AdaptiveIconDrawable) {
                    glyph = ((AdaptiveIconDrawable) launcher).getMonochrome();
                    fromApp = true;
                }
            } catch (PackageManager.NameNotFoundException | RuntimeException ignored) { }
        }
        // DZIF's Clock ongoing header supplies a genuine vector small-icon glyph and
        // a separate opaque background on NotificationRowIconView. Only this verified
        // native notification view may use the vector itself as a monochrome mask.
        if ((glyph == null || glyph.getConstantState() == null) && nativeNotificationVector(target, nativeIcon))
            glyph = nativeIcon;
        if (glyph == null || glyph.getConstantState() == null) {
            rejectedView = new WeakReference<>(target);
            rejectedDrawable = new WeakReference<>(nativeIcon);
            rejectedPackage = packageName;
            return;
        }
        rejectedView.clear();
        rejectedDrawable.clear();
        rejectedPackage = null;
        Drawable mono;
        try {
            mono = (fromApp ? glyph.getConstantState().newDrawable()
                    : glyph.getConstantState().newDrawable(target.getResources())).mutate();
        } catch (RuntimeException | LinkageError ignored) { return; }
        if (io.github.igorcv88.oneuiliquidglass.diagnostics.Probe.trace && fromApp)
            io.github.igorcv88.oneuiliquidglass.diagnostics.Probe.log("NOWBAR_ICON_FALLBACK",
                    "pkg=" + packageName + " native="
                            + (nativeIcon == null ? "null" : nativeIcon.getClass().getSimpleName())
                            + " source=installedMonochrome");
        mono.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        image = new WeakReference<>(target);
        original = nativeIcon;
        activePackage = packageName;
        applied = new WeakReference<>(mono);
        nativeTint = target.getImageTintList();
        target.setImageTintList(null);
        target.setImageDrawable(mono);
        transparentDisc(target);
        transparentIconBackground(target);
        contrast(target);
    }

    static boolean nativeNotificationVector(ImageView target, Drawable drawable) {
        if (!(drawable instanceof VectorDrawable) || target == null) return false;
        try {
            String id = target.getResources().getResourceEntryName(target.getId());
            // These two exact Samsung IDs carry small, single-ink symbols even on plain
            // ImageView; never tint media art, progress bars or arbitrary app vectors.
            if ("ongoing_activity_secondary_info_icon".equals(id)
                    || "ongoing_activity_expand_right_image".equals(id)) return true;
            if (!"com.android.internal.widget.NotificationRowIconView".equals(
                    target.getClass().getName())) return false;
            return "ongoing_activity_expand_left_image".equals(id)
                    || "ongoing_activity_collapsed_icon".equals(id)
                    || "icon".equals(id);
        } catch (RuntimeException ignored) { return false; }
    }

    /** Remove only the icon ImageView's own native disc; preserve the drawable for restore. */
    private void transparentIconBackground(ImageView target) {
        Drawable current = target.getBackground();
        if (current != nativeIconBackground) {
            restoreIconBackground();
            nativeIconBackground = current;
            nativeIconBackgroundAlpha = current == null ? 255 : current.getAlpha();
        }
        if (current != null && current.getAlpha() != 0) current.mutate().setAlpha(0);
    }

    private void restoreIconBackground() {
        if (nativeIconBackground != null && nativeIconBackground.getAlpha() == 0)
            nativeIconBackground.setAlpha(nativeIconBackgroundAlpha);
        nativeIconBackground = null;
    }

    private void transparentDisc(ImageView target) {
        Object parent = target.getParent();
        if (!(parent instanceof View)) return;
        View container = (View) parent;
        View saved = disc.get();
        if (saved != container && !NativeShape.cardLike(container)) return;
        if (saved != null && saved != container) restoreDisc();
        Object color = NativeShape.call(container, "getCardBackgroundColor");
        if (!(color instanceof ColorStateList)) return;
        if (((ColorStateList) color).getDefaultColor() == Color.TRANSPARENT) return;
        nativeDisc = (ColorStateList) color;
        disc = new WeakReference<>(container);
        cardColor(container, ColorStateList.valueOf(Color.TRANSPARENT));
    }

    /** Clear only the expanded icon container's coloured fill; leave its icon/children native. */
    void syncExpandedDisc(View container, boolean glass) {
        if (!glass || container == null || !NativeShape.cardLike(container)) {
            restoreExpandedDisc();
            return;
        }
        if (expandedDisc.get() != container) restoreExpandedDisc();
        Object nativeValue = NativeShape.call(container, "getCardBackgroundColor");
        if (!(nativeValue instanceof ColorStateList)) return;
        ColorStateList current = (ColorStateList) nativeValue;
        if (current.getDefaultColor() == Color.TRANSPARENT) return;
        // Store the latest Samsung colour; it may be rewritten when expanding or switching mode.
        nativeExpandedDisc = current;
        expandedDisc = new WeakReference<>(container);
        cardColor(container, ColorStateList.valueOf(Color.TRANSPARENT));
    }

    private void restoreExpandedDisc() {
        View target = expandedDisc.get();
        if (target != null && nativeExpandedDisc != null) {
            Object current = NativeShape.call(target, "getCardBackgroundColor");
            if (current instanceof ColorStateList &&
                    ((ColorStateList) current).getDefaultColor() == Color.TRANSPARENT)
                cardColor(target, nativeExpandedDisc);
        }
        expandedDisc.clear();
        nativeExpandedDisc = null;
    }

    void restore() {
        generation++;
        nextSample = 0;
        lightBackdrop = null;
        ink = Color.WHITE;
        ImageView target = image.get();
        if (target != null && target.getDrawable() == applied.get() && original != null) {
            target.setImageDrawable(original);
        }
        // Tint belongs to the ImageView even if Samsung replaced its drawable.
        if (target != null && target.getImageTintList() == null) target.setImageTintList(nativeTint);
        nativeTint = null;
        restoreDisc();
        restoreExpandedDisc();
        restoreIconBackground();
        image.clear();
        original = null;
        activePackage = null;
        applied.clear();
    }

    private void restoreDisc() {
        View container = disc.get();
        if (container != null && nativeDisc != null) {
            Object current = NativeShape.call(container, "getCardBackgroundColor");
            if (current instanceof ColorStateList && ((ColorStateList) current).getDefaultColor() == Color.TRANSPARENT)
                cardColor(container, nativeDisc);
        }
        disc.clear();
        nativeDisc = null;
    }

    private static void cardColor(View card, ColorStateList color) {
        try { card.getClass().getMethod("setCardBackgroundColor", ColorStateList.class).invoke(card, color); }
        catch (ReflectiveOperationException | RuntimeException ignored) { }
    }
}
