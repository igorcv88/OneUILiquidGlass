package io.github.igorcv88.oneuiliquidglass.config;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import io.github.igorcv88.oneuiliquidglass.Config;

import java.util.*;

/** Desired overrides only: opening the app never installs defaults or adopts external props. */
public final class ConfigRepository {
    public static final int SCHEMA_VERSION = 1;
    public static final Uri SNAPSHOT = Uri.parse("content://" + Config.AUTHORITY + "/v1/snapshot");
    public static final String PREFIX = "desired.", BASELINE = "baseline.";
    private final Context context;
    private final SharedPreferences prefs;

    public ConfigRepository(Context context) {
        this.context = context.getApplicationContext();
        prefs = Config.open(context);
    }

    public SharedPreferences preferences() {
        return prefs;
    }

    public String desired(SettingSpec s) {
        String saved = s.helperOwned() ? null : prefs.getString(PREFIX + s.key, null);
        if (saved != null) return saved;
        String observed = SystemPropertyReader.read(s.key);
        return observed.isEmpty() ? s.defaultValue : observed;
    }

    public static final class Snapshot {
        public final long generation;
        public final boolean enabled;
        public final Map<String, String> overrides;

        Snapshot(long generation, boolean enabled, Map<String, String> overrides) {
            this.generation = generation;
            this.enabled = enabled;
            this.overrides = Collections.unmodifiableMap(overrides);
        }
    }

    /** SharedPreferences.getAll copies one committed in-memory state, including its generation. */
    public Snapshot snapshotValues() {
        Map<String, ?> all = prefs.getAll();
        Map<String, String> values = new LinkedHashMap<>();
        for (SettingSpec spec : SettingsRegistry.all()) {
            Object raw = all.get(PREFIX + spec.key);
            if (raw instanceof String
                    && spec.type != SettingSpec.Type.READONLY
                    && !spec.helperOwned()) {
                try {
                    values.put(spec.key, spec.validate((String) raw));
                } catch (IllegalArgumentException ignored) {
                    /* Corrupt storage never reaches SystemUI. */
                }
            }
        }
        for (Map.Entry<String, ?> entry : all.entrySet())
            if (ProductFlags.known(entry.getKey()) && entry.getValue() instanceof Boolean)
                values.put(entry.getKey(), (Boolean) entry.getValue() ? "1" : "0");
        Object generation = all.get("generation"), enabled = all.get(Config.KEY_ENABLED);
        return new Snapshot(
                generation instanceof Long ? (Long) generation : 0,
                enabled instanceof Boolean && (Boolean) enabled,
                values);
    }

    public Map<String, String> overrides() {
        return snapshotValues().overrides;
    }

    public boolean conflict(SettingSpec s) {
        String saved = prefs.getString(BASELINE + s.key, null);
        return saved != null && !saved.equals(SystemPropertyReader.read(s.key));
    }

    public synchronized void save(SettingSpec s, String value) {
        String normalized = s.validate(value);
        Map<String, String> update = new LinkedHashMap<>();
        update.put(s.key, normalized);
        replace(update, false);
    }

    public synchronized void flag(String key, boolean value) {
        if (!ProductFlags.known(key) && !key.equals(Config.KEY_ENABLED))
            throw new IllegalArgumentException(key);
        snapshot();
        prefs.edit()
                .putBoolean(key, value)
                .putLong("generation", generation() + 1)
                .putLong("lastChange", System.currentTimeMillis())
                .apply();
        notifyChanged();
    }

    /** Code defaults for {@code keys} saved as desired values (one undo snapshot). */
    public synchronized void resetToDefaults(Collection<String> keys) {
        Map<String, String> defaults = new LinkedHashMap<>();
        for (String key : keys) {
            SettingSpec s = SettingsRegistry.get(key);
            if (s != null && s.type != SettingSpec.Type.READONLY && !s.helperOwned())
                defaults.put(key, s.defaultValue);
        }
        if (!defaults.isEmpty()) replace(defaults, false);
    }

    public long lastChange() {
        return prefs.getLong("lastChange", 0);
    }

    public boolean canUndo() {
        return prefs.contains("undo");
    }

    public long generation() {
        return prefs.getLong("generation", 0);
    }

    public synchronized void replace(Map<String, String> values, boolean clear) {
        replace(values, clear, null);
    }

    /** Values and, when not null, the master switch in one edit with one undo snapshot. */
    public synchronized void replace(Map<String, String> values, boolean clear, Boolean enabled) {
        Map<String, String> checked = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : values.entrySet()) {
            SettingSpec s = SettingsRegistry.get(e.getKey());
            if (s != null && !s.helperOwned()) checked.put(s.key, s.validate(e.getValue()));
            else if (s != null && s.helperOwned()) continue;
            else if (ProductFlags.known(e.getKey())
                    && (e.getValue().equals("0") || e.getValue().equals("1")))
                checked.put(e.getKey(), e.getValue());
            else throw new IllegalArgumentException("Chave desconhecida: " + e.getKey());
        }
        snapshot();
        SharedPreferences.Editor edit = prefs.edit();
        if (clear)
            for (String key : prefs.getAll().keySet())
                if (key.startsWith(PREFIX) || key.startsWith(BASELINE) || ProductFlags.known(key))
                    edit.remove(key);
        for (Map.Entry<String, String> e : checked.entrySet()) {
            if (ProductFlags.known(e.getKey()))
                edit.putBoolean(e.getKey(), e.getValue().equals("1"));
            else
                edit.putString(PREFIX + e.getKey(), e.getValue())
                        .putString(BASELINE + e.getKey(), SystemPropertyReader.read(e.getKey()));
        }
        if (enabled != null) edit.putBoolean(Config.KEY_ENABLED, enabled);
        edit.putLong("generation", generation() + 1)
                .putLong("lastChange", System.currentTimeMillis())
                .apply();
        notifyChanged();
    }

    /**
     * The app's own compositor writes, confirmed by the helper, become the new baselines so they
     * are not mistaken for a Terminal change. commit(): a restart follows and kills the process.
     */
    @android.annotation.SuppressLint("ApplySharedPref")
    public synchronized void adoptWritten(Map<String, String> written) {
        SharedPreferences.Editor edit = prefs.edit();
        for (Map.Entry<String, String> e : written.entrySet())
            if (prefs.contains(PREFIX + e.getKey())) edit.putString(BASELINE + e.getKey(), e.getValue());
        edit.commit();
    }

    private void snapshot() {
        org.json.JSONObject json = new org.json.JSONObject(overrides());
        prefs.edit()
                .putString("undo", json.toString())
                .putBoolean("undo.enabled", prefs.getBoolean(Config.KEY_ENABLED, false))
                .apply();
    }

    public synchronized boolean undo() {
        String previous = prefs.getString("undo", null);
        if (previous == null) return false;
        boolean enabled = prefs.getBoolean("undo.enabled", false);
        try {
            org.json.JSONObject json = new org.json.JSONObject(previous);
            Map<String, String> map = new LinkedHashMap<>();
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                map.put(key, json.getString(key));
            }
            replace(map, true);
            prefs.edit().putBoolean(Config.KEY_ENABLED, enabled).apply();
            notifyChanged();
            return true;
        } catch (org.json.JSONException e) {
            return false;
        }
    }

    public synchronized void releaseToExternal(SettingSpec s) {
        snapshot();
        prefs.edit()
                .remove(PREFIX + s.key)
                .remove(BASELINE + s.key)
                .putLong("generation", generation() + 1)
                .apply();
        notifyChanged();
    }

    private void notifyChanged() {
        context.getContentResolver().notifyChange(SNAPSHOT, null);
    }
}
