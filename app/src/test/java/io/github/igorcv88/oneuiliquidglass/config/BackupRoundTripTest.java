package io.github.igorcv88.oneuiliquidglass.config;

import static org.junit.Assert.*;

import io.github.igorcv88.oneuiliquidglass.live.CompiledEvidence;
import io.github.igorcv88.oneuiliquidglass.live.fixtures.ActivationFixture;
import java.util.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/** A backup restores what the person chose; the diagnostic is evidence and never an input. */
@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 33)
public class BackupRoundTripTest {
    private static String export(Map<String, String> terminal, PresetCodec.Diagnostic diagnostic) throws Exception {
        return PresetCodec.encodeV2(ActivationFixture.desired(), true, "device", 42, 1_000L, terminal, diagnostic);
    }

    private static PresetCodec.Diagnostic diagnostic() {
        return new PresetCodec.Diagnostic(true, 5L, Collections.singletonMap("sf.hcsat", "1.4"),
                ActivationFixture.historicalEvidence());
    }

    @Test
    public void desiredValuesSurviveARoundTripExactly() throws Exception {
        PresetCodec.Parsed parsed = PresetCodec.parse(export(null, null));
        assertEquals(2, parsed.schema);
        assertEquals(ActivationFixture.desired().size(), parsed.values.size() + parsed.unknown.size());
        for (Map.Entry<String, String> e : parsed.values.entrySet())
            assertEquals(e.getKey(), ActivationFixture.desired().get(e.getKey()), e.getValue());
        assertTrue(parsed.enabled);
    }

    @Test
    public void implicitDefaultsListEveryEditableKeyTheFileDoesNotOverride() throws Exception {
        PresetCodec.Parsed parsed = PresetCodec.parse(export(null, null));
        assertFalse(parsed.implicitDefaults.isEmpty());
        for (String key : parsed.implicitDefaults.keySet()) {
            assertFalse("an override is not also implicit: " + key, parsed.values.containsKey(key));
            assertEquals(SettingsRegistry.get(key).validate(SettingsRegistry.get(key).defaultValue),
                    parsed.implicitDefaults.get(key));
        }
        assertEquals(PresetCodec.implicitDefaults(ActivationFixture.desired()).keySet(), parsed.implicitDefaults.keySet());
    }

    @Test
    public void terminalValuesAreLeftOutUnlessTheExportAsksForThem() throws Exception {
        PresetCodec.Parsed without = PresetCodec.parse(export(null, null));
        assertTrue(without.terminal.isEmpty());
        JSONObject block = new JSONObject(export(null, null)).getJSONObject("terminal");
        assertFalse(block.getBoolean("included"));

        PresetCodec.Parsed with = PresetCodec.parse(export(Collections.singletonMap("sf.hcsat", "1.4"), null));
        assertEquals(Collections.singletonMap("sf.hcsat", "1.4"), with.terminal);
    }

    @Test
    public void theDiagnosticIsReadAsPresentAndNeverBecomesAValue() throws Exception {
        PresetCodec.Parsed parsed = PresetCodec.parse(export(null, diagnostic()));
        assertTrue(parsed.diagnosticPresent);
        assertFalse(parsed.values.containsKey("sfrefract"));
        PresetCodec.Parsed plain = PresetCodec.parse(export(null, null));
        assertFalse(plain.diagnosticPresent);
        assertEquals("the diagnostic adds no importable value", plain.values, parsed.values);
        assertEquals(plain.implicitDefaults, parsed.implicitDefaults);
    }

    @Test
    public void theFileRecordsTheVersionAndTheDefaultsItWasWrittenWith() throws Exception {
        JSONObject json = new JSONObject(export(null, null));
        assertEquals(42, json.getJSONObject("app").getInt("versionCode"));
        assertEquals(PresetCodec.registryHash(), json.getJSONObject("app").getString("registry"));
        assertTrue(PresetCodec.registryHash().matches("[0-9a-f]{40}"));
    }

    @Test
    public void aV2FileWithAnInvalidBlockIsRejectedAtomically() throws Exception {
        JSONObject json = new JSONObject(export(null, null));
        json.getJSONObject("implicitDefaults").put("sf.hcsat", "not-a-number");
        try {
            PresetCodec.parse(json.toString());
            fail("an invalid implicit default must reject the file");
        } catch (PresetCodec.InvalidPreset expected) {
            assertEquals(PresetCodec.Error.VALUE, expected.error);
        }
    }

    @Test
    public void schemaOneStillImportsAsDesiredOnly() throws Exception {
        String legacy = "{\"schemaVersion\":1,\"enabled\":false,\"desired\":{\"sf.hcsat\":\"1.2\"},"
                + "\"observed\":{\"sf.hcsat\":\"1.4\"}}";
        PresetCodec.Parsed parsed = PresetCodec.parse(legacy);
        assertEquals(1, parsed.schema);
        assertEquals("1.2", parsed.values.get("sf.hcsat"));
        assertTrue(parsed.implicitDefaults.isEmpty());
        assertTrue(parsed.terminal.isEmpty());
        assertTrue(parsed.diagnosticPresent);
        assertFalse(parsed.enabled);
    }

    @Test
    public void anUnknownSchemaOrAnOversizedFileIsRefused() throws Exception {
        try {
            PresetCodec.parse("{\"schemaVersion\":3,\"enabled\":true,\"desired\":{}}");
            fail();
        } catch (PresetCodec.InvalidPreset expected) {
            assertEquals(PresetCodec.Error.VERSION, expected.error);
        }
        StringBuilder big = new StringBuilder("{\"schemaVersion\":2,\"enabled\":true,\"desired\":{},\"pad\":\"");
        for (int i = 0; i < PresetCodec.MAX_BYTES; i++) big.append('x');
        big.append("\"}");
        try {
            PresetCodec.parse(big.toString());
            fail();
        } catch (PresetCodec.InvalidPreset expected) {
            assertEquals(PresetCodec.Error.TOO_LARGE, expected.error);
        }
    }

    @Test
    public void aDiagnosticKeepsTheCompiledEvidenceItWasGiven() throws Exception {
        CompiledEvidence evidence = ActivationFixture.historicalEvidence();
        JSONObject compiled = new JSONObject(export(null, diagnostic())).getJSONObject("diagnostic")
                .getJSONObject("compiled");
        assertEquals(evidence.generation, compiled.getString("generation"));
        assertEquals("HISTORICAL", compiled.getString("freshness"));
        assertEquals(evidence.inputs.size(), compiled.getJSONObject("inputs").length());
    }
}
