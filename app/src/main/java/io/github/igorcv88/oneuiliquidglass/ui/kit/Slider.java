package io.github.igorcv88.oneuiliquidglass.ui.kit;

import android.content.Context;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.SeekBar;

/**
 * Track slider drawn by the app: 4 dp track, 22 dp thumb that grows while dragged, a small mark
 * at the default value and a detent at an automatic first position. Drags only after horizontal
 * movement so vertical scrolling is never captured. Disabled state is both drawn and enforced.
 */
@android.annotation.SuppressLint("ViewConstructor") // Built in code only, never inflated.
public final class Slider extends View {
    public interface Listener {
        void start();

        void change(int index, boolean fromUser);

        void stop(int index);
    }

    private final Theme t;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int max = 100, index, mark = -1;
    private boolean autoDetent, dragging, pending;
    private float downX, downY, grow;
    private Listener listener;
    private String description = "";
    private java.util.function.IntFunction<String> valueText = Integer::toString;
    private final int slop;

    public Slider(Context c, Theme t) {
        super(c);
        this.t = t;
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        setFocusable(true);
        setMinimumHeight(t.dp(48));
    }

    public void configure(int max, int mark, boolean autoDetent) {
        this.max = Math.max(1, max);
        this.mark = mark;
        this.autoDetent = autoDetent;
        invalidate();
    }

    public void listener(Listener l) {
        listener = l;
    }

    public void describe(String label, java.util.function.IntFunction<String> text) {
        description = label;
        valueText = text;
    }

    public int index() {
        return index;
    }

    public void set(int i) {
        i = Math.max(0, Math.min(max, i));
        if (i == index) return;
        index = i;
        invalidate();
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (!enabled && dragging) finish();
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), resolveSize(t.dp(44), h));
    }

    private float left() {
        return getPaddingLeft() + t.dpf(12);
    }

    private float right() {
        return getWidth() - getPaddingRight() - t.dpf(12);
    }

    private float x(int i) {
        return left() + (right() - left()) * i / (float) max;
    }

    private int at(float x) {
        float f = (x - left()) / Math.max(1, right() - left());
        return Math.round(Math.max(0, Math.min(1, f)) * max);
    }

    @Override
    protected void onDraw(Canvas c) {
        float cy = getHeight() / 2f, h = t.dpf(4);
        boolean on = isEnabled();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(t.track);
        if (!on) paint.setAlpha(110);
        c.drawRoundRect(left(), cy - h / 2, right(), cy + h / 2, h, h, paint);
        float tx = x(index);
        paint.setColor(on ? t.accent : t.inkDisabled);
        c.drawRoundRect(left(), cy - h / 2, Math.max(left() + h, tx), cy + h / 2, h, h, paint);
        if (autoDetent) {
            paint.setColor(on ? t.inkFaint : t.inkDisabled);
            c.drawCircle(x(0), cy, t.dpf(3), paint);
        }
        if (mark >= 0 && mark <= max) {
            paint.setColor(index >= mark ? (t.dark ? 0x99000000 : 0x99ffffff) : t.inkFaint);
            if (!on) paint.setAlpha(80);
            c.drawCircle(x(mark), cy, t.dpf(2), paint);
        }
        float r = t.dpf(10 + 3 * grow);
        if (on) {
            paint.setColor(t.accentSoft);
            if (dragging) c.drawCircle(tx, cy, r + t.dpf(8), paint);
            paint.setColor(t.accent);
        } else paint.setColor(t.dark ? 0xff4a586c : 0xffb8c3d1);
        c.drawCircle(tx, cy, r, paint);
        paint.setColor(on ? t.onAccent : t.surface);
        c.drawCircle(tx, cy, t.dpf(3.5f), paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!isEnabled()) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                pending = true;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (pending) {
                    float dx = Math.abs(e.getX() - downX), dy = Math.abs(e.getY() - downY);
                    if (dy > slop && dy > dx) {
                        pending = false;
                        return false;
                    }
                    if (dx > slop) {
                        pending = false;
                        begin();
                    }
                }
                if (dragging) move(at(e.getX()));
                return true;
            case MotionEvent.ACTION_UP:
                if (pending) {
                    pending = false;
                    begin();
                    move(at(e.getX()));
                }
                if (dragging) finish();
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                pending = false;
                if (dragging) finish();
                return true;
        }
        return false;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private void begin() {
        dragging = true;
        getParent().requestDisallowInterceptTouchEvent(true);
        grow = 1;
        if (listener != null) listener.start();
        invalidate();
    }

    private void move(int i) {
        if (i == index) return;
        int old = index;
        index = i;
        if ((mark >= 0 && (old - mark) * (i - mark) <= 0) || (autoDetent && (old == 0 || i == 0)))
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        if (listener != null) listener.change(i, true);
        invalidate();
    }

    private void finish() {
        dragging = false;
        grow = 0;
        getParent().requestDisallowInterceptTouchEvent(false);
        if (listener != null) listener.stop(index);
        invalidate();
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (isEnabled() && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            step(code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
            return true;
        }
        return super.onKeyDown(code, e);
    }

    private void step(int direction) {
        int s = Math.max(1, max / 50);
        int next = Math.max(0, Math.min(max, index + direction * s));
        if (next == index) return;
        if (listener != null) listener.start();
        index = next;
        if (listener != null) {
            listener.change(next, true);
            listener.stop(next);
        }
        invalidate();
        announceForAccessibility(valueText.apply(next));
    }

    @Override
    public CharSequence getAccessibilityClassName() {
        return SeekBar.class.getName();
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(
                AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, 0, max, index));
        info.setContentDescription(description);
        info.setStateDescription(valueText.apply(index));
        if (isEnabled()) {
            if (index > 0) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
            if (index < max) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);
        }
    }

    @Override
    public boolean performAccessibilityAction(int action, Bundle args) {
        if (isEnabled()) {
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) {
                step(1);
                return true;
            }
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
                step(-1);
                return true;
            }
            if (action == android.R.id.accessibilityActionSetProgress && args != null) {
                int target = Math.round(args.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE));
                if (listener != null) listener.start();
                index = Math.max(0, Math.min(max, target));
                if (listener != null) {
                    listener.change(index, true);
                    listener.stop(index);
                }
                invalidate();
                return true;
            }
        }
        return super.performAccessibilityAction(action, args);
    }
}
