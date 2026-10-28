package io.github.igorcv88.oneuiliquidglass.ui;

import android.os.Bundle;
import android.view.*;
import android.widget.*;

import io.github.igorcv88.oneuiliquidglass.config.*;
import io.github.igorcv88.oneuiliquidglass.ui.kit.*;
import io.github.igorcv88.oneuiliquidglass.ui.preview.NotificationPreview;

import java.util.*;

/**
 * Superfícies: Pop-up / Bloqueio / Central, a sample that shrinks while the list scrolls under
 * it, the surface switch, the main controls for the current mode, effect layers, and the rest
 * grouped by perceived effect, closed by default.
 */
public final class SurfacesPage implements Page {
    private final Host host;
    private Toggle qsToggle;
    private View qsRow;
    private final Theme t;
    private final FrameLayout root;
    private final ScrollView scroll;
    private final LinearLayout list, header, tools;
    private final Segmented tabs;
    private final NotificationPreview preview;
    private final FrameLayout previewFrame;
    private final List<ControlRow> rows = new ArrayList<>();
    private final Map<String, LinearLayout> layerRows = new HashMap<>();
    private final Set<String> open = new HashSet<>();
    private Controls.Surface surface = Controls.Surface.HEADSUP;
    private Mode builtMode;
    private Controls.Surface builtSurface;
    private Set<String> builtFavorites = Collections.emptySet();
    private Map<String, String> baseline;
    private Toggle surfaceToggle;
    private LinearLayout surfaceRow, offNotice;
    private final TextView[] backdropChips = new TextView[4];
    private Icon.View themeIcon;
    private int topInset, expandedHeader, previewExpanded, previewCompact;
    /** Scroll position and delta, for the navigation bar. */
    public java.util.function.BiConsumer<Integer, Integer> onScroll;

    public SurfacesPage(Host host) {
        this.host = host;
        t = host.theme();
        android.app.Activity a = host.activity();
        root = new FrameLayout(a) {
            private int lastW, lastH;

            @Override
            protected void onMeasure(int w, int h) {
                int width = MeasureSpec.getSize(w), height = MeasureSpec.getSize(h);
                if (width != lastW || height != lastH) {
                    lastW = width;
                    lastH = height;
                    measureHeader(width, height);
                }
                super.onMeasure(w, h);
            }
        };
        scroll = Ui.scroller(a);
        list = Ui.column(a);
        scroll.addView(list);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        header = Ui.column(a);
        header.setBackgroundColor(t.bg);
        header.setClickable(true);
        tabs = new Segmented(a, t, new String[] {"Pop-up", "Bloqueio", "Central"}, 0);
        tabs.listener(i -> show(Controls.Surface.values()[i]));
        header.addView(tabs, Ui.match());
        preview = new NotificationPreview(a);
        previewFrame = new FrameLayout(a);
        previewFrame.setBackground(Ui.rounded(t.surfaceLow, t.dpf(22)));
        previewFrame.setClipToOutline(true);
        previewFrame.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        header.addView(previewFrame, Ui.match(t.dp(10), 0));
        tools = Ui.row(a);
        tools.setPadding(0, t.dp(6), 0, t.dp(2));
        NotificationPreview.Backdrop[] backdrops = NotificationPreview.Backdrop.values();
        for (int i = 0; i < backdrops.length; i++) {
            final int index = i;
            TextView chip = Ui.text(a, t, backdrops[i].label, Theme.CAPTION, t.inkMuted, true);
            chip.setGravity(Gravity.CENTER);
            chip.setMinHeight(t.dp(36));
            chip.setPadding(t.dp(12), 0, t.dp(12), 0);
            chip.setOnClickListener(v -> backdrop(index));
            chip.setContentDescription("Fundo " + backdrops[i].label);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, t.dp(44));
            p.setMarginEnd(t.dp(4));
            tools.addView(chip, p);
            backdropChips[i] = chip;
        }
        tools.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 1));
        FrameLayout theme = Ui.iconButton(a, t, Icon.MOON, t.inkMuted, "Tema da amostra");
        themeIcon = (Icon.View) theme.getTag();
        theme.setOnClickListener(v -> {
            boolean light = !preview.lightTheme();
            host.model().ui.edit().putBoolean("preview.light", light).apply();
            preview.lightTheme(light);
            paintTools();
            host.feedback(light ? "Amostra em tema claro" : "Amostra em tema escuro");
        });
        tools.addView(theme);
        header.addView(tools, Ui.match());
        root.addView(header, new FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        preview.lightTheme(host.model().ui.getBoolean("preview.light", !t.dark));
        preview.backdrop(backdrops[Math.max(0, Math.min(3, host.model().ui.getInt("preview.backdrop", 2)))]);
        paintTools();
        scroll.setOnScrollChangeListener((v, x, y, ox, oy) -> {
            collapse(y);
            if (onScroll != null) onScroll.accept(y, y - oy);
        });

    }

    private void backdrop(int index) {
        preview.backdrop(NotificationPreview.Backdrop.values()[index]);
        host.model().ui.edit().putInt("preview.backdrop", index).apply();
        paintTools();
    }

    private void paintTools() {
        for (int i = 0; i < backdropChips.length; i++) {
            boolean on = preview.backdrop().ordinal() == i;
            backdropChips[i].setTextColor(on ? t.onAccentContainer : t.inkMuted);
            backdropChips[i].setBackground(Ui.ripple(t, Ui.rounded(on ? t.accentContainer : 0, t.dpf(18)), t.dpf(18)));
            backdropChips[i].setSelected(on);
        }
        themeIcon.set(preview.lightTheme() ? Icon.SUN : Icon.MOON, t.inkMuted);
    }

    /** Header heights from the width: the sample keeps its proportions when expanded. */
    private void measureHeader() {
        measureHeader(root.getWidth(), root.getHeight());
    }

    private void measureHeader(int rootWidth, int rootHeight) {
        if (rootWidth <= 0) return;
        // Wide screens (landscape, unfolded): the sample stays in a left column, controls scroll beside it.
        wide = rootWidth > rootHeight && rootWidth >= t.dp(600);
        int column = wide ? Math.round(rootWidth * .46f) : rootWidth;
        int width = column - t.dp(32);
        FrameLayout.LayoutParams hp = (FrameLayout.LayoutParams) header.getLayoutParams();
        hp.width = wide ? column : ViewGroup.LayoutParams.MATCH_PARENT;
        tools.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
        tools.setAlpha(1);
        tools.setVisibility(View.VISIBLE);
        tools.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        tabs.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        toolsExpanded = tools.getMeasuredHeight();
        int chrome = topInset + t.dp(8) + tabs.getMeasuredHeight() + t.dp(10) + toolsExpanded + t.dp(8);
        int ideal = Math.round(width * 248f / 360f);
        previewExpanded = wide ? Math.max(t.dp(120), Math.min(ideal, rootHeight - chrome - bottomInset)) : Math.min(ideal, Math.round(rootHeight * .36f));
        previewCompact = wide ? previewExpanded : Math.min(previewExpanded, t.dp(128));
        previewFrame.getLayoutParams().height = previewExpanded;
        expandedHeader = chrome + previewExpanded;
        if (wide) list.setPaddingRelative(column, topInset + t.dp(8), t.dp(16), list.getPaddingBottom());
        else list.setPaddingRelative(t.dp(16), expandedHeader + t.dp(4), t.dp(16), list.getPaddingBottom());
        header.requestLayout();
        collapse(scroll.getScrollY());
    }

    private boolean wide;
    private int toolsExpanded;
    private int bottomInset;

    private void collapse(int y) {
        if (previewExpanded == 0 || wide) return;
        // The expanded height measured once: the live one shrinks as the header collapses.
        int toolsH = toolsExpanded;
        int range = previewExpanded - previewCompact + toolsH;
        float p = range <= 0 ? 0 : Math.max(0, Math.min(1, y / (float) range));
        int ph = Math.round(previewExpanded + (previewCompact - previewExpanded) * p);
        if (previewFrame.getLayoutParams().height != ph) {
            previewFrame.getLayoutParams().height = ph;
            previewFrame.requestLayout();
        }
        int th = Math.round(toolsH * (1 - p));
        tools.setAlpha(Math.max(0, 1 - p * 1.8f));
        tools.setVisibility(th < t.dp(2) ? View.INVISIBLE : View.VISIBLE);
        if (tools.getLayoutParams().height != th) {
            tools.getLayoutParams().height = th;
            tools.requestLayout();
        }
        header.setElevation(p > .98f ? t.dpf(2) : 0);
    }

    public void show(Controls.Surface s) {
        if (s == surface && builtSurface == s) return;
        surface = s;
        tabs.select(s.ordinal(), false);
        preview.surface(s);
        baseline = new HashMap<>(host.model().state().shownMap());
        bind();
        scroll.scrollTo(0, 0);
    }

    Controls.Surface surface() {
        return surface;
    }

    NotificationPreview preview() {
        return preview;
    }

    /** Draft or focus changed: only the sample redraws. */
    public void previewChanged(Controls.Channel focus) {
        ControlState st = host.model().state();
        preview.values(st::shown, baseline::get);
        preview.focus(focus);
    }

    @Override
    public View view() {
        return root;
    }

    @Override
    public ScrollView scroller() {
        return scroll;
    }

    @Override
    public void insets(int top, int bottom) {
        topInset = top;
        header.setPadding(t.dp(16), top + t.dp(8), t.dp(16), t.dp(8));
        bottomInset = bottom;
        list.setPadding(list.getPaddingLeft(), list.getPaddingTop(), t.dp(16), bottom);
        measureHeader();
    }

    @Override
    public void save(Bundle out) {
        out.putString("surface", surface.id);
        out.putStringArrayList("open", new ArrayList<>(open));
        out.putInt("scroll", scroll.getScrollY());
    }

    @Override
    public void restore(Bundle in) {
        surface = Controls.Surface.of(in.getString("surface", "headsup"));
        ArrayList<String> o = in.getStringArrayList("open");
        if (o != null) open.addAll(o);
        tabs.select(surface.ordinal(), false);
        preview.surface(surface);
        int y = in.getInt("scroll");
        scroll.post(() -> scroll.scrollTo(0, y));
    }

    private Set<String> favorites() {
        Set<String> out = new LinkedHashSet<>();
        for (Controls.Control c : Controls.all())
            if (c.on(surface) && Controls.editable(c.key) && host.model().ui.getBoolean("favorite." + c.key, false)) out.add(c.key);
        return out;
    }

    @Override
    public void bind() {
        AppModel m = host.model();
        ControlState st = m.state();
        Applicability app = st.applicability();
        if (baseline == null) baseline = new HashMap<>(st.shownMap());
        Set<String> fav = favorites();
        if (builtSurface != surface || builtMode != app.mode || !fav.equals(builtFavorites)) build(app.mode, fav);
        if (qsToggle != null) {
            qsToggle.set(!"0".equals(st.shown(ProductFlags.QUICK_SETTINGS)), true);
            Ui.enable(qsRow, st.enabled);
        }
        boolean surfaceOn = app.surfaceOn(surface);
        surfaceToggle.set(surfaceOn, true);
        Ui.enable(surfaceRow, st.enabled);
        Ui.setSubtitle(surfaceRow, !st.enabled ? "O vidro está desligado nas notificações" : surfaceOn ? "Ligado" : "Visual original do sistema");
        offNotice.setVisibility(st.enabled ? View.GONE : View.VISIBLE);
        for (ControlRow r : rows) r.bind(st, app);
        for (Map.Entry<String, LinearLayout> e : layerRows.entrySet()) {
            Applicability.Result res = app.layer(surface, e.getKey());
            Toggle tg = (Toggle) e.getValue().getChildAt(e.getValue().getChildCount() - 1);
            tg.set(app.layerOn(surface, e.getKey()), true);
            boolean usable = res.usable();
            Ui.enable(e.getValue(), usable);
            Ui.setSubtitle(e.getValue(), usable ? null : res.reason);
        }
        preview.values(st::shown, baseline::get);
    }

    private void build(Mode mode, Set<String> fav) {
        android.app.Activity a = host.activity();
        builtSurface = surface;
        builtMode = mode;
        builtFavorites = fav;
        rows.clear();
        layerRows.clear();
        list.removeAllViews();

        offNotice = Ui.notice(a, t, Ui.Tone.INFO, "O vidro está desligado nas notificações.", "Ligar", () -> host.model().master(true));
        list.addView(offNotice, Ui.match(0, t.dp(10)));

        LinearLayout gate = Ui.card(a, t);
        surfaceToggle = new Toggle(a, t);
        Icon icon = surface == Controls.Surface.HEADSUP ? Icon.POPUP : surface == Controls.Surface.KEYGUARD ? Icon.LOCK : Icon.SHADE;
        surfaceRow = Ui.switchRow(a, t, icon, "Vidro " + (surface == Controls.Surface.HEADSUP ? "no pop-up" : surface == Controls.Surface.KEYGUARD ? "no bloqueio" : "na central"), "", surfaceToggle);
        Controls.Surface s = surface;
        surfaceToggle.listener((x, on) -> host.model().flag("surface." + s.id + ".enabled", on));
        gate.addView(surfaceRow);
        list.addView(gate, Ui.match());

        Set<String> placed = new HashSet<>();
        qsToggle=null; qsRow=null;
        if (surface == Controls.Surface.SHADE) {
            list.addView(Ui.section(a,t,"Controles rápidos"));
            LinearLayout qsGate=Ui.card(a,t);
            qsToggle=new Toggle(a,t);
            qsRow=Ui.switchRow(a,t,Icon.SHADE,"Vidro nos controles rápidos",null,qsToggle);
            qsToggle.listener((x,on)->host.model().flag(ProductFlags.QUICK_SETTINGS,on));
            qsGate.addView(qsRow);list.addView(qsGate,Ui.match());
            String[][] keys={
                {"qs.darkfill","qs.darkcolor","qs.lightfill","qs.lightcolor","qs.lightstyle"},
                {"qs.idle","qs.active"}, {"qs.darkrim","qs.lightrim","qs.edgeblur"},
                {"qs.outer","qs.track","qs.fill"}, {"sf.qslens","sf.qsbody","sf.qsdepth","sf.qslight"}
            };
            String[] names={"Aparência","Círculos","Bordas","Sliders","Avançado"};
            for(int i=0;i<keys.length;i++) {
                if(i==4){
                    String id="shade.quicksettings.advanced";
                    View[] disclosure=Ui.disclosure(a,t,Icon.LENS,names[i],"Luz e refração da borda",open.contains(id),on->{if(on)open.add(id);else open.remove(id);});
                    LinearLayout advanced=Ui.card(a,t);advanced.addView(disclosure[0]);
                    LinearLayout body=(LinearLayout)disclosure[1];
                    for(String k:keys[i])addRow(body,k,placed);
                    advanced.addView(body);list.addView(advanced,Ui.match());
                }else{
                    list.addView(Ui.section(a,t,names[i]));
                    list.addView(card(Arrays.asList(keys[i]),placed),Ui.match());
                }
            }
            list.addView(Ui.notice(a,t,Ui.Tone.INFO,"A amostra acima é de notificações. Confira os controles rápidos no aparelho.",null,null),Ui.match());
        }
        if (!fav.isEmpty()) {
            list.addView(Ui.section(a, t, "Favoritos"));
            list.addView(card(new ArrayList<>(fav), placed), Ui.match());
        }
        list.addView(Ui.section(a, t, "Principais"));
        List<String> primary = new ArrayList<>();
        for (String k : Controls.primary(surface, mode)) if (!placed.contains(k)) primary.add(k);
        list.addView(card(primary, placed), Ui.match());

        list.addView(Ui.section(a, t, "Camadas do efeito"));
        LinearLayout layers = Ui.card(a, t);
        boolean first = true;
        for (Map.Entry<String, String> e : Controls.layers(surface).entrySet()) {
            String key = "surface." + surface.id + ".feature." + e.getKey() + ".enabled";
            Toggle tg = new Toggle(a, t);
            LinearLayout r = Ui.switchRow(a, t, null, e.getValue(), null, tg);
            tg.listener((x, on) -> host.model().flag(key, on));
            if (!first) layers.addView(Ui.divider(a, t, t.dp(18)));
            first = false;
            layers.addView(r);
            layerRows.put(e.getKey(), r);
        }
        list.addView(layers, Ui.match());

        list.addView(Ui.section(a, t, "Mais ajustes"));
        LinearLayout groups = Ui.card(a, t);
        boolean any = false;
        for (Controls.Section section : Controls.Section.values()) {
            if (section == Controls.Section.TECHNICAL) continue;
            if (section == Controls.Section.LENS_TUNING && mode != Mode.DYNAMIC_LENS) continue;
            List<String> std = new ArrayList<>(), adv = new ArrayList<>();
            for (Controls.Control c : Controls.all()) {
                if (c.section != section || !c.on(surface) || !Controls.editable(c.key) || placed.contains(c.key)) continue;
                if (c.tier == Controls.Tier.STANDARD) std.add(c.key);
                else if (c.tier == Controls.Tier.ADVANCED) adv.add(c.key);
            }
            if (std.isEmpty() && adv.isEmpty()) continue;
            String id = surface.id + "." + section.name();
            int count = std.size() + adv.size();
            View[] d = Ui.disclosure(a, t, sectionIcon(section), section.label, count == 1 ? "1 ajuste" : count + " ajustes", open.contains(id), on -> {
                if (on) open.add(id);
                else open.remove(id);
            });
            if (any) groups.addView(Ui.divider(a, t, t.dp(72)));
            any = true;
            groups.addView(d[0]);
            LinearLayout body = (LinearLayout) d[1];
            for (String k : std) addRow(body, k, placed);
            if (!adv.isEmpty()) {
                if (!std.isEmpty()) {
                    TextView label = Ui.text(a, t, "Avançado", Theme.CAPTION, t.inkFaint, true);
                    label.setPadding(t.dp(20), t.dp(14), t.dp(20), t.dp(2));
                    body.addView(label);
                }
                for (String k : adv) addRow(body, k, placed);
            }
            groups.addView(body);
        }
        if (any) list.addView(groups, Ui.match());

        LinearLayout footer = Ui.row(a);
        TextView search = Ui.button(a, t, "Buscar ajuste", Icon.SEARCH, Ui.ButtonStyle.OUTLINED, true);
        search.setOnClickListener(v -> new SearchSheet(host, surface).show());
        footer.addView(search, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView reset = Ui.button(a, t, "Restaurar", Icon.RESET, Ui.ButtonStyle.TEXT, true);
        reset.setContentDescription("Restaurar padrões de " + surface.label);
        reset.setOnClickListener(v -> confirmReset());
        footer.addView(reset);
        list.addView(footer, Ui.match(t.dp(18), 0));
    }

    private Icon sectionIcon(Controls.Section s) {
        switch (s) {
            case GLASS: return Icon.LAYERS;
            case EDGES: return Icon.LENS;
            case COLOR: return Icon.CONTRAST;
            case BEHAVIOR: return Icon.CLOCK;
            case MOTION: return Icon.PULSE;
            default: return Icon.RESTART;
        }
    }

    private LinearLayout card(List<String> keys, Set<String> placed) {
        LinearLayout card = Ui.card(host.activity(), t);
        for (String k : keys) addRow(card, k, placed);
        return card;
    }

    private void addRow(LinearLayout parent, String key, Set<String> placed) {
        Controls.Control c = Controls.get(key);
        if (c == null || !Controls.editable(key)) return;
        if (parent.getChildCount() > 0 && parent.getChildAt(parent.getChildCount() - 1) instanceof ControlRow)
            parent.addView(Ui.divider(host.activity(), t, t.dp(20)));
        ControlRow r = new ControlRow(host.activity(), host, c, surface);
        parent.addView(r, Ui.match());
        rows.add(r);
        placed.add(key);
    }

    private void confirmReset() {
        List<String> keys = new ArrayList<>();
        boolean shared = false;
        for (Controls.Control c : Controls.all())
            if (c.on(surface) && Controls.editable(c.key) && c.tier != Controls.Tier.TECHNICAL) {
                keys.add(c.key);
                shared |= c.surfaces.size() > 1;
            }
        Sheet sheet = new Sheet(host.activity(), t, "Restaurar " + surface.label.toLowerCase(Locale.ROOT) + "?");
        sheet.text("Os ajustes deste ambiente voltam ao padrão. Você pode desfazer em Estilos."
                + (shared ? " Ajustes que valem também em outros ambientes mudam lá também." : ""));
        sheet.action("Cancelar", Ui.ButtonStyle.OUTLINED, sheet::dismiss);
        sheet.action("Restaurar", Ui.ButtonStyle.FILLED, () -> {
            host.model().reset(keys);
            host.feedback("Padrões restaurados");
            sheet.dismiss();
        });
        sheet.show();
    }
}
