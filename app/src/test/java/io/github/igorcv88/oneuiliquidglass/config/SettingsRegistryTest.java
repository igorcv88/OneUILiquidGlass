package io.github.igorcv88.oneuiliquidglass.config;

import static org.junit.Assert.*;

import org.junit.Test;

import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class SettingsRegistryTest {
    private String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), java.nio.charset.StandardCharsets.UTF_8);
    }

    private Path root() {
        Path p = Paths.get("").toAbsolutePath();
        while (p != null && !Files.isRegularFile(p.resolve("sfhook/oulg_sf.c"))) p = p.getParent();
        assertNotNull(p);
        return p;
    }

    @Test
    public void everyReaderKeyIsRegistered() throws Exception {
        Set<String> readers = new TreeSet<>();
        String java =
                read(
                        root().resolve(
                                        "app/src/main/java/io/github/igorcv88/oneuiliquidglass/glass/Tuning.java"));
        java += read(root().resolve("app/src/main/java/io/github/igorcv88/oneuiliquidglass/glass/QsStyle.java"));
        Matcher j = Pattern.compile("(?:number|prop|color)\\(\"([a-z0-9.]+)\"").matcher(java);
        while (j.find()) readers.add(j.group(1));
        Matcher c =
                Pattern.compile("debug\\.oulg\\.([a-z0-9.]+)")
                        .matcher(read(root().resolve("sfhook/oulg_sf.c")));
        while (c.find()) readers.add(c.group(1));
        Set<String> registered = new TreeSet<>();
        for (SettingSpec s : SettingsRegistry.all()) registered.add(s.key);
        assertEquals(registered.size(), readers.size());
        assertEquals(readers, registered);
    }

    @Test
    public void everyDefaultPassesAndReadonlyCannotBeWritten() {
        for (SettingSpec spec : SettingsRegistry.all()) {
            if (spec.type == SettingSpec.Type.READONLY) {
                assertEquals("sf.optics", spec.key);
                try {
                    spec.validate("9");
                    fail();
                } catch (IllegalArgumentException expected) {
                }
            } else {
                assertNotNull(spec.key, spec.validate(spec.defaultValue));
                assertFalse(spec.title.isEmpty());
                assertFalse(spec.explanation.isEmpty());
            }
        }
    }

    @Test
    public void sentinelAndInjectionAndNonFiniteValuesAreRejected() {
        for (String value :
                new String[] {
                    "NaN",
                    "Infinity",
                    "-Infinity",
                    "1;reboot",
                    "$(id)",
                    "1\nsetprop foo 1",
                    "-2",
                    "1.21"
                }) {
            try {
                SettingsRegistry.get("kglens").validate(value);
                fail(value);
            } catch (IllegalArgumentException expected) {
            }
        }
        assertEquals("-1", SettingsRegistry.get("kglens").validate("-1"));
        for (String value : new String[] {"-.1", "0", ".09"})
            try {
                SettingsRegistry.get("kglens").validate(value);
                fail(value);
            } catch (IllegalArgumentException expected) {
            }
        try {
            SettingsRegistry.get("rimhz").validate("2.5");
            fail();
        } catch (IllegalArgumentException expected) {
        }
        try {
            SettingsRegistry.get("semcurve").validate("1,2,3,4,5,Infinity");
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    public void defaultsStillMatchJavaReaderLiterals() throws Exception {
        String java =
                read(
                        root().resolve(
                                        "app/src/main/java/io/github/igorcv88/oneuiliquidglass/glass/Tuning.java"));
        Matcher m = Pattern.compile("number\\(\"([a-z0-9.]+)\", ([0-9.-]+)f\\)").matcher(java);
        while (m.find()) {
            SettingSpec s = SettingsRegistry.get(m.group(1));
            assertNotNull(s);
            assertEquals(
                    s.key,
                    Double.parseDouble(m.group(2)),
                    Double.parseDouble(s.defaultValue),
                    .000001);
        }
        assertEquals("80", SettingsRegistry.get("liveradius").defaultValue);
        assertEquals("60", SettingsRegistry.get("liveshaderadius").defaultValue);
        assertEquals("1", SettingsRegistry.get("sf.kgfixedrim").defaultValue);
        assertEquals("0", SettingsRegistry.get("kgsharedoptics").defaultValue);
    }

    @Test
    public void productFlagsAreNotPropertiesAndHaveSurfaceScope() {
        assertTrue(ProductFlags.known(ProductFlags.QUICK_SETTINGS));
        assertFalse(ProductFlags.defaultEnabled(ProductFlags.QUICK_SETTINGS));
        assertTrue(ProductFlags.defaultEnabled("surface.shade.enabled"));
        for (String surface : ProductFlags.SURFACES) {
            assertTrue(ProductFlags.known("surface." + surface + ".enabled"));
            for (String feature : ProductFlags.FEATURES)
                assertTrue(
                        ProductFlags.known(
                                "surface." + surface + ".feature." + feature + ".enabled"));
        }
        assertFalse(ProductFlags.known("watchdog.enabled"));
        assertFalse(ProductFlags.known("surface.unknown.enabled"));
        assertNull(SettingsRegistry.get("surface.headsup.enabled"));
    }

    @Test
    public void mediaArtOpacityDomain() {
        SettingSpec s = SettingsRegistry.get("mediaart");
        assertEquals(SettingSpec.ApplyMode.LIVE, s.applyMode);
        assertEquals("0.50", s.defaultValue);
        assertEquals(0.05, s.step, 1e-9);
        assertEquals("0", s.validate("0"));
        assertEquals("0.05", s.validate("0.05"));
        assertEquals("1", s.validate("1"));
        for (String bad : new String[] {"1.05", "-0.1", "NaN", "off"}) {
            try {
                s.validate(bad);
                fail(bad);
            } catch (IllegalArgumentException expected) {
            }
        }
    }
    @Test
    public void approvedDefaultsMatchTheCatalog() {
        String[][] expected = {
            {"mediaart", "0.50"},
            {"sf.kgflat", "0.50"},
            {"sf.kglevel", "0.85"},
            {"sf.kgtone", "0"},
            {"sf.kgcoredim", "0"},
            {"sf.kglight", "0.50"},
            {"sf.kgdepth", "24"},
            {"kgblurradius", "12"},
            {"kgdarkfill", "5"},
            {"sf.kgsat", "0.95"},
            {"sf.kgspec", "0.17"},
            {"liveshaderadius", "60"},
            {"shadeglint", "0.22"},
            {"semradius", "0"},
            {"semradiuslens", "14"},
            {"sflens", "0.55"},
            {"semalpha", "0"},
            {"shadelightglass", "0.18"},
            {"livedarkfill", "0"},
            {"headertone", "0"},
            {"paneldim", "0.08"},
            {"panelblurcap", "70"},
            {"headergap", "7"},
            {"sf.core", "56"},
            {"sf.taps", "48"},
            {"sf.ramp", "1"},
            {"sf.edgeblur", "8"},
            {"sf.hubodylens", "0.06"},
            {"qs.lightfill", "0"},
            {"qs.lightcolor", "ffffffff"},
            {"qs.lightoptics", "0.36"},
            {"qs.sliderlightcolor", "000c2189"},
            {"qs.sliderdarkcolor", "f1f7ffe0"},
            {"qs.tilelighton", "000c21cd"},
            {"qs.tilelightoff", "24324666"},
            {"qs.tilelightonink", "ffffffeb"},
            {"qs.tilelightoffink", "000c21cd"},
            {"qs.tiledark", "1"},
            {"qs.tiledarkon", "ffffffd9"},
            {"qs.tiledarkoff", "e6f0ff40"},
            {"qs.tiledarkonink", "17202ff2"},
            {"qs.tiledarkoffink", "ffffffe6"},
            {"qs.darkrim", "0.28"},
            {"qs.edgeblur", "6"},
        };
        for (String[] pair : expected)
            assertEquals(pair[0], pair[1], SettingsRegistry.get(pair[0]).defaultValue);
        assertEquals("2 %", ValueFormat.display(Controls.get("kgdarkfill"), "5"));
        assertEquals("5", ValueFormat.fromEdit(Controls.get("kgdarkfill"), "2"));
    }
}
