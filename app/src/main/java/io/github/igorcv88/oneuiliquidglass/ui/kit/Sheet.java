package io.github.igorcv88.oneuiliquidglass.ui.kit;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.*;
import android.widget.*;

import java.util.function.Consumer;

/**
 * Bottom sheet: drag handle, title, scrollable body and an optional action row pinned at the
 * bottom. Slides in, follows the keyboard and the gesture bar, dismisses on scrim tap, back, or
 * a downward drag on the handle. Used for editors, choices, confirmations and secondary options.
 */
public class Sheet extends Dialog {
    protected final Activity activity;
    protected final Theme t;
    private final LinearLayout panel, body, actions;
    private final TextView title, subtitle;
    private final ScrollView scroll;
    private float dragStart;
    private Runnable onDismiss;

    public Sheet(Activity a, Theme t, String heading) {
        super(a);
        activity = a;
        this.t = t;
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        panel = Ui.column(a);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(t.surfaceHigh);
        float r = t.dpf(28);
        bg.setCornerRadii(new float[] {r, r, r, r, 0, 0, 0, 0});
        panel.setBackground(bg);
        panel.setClipToOutline(true);
        View handle = new View(a);
        handle.setBackground(Ui.rounded(t.dark ? 0x40ffffff : 0x33102030, t.dpf(2)));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(t.dp(36), t.dp(4));
        hp.gravity = Gravity.CENTER_HORIZONTAL;
        hp.topMargin = t.dp(10);
        hp.bottomMargin = t.dp(6);
        FrameLayout handleArea = new FrameLayout(a) {
            @Override
            public boolean performClick() {
                return super.performClick();
            }
        };
        handleArea.addView(handle, new FrameLayout.LayoutParams(t.dp(36), t.dp(4), Gravity.CENTER));
        handleArea.setMinimumHeight(t.dp(24));
        handleArea.setContentDescription("Fechar");
        handleArea.setOnClickListener(v -> dismiss());
        handleArea.setOnTouchListener(this::drag);
        panel.addView(handleArea, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, t.dp(24)));
        title = Ui.text(a, t, heading, Theme.TITLE, t.ink, true);
        title.setAccessibilityHeading(true);
        title.setPadding(t.dp(24), t.dp(6), t.dp(24), 0);
        panel.addView(title);
        subtitle = Ui.text(a, t, "", Theme.LABEL, t.inkMuted, false);
        subtitle.setPadding(t.dp(24), t.dp(4), t.dp(24), 0);
        subtitle.setVisibility(View.GONE);
        panel.addView(subtitle);
        scroll = Ui.scroller(a);
        scroll.setFillViewport(false);
        body = Ui.column(a);
        body.setPadding(t.dp(16), t.dp(12), t.dp(16), t.dp(12));
        scroll.addView(body);
        panel.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        actions = Ui.row(a);
        actions.setPadding(t.dp(16), t.dp(4), t.dp(16), t.dp(16));
        actions.setVisibility(View.GONE);
        panel.addView(actions);
        panel.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets nav = insets.getInsets(WindowInsets.Type.navigationBars() | WindowInsets.Type.ime());
            v.setPadding(0, 0, 0, nav.bottom);
            return WindowInsets.CONSUMED;
        });
        panel.setClickable(true);
        super.setOnDismissListener(d -> { if (onDismiss != null) onDismiss.run(); });
    }

    public Sheet subtitle(CharSequence s) {
        subtitle.setText(s);
        subtitle.setVisibility(s == null || s.length() == 0 ? View.GONE : View.VISIBLE);
        return this;
    }

    /** The sheet surface (for screenshots and tests). */
    public View panel() {
        return panel;
    }

    public LinearLayout body() {
        return body;
    }

    public TextView titleView() {
        return title;
    }

    public void onClosed(Runnable r) {
        onDismiss = r;
    }

    /** Adds a footer button; FILLED is the main action. Returns it for enable/label updates. */
    public TextView action(String label, Ui.ButtonStyle style, Runnable r) {
        TextView b = Ui.button(activity, t, label, null, style, false);
        b.setOnClickListener(v -> r.run());
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        if (actions.getChildCount() > 0) p.setMarginStart(t.dp(10));
        actions.addView(b, p);
        actions.setVisibility(View.VISIBLE);
        return b;
    }

    public Sheet text(CharSequence s) {
        TextView v = Ui.text(activity, t, s, Theme.BODY - 1, t.inkMuted, false);
        v.setPadding(t.dp(8), t.dp(2), t.dp(8), t.dp(10));
        body.addView(v);
        return this;
    }

    /** Single-choice list (radio rows with optional description). */
    public void choices(String[][] options, String selected, Consumer<String> pick) {
        LinearLayout group = Ui.column(activity);
        for (String[] o : options) {
            boolean on = o[0].equals(selected);
            LinearLayout r = Ui.row(activity);
            r.setMinimumHeight(t.dp(56));
            r.setPadding(t.dp(16), t.dp(10), t.dp(16), t.dp(10));
            r.setBackground(Ui.ripple(t, Ui.rounded(on ? t.accentSoft : Color.TRANSPARENT, t.dpf(18)), t.dpf(18)));
            LinearLayout texts = Ui.column(activity);
            TextView a = Ui.text(activity, t, o[1], Theme.BODY, on ? t.ink : t.ink, on);
            texts.addView(a);
            if (o.length > 2 && o[2] != null && !o[2].isEmpty())
                texts.addView(Ui.text(activity, t, o[2], Theme.CAPTION, t.inkMuted, false));
            r.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            Icon.View check = new Icon.View(activity, Icon.CHECK, t.accent);
            check.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
            r.addView(check, new LinearLayout.LayoutParams(t.dp(22), t.dp(22)));
            r.setOnClickListener(v -> {
                pick.accept(o[0]);
                dismiss();
            });
            r.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override
                public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setClassName(RadioButton.class.getName());
                    info.setCheckable(true);
                    info.setChecked(on);
                }
            });
            LinearLayout.LayoutParams lp = Ui.match(0, t.dp(4));
            group.addView(r, lp);
        }
        body.addView(group);
    }

    private boolean drag(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragStart = e.getRawY();
                return false;
            case MotionEvent.ACTION_MOVE:
                panel.setTranslationY(Math.max(0, e.getRawY() - dragStart));
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (panel.getTranslationY() > t.dp(96)) dismiss();
                else panel.animate().translationY(0).setDuration(t.ms(Theme.FAST)).start();
                if (panel.getTranslationY() > t.dp(4)) return true;
                if (e.getActionMasked() == MotionEvent.ACTION_UP) v.performClick();
                return true;
        }
        return false;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout root = new FrameLayout(activity);
        root.setOnClickListener(v -> dismiss());
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        int width = activity.getResources().getDisplayMetrics().widthPixels;
        if (width > t.dp(640)) {
            p.width = t.dp(600);
            p.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        }
        root.addView(panel, p);
        setContentView(root);
        Window w = getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setDimAmount(t.dark ? .55f : .35f);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            w.setDecorFitsSystemWindows(false);
            w.setNavigationBarColor(Color.TRANSPARENT);
            w.setStatusBarColor(Color.TRANSPARENT);
        }
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = insets.getInsets(WindowInsets.Type.statusBars()).top;
            v.setPadding(0, top + t.dp(24), 0, 0);
            panel.dispatchApplyWindowInsets(insets);
            return WindowInsets.CONSUMED;
        });
    }

    @Override
    public void show() {
        super.show();
        panel.setTranslationY(t.dp(320));
        panel.setAlpha(t.reduceMotion ? 1 : 0);
        panel.animate().translationY(0).alpha(1).setDuration(t.ms(Theme.EMPHASIZED))
                .setInterpolator(new android.view.animation.PathInterpolator(.2f, 0, 0, 1)).start();
    }
}
