package io.github.igorcv88.oneuiliquidglass.config;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.*;

public class PresetCodecTest {
    @Test
    public void diagnosticSnapshotsNeverBecomeImportedDesiredValues() throws Exception {
        Map<String, String> desired = Collections.singletonMap("lens", "0.24");
        String json = PresetCodec.encode(desired, true, "fixture",
                Collections.singletonMap("sf.core", "90"), true,
                Collections.singletonMap("sf.core", "48"), "boot:52:700", 123);
        assertEquals(desired, PresetCodec.parse(json).values);
        assertTrue(json.contains("verifiedRestart"));
        assertTrue(json.contains("not per-shader compilation"));
    }

    @Test
    public void roundTripKeepsFlagsModeAndDesiredWithoutApplying() throws Exception {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("lens", "0.24");
        values.put("sf.kgcore", "80");
        values.put("surface.keyguard.enabled", "0");
        String json = PresetCodec.encode(values, true, "Samsung fixture");
        PresetCodec.Parsed result = PresetCodec.parse(json);
        assertEquals(values, result.values);
        assertTrue(result.enabled);
        assertTrue(result.unknown.isEmpty());
        assertTrue(json.contains("SF/manual"));
    }

    @Test
    public void unknownAndReadOnlyAreReportedAndNeverStored() throws Exception {
        PresetCodec.Parsed result =
                PresetCodec.parse(
                        "{\"schemaVersion\":1,\"enabled\":false,\"desired\":{\"other.module\":\"1\",\"sf.optics\":\"9\",\"lens\":\"0.24\"}}");
        assertEquals(2, result.unknown.size());
        assertEquals(Collections.singletonMap("lens", "0.24"), result.values);
    }

    @Test
    public void invalidKnownValuesRejectWholePayload() throws Exception {
        for (String value : new String[] {"NaN", "1;reboot", "0.9"}) {
            try {
                PresetCodec.parse(
                        "{\"schemaVersion\":1,\"enabled\":false,\"desired\":{\"liveglint\":\"0.1\",\"lens\":\""
                                + value
                                + "\"}}");
                fail();
            } catch (IllegalArgumentException expected) {
            }
        }
    }

    @Test
    public void injectionLifecycleCannotBePersistedByPreset() throws Exception {
        PresetCodec.Parsed result =
                PresetCodec.parse(
                        "{\"schemaVersion\":1,\"enabled\":true,\"desired\":{\"sfrefract\":\"1\"}}");
        assertTrue(result.values.isEmpty());
        assertEquals(1, result.unknown.size());
    }

    @Test
    public void rejectWrongSchemaOversizeAndWrongJsonType() throws Exception {
        for (String data :
                new String[] {
                    "{\"schemaVersion\":2,\"enabled\":false,\"desired\":{}}",
                    "{\"schemaVersion\":1,\"enabled\":false,\"desired\":{\"lens\":0.2}}",
                    " ".repeat(PresetCodec.MAX_BYTES + 1)
                })
            try {
                PresetCodec.parse(data);
                fail();
            } catch (IllegalArgumentException expected) {
            }
    }
}
