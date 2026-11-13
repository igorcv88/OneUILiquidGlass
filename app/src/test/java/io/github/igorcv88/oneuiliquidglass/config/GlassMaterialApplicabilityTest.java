package io.github.igorcv88.oneuiliquidglass.config;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

/** Which Clear and Frosted controls act for the material in force, and their dependencies. */
public class GlassMaterialApplicabilityTest {
    private static Applicability of(boolean lens, String... saved) {
        Map<String, String> s = new HashMap<>();
        for (int i = 0; i < saved.length; i += 2) s.put(saved[i], saved[i + 1]);
        Map<String, String> observed = lens ? Collections.singletonMap("sfrefract", "1") : Collections.<String, String>emptyMap();
        return new ControlState(s, observed, Collections.emptyMap(), true).applicability();
    }

    private static void assertKind(Applicability.Kind kind, Applicability a, String key) {
        assertEquals(key + " -> " + a.rule(key), kind, a.rule(key).kind);
    }

    @Test
    public void clearParametersNeedClearAndFrostedKnobsNeedFrosted() {
        Applicability frosted = of(true);
        assertKind(Applicability.Kind.INACTIVE, frosted, "sf.hcfold");
        assertKind(Applicability.Kind.INACTIVE, frosted, "sf.kcedge");
        assertKind(Applicability.Kind.NEEDS_RESTART, frosted, "sf.core");
        assertKind(Applicability.Kind.NEEDS_RESTART, frosted, "sf.kgcore");
        Applicability clear = of(true, "sf.huclear", "1", "sf.kgclear", "1");
        assertKind(Applicability.Kind.NEEDS_RESTART, clear, "sf.hcfold");
        assertKind(Applicability.Kind.NEEDS_RESTART, clear, "sf.kcedge");
        assertKind(Applicability.Kind.INACTIVE, clear, "sf.core");
        assertKind(Applicability.Kind.INACTIVE, clear, "sf.kgcore");
        assertKind(Applicability.Kind.NEEDS_RESTART, clear, "sf.hurefract");
        assertKind(Applicability.Kind.NEEDS_RESTART, clear, "sf.kgrim");
    }

    @Test
    public void theReasonsNameTheMaterial() {
        assertEquals(Applicability.NEEDS_CLEAR, of(true).rule("sf.hcfold").reason);
        assertEquals(Applicability.NEEDS_FROSTED, of(true, "sf.huclear", "1").rule("sf.core").reason);
    }

    @Test
    public void eachProfileFollowsItsOwnSelector() {
        Applicability a = of(true, "sf.huclear", "1", "sf.kgclear", "0");
        assertKind(Applicability.Kind.NEEDS_RESTART, a, "sf.hcfold");
        assertKind(Applicability.Kind.INACTIVE, a, "sf.kcfold");
        assertKind(Applicability.Kind.INACTIVE, a, "sf.core");
        assertKind(Applicability.Kind.NEEDS_RESTART, a, "sf.kgcore");
    }

    @Test
    public void perCardKeepsBothGroupsActive() {
        Applicability a = of(true, "sf.huclear", "2");
        assertKind(Applicability.Kind.NEEDS_RESTART, a, "sf.hcfold");
        assertKind(Applicability.Kind.NEEDS_RESTART, a, "sf.core");
    }

    @Test
    public void withoutTheLensClearCannotBeApplied() {
        Applicability a = of(false, "sf.huclear", "1", "sf.kgclear", "1");
        assertKind(Applicability.Kind.INACTIVE, a, "sf.hcfold");
        assertEquals("Usado com a lente dinâmica", a.rule("sf.hcfold").reason);
    }

    @Test
    public void edgeAmplitudeOnlyActsWithTheMeniscusOff() {
        Applicability off = of(true, "sf.huclear", "1", "sf.kgclear", "1");
        assertKind(Applicability.Kind.NEEDS_RESTART, off, "sf.hcamp");
        assertKind(Applicability.Kind.NEEDS_RESTART, off, "sf.kcamp");
        Applicability on = of(true, "sf.huclear", "1", "sf.kgclear", "1", "sf.hcfold", "2", "sf.kcfold", "0.5");
        assertKind(Applicability.Kind.INACTIVE, on, "sf.hcamp");
        assertKind(Applicability.Kind.INACTIVE, on, "sf.kcamp");
        assertEquals(Applicability.NEEDS_MENISCUS_OFF, on.rule("sf.hcamp").reason);
        // Each profile has its own meniscus.
        Applicability hu = of(true, "sf.huclear", "1", "sf.kgclear", "1", "sf.hcfold", "2");
        assertKind(Applicability.Kind.INACTIVE, hu, "sf.hcamp");
        assertKind(Applicability.Kind.NEEDS_RESTART, hu, "sf.kcamp");
    }

    @Test
    public void whiteNeutralisingNeedsTheMaterialBlock() {
        String[] clear = {"sf.huclear", "1", "sf.kgclear", "1"};
        assertKind(Applicability.Kind.INACTIVE, of(true, clear), "sf.hcneutral");
        assertEquals(Applicability.NEEDS_MATERIAL_BLOCK, of(true, clear).rule("sf.hcneutral").reason);
        assertKind(Applicability.Kind.NEEDS_RESTART, of(true, concat(clear, "sf.hcsat", "1.05")), "sf.hcneutral");
        assertKind(Applicability.Kind.NEEDS_RESTART, of(true, concat(clear, "sf.hclift", "0.3")), "sf.hcneutral");
        assertKind(Applicability.Kind.NEEDS_RESTART, of(true, concat(clear, "sf.hcscrimmax", "0.2")), "sf.hcneutral");
        // The minimum darkening alone does not emit the block.
        assertKind(Applicability.Kind.INACTIVE, of(true, concat(clear, "sf.hcscrim", "0.2")), "sf.hcneutral");
        // Saturation back at exactly 1 is neutral.
        assertKind(Applicability.Kind.INACTIVE, of(true, concat(clear, "sf.hcsat", "1")), "sf.hcneutral");
    }

    private static String[] concat(String[] a, String... b) {
        String[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    @Test
    public void switchingMaterialKeepsEveryHiddenValue() {
        Map<String, String> saved = new HashMap<>();
        saved.put("sf.hcfold", "2");
        saved.put("sf.core", "50");
        for (String material : new String[] {"0", "1", "0", "1"}) {
            saved.put("sf.huclear", material);
            ControlState st = new ControlState(saved, Collections.singletonMap("sfrefract", "1"), Collections.emptyMap(), true);
            assertEquals("2", st.shown("sf.hcfold"));
            assertEquals("50", st.shown("sf.core"));
        }
    }

    @Test
    public void theGenericCompositorRuleStillAppliesToOtherCompositorKeys() {
        assertKind(Applicability.Kind.NEEDS_RESTART, of(true), "sf.hulight");
        assertKind(Applicability.Kind.INACTIVE, of(false), "sf.hulight");
        assertKind(Applicability.Kind.NEEDS_RESTART, of(true), "sf.qslight");
    }

    @Test
    public void everyClearControlIsReachableThroughSomeMaterialAndMode() {
        Applicability clear = of(true, "sf.huclear", "1", "sf.kgclear", "1", "sf.hclift", "0.2");
        for (String key : GlassMaterial.clearKeys()) {
            Applicability.Result r = clear.rule(key);
            if (key.equals("sf.hcamp") || key.equals("sf.kcamp")) continue; // depend on the meniscus, tested above
            assertTrue(key + " -> " + r, r.usable());
        }
    }
}
