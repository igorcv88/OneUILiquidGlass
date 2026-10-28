package io.github.igorcv88.oneuiliquidglass.live;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.*;

public final class CompositorValuesTest {
    @Test public void qsLightRequiresCompileAndComposeValuesDoNot() {
        assertTrue(CompositorValues.writable("sf.qslight"));
        assertEquals(Collections.singletonMap("sf.qslight","2"),
                CompositorValues.pending(Collections.singletonMap("sf.qslight","2"),Collections.emptyMap()));
        assertTrue(CompositorValues.pending(Collections.singletonMap("qs.darkfill","0.2"),Collections.emptyMap()).isEmpty());
        assertTrue(CompositorValues.pending(Collections.singletonMap("sf.qslight","0.90"),Collections.emptyMap()).isEmpty());
    }
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

    @Test public void terminalOnlyChangeStillRequiresARecompile() {
        Map<String, String> compiled = new HashMap<>();
        compiled.put("sf.core", "48");
        compiled.put("sf.taps", "24");
        Map<String, String> terminal = new HashMap<>();
        terminal.put("sf.core", "72");
        terminal.put("sf.taps", "24.0");
        // The app has released both controls to Terminal; getprop readback is not compilation.
        assertEquals(Collections.singletonMap("sf.core", "72"),
                CompositorValues.pending(Collections.emptyMap(), terminal, compiled));
        assertTrue(CompositorValues.pending(Collections.emptyMap(), compiled, compiled).isEmpty());
        // An explicit property clear is another change, back to the built-in default.
        assertEquals(Collections.singletonMap("sf.core", "48"),
                CompositorValues.pending(Collections.emptyMap(), Collections.emptyMap(),
                        Collections.singletonMap("sf.core", "72")));
    }

    @Test public void aSentValueAwaitsVerificationInsteadOfAnotherRestart() {
        // The device report: kgcoredim 0.05 compiled, 0.03 saved and sent by a restart that has not
        // been verified yet. Comparing with the old compile kept "3 % -> 3 %" pending forever and
        // offered another restart; the sent inputs are the baseline until a status confirms them.
        Map<String, String> compiled = CompositorValues.restartInputs(
                Collections.singletonMap("sf.kgcoredim", "0.05"), Collections.emptyMap());
        Map<String, String> saved = Collections.singletonMap("sf.kgcoredim", "0.03");
        Map<String, String> observed = Collections.singletonMap("sf.kgcoredim", "0.03");
        Map<String, String> sent = CompositorValues.restartInputs(observed, saved);
        assertEquals(Collections.singletonMap("sf.kgcoredim", "0.03"),
                CompositorValues.pending(saved, observed, compiled));
        Map<String, String> base = CompositorValues.baseline(2000, 1000, sent, compiled);
        assertSame(sent, base);
        assertTrue(CompositorValues.pending(saved, observed, base).isEmpty());
        // A change made after that restart is pending again.
        Map<String, String> later = Collections.singletonMap("sf.kgcoredim", "0.02");
        assertEquals(later, CompositorValues.pending(later, observed, base));
        // Confirmed (compiledAt >= requestedAt) or never attempted: the compile is the baseline.
        assertSame(compiled, CompositorValues.baseline(1000, 1000, sent, compiled));
        assertSame(compiled, CompositorValues.baseline(0, 1000, Collections.emptyMap(), compiled));
        assertNull(CompositorValues.baseline(0, 0, Collections.emptyMap(), null));
    }

    @Test public void inputsForRestartIncludeExternalValuesAndSavedOverrides() {
        Map<String, String> observed = Collections.singletonMap("sf.taps", "36");
        Map<String, String> writes = Collections.singletonMap("sf.core", "72");
        Map<String, String> inputs = CompositorValues.restartInputs(observed, writes);
        assertEquals("72", inputs.get("sf.core"));
        assertEquals("36", inputs.get("sf.taps"));
        assertEquals("0", inputs.get("sf.debug"));
        assertTrue(inputs.size() >= SfLive.WRITABLE.size());
    }

    @Test public void verificationNeedsALoadedLibraryAndMatchingValues() {
        Map<String, String> written = Collections.singletonMap("sf.kgsat", "1.4");
        Map<String, String> loaded = new HashMap<>();
        loaded.put("helper", "1");
        loaded.put("sf_loaded", "1");
        loaded.put("sfrefract", "1");
        loaded.put("sf_rewrite", "1");
        loaded.put("sf_got", "1");
        loaded.put("sf_rewrite_ok", "1");
        loaded.put("sf_stable", "1");
        loaded.put("sf_generation", "boot:4200:123");
        assertEquals(CompositorValues.Outcome.NONE, CompositorValues.verify(0, written, loaded, written));
        assertEquals(CompositorValues.Outcome.UNVERIFIED, CompositorValues.verify(1, written, Collections.emptyMap(), written));
        assertEquals(CompositorValues.Outcome.CONFIRMED, CompositorValues.verify(1, written, loaded, Collections.singletonMap("sf.kgsat", "1.40")));
        assertEquals(CompositorValues.Outcome.CHANGED, CompositorValues.verify(1, written, loaded, Collections.singletonMap("sf.kgsat", "1.2")));
        loaded.put("sf_pid", "4200");
        assertEquals(CompositorValues.Outcome.UNVERIFIED, CompositorValues.verify(1, written, loaded, written, "4200"));
        assertEquals(CompositorValues.Outcome.CONFIRMED, CompositorValues.verify(1, written, loaded, written, "4199"));
        loaded.put("sf_loaded", "0");
        assertEquals(CompositorValues.Outcome.NOT_LOADED, CompositorValues.verify(1, written, loaded, written));
    }

    @Test public void readbackAndOpticsNeverSubstituteForOperationalEvidence() {
        Map<String, String> inputs = Collections.singletonMap("sf.core", "72");
        Map<String, String> status = new HashMap<>();
        status.put("helper", "1"); status.put("sf_loaded", "1");
        status.put("sfrefract", "1"); status.put("sf_rewrite", "1");
        status.put("sf_pid", "52"); status.put("sf_generation", "boot:52:700");
        status.put("sf_optics", "9");
        for (String key : Arrays.asList("sf_got", "sf_rewrite_ok", "sf_stable")) {
            assertEquals(CompositorValues.Outcome.UNVERIFIED,
                    CompositorValues.verify(1, inputs, status, inputs, "51"));
            status.put(key, "1");
        }
        assertEquals(CompositorValues.Outcome.CONFIRMED,
                CompositorValues.verify(1, inputs, status, inputs, "boot:51:600"));
        assertEquals(CompositorValues.Outcome.UNVERIFIED,
                CompositorValues.verify(1, inputs, status, inputs, "boot:52:700"));
        status.put("sf_loaded", "0");
        assertEquals(CompositorValues.Outcome.NOT_LOADED,
                CompositorValues.verify(1, inputs, status, inputs, "51"));
    }

    @Test public void liveParametersDoNotBecomeCompositorPendingAndEdgeblurDoes() {
        assertTrue(CompositorValues.pending(Collections.singletonMap("semradiuslens", "22"),
                Collections.singletonMap("semradiuslens", "16"), Collections.emptyMap()).isEmpty());
        assertEquals(Collections.singletonMap("sf.edgeblur", "12"),
                CompositorValues.pending(Collections.emptyMap(), Collections.singletonMap("sf.edgeblur", "12"),
                        Collections.singletonMap("sf.edgeblur", "0")));
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
