package io.github.igorcv88.oneuiliquidglass.ui;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import io.github.igorcv88.oneuiliquidglass.R;
import io.github.igorcv88.oneuiliquidglass.config.EnergyReport;
import io.github.igorcv88.oneuiliquidglass.glass.EnergyPolicy;
import io.github.igorcv88.oneuiliquidglass.ui.kit.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Settings > Energia (SCREEN_SPECS 5.1): when the glass pauses and on which surfaces. The only
 * home of the six energy keys. The status line shows what SystemUI last reported, never an
 * assumption: with no report it says the state is unknown.
 */
public final class EnergySection {
    /** Surface labels in mask-bit order (EnergyPolicy.BIT_*). */
    private static final int[] LABELS = {R.string.surface_popup, R.string.surface_lock, R.string.surface_shade,
            R.string.home_qs, R.string.home_live_activity, R.string.now_bar, R.string.home_toast};

    private final Host host;
    private final Theme t;
    private final LinearLayout root;
    private final TextView status, reported;
    private final Segmented mode;
    private final LinearLayout details, surfacesCard;
    private final Toggle charging, saver;
    private final SliderRow threshold, hysteresis;
    private final Toggle[] surfaces = new Toggle[LABELS.length];

    public EnergySection(Host host) {
        this.host = host;
        t = host.theme();
        Activity a = host.activity();
        root = Ui.column(a);
        root.addView(Ui.section(a, t, a.getString(R.string.settings_energy)));
        LinearLayout card = Ui.card(a, t);
        card.setPadding(t.dp(18), t.dp(14), t.dp(18), t.dp(14));
        status = Ui.text(a, t, "", Theme.BODY, t.ink, false);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        reported = Ui.text(a, t, "", Theme.CAPTION, t.inkMuted, false);
        card.addView(status);
        card.addView(reported);
        TextView modeTitle = Ui.text(a, t, a.getString(R.string.energy_mode), Theme.LABEL, t.inkMuted, true);
        modeTitle.setPadding(0, t.dp(14), 0, t.dp(6));
        card.addView(modeTitle);
        mode = new Segmented(a, t, new String[] {a.getString(R.string.energy_mode_never),
                a.getString(R.string.energy_mode_threshold)}, 0);
        mode.listener(index -> save("energymode", String.valueOf(index)));
        card.addView(mode, Ui.match());
        root.addView(card, Ui.match());

        details = Ui.column(a);
        LinearLayout limits = Ui.card(a, t);
        threshold = new SliderRow(a.getString(R.string.energy_threshold), "energythreshold", 5, 50);
        hysteresis = new SliderRow(a.getString(R.string.energy_hysteresis), "energyhysteresis", 2, 15);
        limits.addView(threshold.view);
        limits.addView(Ui.divider(a, t, t.dp(18)));
        limits.addView(hysteresis.view);
        limits.addView(Ui.divider(a, t, t.dp(18)));
        charging = new Toggle(a, t);
        charging.listener((tg, on) -> save("energycharging", on ? "1" : "0"));
        limits.addView(Ui.switchRow(a, t, null, a.getString(R.string.energy_charging), "", charging));
        details.addView(limits, Ui.match(t.dp(10), 0));
        details.addView(Ui.section(a, t, a.getString(R.string.energy_surfaces)));
        surfacesCard = Ui.card(a, t);
        for (int i = 0; i < LABELS.length; i++) {
            final int bit = 1 << i;
            Toggle tg = new Toggle(a, t);
            tg.listener((x, on) -> toggleSurface(bit, on, tg));
            if (i > 0) surfacesCard.addView(Ui.divider(a, t, t.dp(18)));
            surfacesCard.addView(Ui.switchRow(a, t, null, a.getString(LABELS[i]), "", tg));
            surfaces[i] = tg;
        }
        details.addView(surfacesCard, Ui.match());
        root.addView(details, Ui.match());

        LinearLayout follow = Ui.card(a, t);
        saver = new Toggle(a, t);
        saver.listener((tg, on) -> save("energysaver", on ? "1" : "0"));
        follow.addView(Ui.switchRow(a, t, null, a.getString(R.string.energy_saver),
                a.getString(R.string.energy_saver_help), saver));
        root.addView(follow, Ui.match(t.dp(10), 0));
        TextView help = Ui.text(a, t, a.getString(R.string.energy_help), Theme.CAPTION, t.inkMuted, false);
        help.setPadding(t.dp(8), t.dp(12), t.dp(8), 0);
        root.addView(help);
    }

    public View view() {
        return root;
    }

    private void save(String key, String value) {
        AppModel.Result r = host.model().save(key, value);
        if (!r.ok) host.feedback(r.message);
    }

    private void toggleSurface(int bit, boolean on, Toggle toggle) {
        int current = mask();
        int next = toggled(current, bit, on);
        if (next == current && on != ((current & bit) != 0)) {
            toggle.set(true, true);
            host.feedback(host.activity().getString(R.string.energy_surfaces_min));
            return;
        }
        save("energysurfaces", String.valueOf(next));
    }

    private int mask() {
        return intOf("energysurfaces", EnergyPolicy.ALL_SURFACES);
    }

    private int intOf(String key, int fallback) {
        try {
            return Integer.parseInt(host.model().state().shown(key));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Refreshes values and the reported state from the model; cheap, never rebuilds. */
    public void bind() {
        Context c = host.activity();
        AppModel m = host.model();
        boolean on = intOf("energymode", 0) == 1;
        mode.select(on ? 1 : 0, false);
        details.setVisibility(on ? View.VISIBLE : View.GONE);
        int limit = intOf("energythreshold", 20);
        threshold.set(limit);
        hysteresis.set(intOf("energyhysteresis", 5));
        charging.set(!"0".equals(m.state().shown("energycharging")), false);
        saver.set("1".equals(m.state().shown("energysaver")), false);
        int mask = mask();
        for (int i = 0; i < surfaces.length; i++) surfaces[i].set((mask & (1 << i)) != 0, false);
        EnergyReport report = report(c);
        boolean silent = m.sync() == AppModel.Sync.NOT_DETECTED;
        status.setText(statusText(c, report, silent, limit));
        String at = reportedAt(c, report, silent);
        reported.setText(at == null ? "" : at);
        reported.setVisibility(at == null ? View.GONE : View.VISIBLE);
    }

    static EnergyReport report(Context c) {
        return EnergyReport.read(c.getSharedPreferences("engine_status", Context.MODE_PRIVATE));
    }

    // ---- pure text and mask helpers (tested without views)

    /** One surface switched; the mask never reaches zero, so the last selected surface cannot be removed. */
    static int toggled(int mask, int bit, boolean on) {
        int next = on ? mask | bit : mask & ~bit;
        return next == 0 ? mask : next;
    }

    static List<String> surfaceNames(Context c, int mask) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < LABELS.length; i++) if ((mask & (1 << i)) != 0) names.add(c.getString(LABELS[i]));
        return names;
    }

    /** What the system last said, or that it is unknown (no report, or a system that does not answer). */
    static String statusText(Context c, EnergyReport r, boolean systemSilent, int limit) {
        if (systemSilent || !r.known()) return c.getString(R.string.energy_now_unknown);
        switch (r.reason) {
            case SAVER: return c.getString(R.string.energy_now_saver);
            case LOW_BATTERY:
                return c.getString(R.string.energy_now_low, String.join(", ", surfaceNames(c, r.mask)), r.level, limit);
            default: return c.getString(R.string.energy_now_active);
        }
    }

    static String reportedAt(Context c, EnergyReport r, boolean systemSilent) {
        if (systemSilent || !r.known()) return null;
        return c.getString(R.string.energy_reported_at,
                android.text.format.DateFormat.getTimeFormat(c).format(new java.util.Date(r.at)));
    }

    /** The Home notice: only for a known, paused state of a system that answers. */
    static boolean homeNotice(EnergyReport r, AppModel.Sync sync) {
        return r.known() && r.paused() && sync != AppModel.Sync.NOT_DETECTED && sync != AppModel.Sync.OFF;
    }

    /** A titled slider over an integer registry key, saved when the drag ends. */
    private final class SliderRow {
        final LinearLayout view;
        final Slider slider;
        final TextView value;
        final int min;

        SliderRow(String title, String key, int min, int max) {
            this.min = min;
            Activity a = host.activity();
            view = Ui.column(a);
            view.setPadding(t.dp(18), t.dp(10), t.dp(18), t.dp(4));
            LinearLayout line = Ui.row(a);
            TextView name = Ui.text(a, t, title, Theme.BODY, t.ink, false);
            value = Ui.text(a, t, "", Theme.BODY, t.inkMuted, true);
            line.addView(name, new LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            line.addView(value);
            view.addView(line, Ui.match());
            slider = new Slider(a, t);
            slider.configure(max - min, -1, false);
            slider.describe(title, i -> text(min + i));
            slider.listener(new Slider.Listener() {
                @Override public void start() { }

                @Override public void change(int index, boolean fromUser) {
                    value.setText(text(min + index));
                }

                @Override public void stop(int index) {
                    save(key, String.valueOf(min + index));
                }
            });
            view.addView(slider, Ui.match());
        }

        String text(int v) {
            return v + " %";
        }

        void set(int v) {
            slider.set(v - min);
            value.setText(text(v));
        }
    }
}
