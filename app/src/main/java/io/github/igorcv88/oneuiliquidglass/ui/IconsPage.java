package io.github.igorcv88.oneuiliquidglass.ui;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import io.github.igorcv88.oneuiliquidglass.R;
import io.github.igorcv88.oneuiliquidglass.config.Controls;
import io.github.igorcv88.oneuiliquidglass.config.IconSettings;
import io.github.igorcv88.oneuiliquidglass.ui.kit.Icon;
import io.github.igorcv88.oneuiliquidglass.ui.kit.Theme;
import io.github.igorcv88.oneuiliquidglass.ui.kit.Ui;

import java.util.ArrayList;
import java.util.List;

/** One destination for icon eligibility, sizes and both notification/status/QS palettes. */
public final class IconsPage implements Page {
    public static boolean isIconKey(String key) { return IconSettings.contains(key); }

    private final Host host;
    private final Theme theme;
    private final ScrollView scroll;
    private final LinearLayout content;
    private final LinearLayout pendingSlot;
    private final IconSizePreview preview;
    private final List<ControlRow> rows = new ArrayList<>();
    private int displayedPending = -1;
    private final List<View> fixedNotification = new ArrayList<>(), dualNotification = new ArrayList<>();
    private final List<View> fixedStatus = new ArrayList<>(), dualStatus = new ArrayList<>();

    public IconsPage(Host host) {
        this.host = host;
        this.theme = host.theme();
        android.app.Activity a = host.activity();
        scroll = Ui.scroller(a);
        content = Ui.column(a);
        scroll.addView(content);
        content.addView(Ui.title(a, theme, a.getString(R.string.nav_icons)), Ui.match(theme.dp(8), theme.dp(4)));
        pendingSlot = Ui.column(a);
        content.addView(pendingSlot, Ui.match());
        content.addView(Ui.section(a, theme, a.getString(R.string.icons_preview)));
        LinearLayout previewCard = Ui.card(a, theme);
        preview = new IconSizePreview(a, theme);
        previewCard.addView(preview, Ui.match());
        previewCard.addView(Ui.text(a, theme, a.getString(R.string.icons_preview_note),
                Theme.CAPTION, theme.inkMuted, false), Ui.match());
        content.addView(previewCard, Ui.match());

        section(R.string.icons_notifications,
                "iconmode", "iconcardsize", "iconinkmode");
        colorGroup(fixedNotification, "iconfixedink");
        colorGroup(dualNotification, "iconlightink", "icondarkink");
        section(R.string.icons_status_bar,
                "statusiconsize", "statusmaxicons", "statusinkmode");
        colorGroup(fixedStatus, "statusfixedink");
        colorGroup(dualStatus, "statuslightink", "statusdarkink");
        section(R.string.icons_quick_settings,
                "qs.tilelightonink", "qs.tilelightoffink", "qs.tiledarkonink", "qs.tiledarkoffink");
        section(R.string.icons_compatibility, "icontone");

        content.addView(Ui.section(a, theme, a.getString(R.string.icons_library)));
        LinearLayout library = Ui.card(a, theme);
        LinearLayout manage = Ui.listRow(a, theme, Icon.BELL,
                a.getString(R.string.notification_icons), a.getString(R.string.notification_icons_desc),
                Ui.chevron(a, theme));
        manage.setOnClickListener(v -> new NotificationIconsSheet(host).show());
        library.addView(manage);
        content.addView(library, Ui.match());
    }

    private void section(int label, String... keys) {
        android.app.Activity a = host.activity();
        content.addView(Ui.section(a, theme, a.getString(label)));
        LinearLayout card = Ui.card(a, theme);
        for (String key : keys) add(card, key);
        content.addView(card, Ui.match());
    }

    private void colorGroup(List<View> target, String... keys) {
        android.app.Activity a = host.activity();
        LinearLayout card = Ui.card(a, theme);
        for (String key : keys) add(card, key);
        content.addView(card, Ui.match(theme.dp(6), 0));
        target.add(card);
    }

    private void add(LinearLayout card, String key) {
        Controls.Control control = Controls.get(key);
        if (control == null) throw new IllegalStateException("Missing Icons control: " + key);
        if (card.getChildCount() != 0) card.addView(Ui.divider(host.activity(), theme, theme.dp(20)));
        ControlRow row = new ControlRow(host.activity(), host, control, Controls.Surface.SHADE);
        rows.add(row);
        card.addView(row, Ui.match());
    }

    @Override public View view() { return scroll; }
    @Override public ScrollView scroller() { return scroll; }
    @Override public void insets(int top, int bottom) {
        content.setPadding(theme.dp(16), top + theme.dp(12), theme.dp(16), bottom);
    }
    @Override public void bind() {
        io.github.igorcv88.oneuiliquidglass.config.ControlState state = host.model().state();
        AppModel model = host.model();
        int pending = model.pendingIcons().size();
        if (displayedPending != pending) {
            displayedPending = pending;
            pendingSlot.removeAllViews();
            if (pending != 0) pendingSlot.addView(Ui.notice(host.activity(), theme, Ui.Tone.INFO,
                    host.activity().getResources().getQuantityString(R.plurals.icons_pending, pending, pending),
                    host.activity().getString(R.string.home_apply), () -> InfoSheets.applyIcons(host)), Ui.match());
        }
        preview.update(model.appliedIcon("statusiconsize"), state.shown("statusiconsize"),
                model.appliedIcon("iconcardsize"), state.shown("iconcardsize"),
                model.appliedIcon("statusmaxicons"), state.shown("statusmaxicons"));
        for (ControlRow row : rows) row.bind(state, state.applicability());
        String notifications = state.shown("iconinkmode");
        String status = state.shown("statusinkmode");
        for (View v : fixedNotification) v.setVisibility("1".equals(notifications) ? View.VISIBLE : View.GONE);
        for (View v : dualNotification) v.setVisibility("2".equals(notifications) ? View.VISIBLE : View.GONE);
        for (View v : fixedStatus) v.setVisibility("1".equals(status) ? View.VISIBLE : View.GONE);
        for (View v : dualStatus) v.setVisibility("2".equals(status) ? View.VISIBLE : View.GONE);
    }
}
