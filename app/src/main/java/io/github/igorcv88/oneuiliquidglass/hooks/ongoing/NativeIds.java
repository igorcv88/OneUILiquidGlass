package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import android.content.res.Resources;
import android.view.View;
import android.view.ViewGroup;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Finds a Samsung view by its resource entry name without walking the tree with string lookups on
 * every call. The entry name is resolved to an int id once per Resources (a plugin's and
 * SystemUI's differ) and then found with {@link View#findViewById}, which only compares ints.
 *
 * <p>DZIF profile (04/11/2026, LSPosed ON): the Now Bar card's expanded-icon search walked the
 * whole card on every pre-draw and ran reflection on each node; it dominated SystemUI's main
 * thread. Resolution falls back to a single name walk (no reflection) when the id cannot be
 * resolved, and remembers the id that walk found.</p>
 */
final class NativeIds {
    private NativeIds() {}

    private static final Map<Resources, Map<String, Integer>> IDS = new WeakHashMap<>();
    // Re-inflated DZIF plugin roots can use an id belonging to another package. Recheck a
    // failed lookup at most once per root/name/750 ms, never walk the card every pre-draw.
    private static final Map<View, Map<String, Long>> MISSES = new WeakHashMap<>();
    private static final long MISS_RETRY_MS = 750;

    /** The first view under root (root included) whose id has this entry name, or null. Main thread. */
    static View find(View root, String entryName) {
        if (root == null) return null;
        int id = resolve(root, entryName);
        if (id != 0) {
            View direct = root.findViewById(id);
            if (direct != null) return direct;
        }
        // getIdentifier may resolve SystemUI's "icon" id while a notification RemoteViews
        // subtree contains android:id/icon. The resolved int must not suppress that fallback.
        long now = android.os.SystemClock.uptimeMillis();
        Map<String, Long> byName = MISSES.get(root);
        Long retry = byName == null ? null : byName.get(entryName);
        if (retry != null && now < retry) return null;
        View found = walk(root, entryName, 0);
        if (found != null) {
            remember(root.getResources(), entryName, found.getId());
            if (byName != null) byName.remove(entryName);
        } else {
            if (byName == null) { byName = new HashMap<>(); MISSES.put(root, byName); }
            byName.put(entryName, now + MISS_RETRY_MS);
        }
        return found;
    }

    private static int resolve(View root, String name) {
        Resources res = root.getResources();
        if (res == null) return 0;
        Map<String, Integer> byName = IDS.get(res);
        Integer cached = byName == null ? null : byName.get(name);
        if (cached != null) return cached;
        int id = 0;
        String pkg = packageOf(root);
        try { if (pkg != null) id = res.getIdentifier(name, "id", pkg); }
        catch (RuntimeException ignored) { id = 0; }
        if (id == 0) {
            String own = root.getContext() == null ? null : root.getContext().getPackageName();
            try { if (own != null && !own.equals(pkg)) id = res.getIdentifier(name, "id", own); }
            catch (RuntimeException ignored) { id = 0; }
        }
        // A miss is not cached: the walk below may still find the view and remember its id.
        if (id != 0) remember(res, name, id);
        return id;
    }

    /** The package of the first id found under root: a plugin view's resources are the plugin's. */
    private static String packageOf(View root) {
        View withId = firstWithId(root, 0);
        if (withId == null) return null;
        try { return withId.getResources().getResourcePackageName(withId.getId()); }
        catch (RuntimeException e) { return null; }
    }

    private static View firstWithId(View v, int depth) {
        if (v == null || depth > 6) return null;
        // A generated (non-resource) id makes getResourcePackageName throw; packageOf handles it.
        if (v.getId() != View.NO_ID) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View found = firstWithId(g.getChildAt(i), depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void remember(Resources res, String name, int id) {
        if (res == null || id == View.NO_ID || id == 0) return;
        Map<String, Integer> byName = IDS.get(res);
        if (byName == null) { byName = new HashMap<>(); IDS.put(res, byName); }
        byName.put(name, id);
    }

    /** Fallback only: resource names compared once per node, no reflection. */
    private static View walk(View v, String name, int depth) {
        if (v == null || depth > 16) return null;
        if (v.getId() != View.NO_ID) {
            try { if (name.equals(v.getResources().getResourceEntryName(v.getId()))) return v; }
            catch (RuntimeException ignored) { }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View found = walk(g.getChildAt(i), name, depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Tests: forget every resolved id. */
    static void clearForTest() { IDS.clear(); MISSES.clear(); }
}
