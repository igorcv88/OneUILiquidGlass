package io.github.igorcv88.oneuiliquidglass.ui;

import android.view.*;
import android.widget.*;

import io.github.igorcv88.oneuiliquidglass.Config;
import io.github.igorcv88.oneuiliquidglass.R;
import io.github.igorcv88.oneuiliquidglass.i18n.AppLanguage;
import io.github.igorcv88.oneuiliquidglass.config.*;
import io.github.igorcv88.oneuiliquidglass.live.SfLive;
import io.github.igorcv88.oneuiliquidglass.ui.kit.*;

/**
 * Ajustes: appearance, the optional dynamic lens, tests, backup, diagnostics and recovery.
 * Everything technical lives here, one tap deeper than the everyday screens.
 */
public final class SettingsPage implements Page {
    private final Host host;
    private final Theme t;
    private final ScrollView scroll;
    private final LinearLayout content;
    private final LinearLayout themeRow, lensRow, syncRow, conflictsRow, iconRow;
    private final View conflictsDivider;
    private final Toggle motion;

    public SettingsPage(Host host) {
        this.host = host;
        t = host.theme();
        android.app.Activity a = host.activity();
        scroll = Ui.scroller(a);
        content = Ui.column(a);
        scroll.addView(content);
        content.addView(Ui.title(a, t, host.activity().getString(R.string.settings)), Ui.match(t.dp(8), t.dp(4)));

        content.addView(Ui.section(a, t, host.activity().getString(R.string.appearance)));
        LinearLayout look = Ui.card(a, t);
        LinearLayout languageRow = Ui.listRow(a, t, Icon.SETTINGS, a.getString(R.string.language),
                AppLanguage.PORTUGUESE.equals(AppLanguage.get(a)) ? "Português (Brasil)" : "English", Ui.chevron(a, t));
        languageRow.setOnClickListener(v -> chooseLanguage());
        look.addView(languageRow);
        look.addView(Ui.divider(a, t, t.dp(72)));
        themeRow = Ui.listRow(a, t, Icon.SUN, host.activity().getString(R.string.theme), "", Ui.chevron(a, t));
        themeRow.setOnClickListener(v -> chooseTheme());
        look.addView(themeRow);
        look.addView(Ui.divider(a, t, t.dp(72)));
        motion = new Toggle(a, t);
        LinearLayout motionRow = Ui.switchRow(a, t, Icon.PULSE, host.activity().getString(R.string.reduce_motion), host.activity().getString(R.string.reduce_motion_desc), motion);
        motion.listener((x, on) -> {
            host.model().ui.edit().putBoolean("reduceMotion", on).apply();
            host.rebuild();
        });
        look.addView(motionRow);
        content.addView(look, Ui.match());

        content.addView(Ui.section(a, t, a.getString(R.string.notification_icons)));
        LinearLayout iconCard = Ui.card(a, t);
        iconRow = Ui.listRow(a, t, Icon.BELL, a.getString(R.string.notification_icons),
                a.getString(R.string.notification_icons_desc), Ui.chevron(a, t));
        iconRow.setOnClickListener(v -> new NotificationIconsSheet(host).show());
        iconCard.addView(iconRow);
        content.addView(iconCard, Ui.match());

        content.addView(Ui.section(a, t, host.activity().getString(R.string.glass)));
        LinearLayout glass = Ui.card(a, t);
        lensRow = Ui.listRow(a, t, Icon.LENS, host.activity().getString(R.string.dynamic_lens), "", Ui.chevron(a, t));
        lensRow.setOnClickListener(v -> host.openLens());
        glass.addView(lensRow);
        glass.addView(Ui.divider(a, t, t.dp(72)));
        LinearLayout tests = Ui.listRow(a, t, Icon.BELL, host.activity().getString(R.string.test_notification), host.activity().getString(R.string.test_notification_desc), Ui.chevron(a, t));
        tests.setOnClickListener(v -> host.openTestOptions());
        glass.addView(tests);
        glass.addView(Ui.divider(a, t, t.dp(72)));
        LinearLayout styles = Ui.listRow(a, t, Icon.PALETTE, host.activity().getString(R.string.styles), host.activity().getString(R.string.styles_desc), Ui.chevron(a, t));
        styles.setOnClickListener(v -> new PresetsSheet(host).show());
        glass.addView(styles);
        content.addView(glass, Ui.match());

        content.addView(Ui.section(a, t, host.activity().getString(R.string.backup)));
        LinearLayout backup = Ui.card(a, t);
        LinearLayout export = Ui.listRow(a, t, Icon.BACKUP, host.activity().getString(R.string.export_settings), host.activity().getString(R.string.export_settings_desc), null);
        export.setOnClickListener(v -> host.exportSettings());
        backup.addView(export);
        backup.addView(Ui.divider(a, t, t.dp(72)));
        LinearLayout imp = Ui.listRow(a, t, Icon.COPY, host.activity().getString(R.string.import_settings), host.activity().getString(R.string.import_settings_desc), null);
        imp.setOnClickListener(v -> host.importSettings());
        backup.addView(imp);
        content.addView(backup, Ui.match());

        content.addView(Ui.section(a, t, host.activity().getString(R.string.diagnostics)));
        LinearLayout diag = Ui.card(a, t);
        syncRow = Ui.listRow(a, t, Icon.PULSE, host.activity().getString(R.string.system_connection), "", Ui.chevron(a, t));
        syncRow.setOnClickListener(v -> host.openSync());
        diag.addView(syncRow);
        conflictsDivider = Ui.divider(a, t, t.dp(72));
        diag.addView(conflictsDivider);
        conflictsRow = Ui.listRow(a, t, Icon.WARNING, host.activity().getString(R.string.external_changes), "", Ui.chevron(a, t));
        conflictsRow.setOnClickListener(v -> host.openConflicts());
        diag.addView(conflictsRow);
        diag.addView(Ui.divider(a, t, t.dp(72)));
        LinearLayout all = Ui.listRow(a, t, Icon.CODE, host.activity().getString(R.string.all_parameters), host.activity().getString(R.string.all_parameters_desc), Ui.chevron(a, t));
        all.setOnClickListener(v -> new SearchSheet(host, Controls.Surface.HEADSUP, true).show());
        diag.addView(all);
        diag.addView(Ui.divider(a, t, t.dp(72)));
        LinearLayout report = Ui.listRow(a, t, Icon.COPY, host.activity().getString(R.string.copy_report), host.activity().getString(R.string.copy_report_desc), null);
        report.setOnClickListener(v -> host.copy(report()));
        diag.addView(report);
        content.addView(diag, Ui.match());

        content.addView(Ui.section(a, t, host.activity().getString(R.string.recovery)));
        LinearLayout rec = Ui.card(a, t);
        LinearLayout safe = Ui.listRow(a, t, Icon.SHIELD, host.activity().getString(R.string.disable_all_glass), host.activity().getString(R.string.disable_all_glass_desc), null);
        safe.setOnClickListener(v -> {
            host.model().master(false);
            host.feedback(host.activity().getString(R.string.glass_disabled));
        });
        rec.addView(safe);
        rec.addView(Ui.divider(a, t, t.dp(72)));
        LinearLayout restart = Ui.listRow(a, t, Icon.RESTART, host.activity().getString(R.string.restart_system_ui), host.activity().getString(R.string.restart_system_ui_desc), null);
        restart.setOnClickListener(v -> InfoSheets.restartSystemUi(host));
        rec.addView(restart);
        rec.addView(Ui.divider(a, t, t.dp(72)));
        LinearLayout soft = Ui.listRow(a, t, Icon.RESTART, host.activity().getString(R.string.restart_userspace), host.activity().getString(R.string.restart_userspace_desc), null);
        soft.setOnClickListener(v -> InfoSheets.softReboot(host));
        rec.addView(soft);
        content.addView(rec, Ui.match());
        TextView help = Ui.text(a, t, a.getString(R.string.settings_recovery_help), Theme.CAPTION, t.inkMuted, false);
        help.setPadding(t.dp(8), t.dp(12), t.dp(8), 0);
        content.addView(help);
        TextView version = Ui.text(a, t, "One UI Liquid Glass " + versionName(a), Theme.CAPTION, t.inkFaint, false);
        version.setPadding(t.dp(8), t.dp(16), t.dp(8), 0);
        content.addView(version);
    }

    private static String versionName(android.content.Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private void chooseTheme() {
        Sheet s = new Sheet(host.activity(), t, host.activity().getString(R.string.theme));
        s.choices(new String[][] {{"system", host.activity().getString(R.string.follow_system), null}, {"light", host.activity().getString(R.string.light_theme), null}, {"dark", host.activity().getString(R.string.dark_theme), null}},
                host.model().ui.getString("theme", "system"), v -> {
                    host.model().ui.edit().putString("theme", v).apply();
                    host.rebuild();
                });
        s.show();
    }

    private void chooseLanguage() {
        Sheet s = new Sheet(host.activity(), t, host.activity().getString(R.string.language));
        s.choices(new String[][] {{AppLanguage.ENGLISH, "English", null},
                        {AppLanguage.PORTUGUESE, "Português (Brasil)", null}},
                AppLanguage.get(host.activity()), host::changeLanguage);
        s.show();
    }

    private String report() {
        AppModel m = host.model();
        ControlState st = m.state();
        StringBuilder r = new StringBuilder("One UI Liquid Glass ").append(versionName(host.activity()))
                .append('\n').append(PresetsSheet.device())
                .append('\n').append(host.activity().getString(R.string.report_glass,
                        host.activity().getString(st.enabled ? R.string.switch_on : R.string.switch_off),
                        HomePage.modeLabel(host.activity(), st.applicability().mode), m.sync().name()))
                .append('\n').append(host.activity().getString(R.string.report_properties,
                        m.observedAvailable() ? "ok" : host.activity().getString(R.string.report_unavailable), String.valueOf(Config.lsposedPrefs)))
                .append('\n').append(host.activity().getString(R.string.report_lens, SfLive.state(m.lens.model().status).name())).append('\n');
        for (SettingSpec s : SettingsRegistry.all()) {
            String saved = st.saved.get(s.key), observed = st.observed.get(s.key);
            r.append(s.fullKey()).append(host.activity().getString(R.string.report_values,
                            s.defaultValue, saved == null ? "-" : saved,
                            observed == null || observed.isEmpty() ? "-" : observed, st.device(s.key)))
                    .append(s.applyMode == SettingSpec.ApplyMode.SF_RECOMPILE && st.pendingRestart(s.key) ? host.activity().getString(R.string.report_pending) : "")
                    .append('\n');
        }
        for (java.util.Map.Entry<String, String> e : st.shownMap().entrySet())
            if (e.getKey().startsWith("surface.")) r.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        return r.toString();
    }

    @Override
    public View view() {
        return scroll;
    }

    @Override
    public ScrollView scroller() {
        return scroll;
    }

    @Override
    public void insets(int top, int bottom) {
        content.setPadding(t.dp(16), top + t.dp(12), t.dp(16), bottom);
    }

    @Override
    public void bind() {
        AppModel m = host.model();
        String theme = m.ui.getString("theme", "system");
        Ui.setSubtitle(themeRow, theme.equals("light") ? host.activity().getString(R.string.light_theme) : theme.equals("dark") ? host.activity().getString(R.string.dark_theme) : host.activity().getString(R.string.follow_system));
        motion.set(m.ui.getBoolean("reduceMotion", false), false);
        String iconMode = m.state().shown("iconmode");
        int modeString = "0".equals(iconMode) ? R.string.notification_icon_off
                : "2".equals(iconMode) ? R.string.notification_icon_originals
                : "3".equals(iconMode) ? R.string.notification_icon_all
                : R.string.notification_icon_system;
        Ui.setSubtitle(iconRow, host.activity().getString(modeString));
        Mode mode = m.mode();
        SfLive.State lens = SfLive.state(m.lens.model().status);
        String lensText = mode == Mode.DYNAMIC_LENS ? host.activity().getString(R.string.active_feminine) : !m.lens.model().bundled ? host.activity().getString(R.string.unavailable_in_build)
                : lens == SfLive.State.NO_ROOT ? host.activity().getString(R.string.optional_requires_root) : lens == SfLive.State.UNSUPPORTED ? host.activity().getString(R.string.unsupported_firmware) : host.activity().getString(R.string.not_enabled);
        int pending = mode == Mode.DYNAMIC_LENS ? m.pendingCompositor().size() : 0;
        if (pending > 0) lensText += pending == 1 ? host.activity().getString(R.string.settings_pending_one) : host.activity().getString(R.string.settings_pending_many, pending);
        Ui.setSubtitle(lensRow, lensText);
        switch (m.sync()) {
            case SYNCED: Ui.setSubtitle(syncRow, host.activity().getString(R.string.settings_read)); break;
            case WAITING: Ui.setSubtitle(syncRow, host.activity().getString(R.string.sending)); break;
            case NO_RESPONSE: Ui.setSubtitle(syncRow, host.activity().getString(R.string.last_change_unconfirmed)); break;
            case NOT_DETECTED: Ui.setSubtitle(syncRow, host.activity().getString(R.string.module_not_detected)); break;
            default: Ui.setSubtitle(syncRow, host.activity().getString(R.string.glass_disabled));
        }
        int conflicts = m.conflicts().size();
        conflictsRow.setVisibility(conflicts == 0 ? View.GONE : View.VISIBLE);
        conflictsDivider.setVisibility(conflicts == 0 ? View.GONE : View.VISIBLE);
        Ui.setSubtitle(conflictsRow, conflicts == 1 ? host.activity().getString(R.string.settings_conflict_one) : host.activity().getString(R.string.settings_conflict_many, conflicts));
    }
}
