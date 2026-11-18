package io.github.igorcv88.oneuiliquidglass.ui;

import android.os.Bundle;
import android.view.*;
import android.widget.*;

import io.github.igorcv88.oneuiliquidglass.config.*;
import io.github.igorcv88.oneuiliquidglass.config.product.ProductLayout;
import io.github.igorcv88.oneuiliquidglass.config.state.EditGuard;
import io.github.igorcv88.oneuiliquidglass.config.state.EffectiveState;
import io.github.igorcv88.oneuiliquidglass.R;
import io.github.igorcv88.oneuiliquidglass.ui.surfaces.GroupSection;
import io.github.igorcv88.oneuiliquidglass.ui.surfaces.SurfacesPlan;
import io.github.igorcv88.oneuiliquidglass.ui.kit.*;
import io.github.igorcv88.oneuiliquidglass.ui.preview.NotificationPreview;

import java.util.*;

/**
 * Superfícies: Pop-up / Bloqueio / Central, a sample that shrinks while the list scrolls under
 * it, the surface switch, the main controls of the route in use, and the appearance details in
 * collapsed groups (docs/OULG_UI_PLAN/SCREEN_SPECS.md section 2). Technical controls live in
 * Settings.
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
    private final List<GroupSection> groups = new ArrayList<>();
    private Controls.Surface surface = Controls.Surface.HEADSUP;
    private ProductLayout.Route builtRoute;
    private LinearLayout lensBanner;
    private LinearLayout materialRow;
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
        tabs = new Segmented(a, t, new String[] {HomePage.surfaceLabel(a, Controls.Surface.HEADSUP), HomePage.surfaceLabel(a, Controls.Surface.KEYGUARD), HomePage.surfaceLabel(a, Controls.Surface.SHADE)}, 0);
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
            TextView chip = Ui.text(a, t, UiLabels.backdrop(a, backdrops[i]), Theme.CAPTION, t.inkMuted, true);
            chip.setGravity(Gravity.CENTER);
            chip.setMinHeight(t.dp(36));
            chip.setPadding(t.dp(12), 0, t.dp(12), 0);
            chip.setOnClickListener(v -> backdrop(index));
            chip.setContentDescription(a.getString(R.string.preview_backdrop, UiLabels.backdrop(a, backdrops[i])));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, t.dp(44));
            p.setMarginEnd(t.dp(4));
            tools.addView(chip, p);
            backdropChips[i] = chip;
        }
        tools.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 1));
        FrameLayout theme = Ui.iconButton(a, t, Icon.MOON, t.inkMuted, a.getString(R.string.preview_theme));
        themeIcon = (Icon.View) theme.getTag();
        theme.setOnClickListener(v -> {
            boolean light = !preview.lightTheme();
            host.model().ui.edit().putBoolean("preview.light", light).apply();
            preview.lightTheme(light);
            paintTools();
            host.feedback(a.getString(light ? R.string.preview_theme_light : R.string.preview_theme_dark));
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
        out.putInt("scroll", scroll.getScrollY());
    }

    @Override
    public void restore(Bundle in) {
        surface = Controls.Surface.of(in.getString("surface", "headsup"));
        tabs.select(surface.ordinal(), false);
        preview.surface(surface);
        int y = in.getInt("scroll");
        scroll.post(() -> scroll.scrollTo(0, y));
    }

    /** The ordered sections of the page for a surface and a route (no View). */
    static SurfacesPlan plan(Controls.Surface surface, ProductLayout.Route route, Set<String> favorites) {
        return SurfacesPlan.of(surface, route, favorites);
    }

    private Set<String> favorites() {
        List<String> favored = new ArrayList<>();
        for (Controls.Control c : Controls.all())
            if (host.model().ui.getBoolean("favorite." + c.key, false)) favored.add(c.key);
        return SurfacesPlan.favorites(surface, favored);
    }

    private boolean favoritesNoticeChecked;

    @Override
    public void bind() {
        AppModel m = host.model();
        if (!favoritesNoticeChecked) {
            favoritesNoticeChecked = true;
            int left = m.takeFavoritesMigrationNotice();
            if (left > 0) host.feedback(host.activity().getString(R.string.favorites_migrated, left));
        }
        ControlState st = m.state();
        Applicability app = m.rules();
        if (baseline == null) baseline = new HashMap<>(st.shownMap());
        Set<String> fav = favorites();
        AppModel.SurfaceRoute where = m.surfaceRoute(surface);
        if (builtSurface != surface || builtRoute != where.route || !fav.equals(builtFavorites))
            rebuild(where.route, fav);
        GlassMaterial material = GlassMaterial.forSurface(st::shown, surface);
        if (materialRow != null) Ui.setSubtitle(materialRow, material == null
                ? UiLabels.selector(host.activity(), st.shown(surface == Controls.Surface.HEADSUP
                        ? GlassMaterial.HU_SELECTOR : GlassMaterial.KG_SELECTOR))
                : UiLabels.material(host.activity(), material));
        if (qsToggle != null) {
            qsToggle.set(!"0".equals(st.shown(ProductFlags.QUICK_SETTINGS)), true);
            Ui.enable(qsRow, st.enabled);
        }
        lensBanner.setVisibility(where.lensNotLoaded ? View.VISIBLE : View.GONE);
        boolean surfaceOn = app.surfaceOn(surface);
        surfaceToggle.set(surfaceOn, true);
        Ui.enable(surfaceRow, st.enabled);
        Ui.setSubtitle(surfaceRow, !st.enabled ? host.activity().getString(R.string.surface_master_off) : surfaceOn ? host.activity().getString(R.string.switch_on) : host.activity().getString(R.string.surface_original));
        offNotice.setVisibility(st.enabled ? View.GONE : View.VISIBLE);
        bindRows(m, st, app);
        for (Map.Entry<String, LinearLayout> e : layerRows.entrySet()) {
            Applicability.Result res = app.layer(surface, e.getKey());
            Toggle tg = (Toggle) e.getValue().getChildAt(e.getValue().getChildCount() - 1);
            tg.set(app.layerOn(surface, e.getKey()), true);
            boolean usable = res.usable();
            Ui.enable(e.getValue(), usable);
            Ui.setSubtitle(e.getValue(), usable ? null : ControlReasons.text(host.activity(), res.reason));
        }
        preview.values(st::shown, baseline::get);
    }

    /** One plan for every row; each group counts the controls that take effect. */
    private void bindRows(AppModel m, ControlState st, Applicability app) {
        EffectiveView view = m.effectiveView();
        Set<String> active = new HashSet<>();
        for (ControlRow r : rows) {
            EffectiveState.Effective e = view.of(r.control.key, surface);
            if (RowText.enabled(e)) active.add(r.control.key);
            r.bind(st, app, e);
        }
        for (GroupSection g : groups) {
            int n = 0;
            for (String k : g.keys) if (active.contains(k)) n++;
            g.counter(n);
        }
    }

    /** Rebuilds the list for a new route or favorites set, keeping the scroll position. */
    private void rebuild(ProductLayout.Route route, Set<String> fav) {
        int y = scroll.getScrollY();
        build(route, fav);
        scroll.post(() -> scroll.scrollTo(0, y));
    }

    private void build(ProductLayout.Route route, Set<String> fav) {
        android.app.Activity a = host.activity();
        builtSurface = surface;
        builtRoute = route;
        builtFavorites = fav;
        materialRow = null;
        qsToggle = null;
        qsRow = null;
        rows.clear();
        groups.clear();
        layerRows.clear();
        list.removeAllViews();

        offNotice = Ui.notice(a, t, Ui.Tone.INFO, a.getString(R.string.surface_master_off_notice), a.getString(R.string.action_enable), () -> host.model().master(true));
        list.addView(offNotice, Ui.match(0, t.dp(10)));
        lensBanner = Ui.notice(a, t, Ui.Tone.INFO, a.getString(R.string.lens_not_loaded_banner), null, null);
        list.addView(lensBanner, Ui.match(0, t.dp(10)));

        LinearLayout gate = Ui.card(a, t);
        surfaceToggle = new Toggle(a, t);
        Icon icon = surface == Controls.Surface.HEADSUP ? Icon.POPUP : surface == Controls.Surface.KEYGUARD ? Icon.LOCK : Icon.SHADE;
        surfaceRow = Ui.switchRow(a, t, icon, a.getString(R.string.home_surface_glass, HomePage.surfaceLabel(a, surface)), "", surfaceToggle);
        Controls.Surface s = surface;
        surfaceToggle.listener((x, on) -> host.model().flag("surface." + s.id + ".enabled", on));
        gate.addView(surfaceRow);
        list.addView(gate, Ui.match());

        if (surface != Controls.Surface.SHADE) {
            LinearLayout materialCard = Ui.card(a, t);
            materialRow = Ui.listRow(a, t, Icon.LAYERS, a.getString(R.string.material_title), "", Ui.chevron(a, t));
            materialRow.setOnClickListener(v -> new MaterialSheet(host).show());
            materialCard.addView(materialRow);
            list.addView(materialCard, Ui.match(t.dp(10), 0));
        }

        SurfacesPlan plan = plan(surface, route, fav);
        boolean detailsTitled = false;
        for (SurfacesPlan.Section section : plan.sections) {
            switch (section.kind) {
                case FAVORITES:
                case MAIN:
                    list.addView(Ui.section(a, t, a.getString(section.titleRes)));
                    list.addView(card(section.keys, section.kind == SurfacesPlan.Kind.MAIN), Ui.match());
                    break;
                case DETAIL_GROUP:
                    if (!detailsTitled) {
                        list.addView(Ui.section(a, t, a.getString(R.string.section_appearance_details)),
                                Ui.match(t.dp(Theme.GROUP_GAP - 22), 0));
                        detailsTitled = true;
                    }
                    list.addView(group(section, route, groupIcon(section.id)), Ui.match(t.dp(Theme.ROW_GAP_TIGHT * 2), 0));
                    break;
                case QS_MAIN:
                    quickSettingsMain(a, section);
                    break;
                case QS_DETAILS:
                    list.addView(group(section, route, Icon.SHADE), Ui.match(t.dp(Theme.ROW_GAP_TIGHT * 2), 0));
                    break;
                default:
                    list.addView(group(section, route, Icon.CLOCK), Ui.match(t.dp(Theme.GROUP_GAP), 0));
            }
        }

        list.addView(Ui.section(a, t, a.getString(R.string.surface_layers)));
        LinearLayout layers = Ui.card(a, t);
        boolean first = true;
        for (Map.Entry<String, String> e : Controls.layers(surface).entrySet()) {
            String key = "surface." + surface.id + ".feature." + e.getKey() + ".enabled";
            Toggle tg = new Toggle(a, t);
            LinearLayout r = Ui.switchRow(a, t, null, UiLabels.layer(a, e.getKey()), null, tg);
            tg.listener((x, on) -> host.model().flag(key, on));
            if (!first) layers.addView(Ui.divider(a, t, t.dp(18)));
            first = false;
            layers.addView(r);
            layerRows.put(e.getKey(), r);
        }
        list.addView(layers, Ui.match());

        LinearLayout footer = Ui.row(a);
        TextView search = Ui.button(a, t, a.getString(R.string.search_title), Icon.SEARCH, Ui.ButtonStyle.OUTLINED, true);
        search.setOnClickListener(v -> new SearchSheet(host, surface).show());
        footer.addView(search, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView reset = Ui.button(a, t, host.activity().getString(R.string.action_restore), Icon.RESET, Ui.ButtonStyle.TEXT, true);
        reset.setContentDescription(a.getString(R.string.surface_restore_desc, HomePage.surfaceLabel(a, surface)));
        reset.setOnClickListener(v -> confirmReset(plan));
        footer.addView(reset);
        list.addView(footer, Ui.match(t.dp(18), 0));
    }

    private static Icon groupIcon(String id) {
        switch (id) {
            case "blur": return Icon.LAYERS;
            case "refraction": return Icon.LENS;
            case "light": return Icon.PULSE;
            case "color": return Icon.PALETTE;
            default: return Icon.CONTRAST;
        }
    }

    /** A collapsed group with its counter and "Restore group"; its open state is remembered per route. */
    private View group(SurfacesPlan.Section section, ProductLayout.Route route, Icon icon) {
        android.app.Activity a = host.activity();
        String pref = "group." + surface.id + "." + route.name() + "." + section.id;
        String title = a.getString(section.titleRes);
        GroupSection g = new GroupSection(a, t, icon, title, section.keys,
                host.model().ui.getBoolean(pref, false),
                now -> host.model().ui.edit().putBoolean(pref, now).apply(),
                (body, key) -> addRow(body, key, false),
                () -> confirmRestoreGroup(title, section.keys));
        groups.add(g);
        return g.card;
    }

    private LinearLayout card(List<String> keys, boolean primary) {
        LinearLayout card = Ui.card(host.activity(), t);
        for (String k : keys) addRow(card, k, primary);
        return card;
    }

    /** Central de controle: its own opt-in, then the main quick-panel controls; the rest is a group. */
    private void quickSettingsMain(android.app.Activity a, SurfacesPlan.Section section) {
        list.addView(Ui.section(a, t, a.getString(section.titleRes)));
        LinearLayout card = Ui.card(a, t);
        qsToggle = new Toggle(a, t);
        qsRow = Ui.switchRow(a, t, Icon.SHADE, a.getString(R.string.home_qs_glass),
                a.getString(R.string.qs_summary), qsToggle);
        qsToggle.listener((x, on) -> host.model().flag(ProductFlags.QUICK_SETTINGS, on));
        card.addView(qsRow);
        for (String k : section.keys) addRow(card, k, true);
        list.addView(card, Ui.match());
    }

    private void addRow(LinearLayout parent, String key, boolean primary) {
        Controls.Control c = Controls.get(key);
        if (c == null || !Controls.editable(key)) return;
        if (parent.getChildCount() > 0 && parent.getChildAt(parent.getChildCount() - 1) instanceof ControlRow)
            parent.addView(Ui.divider(host.activity(), t, t.dp(20)));
        ControlRow r = new ControlRow(host.activity(), host, c, surface, primary);
        parent.addView(r, Ui.match());
        rows.add(r);
    }

    /** Asks before restoring a group; keys that are not in effect keep their saved value. */
    private void confirmRestoreGroup(String title, List<String> keys) {
        AppModel m = host.model();
        android.app.Activity a = host.activity();
        Applicability rules = m.rules();
        int count = 0;
        for (String k : keys)
            if (EditGuard.check(EditGuard.Action.RESET_GROUP, k, surface, rules).outcome == EditGuard.Outcome.ALLOW) count++;
        if (count == 0) {
            host.feedback(a.getString(R.string.restore_group_nothing));
            return;
        }
        Sheet sheet = new Sheet(a, t, a.getString(R.string.restore_group));
        sheet.text(a.getString(R.string.restore_group_confirm, count, title));
        sheet.action(a.getString(R.string.action_cancel), Ui.ButtonStyle.OUTLINED, sheet::dismiss);
        sheet.action(a.getString(R.string.restore_group), Ui.ButtonStyle.FILLED, () -> {
            m.resetGroup(keys, surface);
            host.feedback(a.getString(R.string.preset_restored));
            sheet.dismiss();
        });
        sheet.show();
    }

    private void confirmReset(SurfacesPlan plan) {
        List<String> keys = new ArrayList<>();
        boolean shared = false;
        for (SurfacesPlan.Section section : plan.sections)
            for (String k : section.keys) {
                keys.add(k);
                shared |= Controls.get(k).surfaces.size() > 1;
            }
        Sheet sheet = new Sheet(host.activity(), t, host.activity().getString(R.string.surface_reset_question, HomePage.surfaceLabel(host.activity(), surface)));
        sheet.text(host.activity().getString(R.string.surface_reset_warning)
                + (shared ? host.activity().getString(R.string.surface_reset_shared) : ""));
        sheet.action(host.activity().getString(R.string.action_cancel), Ui.ButtonStyle.OUTLINED, sheet::dismiss);
        sheet.action(host.activity().getString(R.string.action_restore), Ui.ButtonStyle.FILLED, () -> {
            host.model().reset(keys, surface); // inactive keys keep their saved value
            host.feedback(host.activity().getString(R.string.preset_restored));
            sheet.dismiss();
        });
        sheet.show();
    }
}
