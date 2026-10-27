package io.github.igorcv88.oneuiliquidglass.ui.kit;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.*;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;

/** Segmented selector with a sliding pill; each segment is a radio button for TalkBack. */
@android.annotation.SuppressLint("ViewConstructor") // Built in code only, never inflated.
public final class Segmented extends LinearLayout {
    public interface Listener {
        void selected(int index);
    }

    private final Theme t;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextView[] items;
    private float pill;
    private int selected;
    private ValueAnimator anim;
    private Listener listener;

    public Segmented(Context c, Theme t, String[] labels, int selected) {
        super(c);
        this.t = t;
        this.selected = selected;
        pill = selected;
        setWillNotDraw(false);
        setPadding(t.dp(4), t.dp(4), t.dp(4), t.dp(4));
        setBackground(Ui.rounded(t.dark ? 0xff131a25 : 0xffe3e9f0, t.dpf(26)));
        items = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView v = Ui.text(c, t, labels[i], Theme.LABEL, t.inkMuted, true);
            v.setGravity(Gravity.CENTER);
            v.setSingleLine(true);
            v.setEllipsize(android.text.TextUtils.TruncateAt.END);
            v.setMinHeight(t.dp(44));
            v.setPadding(t.dp(6), 0, t.dp(6), 0);
            v.setOnClickListener(x -> select(index, true));
            v.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override
                public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setClassName(RadioButton.class.getName());
                    info.setCheckable(true);
                    info.setChecked(index == Segmented.this.selected);
                }
            });
            items[i] = v;
            addView(v, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        }
        tint();
    }

    public void listener(Listener l) {
        listener = l;
    }

    public int selected() {
        return selected;
    }

    public void select(int index, boolean fromUser) {
        if (index == selected) return;
        selected = index;
        tint();
        if (anim != null) anim.cancel();
        if (t.reduceMotion || !isAttachedToWindow()) {
            pill = index;
            invalidate();
        } else {
            anim = ValueAnimator.ofFloat(pill, index);
            anim.setDuration(Theme.STANDARD_MS);
            anim.setInterpolator(new android.view.animation.PathInterpolator(.3f, 0, .1f, 1));
            anim.addUpdateListener(a -> {
                pill = (float) a.getAnimatedValue();
                invalidate();
            });
            anim.start();
        }
        if (fromUser) {
            performHapticFeedback(android.os.Build.VERSION.SDK_INT >= 34 ? HapticFeedbackConstants.SEGMENT_TICK : HapticFeedbackConstants.CLOCK_TICK);
            if (listener != null) listener.selected(index);
        }
    }

    private void tint() {
        for (int i = 0; i < items.length; i++) {
            boolean on = i == selected;
            items[i].setTextColor(on ? t.ink : t.inkMuted);
            items[i].setSelected(on);
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        if (items.length == 0) return;
        float w = (getWidth() - getPaddingLeft() - getPaddingRight()) / (float) items.length;
        float l = getPaddingLeft() + w * pill, top = getPaddingTop(), b = getHeight() - getPaddingBottom();
        paint.setColor(t.dark ? 0xff27324a : 0xffffffff);
        if (!t.dark) paint.setShadowLayer(t.dpf(3), 0, t.dpf(1), 0x1f0b1a2c);
        c.drawRoundRect(l, top, l + w, b, t.dpf(22), t.dpf(22), paint);
        paint.clearShadowLayer();
        super.onDraw(c);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (anim != null) anim.cancel();
        super.onDetachedFromWindow();
    }
}
