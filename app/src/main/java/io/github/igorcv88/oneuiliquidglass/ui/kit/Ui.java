package io.github.igorcv88.oneuiliquidglass.ui.kit;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;

/** View factory for the design system. Every control here is styled explicitly. */
public final class Ui {
    private Ui() {}

    public static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static TextView text(Context c, Theme t, CharSequence s, float sp, int color, boolean medium) {
        TextView v = new TextView(c);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setTypeface(medium ? Theme.MEDIUM : Theme.REGULAR);
        v.setIncludeFontPadding(true);
        v.setLineSpacing(0, 1.12f);
        return v;
    }

    public static TextView title(Context c, Theme t, CharSequence s) {
        TextView v = text(c, t, s, Theme.DISPLAY, t.ink, true);
        v.setAccessibilityHeading(true);
        return v;
    }

    /** Small section label above a card. */
    public static TextView section(Context c, Theme t, CharSequence s) {
        TextView v = text(c, t, s, Theme.CAPTION, t.inkMuted, true);
        v.setPadding(t.dp(8), t.dp(22), t.dp(8), t.dp(8));
        v.setAccessibilityHeading(true);
        v.setLetterSpacing(.02f);
        return v;
    }

    public static GradientDrawable rounded(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    public static Drawable ripple(Theme t, Drawable content, float radius) {
        return new RippleDrawable(ColorStateList.valueOf(t.ripple), content,
                content != null ? null : rounded(Color.WHITE, radius));
    }

    /** A surface card holding rows; rows draw their own dividers. */
    public static LinearLayout card(Context c, Theme t) {
        LinearLayout l = column(c);
        GradientDrawable d = rounded(t.surface, t.dpf(Theme.CARD_RADIUS));
        if (!t.dark) d.setStroke(t.dp(1), t.stroke);
        l.setBackground(d);
        l.setClipToOutline(true);
        l.setPadding(0, t.dp(4), 0, t.dp(4));
        return l;
    }

    public static LinearLayout.LayoutParams match() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public static LinearLayout.LayoutParams match(int topMargin, int bottomMargin) {
        LinearLayout.LayoutParams p = match();
        p.topMargin = topMargin;
        p.bottomMargin = bottomMargin;
        return p;
    }

    public static View divider(Context c, Theme t, int inset) {
        View v = new View(c);
        v.setBackgroundColor(t.divider);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, t.dp(.7f)));
        p.leftMargin = inset;
        p.rightMargin = t.dp(20);
        v.setLayoutParams(p);
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return v;
    }

    public enum ButtonStyle { FILLED, TONAL, OUTLINED, TEXT, DANGER }

    /** Pill button, 52 dp (48 dp small) high, optional leading icon. Never an OEM Button. */
    public static TextView button(Context c, Theme t, CharSequence label, Icon icon, ButtonStyle style, boolean small) {
        int side = t.dp(small ? 16 : 22);
        TextView b = new TextView(c) {
            // Keeps a leading icon next to the centred label instead of at the far edge.
            @Override
            protected void onMeasure(int w, int h) {
                super.onMeasure(w, h);
                android.graphics.drawable.Drawable d = getCompoundDrawablesRelative()[0];
                if (d == null) return;
                float text = getPaint().measureText(getText().toString());
                int content = Math.round(text) + d.getBounds().width() + getCompoundDrawablePadding();
                int pad = Math.max(side, (getMeasuredWidth() - content) / 2);
                if (pad != getPaddingStart()) {
                    setPaddingRelative(pad, getPaddingTop(), pad, getPaddingBottom());
                    super.onMeasure(w, h);
                }
            }
        };
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(small ? Theme.LABEL : 15);
        b.setTypeface(Theme.MEDIUM);
        b.setSingleLine(false);
        b.setMaxLines(2);
        b.setEllipsize(TextUtils.TruncateAt.END);
        b.setMinHeight(t.dp(small ? Theme.BUTTON_SMALL : Theme.BUTTON));
        b.setMinimumHeight(t.dp(small ? Theme.BUTTON_SMALL : Theme.BUTTON));
        b.setPadding(side, t.dp(8), side, t.dp(8));
        b.setClickable(true);
        b.setFocusable(true);
        styleButton(b, t, style, icon);
        b.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Button.class.getName());
            }
        });
        return b;
    }

    public static void styleButton(TextView b, Theme t, ButtonStyle style, Icon icon) {
        int fg, bg;
        switch (style) {
            case FILLED: fg = t.onAccent; bg = t.accent; break;
            case TONAL: fg = t.onAccentContainer; bg = t.accentContainer; break;
            case DANGER: fg = t.onErrorContainer; bg = t.errorContainer; break;
            case OUTLINED: fg = t.ink; bg = Color.TRANSPARENT; break;
            default: fg = t.accent; bg = Color.TRANSPARENT;
        }
        float r = t.dpf(26);
        GradientDrawable shape = rounded(bg, r);
        if (style == ButtonStyle.OUTLINED) shape.setStroke(t.dp(1), t.dark ? 0x33ffffff : 0x2a102030);
        StateListDrawable states = new StateListDrawable();
        GradientDrawable disabled = rounded(style == ButtonStyle.TEXT || style == ButtonStyle.OUTLINED
                ? Color.TRANSPARENT : (t.dark ? 0x1fffffff : 0x14102030), r);
        if (style == ButtonStyle.OUTLINED) disabled.setStroke(t.dp(1), t.divider);
        states.addState(new int[] {-android.R.attr.state_enabled}, disabled);
        states.addState(new int[] {}, shape);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(style == ButtonStyle.FILLED ? 0x33ffffff : t.ripple), states, rounded(Color.WHITE, r)));
        b.setTextColor(new ColorStateList(new int[][] {{-android.R.attr.state_enabled}, {}},
                new int[] {t.inkDisabled, fg}));
        if (icon != null) {
            Drawable d = icon.drawable(fg, t.dpf(20));
            d.setBounds(0, 0, t.dp(20), t.dp(20));
            b.setCompoundDrawablesRelative(d, null, null, null);
            b.setCompoundDrawablePadding(t.dp(8));
        } else b.setCompoundDrawablesRelative(null, null, null, null);
    }

    /** 48 dp round icon button with a content description. */
    public static FrameLayout iconButton(Context c, Theme t, Icon icon, int color, String description) {
        FrameLayout f = new FrameLayout(c);
        Icon.View v = new Icon.View(c, icon, color);
        f.addView(v, new FrameLayout.LayoutParams(t.dp(22), t.dp(22), Gravity.CENTER));
        f.setBackground(new RippleDrawable(ColorStateList.valueOf(t.ripple), null, new ShapeDrawable(new android.graphics.drawable.shapes.OvalShape())));
        f.setContentDescription(description);
        f.setClickable(true);
        f.setFocusable(true);
        f.setMinimumWidth(t.dp(Theme.TOUCH));
        f.setMinimumHeight(t.dp(Theme.TOUCH));
        f.setLayoutParams(new LinearLayout.LayoutParams(t.dp(Theme.TOUCH), t.dp(Theme.TOUCH)));
        f.setTag(v);
        return f;
    }

    /** Leading icon in a soft tinted circle, used by navigation rows. */
    public static FrameLayout badge(Context c, Theme t, Icon icon, int tint, int background) {
        FrameLayout f = new FrameLayout(c);
        f.setBackground(rounded(background, t.dpf(14)));
        f.addView(new Icon.View(c, icon, tint), new FrameLayout.LayoutParams(t.dp(22), t.dp(22), Gravity.CENTER));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(t.dp(40), t.dp(40));
        p.setMarginEnd(t.dp(14));
        f.setLayoutParams(p);
        f.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        return f;
    }

    /**
     * A tappable list row: optional badge, title, optional subtitle, trailing view. Returns the
     * row; its tag holds {title, subtitle} TextViews for updates.
     */
    public static LinearLayout listRow(Context c, Theme t, Icon icon, CharSequence title, CharSequence subtitle, View trailing) {
        LinearLayout r = row(c);
        r.setMinimumHeight(t.dp(Theme.ROW_MIN + 8));
        r.setPadding(t.dp(18), t.dp(10), t.dp(14), t.dp(10));
        if (icon != null) r.addView(badge(c, t, icon, t.accent, t.accentSoft));
        LinearLayout texts = column(c);
        TextView a = text(c, t, title, Theme.BODY, t.ink, false);
        TextView b = text(c, t, subtitle == null ? "" : subtitle, Theme.CAPTION, t.inkMuted, false);
        b.setVisibility(subtitle == null || subtitle.length() == 0 ? View.GONE : View.VISIBLE);
        texts.addView(a);
        texts.addView(b);
        r.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (trailing != null) r.addView(trailing);
        r.setTag(new TextView[] {a, b});
        r.setBackground(ripple(t, null, 0));
        return r;
    }

    public static Icon.View chevron(Context c, Theme t) {
        Icon.View v = new Icon.View(c, Icon.CHEVRON_RIGHT, t.inkFaint);
        v.setLayoutParams(new LinearLayout.LayoutParams(t.dp(20), t.dp(20)));
        return v;
    }

    public static void setSubtitle(LinearLayout row, CharSequence s) {
        TextView sub = ((TextView[]) row.getTag())[1];
        sub.setText(s);
        sub.setVisibility(s == null || s.length() == 0 ? View.GONE : View.VISIBLE);
    }

    /** A row with a trailing Toggle; the whole row toggles. */
    public static LinearLayout switchRow(Context c, Theme t, Icon icon, CharSequence title, CharSequence subtitle, Toggle toggle) {
        LinearLayout r = listRow(c, t, icon, title, subtitle, toggle);
        toggle.setClickable(false);
        toggle.setFocusable(false);
        toggle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        r.setOnClickListener(v -> toggle.toggleByUser());
        r.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Switch.class.getName());
                info.setCheckable(true);
                info.setChecked(toggle.checked());
                info.setEnabled(toggle.isEnabled());
            }
        });
        return r;
    }

    public static void enable(View v, boolean on) {
        v.setEnabled(on);
        v.setAlpha(on ? 1f : .45f);
        if (v instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) ((ViewGroup) v).getChildAt(i).setEnabled(on);
    }

    public enum Tone { INFO, WARN, ERROR, OK }

    /**
     * Contextual notice: icon, one or two lines and an optional action. Used only when there is
     * something to do or to know at that moment.
     */
    public static LinearLayout notice(Context c, Theme t, Tone tone, CharSequence message, String action, Runnable onAction) {
        int bg, fg, iconColor;
        Icon icon;
        switch (tone) {
            case WARN: bg = t.warnContainer; fg = t.onWarnContainer; iconColor = t.warn; icon = Icon.WARNING; break;
            case ERROR: bg = t.errorContainer; fg = t.onErrorContainer; iconColor = t.error; icon = Icon.WARNING; break;
            case OK: bg = t.accentContainer; fg = t.onAccentContainer; iconColor = t.ok; icon = Icon.CHECK; break;
            default: bg = t.surfaceHigh; fg = t.ink; iconColor = t.accent; icon = Icon.INFO;
        }
        LinearLayout r = row(c);
        r.setBackground(rounded(bg, t.dpf(20)));
        r.setPadding(t.dp(16), t.dp(12), t.dp(action == null ? 16 : 6), t.dp(12));
        r.setMinimumHeight(t.dp(56));
        Icon.View iv = new Icon.View(c, icon, iconColor);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(t.dp(22), t.dp(22));
        ip.setMarginEnd(t.dp(12));
        r.addView(iv, ip);
        TextView m = text(c, t, message, Theme.LABEL, fg, false);
        r.addView(m, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView b = null;
        if (action != null) {
            b = button(c, t, action, null, ButtonStyle.TEXT, true);
            b.setTextColor(tone == Tone.INFO ? t.accent : fg);
            b.setOnClickListener(v -> onAction.run());
            r.addView(b);
        }
        r.setTag(new Object[] {iv, m, b});
        r.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        return r;
    }

    public static void updateNotice(LinearLayout notice, CharSequence message) {
        ((TextView) ((Object[]) notice.getTag())[1]).setText(message);
    }

    /** Animated show/hide without layout jumps elsewhere (height collapse). */
    public static void reveal(View v, boolean show, Theme t) {
        if ((v.getVisibility() == View.VISIBLE) == show && v.getTag(0x7f0a0001) == null) return;
        if (v.getParent() == null || ((View) v.getParent()).getWidth() == 0) {
            v.setVisibility(show ? View.VISIBLE : View.GONE);
            return;
        }
        if (t.reduceMotion || !v.isAttachedToWindow()) {
            v.setVisibility(show ? View.VISIBLE : View.GONE);
            return;
        }
        Object running = v.getTag(0x7f0a0001);
        if (running instanceof ValueAnimator) ((ValueAnimator) running).cancel();
        int width = View.MeasureSpec.makeMeasureSpec(((View) v.getParent()).getWidth(), View.MeasureSpec.EXACTLY);
        v.measure(width, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int target = v.getMeasuredHeight();
        int from = v.getVisibility() == View.VISIBLE ? v.getHeight() : 0;
        v.setVisibility(View.VISIBLE);
        ValueAnimator a = ValueAnimator.ofInt(from, show ? target : 0);
        a.setDuration(t.ms(Theme.STANDARD_MS));
        a.setInterpolator(new android.view.animation.PathInterpolator(.2f, 0, 0, 1));
        a.addUpdateListener(an -> {
            v.getLayoutParams().height = (int) an.getAnimatedValue();
            v.setAlpha(show ? an.getAnimatedFraction() : 1 - an.getAnimatedFraction());
            v.requestLayout();
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            boolean cancelled;
            @Override public void onAnimationCancel(android.animation.Animator animation) { cancelled = true; }
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                v.setTag(0x7f0a0001, null);
                if (cancelled) return;
                v.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
                v.setAlpha(1);
                v.setVisibility(show ? View.VISIBLE : View.GONE);
                v.requestLayout();
            }
        });
        v.setTag(0x7f0a0001, a);
        a.start();
    }

    /**
     * Collapsible group: header row (title, summary, chevron) and a body. Collapsed by default.
     * Returns {header, body}.
     */
    public static View[] disclosure(Context c, Theme t, Icon icon, String title, String summary, boolean open, java.util.function.Consumer<Boolean> changed) {
        Icon.View chevron = new Icon.View(c, Icon.CHEVRON_DOWN, t.inkFaint);
        chevron.setLayoutParams(new LinearLayout.LayoutParams(t.dp(20), t.dp(20)));
        chevron.glyphRotation(open ? 180 : 0);
        LinearLayout header = listRow(c, t, icon, title, summary, chevron);
        LinearLayout body = column(c);
        body.setVisibility(open ? View.VISIBLE : View.GONE);
        boolean[] state = {open};
        header.setOnClickListener(v -> {
            boolean now = state[0] = !state[0];
            reveal(body, now, t);
            chevron.animate().cancel();
            ValueAnimator rot = ValueAnimator.ofFloat(now ? 0 : 180, now ? 180 : 0);
            rot.setDuration(t.ms(Theme.STANDARD_MS));
            rot.addUpdateListener(a -> chevron.glyphRotation((float) a.getAnimatedValue()));
            rot.start();
            header.setStateDescription(now ? "Expandido" : "Recolhido");
            if (changed != null) changed.accept(now);
        });
        header.setStateDescription(open ? "Expandido" : "Recolhido");
        return new View[] {header, body};
    }

    public static ScrollView scroller(Context c) {
        ScrollView s = new ScrollView(c);
        s.setFillViewport(true);
        s.setClipToPadding(false);
        s.setVerticalScrollBarEnabled(false);
        s.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        return s;
    }
}
