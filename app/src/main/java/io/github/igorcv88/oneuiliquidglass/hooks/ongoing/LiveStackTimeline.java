package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import android.content.res.ColorStateList;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import java.lang.reflect.Method;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;

/**
 * Opt-in, READ-ONLY timeline of the first 500 ms of a DZIF expanded CardStackView.
 *
 * Enable independently with: setprop debug.oulg.stackprobe 1
 * No screenshot, notification text, package, media content, key, bitmap or RemoteViews is read.
 * No view/drawable properties are written, no drawable is wrapped and no draw() is intercepted.
 * Samples run on the main thread with a hard per-burst/visit budget; disabled costs only a
 * cached property check on existing card callbacks. The Android RenderThread may reuse a display
 * list without a Java redraw, so an unchanged snapshot is NOT proof that pixels did not change.
 */
final class LiveStackTimeline {
    private static final long[] TICKS = {0, 16, 32, 48, 64, 96, 128, 176, 256, 352, 480};
    private static final long COOLDOWN_MS = 620;
    private static final int MAX_CARDS = 4;
    // The expanded action0 is 10-12 levels below the Samsung card root on DZIF.
    private static final int MAX_DEPTH = 16;
    private static final int MAX_VISITS = 200;
    private static final int MAX_LINES_PER_SAMPLE = 72;
    private static final String STACK = LiveCardHooks.STACK;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final WeakHashMap<View, Burst> bursts = new WeakHashMap<>();
    private long lastPropertyCheck = -1;
    private boolean enabled;
    private int serial;

    private static final class Burst {
        final WeakReference<View> stack;
        final long start;
        final int id;
        final String reason;
        final Map<Integer, String> previous = new HashMap<>();
        Burst(View stack, long start, int id, String reason) {
            this.stack = new WeakReference<>(stack);
            this.start = start;
            this.id = id;
            this.reason = reason;
        }
    }

    /** Existing callbacks call this only for a classified expanded CARD, never for CHIP or media. */
    void begin(View stack, String reason) {
        if (stack == null || !STACK.equals(stack.getClass().getName()) || !stack.isAttachedToWindow()
                || !propertyEnabled()) return;
        long now = SystemClock.uptimeMillis();
        Burst last = bursts.get(stack);
        if (last != null && now - last.start < COOLDOWN_MS) return;
        Burst burst = new Burst(stack, now, ++serial, reason);
        bursts.put(stack, burst);
        Probe.log("LIVE_STACK_TIMELINE_BEGIN", "burst=" + burst.id + " reason=" + reason
                + " stack=" + id(stack) + " cards=" + ((ViewGroup) stack).getChildCount());
        for (long tick : TICKS) main.postDelayed(() -> sample(burst, tick), tick);
        main.postDelayed(() -> finish(burst), 520);
    }

    private boolean propertyEnabled() {
        long now = SystemClock.uptimeMillis();
        if (lastPropertyCheck >= 0 && now - lastPropertyCheck < 750) return enabled;
        lastPropertyCheck = now;
        try {
            Class<?> props = Class.forName("android.os.SystemProperties");
            Method get = props.getMethod("get", String.class);
            String value = (String) get.invoke(null, "debug.oulg.stackprobe");
            enabled = "1".equals(value) || "true".equalsIgnoreCase(value);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            enabled = false;
        }
        return enabled;
    }

    private void sample(Burst burst, long scheduled) {
        View v = burst.stack.get();
        if (!(v instanceof ViewGroup) || bursts.get(v) != burst || !v.isAttachedToWindow()) return;
        try {
            ViewGroup stack = (ViewGroup) v;
            long elapsed = SystemClock.uptimeMillis() - burst.start;
            int top = -1;
            Object index = NativeShape.call(stack, "getTopViewIndex");
            if (index instanceof Number) top = ((Number) index).intValue();
            int[] remaining = {MAX_VISITS, MAX_LINES_PER_SAMPLE, 0};
            Probe.log("LIVE_STACK_TIMELINE_TICK", "burst=" + burst.id + " planMs=" + scheduled
                    + " actualMs=" + elapsed + " children=" + stack.getChildCount()
                    + " topIndex=" + top + " stack=" + id(v));
            int n = Math.min(MAX_CARDS, stack.getChildCount());
            for (int i = 0; i < n && remaining[0] > 0; i++)
                walk(stack.getChildAt(i), "card" + i, 0, burst, remaining);
            Probe.log("LIVE_STACK_TIMELINE_END_TICK", "burst=" + burst.id + " ms=" + elapsed
                    + " nodes=" + remaining[2] + " left=" + remaining[0]
                    + " emitted=" + (MAX_LINES_PER_SAMPLE - remaining[1]));
        } catch (RuntimeException | LinkageError e) {
            Probe.log("LIVE_STACK_TIMELINE_ERROR", "burst=" + burst.id
                    + " type=" + e.getClass().getSimpleName());
        }
    }

    private void walk(View v, String route, int depth, Burst burst, int[] remaining) {
        if (v == null || depth > MAX_DEPTH || remaining[0]-- <= 0) return;
        remaining[2]++;
        Drawable bg = v.getBackground();
        String resource = resourceName(v);
        boolean selected = bg != null || v.getForeground() != null || v.getElevation() != 0f
                || resource.contains("action") || resource.contains("stack")
                || resource.contains("blur") || resource.contains("pip");
        if (selected && remaining[1] > 0) {
            String state = describe(v, resource, bg);
            int key = System.identityHashCode(v);
            String previous = burst.previous.put(key, state);
            if (!state.equals(previous)) {
                remaining[1]--;
                Probe.log("LIVE_STACK_TIMELINE_NODE", "burst=" + burst.id + " path=" + route
                        + " obj=" + id(v) + " " + state);
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup children = (ViewGroup) v;
            for (int i = 0; i < children.getChildCount() && remaining[0] > 0; i++)
                walk(children.getChildAt(i), route + "." + i, depth + 1, burst, remaining);
        }
    }

    static String describe(View v, String name, Drawable bg) {
        int[] xy = new int[2];
        v.getLocationOnScreen(xy);
        StringBuilder b = new StringBuilder("type=").append(v.getClass().getSimpleName())
                .append(" res=").append(name).append(" xy=").append(xy[0]).append(',').append(xy[1])
                .append(" wh=").append(v.getWidth()).append('x').append(v.getHeight())
                .append(" vis=").append(v.getVisibility()).append(" alpha=").append(v.getAlpha())
                .append(" pressed=").append(v.isPressed()).append(" selected=").append(v.isSelected())
                .append(" activated=").append(v.isActivated())
                .append(" tx=").append(v.getTranslationX()).append(" ty=").append(v.getTranslationY())
                .append(" tz=").append(v.getTranslationZ()).append(" sx=").append(v.getScaleX())
                .append(" sy=").append(v.getScaleY())
                .append(" elev=").append(v.getElevation()).append(" clip=").append(v.getClipToOutline())
                .append(" layer=").append(v.getLayerType())
                .append(" children=").append(v instanceof ViewGroup ? ((ViewGroup) v).getChildCount() : 0)
                .append(" shadowA=").append(shadow(v, true))
                .append(" shadowS=").append(shadow(v, false))
                .append(" bg=").append(drawable(bg))
                .append(" fg=").append(drawable(v.getForeground()));
        return b.toString();
    }

    private static String shadow(View view, boolean ambient) {
        try { return hex(ambient ? view.getOutlineAmbientShadowColor() : view.getOutlineSpotShadowColor()); }
        catch (RuntimeException | LinkageError e) { return "?"; }
    }

    static String drawable(Drawable background) {
        if (background == null) return "null";
        StringBuilder b = new StringBuilder();
        part(b, background);
        if (background instanceof RippleDrawable) {
            RippleDrawable ripple = (RippleDrawable) background;
            int n = Math.min(5, ripple.getNumberOfLayers());
            Rect hotspot = new Rect();
            ripple.getHotspotBounds(hotspot);
            b.append(" rippleLayers=").append(ripple.getNumberOfLayers())
                    .append(" hotspot=").append(hotspot.toShortString());
            for (int i = 0; i < n; i++) {
                Drawable layer = ripple.getDrawable(i);
                b.append(" [").append(i).append(" id=").append(ripple.getId(i))
                        .append(" mask=").append(ripple.getId(i) == android.R.id.mask).append(':');
                part(b, layer);
                b.append(']');
            }
        } else if (background instanceof LayerDrawable) {
            LayerDrawable layers = (LayerDrawable) background;
            for (int i = 0; i < Math.min(4, layers.getNumberOfLayers()); i++) {
                b.append(" [").append(i).append(" id=").append(layers.getId(i)).append(':');
                part(b, layers.getDrawable(i));
                b.append(']');
            }
        }
        return b.toString();
    }

    private static void part(StringBuilder b, Drawable d) {
        if (d == null) { b.append("null"); return; }
        b.append(d.getClass().getSimpleName()).append('@').append(id(d))
                .append(" alpha=").append(d.getAlpha())
                .append(" visible=").append(d.isVisible())
                .append(" opacity=").append(d.getOpacity())
                .append(" bounds=").append(d.getBounds().toShortString());
        Object filter = d.getColorFilter();
        b.append(" filter=").append(filter == null ? "null" : filter.getClass().getSimpleName() + '@' + id(filter));
        Drawable nativePart = NativeShape.unwrap(d);
        if (nativePart instanceof GradientDrawable) {
            ColorStateList colour = ((GradientDrawable) nativePart).getColor();
            if (colour != null) b.append(" fill=").append(hex(colour.getDefaultColor()));
        }
        try {
            Outline o = new Outline();
            d.getOutline(o);
            b.append(" outline=").append(o.isEmpty() ? "empty" : o.getAlpha() + "/" + o.getRadius());
        } catch (RuntimeException | LinkageError ignored) { b.append(" outline=?"); }
    }

    private static String resourceName(View v) {
        try { return v.getId() == View.NO_ID ? "-" : v.getResources().getResourceEntryName(v.getId()); }
        catch (RuntimeException ignored) { return "?"; }
    }
    private static String hex(int value) { return String.format(java.util.Locale.ROOT, "%08x", value); }
    private static String id(Object value) { return Integer.toHexString(System.identityHashCode(value)); }

    private void finish(Burst burst) {
        View stack = burst.stack.get();
        if (stack == null || bursts.get(stack) != burst) return;
        Probe.log("LIVE_STACK_TIMELINE_DONE", "burst=" + burst.id
                + " elapsedMs=" + (SystemClock.uptimeMillis() - burst.start)
                + " objects=" + burst.previous.size());
        // Keep the timestamp for the cooldown; avoid retaining stale view identities.
        burst.previous.clear();
    }
}
