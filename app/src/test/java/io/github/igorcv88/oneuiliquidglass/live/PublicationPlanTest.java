package io.github.igorcv88.oneuiliquidglass.live;

import static org.junit.Assert.*;

import io.github.igorcv88.oneuiliquidglass.config.Applicability;
import io.github.igorcv88.oneuiliquidglass.config.ControlState;
import io.github.igorcv88.oneuiliquidglass.config.state.ConfirmedSnapshot;
import java.util.*;
import org.junit.Test;

/**
 * The eighteen transitions of docs/OULG_UI_PLAN/EFFECTIVE_STATE_CONTRACT.md section 8, plus the
 * non-pop-up cases listed under it. Rules are always evaluated on the target configuration, never
 * on drafts.
 */
public class PublicationPlanTest {
    private static Map<String, String> map(String... kv) {
        Map<String, String> m = new TreeMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private static Map<String, String> plus(Map<String, String> base, String... kv) {
        Map<String, String> m = new TreeMap<>(base);
        m.putAll(map(kv));
        return m;
    }

    private static final Map<String, String> LENS = map("sfrefract", "1");
    /** Clear pop-up on the original Gaussian kernel 1; adaptive blur keeps its default (1). */
    private static final Map<String, String> CLEAR_K1 = plus(LENS, "sf.huclear", "1", "sf.hckernel", "1");
    private static final Map<String, String> CLEAR = plus(LENS, "sf.huclear", "1");

    private static ConfirmedSnapshot snap(Map<String, String> saved, Map<String, String> observed, Map<String, String> compiled) {
        return new ConfirmedSnapshot(saved, observed, compiled, true, true, false);
    }

    private static PublicationPlan plan(ConfirmedSnapshot s) throws PlanContradiction {
        return PublicationPlan.of(s, Applicability::new);
    }

    private static Set<String> keys(String... k) {
        return new TreeSet<>(Arrays.asList(k));
    }

    // 1
    @Test
    public void case1_savedValueOfAnInactiveKeyIsNeverPublishedAndStaysDeferred() throws Exception {
        PublicationPlan p = plan(snap(map("sf.hubloom", "1"), plus(CLEAR_K1, "sf.hubloom", "0"), plus(CLEAR_K1, "sf.hubloom", "0")));
        assertTrue(p.writes.isEmpty());
        assertTrue(p.activeDrift.isEmpty());
        assertTrue(p.inactiveDrift.isEmpty());
        assertEquals(keys("sf.hubloom"), p.deferred);
        assertFalse(p.needsCompile());
        assertTrue(p.listed().isEmpty());
    }

    // 2
    @Test
    public void case2_anInactiveValueAlreadyPublishedAndCompiledIsNotPending() throws Exception {
        Map<String, String> o = plus(CLEAR_K1, "sf.hubloom", "1");
        PublicationPlan p = plan(snap(map("sf.hubloom", "1"), o, o));
        assertTrue(p.writes.isEmpty());
        assertTrue(p.activeDrift.isEmpty());
        assertTrue(p.inactiveDrift.isEmpty());
        assertTrue(p.deferred.isEmpty());
        assertFalse(p.needsCompile());
    }

    // 3
    @Test
    public void case3_driftOfAnInactiveKeyIsReportedNotQueued() throws Exception {
        Map<String, String> o = plus(CLEAR_K1, "sf.hubloom", "1");
        PublicationPlan p = plan(snap(map("sf.hubloom", "1"), o, plus(CLEAR_K1, "sf.hubloom", "0")));
        assertTrue(p.writes.isEmpty());
        assertTrue(p.activeDrift.isEmpty());
        assertEquals(keys("sf.hubloom"), p.inactiveDrift);
        assertFalse("no restart for a value the kernel ignores", p.needsCompile());
    }

    // 4
    @Test
    public void case4_theLatentValueReturnsWhenTheKernelChangesBack() throws Exception {
        Map<String, String> o = plus(CLEAR_K1, "sf.hubloom", "0");
        PublicationPlan p = plan(snap(map("sf.hckernel", "0", "sf.hubloom", "1"), o, o));
        assertEquals(map("sf.hckernel", "0", "sf.hubloom", "1"), p.writes);
        assertTrue(p.deferred.isEmpty());
    }

    // 5
    @Test
    public void case5_theLatentValueReturnsWhenAdaptiveBlurTurnsOffAndDependentsLeave() throws Exception {
        Map<String, String> o = plus(CLEAR_K1, "sf.hubloom", "0");
        PublicationPlan p = plan(snap(map("sf.hcadapt", "0", "sf.hubloom", "1", "sf.hchlneutral", "0.5"), o, o));
        assertEquals(map("sf.hcadapt", "0", "sf.hubloom", "1"), p.writes);
        assertFalse("hchlneutral needs adaptive blur", p.writes.containsKey("sf.hchlneutral"));
        assertEquals(keys("sf.hchlneutral"), p.deferred);
    }

    // 6
    @Test
    public void case6_draftsNeverReachThePlan() throws Exception {
        Map<String, String> saved = map("sf.hclift", "0.4");
        ControlState plain = new ControlState(saved, CLEAR, Collections.emptyMap(), true);
        ControlState dragging = plain.withDraft("sf.huclear", "0").withDraft("sf.hclift", "1.9");
        PublicationPlan a = plan(plain.snapshot(null, false)), b = plan(dragging.snapshot(null, false));
        assertEquals(a.writes, b.writes);
        assertEquals(a.fingerprint, b.fingerprint);
        assertEquals(map("sf.hclift", "0.4"), b.writes);
    }

    // 7
    @Test
    public void case7_clearToFrostedPublishesTheSelectorsAndFrostedOnlyKeysNotClearOnes() throws Exception {
        Map<String, String> saved = map("sf.huclear", "0", "sf.kgclear", "0", "sf.core", "60", "sf.kgcore", "90", "sf.hcsat", "1.3");
        Map<String, String> o = plus(LENS, "sf.huclear", "1", "sf.kgclear", "1");
        PublicationPlan p = plan(snap(saved, o, o));
        assertEquals(map("sf.huclear", "0", "sf.kgclear", "0", "sf.core", "60", "sf.kgcore", "90"), p.writes);
        assertEquals(keys("sf.hcsat"), p.deferred);
    }

    // 8
    @Test
    public void case8_frostedToClearLeavesTheFrostedOnlyKeyOutOfTheWrites() throws Exception {
        PublicationPlan p = plan(snap(map("sf.huclear", "1", "sf.core", "60"), LENS, LENS));
        assertEquals(map("sf.huclear", "1"), p.writes);
        assertEquals(keys("sf.core"), p.deferred);
    }

    // 9
    @Test
    public void case9_anExternalValueOfAnActiveKeyIsDriftNeedingACompile() throws Exception {
        Map<String, String> compiled = plus(CLEAR, "sf.hcsat", "1.05");
        PublicationPlan p = plan(snap(Collections.emptyMap(), plus(CLEAR, "sf.hcsat", "1.2"), compiled));
        assertTrue(p.writes.isEmpty());
        assertEquals(map("sf.hcsat", "1.2"), p.activeDrift);
        assertTrue(p.external.contains("sf.hcsat"));
        assertTrue(p.needsCompile());
        assertEquals(map("sf.hcsat", "1.2"), p.listed());
    }

    // 10
    @Test
    public void case10_withoutABaselineThereIsNoDriftAndTheBaselineIsFlaggedUnknown() throws Exception {
        PublicationPlan p = plan(snap(map("sf.hclift", "0.4"), plus(CLEAR, "sf.hcsat", "1.2"), null));
        assertTrue(p.unknownBaseline);
        assertTrue(p.activeDrift.isEmpty());
        assertEquals(map("sf.hclift", "0.4"), p.writes);
    }

    // 11
    @Test
    public void case11_theMeniscusParentDecidesTheChildOnlyByItsFinalValue() throws Exception {
        Map<String, String> o = plus(CLEAR, "sf.hcfold", "2");
        PublicationPlan off = plan(snap(map("sf.hcfold", "0", "sf.hcamp", "0.3"), o, o));
        assertEquals(map("sf.hcfold", "0", "sf.hcamp", "0.3"), off.writes);
        PublicationPlan back = plan(snap(map("sf.hcfold", "2", "sf.hcamp", "0.3"), o, o));
        assertTrue("fold back at 2 equals what is read", back.writes.isEmpty());
        assertEquals(keys("sf.hcamp"), back.deferred);
        PublicationPlan three = plan(snap(map("sf.hcfold", "3", "sf.hcamp", "0.3"), o, o));
        assertEquals(map("sf.hcfold", "3"), three.writes);
    }

    // 12
    @Test
    public void case12_aSurfaceOrMasterSwitchOffDoesNotChangeThePublication() throws Exception {
        Map<String, String> on = map("sf.hclift", "0.4");
        Map<String, String> off = map("sf.hclift", "0.4", "surface.headsup.enabled", "0");
        assertEquals(plan(snap(on, CLEAR, CLEAR)).writes, plan(snap(off, CLEAR, CLEAR)).writes);
        ConfirmedSnapshot disabled = new ConfirmedSnapshot(on, CLEAR, CLEAR, false, true, false);
        assertEquals(map("sf.hclift", "0.4"), plan(disabled).writes);
    }

    // 13
    @Test
    public void case13_anOrdinaryRestartKeyIsAWrite() throws Exception {
        PublicationPlan p = plan(snap(map("sf.hclift", "0.4"), plus(CLEAR, "sf.hclift", "0.3"), CLEAR));
        assertEquals(map("sf.hclift", "0.4"), p.writes);
        assertTrue(p.needsCompile());
    }

    // 14
    @Test
    public void case14_aTerminalChangeWhileTheDialogIsOpenChangesTheFingerprint() throws Exception {
        Map<String, String> compiled = plus(CLEAR, "sf.hcsat", "1.05");
        String before = plan(snap(map("sf.hclift", "0.4"), plus(CLEAR, "sf.hcsat", "1.05"), compiled)).fingerprint;
        String same = plan(snap(map("sf.hclift", "0.4"), plus(CLEAR, "sf.hcsat", "1.05"), compiled)).fingerprint;
        String after = plan(snap(map("sf.hclift", "0.4"), plus(CLEAR, "sf.hcsat", "1.2"), compiled)).fingerprint;
        assertEquals(before, same);
        assertNotEquals(before, after);
        assertTrue(before.matches("[0-9a-f]{40}"));
    }

    @Test
    public void theFingerprintCoversValuesNotOnlyKeys() throws Exception {
        assertNotEquals(plan(snap(map("sf.hclift", "0.4"), CLEAR, CLEAR)).fingerprint,
                plan(snap(map("sf.hclift", "0.5"), CLEAR, CLEAR)).fingerprint);
        assertNotEquals(plan(snap(map("sf.hclift", "0.4"), CLEAR, CLEAR)).fingerprint,
                plan(snap(Collections.emptyMap(), CLEAR, CLEAR)).fingerprint);
    }

    // 15
    @Test
    public void case15_aFailedRestartLeavesThePendingListUnchanged() throws Exception {
        Map<String, String> saved = map("sf.hclift", "0.4");
        PublicationPlan before = plan(new ConfirmedSnapshot(saved, CLEAR, CLEAR, true, true, false));
        PublicationPlan awaiting = plan(new ConfirmedSnapshot(saved, CLEAR, CLEAR, true, true, true));
        assertEquals(before.writes, awaiting.writes);
        assertEquals(before.fingerprint, awaiting.fingerprint);
    }

    // 16
    @Test
    public void case16_aContradictoryRuleSetIsReportedNotLooped() {
        // The target the rules see flips the kernel with the value of sf.hubloom in the writes,
        // so sf.hubloom is active only while it is absent: no fixed point exists.
        Map<String, String> o = plus(CLEAR, "sf.hubloom", "0");
        try {
            PublicationPlan.of(snap(map("sf.hubloom", "1"), o, o),
                    value -> new Applicability(k -> k.equals("sf.hckernel")
                            ? ("1".equals(value.apply("sf.hubloom")) ? "1" : "0")
                            : value.apply(k)));
            fail("expected a contradiction");
        } catch (PlanContradiction e) {
            assertEquals(keys("sf.hubloom"), e.keys);
        }
    }

    // 17
    @Test
    public void case17_anImportedLatentValueIsSavedAndNotWritten() throws Exception {
        PublicationPlan p = plan(snap(map("sf.hubloom", "1", "sf.hclift", "0.4"), plus(CLEAR_K1, "sf.hubloom", "0"), plus(CLEAR_K1, "sf.hubloom", "0")));
        assertEquals(map("sf.hclift", "0.4"), p.writes);
        assertEquals(keys("sf.hubloom"), p.deferred);
    }

    // 18
    @Test
    public void case18_undoRestoresThePreviousList() throws Exception {
        Map<String, String> before = map("sf.hclift", "0.4");
        Map<String, String> after = map("sf.huclear", "1", "sf.hclift", "0.4");
        PublicationPlan a = plan(snap(before, CLEAR, CLEAR));
        PublicationPlan changed = plan(snap(after, LENS, LENS));
        assertNotEquals(a.fingerprint, changed.fingerprint);
        assertEquals(a.fingerprint, plan(snap(before, CLEAR, CLEAR)).fingerprint);
    }

    // beyond the pop-up
    @Test
    public void theLockscreenMeniscusAndVeilRulesBehaveLikeThePopUpOnes() throws Exception {
        Map<String, String> o = plus(LENS, "sf.kgclear", "1");
        PublicationPlan p = plan(snap(map("sf.kcamp", "0.2", "sf.kclevel", "0.5", "sf.kcveil", "0"), o, o));
        assertEquals(map("sf.kcveil", "0"), p.writes);
        assertEquals(keys("sf.kcamp", "sf.kclevel"), p.deferred);
        PublicationPlan veil = plan(snap(map("sf.kclevel", "0.5"), o, o));
        assertEquals(map("sf.kclevel", "0.5"), veil.writes);
    }

    @Test
    public void theShadeTonemapCapsNeedTheLensToneMap() throws Exception {
        PublicationPlan adaptive = plan(snap(map("sf.shadecap", "0.6"), LENS, LENS));
        assertTrue("shadetone defaults to 3", adaptive.writes.isEmpty());
        assertEquals(keys("sf.shadecap"), adaptive.deferred);
        PublicationPlan lensMap = plan(snap(map("shadetone", "1", "sf.shadecap", "0.6"), LENS, LENS));
        assertEquals(map("sf.shadecap", "0.6"), lensMap.writes);
    }

    @Test
    public void liveKeysAreNeverPublished() throws Exception {
        Applicability rules = new Applicability(k -> k.equals("kgblurpath") ? "0" : k.equals("sfrefract") ? "1" : null);
        assertEquals(Applicability.Kind.INACTIVE, rules.rule("kgblurradius").kind);
        PublicationPlan p = plan(snap(map("kgblurpath", "0", "kgblurradius", "20", "qs.hideoffdiscs", "1", "qs.tilelightoff", "999"), LENS, LENS));
        assertTrue("only compositor keys are written", p.writes.isEmpty());
    }

    @Test
    public void anExternalValueWithoutASavedOneIsListedAsExternal() throws Exception {
        PublicationPlan p = plan(snap(map("sf.hclift", "0.4"), plus(CLEAR, "sf.hcsat", "1.2", "liveradius", "70"), CLEAR));
        assertTrue(p.external.contains("sf.hcsat"));
        assertTrue(p.external.contains("liveradius"));
        assertFalse(p.external.contains("sf.hclift"));
        assertFalse("the helper marker is not an external edit", p.external.contains("sfrefract"));
    }

    @Test
    public void planFieldsAreImmutable() throws Exception {
        PublicationPlan p = plan(snap(map("sf.hclift", "0.4"), CLEAR, CLEAR));
        try {
            p.writes.put("sf.x", "1");
            fail();
        } catch (UnsupportedOperationException expected) {
            // expected
        }
        try {
            p.deferred.add("x");
            fail();
        } catch (UnsupportedOperationException expected) {
            // expected
        }
    }
}
