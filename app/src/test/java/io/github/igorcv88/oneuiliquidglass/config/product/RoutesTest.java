package io.github.igorcv88.oneuiliquidglass.config.product;

import static org.junit.Assert.*;

import io.github.igorcv88.oneuiliquidglass.config.ControlState;
import io.github.igorcv88.oneuiliquidglass.config.Controls;
import io.github.igorcv88.oneuiliquidglass.config.product.ProductLayout.Route;
import io.github.igorcv88.oneuiliquidglass.config.state.ConfirmedSnapshot;
import io.github.igorcv88.oneuiliquidglass.config.state.Snapshots;
import java.util.*;
import org.junit.Test;

/** The layout route is derived from confirmed values only, never from a draft. */
public class RoutesTest {
    private static final Controls.Surface HU = Controls.Surface.HEADSUP, KG = Controls.Surface.KEYGUARD,
            SHADE = Controls.Surface.SHADE;

    private static Map<String, String> map(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private static ConfirmedSnapshot snap(Map<String, String> saved, Map<String, String> observed) {
        return Snapshots.of(saved, observed, Collections.emptyMap(), true, false);
    }

    @Test
    public void standardWhenTheLensIsNotLoaded() {
        assertEquals(Route.STANDARD, Routes.of(snap(map(), map()), HU));
    }

    @Test
    public void captureAndPlainBlurFollowTheBackdropKeys() {
        assertEquals(Route.CAPTURE, Routes.of(snap(map("backdrop", "capture"), map()), HU));
        assertEquals(Route.CAPTURE, Routes.of(snap(map("liveblur", "0", "backdrop", "hybrid"), map()), HU));
        assertEquals(Route.PLAIN_BLUR, Routes.of(snap(map("liveblur", "0", "backdrop", "off"), map()), KG));
    }

    @Test
    public void lensRoutesFollowTheSurfaceMaterial() {
        Map<String, String> lens = map("sfrefract", "1");
        assertEquals(Route.LENS_FROSTED, Routes.of(snap(map(), lens), HU));
        assertEquals(Route.LENS_CLEAR, Routes.of(snap(map("sf.huclear", "1"), lens), HU));
        assertEquals(Route.LENS_FROSTED, Routes.of(snap(map("sf.huclear", "1"), lens), KG));
        assertEquals(Route.LENS_CLEAR, Routes.of(snap(map("sf.kgclear", "1"), lens), KG));
    }

    @Test
    public void perCardSelectorIsMixed() {
        assertEquals(Route.LENS_MIXED, Routes.of(snap(map("sf.huclear", "2"), map("sfrefract", "1")), HU));
    }

    @Test
    public void shadeHasNoMaterialAndUsesTheFrostedRouteUnderTheLens() {
        ConfirmedSnapshot clear = snap(map("sf.huclear", "1", "sf.kgclear", "1"), map("sfrefract", "1"));
        assertEquals(Route.LENS_FROSTED, Routes.of(clear, SHADE));
        assertEquals(Route.STANDARD, Routes.of(snap(map(), map()), SHADE));
    }

    @Test
    public void nextContextShowsTheLensRouteWhileTheLensIsNotLoaded() {
        ConfirmedSnapshot s = snap(map("sf.huclear", "1"), map());
        assertEquals(Route.STANDARD, Routes.of(s, HU));
        assertEquals(Route.LENS_CLEAR, Routes.of(s.nextReader(), HU));
    }

    @Test
    public void aDraftHasNoWayIntoTheRoute() {
        ConfirmedSnapshot s = snap(map("sf.huclear", "1"), map("sfrefract", "1"));
        ControlState withDraft = new ControlState(s.saved, s.observed, map("sf.huclear", "0"), true);
        ConfirmedSnapshot fromDraftState = withDraft.snapshot(null, Collections.emptyMap(), false, 1);
        assertEquals(Route.LENS_CLEAR, Routes.of(fromDraftState, HU));
    }
}
