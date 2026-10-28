package io.github.igorcv88.oneuiliquidglass.config;

import org.json.*;

import java.util.*;

/** Import parses and validates the entire payload before any write; unknown keys are reported. */
public final class PresetCodec {
    public static final int MAX_BYTES = 131072;

    public static final class Parsed {
        public final Map<String, String> values = new LinkedHashMap<>();
        public final List<String> unknown = new ArrayList<>();
        public boolean enabled;
        public String device;
    }

    public static String encode(Map<String, String> values, boolean enabled, String device)
            throws JSONException {
        return encode(values, enabled, device, Collections.emptyMap(), false,
                Collections.emptyMap(), "", 0);
    }

    public static String encode(Map<String, String> values, boolean enabled, String device,
            Map<String, String> observed, boolean available, Map<String, String> restartInputs,
            String generation, long requestedAt) throws JSONException {
        return new JSONObject()
                .put("provenance", "desired contains app overrides only; observed and verifiedRestart are diagnostic snapshots, never imported")
                .put("observed", new JSONObject().put("available", available)
                        .put("capturedAt", System.currentTimeMillis()).put("values", new JSONObject(observed)))
                .put("verifiedRestart", new JSONObject().put("generation", generation)
                        .put("requestedAt", requestedAt).put("inputs", new JSONObject(restartInputs))
                        .put("evidence", "restart inputs, not per-shader compilation or full appearance"))
                .put("schemaVersion", 1)
                .put("device", device)
                .put("timestamp", System.currentTimeMillis())
                .put("enabled", enabled)
                .put(
                        "requiredMode",
                        values.keySet().stream().anyMatch(k -> k.startsWith("sf."))
                                ? "SF/manual"
                                : "APK")
                .put("desired", new JSONObject(values))
                .toString(2);
    }

    public static Parsed parse(String input) throws JSONException {
        if (input.length() > MAX_BYTES) throw new IllegalArgumentException("Arquivo muito grande");
        JSONObject json = new JSONObject(input);
        if (json.getInt("schemaVersion") != 1)
            throw new IllegalArgumentException("Versão de preset incompatível");
        Parsed result = new Parsed();
        result.enabled = json.getBoolean("enabled");
        result.device = json.optString("device");
        JSONObject values = json.getJSONObject("desired");
        Iterator<String> keys = values.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object raw = values.get(key);
            if (!(raw instanceof String))
                throw new IllegalArgumentException("O valor deve ser texto: " + key);
            String value = (String) raw;
            SettingSpec spec = SettingsRegistry.get(key);
            if (spec != null) {
                if (spec.type == SettingSpec.Type.READONLY || spec.helperOwned()) {
                    result.unknown.add(key + " (somente leitura)");
                    continue;
                }
                result.values.put(key, spec.validate(value));
            } else if (ProductFlags.known(key) && (value.equals("1") || value.equals("0")))
                result.values.put(key, value);
            else result.unknown.add(key);
        }
        return result;
    }

    private PresetCodec() {}
}
