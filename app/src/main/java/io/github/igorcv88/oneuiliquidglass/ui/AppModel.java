package io.github.igorcv88.oneuiliquidglass.ui;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import io.github.igorcv88.oneuiliquidglass.Config;
import io.github.igorcv88.oneuiliquidglass.R;
import io.github.igorcv88.oneuiliquidglass.config.*;
import io.github.igorcv88.oneuiliquidglass.config.product.ProductLayout;
import io.github.igorcv88.oneuiliquidglass.config.product.Routes;
import io.github.igorcv88.oneuiliquidglass.config.state.ConfirmedSnapshot;
import io.github.igorcv88.oneuiliquidglass.config.state.EditGuard;
import io.github.igorcv88.oneuiliquidglass.config.state.LensIntent;
import io.github.igorcv88.oneuiliquidglass.live.*;
import io.github.igorcv88.oneuiliquidglass.ui.surfaces.SurfacesPlan;

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

    /**
     * Saves a value edited from a control (row, sheet, exact field). Refuses when the guard, which
     * reads only confirmed values (never a draft), says the control has no effect on that surface
     * right now (INACTIVE, OFF or read-only), so no entry point can edit an inapplicable setting
     * as if it were in effect; the value saved earlier is left untouched. NEEDS_RESTART stays
     * editable.
     */
    public Result saveControl(String key, String value, Controls.Surface surface) {
        return saveControl(key, value, surface, EditGuard.Action.EDIT);
    }

    /** As {@link #saveControl(String, String, Controls.Surface)} for EDIT or RESET_ONE. */
    public Result saveControl(String key, String value, Controls.Surface surface, EditGuard.Action action) {
        EditGuard.Decision d = EditGuard.check(action, key, surface, rules());
        if (d.outcome != EditGuard.Outcome.ALLOW) {
            drafts.remove(key);
            notifyChanged();
            return new Result(false, false, ControlReasons.text(activity, d.reason));
        }
        if (violatesStyleOrder(key, value))
            return new Result(false, false, activity.getString(R.string.error_style_order));
        return save(key, value);
    }

    /** stylelo (back to dark) must stay below stylehi (on to light), judged on the confirmed other limit. */
    private boolean violatesStyleOrder(String key, String value) {
        boolean lo = key.equals("stylelo");
        if (!lo && !key.equals("stylehi")) return false;
        try {
            double mine = Double.parseDouble(value.trim());
            double other = Double.parseDouble(state().confirmed(lo ? "stylehi" : "stylelo"));
            return lo ? mine >= other : other >= mine;
        } catch (NumberFormatException e) {
            return false; // save() rejects the malformed value with its own message.
        }
    }

    /** Validates and saves a desired value; refuses silently overwriting a Terminal change. */
    public Result save(String key, String value) {
        SettingSpec s = SettingsRegistry.get(key);
        drafts.remove(key);
        if (s == null || !Controls.editable(key)) return new Result(false, false, activity.getString(R.string.error_not_editable));
        if (repo.conflict(s)) {
            notifyChanged();
            return new Result(false, true, activity.getString(R.string.error_conflict));
        }
        try {
            repo.save(s, value);
            notifyChanged();
            return new Result(true, false, "");
        } catch (IllegalArgumentException e) {
            notifyChanged();
            return new Result(false, false, activity.getString(R.string.error_invalid_setting, key));
        }
    }

    // ---- glass material (Frosted / Clear): one choice, two compositor selectors

    /** The material both selectors stand for; null when they differ or one is per-card. */
    public GlassMaterial material() {
        return GlassMaterial.of(state()::shown);
    }

    /**
     * Saves the choice as the two selectors in one edit (one undo step) and nothing else: no root,
     * no property write, no restart. The values stay pending until the compositor is recompiled.
     * Refuses when the Terminal changed a selector the app had saved (the existing conflict rule);
     * a selector the app never saved is replaced by an explicit choice, still pending until applied.
     */
    public Result selectMaterial(GlassMaterial material) {
        return saveSelectors(material.selection(), material.id);
    }

    private Result saveSelectors(Map<String, String> selection, String label) {
        for (String key : selection.keySet()) {
            SettingSpec spec = SettingsRegistry.get(key);
            if (spec == null) return new Result(false, false, activity.getString(R.string.error_not_editable));
            if (repo.conflict(spec)) {
                notifyChanged();
                return new Result(false, true, activity.getString(R.string.error_conflict));
            }
        }
        try {
            repo.replace(selection, false, null, ConfigRepository.Source.MATERIAL);
            notifyChanged();
            return new Result(true, false, "");
        } catch (IllegalArgumentException e) {
            notifyChanged();
            return new Result(false, false, activity.getString(R.string.error_invalid_setting, label));
        }
    }

    /** What the running compositor was compiled with (or last sent); null when unknown. */
    public GlassMaterial compiledMaterial() {
        Map<String, String> baseline = CompositorValues.baseline(lensPrefs());
        if (baseline == null) return null;
        GlassMaterial hu = GlassMaterial.ofSelector(baseline.get(GlassMaterial.HU_SELECTOR));
        GlassMaterial kg = GlassMaterial.ofSelector(baseline.get(GlassMaterial.KG_SELECTOR));
        return hu != null && hu == kg ? hu : null;
    }

    public MaterialStatus materialStatus() {
        return MaterialStatus.of(material(), mode(), pendingCompositor().keySet(),
                compositorUnverified(), lastApply());
    }

    /** Whether the choice differs from the material the compositor was last compiled with. */
    public boolean canRevertMaterial() {
        GlassMaterial compiled = compiledMaterial();
        return compiled != null && compiled != material();
    }

    public Result revertMaterial() {
        GlassMaterial compiled = compiledMaterial();
        return compiled == null ? new Result(false, false, "") : selectMaterial(compiled);
    }

    /** Clear parameters the person has not set anywhere: the starting point may be offered. */
    public Map<String, String> recommendedClearOffer() {
        ControlState st = state();
        if (!GlassMaterial.clearValuesPresent(st.saved, st.observed).isEmpty())
            return Collections.emptyMap();
        return GlassMaterial.recommendedClear();
    }

    /** Clear values found on the device (Terminal) that the app has not saved. */
    public Map<String, String> clearValuesOnDevice() {
        ControlState st = state();
        return GlassMaterial.clearValuesOnlyOnDevice(st.saved, st.observed);
    }

    /** Saves {@code values} (a reviewed set of Clear parameters) in one edit; nothing is sent. */
    public Result saveClearValues(Map<String, String> values) {
        if (values.isEmpty()) return new Result(true, false, "");
        for (String key : values.keySet()) {
            SettingSpec spec = SettingsRegistry.get(key);
            if (spec == null || !Controls.editable(key))
                return new Result(false, false, activity.getString(R.string.error_not_editable));
            if (repo.conflict(spec)) {
                notifyChanged();
                return new Result(false, true, activity.getString(R.string.error_conflict));
            }
        }
        try {
            repo.replace(values, false, null, ConfigRepository.Source.ADOPT);
            notifyChanged();
            return new Result(true, false, "");
        } catch (IllegalArgumentException e) {
            notifyChanged();
            return new Result(false, false, activity.getString(R.string.error_invalid_setting, "Clear"));
        }
    }

    /** Saves "per card" (2) as both selectors in one edit; only the technical screen offers it. */
    public Result selectPerCard() {
        Map<String, String> selection = new LinkedHashMap<>();
        selection.put(GlassMaterial.HU_SELECTOR, "2");
        selection.put(GlassMaterial.KG_SELECTOR, "2");
        return saveSelectors(selection, "per-card");
    }

    /** Whether either selector is saved as per-card: the only case where that option is offered outside Technical. */
    public boolean perCardSelected() {
        return "2".equals(state().shown(GlassMaterial.HU_SELECTOR).trim())
                || "2".equals(state().shown(GlassMaterial.KG_SELECTOR).trim());
    }

    private static final String FAVORITES_MIGRATED = "favorites.migrated.r6";

    /**
     * How many saved favorites the surface lists no longer show (technical, diagnostic, legacy), once
     * after the update; 0 afterwards. The favorite flags are never deleted.
     */
    public int takeFavoritesMigrationNotice() {
        if (ui.getBoolean(FAVORITES_MIGRATED, false)) return 0;
        List<String> favored = new ArrayList<>();
        for (Map.Entry<String, ?> e : ui.getAll().entrySet())
            if (e.getKey().startsWith("favorite.") && Boolean.TRUE.equals(e.getValue()))
                favored.add(e.getKey().substring("favorite.".length()));
        ui.edit().putBoolean(FAVORITES_MIGRATED, true).apply();
        return SurfacesPlan.hiddenFavorites(favored).size();
    }

    public void flag(String key, boolean on) {
        repo.flag(key, on);
        notifyChanged();
    }

    public void master(boolean on) {
        repo.flag(Config.KEY_ENABLED, on);
        notifyChanged();
    }

    /** Restores usable keys to their defaults; see {@link #reset(Collection, Controls.Surface)}. */
    public List<String> reset(Collection<String> keys) {
        return reset(keys, null);
    }

    /**
     * Restores to the code default the keys the guard allows (RESET_ONE, rules from confirmed
     * values) in one transaction. Inactive, off and read-only keys keep their saved value.
     *
     * @return the keys left alone
     */
    public List<String> reset(Collection<String> keys, Controls.Surface surface) {
        Applicability rules = rules();
        List<String> approved = new ArrayList<>(), refused = new ArrayList<>();
        for (String key : keys)
            (EditGuard.check(EditGuard.Action.RESET_ONE, key, surface, rules).outcome == EditGuard.Outcome.ALLOW
                    ? approved : refused).add(key);
        drafts.keySet().removeAll(approved);
        repo.resetToDefaults(approved);
        notifyChanged();
        return refused;
    }

    /**
     * Restores a group of keys (a parent and its children) to their defaults in one transaction, one
     * undo step. Keys the guard skips (RESET_GROUP: inactive, off or read-only) keep their saved value.
     *
     * @return the keys left alone, for the confirmation text
     */
    public List<String> resetGroup(Collection<String> keys, Controls.Surface surface) {
        Applicability rules = rules();
        List<String> approved = new ArrayList<>(), skipped = new ArrayList<>();
        for (String key : keys)
            (EditGuard.check(EditGuard.Action.RESET_GROUP, key, surface, rules).outcome == EditGuard.Outcome.ALLOW
                    ? approved : skipped).add(key);
        drafts.keySet().removeAll(approved);
        repo.resetToDefaults(approved);
        notifyChanged();
        return skipped;
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

    /** Result of revalidating an Apply confirmation against the current list. */
    public enum PlanCheck { OK, STALE, CONTRADICTION, BLOCKED }

    private PlanContradiction contradiction;

    /**
     * What is confirmed now, with no draft: saved values, the properties read last, the evidence of
     * the last compile and the helper status. Reads only thread-safe state, so the lens worker
     * can rebuild it for a fresh status.
     */
    public ConfirmedSnapshot confirmedSnapshot(Map<String, String> status) {
        SharedPreferences p = lensPrefs();
        ControlState st = new ControlState(repo.overrides(), SystemPropertyReader.snapshot(),
                Collections.<String, String>emptyMap(), repo.preferences().getBoolean(Config.KEY_ENABLED, false));
        return st.snapshot(CompiledEvidence.from(p, status), status,
                CompositorValues.awaitingVerification(p), repo.generation());
    }

    public ConfirmedSnapshot confirmedSnapshot() {
        return confirmedSnapshot(lens.model().status);
    }

    /** Whether the person means to use the lens: compositor controls are then judged on the next compile. */
    public boolean lensIntent() {
        return LensIntent.of(confirmedSnapshot(), lensPrefs());
    }

    /** The rules every edit entry point uses: confirmed values only, the lens intent taken into account. */
    public Applicability rules() {
        return state().applicability(lensIntent());
    }

    /** The evidence of the last confirmed compile, current only for the loaded compositor process. */
    public CompiledEvidence compiledEvidence() {
        return confirmedSnapshot().compiled;
    }

    /**
     * What the next Apply writes and recompiles, from the confirmed state only (no drafts), the
     * compile evidence and the properties read last. Null when the saved values contradict the
     * rules; see {@link #contradiction()}.
     */
    public PublicationPlan plan() {
        try {
            PublicationPlan plan = PublicationPlan.of(confirmedSnapshot(), Applicability::new);
            contradiction = null;
            return plan;
        } catch (PlanContradiction e) {
            contradiction = e;
            return null;
        }
    }

    /**
     * The fingerprint of the plan for a helper status read just now (safe on the worker thread);
     * null when the plan is contradictory. The lens controller compares it with the confirmed one
     * before writing anything.
     */
    public String planFingerprint(Map<String, String> freshStatus) {
        try {
            return PublicationPlan.of(confirmedSnapshot(freshStatus), Applicability::new).fingerprint;
        } catch (PlanContradiction e) {
            return null;
        }
    }

    /** The layout route of a surface and whether the lens it shows is not loaded yet. */
    public static final class SurfaceRoute {
        public final ProductLayout.Route route;
        /** The lens routes are shown from the saved intent, but the lens is not loaded now. */
        public final boolean lensNotLoaded;

        SurfaceRoute(ProductLayout.Route route, boolean lensNotLoaded) {
            this.route = route;
            this.lensNotLoaded = lensNotLoaded;
        }
    }

    /**
     * The route a surface page lays out, from confirmed values only. With the lens intent the next
     * compile's values decide (the lens routes stay visible and editable while it is not loaded).
     */
    public SurfaceRoute surfaceRoute(Controls.Surface surface) {
        ConfirmedSnapshot snapshot = confirmedSnapshot();
        boolean intent = LensIntent.of(snapshot, lensPrefs());
        ProductLayout.Route route = Routes.of(intent ? snapshot.nextReader() : snapshot::value, surface);
        boolean lens = route != ProductLayout.Route.STANDARD && route != ProductLayout.Route.CAPTURE
                && route != ProductLayout.Route.PLAIN_BLUR;
        return new SurfaceRoute(route, intent && lens && !snapshot.lensLoaded());
    }

    /** One plan and the confirmed state for rendering many rows; never a draft. */
    EffectiveView effectiveView() {
        return new EffectiveView(state(), plan(), lensIntent());
    }

    /** What the userspace restart that reloads the lens would write, from the same plan as Apply. */
    public static final class Reinject {
        public enum Outcome { START, BLOCKED, CONTRADICTION }

        public final Outcome outcome;
        public final Map<String, String> writes;
        public final String fingerprint;

        Reinject(Outcome outcome, Map<String, String> writes, String fingerprint) {
            this.outcome = outcome;
            this.writes = Collections.unmodifiableMap(new LinkedHashMap<>(writes));
            this.fingerprint = fingerprint;
        }
    }

    /**
     * The restart that reloads the lens writes the plan's values and checks the plan's fingerprint
     * before writing, like the Apply dialog: after a reboot the properties are gone, so a restart
     * that writes nothing would compile defaults. The caller refreshes the properties first.
     */
    public Reinject reinjectPlan() {
        PublicationPlan plan = plan();
        if (plan == null) return new Reinject(Reinject.Outcome.CONTRADICTION, Collections.<String, String>emptyMap(), null);
        if (plan.blocked) return new Reinject(Reinject.Outcome.BLOCKED, Collections.<String, String>emptyMap(), plan.fingerprint);
        return new Reinject(Reinject.Outcome.START, plan.writeValues(), plan.fingerprint);
    }

    /** The contradiction found by the last {@link #plan()}, or null. */
    public PlanContradiction contradiction() {
        return contradiction;
    }

    /**
     * Revalidates a confirmation shown for {@code shownFingerprint} against what is saved and read
     * now. The caller refreshes {@link SystemPropertyReader} off the UI thread first.
     */
    public PlanCheck confirmPlan(String shownFingerprint) {
        cached = null;
        PublicationPlan now = plan();
        if (now == null) return PlanCheck.CONTRADICTION;
        if (now.blocked) return PlanCheck.BLOCKED;
        return now.fingerprint.equals(shownFingerprint) ? PlanCheck.OK : PlanCheck.STALE;
    }

    /** Compositor values to list on the next restart: the plan writes and drift. */
    public Map<String, String> pendingCompositor() {
        PublicationPlan plan = plan();
        return plan == null ? Collections.<String, String>emptyMap() : plan.listed();
    }

    // ---- backup, styles and undo: one plan, reviewed, then applied as reviewed

    public ImportPlan importPlan(PresetCodec.Parsed parsed, ImportPlan.Mode mode,
            boolean useBackupDefaults, boolean adoptTerminal) {
        return ImportPlan.of(confirmedSnapshot(), parsed, mode, useBackupDefaults, adoptTerminal);
    }

    /** What Undo would do, as a review; null when there is nothing to undo. */
    public ImportPlan undoPlan() {
        ConfigRepository.UndoSnapshot snapshot = repo.undoSnapshot();
        return snapshot == null ? null : ImportPlan.ofSnapshot(confirmedSnapshot(), snapshot.overrides, snapshot.enabled);
    }

    /** Saves what the plan decided in one transaction; nothing is published or restarted. */
    public void applyPlan(ImportPlan plan, ConfigRepository.Source source) {
        repo.replaceAll(plan.result, plan.enabled, source);
        drafts.clear();
        notifyChanged();
    }

    /**
     * Saves, as the person own value, one an earlier session or the Terminal had set. Goes through
     * the guard (ADOPT_EXTERNAL) and records the adoption as its provenance.
     */
    public Result adoptExternal(String key, String value) {
        SettingSpec s = SettingsRegistry.get(key);
        if (s == null || !Controls.editable(key))
            return new Result(false, false, activity.getString(R.string.error_not_editable));
        EditGuard.Decision d = EditGuard.check(EditGuard.Action.ADOPT_EXTERNAL, key, null, rules());
        if (!d.permitted()) return new Result(false, false, ControlReasons.text(activity, d.reason));
        try {
            repo.save(s, value, ConfigRepository.Source.ADOPT);
            notifyChanged();
            return new Result(true, false, "");
        } catch (IllegalArgumentException e) {
            return new Result(false, false, activity.getString(R.string.error_invalid_setting, key));
        }
    }

    public Map<String, String> pendingIcons() { return repo.pendingIcons(); }

    public String appliedIcon(String key) {
        SettingSpec spec = SettingsRegistry.get(key);
        return spec == null ? "" : repo.appliedIcon(spec);
    }

    public void applyIcons() {
        repo.applyIcons();
        notifyChanged();
    }

    /** The value the compositor last compiled (or was last sent) for a key; null when unknown. */
    public String compiledCompositor(String key) {
        Map<String, String> b = CompositorValues.baseline(lensPrefs());
        return b == null ? null : b.get(key);
    }

    private final Runnable verifyRestart = this::verifyRestartNow;
    /** Automatic status reads per restart, 30 s apart from 125 s after the request. */
    private static final int AUTO_VERIFY_TRIES = 3;
    private static final long AUTO_VERIFY_FIRST_MS = 125_000, AUTO_VERIFY_GAP_MS = 30_000, AUTO_VERIFY_BUSY_MS = 5_000;

    /**
     * After a restart requested from the app, reads the helper's status once the injector's
     * 120 s health monitor should have ended, so the parameters it sent are confirmed (or not)
     * without the user having to ask; a status read before that cannot report a stable process.
     * The monitor's clock starts only after SurfaceFlinger stopped (up to ~15 s after the request),
     * so up to three reads are made, 30 s apart, and each counts only once it is actually issued.
     */
    public void scheduleRestartVerification() {
        main.removeCallbacks(verifyRestart);
        SharedPreferences p = lensPrefs();
        if (!CompositorValues.awaitingVerification(p) || autoVerifyTries(p) >= AUTO_VERIFY_TRIES) return;
        long requested = p.getLong("restartRequestedAt", 0);
        long due = requested + AUTO_VERIFY_FIRST_MS + autoVerifyTries(p) * AUTO_VERIFY_GAP_MS
                - System.currentTimeMillis();
        main.postDelayed(verifyRestart, Math.max(due, 0));
    }

    public void cancelRestartVerification() {
        main.removeCallbacks(verifyRestart);
    }

    private static int autoVerifyTries(SharedPreferences p) {
        return p.getLong("autoVerifiedFor", 0) == p.getLong("restartRequestedAt", 0)
                ? p.getInt("autoVerifyTries", 0) : 0;
    }

    private void verifyRestartNow() {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        SharedPreferences p = lensPrefs();
        if (!CompositorValues.awaitingVerification(p) || autoVerifyTries(p) >= AUTO_VERIFY_TRIES) return;
        // A running lens or root operation would drop the request: retry shortly, uncounted.
        if (lens.model().busy || root.busy()) {
            main.postDelayed(verifyRestart, AUTO_VERIFY_BUSY_MS);
            return;
        }
        p.edit().putLong("autoVerifiedFor", p.getLong("restartRequestedAt", 0))
                .putInt("autoVerifyTries", autoVerifyTries(p) + 1).apply();
        // The restart killed this process: root was granted to it before (the restart itself ran
        // through su), so the check does not ask again.
        if (root.granted()) lens.status();
        else root.check(ok -> { if (ok) lens.status(); });
        // The next read, if this one does not confirm the restart (checked when it is due).
        scheduleRestartVerification();
    }

    /** With no acknowledged restart, equal setprop values do not prove the shader uses them. */
    public boolean compositorUnverified() {
        SharedPreferences p = lensPrefs();
        Map<String, String> status = lens.model().status;
        // Earlier versions recorded a compile baseline before Clear selectors were writable.
        // A matching getprop is not proof that those selectors were compiled into the shader.
        Map<String, String> compiled = CompositorValues.compiledValues(p);
        return !compiled.containsKey(GlassMaterial.HU_SELECTOR)
                || !compiled.containsKey(GlassMaterial.KG_SELECTOR)
                || !CompositorValues.hasCompiled(p)
                || !"1".equals(status.get("sf_loaded"))
                || !"1".equals(status.get("sf_got"))
                || !"1".equals(status.get("sf_rewrite_ok"))
                || !"1".equals(status.get("sf_stable"))
                || !p.getString("compiledGeneration", "").equals(status.get("sf_generation"))
                || p.getLong("restartRequestedAt", 0) > p.getLong("compiledAt", 0);
    }

    public SharedPreferences lensPrefs() {
        return activity.getSharedPreferences("sf_live_ui", Activity.MODE_PRIVATE);
    }

    public CompositorValues.Outcome lastApply() {
        if (!SystemPropertyReader.available()) return CompositorValues.Outcome.UNVERIFIED;
        SharedPreferences p = lensPrefs();
        if (CompositorValues.hasCompiled(p) && !lens.model().status.isEmpty()
                && !p.getString("compiledGeneration", "").equals(lens.model().status.get("sf_generation")))
            return CompositorValues.Outcome.UNVERIFIED;
        return CompositorValues.verify(p.getLong("restartRequestedAt", 0),
                CompositorValues.attemptValues(p), lens.model().status,
                SystemPropertyReader.snapshot(), p.getString("restartBeforePid", ""));
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
