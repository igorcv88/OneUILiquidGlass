package io.github.igorcv88.oneuiliquidglass.ui;

import android.content.Context;
import android.view.*;
import android.widget.*;

import io.github.igorcv88.oneuiliquidglass.config.*;
import io.github.igorcv88.oneuiliquidglass.config.state.EditGuard;
import io.github.igorcv88.oneuiliquidglass.config.state.EffectiveState;
import io.github.igorcv88.oneuiliquidglass.R;
import io.github.igorcv88.oneuiliquidglass.ui.kit.*;

/**
 * One control: name, current value, a reset that appears only when the value differs from the
 * default, and the control itself. A status line appears only when it says something the
 * person can act on (inactive and why, waiting for a restart, changed outside the app). An
 * inactive control is drawn and enforced as disabled; its saved value is kept.
 */
@android.annotation.SuppressLint("ViewConstructor") // Built in code only, never inflated.
public final class ControlRow extends LinearLayout {
    private final Host host;
    private final Theme t;
    public final Controls.Control control;
    private final Controls.Surface surface;
    private final TextView title, value, status;
    private final FrameLayout reset;
    private Slider slider;
    private Toggle toggle;
    private Icon.View chevron;
    private Scale scale;
    private LinearLayout titleLine;
    private boolean usable = true, dragging;

    public ControlRow(Context c, Host host, Controls.Control control, Controls.Surface surface) {
        this(c, host, control, surface, false);
    }

    /** @param primary a main row: taller, with the title one step larger */
    public ControlRow(Context c, Host host, Controls.Control control, Controls.Surface surface, boolean primary) {
        super(c);
        this.host = host;
        this.t = host.theme();
        this.control = control;
        this.surface = surface;
        setOrientation(VERTICAL);
        setPadding(t.dp(20), t.dp(12), t.dp(8), t.dp(control.format == Controls.Format.TOGGLE
                || control.format == Controls.Format.CHOICE || control.format == Controls.Format.COLOR ? 12 : 2));
        LinearLayout line = Ui.row(c);
        titleLine = line;
        LinearLayout texts = Ui.column(c);
        String productTitle = ProductTitles.title(host.activity(), control);
        title = Ui.text(c, t, productTitle, primary ? Theme.HEADLINE : Theme.BODY, t.ink, primary);
        texts.addView(title);
        status = Ui.text(c, t, "", Theme.CAPTION, t.inkMuted, false);
        status.setVisibility(GONE);
        texts.addView(status);
        line.addView(texts, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        value = Ui.text(c, t, "", Theme.LABEL, t.inkMuted, true);
        value.setPadding(t.dp(8), 0, t.dp(4), 0);
        reset = Ui.iconButton(c, t, Icon.RESET, t.inkFaint, host.activity().getString(R.string.control_reset_a11y, productTitle));
        reset.setOnClickListener(v -> resetToDefault());
        reset.setVisibility(INVISIBLE);
        SettingSpec spec = control.spec();
        switch (control.format) {
            case TOGGLE:
                toggle = new Toggle(c, t);
                line.addView(toggle);
                toggle.listener((tg, on) -> commit(on ? "1" : "0"));
                setOnClickListener(v -> {
                    if (usable) toggle.toggleByUser();
                    else open();
                });
                setOnLongClickListener(v -> {
                    open();
                    return true;
                });
                break;
            case CHOICE:
            case COLOR:
                line.addView(value);
                chevron = new Icon.View(c, Icon.CHEVRON_RIGHT, t.inkFaint);
                line.addView(chevron, new LayoutParams(t.dp(20), t.dp(20)));
                View pad = new View(c);
                line.addView(pad, new LayoutParams(t.dp(12), 1));
                setOnClickListener(v -> open());
                break;
            default:
                line.addView(value);
                line.addView(reset);
                scale = Scale.of(control);
                slider = new Slider(c, t);
                slider.configure(scale.steps, scale.index(spec.defaultValue), scale.auto);
                slider.describe(productTitle, i -> ControlValues.display(host.activity(), control, scale.value(i)));
                slider.listener(new Slider.Listener() {
                    @Override
                    public void start() {
                        dragging = true;
                    }

                    @Override
                    public void change(int index, boolean fromUser) {
                        String raw = scale.value(index);
                        value.setText(ControlValues.display(host.activity(), control, raw));
                        host.preview(control.key, raw, control.channel);
                    }

                    @Override
                    public void stop(int index) {
                        dragging = false;
                        commit(scale.value(index));
                        host.endPreview();
                    }
                });
                // Title and value open the editor; the slider is the direct control.
                line.setOnClickListener(v -> open());
                line.setBackground(Ui.ripple(t, null, t.dpf(12)));
                line.setMinimumHeight(t.dp(primary ? Theme.PRIMARY_ROW_MIN - 32 : 40));
                break;
        }
        addView(line, Ui.match());
        if (slider != null) {
            LayoutParams sp = Ui.match();
            sp.rightMargin = t.dp(12);
            addView(slider, sp);
        }
        setBackground(Ui.ripple(t, null, 0));
        if (slider != null) setBackground(null);
        if (toggle != null) {
            toggle.setClickable(false);
            toggle.setFocusable(false);
            toggle.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override
                public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setClassName(Switch.class.getName());
                    info.setCheckable(true);
                    info.setChecked(toggle.checked());
                    info.setEnabled(usable);
                }
            });
        }
    }

    /** Brightening this far can wash out text the module still colours for the original backdrop. */
    private boolean caution(String raw) {
        if (!control.key.equals("sf.hclift") && !control.key.equals("sf.kclift")) return false;
        try {
            return Double.parseDouble(raw) >= LIFT_CAUTION;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static final double LIFT_CAUTION = 1;

    private void open() {
        host.openControl(control.key, surface);
    }

    private void commit(String raw) {
        commit(raw, EditGuard.Action.EDIT);
    }

    private void commit(String raw, EditGuard.Action action) {
        AppModel.Result r = host.model().saveControl(control.key, raw, surface, action);
        if (!r.ok) {
            host.feedback(r.conflict ? host.activity().getString(R.string.error_conflict) : r.message);
            if (r.conflict) host.openConflicts();
        } else if (control.needsRestart()) {
            host.feedback(host.activity().getString(R.string.control_saved_restart));
        }
    }

    private void resetToDefault() {
        String def = control.spec().defaultValue;
        commit(def, EditGuard.Action.RESET_ONE);
        performHapticFeedback(HapticFeedbackConstants.CONFIRM);
        announceForAccessibility(host.activity().getString(R.string.control_reset_done, ProductTitles.title(host.activity(), control)));
    }

    /** What an automatic value resolves to now, as the readers resolve it. */
    private String followed(ControlState st, Mode mode) {
        switch (control.key) {
            case "kgblurradius":
                return mode == Mode.STANDARD ? ControlValues.display(host.activity(), Controls.get("liveradius"), st.shown("liveradius"))
                        : followed("semradiuslens", st);
            case "semradiuslens":
                return followed("semradius", st);
            case "semradius":
                return "180 px";
            case "kglens":
                return ControlValues.display(host.activity(), Controls.get("sflens"), st.shown("sflens"));
            default:
                return null;
        }
    }

    private String followed(String key, ControlState st) {
        String v = st.shown(key);
        Controls.Control c = Controls.get(key);
        if (!ValueFormat.isAuto(c, v)) return ControlValues.display(host.activity(), c, v);
        return key.equals("semradiuslens") ? followed("semradius", st) : "180 px";
    }

    /** Incremental update from the shared state and the effective state; never rebuilds the row. */
    public void bind(ControlState st, Applicability app, EffectiveState.Effective effective) {
        if (dragging) return;
        String raw = st.shown(control.key);
        usable = RowText.enabled(effective) && app.on(control.key, surface).usable();
        boolean changed = st.changed(control.key);
        String shown = ControlValues.display(host.activity(), control, raw);
        String follows = ValueFormat.isAuto(control, raw) ? followed(st, app.mode) : null;
        if (follows != null) shown = shown + " · " + follows;
        RowText text = RowText.of(host.activity(), effective, title.getText().toString(), shown);
        value.setText(text.value);
        value.setTextColor(!usable ? t.inkFaint : changed ? t.accent : t.inkMuted);
        if (toggle != null) {
            toggle.set("1".equals(raw), isAttachedToWindow());
            toggle.setEnabled(usable);
        }
        if (slider != null) {
            slider.set(scale.index(raw));
            slider.setEnabled(usable);
            slider.setAlpha(usable ? 1f : DISABLED_ALPHA);
            reset.setVisibility(changed && usable ? VISIBLE : INVISIBLE);
        }
        title.setTextColor(usable ? t.ink : t.inkFaint);
        String line = text.line;
        RowText.Tone tone = text.tone;
        if (line == null && host.model().observedAvailable() && host.model().repo.conflict(control.spec())) {
            line = host.activity().getString(R.string.control_external_change);
            tone = RowText.Tone.PENDING;
        }
        if (usable && caution(raw)) {
            String warning = host.activity().getString(R.string.control_caution_contrast);
            line = line == null ? warning : line + " · " + warning;
            if (tone == RowText.Tone.NORMAL) tone = RowText.Tone.PENDING;
        }
        status.setText(line == null ? "" : statusLine(line, tone));
        status.setTextColor(t.inkMuted);
        status.setVisibility(line == null ? GONE : VISIBLE);
        String description = line == null || line.equals(text.line) ? text.description : text.description + ", " + line;
        (slider != null ? titleLine : this).setContentDescription(description);
        setStateDescription(line == null ? shown : shown + ". " + line);
        if (slider != null)
            slider.setStateDescription(shown);
    }

    private static final float DISABLED_ALPHA = .38f;

    /** A coloured dot before the text for a pending or external state; the words carry the meaning. */
    private CharSequence statusLine(String line, RowText.Tone tone) {
        if (tone == RowText.Tone.NORMAL) return line;
        android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder("● ").append(line);
        b.setSpan(new android.text.style.ForegroundColorSpan(tone == RowText.Tone.EXTERNAL ? t.lilac : t.warn),
                0, 1, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        return b;
    }
}
