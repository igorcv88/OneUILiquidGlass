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
        Matcher j = Pattern.compile("(?:number|prop)\\(\"([a-z0-9.]+)\"").matcher(java);
        while (j.find()) readers.add(j.group(1));
        Matcher c =
                Pattern.compile("debug\\.oulg\\.([a-z0-9.]+)")
                        .matcher(read(root().resolve("sfhook/oulg_sf.c")));
        while (c.find()) readers.add(c.group(1));
        Set<String> registered = new TreeSet<>();
        for (SettingSpec s : SettingsRegistry.all()) registered.add(s.key);
        assertEquals(102, readers.size());
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
        assertEquals("120", SettingsRegistry.get("liveshaderadius").defaultValue);
    }

    @Test
    public void productFlagsAreNotPropertiesAndHaveSurfaceScope() {
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
}
