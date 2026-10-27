package io.github.igorcv88.oneuiliquidglass.shots;

import android.content.SharedPreferences;
import java.util.*;

/** In-memory SharedPreferences: layoutlib has no persistent preferences. */
final class MemoryPrefs implements SharedPreferences {
    final Map<String, Object> map = new HashMap<>();
    @Override public Map<String, ?> getAll() { return new HashMap<>(map); }
    @Override public String getString(String k, String d) { Object v = map.get(k); return v instanceof String ? (String) v : d; }
    @Override public Set<String> getStringSet(String k, Set<String> d) { return d; }
    @Override public int getInt(String k, int d) { Object v = map.get(k); return v instanceof Integer ? (Integer) v : d; }
    @Override public long getLong(String k, long d) { Object v = map.get(k); return v instanceof Long ? (Long) v : d; }
    @Override public float getFloat(String k, float d) { Object v = map.get(k); return v instanceof Float ? (Float) v : d; }
    @Override public boolean getBoolean(String k, boolean d) { Object v = map.get(k); return v instanceof Boolean ? (Boolean) v : d; }
    @Override public boolean contains(String k) { return map.containsKey(k); }
    @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}
    @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}
    @Override public Editor edit() {
        Map<String, Object> pending = new HashMap<>();
        Set<String> removed = new HashSet<>();
        return new Editor() {
            @Override public Editor putString(String k, String v) { pending.put(k, v); return this; }
            @Override public Editor putStringSet(String k, Set<String> v) { pending.put(k, v); return this; }
            @Override public Editor putInt(String k, int v) { pending.put(k, v); return this; }
            @Override public Editor putLong(String k, long v) { pending.put(k, v); return this; }
            @Override public Editor putFloat(String k, float v) { pending.put(k, v); return this; }
            @Override public Editor putBoolean(String k, boolean v) { pending.put(k, v); return this; }
            @Override public Editor remove(String k) { removed.add(k); return this; }
            @Override public Editor clear() { map.clear(); return this; }
            @Override public boolean commit() { apply(); return true; }
            @Override public void apply() { for (String k : removed) map.remove(k); map.putAll(pending); }
        };
    }
}
