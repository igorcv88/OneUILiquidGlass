package io.github.igorcv88.oneuiliquidglass.live;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.*;

public final class CompositorValuesTest {
    @Test public void pendingIsSavedMinusWhatTheCompositorWouldCompile() {
        Map<String, String> saved = new HashMap<>(), observed = new HashMap<>();
        saved.put("sf.kgcore", "80");      // equals the library default, property empty
        saved.put("sf.kgsat", "1.4");      // differs from default
        saved.put("sf.core", "48.0");      // equals observed numerically
        saved.put("liveradius", "60");     // not a compositor value
        observed.put("sf.core", "48");
        assertEquals(Collections.singletonMap("sf.kgsat", "1.4"), CompositorValues.pending(saved, observed));
        observed.put("sf.kgcore", "90");   // a Terminal changed it: the saved default is pending again
        assertEquals(new TreeSet<>(Arrays.asList("sf.kgcore", "sf.kgsat")), CompositorValues.pending(saved, observed).keySet());
    }

    @Test public void readOnlyMarkersAreNeverWritable() {
        assertFalse(CompositorValues.writable("sf.optics"));
        assertFalse(CompositorValues.writable("sf.norewrite"));
        assertFalse(CompositorValues.writable("sfrefract"));
        assertTrue(CompositorValues.writable("sf.kgtilt"));
    }

    @Test public void verificationNeedsALoadedLibraryAndMatchingValues() {
        Map<String, String> written = Collections.singletonMap("sf.kgsat", "1.4");
        Map<String, String> loaded = new HashMap<>();
        loaded.put("helper", "1");
        loaded.put("sf_loaded", "1");
        loaded.put("sfrefract", "1");
        assertEquals(CompositorValues.Outcome.NONE, CompositorValues.verify(0, written, loaded, written));
        assertEquals(CompositorValues.Outcome.UNVERIFIED, CompositorValues.verify(1, written, Collections.emptyMap(), written));
        assertEquals(CompositorValues.Outcome.CONFIRMED, CompositorValues.verify(1, written, loaded, Collections.singletonMap("sf.kgsat", "1.40")));
        assertEquals(CompositorValues.Outcome.CHANGED, CompositorValues.verify(1, written, loaded, Collections.singletonMap("sf.kgsat", "1.2")));
        loaded.put("sf_loaded", "0");
        assertEquals(CompositorValues.Outcome.NOT_LOADED, CompositorValues.verify(1, written, loaded, written));
    }

    @Test public void setpropsCommandIsAllowlistedAndNumeric() {
        Map<String, String> v = new LinkedHashMap<>();
        v.put("sf.kgcore", "96");
        v.put("sf.core", "");
        assertEquals("sh '/d/oulg-live.sh' setprops kgcore=96 core=", SfLive.setprops("/d", v).get(2));
        for (String[] bad : new String[][] {{"sf.optics", "9"}, {"sf.norewrite", "1"}, {"liveradius", "1"},
                {"sf.kgcore", "1;reboot"}, {"sf.kgcore", "$(id)"}, {"sf.kgcore", "1e3"}, {"sf.kgcore", "1234567890123"}})
            assertThrows(bad[0] + "=" + bad[1], IllegalArgumentException.class,
                    () -> SfLive.setprops("/d", Collections.singletonMap(bad[0], bad[1])));
        assertThrows(IllegalArgumentException.class, () -> SfLive.setprops("/d", Collections.emptyMap()));
        assertThrows(IllegalArgumentException.class, () -> SfLive.command("/d", SfLive.Command.SETPROPS, null));
    }

    @Test public void writableListMatchesTheLibraryReaderMinusOwnedMarkers() throws Exception {
        String c = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("../sfhook/oulg_sf.c")), "UTF-8");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("debug\\.oulg\\.sf\\.([a-z]+)").matcher(c);
        Set<String> read = new TreeSet<>();
        while (m.find()) read.add(m.group(1));
        read.remove("optics");
        read.remove("norewrite");
        assertEquals(read, new TreeSet<>(SfLive.WRITABLE));
        String helper = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("../sfhook/live/oulg-live.sh")), "UTF-8");
        for (String k : SfLive.WRITABLE) assertTrue(k, helper.contains("|" + k + "|") || helper.contains(" " + k + "|") || helper.contains("|" + k + ")"));
    }
}
