package io.github.igorcv88.oneuiliquidglass.config;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;
import io.github.igorcv88.oneuiliquidglass.Config;
import io.github.igorcv88.oneuiliquidglass.live.CompositorValues;
import io.github.igorcv88.oneuiliquidglass.live.SfLive;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * Clear Glass compositor properties (docs/clear-glass/INTEGRATION_CONTRACT.md): registered with the
 * domains the library accepts, editable in the app (the two selectors only through the glass
 * material) and live-writable, while the compiled-mode marker sf.clear stays read-only.
 */
@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 33)
public class ClearGlassRegistryTest {
    /** key -> {type, default, min, max}; the contract's table. */
    private static final Map<String, String[]> WRITABLE_SPECS = new LinkedHashMap<>();

    static {
        spec("sf.huclear", "INT", "0", "0", "2");
        spec("sf.kgclear", "INT", "0", "0", "2");
        for (String p : new String[] {"sf.hc", "sf.kc"}) {
            spec(p + "band", "FLOAT", "0.85", "0.42", "1");
            spec(p + "amp", "FLOAT", "0.40", "0", "0.5");
            spec(p + "body", "FLOAT", p.equals("sf.hc") ? "0.06" : "0.05", "0", "0.15");
            spec(p + "body2", "FLOAT", "0", "0", "0.05");
            spec(p + "edge", "FLOAT", "4", "0", "48");
            spec(p + "core", "FLOAT", "24", "0", "48");
            spec(p + "blurexp", "FLOAT", "2", "1", "4");
        }
        spec("sf.hcfold", "FLOAT", "0", "0", "3");
        spec("sf.hcsat", "FLOAT", "1", "0.9", "2");
        spec("sf.hcscrim", "FLOAT", "0", "0", "0.6");
        spec("sf.hcscrimmax", "FLOAT", "0", "0", "0.8");
        spec("sf.hcadapt", "FLOAT", "0", "0", "1");
        spec("sf.kcfold", "FLOAT", "0", "0", "3");
        spec("sf.hclift", "FLOAT", "0", "0", "2");
        spec("sf.kclift", "FLOAT", "0", "0", "2");
        spec("sf.hcneutral", "FLOAT", "0.75", "0", "1");
        spec("sf.kcveil", "FLOAT", "0.04", "0", "0.2");
        spec("sf.kclevel", "FLOAT", "0.85", "0.1", "0.85");
        spec("sf.kcdim", "FLOAT", "0", "0", "0.3");
        spec("sf.kcknee", "FLOAT", "0", "0", "0.6");
        spec("sf.kcsat", "FLOAT", "1", "0.9", "1.3");
        spec("sf.kccool", "FLOAT", "0.50", "0", "2");
    }

    private static void spec(String key, String type, String def, String min, String max) {
        WRITABLE_SPECS.put(key, new String[] {type, def, min, max});
    }

    private static Path root() {
        Path p = Paths.get("").toAbsolutePath();
        while (p != null && !Files.isRegularFile(p.resolve("sfhook/refract.h"))) p = p.getParent();
        assertNotNull(p);
        return p;
    }

    @Test
    public void everyClearPropertyIsRegisteredWithTheContractDomain() {
        assertEquals(31, WRITABLE_SPECS.size());
        for (Map.Entry<String, String[]> e : WRITABLE_SPECS.entrySet()) {
            SettingSpec s = SettingsRegistry.get(e.getKey());
            String[] want = e.getValue();
            assertNotNull(e.getKey(), s);
            assertEquals(e.getKey(), SettingSpec.Type.valueOf(want[0]), s.type);
            assertEquals(e.getKey(), want[1], s.defaultValue);
            assertEquals(e.getKey(), Double.parseDouble(want[2]), s.min, 0);
            assertEquals(e.getKey(), Double.parseDouble(want[3]), s.max, 0);
            assertEquals(e.getKey(), SettingSpec.ApplyMode.SF_RECOMPILE, s.applyMode);
            assertNotNull(e.getKey(), s.validate(s.defaultValue));
        }
    }

    /** The registry's domains are the library's: every oulg_in(...) range in oulg_fix_clear. */
    @Test
    public void domainsMatchTheLibraryValidator() throws Exception {
        String h = new String(Files.readAllBytes(root().resolve("sfhook/refract.h")), StandardCharsets.UTF_8);
        int start = h.indexOf("static void oulg_fix_clear(");
        String fix = h.substring(start, h.indexOf("\n}\n", start));
        Matcher m = Pattern.compile("c->(\\w+)(\\[i\\])? = oulg_in\\(c->\\w+(?:\\[i\\])?, ([0-9.]+)f, ([0-9.]+)f").matcher(fix);
        int checked = 0;
        while (m.find()) {
            // Per-profile arrays are hc/kc pairs; the scalar fields are the lockscreen material, except
            // the heads-up meniscus and material (fold, hsat, scrim, scrimmax).
            String name = m.group(1);
            boolean headsUpOnly = name.equals("neutral") || name.equals("hsat") || name.equals("scrim") || name.equals("scrimmax")
                    || name.equals("adapt");
            String[] keys = m.group(2) != null
                    ? new String[] {"sf.hc" + name, "sf.kc" + name}
                    : headsUpOnly ? new String[] {"sf.hc" + (name.equals("hsat") ? "sat" : name)}
                    : new String[] {"sf.kc" + name};
            for (String key : keys) {
                SettingSpec s = SettingsRegistry.get(key);
                assertNotNull(key, s);
                assertEquals(key, Double.parseDouble(m.group(3)), s.min, 1e-9);
                assertEquals(key, Double.parseDouble(m.group(4)), s.max, 1e-9);
                checked++;
            }
        }
        assertEquals(29, checked);
        assertTrue(fix.contains("c->mode[i] != 1 && c->mode[i] != 2"));
    }

    @Test
    public void outOfRangeAndNonNumericValuesAreRejected() {
        for (Map.Entry<String, String[]> e : WRITABLE_SPECS.entrySet()) {
            SettingSpec s = SettingsRegistry.get(e.getKey());
            double min = Double.parseDouble(e.getValue()[2]), max = Double.parseDouble(e.getValue()[3]);
            for (String bad : new String[] {
                    String.valueOf(min - .01), String.valueOf(max + .01), "NaN", "Infinity", "-Infinity",
                    "1;reboot", "$(id)", "", "on"}) {
                try {
                    s.validate(bad);
                    fail(e.getKey() + " accepted " + bad);
                } catch (IllegalArgumentException expected) {
                }
            }
        }
        for (String mode : new String[] {"sf.huclear", "sf.kgclear"}) {
            SettingSpec s = SettingsRegistry.get(mode);
            for (String ok : new String[] {"0", "1", "2"}) assertEquals(ok, s.validate(ok));
            for (String bad : new String[] {"3", "-1", "1.5"})
                try {
                    s.validate(bad);
                    fail(mode + " accepted " + bad);
                } catch (IllegalArgumentException expected) {
                }
        }
    }

    @Test
    public void clearPropertiesAreControlsAndLiveWritableExceptTheTwoSelectors() {
        for (String key : WRITABLE_SPECS.keySet()) {
            Controls.Control c = Controls.get(key);
            assertNotNull(key, c);
            assertTrue(key, SfLive.WRITABLE.contains(key.substring(3)));
            assertTrue(key, CompositorValues.writable(key));
            boolean selector = key.equals("sf.huclear") || key.equals("sf.kgclear");
            // The selectors are chosen through the glass material, never as an ordinary row.
            if (selector) assertEquals(key, Controls.Tier.INTERNAL, c.tier);
            else assertNotEquals(key, Controls.Tier.INTERNAL, c.tier);
            assertEquals(key, !selector, Controls.editable(key));
            assertNotEquals(key, SettingSpec.Type.READONLY, SettingsRegistry.get(key).type);
        }
        assertFalse(Controls.editable("sf.clear"));
        assertFalse(SfLive.WRITABLE.contains("clear"));
        assertFalse(CompositorValues.writable("sf.clear"));
    }

    @Test
    public void compositorValuesAreCanonicalisedToWhatTheHelperAccepts() {
        SettingSpec amp = SettingsRegistry.get("sf.hcamp");
        assertEquals("0.3", amp.validate("0.30000000000000004"));
        assertEquals("0.123457", amp.validate("0.1234567"));
        for (SettingSpec s : SettingsRegistry.all()) {
            if (!s.key.startsWith("sf.") || s.type != SettingSpec.Type.FLOAT) continue;
            assertTrue(s.key, s.validate(String.valueOf(s.max)).length() <= 12);
        }
    }

    @Test
    public void defaultsLeaveClearGlassOff() {
        assertEquals("0", SettingsRegistry.get("sf.huclear").defaultValue);
        assertEquals("0", SettingsRegistry.get("sf.kgclear").defaultValue);
    }

    /** Absent means unknown: the marker has no default state and no value is ever accepted. */
    @Test
    public void compiledModeMarkerIsReadOnly() throws Exception {
        SettingSpec s = SettingsRegistry.get("sf.clear");
        assertEquals(SettingSpec.Type.READONLY, s.type);
        assertEquals(SettingSpec.ApplyMode.READONLY, s.applyMode);
        assertEquals("", s.defaultValue);
        for (Map<String, String> observed : java.util.Arrays.asList(
                Collections.<String, String>emptyMap(), Collections.singletonMap("sfrefract", "1")))
            assertEquals(Applicability.Kind.READ_ONLY,
                    new ControlState(Collections.emptyMap(), observed, Collections.emptyMap(), true)
                            .applicability().rule("sf.clear").kind);
        for (String v : new String[] {"hu:0,kg:0", "hu:1,kg:2", "0", ""})
            try {
                s.validate(v);
                fail("sf.clear accepted " + v);
            } catch (IllegalArgumentException expected) {
            }
        PresetCodec.Parsed imported = PresetCodec.parse(
                "{\"schemaVersion\":1,\"enabled\":false,\"desired\":{\"sf.clear\":\"hu:1,kg:1\",\"lens\":\"0.24\"}}");
        assertFalse(imported.values.containsKey("sf.clear"));
        assertTrue(imported.unknown.contains("sf.clear (somente leitura)"));
        assertEquals(Collections.singletonMap("lens", "0.24"), imported.values);
    }

    @Test
    public void repositoryRefusesTheMarkerAndNeverExportsCompositorKeys() {
        Context context = RuntimeEnvironment.getApplication();
        SharedPreferences prefs = Config.open(context);
        prefs.edit().clear().putString("desired.sf.clear", "hu:1,kg:1").commit();
        try {
            ConfigRepository repo = new ConfigRepository(context);
            try {
                repo.save(SettingsRegistry.get("sf.clear"), "hu:1,kg:1");
                fail("save accepted sf.clear");
            } catch (IllegalArgumentException expected) {
            }
            try {
                repo.replace(Collections.singletonMap("sf.clear", "hu:1,kg:1"), false);
                fail("replace accepted sf.clear");
            } catch (IllegalArgumentException expected) {
            }
            assertFalse(repo.overrides().containsKey("sf.clear"));
            assertFalse(repo.snapshotValues().overrides.containsKey("sf.clear"));
        } finally {
            prefs.edit().clear().commit();
        }
    }
}
