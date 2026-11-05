package io.github.igorcv88.oneuiliquidglass.ui;

import android.text.InputType;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.widget.*;

import io.github.igorcv88.oneuiliquidglass.config.*;
import io.github.igorcv88.oneuiliquidglass.R;
import io.github.igorcv88.oneuiliquidglass.ui.kit.*;
import io.github.igorcv88.oneuiliquidglass.ui.preview.NotificationPreview;

import java.util.function.Function;

/**
 * Editor for one control. Shows what changes (less / now / more samples focused on the part it
 * affects, or an honest "no visual sample"), the value with a numeric field, the default, why it
 * is inactive, and the technical name only inside activity.getString(R.string.control_technical).
 */
final class ControlSheet extends Sheet {
    private final Host host;
    private final Controls.Control c;
    private final Controls.Surface surface;
    private final AppModel model;
    private String working;
    private TextView valueText;
    private Slider slider;
    private EditText field;
    private ColorPicker colorPicker;
    private NotificationPreview now;
    private Scale scale;

    ControlSheet(Host host, String key, Controls.Surface surface) {
        super(host.activity(), host.theme(), ControlTitles.title(host.activity(), Controls.get(key)));
        this.host = host;
        this.c = Controls.get(key);
        this.surface = surface == null || !c.on(surface) ? c.surfaces.iterator().next() : surface;
        this.model = host.model();
        working = model.state().shown(key);
        build();
    }

    private Theme th() {
        return t;
    }

    private void build() {
        ControlState st = model.state();
        Applicability.Result a = st.applicability().on(c.key, surface);
        LinearLayout body = body();
        String hint = ControlDescriptions.hint(activity, c);
        if (!hint.isEmpty()) subtitle(hint);
        if (a.kind == Applicability.Kind.INACTIVE || a.kind == Applicability.Kind.OFF) {
            LinearLayout n = Ui.notice(activity, t, Ui.Tone.INFO,
                    ControlReasons.text(activity, a.reason) + ". " + activity.getString(R.string.control_inactive_suffix), null, null);
            body.addView(n, Ui.match(0, t.dp(12)));
        } else if (a.kind == Applicability.Kind.NEEDS_RESTART) {
            body.addView(Ui.notice(activity, t, Ui.Tone.WARN,
                    activity.getString(R.string.control_restart_needed),
                    activity.getString(R.string.home_apply), () -> {
                        dismiss();
                        host.openLens();
                    }), Ui.match(0, t.dp(12)));
        }
        samples(body);
        editor(body);
        TextView help = Ui.text(activity, t, ControlDescriptions.help(activity, c), Theme.LABEL, t.inkMuted, false);
        help.setPadding(t.dp(8), t.dp(14), t.dp(8), t.dp(4));
        body.addView(help);
        values(body, st);
        technical(body);
        action(model.ui.getBoolean("favorite." + c.key, false) ? activity.getString(R.string.control_favorite_remove) : activity.getString(R.string.control_favorite_add),
                Ui.ButtonStyle.OUTLINED, () -> {
                    boolean fav = !model.ui.getBoolean("favorite." + c.key, false);
                    model.ui.edit().putBoolean("favorite." + c.key, fav).apply();
                    host.feedback(fav ? activity.getString(R.string.control_favorite_added) : activity.getString(R.string.control_favorite_removed));
                    model.notifyChanged();
                    dismiss();
                });
        if (c.format != Controls.Format.CHOICE && c.format != Controls.Format.TOGGLE)
            action(activity.getString(R.string.action_save), Ui.ButtonStyle.FILLED, this::saveAndClose);
        onClosed(() -> {
            model.draft(c.key, null);
            host.endPreview();
            model.notifyChanged();
        });
    }

    /** Less / now / more, magnified on the part the control changes. */
    private void samples(LinearLayout body) {
        boolean visual = c.channel != Controls.Channel.NONE
                && (c.preview == Controls.Preview.FAITHFUL || c.preview == Controls.Preview.DIDACTIC);
        if (!visual) {
            LinearLayout n = Ui.notice(activity, t, Ui.Tone.INFO,
                    c.preview == Controls.Preview.BEHAVIOR
                            ? activity.getString(R.string.control_no_preview_behavior)
                            : activity.getString(R.string.control_no_preview),
                    c.preview == Controls.Preview.BEHAVIOR ? activity.getString(R.string.action_test) : null, host::testNotification);
            body.addView(n, Ui.match(0, t.dp(8)));
            return;
        }
        SettingSpec s = c.spec();
        String lo, hi;
        if (s.type == SettingSpec.Type.BOOLEAN) { lo = "0"; hi = "1"; }
        else if (c.format == Controls.Format.CHOICE || s.type == SettingSpec.Type.RGB) { lo = null; hi = null; }
        else {
            Scale sc = Scale.of(c);
            lo = sc.value(sc.auto ? 1 : 0);
            hi = sc.value(sc.steps);
        }
        LinearLayout row = Ui.row(activity);
        Function<String, String> base = model.state()::shown;
        boolean light = host.model().ui.getBoolean("preview.light", !th().dark);
        NotificationPreview.Backdrop b = NotificationPreview.Backdrop.values()[model.ui.getInt("preview.backdrop", 2)];
        if (lo != null) row.addView(tile(override(base, lo), s.type == SettingSpec.Type.BOOLEAN ? activity.getString(R.string.switch_off) : activity.getString(R.string.control_less), light, b), weight());
        now = tile(k -> k.equals(c.key) ? working : base.apply(k), activity.getString(R.string.control_current), light, b);
        row.addView(now, weight());
        if (hi != null) row.addView(tile(override(base, hi), s.type == SettingSpec.Type.BOOLEAN ? activity.getString(R.string.switch_on) : activity.getString(R.string.control_more), light, b), weight());
        body.addView(row, Ui.match(0, t.dp(4)));
        TextView note = Ui.text(activity, t, c.preview == Controls.Preview.DIDACTIC
                ? activity.getString(R.string.control_preview_didactic)
                : activity.getString(R.string.control_preview_approx), Theme.SMALL, t.inkFaint, false);
        note.setPadding(t.dp(8), t.dp(6), t.dp(8), 0);
        body.addView(note);
    }

    private Function<String, String> override(Function<String, String> base, String v) {
        return k -> k.equals(c.key) ? v : base.apply(k);
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, t.dp(112), 1);
        p.setMarginEnd(t.dp(6));
        return p;
    }

    private NotificationPreview tile(Function<String, String> values, String label, boolean light, NotificationPreview.Backdrop b) {
        NotificationPreview p = new NotificationPreview(activity);
        p.surface(surface);
        p.lightTheme(light);
        p.backdrop(b);
        p.values(values, null);
        p.zoomOnly(c.channel, label);
        p.setClipToOutline(true);
        p.setBackground(Ui.rounded(t.surfaceLow, t.dpf(16)));
        p.setContentDescription(activity.getString(R.string.control_preview_a11y, label, ControlTitles.title(activity, c)));
        return p;
    }

    private void editor(LinearLayout body) {
        SettingSpec s = c.spec();
        LinearLayout box = Ui.card(activity, t);
        box.setBackground(Ui.rounded(t.surface, t.dpf(20)));
        box.setPadding(t.dp(16), t.dp(12), t.dp(8), t.dp(8));
        body.addView(box, Ui.match(t.dp(12), 0));
        switch (c.format) {
            case TOGGLE: {
                Toggle tg = new Toggle(activity, t);
                tg.set("1".equals(working), false);
                LinearLayout r = Ui.switchRow(activity, t, null, ControlTitles.title(activity, c), null, tg);
                r.setPadding(0, 0, t.dp(8), 0);
                tg.listener((x, on) -> {
                    working = on ? "1" : "0";
                    commit(working);
                    refreshNow();
                });
                box.addView(r);
                return;
            }
            case CHOICE: {
                String[][] options = new String[c.choices.size()][];
                int i = 0;
                for (java.util.Map.Entry<String, String[]> e : c.choices.entrySet())
                    options[i++] = new String[] {e.getKey(), ControlOptions.text(activity, e.getValue()[0]), ControlOptions.text(activity, e.getValue()[1])};
                box.setPadding(t.dp(4), t.dp(4), t.dp(4), t.dp(4));
                LinearLayout holder = Ui.column(activity);
                box.addView(holder);
                choiceList(holder, options);
                return;
            }
            case COLOR: {
                colorPicker = new ColorPicker(activity, t);
                colorPicker.setColor(working);
                box.addView(colorPicker, Ui.match());
                field = field(activity.getString(R.string.control_hex_hint), working, InputType.TYPE_CLASS_TEXT);
                box.addView(field);
                colorPicker.listener(hex -> {
                    working = hex;
                    if (!hex.equals(field.getText().toString())) field.setText(hex);
                    host.preview(c.key, hex, c.channel);
                    refreshNow();
                });
                return;
            }
            default:
                break;
        }
        scale = Scale.of(c);
        LinearLayout top = Ui.row(activity);
        valueText = Ui.text(activity, t, ControlValues.display(host.activity(), c, working), Theme.TITLE, t.ink, true);
        top.addView(valueText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView def = Ui.button(activity, t, activity.getString(R.string.control_default, ControlValues.display(host.activity(), c, s.defaultValue)), Icon.RESET, Ui.ButtonStyle.TEXT, true);
        def.setOnClickListener(v -> setWorking(s.defaultValue, true));
        top.addView(def);
        box.addView(top);
        slider = new Slider(activity, t);
        slider.configure(scale.steps, scale.index(s.defaultValue), scale.auto);
        slider.set(scale.index(working));
        slider.describe(ControlTitles.title(activity, c), i -> ControlValues.display(host.activity(), c, scale.value(i)));
        slider.listener(new Slider.Listener() {
            @Override public void start() {}
            @Override public void change(int index, boolean fromUser) { setWorking(scale.value(index), false); }
            @Override public void stop(int index) {}
        });
        box.addView(slider, Ui.match());
        String unit = ValueFormat.editUnit(c);
        TextView exact = Ui.text(activity, t, activity.getString(R.string.control_exact_value) + (unit.isEmpty() ? "" : " (" + unit + ")"), Theme.CAPTION, t.inkMuted, false);
        exact.setPadding(t.dp(4), t.dp(8), 0, 0);
        box.addView(exact);
        field = field(activity.getString(R.string.control_value) + (unit.isEmpty() ? "" : " (" + unit + ")") + (scale.auto ? activity.getString(R.string.control_auto_suffix) : ""),
                ValueFormat.isAuto(c, working) ? "-1" : ValueFormat.toEdit(c, working, host.activity().getResources().getConfiguration().getLocales().get(0)),
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        field.setOnEditorActionListener((v, id, e) -> {
            if (id == EditorInfo.IME_ACTION_DONE) {
                fromField();
                return false;
            }
            return false;
        });
        box.addView(field);
    }

    private void choiceList(LinearLayout holder, String[][] options) {
        for (String[] o : options) {
            boolean on = o[0].equals(working);
            LinearLayout r = Ui.row(activity);
            r.setMinimumHeight(t.dp(56));
            r.setPadding(t.dp(14), t.dp(8), t.dp(14), t.dp(8));
            r.setBackground(Ui.ripple(t, Ui.rounded(on ? t.accentSoft : 0, t.dpf(16)), t.dpf(16)));
            LinearLayout texts = Ui.column(activity);
            texts.addView(Ui.text(activity, t, o[1], Theme.BODY, t.ink, on));
            if (!o[2].isEmpty()) texts.addView(Ui.text(activity, t, o[2], Theme.CAPTION, t.inkMuted, false));
            r.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            Icon.View check = new Icon.View(activity, Icon.CHECK, t.accent);
            check.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
            r.addView(check, new LinearLayout.LayoutParams(t.dp(22), t.dp(22)));
            r.setOnClickListener(v -> {
                working = o[0];
                commit(working);
                holder.removeAllViews();
                choiceList(holder, options);
                refreshNow();
            });
            r.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override
                public void onInitializeAccessibilityNodeInfo(View h, android.view.accessibility.AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(h, info);
                    info.setClassName(RadioButton.class.getName());
                    info.setCheckable(true);
                    info.setChecked(on);
                }
            });
            holder.addView(r, Ui.match(0, t.dp(2)));
        }
    }

    private EditText field(String hint, String text, int type) {
        EditText e = new EditText(activity);
        e.setHint(hint);
        e.setText(text);
        e.setInputType(type);
        e.setSingleLine(true);
        e.setImeOptions(EditorInfo.IME_ACTION_DONE);
        e.setTextColor(t.ink);
        e.setHintTextColor(t.inkFaint);
        e.setTextSize(Theme.BODY);
        e.setBackground(Ui.rounded(t.surfaceLow, t.dpf(14)));
        e.setPadding(t.dp(14), t.dp(12), t.dp(14), t.dp(12));
        e.setMinHeight(t.dp(48));
        LinearLayout.LayoutParams p = Ui.match(t.dp(8), t.dp(4));
        p.rightMargin = t.dp(8);
        e.setLayoutParams(p);
        e.setContentDescription(hint);
        return e;
    }

    private void setWorking(String raw, boolean syncSlider) {
        working = raw;
        if (valueText != null) valueText.setText(ControlValues.display(host.activity(), c, raw));
        if (slider != null && syncSlider) slider.set(scale.index(raw));
        if (colorPicker != null) colorPicker.setColor(raw);
        if (field != null) {
            String edit = scale == null ? raw : ValueFormat.isAuto(c, raw) ? "-1" : ValueFormat.toEdit(c, raw, host.activity().getResources().getConfiguration().getLocales().get(0));
            if (!edit.equals(field.getText().toString())) field.setText(edit);
            field.setError(null);
        }
        host.preview(c.key, raw, c.channel);
        refreshNow();
    }

    private void refreshNow() {
        if (now != null) {
            Function<String, String> base = model.state()::shown;
            String w = working;
            now.values(k -> k.equals(c.key) ? w : base.apply(k), null);
        }
    }

    private boolean fromField() {
        if (field == null) return true;
        String text = field.getText().toString().trim();
        try {
            // Preserve a legacy opacity below 50% unless the user changes it.
            if (c.key.equals("mediaart") && text.equals(ValueFormat.toEdit(c, working, host.activity().getResources().getConfiguration().getLocales().get(0)))) return true;
            String raw;
            if (c.format == Controls.Format.COLOR) raw = c.spec().validate(text);
            else if (scale.auto && text.equals("-1")) raw = c.spec().validate("-1");
            else raw = ValueFormat.fromEdit(c, text);
            setWorking(raw, true);
            return true;
        } catch (IllegalArgumentException e) {
            String message = e.getMessage();
            if (c.format == Controls.Format.COLOR) message = activity.getString(R.string.error_color);
            else if ("Digite um número".equals(message)) message = activity.getString(R.string.error_number);
            else if (message != null && message.startsWith("Use 0%")) message = activity.getString(R.string.error_media_range);
            else if (message != null && message.startsWith("Use de "))
                message = activity.getString(R.string.error_value_range,
                        ControlValues.display(activity, c, ValueFormat.plain(c.spec().min)),
                        ControlValues.display(activity, c, ValueFormat.plain(c.spec().max)));
            else message = activity.getString(R.string.error_invalid_value);
            field.setError(message);
            return false;
        }
    }

    private void saveAndClose() {
        if (!fromField()) return;
        if (commit(working)) dismiss();
    }

    private boolean commit(String raw) {
        AppModel.Result r = model.save(c.key, raw);
        if (r.ok) {
            host.feedback(c.needsRestart() ? activity.getString(R.string.control_saved_restart) : activity.getString(R.string.control_saved));
            return true;
        }
        if (r.conflict) {
            dismiss();
            host.openConflicts();
        } else if (field != null) field.setError(r.message);
        else host.feedback(r.message);
        return false;
    }

    /** Saved, on the phone now, and default; only rows that differ are worth reading. */
    private void values(LinearLayout body, ControlState st) {
        String saved = st.saved.get(c.key), device = st.device(c.key);
        StringBuilder b = new StringBuilder();
        if (saved != null) b.append(activity.getString(R.string.control_saved_in_app, ControlValues.display(host.activity(), c, saved)));
        if (model.observedAvailable()) {
            String o = st.observed.get(c.key);
            if (o != null && !o.isEmpty()) {
                b.append(b.length() == 0 ? "" : "\n").append(activity.getString(R.string.control_system_property, ControlValues.display(host.activity(), c, o)));
                if (saved != null && c.spec().applyMode == SettingSpec.ApplyMode.LIVE
                        && !c.spec().helperOwned() && !saved.equals(o))
                    b.append("\n").append(activity.getString(R.string.control_app_priority));
            }
        }
        if (c.needsRestart())
            b.append(b.length() == 0 ? "" : "\n").append(activity.getString(R.string.control_lens_value, ControlValues.display(host.activity(), c, device)));
        if (b.length() == 0) return;
        TextView v = Ui.text(activity, t, b, Theme.CAPTION, t.inkMuted, false);
        v.setPadding(t.dp(8), t.dp(10), t.dp(8), t.dp(4));
        body.addView(v);
    }

    private void technical(LinearLayout body) {
        SettingSpec s = c.spec();
        View[] d = Ui.disclosure(activity, t, null, activity.getString(R.string.control_technical), null, false, null);
        LinearLayout card = Ui.card(activity, t);
        card.addView(d[0]);
        TextView text = Ui.text(activity, t,
                activity.getString(R.string.control_property, s.fullKey())
                        + "\n" + activity.getString(R.string.control_type, s.type.name().toLowerCase(java.util.Locale.ROOT))
                        + (s.type == SettingSpec.Type.FLOAT || s.type == SettingSpec.Type.INT
                                ? " · " + activity.getString(R.string.control_range, ValueFormat.plain(s.min), ValueFormat.plain(s.max)) : "")
                        + "\n" + activity.getString(R.string.control_reading, activity.getString(c.needsRestart() ? R.string.control_compositor : R.string.control_systemui))
                        + "\n" + activity.getString(R.string.control_applies, surfaces()), Theme.CAPTION, t.inkMuted, false);
        text.setTextIsSelectable(true);
        text.setPadding(t.dp(18), 0, t.dp(18), t.dp(14));
        ((LinearLayout) d[1]).addView(text);
        card.addView(d[1]);
        body.addView(card, Ui.match(t.dp(12), 0));
    }

    private String surfaces() {
        StringBuilder b = new StringBuilder();
        for (Controls.Surface s : c.surfaces) b.append(b.length() == 0 ? "" : ", ").append(HomePage.surfaceLabel(activity, s));
        return b.toString();
    }
}
