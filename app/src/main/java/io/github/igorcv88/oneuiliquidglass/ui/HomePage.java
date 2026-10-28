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
 * the test as the main action and styles. At most one notice, only when something needs the
 * person.
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
    private final TextView testResult;
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
        masterRow = Ui.switchRow(a, t, Icon.BELL, "Vidro no SystemUI", "", master);
        master.listener((tg, on) -> {
            host.model().master(on);
            host.feedback(on ? "Vidro ligado" : "Vidro desligado");
        });
        masterCard.addView(masterRow);
        content.addView(masterCard, Ui.match());

        noticeSlot = Ui.column(a);
        content.addView(noticeSlot, Ui.match(t.dp(12), 0));

        content.addView(Ui.section(a, t, "Ambientes"));
        LinearLayout surfaces = Ui.card(a, t);
        Icon[] icons = {Icon.POPUP, Icon.LOCK, Icon.SHADE};
        for (Controls.Surface s : Controls.Surface.values()) {
            Toggle tg = new Toggle(a, t);
            tg.setContentDescription("Vidro em " + s.label);
            tg.listener((x, on) -> {
                host.model().flag("surface." + s.id + ".enabled", on);
                host.feedback(s.label + (on ? ": vidro ligado" : ": vidro desligado"));
            });
            LinearLayout r = Ui.listRow(a, t, icons[s.ordinal()], s.label, "", tg);
            r.setOnClickListener(v -> host.showSurface(s));
            r.setContentDescription(null);
            if (s.ordinal() > 0) surfaces.addView(Ui.divider(a, t, t.dp(72)));
            surfaces.addView(r);
            rows.put(s, r);
            toggles.put(s, tg);
        }
        surfaces.addView(Ui.divider(a, t, t.dp(72)));
        quickSettingsToggle = new Toggle(a, t);
        quickSettingsToggle.setContentDescription("Vidro nos controles rápidos");
        quickSettingsToggle.listener((tg, on) -> {
            host.model().flag(ProductFlags.QUICK_SETTINGS, on);
            host.feedback(on ? "Controles rápidos: vidro ligado" : "Controles rápidos: vidro desligado");
        });
        quickSettingsRow = Ui.listRow(a, t, Icon.SHADE, "Controles rápidos", "Experimental · desativado por padrão", quickSettingsToggle);
        quickSettingsRow.setOnClickListener(v -> quickSettingsToggle.toggleByUser());
        surfaces.addView(quickSettingsRow);
        content.addView(surfaces, Ui.match());

        content.addView(Ui.section(a, t, "Teste"));
        LinearLayout test = Ui.card(a, t);
        test.setPadding(t.dp(16), t.dp(16), t.dp(16), t.dp(8));
        TextView send = Ui.button(a, t, "Testar notificação", Icon.BELL, Ui.ButtonStyle.FILLED, false);
        send.setOnClickListener(v -> host.testNotification());
        test.addView(send, Ui.match());
        testResult = Ui.text(a, t, "", Theme.LABEL, t.inkMuted, false);
        testResult.setPadding(t.dp(6), t.dp(10), t.dp(6), 0);
        testResult.setVisibility(View.GONE);
        testResult.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        test.addView(testResult);
        TextView more = Ui.button(a, t, "Mais opções de teste", null, Ui.ButtonStyle.TEXT, true);
        more.setOnClickListener(v -> host.openTestOptions());
        test.addView(more, Ui.match(t.dp(4), 0));
        content.addView(test, Ui.match());

        content.addView(Ui.section(a, t, "Estilo"));
        LinearLayout style = Ui.card(a, t);
        LinearLayout presets = Ui.listRow(a, t, Icon.PALETTE, "Estilos", "Salvar, restaurar ou voltar ao padrão", Ui.chevron(a, t));
        presets.setOnClickListener(v -> new PresetsSheet(host).show());
        style.addView(presets);
        content.addView(style, Ui.match());
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
        Ui.setSubtitle(masterRow, st.enabled ? "Modo: " + app.mode.label : "Notificações com o visual original");
        AppModel.Sync sync = m.sync();
        int dot;
        String line;
        if (!st.enabled) {
            line = "Desligado";
            dot = t.inkFaint;
        } else {
            int on = 0;
            for (Controls.Surface s : Controls.Surface.values()) if (app.surfaceOn(s)) on++;
            line = (on == 3 ? "Ativo nos três ambientes" : on == 0 ? "Nenhum ambiente ligado" : "Ativo em " + on + " de 3 ambientes");
            switch (sync) {
                case SYNCED: dot = t.ok; break;
                case WAITING: dot = t.accent; line += " · enviando"; break;
                case NO_RESPONSE: dot = t.warn; line += " · sem resposta do sistema"; break;
                case NOT_DETECTED: dot = t.warn; line = "Módulo não detectado no sistema"; break;
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
                if (c.on(s) && Controls.editable(c.key) && c.tier != Controls.Tier.TECHNICAL && st.saved.containsKey(c.key) && st.changed(c.key)) changed++;
            String sub = !st.enabled ? "Vidro desligado" : !on ? "Desligado" : changed == 0 ? "Padrão" : changed == 1 ? "1 ajuste personalizado" : changed + " ajustes personalizados";
            Ui.setSubtitle(rows.get(s), sub);
        }
        boolean quickOn = "1".equals(st.shown(ProductFlags.QUICK_SETTINGS));
        quickSettingsToggle.set(quickOn, true);
        Ui.enable(quickSettingsToggle, st.enabled);
        Ui.setSubtitle(quickSettingsRow, !st.enabled ? "Vidro desligado" : quickOn
                ? "Ativo · teste os tiles no aparelho" : "Experimental · desativado por padrão");
        notice(st, sync);
    }

    /** The single most useful notice, or none. */
    private void notice(ControlState st, AppModel.Sync sync) {
        AppModel m = host.model();
        android.app.Activity a = host.activity();
        String key;
        LinearLayout n = null;
        Map<String, String> pending = m.mode() == Mode.DYNAMIC_LENS ? m.pendingCompositor() : Collections.emptyMap();
        List<String> conflicts = m.conflicts();
        CompositorValues.Outcome last = m.lastApply();
        if (st.enabled && sync == AppModel.Sync.NOT_DETECTED) {
            key = "detect";
            n = Ui.notice(a, t, Ui.Tone.WARN, "O sistema ainda não leu os ajustes. Confira se o módulo está ativo no LSPosed.", "Ver como", host::openSync);
        } else if (st.enabled && sync == AppModel.Sync.NO_RESPONSE) {
            key = "sync";
            n = Ui.notice(a, t, Ui.Tone.WARN, "O sistema não confirmou as últimas alterações.", "Resolver", host::openSync);
        } else if (last == CompositorValues.Outcome.NOT_LOADED) {
            key = "lens-failed";
            n = Ui.notice(a, t, Ui.Tone.ERROR, "A lente dinâmica não foi carregada no último reinício.", "Detalhes", host::openLens);
        } else if (!pending.isEmpty()) {
            key = "pending" + pending.size();
            n = Ui.notice(a, t, Ui.Tone.INFO, pending.size() == 1 ? "1 ajuste da lente aguarda aplicação." : pending.size() + " ajustes da lente aguardam aplicação.", "Aplicar", host::openLens);
        } else if (!conflicts.isEmpty()) {
            key = "conflict" + conflicts.size();
            n = Ui.notice(a, t, Ui.Tone.INFO, conflicts.size() == 1 ? "Um ajuste foi alterado fora do app." : conflicts.size() + " ajustes foram alterados fora do app.", "Revisar", host::openConflicts);
        } else key = "";
        if (key.equals(noticeKey)) return;
        noticeKey = key;
        noticeSlot.removeAllViews();
        if (n != null) noticeSlot.addView(n, Ui.match());
    }
}
