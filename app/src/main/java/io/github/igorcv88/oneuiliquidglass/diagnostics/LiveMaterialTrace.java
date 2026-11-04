package io.github.igorcv88.oneuiliquidglass.diagnostics;

import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewParent;
import io.github.igorcv88.oneuiliquidglass.glass.BackgroundBlurBridge;
import io.github.igorcv88.oneuiliquidglass.glass.LiveGlassDrawable;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Read-only, bounded companion to the CARD stack timeline.
 *
 * Tracks submission of the Java blur draw command, NOT completion/visibility in SurfaceFlinger.
 * No draw hooks, framebuffer reads, view changes or compositor intervention. It is enabled
 * only while LiveStackTimeline has opened a debug.oulg.stackprobe burst on a real CardStackView.
 * An unchanged or absent draw event does not prove that pixels are unchanged: RenderThread may
 * replay a previously recorded display list.
 */
public final class LiveMaterialTrace {
    private LiveMaterialTrace() {}
    private static final long MAX_WINDOW_MS = 650L;
    private static final int MAX_EVENTS = 96;
    private static final int MAX_DRAWS_PER_MATERIAL = 25;

    private static volatile Burst active;

    private static final class Last {
        String state;
        long lastDraw = -1000L;
        int draws;
    }

    private static final class Burst {
        final WeakReference<View> stack;
        final int id;
        final long started;
        final Map<LiveGlassDrawable, Last> materials = new WeakHashMap<>();
        int lines;
        Burst(View stack, int id, long started) {
            this.stack = new WeakReference<>(stack);
            this.id = id;
            this.started = started;
        }
    }

    /** Fast read for the hot draw path: disabled by default, no string allocation. */
    public static boolean isArmed() {
        Burst b = active;
        return b != null && SystemClock.uptimeMillis() - b.started <= MAX_WINDOW_MS;
    }

    /** Called only after LiveStackTimeline has checked the opt-in system property. */
    public static void begin(View stack, int id, long started) {
        if (stack == null) return;
        active = new Burst(stack, id, started);
    }

    /** True only if the material host still belongs to the stack sampled by this burst. */
    public static boolean belongsTo(View host, View stack) {
        if (host == null || stack == null) return false;
        for (Object current = host; current instanceof View;
                current = ((View) current).getParent()) {
            if (current == stack) return true;
        }
        return false;
    }

    /**
     * Event source: the existing owner and drawable, never Samsung's methods. "DRAW_*" means
     * a Java draw command returned; "BLUR_*" means the owner changed a bridge reference.
     */
    public static void note(View host, LiveGlassDrawable material, String phase, Canvas canvas) {
        Burst b = active;
        if (b == null) return;
        long time = SystemClock.uptimeMillis();
        if (time - b.started > MAX_WINDOW_MS) return;
        View stack = b.stack.get();
        if (!belongsTo(host, stack) || material == null || phase == null) return;
        try {
            synchronized (b) {
                if (active != b || b.lines >= MAX_EVENTS) return;
                Last last = b.materials.get(material);
                if (last == null) {
                    last = new Last();
                    b.materials.put(material, last);
                }
                boolean draw = phase.startsWith("DRAW_");
                if (draw) {
                    if (last.draws >= MAX_DRAWS_PER_MATERIAL || time - last.lastDraw < 12L) return;
                    last.draws++;
                    last.lastDraw = time;
                }
                int[] xy = new int[2];
                host.getLocationOnScreen(xy);
                BackgroundBlurBridge region = material.blur();
                Drawable blurDrawable = region == null ? null : region.drawable;
                Rect bounds = material.getBounds();
                int stackIndex = -1;
                try {
                    View stackChild = directChild(host, stack);
                    if (stack instanceof android.view.ViewGroup && stackChild != null)
                        stackIndex = ((android.view.ViewGroup) stack).indexOfChild(stackChild);
                } catch (RuntimeException ignored) { }
                String state = "phase=" + phase
                        + " host=" + id(host) + " material=" + id(material)
                        + " stack=" + id(stack) + " stackChild=" + stackIndex
                        + " xy=" + xy[0] + "," + xy[1]
                        + " wh=" + host.getWidth() + "x" + host.getHeight()
                        + " bounds=" + bounds.width() + "x" + bounds.height()
                        + " hostAlpha=" + host.getAlpha()
                        + " attached=" + host.isAttachedToWindow()
                        + " nativeHostBg=" + (host.getBackground() == material)
                        + " glass=" + material.showsGlass()
                        + " regionless=" + material.regionless()
                        + " fade=" + material.regionFadeForTrace()
                        + " blurPx=" + material.blurRadiusForTrace()
                        + " region=" + id(region)
                        + " regionDrawable=" + id(blurDrawable)
                        + " regionAlpha=" + (blurDrawable == null ? -1 : blurDrawable.getAlpha())
                        + " regionBounds=" + (blurDrawable == null ? "-" : blurDrawable.getBounds().toShortString())
                        + " hwCanvas=" + (canvas == null ? "-" : canvas.isHardwareAccelerated());
                if (!draw && state.equals(last.state)) return;
                last.state = state;
                b.lines++;
                Probe.log("LIVE_MATERIAL_FRAME", "burst=" + b.id
                        + " ms=" + (time - b.started) + " " + state);
            }
        } catch (RuntimeException | LinkageError e) {
            // No diagnostic failure is allowed to change a live notification's rendering.
        }
    }

    private static View directChild(View descendant, View stack) {
        for (Object current = descendant; current instanceof View;
                current = ((View) current).getParent()) {
            ViewParent parent = ((View) current).getParent();
            if (parent == stack) return (View) current;
            if (current == stack) break;
        }
        return null;
    }

    private static String id(Object o) {
        return o == null ? "null" : Integer.toHexString(System.identityHashCode(o));
    }
}
