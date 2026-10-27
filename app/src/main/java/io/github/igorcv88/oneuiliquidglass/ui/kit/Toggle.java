package io.github.igorcv88.oneuiliquidglass.ui.kit;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.*;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Switch;

/**
 * Switch drawn by the app (52 x 32 dp track inside a 48 dp target), so One UI's OEM switch theme
 * never leaks in. Reads as a Switch to TalkBack. Usually placed inside a row that forwards clicks.
 */
@android.annotation.SuppressLint("ViewConstructor") // Built in code only, never inflated.
public final class Toggle extends View {
    public interface Listener {
        void changed(Toggle toggle, boolean on);
    }

    private final Theme t;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean checked;
    private float position;
    private ValueAnimator anim;
    private Listener listener;

    public Toggle(Context c, Theme t) {
        super(c);
        this.t = t;
        setClickable(true);
        setFocusable(true);
        setOnClickListener(v -> toggleByUser());
        setMinimumWidth(t.dp(52));
        setMinimumHeight(t.dp(48));
    }

    public void listener(Listener l) {
        listener = l;
    }

    public boolean checked() {
        return checked;
    }

    /** Programmatic set: no listener call, no haptic. */
    public void set(boolean on, boolean animate) {
        if (checked == on && (anim == null || !anim.isRunning())) {
            position = on ? 1 : 0;
            invalidate();
            return;
        }
        checked = on;
        if (anim != null) anim.cancel();
        if (!animate || t.reduceMotion || !isAttachedToWindow()) {
            position = on ? 1 : 0;
            invalidate();
            return;
        }
        anim = ValueAnimator.ofFloat(position, on ? 1 : 0);
        anim.setDuration(Theme.FAST + 40);
        anim.setInterpolator(new android.view.animation.PathInterpolator(.3f, 0, .2f, 1));
        anim.addUpdateListener(a -> {
            position = (float) a.getAnimatedValue();
            invalidate();
        });
        anim.start();
    }

    public void toggleByUser() {
        if (!isEnabled()) return;
        set(!checked, true);
        performHapticFeedback(android.os.Build.VERSION.SDK_INT >= 34
                ? (checked ? HapticFeedbackConstants.TOGGLE_ON : HapticFeedbackConstants.TOGGLE_OFF)
                : HapticFeedbackConstants.CLOCK_TICK);
        sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED);
        if (listener != null) listener.changed(this, checked);
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(resolveSize(t.dp(52), w), resolveSize(t.dp(48), h));
    }

    @Override
    protected void onDraw(Canvas c) {
        float tw = t.dpf(46), th = t.dpf(28);
        float left = getWidth() - tw - t.dpf(2), top = (getHeight() - th) / 2f;
        float alpha = isEnabled() ? 1f : .38f;
        int trackOff = t.track, trackOn = t.accent;
        paint.setColor(blend(trackOff, trackOn, position));
        paint.setAlpha(Math.round(255 * alpha));
        c.drawRoundRect(left, top, left + tw, top + th, th / 2, th / 2, paint);
        float r = t.dpf(10 + position);
        float cx = left + th / 2 + (tw - th) * position, cy = top + th / 2;
        paint.setColor(blend(t.thumbOff, t.onAccent, position));
        paint.setAlpha(Math.round(255 * alpha));
        if (!t.dark) paint.setShadowLayer(t.dpf(1.5f), 0, t.dpf(.5f), 0x33000000);
        c.drawCircle(cx, cy, r, paint);
        paint.clearShadowLayer();
    }

    static int blend(int a, int b, float f) {
        f = Math.max(0, Math.min(1, f));
        int ar = Color.alpha(a), rr = Color.red(a), gg = Color.green(a), bb = Color.blue(a);
        return Color.argb(
                Math.round(ar + (Color.alpha(b) - ar) * f),
                Math.round(rr + (Color.red(b) - rr) * f),
                Math.round(gg + (Color.green(b) - gg) * f),
                Math.round(bb + (Color.blue(b) - bb) * f));
    }

    @Override
    public CharSequence getAccessibilityClassName() {
        return Switch.class.getName();
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setCheckable(true);
        info.setChecked(checked);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (anim != null) anim.cancel();
        super.onDetachedFromWindow();
    }
}
