package io.github.igorcv88.oneuiliquidglass.ui;

import android.content.Context;
import android.view.*;
import android.widget.*;

import io.github.igorcv88.oneuiliquidglass.config.*;
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
    private boolean usable = true, dragging;

    public ControlRow(Context c, Host host, Controls.Control control, Controls.Surface surface) {
        super(c);
        this.host = host;
        this.t = host.theme();
        this.control = control;
        this.surface = surface;
        setOrientation(VERTICAL);
        setPadding(t.dp(20), t.dp(12), t.dp(8), t.dp(control.format == Controls.Format.TOGGLE
                || control.format == Controls.Format.CHOICE || control.format == Controls.Format.COLOR ? 12 : 2));
        LinearLayout line = Ui.row(c);
        LinearLayout texts = Ui.column(c);
        title = Ui.text(c, t, control.title, Theme.BODY, t.ink, false);
        texts.addView(title);
        status = Ui.text(c, t, "", Theme.CAPTION, t.inkMuted, false);
        status.setVisibility(GONE);
        texts.addView(status);
        line.addView(texts, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        value = Ui.text(c, t, "", Theme.LABEL, t.inkMuted, true);
        value.setPadding(t.dp(8), 0, t.dp(4), 0);
        reset = Ui.iconButton(c, t, Icon.RESET, t.inkFaint, "Restaurar padrão de " + control.title);
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
                slider.describe(control.title, i -> ValueFormat.display(control, scale.value(i)));
                slider.listener(new Slider.Listener() {
                    @Override
                    public void start() {
                        dragging = true;
                    }

                    @Override
                    public void change(int index, boolean fromUser) {
                        String raw = scale.value(index);
                        value.setText(ValueFormat.display(control, raw));
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
                line.setMinimumHeight(t.dp(40));
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

    private void open() {
        host.openControl(control.key, surface);
    }

    private void commit(String raw) {
        AppModel.Result r = host.model().save(control.key, raw);
        if (!r.ok) {
            host.feedback(r.conflict ? "Este valor foi alterado fora do app" : r.message);
            if (r.conflict) host.openConflicts();
        } else if (control.needsRestart()) {
            host.feedback("Salvo · aplicado ao reiniciar o userspace");
        }
    }

    private void resetToDefault() {
        String def = control.spec().defaultValue;
        commit(def);
        performHapticFeedback(HapticFeedbackConstants.CONFIRM);
        announceForAccessibility(control.title + " restaurado");
    }

    /** What an automatic value resolves to now, as the readers resolve it. */
    private String followed(ControlState st, Mode mode) {
        switch (control.key) {
            case "kgblurradius":
                return mode == Mode.STANDARD ? ValueFormat.display(Controls.get("liveradius"), st.shown("liveradius"))
                        : followed("semradiuslens", st);
            case "semradiuslens":
                return followed("semradius", st);
            case "semradius":
                return "180 px";
            case "kglens":
                return ValueFormat.display(Controls.get("sflens"), st.shown("sflens"));
            default:
                return null;
        }
    }

    private static String followed(String key, ControlState st) {
        String v = st.shown(key);
        Controls.Control c = Controls.get(key);
        if (!ValueFormat.isAuto(c, v)) return ValueFormat.display(c, v);
        return key.equals("semradiuslens") ? followed("semradius", st) : "180 px";
    }

    /** Incremental update from the shared state; never rebuilds the row. */
    public void bind(ControlState st, Applicability app) {
        if (dragging) return;
        String raw = st.shown(control.key);
        Applicability.Result a = app.on(control.key, surface);
        usable = a.usable();
        boolean changed = st.changed(control.key);
        String shown = ValueFormat.display(control, raw);
        String follows = ValueFormat.isAuto(control, raw) ? followed(st, app.mode) : null;
        if (follows != null) shown = shown + " · " + follows;
        value.setText(shown);
        value.setTextColor(changed && usable ? t.accent : t.inkMuted);
        if (toggle != null) {
            toggle.set("1".equals(raw), isAttachedToWindow());
            toggle.setEnabled(usable);
        }
        if (slider != null) {
            slider.set(scale.index(raw));
            slider.setEnabled(usable);
            reset.setVisibility(changed && usable ? VISIBLE : INVISIBLE);
        }
        title.setTextColor(usable ? t.ink : t.inkFaint);
        String line = null;
        int color = t.inkMuted;
        if (a.kind == Applicability.Kind.INACTIVE) line = a.reason;
        else if (a.kind == Applicability.Kind.NEEDS_RESTART && st.pendingRestart(control.key)) {
            line = "Pendente · aplicar ao reiniciar o userspace";
            color = t.warn;
        } else if (host.model().observedAvailable() && host.model().repo.conflict(control.spec())) {
            line = "Alterado fora do app";
            color = t.warn;
        }
        status.setText(line == null ? "" : line);
        status.setTextColor(color);
        status.setVisibility(line == null ? GONE : VISIBLE);
        setStateDescription(shown + (line == null ? "" : ". " + line));
        if (slider != null)
            slider.setStateDescription(shown);
    }
}
