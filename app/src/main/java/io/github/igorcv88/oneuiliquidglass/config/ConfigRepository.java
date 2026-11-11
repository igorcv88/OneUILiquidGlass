package io.github.igorcv88.oneuiliquidglass.config;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.Settings;

import io.github.igorcv88.oneuiliquidglass.Config;

import java.util.*;

/** Desired overrides only: opening the app never installs defaults or adopts external props. */
public final class ConfigRepository {
    public static final int SCHEMA_VERSION = 1;
    public static final Uri SNAPSHOT = Uri.parse("content://" + Config.AUTHORITY + "/v1/snapshot");
    public static final String PREFIX = "desired.", BASELINE = "baseline.";
    private static final String BASELINE_BOOT = "baselineBoot.";
    private final Context context;
    private final SharedPreferences prefs;
    private final int bootCount;

    public ConfigRepository(Context context) {
        this.context = context.getApplicationContext();
        prefs = Config.open(context);
        int count;
        try {
            count = Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT);
        } catch (Settings.SettingNotFoundException | SecurityException e) {
            count = ExternalChanges.UNKNOWN_BOOT;
        }
        bootCount = count;
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
            // Icon edits are held back from the running SystemUI until explicit Apply.
            Object applied = all.get(IconSettings.APPLIED_PREFIX + spec.key);
            if (applied instanceof String) raw = IconSettings.ABSENT.equals(applied) ? null : applied;
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
        Map<String, String> desired = new LinkedHashMap<>();
        Map<String, ?> all = prefs.getAll();
        for (SettingSpec spec : SettingsRegistry.all()) {
            Object raw = all.get(PREFIX + spec.key);
            if (raw instanceof String && spec.type != SettingSpec.Type.READONLY && !spec.helperOwned()) {
                try { desired.put(spec.key, spec.validate((String) raw)); }
                catch (IllegalArgumentException ignored) { }
            }
        }
        for (Map.Entry<String, ?> e : all.entrySet())
            if (ProductFlags.known(e.getKey()) && e.getValue() instanceof Boolean)
                desired.put(e.getKey(), (Boolean) e.getValue() ? "1" : "0");
        return desired;
    }

    /** Pending icon values, including values that reset to the OS/default. */
    public Map<String, String> pendingIcons() {
        Map<String, ?> all = prefs.getAll();
        Map<String, String> pending = new LinkedHashMap<>();
        for (String key : IconSettings.keys()) {
            Object before = all.get(IconSettings.APPLIED_PREFIX + key);
            if (!(before instanceof String)) continue;
            String old = IconSettings.ABSENT.equals(before) ? null : (String) before;
            String desired = (String) all.get(PREFIX + key);
            if (!Objects.equals(old, desired)) pending.put(key, desired);
        }
        return pending;
    }

    /** Returns the effective value currently delivered to SystemUI, for the preview. */
    public String appliedIcon(SettingSpec spec) {
        String staged = prefs.getString(IconSettings.APPLIED_PREFIX + spec.key, null);
        if (staged != null) {
            if (!IconSettings.ABSENT.equals(staged)) return staged;
            String external = SystemPropertyReader.read(spec.key);
            return external.isEmpty() ? spec.defaultValue : external;
        }
        String saved = prefs.getString(PREFIX + spec.key, null);
        return saved == null ? desired(spec) : saved;
    }

    /** Commit staged icon settings atomically; the caller then requests one SystemUI restart. */
    @android.annotation.SuppressLint("ApplySharedPref")
    public synchronized void applyIcons() {
        if (pendingIcons().isEmpty()) return;
        SharedPreferences.Editor edit = prefs.edit();
        for (String key : IconSettings.keys()) edit.remove(IconSettings.APPLIED_PREFIX + key);
        edit.putLong("generation", generation() + 1)
                .putLong("lastChange", System.currentTimeMillis()).commit();
        notifyChanged();
    }

    public boolean conflict(SettingSpec s) {
        String baseline = prefs.getString(BASELINE + s.key, null);
        return ExternalChanges.conflict(s, baseline, prefs.getString(PREFIX + s.key, null),
                SystemPropertyReader.read(s.key),
                prefs.getInt(BASELINE_BOOT + s.key, ExternalChanges.UNKNOWN_BOOT), bootCount);
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

    /** A locally imported icon changed; notify SystemUI without changing the selected settings. */
    public synchronized void iconAssetsChanged() {
        prefs.edit().putLong("generation", generation() + 1)
                .putLong("lastChange", System.currentTimeMillis()).apply();
        notifyChanged();
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
        boolean affectsRunningEngine = clear || enabled != null;
        for (String key : checked.keySet())
            if (!IconSettings.contains(key)) affectsRunningEngine = true;
        snapshot();
        SharedPreferences.Editor edit = prefs.edit();
        for (String key : IconSettings.keys()) {
            if (!checked.containsKey(key) && !clear) continue;
            String marker = IconSettings.APPLIED_PREFIX + key;
            if (!prefs.contains(marker)) {
                String before = prefs.getString(PREFIX + key, null);
                edit.putString(marker, before == null ? IconSettings.ABSENT : before);
            }
        }
        if (clear)
            for (String key : prefs.getAll().keySet())
                if (key.startsWith(PREFIX) || key.startsWith(BASELINE)
                        || key.startsWith(BASELINE_BOOT) || ProductFlags.known(key))
                    edit.remove(key);
        for (Map.Entry<String, String> e : checked.entrySet()) {
            if (ProductFlags.known(e.getKey()))
                edit.putBoolean(e.getKey(), e.getValue().equals("1"));
            else
                edit.putString(PREFIX + e.getKey(), e.getValue())
                        .putString(BASELINE + e.getKey(), SystemPropertyReader.read(e.getKey()))
                        .putInt(BASELINE_BOOT + e.getKey(), bootCount);
        }
        if (enabled != null) edit.putBoolean(Config.KEY_ENABLED, enabled);
        if (affectsRunningEngine) edit.putLong("generation", generation() + 1)
                .putLong("lastChange", System.currentTimeMillis());
        edit.apply();
        if (affectsRunningEngine) notifyChanged();
    }

    /**
     * The app's own compositor writes, confirmed by the helper, become the new baselines so they
     * are not mistaken for a Terminal change. commit(): a restart follows and kills the process.
     */
    @android.annotation.SuppressLint("ApplySharedPref")
    public synchronized void adoptWritten(Map<String, String> written) {
        SharedPreferences.Editor edit = prefs.edit();
        for (Map.Entry<String, String> e : written.entrySet())
            if (prefs.contains(PREFIX + e.getKey())) edit.putString(BASELINE + e.getKey(), e.getValue())
                    .putInt(BASELINE_BOOT + e.getKey(), bootCount);
        edit.commit();
    }

    private void snapshot() {
        // Undo refers to the visible desired edits, including un-applied icon values.
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
        SharedPreferences.Editor edit = prefs.edit();
        if (IconSettings.contains(s.key) && !prefs.contains(IconSettings.APPLIED_PREFIX + s.key)) {
            String before = prefs.getString(PREFIX + s.key, null);
            edit.putString(IconSettings.APPLIED_PREFIX + s.key,
                    before == null ? IconSettings.ABSENT : before);
        }
        edit.remove(PREFIX + s.key).remove(BASELINE + s.key).remove(BASELINE_BOOT + s.key);
        if (!IconSettings.contains(s.key)) edit.putLong("generation", generation() + 1);
        edit.apply();
        if (!IconSettings.contains(s.key)) notifyChanged();
    }

    private void notifyChanged() {
        context.getContentResolver().notifyChange(SNAPSHOT, null);
    }
}
