package io.github.igorcv88.oneuiliquidglass.ui;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import io.github.igorcv88.oneuiliquidglass.R;
import io.github.igorcv88.oneuiliquidglass.config.NotificationIconStore;
import io.github.igorcv88.oneuiliquidglass.ui.kit.Icon;
import io.github.igorcv88.oneuiliquidglass.ui.kit.Sheet;
import io.github.igorcv88.oneuiliquidglass.ui.kit.Theme;
import io.github.igorcv88.oneuiliquidglass.ui.kit.Ui;

import java.util.List;

/** Appearance -> Notification icons: explicit modes and locally unresolved package names only. */
public final class NotificationIconsSheet extends Sheet {
    private final Host host;

    public NotificationIconsSheet(Host host) {
        super(host.activity(), host.theme(), host.activity().getString(R.string.notification_icons));
        this.host = host;
        subtitle(activity.getString(R.string.notification_icons_desc));
        body().addView(Ui.section(activity, t, activity.getString(R.string.notification_icon_policy)));
        LinearLayout policy = Ui.card(activity, t);
        String mode = host.model().state().shown("iconmode");
        LinearLayout selected = Ui.listRow(activity, t, Icon.BELL, activity.getString(R.string.notification_icon_scope),
                modeLabel(mode), Ui.chevron(activity, t));
        selected.setOnClickListener(v -> modes());
        policy.addView(selected);
        body().addView(policy, Ui.match());
        text(activity.getString(R.string.notification_icons_custom_warning));

        List<String> misses = NotificationIconStore.missing(activity);
        body().addView(Ui.section(activity, t, activity.getString(R.string.notification_icons_unresolved)));
        int unresolved = 0;
        for (String pkg : misses) {
            if (NotificationIconStore.imported(activity, pkg)) continue;
            unresolved++;
            addPackage(pkg, false);
        }
        if (unresolved == 0) text(activity.getString(R.string.notification_icons_no_missing));
        TextView manual = Ui.button(activity, t, activity.getString(R.string.notification_icon_manual),
                null, Ui.ButtonStyle.OUTLINED, false);
        manual.setOnClickListener(v -> manualImport());
        body().addView(manual, Ui.match(t.dp(6), t.dp(12)));

        List<String> uploads = NotificationIconStore.importedPackages(activity);
        body().addView(Ui.section(activity, t, activity.getString(R.string.notification_icons_imported)));
        if (uploads.isEmpty()) text(activity.getString(R.string.notification_icons_no_imports));
        else for (String pkg : uploads) addPackage(pkg, true);
    }

    private String modeLabel(String value) {
        if ("0".equals(value)) return activity.getString(R.string.notification_icon_off);
        if ("2".equals(value)) return activity.getString(R.string.notification_icon_originals);
        if ("3".equals(value)) return activity.getString(R.string.notification_icon_all);
        return activity.getString(R.string.notification_icon_system);
    }

    private void modes() {
        Sheet choice = new Sheet(activity, t, activity.getString(R.string.notification_icon_scope));
        choice.choices(new String[][] {
                {"1", activity.getString(R.string.notification_icon_system), activity.getString(R.string.notification_icon_system_desc)},
                {"2", activity.getString(R.string.notification_icon_originals), activity.getString(R.string.notification_icon_originals_desc)},
                {"3", activity.getString(R.string.notification_icon_all), activity.getString(R.string.notification_icon_all_desc)},
                {"0", activity.getString(R.string.notification_icon_off), activity.getString(R.string.notification_icon_off_desc)}
        }, host.model().state().shown("iconmode"), value -> {
            AppModel.Result result = host.model().save("iconmode", value);
            if (!result.ok) { host.feedback(result.message); return; }
            dismiss();
            new NotificationIconsSheet(host).show();
        });
        choice.show();
    }

    private void manualImport() {
        Sheet entry = new Sheet(activity, t, activity.getString(R.string.notification_icon_manual));
        entry.subtitle(activity.getString(R.string.notification_icon_manual_desc));
        EditText field = new EditText(activity);
        field.setTextColor(t.ink);
        field.setHintTextColor(t.inkMuted);
        field.setHint("com.example.app");
        field.setSingleLine(true);
        field.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        entry.body().addView(field, Ui.match());
        entry.action(activity.getString(R.string.notification_icon_upload), Ui.ButtonStyle.FILLED, () -> {
            String pkg = field.getText().toString().trim();
            if (!NotificationIconStore.validPackage(pkg)) {
                host.feedback(activity.getString(R.string.notification_icon_invalid_package));
                return;
            }
            entry.dismiss();
            dismiss();
            host.importNotificationIcon(pkg);
        });
        entry.show();
    }

    private void addPackage(String pkg, boolean imported) {
        LinearLayout card = Ui.card(activity, t);
        String label = label(pkg);
        LinearLayout row = Ui.listRow(activity, t, Icon.BELL, label, pkg, null);
        card.addView(row);
        if (imported) {
            LinearLayout previews = Ui.row(activity);
            for (int color : new int[] {0xff202a42, 0xffe7ecf5}) {
                ImageView picture = new ImageView(activity);
                picture.setBackground(Ui.rounded(color, t.dp(16)));
                picture.setPadding(t.dp(14), t.dp(14), t.dp(14), t.dp(14));
                try {
                    Drawable drawable = Drawable.createFromPath(NotificationIconStore.file(activity, pkg).getAbsolutePath());
                    picture.setImageDrawable(drawable);
                    picture.setColorFilter(color == 0xff202a42 ? Color.WHITE : Color.BLACK, PorterDuff.Mode.SRC_IN);
                } catch (RuntimeException ignored) { }
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(t.dp(76), t.dp(76));
                p.setMargins(t.dp(8), 0, t.dp(8), t.dp(8));
                previews.addView(picture, p);
            }
            card.addView(previews);
        }
        LinearLayout actions = Ui.row(activity);
        String first = activity.getString(imported ? R.string.notification_icon_replace : R.string.notification_icon_upload);
        TextView upload = Ui.button(activity, t, first, null, Ui.ButtonStyle.OUTLINED, false);
        upload.setOnClickListener(v -> { dismiss(); host.importNotificationIcon(pkg); });
        actions.addView(upload, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView second = Ui.button(activity, t,
                activity.getString(imported ? R.string.notification_icon_remove : R.string.notification_icon_request),
                null, Ui.ButtonStyle.OUTLINED, false);
        second.setOnClickListener(v -> {
            if (imported) {
                if (NotificationIconStore.remove(activity, pkg)) {
                    host.model().repo.iconAssetsChanged();
                    host.feedback(activity.getString(R.string.notification_icon_removed));
                    dismiss();
                    new NotificationIconsSheet(host).show();
                }
                else host.feedback(activity.getString(R.string.notification_icon_error));
            } else request(pkg);
        });
        LinearLayout.LayoutParams secondParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        secondParams.setMarginStart(t.dp(8));
        actions.addView(second, secondParams);
        card.addView(actions);
        body().addView(card, Ui.match(0, t.dp(8)));
    }

    private String label(String pkg) {
        try {
            PackageManager pm = activity.getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            CharSequence text = pm.getApplicationLabel(info);
            if (text != null && text.length() != 0) return text.toString();
        } catch (PackageManager.NameNotFoundException ignored) { }
        return pkg;
    }

    private void request(String pkg) {
        String title = "Notification monochrome icon: " + pkg;
        String body = "Package: `" + pkg + "`\n\n"
                + "The OneUILiquidGlass notification icon resolver found no valid monochrome source. "
                + "Please consider including a transparent silhouette in the official icon library. "
                + "Attach a screenshot or PNG manually if appropriate. No notification contents are included.";
        Uri url = Uri.parse("https://github.com/igorcv88/OneUILiquidGlass/issues/new").buildUpon()
                .appendQueryParameter("title", title).appendQueryParameter("body", body).build();
        activity.startActivity(new Intent(Intent.ACTION_VIEW, url));
    }
}
