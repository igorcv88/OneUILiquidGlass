package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import android.graphics.Outline;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;

/**
 * E1: read-only inspection of the siblings and parents OUTSIDE CardStackView.
 *
 * Candidate hypothesis: a separate native "stack peeking/shadow indicator" under the translucent
 * active card. The node is not presumed to exist. Traversal stops at OngoingCardBackgroundContainer
 * or after three ancestor levels. No View/drawable mutations, no draw interceptions, no text read.
 */
final class LiveStackExternalProbe {
    private static final int MAX_VISITS = 56;
    private static final int MAX_LINES = 42;
    private static final int MAX_DEPTH = 6;
    private static final int MAX_SIBLINGS = 12;
    private static final int MAX_ANCESTORS = 4;
    private static boolean methodsListed;

    private LiveStackExternalProbe() {}

    static void sample(View stack, int burst, long elapsed, Map<Integer, String> previous) {
        if (stack == null) return;
        try {
            listDrawingMethods(stack.getClass());
            int[] budget = {MAX_VISITS, MAX_LINES, 0, 0};
            int targets = 0;
            boolean foundLayout = false, foundContainer = false;
            View childOnPath = stack;
            for (int ancestor = 0; ancestor < MAX_ANCESTORS; ancestor++) {
                ViewParent p = childOnPath.getParent();
                if (!(p instanceof View)) break;
                View parent = (View) p;
                String resource = resourceName(parent);
                String name = parent.getClass().getSimpleName();
                boolean layout = "ongoing_card_stack_and_shadow_layout".equals(resource);
                boolean container = name.contains("OngoingCardBackgroundContainer");
                boolean inspect = ancestor == 0 || layout || container;
                if (layout) foundLayout = true;
                if (container) foundContainer = true;
                if (inspect) {
                    targets++;
                    scan(parent, "parent" + ancestor, 0, burst, previous, budget);
                    if (parent instanceof ViewGroup) {
                        ViewGroup group = (ViewGroup) parent;
                        for (int i = 0; i < Math.min(MAX_SIBLINGS, group.getChildCount())
                                && budget[0] > 0; i++) {
                            View sibling = group.getChildAt(i);
                            if (sibling == childOnPath) continue;
                            scanTree(sibling, "parent" + ancestor + ".sibling" + i, 0,
                                    burst, previous, budget);
                        }
                    }
                }
                if (container) break;
                childOnPath = parent;
            }
            Probe.log("LIVE_STACK_EXTERNAL_TICK", "burst=" + burst + " ms=" + elapsed
                    + " inspectedParents=" + targets
                    + " foundLayout=" + foundLayout + " foundContainer=" + foundContainer
                    + " visits=" + budget[2] + " changed=" + budget[3]
                    + " quotaExhausted=" + (budget[0] <= 0 || budget[1] <= 0));
        } catch (RuntimeException | LinkageError error) {
            Probe.log("LIVE_STACK_EXTERNAL_ERROR", "burst=" + burst
                    + " class=" + error.getClass().getSimpleName());
        }
    }

    private static void scanTree(View view, String path, int depth, int burst,
                                 Map<Integer, String> previous, int[] budget) {
        if (view == null || depth > MAX_DEPTH || budget[0] <= 0) return;
        scan(view, path, depth, burst, previous, budget);
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount() && budget[0] > 0; i++)
            scanTree(group.getChildAt(i), path + "." + i, depth + 1,
                    burst, previous, budget);
    }

    private static void scan(View view, String path, int depth, int burst,
                             Map<Integer, String> previous, int[] budget) {
        if (budget[0]-- <= 0) return;
        budget[2]++;
        String state = describe(view);
        int key = System.identityHashCode(view);
        String old = previous.put(key, state);
        if (!state.equals(old) && budget[1]-- > 0) {
            budget[3]++;
            Probe.log("LIVE_STACK_EXTERNAL_NODE", "burst=" + burst
                    + " path=" + path + " obj=" + Integer.toHexString(key)
                    + " " + state);
        }
    }

    /** Log metadata and geometry only. Never read content or call custom draw(Canvas). */
    static String describe(View v) {
        String base = LiveStackTimeline.describe(v, resourceName(v), v.getBackground());
        StringBuilder out = new StringBuilder(base)
                .append(" z=").append(v.getZ());
        Rect clip = v.getClipBounds();
        out.append(" clipBounds=").append(clip == null ? "-" : clip.toShortString());
        ViewOutlineProvider provider = v.getOutlineProvider();
        out.append(" provider=").append(provider == null ? "null" : provider.getClass().getName());
        if (provider != null) {
            try {
                Outline outline = new Outline();
                provider.getOutline(v, outline);
                out.append(" providerOutline=")
                        .append(outline.isEmpty() ? "empty"
                                : outline.getAlpha() + "/" + outline.getRadius());
            } catch (RuntimeException | LinkageError ex) {
                out.append(" providerOutline=?");
            }
        }
        return out.toString();
    }

    static String resourceName(View v) {
        try {
            return v.getId() == View.NO_ID ? "-" : v.getResources().getResourceEntryName(v.getId());
        } catch (RuntimeException ex) {
            return "?";
        }
    }

    /** Once per SystemUI process; only signature names, never invoke methods. */
    private static synchronized void listDrawingMethods(Class<?> stackClass) {
        if (methodsListed) return;
        methodsListed = true;
        int emitted = 0;
        for (Method method : stackClass.getDeclaredMethods()) {
            if (Modifier.isSynthetic(method.getModifiers())) continue;
            String name = method.getName();
            String low = name.toLowerCase(java.util.Locale.ROOT);
            if (!(low.contains("draw") || low.contains("shadow") || low.contains("peek")
                    || low.contains("dummy") || low.contains("behind"))) continue;
            if (++emitted > 45) break;
            Probe.log("LIVE_STACK_NATIVE_METHOD", "owner=" + stackClass.getName()
                    + " method=" + name + " params=" + method.getParameterCount());
        }
        Probe.log("LIVE_STACK_NATIVE_METHOD_END", "owner=" + stackClass.getName()
                + " count=" + emitted);
    }
}
