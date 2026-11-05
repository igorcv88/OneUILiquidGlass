package io.github.igorcv88.oneuiliquidglass.ui;

import android.content.Intent;
import android.provider.Settings;
import android.widget.*;

import io.github.igorcv88.oneuiliquidglass.TestNotifications;
import io.github.igorcv88.oneuiliquidglass.R;
import io.github.igorcv88.oneuiliquidglass.config.*;
import io.github.igorcv88.oneuiliquidglass.ui.kit.*;

import java.util.List;

/** Secondary sheets: test variants, delivery to the system, values changed outside the app. */
final class InfoSheets {
    private InfoSheets() {}

    static void tests(Host host) {
        testsSheet(host).show();
    }

    static Sheet testsSheet(Host host) {
        Theme t = host.theme();
        android.app.Activity a = host.activity();
        Sheet s = new Sheet(a, t, host.activity().getString(R.string.test_options));
        LinearLayout card = Ui.card(a, t);
        LinearLayout later = Ui.listRow(a, t, Icon.CLOCK, host.activity().getString(R.string.test_delay), host.activity().getString(R.string.test_delay_desc), null);
        later.setOnClickListener(v -> {
            s.dismiss();
            host.requestTest(1, 5000);
        });
        card.addView(later);
        card.addView(Ui.divider(a, t, t.dp(72)));
        LinearLayout many = Ui.listRow(a, t, Icon.STACK, host.activity().getString(R.string.test_many), host.activity().getString(R.string.test_many_desc), null);
        many.setOnClickListener(v -> {
            s.dismiss();
            host.requestTest(3, 0);
        });
        card.addView(many);
        card.addView(Ui.divider(a, t, t.dp(72)));
        LinearLayout clear = Ui.listRow(a, t, Icon.CLOSE, host.activity().getString(R.string.test_remove),
                TestNotifications.hasScheduled() ? host.activity().getString(R.string.test_cancel_scheduled) : host.activity().getString(R.string.test_only_app), null);
        clear.setOnClickListener(v -> {
            TestNotifications.clear(a);
            host.feedback(host.activity().getString(R.string.test_removed));
            s.dismiss();
        });
        card.addView(clear);
        card.addView(Ui.divider(a, t, t.dp(72)));
        LinearLayout channel = Ui.listRow(a, t, Icon.SETTINGS, host.activity().getString(R.string.test_channel), host.activity().getString(R.string.test_channel_desc), Ui.chevron(a, t));
        channel.setOnClickListener(v -> {
            TestNotifications.channel(a);
            a.startActivity(new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, a.getPackageName())
                    .putExtra(Settings.EXTRA_CHANNEL_ID, TestNotifications.CHANNEL));
        });
        card.addView(channel);
        s.body().addView(card, Ui.match());
        TextView help = Ui.text(a, t, a.getString(R.string.test_help),
                Theme.CAPTION, t.inkMuted, false);
        help.setPadding(t.dp(8), t.dp(14), t.dp(8), 0);
        s.body().addView(help);
        return s;
    }

    /** Why the system has not confirmed the latest settings, and what to do. */
    static void sync(Host host) {
        Theme t = host.theme();
        android.app.Activity a = host.activity();
        AppModel m = host.model();
        AppModel.Sync sync = m.sync();
        Sheet s = new Sheet(a, t, sync == AppModel.Sync.SYNCED ? host.activity().getString(R.string.sync_ok) : host.activity().getString(R.string.sync_unconfirmed));
        String text;
        switch (sync) {
            case NOT_DETECTED:
                text = a.getString(R.string.sync_not_detected_text);
                break;
            case NO_RESPONSE:
                text = a.getString(R.string.sync_no_response_text);
                break;
            case WAITING:
                text = a.getString(R.string.sync_waiting_text);
                break;
            case OFF:
                text = a.getString(R.string.sync_off_text);
                break;
            default:
                text = a.getString(R.string.sync_confirmed_text);
        }
        s.text(text);
        if (sync == AppModel.Sync.NO_RESPONSE || sync == AppModel.Sync.NOT_DETECTED)
            s.action(host.activity().getString(R.string.restart_ui_action), Ui.ButtonStyle.TONAL, () -> {
                s.dismiss();
                restartSystemUi(host);
            });
        s.action(host.activity().getString(R.string.action_close), Ui.ButtonStyle.OUTLINED, s::dismiss);
        s.show();
    }

    /** SystemUI restart: fixed root command, explicit confirmation, no automation. */
    static void restartSystemUi(Host host) {
        Theme t = host.theme();
        Sheet s = new Sheet(host.activity(), t, host.activity().getString(R.string.restart_ui_title));
        s.text(host.activity().getString(R.string.restart_ui_warning));
        s.action(host.activity().getString(R.string.action_cancel), Ui.ButtonStyle.OUTLINED, s::dismiss);
        s.action(host.activity().getString(R.string.restart_ui_button), Ui.ButtonStyle.FILLED, () -> {
            s.dismiss();
            AppModel m = host.model();
            Runnable restart = () -> m.root.restartSystemUi(ok -> host.feedback(ok ? host.activity().getString(R.string.restart_ui_done) : host.activity().getString(R.string.restart_error, m.root.message())));
            if (m.root.granted()) restart.run();
            else m.root.check(ok -> {
                if (ok) restart.run();
                else host.feedback(host.activity().getString(R.string.root_not_granted));
            });
        });
        s.show();
    }

    /** "Soft reboot": fixed root command, informed confirmation, no automation. */
    static void softReboot(Host host) {
        Theme t = host.theme();
        android.app.Activity a = host.activity();
        Sheet s = new Sheet(a, t, host.activity().getString(R.string.restart_userspace_title));
        s.text(a.getString(R.string.restart_userspace_warning));
        Toggle ack = new Toggle(a, t);
        LinearLayout card = Ui.card(a, t);
        card.addView(Ui.switchRow(a, t, null, host.activity().getString(R.string.restart_saved_ack), null, ack));
        s.body().addView(card, Ui.match(t.dp(4), 0));
        s.action(host.activity().getString(R.string.action_cancel), Ui.ButtonStyle.OUTLINED, s::dismiss);
        TextView go = s.action(host.activity().getString(R.string.restart_userspace), Ui.ButtonStyle.FILLED, () -> {
            s.dismiss();
            AppModel m = host.model();
            Runnable restart = () -> m.root.softReboot(ok -> { if (!ok) host.feedback(host.activity().getString(R.string.restart_error, m.root.message())); });
            if (m.root.granted()) restart.run();
            else m.root.check(ok -> {
                if (ok) restart.run();
                else host.feedback(host.activity().getString(R.string.root_not_granted));
            });
        });
        go.setEnabled(false);
        ack.listener((x, on) -> go.setEnabled(on));
        s.show();
    }

    /** A Terminal changed a value the app also saves: choose which one wins. */
    static void conflicts(Host host) {
        Theme t = host.theme();
        android.app.Activity a = host.activity();
        AppModel m = host.model();
        List<String> keys = m.conflicts();
        Sheet s = new Sheet(a, t, host.activity().getString(R.string.external_changes));
        if (keys.isEmpty()) {
            s.text(host.activity().getString(R.string.conflict_none));
            s.action(host.activity().getString(R.string.action_close), Ui.ButtonStyle.OUTLINED, s::dismiss);
            s.show();
            return;
        }
        s.subtitle(a.getString(R.string.conflict_explanation));
        LinearLayout card = Ui.card(a, t);
        ControlState st = m.state();
        for (String key : keys) {
            Controls.Control c = Controls.get(key);
            if (c == null) continue;
            if (card.getChildCount() > 0) card.addView(Ui.divider(a, t, t.dp(20)));
            LinearLayout block = Ui.column(a);
            block.setPadding(t.dp(18), t.dp(12), t.dp(12), t.dp(8));
            block.addView(Ui.text(a, t, ControlTitles.title(host.activity(), c) + " · " + SearchSheet.surfaces(host.activity(), c), Theme.BODY, t.ink, false));
            String observed = st.observed.get(key);
            block.addView(Ui.text(a, t, a.getString(R.string.conflict_values, ControlValues.display(host.activity(), c, st.saved.get(key)),
                    ControlValues.display(host.activity(), c, observed == null || observed.isEmpty() ? c.spec().defaultValue : observed)), Theme.CAPTION, t.inkMuted, false));
            LinearLayout actions = Ui.row(a);
            TextView external = Ui.button(a, t, host.activity().getString(R.string.conflict_use_terminal), null, Ui.ButtonStyle.TEXT, true);
            external.setOnClickListener(v -> {
                m.useExternal(key);
                host.feedback(host.activity().getString(R.string.conflict_using_terminal));
                s.dismiss();
                conflicts(host);
            });
            TextView keep = Ui.button(a, t, host.activity().getString(R.string.conflict_keep_app), null, Ui.ButtonStyle.TEXT, true);
            keep.setOnClickListener(v -> {
                m.keepApp(key);
                host.feedback(host.activity().getString(R.string.conflict_kept_app));
                s.dismiss();
                conflicts(host);
            });
            actions.addView(external);
            actions.addView(keep);
            block.addView(actions);
            card.addView(block);
        }
        s.body().addView(card, Ui.match());
        s.show();
    }
}
