package io.github.igorcv88.oneuiliquidglass.ui;

import android.view.*;
import android.widget.*;

import io.github.igorcv88.oneuiliquidglass.R;
import io.github.igorcv88.oneuiliquidglass.config.*;
import io.github.igorcv88.oneuiliquidglass.live.CompositorValues;
import io.github.igorcv88.oneuiliquidglass.ui.kit.*;

import java.util.*;

/**
 * Início: state in one line, the master switch, the three surfaces with their own switch,
 * the test as the main action and styles. Pending icon edits can coexist with a lens notice.
 */
public final class HomePage implements Page {
    private final Host host;
    private final Theme t;
    private final ScrollView scroll;
    private final LinearLayout content, noticeSlot;
    private final TextView status;
    private final View statusDot;
    private final Toggle master;
    private final LinearLayout masterRow;
    private final Map<Controls.Surface, LinearLayout> rows = new EnumMap<>(Controls.Surface.class);
    private final Map<Controls.Surface, Toggle> toggles = new EnumMap<>(Controls.Surface.class);
    private final Toggle quickSettingsToggle;
    private final LinearLayout quickSettingsRow;
    /** Live update at the top, Now Bar and toasts: opt-ins of their own (hooks/ongoing). */
    private static final String[] LIVE_KEYS = {ProductFlags.LIVE_ACTIVITY, ProductFlags.NOW_BAR, ProductFlags.TOAST};
    private static final int[] LIVE_LABELS = {R.string.home_live_activity, R.string.now_bar, R.string.home_toast};
    private static final int[] LIVE_DESCRIPTIONS = {R.string.home_live_activity_desc, R.string.home_nowbar_desc, R.string.home_toast_desc};
    private final Map<String, Toggle> liveToggles = new LinkedHashMap<>();
    private final Map<String, LinearLayout> liveRows = new LinkedHashMap<>();
    private final MaterialPanel material;
    private final TextView testResult;
    private final Toggle traceToggle;
    private final LinearLayout traceRow;
    private String noticeKey = "";

    public HomePage(Host host) {
        this.host = host;
        t = host.theme();
        android.app.Activity a = host.activity();
        scroll = Ui.scroller(a);
        content = Ui.column(a);
        scroll.addView(content);

        LinearLayout header = Ui.row(a);
        // The launcher icon's layers in a rounded tile (an adaptive icon needs a launcher mask).
        FrameLayout logo = new FrameLayout(a);
        logo.setBackgroundResource(R.drawable.oulg_launcher_background);
        logo.setClipToOutline(true);
        logo.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), v.getWidth() * .3f);
            }
        });
        ImageView fg = new ImageView(a);
        fg.setImageResource(R.drawable.oulg_launcher_foreground);
        fg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        fg.setScaleX(1.5f);
        fg.setScaleY(1.5f);
        logo.addView(fg, new FrameLayout.LayoutParams(-1, -1));
        logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(t.dp(44), t.dp(44));
        lp.setMarginEnd(t.dp(12));
        header.addView(logo, lp);
        LinearLayout titles = Ui.column(a);
        TextView name = Ui.title(a, t, "Liquid Glass");
        name.setTextSize(Theme.TITLE + 2);
        titles.addView(name);
        LinearLayout statusLine = Ui.row(a);
        statusDot = new View(a);
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(t.dp(8), t.dp(8));
        dp.setMarginEnd(t.dp(6));
        statusLine.addView(statusDot, dp);
        status = Ui.text(a, t, "", Theme.CAPTION, t.inkMuted, false);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        statusLine.addView(status);
        titles.addView(statusLine);
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        content.addView(header, Ui.match(t.dp(8), t.dp(18)));

        LinearLayout masterCard = Ui.card(a, t);
        master = new Toggle(a, t);
        masterRow = Ui.switchRow(a, t, Icon.BELL, host.activity().getString(R.string.home_system_glass), "", master);
        master.listener((tg, on) -> {
            host.model().master(on);
            host.feedback(on ? host.activity().getString(R.string.home_glass_on) : host.activity().getString(R.string.home_glass_off));
        });
        masterCard.addView(masterRow);
        content.addView(masterCard, Ui.match());

        noticeSlot = Ui.column(a);
        content.addView(noticeSlot, Ui.match(t.dp(12), 0));

        material = new MaterialPanel(host);
        content.addView(material.view(), Ui.match(0, t.dp(4)));

        content.addView(Ui.section(a, t, a.getString(R.string.home_surfaces)));
        LinearLayout surfaces = Ui.card(a, t);
        Icon[] icons = {Icon.POPUP, Icon.LOCK, Icon.SHADE};
        for (Controls.Surface s : Controls.Surface.values()) {
            Toggle tg = new Toggle(a, t);
            String label = surfaceLabel(a, s);
            tg.setContentDescription(a.getString(R.string.home_surface_glass, label));
            tg.listener((x, on) -> {
                host.model().flag("surface." + s.id + ".enabled", on);
                host.feedback(a.getString(on ? R.string.home_surface_enabled : R.string.home_surface_disabled, label));
            });
            LinearLayout r = Ui.listRow(a, t, icons[s.ordinal()], label, "", tg);
            r.setOnClickListener(v -> host.showSurface(s));
            r.setContentDescription(null);
            if (s.ordinal() > 0) surfaces.addView(Ui.divider(a, t, t.dp(72)));
            surfaces.addView(r);
            rows.put(s, r);
            toggles.put(s, tg);
        }
        surfaces.addView(Ui.divider(a, t, t.dp(72)));
        quickSettingsToggle = new Toggle(a, t);
        quickSettingsToggle.setContentDescription(host.activity().getString(R.string.home_qs_glass));
        quickSettingsToggle.listener((tg, on) -> {
            host.model().flag(ProductFlags.QUICK_SETTINGS, on);
            host.feedback(on ? host.activity().getString(R.string.home_qs_on) : host.activity().getString(R.string.home_qs_off));
        });
        quickSettingsRow = Ui.listRow(a, t, Icon.SHADE, host.activity().getString(R.string.home_qs), host.activity().getString(R.string.home_qs_desc), quickSettingsToggle);
        quickSettingsRow.setOnClickListener(v -> quickSettingsToggle.toggleByUser());
        surfaces.addView(quickSettingsRow);
        for (int i = 0; i < LIVE_KEYS.length; i++) {
            String key = LIVE_KEYS[i], label = a.getString(LIVE_LABELS[i]);
            Toggle tg = new Toggle(a, t);
            tg.setContentDescription(a.getString(R.string.home_surface_glass, label));
            tg.listener((x, on) -> {
                host.model().flag(key, on);
                host.feedback(a.getString(on ? R.string.home_surface_enabled : R.string.home_surface_disabled, label));
            });
            LinearLayout r = Ui.listRow(a, t, Icon.SHADE, label, a.getString(LIVE_DESCRIPTIONS[i]), tg);
            r.setOnClickListener(v -> tg.toggleByUser());
            surfaces.addView(Ui.divider(a, t, t.dp(72)));
            surfaces.addView(r);
            liveToggles.put(key, tg);
            liveRows.put(key, r);
        }
        content.addView(surfaces, Ui.match());

        content.addView(Ui.section(a, t, host.activity().getString(R.string.home_test)));
        LinearLayout test = Ui.card(a, t);
        test.setPadding(t.dp(16), t.dp(16), t.dp(16), t.dp(8));
        TextView send = Ui.button(a, t, host.activity().getString(R.string.home_test_notification), Icon.BELL, Ui.ButtonStyle.FILLED, false);
        send.setOnClickListener(v -> host.testNotification());
        test.addView(send, Ui.match());
        testResult = Ui.text(a, t, "", Theme.LABEL, t.inkMuted, false);
        testResult.setPadding(t.dp(6), t.dp(10), t.dp(6), 0);
        testResult.setVisibility(View.GONE);
        testResult.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        test.addView(testResult);
        TextView more = Ui.button(a, t, host.activity().getString(R.string.home_more_tests), null, Ui.ButtonStyle.TEXT, true);
        more.setOnClickListener(v -> host.openTestOptions());
        test.addView(more, Ui.match(t.dp(4), 0));
        test.addView(Ui.divider(a, t, t.dp(16)));
        traceToggle = new Toggle(a, t);
        traceToggle.setContentDescription(host.activity().getString(R.string.home_detailed_log));
        traceToggle.listener((toggle, on) -> {
            AppModel.Result result = host.model().save("trace", on ? "1" : "0");
            if (!result.ok) {
                if (result.conflict) host.openConflicts();
                else host.feedback(result.message);
            } else host.feedback(a.getString(on ? R.string.home_log_enabled : R.string.home_log_disabled));
        });
        traceRow = Ui.listRow(a, t, Icon.INFO, a.getString(R.string.home_log), host.activity().getString(R.string.home_diagnostic_desc), traceToggle);
        traceRow.setOnClickListener(v -> traceToggle.toggleByUser());
        test.addView(traceRow);
        content.addView(test, Ui.match());

        content.addView(Ui.section(a, t, a.getString(R.string.home_style)));
        LinearLayout style = Ui.card(a, t);
        LinearLayout presets = Ui.listRow(a, t, Icon.PALETTE, a.getString(R.string.styles), host.activity().getString(R.string.home_styles_desc), Ui.chevron(a, t));
        presets.setOnClickListener(v -> new PresetsSheet(host).show());
        style.addView(presets);
        content.addView(style, Ui.match());
    }

    static String surfaceLabel(android.content.Context c, Controls.Surface surface) {
        switch (surface) {
            case HEADSUP: return c.getString(R.string.surface_popup);
            case KEYGUARD: return c.getString(R.string.surface_lock);
            default: return c.getString(R.string.surface_shade);
        }
    }

    static String modeLabel(android.content.Context c, Mode mode) {
        switch (mode) {
            case DYNAMIC_LENS: return c.getString(R.string.mode_dynamic);
            case CAPTURE: return c.getString(R.string.mode_capture);
            case PLAIN_BLUR: return c.getString(R.string.mode_blur);
            default: return c.getString(R.string.mode_standard);
        }
    }

    public void showTestResult(String text, boolean problem) {
        testResult.setText(text);
        testResult.setTextColor(problem ? t.warn : t.inkMuted);
        Ui.reveal(testResult, true, t);
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
        ControlState st = m.state();
        Applicability app = st.applicability();
        master.set(st.enabled, true);
        Ui.setSubtitle(masterRow, st.enabled ? host.activity().getString(R.string.home_mode, modeLabel(host.activity(), app.mode)) : host.activity().getString(R.string.home_original_notifications));
        AppModel.Sync sync = m.sync();
        int dot;
        String line;
        if (!st.enabled) {
            line = host.activity().getString(R.string.home_off);
            dot = t.inkFaint;
        } else {
            int on = 0;
            for (Controls.Surface s : Controls.Surface.values()) if (app.surfaceOn(s)) on++;
            line = (on == 3 ? host.activity().getString(R.string.home_active_all) : on == 0 ? host.activity().getString(R.string.home_none_enabled) : host.activity().getString(R.string.home_active_count, on));
            switch (sync) {
                case SYNCED: dot = t.ok; break;
                case WAITING: dot = t.accent; line += host.activity().getString(R.string.home_sending); break;
                case NO_RESPONSE: dot = t.warn; line += host.activity().getString(R.string.home_no_response); break;
                case NOT_DETECTED: dot = t.warn; line = host.activity().getString(R.string.home_module_missing); break;
                default: dot = t.inkFaint;
            }
        }
        status.setText(line);
        statusDot.setBackground(Ui.rounded(dot, t.dpf(4)));
        for (Controls.Surface s : Controls.Surface.values()) {
            boolean on = app.surfaceOn(s);
            Toggle tg = toggles.get(s);
            tg.set(on, true);
            Ui.enable(tg, st.enabled);
            int changed = 0;
            for (Controls.Control c : Controls.all())
                if (c.on(s) && Controls.editable(c.key) && c.tier != Controls.Tier.TECHNICAL && GlassMaterial.visible(c.key, s, st::shown) && st.saved.containsKey(c.key) && st.changed(c.key)) changed++;
            String sub = !st.enabled ? host.activity().getString(R.string.home_glass_off) : !on ? host.activity().getString(R.string.home_off) : changed == 0 ? host.activity().getString(R.string.home_default) : changed == 1 ? host.activity().getString(R.string.home_custom_one) : host.activity().getString(R.string.home_custom_many, changed);
            Ui.setSubtitle(rows.get(s), sub);
        }
        boolean quickOn = "1".equals(st.shown(ProductFlags.QUICK_SETTINGS));
        quickSettingsToggle.set(quickOn, true);
        Ui.enable(quickSettingsToggle, st.enabled);
        Ui.setSubtitle(quickSettingsRow, !st.enabled ? host.activity().getString(R.string.home_glass_off) : quickOn
                ? host.activity().getString(R.string.home_qs_active) : host.activity().getString(R.string.home_experimental));
        for (Map.Entry<String, Toggle> e : liveToggles.entrySet()) {
            boolean on = "1".equals(st.shown(e.getKey()));
            e.getValue().set(on, true);
            Ui.enable(e.getValue(), st.enabled);
            Ui.setSubtitle(liveRows.get(e.getKey()), !st.enabled ? host.activity().getString(R.string.home_glass_off) : on
                    ? host.activity().getString(R.string.home_verify) : host.activity().getString(R.string.home_experimental));
        }
        boolean traceRequested = "1".equals(st.shown("trace"));
        traceToggle.set(traceRequested, true);
        AppModel.Sync traceSync = m.sync();
        Ui.setSubtitle(traceRow, !traceRequested ? host.activity().getString(R.string.home_off) : traceSync == AppModel.Sync.SYNCED
                ? host.activity().getString(R.string.home_system_received) : traceSync == AppModel.Sync.WAITING
                ? host.activity().getString(R.string.home_trace_enabling) : host.activity().getString(R.string.home_unconfirmed));
        material.bind();
        notice(st, sync);
    }

    /** Pending icon edits and, independently, the most useful general notice. */
    private void notice(ControlState st, AppModel.Sync sync) {
        AppModel m = host.model();
        android.app.Activity a = host.activity();
        String key;
        LinearLayout n = null;
        Map<String, String> pending = m.mode() == Mode.DYNAMIC_LENS ? m.pendingCompositor() : Collections.emptyMap();
        List<String> conflicts = m.conflicts();
        int pendingIcons = m.pendingIcons().size();
        CompositorValues.Outcome last = m.lastApply();
        if (last == CompositorValues.Outcome.NOT_LOADED) {
            key = "lens-failed";
            n = Ui.notice(a, t, Ui.Tone.ERROR, a.getString(R.string.home_lens_failed), a.getString(R.string.home_details), host::openLens);
        } else if (!pending.isEmpty()) {
            key = "pending" + pending.size();
            n = Ui.notice(a, t, Ui.Tone.INFO, pending.size() == 1 ? a.getString(R.string.home_lens_pending_one) : a.getString(R.string.home_lens_pending_many, pending.size()), a.getString(R.string.home_apply), host::openLens);
        } else if (m.mode() == Mode.DYNAMIC_LENS && m.compositorUnverified()) {
            key = "lens-unverified";
            n = Ui.notice(a, t, Ui.Tone.WARN,
                    a.getString(R.string.home_lens_unverified),
                    a.getString(R.string.home_check), host::openLens);
        } else if (st.enabled && sync == AppModel.Sync.NOT_DETECTED) {
            key = "detect";
            n = Ui.notice(a, t, Ui.Tone.WARN, a.getString(R.string.home_not_read), a.getString(R.string.home_how), host::openSync);
        } else if (st.enabled && sync == AppModel.Sync.NO_RESPONSE) {
            key = "sync";
            n = Ui.notice(a, t, Ui.Tone.WARN, a.getString(R.string.home_unconfirmed_changes), a.getString(R.string.home_resolve), host::openSync);
        } else if (!conflicts.isEmpty()) {
            key = "conflict" + conflicts.size();
            n = Ui.notice(a, t, Ui.Tone.INFO, conflicts.size() == 1 ? a.getString(R.string.home_conflict_one) : a.getString(R.string.home_conflict_many, conflicts.size()), a.getString(R.string.home_review), host::openConflicts);
        } else key = "";
        String combinedKey = key + ":icons:" + pendingIcons;
        if (combinedKey.equals(noticeKey)) return;
        noticeKey = combinedKey;
        noticeSlot.removeAllViews();
        if (pendingIcons != 0) noticeSlot.addView(Ui.notice(a, t, Ui.Tone.INFO,
                a.getResources().getQuantityString(R.plurals.icons_pending, pendingIcons, pendingIcons),
                a.getString(R.string.home_apply), () -> InfoSheets.applyIcons(host)), Ui.match());
        if (n != null) noticeSlot.addView(n, Ui.match());
    }
}
