package io.github.igorcv88.oneuiliquidglass.ui;

import android.text.format.DateFormat;
import android.view.View;
import android.widget.*;

import io.github.igorcv88.oneuiliquidglass.config.*;
import io.github.igorcv88.oneuiliquidglass.R;
import io.github.igorcv88.oneuiliquidglass.live.*;
import io.github.igorcv88.oneuiliquidglass.ui.kit.*;

import java.util.*;

/**
 * The optional dynamic lens: what state it is in, one next step, the values waiting for a
 * restart, and maintenance. Root is requested only from an explicit tap here; the restart is
 * always confirmed; the helper accepts a fixed command set.
 */
final class LensSheet extends Sheet {
    private final Host host;
    private final AppModel m;
    private final TextView state, detail;
    private final TextView primary;
    private final LinearLayout pendingBox;
    private final TextView protocol, maintenance;
    private final LinearLayout tools;
    private final AppModel.Listener listener = this::bind;

    LensSheet(Host host) {
        super(host.activity(), host.theme(), host.activity().getString(R.string.dynamic_lens));
        this.host = host;
        m = host.model();
        subtitle(activity.getString(R.string.lens_subtitle));
        LinearLayout card = Ui.card(activity, t);
        card.setPadding(t.dp(18), t.dp(16), t.dp(18), t.dp(16));
        state = Ui.text(activity, t, "", Theme.HEADLINE, t.ink, true);
        state.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        card.addView(state);
        detail = Ui.text(activity, t, "", Theme.LABEL, t.inkMuted, false);
        detail.setPadding(0, t.dp(4), 0, t.dp(12));
        card.addView(detail);
        primary = Ui.button(activity, t, "", null, Ui.ButtonStyle.FILLED, false);
        primary.setOnClickListener(v -> next());
        card.addView(primary, Ui.match());
        body().addView(card, Ui.match());
        pendingBox = Ui.column(activity);
        body().addView(pendingBox, Ui.match());

        maintenance = Ui.section(activity, t, activity.getString(R.string.lens_maintenance));
        body().addView(maintenance);
        tools = Ui.card(activity, t);
        LinearLayout refresh = Ui.listRow(activity, t, Icon.RESTART, activity.getString(R.string.lens_check_again), activity.getString(R.string.lens_check_desc), null);
        refresh.setOnClickListener(v -> m.lens.status());
        tools.addView(refresh);
        tools.addView(Ui.divider(activity, t, t.dp(72)));
        LinearLayout restart = Ui.listRow(activity, t, Icon.RESTART, activity.getString(R.string.lens_restart), activity.getString(R.string.lens_restart_desc), null);
        restart.setOnClickListener(v -> confirmRestart());
        tools.addView(restart);
        tools.addView(Ui.divider(activity, t, t.dp(72)));
        LinearLayout log = Ui.listRow(activity, t, Icon.CODE, activity.getString(R.string.lens_show_log), activity.getString(R.string.lens_log_desc), null);
        log.setOnClickListener(v -> m.lens.log());
        tools.addView(log);
        tools.addView(Ui.divider(activity, t, t.dp(72)));
        LinearLayout copy = Ui.listRow(activity, t, Icon.COPY, activity.getString(R.string.lens_copy_state), null, null);
        copy.setOnClickListener(v -> host.copy(describe(m.lens.model())));
        tools.addView(copy);
        tools.addView(Ui.divider(activity, t, t.dp(72)));
        LinearLayout remove = Ui.listRow(activity, t, Icon.CLOSE, activity.getString(R.string.lens_remove), activity.getString(R.string.lens_remove_desc), null);
        remove.setOnClickListener(v -> confirmRemove());
        tools.addView(remove);
        body().addView(tools, Ui.match());

        View[] d = Ui.disclosure(activity, t, null, activity.getString(R.string.control_technical), null, false, null);
        LinearLayout tech = Ui.card(activity, t);
        tech.addView(d[0]);
        protocol = Ui.text(activity, t, "", Theme.SMALL, t.inkMuted, false);
        protocol.setTextIsSelectable(true);
        protocol.setTypeface(android.graphics.Typeface.MONOSPACE);
        protocol.setPadding(t.dp(18), 0, t.dp(18), t.dp(14));
        ((LinearLayout) d[1]).addView(protocol);
        tech.addView(d[1]);
        body().addView(tech, Ui.match(t.dp(12), 0));
        m.listen(listener);
        onClosed(() -> m.unlisten(listener));
        bind();
    }

    private boolean su() {
        return m.root.granted() && "1".equals(m.lens.model().status.get("helper"));
    }

    private void bind() {
        SfLiveController.Model model = m.lens.model();
        SfLive.State s = SfLive.state(model.status);
        Map<String, String> pending = m.pendingCompositor();
        boolean unverified = m.compositorUnverified();
        String title, text, action;
        boolean enabled = !model.busy && !m.root.busy();
        if (!model.bundled) {
            title = activity.getString(R.string.lens_unavailable);
            text = activity.getString(R.string.lens_unavailable_desc);
            action = null;
        } else if (model.busy || m.root.busy()) {
            title = activity.getString(R.string.lens_working);
            text = activity.getString(R.string.lens_authorize_root);
            action = activity.getString(R.string.lens_wait);
            enabled = false;
        } else if (!m.root.granted()) {
            title = activity.getString(R.string.lens_root_unchecked);
            text = activity.getString(R.string.lens_root_check_desc);
            action = activity.getString(R.string.lens_check_root);
        } else if (!su()) {
            title = activity.getString(R.string.lens_unknown);
            text = activity.getString(R.string.lens_root_granted);
            action = activity.getString(R.string.lens_check_state);
        } else switch (s) {
            case UNSUPPORTED:
                title = activity.getString(R.string.lens_unsupported);
                text = activity.getString(R.string.lens_unsupported_desc);
                action = null;
                break;
            case UPDATE:
            case SELFTEST:
                title = s == SfLive.State.UPDATE ? activity.getString(R.string.lens_not_installed) : activity.getString(R.string.lens_needs_test);
                text = activity.getString(R.string.lens_install_desc);
                action = s == SfLive.State.UPDATE ? activity.getString(R.string.lens_install_test) : activity.getString(R.string.action_test);
                break;
            case RESTART:
                title = activity.getString(R.string.lens_ready);
                text = activity.getString(R.string.lens_activate_desc);
                action = activity.getString(R.string.lens_activate);
                break;
            case LOADED:
                boolean on = "1".equals(model.status.get("sfrefract")) && "1".equals(model.status.get("sf_rewrite"))
                        && "1".equals(model.status.get("sf_got")) && "1".equals(model.status.get("sf_rewrite_ok"))
                        && "1".equals(model.status.get("sf_stable"));
                title = on ? activity.getString(R.string.lens_active) : activity.getString(R.string.lens_loaded_unverified);
                text = on ? (pending.isEmpty()
                        ? unverified ? activity.getString(R.string.lens_loaded_params_unverified)
                                     : activity.getString(R.string.lens_loaded_desc)
                        : activity.getString(R.string.lens_pending_desc))
                        : activity.getString(R.string.lens_wait_verify);
                action = !pending.isEmpty() ? (pending.size() == 1 ? activity.getString(R.string.lens_apply_one) : activity.getString(R.string.lens_apply_many, pending.size()))
                        : !on ? activity.getString(R.string.lens_check_state) : unverified ? activity.getString(R.string.lens_restart_confirm) : null;
                break;
            default:
                title = activity.getString(R.string.lens_no_response);
                text = activity.getString(R.string.lens_no_response_desc);
                action = activity.getString(R.string.lens_check_root);
        }
        // Keep the actual failed operation visible instead of replacing it with a generic state.
        // In particular, a rejected property value must not look like a root permission failure.
        if (!model.busy && model.message != null
                && model.message.startsWith("Operação interrompida:")) {
            title = activity.getString(R.string.lens_interrupted);
            text = model.message;
            action = activity.getString(R.string.lens_check_state);
        }
        CompositorValues.Outcome last = m.lastApply();
        if (last != CompositorValues.Outcome.NONE && last != CompositorValues.Outcome.UNVERIFIED) {
            String when = DateFormat.getTimeFormat(activity).format(new Date(m.lastApplyAt()));
            switch (last) {
                case CONFIRMED: text += "\n" + activity.getString(R.string.lens_last_confirmed, when); break;
                case NOT_LOADED: text += "\n" + activity.getString(R.string.lens_last_not_loaded, when); break;
                case CHANGED: text += "\n" + activity.getString(R.string.lens_last_changed, when); break;
                default: break;
            }
        }
        state.setText(title);
        detail.setText(text);
        primary.setVisibility(action == null ? View.GONE : View.VISIBLE);
        if (action != null) primary.setText(action);
        primary.setEnabled(enabled);
        pendingBox.removeAllViews();
        if (!pending.isEmpty()) {
            pendingBox.addView(Ui.section(activity, t, activity.getString(R.string.lens_await_restart)));
            LinearLayout card = Ui.card(activity, t);
            card.setPadding(t.dp(18), t.dp(12), t.dp(18), t.dp(12));
            StringBuilder b = new StringBuilder();
            ControlState st = m.state();
            for (Map.Entry<String, String> e : pending.entrySet()) {
                Controls.Control c = Controls.get(e.getKey());
                if (c == null) continue;
                b.append(b.length() == 0 ? "" : "\n").append(ControlTitles.title(host.activity(), c)).append(" · ").append(SearchSheet.surfaces(host.activity(), c))
                        .append(": ").append(ControlValues.display(host.activity(), c, from(e.getKey(), st))).append(" → ")
                        .append(ControlValues.display(host.activity(), c, e.getValue()));
            }
            card.addView(Ui.text(activity, t, b, Theme.LABEL, t.ink, false));
            pendingBox.addView(card, Ui.match());
        } else if (unverified && s == SfLive.State.LOADED) {
            pendingBox.addView(Ui.text(activity, t,
                    activity.getString(R.string.lens_unverified_warning),
                    Theme.LABEL, t.inkMuted, false));
        }
        protocol.setText(describe(model));
        // Maintenance needs a helper that answered as root.
        int tools = su() ? View.VISIBLE : View.GONE;
        maintenance.setVisibility(tools);
        this.tools.setVisibility(tools);
    }

    /** What the compositor runs now: the compiled (or last sent) value, else the property. */
    private String from(String key, ControlState st) {
        String compiled = m.compiledCompositor(key);
        return compiled != null ? compiled : st.device(key);
    }

    private void next() {
        SfLiveController.Model model = m.lens.model();
        if (model.busy) return;
        if (!m.root.granted()) {
            m.root.check(ok -> {
                if (ok) m.lens.status();
                else host.feedback(activity.getString(R.string.root_not_granted));
            });
            return;
        }
        if (!su()) {
            m.lens.status();
            return;
        }
        switch (SfLive.state(model.status)) {
            case UPDATE:
            case SELFTEST:
                m.lens.prepare();
                break;
            case LOADED:
                if (m.pendingCompositor().isEmpty()
                        && (!"1".equals(model.status.get("sf_stable"))
                        || !"1".equals(model.status.get("sf_got"))
                        || !"1".equals(model.status.get("sf_rewrite_ok"))
                        || !"1".equals(model.status.get("sfrefract")))) {
                    m.lens.status();
                    break;
                }
                confirmRestart();
                break;
            case RESTART:
                confirmRestart();
                break;
            default:
                m.lens.status();
        }
    }

    /** Explicit, informed confirmation before the userspace restart. */
    private void confirmRestart() {
        SfLiveController.Model model = m.lens.model();
        if (!SfControlPolicy.restart(m.root.granted(), model.bundled, model.busy, model.status)) {
            host.feedback(activity.getString(R.string.lens_install_first));
            return;
        }
        Map<String, String> pending = m.pendingCompositor();
        Sheet s = new Sheet(activity, t, activity.getString(R.string.restart_userspace_title));
        s.text(activity.getString(R.string.lens_confirm_base)
                + (pending.isEmpty() ? (m.compositorUnverified() ? activity.getString(R.string.lens_confirm_verify) : ".")
                        : pending.size() == 1 ? activity.getString(R.string.lens_confirm_one) : activity.getString(R.string.lens_confirm_many, pending.size()))
                + "\n\n" + activity.getString(R.string.lens_confirm_fallback));
        Toggle ack = new Toggle(activity, t);
        LinearLayout row = Ui.switchRow(activity, t, null, activity.getString(R.string.restart_saved_ack), null, ack);
        LinearLayout card = Ui.card(activity, t);
        card.addView(row);
        s.body().addView(card, Ui.match(t.dp(4), 0));
        s.action(activity.getString(R.string.action_cancel), Ui.ButtonStyle.OUTLINED, s::dismiss);
        TextView go = s.action(activity.getString(R.string.restart_userspace), Ui.ButtonStyle.FILLED, () -> {
            s.dismiss();
            m.lens.restartConfirmed(pending);
            host.feedback(activity.getString(R.string.lens_applying));
        });
        go.setEnabled(false);
        ack.listener((x, on) -> go.setEnabled(on));
        s.show();
    }

    private void confirmRemove() {
        Sheet s = new Sheet(activity, t, activity.getString(R.string.lens_remove_title));
        s.text(activity.getString(R.string.lens_remove_warning));
        s.action(activity.getString(R.string.action_cancel), Ui.ButtonStyle.OUTLINED, s::dismiss);
        s.action(activity.getString(R.string.action_remove), Ui.ButtonStyle.DANGER, () -> {
            s.dismiss();
            if (!su()) {
                host.feedback(activity.getString(R.string.lens_root_first));
                return;
            }
            m.lens.uninstallConfirmed();
        });
        s.show();
    }

    static String describe(SfLiveController.Model model) {
        StringBuilder b = new StringBuilder(model.message == null ? "" : model.message);
        for (Map.Entry<String, String> e : model.status.entrySet())
            b.append('\n').append(e.getKey()).append('=').append(e.getValue());
        if (model.log != null && !model.log.isEmpty()) b.append("\n\n").append(model.log);
        return b.toString().trim();
    }
}
