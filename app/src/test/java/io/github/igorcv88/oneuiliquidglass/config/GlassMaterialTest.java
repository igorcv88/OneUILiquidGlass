package io.github.igorcv88.oneuiliquidglass.config;

import static org.junit.Assert.*;

import io.github.igorcv88.oneuiliquidglass.live.SfLive;
import java.util.*;
import java.util.function.Function;
import org.junit.Test;

/** The glass material: what a choice stands for, and which controls act on which material. */
public class GlassMaterialTest {
    private static Function<String, String> values(String hu, String kg) {
        Map<String, String> m = new HashMap<>();
        if (hu != null) m.put("sf.huclear", hu);
        if (kg != null) m.put("sf.kgclear", kg);
        return m::get;
    }

    @Test
    public void aMaterialIsBothSelectorsTogetherAndNothingElse() {
        assertEquals(selection("0", "0"), GlassMaterial.FROSTED.selection());
        assertEquals(selection("1", "1"), GlassMaterial.CLEAR.selection());
        for (GlassMaterial m : GlassMaterial.values())
            for (String key : m.selection().keySet()) {
                assertNotNull(key, SettingsRegistry.get(key));
                assertTrue(key, SfLive.WRITABLE.contains(key.substring(3)));
            }
    }

    private static Map<String, String> selection(String hu, String kg) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("sf.huclear", hu);
        m.put("sf.kgclear", kg);
        return m;
    }

    @Test
    public void selectorValuesMapToMaterialsAndPerCardStaysOutside() {
        assertEquals(GlassMaterial.FROSTED, GlassMaterial.ofSelector("0"));
        assertEquals(GlassMaterial.CLEAR, GlassMaterial.ofSelector("1"));
        assertEquals(GlassMaterial.CLEAR, GlassMaterial.ofSelector(" 1.0 "));
        assertNull("per card is advanced and never chosen here", GlassMaterial.ofSelector("2"));
        assertNull(GlassMaterial.ofSelector(""));
        assertNull(GlassMaterial.ofSelector("on"));
        assertNull(GlassMaterial.ofSelector(null));
    }

    @Test
    public void theChoiceReadsBothSelectorsAndUnsetMeansTheLibraryDefault() {
        assertEquals(GlassMaterial.FROSTED, GlassMaterial.of(values(null, null)));
        assertEquals(GlassMaterial.CLEAR, GlassMaterial.of(values("1", "1")));
        assertNull("different materials per profile are set outside this choice", GlassMaterial.of(values("1", "0")));
        assertNull(GlassMaterial.of(values("2", "2")));
    }

    @Test
    public void eachSurfaceIsDecidedByItsOwnSelectorAndTheShadeByNone() {
        Function<String, String> mixed = values("1", "0");
        assertEquals(GlassMaterial.CLEAR, GlassMaterial.forSurface(mixed, Controls.Surface.HEADSUP));
        assertEquals(GlassMaterial.FROSTED, GlassMaterial.forSurface(mixed, Controls.Surface.KEYGUARD));
        assertNull(GlassMaterial.forSurface(mixed, Controls.Surface.SHADE));
    }

    // ---- classification

    private static final List<String> SHARED_THAT_STILL_ACT_ON_CLEAR = Arrays.asList(
            "sf.hurefract", "sf.hublur", "sf.taps", "sf.hulight", "sf.hubloom",
            "sf.kgrefract", "sf.kgblur", "sf.kgtaps", "sf.kglight", "sf.kgdepth", "sf.kgglow",
            "sf.kgrim", "sf.kgspec", "sf.kgline", "sf.kgtilt", "sf.kgfixedrim");

    @Test
    public void sharedKnobsThatStillActOnClearAreCommon() {
        for (String key : SHARED_THAT_STILL_ACT_ON_CLEAR) {
            assertEquals(key, GlassMaterial.Scope.COMMON, GlassMaterial.scope(key));
            assertTrue(key, GlassMaterial.CLEAR.acts(key));
            assertTrue(key, GlassMaterial.FROSTED.acts(key));
        }
    }

    @Test
    public void replacedOptics11KnobsActOnlyOnFrosted() {
        for (String key : Arrays.asList("sf.core", "sf.edgeblur", "sf.ramp", "sf.hubodylens",
                "sf.kgcore", "sf.kgedgeblur", "sf.kgcorecap", "sf.kgramp", "sf.kgblurexp",
                "sf.kgband", "sf.kgbodylens", "sf.kgsat", "sf.kgtone", "sf.kgflat", "sf.kglevel",
                "sf.kgcool", "sf.kgflatcap", "sf.kgcoredim")) {
            assertEquals(key, GlassMaterial.Scope.FROSTED_ONLY, GlassMaterial.scope(key));
            assertTrue(key, GlassMaterial.FROSTED.acts(key));
            assertFalse(key, GlassMaterial.CLEAR.acts(key));
        }
    }

    @Test
    public void everyClearParameterIsClearOnlyAndBelongsToOneProfile() {
        List<String> clear = GlassMaterial.clearKeys();
        assertEquals(29, clear.size());
        assertEquals(clear.size(), new HashSet<>(clear).size());
        for (String key : clear) {
            assertEquals(key, GlassMaterial.Scope.CLEAR_ONLY, GlassMaterial.scope(key));
            assertTrue(key, GlassMaterial.CLEAR.acts(key));
            assertFalse(key, GlassMaterial.FROSTED.acts(key));
            Controls.Control c = Controls.get(key);
            assertNotNull(key, c);
            assertTrue(key + " is a control", Controls.editable(key));
            // A heads-up key is never an adjustment of the lock screen, and the reverse.
            Set<Controls.Surface> expected = EnumSet.of(key.startsWith("sf.hc")
                    ? Controls.Surface.HEADSUP : Controls.Surface.KEYGUARD);
            assertEquals(key, expected, c.surfaces);
        }
    }

    @Test
    public void clearParametersAreExactlyTheRegistryKeysOfBothProfiles() {
        Set<String> registry = new TreeSet<>();
        for (SettingSpec s : SettingsRegistry.all())
            if (s.key.startsWith("sf.hc") || s.key.startsWith("sf.kc")) registry.add(s.key);
        assertEquals(registry, new TreeSet<>(GlassMaterial.clearKeys()));
    }

    @Test
    public void everyKnobTheClearLibraryReadsIsInTheLiveWritableList() {
        for (String key : GlassMaterial.clearKeys()) assertTrue(key, SfLive.WRITABLE.contains(key.substring(3)));
        assertTrue(SfLive.WRITABLE.contains("huclear"));
        assertTrue(SfLive.WRITABLE.contains("kgclear"));
        assertFalse("the compiled-mode marker is read only", SfLive.WRITABLE.contains("clear"));
    }

    // ---- visibility

    private static boolean visible(String key, Controls.Surface s, String hu, String kg) {
        return GlassMaterial.visible(key, s, values(hu, kg));
    }

    @Test
    public void frostedHidesClearParametersAndKeepsEverythingElse() {
        for (Controls.Surface s : new Controls.Surface[] {Controls.Surface.HEADSUP, Controls.Surface.KEYGUARD}) {
            assertFalse(visible("sf.hcfold", s, "0", "0"));
            assertFalse(visible("sf.kclift", s, "0", "0"));
            assertTrue(visible("sf.hublur", s, "0", "0"));
            assertTrue(visible("liveradius", s, "0", "0"));
        }
        assertTrue(visible("sf.core", Controls.Surface.HEADSUP, "0", "0"));
        assertTrue(visible("sf.kgcore", Controls.Surface.KEYGUARD, "0", "0"));
    }

    @Test
    public void clearHidesFrostedOnlyKnobsAndKeepsTheSharedOnes() {
        assertFalse(visible("sf.core", Controls.Surface.HEADSUP, "1", "1"));
        assertFalse(visible("sf.kgcore", Controls.Surface.KEYGUARD, "1", "1"));
        assertTrue(visible("sf.hcfold", Controls.Surface.HEADSUP, "1", "1"));
        assertTrue(visible("sf.kcfold", Controls.Surface.KEYGUARD, "1", "1"));
        assertTrue(visible("sf.hurefract", Controls.Surface.HEADSUP, "1", "1"));
        assertTrue(visible("sf.kgglow", Controls.Surface.KEYGUARD, "1", "1"));
    }

    @Test
    public void theShadeIsNeverFiltered() {
        for (String m : new String[] {"0", "1", "2"}) {
            assertTrue(visible("sf.shadecap", Controls.Surface.SHADE, m, m));
            assertTrue(visible("sf.qslight", Controls.Surface.SHADE, m, m));
            assertTrue(visible("sf.core", Controls.Surface.SHADE, m, m));
            assertTrue(visible("sf.hcfold", Controls.Surface.SHADE, m, m));
        }
    }

    @Test
    public void aPerCardSelectorKeepsBothGroupsVisible() {
        assertTrue(visible("sf.hcfold", Controls.Surface.HEADSUP, "2", "0"));
        assertTrue(visible("sf.core", Controls.Surface.HEADSUP, "2", "0"));
    }

    @Test
    public void profilesAreFilteredIndependently() {
        assertTrue(visible("sf.hcfold", Controls.Surface.HEADSUP, "1", "0"));
        assertFalse(visible("sf.kcfold", Controls.Surface.KEYGUARD, "1", "0"));
        assertTrue(visible("sf.kgcore", Controls.Surface.KEYGUARD, "1", "0"));
    }

    // ---- recommended configuration

    @Test
    public void theRecommendedSettingsAreTheReviewedLookAndValidForTheRegistry() {
        Map<String, String> r = GlassMaterial.recommendedClear();
        assertEquals("0.42", r.get("sf.hcband"));
        assertEquals("2", r.get("sf.hcfold"));
        assertEquals("24", r.get("sf.hcedge"));
        assertEquals("24", r.get("sf.hccore"));
        assertEquals("1", r.get("sf.hcadapt"));
        assertEquals("1.05", r.get("sf.hcsat"));
        assertEquals("2", r.get("sf.kcfold"));
        assertEquals("0.42", r.get("sf.kcband"));
        assertEquals("24", r.get("sf.kcedge"));
        assertEquals("24", r.get("sf.kccore"));
        // Brightening and the white neutraliser are not part of the reviewed look.
        assertFalse(r.containsKey("sf.hclift"));
        assertFalse(r.containsKey("sf.kclift"));
        assertFalse(r.containsKey("sf.hcneutral"));
        for (Map.Entry<String, String> e : r.entrySet()) {
            SettingSpec spec = SettingsRegistry.get(e.getKey());
            assertEquals(e.getKey(), e.getValue(), spec.validate(e.getValue()));
            assertEquals(e.getKey(), GlassMaterial.Scope.CLEAR_ONLY, GlassMaterial.scope(e.getKey()));
        }
        // Not the library defaults: a user who marks Clear without it gets the neutral slab.
        assertNotEquals(SettingsRegistry.get("sf.hcfold").defaultValue, r.get("sf.hcfold"));
    }

    @Test
    public void presentValuesAreTheOnesSetInTheAppOrOnTheDeviceNotTheDefaults() {
        assertTrue(GlassMaterial.clearValuesPresent(Collections.emptyMap(), Collections.emptyMap()).isEmpty());
        Map<String, String> saved = Collections.singletonMap("sf.hcfold", "2");
        Map<String, String> observed = new HashMap<>();
        observed.put("sf.kcedge", "24");
        observed.put("sf.hcsat", "");
        observed.put("sf.core", "50"); // not a Clear parameter
        assertEquals(new TreeSet<>(Arrays.asList("sf.hcfold", "sf.kcedge")),
                new TreeSet<>(GlassMaterial.clearValuesPresent(saved, observed).keySet()));
        assertEquals(Collections.singletonMap("sf.kcedge", "24"),
                GlassMaterial.clearValuesOnlyOnDevice(saved, observed));
        // The app's own value wins over what the device reports.
        assertTrue(GlassMaterial.clearValuesOnlyOnDevice(Collections.singletonMap("sf.kcedge", "10"), observed).isEmpty());
    }
}
