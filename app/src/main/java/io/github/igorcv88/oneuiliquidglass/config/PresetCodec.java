package io.github.igorcv88.oneuiliquidglass.config;

import io.github.igorcv88.oneuiliquidglass.live.CompiledEvidence;

import org.json.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * The backup file (docs/OULG_UI_PLAN/ACTIVATION_AND_BACKUP_CONTRACT.md section 6). Schema 2 keeps
 * what the person chose ({@code desired}, dormant values included), the defaults of the version
 * that wrote it ({@code implicitDefaults}), optionally the values the Terminal had set
 * ({@code terminal}) and a read-only diagnostic that is never imported. Schema 1 files import only
 * their {@code desired}. Import parses and validates the whole payload before any write; unknown
 * keys are reported.
 */
public final class PresetCodec {
    public static final int MAX_BYTES = 131072;
    public static final int SCHEMA_V2 = 2;

    public enum Error { TOO_LARGE, VERSION, TYPE, VALUE }

    public static final class InvalidPreset extends IllegalArgumentException {
        public final Error error;
        public final String key;
        InvalidPreset(Error error, String key) { this.error = error; this.key = key; }
    }

    public static final class Parsed {
        /** The person's own values (importable). */
        public final Map<String, String> values = new LinkedHashMap<>();
        /** Defaults of the exporting version for keys with no override (importable on request). */
        public final Map<String, String> implicitDefaults = new LinkedHashMap<>();
        /** Values the Terminal had set, present only when the exporter included them (importable on request). */
        public final Map<String, String> terminal = new LinkedHashMap<>();
        public final List<String> unknown = new ArrayList<>();
        public boolean enabled;
        public String device;
        public int schema;
        /** The file carries a system diagnostic; it is never imported. */
        public boolean diagnosticPresent;
    }

    /** The read-only snapshot a backup carries for support; never imported. */
    public static final class Diagnostic {
        final boolean available;
        final long capturedAt;
        final Map<String, String> observed;
        final CompiledEvidence compiled;

        public Diagnostic(boolean available, long capturedAt, Map<String, String> observed, CompiledEvidence compiled) {
            this.available = available;
            this.capturedAt = capturedAt;
            this.observed = observed;
            this.compiled = compiled;
        }
    }

    /** Schema 1, kept for tests of legacy files and for callers that need no more than desired. */
    public static String encode(Map<String, String> values, boolean enabled, String device)
            throws JSONException {
        return encode(values, enabled, device, Collections.emptyMap(), false,
                Collections.emptyMap(), "", 0);
    }

    /** Schema 1 with its diagnostic snapshots. */
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

    /**
     * Schema 2.
     *
     * @param desired every override, dormant ones included
     * @param terminal values the Terminal had set, or null to leave them out (the default)
     * @param diagnostic the read-only system snapshot, or null for none (a saved style has none)
     */
    public static String encodeV2(Map<String, String> desired, boolean enabled, String device,
            int versionCode, long createdAt, Map<String, String> terminal, Diagnostic diagnostic)
            throws JSONException {
        JSONObject json = new JSONObject()
                .put("schemaVersion", SCHEMA_V2)
                .put("kind", "oulg-settings")
                .put("app", new JSONObject().put("versionCode", versionCode).put("registry", registryHash()))
                .put("createdAt", createdAt)
                .put("device", device == null ? "" : device)
                .put("enabled", enabled)
                .put("desired", new JSONObject(desired))
                .put("implicitDefaults", new JSONObject(implicitDefaults(desired)))
                .put("terminal", new JSONObject().put("included", terminal != null)
                        .put("values", new JSONObject(terminal == null ? Collections.<String, String>emptyMap() : terminal)))
                .put("provenance", "desired and implicitDefaults are importable; terminal only by explicit choice; diagnostic never");
        if (diagnostic != null) json.put("diagnostic", diagnosticJson(diagnostic));
        String text = json.toString(2);
        if (text.length() > MAX_BYTES) throw new InvalidPreset(Error.TOO_LARGE, null);
        return text;
    }

    private static JSONObject diagnosticJson(Diagnostic d) throws JSONException {
        return new JSONObject()
                .put("observed", new JSONObject().put("available", d.available)
                        .put("capturedAt", d.capturedAt).put("values", new JSONObject(d.observed)))
                .put("compiled", new JSONObject().put("generation", d.compiled.generation)
                        .put("at", d.compiled.at).put("freshness", d.compiled.freshness.name())
                        .put("inputs", new JSONObject(d.compiled.inputs))
                        .put("evidence", "restart inputs, not per-shader compilation or full appearance"));
    }

    /** Every editable setting without an override, at the default of this version. */
    public static Map<String, String> implicitDefaults(Map<String, String> desired) {
        Map<String, String> out = new TreeMap<>();
        for (SettingSpec spec : SettingsRegistry.all()) {
            if (spec.type == SettingSpec.Type.READONLY || spec.helperOwned() || !Controls.editable(spec.key)) continue;
            if (!desired.containsKey(spec.key)) out.put(spec.key, spec.defaultValue);
        }
        return out;
    }

    /** SHA-1 of the sorted {@code key=default} lines: tells a file's defaults from this version's. */
    public static String registryHash() {
        TreeMap<String, String> sorted = new TreeMap<>();
        for (SettingSpec spec : SettingsRegistry.all()) sorted.put(spec.key, spec.defaultValue);
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, String> e : sorted.entrySet()) text.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        try {
            StringBuilder hex = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-1").digest(text.toString().getBytes(StandardCharsets.UTF_8)))
                hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Parsed parse(String input) throws JSONException {
        if (input.length() > MAX_BYTES) throw new InvalidPreset(Error.TOO_LARGE, null);
        JSONObject json = new JSONObject(input);
        int schema = json.getInt("schemaVersion");
        if (schema != 1 && schema != SCHEMA_V2)
            throw new InvalidPreset(Error.VERSION, null);
        Parsed result = new Parsed();
        result.schema = schema;
        result.enabled = json.getBoolean("enabled");
        result.device = json.optString("device");
        readValues(json.getJSONObject("desired"), result.values, result.unknown, true);
        if (schema == SCHEMA_V2) {
            JSONObject implicit = optObject(json, "implicitDefaults");
            if (implicit != null) readValues(implicit, result.implicitDefaults, result.unknown, false);
            JSONObject terminal = optObject(json, "terminal");
            if (terminal != null && terminal.optBoolean("included", false)) {
                JSONObject values = optObject(terminal, "values");
                if (values != null) readValues(values, result.terminal, result.unknown, false);
            }
            result.diagnosticPresent = json.has("diagnostic");
        } else {
            result.diagnosticPresent = json.has("observed") || json.has("verifiedRestart");
        }
        return result;
    }

    private static JSONObject optObject(JSONObject json, String name) {
        Object raw = json.opt(name);
        if (raw == null || raw == JSONObject.NULL) return null;
        if (!(raw instanceof JSONObject)) throw new InvalidPreset(Error.TYPE, name);
        return (JSONObject) raw;
    }

    /** Validates every entry; {@code withFlags} lets product flags through (only the desired block has them). */
    private static void readValues(JSONObject values, Map<String, String> out, List<String> unknown, boolean withFlags)
            throws JSONException {
        Iterator<String> keys = values.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object raw = values.get(key);
            if (!(raw instanceof String))
                throw new InvalidPreset(Error.TYPE, key);
            String value = (String) raw;
            SettingSpec spec = SettingsRegistry.get(key);
            if (spec != null) {
                if (spec.type == SettingSpec.Type.READONLY || spec.helperOwned()) {
                    unknown.add(key + " (somente leitura)");
                    continue;
                }
                try { out.put(key, spec.validate(value)); }
                catch (IllegalArgumentException e) { throw new InvalidPreset(Error.VALUE, key); }
            } else if (withFlags && ProductFlags.known(key) && (value.equals("1") || value.equals("0")))
                out.put(key, value);
            else unknown.add(key);
        }
    }

    private PresetCodec() {}
}
