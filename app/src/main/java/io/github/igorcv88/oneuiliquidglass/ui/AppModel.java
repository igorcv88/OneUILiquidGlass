package io.github.igorcv88.oneuiliquidglass.ui;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import io.github.igorcv88.oneuiliquidglass.Config;
import io.github.igorcv88.oneuiliquidglass.config.*;
import io.github.igorcv88.oneuiliquidglass.live.*;

import java.util.*;
import java.util.concurrent.ExecutorService;

/**
 * The single source of UI state: saved values, observed properties, drafts, product flags, the
 * system's read acknowledgement and the dynamic lens. Screens read {@link #state()} and listen;
 * nothing rebuilds the whole hierarchy on a change.
 */
public final class AppModel {
    /** What the person can rely on about delivery to SystemUI. */
    public enum Sync {
        /** Glass is off: nothing to deliver. */
        OFF,
        /** SystemUI read the latest generation (a read, not proof of a drawn effect). */
        SYNCED,
        /** A change was made moments ago; the read is expected within a poll. */
        WAITING,
        /** SystemUI answered before but not for the latest change. */
        NO_RESPONSE,
        /** SystemUI never answered: module inactive or not in scope. */
        NOT_DETECTED
    }

    public interface Listener {
        void changed();
    }

    public final Activity activity;
    public final ConfigRepository repo;
    public final SfLiveController lens;
    public final RootAccess root;
    public final SharedPreferences ui;
    private final ExecutorService io;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, String> drafts = new HashMap<>();
    private final List<Listener> listeners = new ArrayList<>();
    private ControlState cached;
    private Sync lastSync;
    private boolean observedOnce;

    public AppModel(Activity a, ExecutorService io) {
        activity = a;
        this.io = io;
        repo = new ConfigRepository(a);
        lens = new SfLiveController(a, io);
        root = new RootAccess(a, io);
        ui = a.getSharedPreferences("ui", Activity.MODE_PRIVATE);
        lens.listen(m -> notifyChanged());
        root.listen(this::notifyChanged);
    }

    public void listen(Listener l) {
        listeners.add(l);
    }

    public void unlisten(Listener l) {
        listeners.remove(l);
    }

    public void notifyChanged() {
        cached = null;
        for (Listener l : new ArrayList<>(listeners)) l.changed();
    }

    public ControlState state() {
        if (cached == null)
            cached = new ControlState(repo.overrides(), SystemPropertyReader.snapshot(), drafts,
                    repo.preferences().getBoolean(Config.KEY_ENABLED, false));
        return cached;
    }

    public boolean observedAvailable() {
        return SystemPropertyReader.available();
    }

    public boolean observedOnce() {
        return observedOnce;
    }

    /** Reads debug.oulg.* off the UI thread, then notifies. */
    public void refreshObserved(Runnable done) {
        io.execute(() -> {
            SystemPropertyReader.refresh();
            main.post(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                observedOnce = true;
                notifyChanged();
                if (done != null) done.run();
            });
        });
    }

    // ---- drafts: preview only, never saved, never sent

    public void draft(String key, String value) {
        if (value == null) drafts.remove(key);
        else drafts.put(key, value);
        cached = null;
    }

    public void clearDrafts() {
        drafts.clear();
        cached = null;
    }

    // ---- writes

    public static final class Result {
        public final boolean ok, conflict;
        public final String message;

        Result(boolean ok, boolean conflict, String message) {
            this.ok = ok;
            this.conflict = conflict;
            this.message = message;
        }
    }

    /** Validates and saves a desired value; refuses silently overwriting a Terminal change. */
    public Result save(String key, String value) {
        SettingSpec s = SettingsRegistry.get(key);
        drafts.remove(key);
        if (s == null || !Controls.editable(key)) return new Result(false, false, "Não editável");
        if (repo.conflict(s)) {
            notifyChanged();
            return new Result(false, true, "Alterado fora do app. Revise antes de salvar.");
        }
        try {
            repo.save(s, value);
            notifyChanged();
            return new Result(true, false, "");
        } catch (IllegalArgumentException e) {
            notifyChanged();
            return new Result(false, false, e.getMessage());
        }
    }

    public void flag(String key, boolean on) {
        repo.flag(key, on);
        notifyChanged();
    }

    public void master(boolean on) {
        repo.flag(Config.KEY_ENABLED, on);
        notifyChanged();
    }

    public void reset(Collection<String> keys) {
        repo.resetToDefaults(keys);
        notifyChanged();
    }

    public boolean undo() {
        boolean ok = repo.undo();
        notifyChanged();
        return ok;
    }

    /** Drops the app's value so the Terminal's property is used again. */
    public void useExternal(String key) {
        SettingSpec s = SettingsRegistry.get(key);
        if (s != null) repo.releaseToExternal(s);
        notifyChanged();
    }

    /** Keeps the app's value and accepts the current property as the new baseline. */
    public void keepApp(String key) {
        SettingSpec s = SettingsRegistry.get(key);
        String v = s == null ? null : repo.preferences().getString(ConfigRepository.PREFIX + key, null);
        if (v != null) {
            repo.preferences().edit().putString(ConfigRepository.BASELINE + key, SystemPropertyReader.read(key)).apply();
            repo.save(s, v);
        }
        notifyChanged();
    }

    public List<String> conflicts() {
        List<String> out = new ArrayList<>();
        if (!SystemPropertyReader.available()) return out;
        for (SettingSpec s : SettingsRegistry.all()) if (repo.conflict(s)) out.add(s.key);
        return out;
    }

    // ---- delivery to SystemUI

    public Sync sync() {
        if (!state().enabled) return Sync.OFF;
        SharedPreferences ack = activity.getSharedPreferences("engine_status", Activity.MODE_PRIVATE);
        long readAt = ack.getLong("readAt", 0), read = ack.getLong("readGeneration", -1);
        long now = System.currentTimeMillis();
        if (readAt == 0) return Sync.NOT_DETECTED;
        if (read >= repo.generation()) return Sync.SYNCED;
        // ConfigClient re-reads on the provider's change notification and every 10 s.
        if (now - repo.lastChange() < 15000) return Sync.WAITING;
        return Sync.NO_RESPONSE;
    }

    /** Poll target: notifies only when the sync state changes. */
    public void pollSync() {
        Sync s = sync();
        if (s != lastSync) {
            lastSync = s;
            notifyChanged();
        }
    }

    // ---- dynamic lens

    public Map<String, String> pendingCompositor() {
        return CompositorValues.pending(state().saved, SystemPropertyReader.snapshot());
    }

    public SharedPreferences lensPrefs() {
        return activity.getSharedPreferences("sf_live_ui", Activity.MODE_PRIVATE);
    }

    public CompositorValues.Outcome lastApply() {
        SharedPreferences p = lensPrefs();
        return CompositorValues.verify(p.getLong("restartRequestedAt", 0),
                CompositorValues.attemptValues(p), lens.model().status, SystemPropertyReader.snapshot());
    }

    public long lastApplyAt() {
        return lensPrefs().getLong("restartRequestedAt", 0);
    }

    public Mode mode() {
        return state().applicability().mode;
    }

    public void close() {
        lens.close();
        root.close();
        main.removeCallbacksAndMessages(null);
        listeners.clear();
    }
}
