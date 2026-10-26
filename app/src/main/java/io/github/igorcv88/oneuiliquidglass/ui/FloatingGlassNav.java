package io.github.igorcv88.oneuiliquidglass.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.RippleDrawable;
import android.view.*;
import android.widget.*;

import java.util.function.IntConsumer;

/** App-local frosted floating bar. Only samples this app's ScrollView, never the display. */
public final class FloatingGlassNav extends LinearLayout {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RenderNode glass = new RenderNode("navigation-local-backdrop");
    private final RectF bounds = new RectF();
    private final TextView[] labels = new TextView[3];
    private final boolean light;
    private final int selected;
    private final IntConsumer onSelect;
    private float bubble, downX;
    private boolean dragging;
    private ValueAnimator bubbleAnimator;
    private final View source;
    private Bitmap snapshot;
    private float collapsed;
    private ValueAnimator animator;
    private boolean compact;
    private long sampled;
    private final Runnable capture = this::sample;
    private Runnable navigation;

    public FloatingGlassNav(
            Context c, View source, boolean light, int selected, IntConsumer onSelect) {
        super(c);
        this.source = source;
        this.light = light;
        this.selected = selected;
        this.bubble = selected;
        this.onSelect = onSelect;
        setWillNotDraw(false);
        setGravity(Gravity.CENTER);
        setPadding(dp(7), dp(7), dp(7), dp(7));
        setElevation(dp(12));
        setBackground(UiStyle.pane(c, light, true, 36));
        setClipToOutline(true);
        String[] names = {"Início", "Superfícies", "Diagnóstico"};
        for (int i = 0; i < 3; i++) {
            final int index = i;
            LinearLayout item = new LinearLayout(c);
            item.setOrientation(VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setBackground(
                    new RippleDrawable(
                            ColorStateList.valueOf(0x356fe1eb),
                            null,
                            UiStyle.pane(c, light, false, 28)));
            item.setContentDescription(names[i]);
            item.setSelected(i == selected);
            item.setFocusable(true);
            item.setOnClickListener(v -> choose(index));
            item.setOnTouchListener(
                    (v, e) -> {
                        if (e.getActionMasked() == MotionEvent.ACTION_DOWN)
                            v.animate()
                                    .translationY(-dp(3))
                                    .scaleX(1.04f)
                                    .scaleY(1.04f)
                                    .setDuration(100)
                                    .start();
                        if (e.getActionMasked() == MotionEvent.ACTION_UP
                                || e.getActionMasked() == MotionEvent.ACTION_CANCEL)
                            v.animate()
                                    .translationY(0)
                                    .scaleX(1)
                                    .scaleY(1)
                                    .setDuration(140)
                                    .start();
                        return false;
                    });
            View icon =
                    new View(c) {
                        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

                        @Override
                        protected void onDraw(Canvas canvas) {
                            p.setStyle(Paint.Style.STROKE);
                            p.setStrokeWidth(1.7f);
                            p.setStrokeCap(Paint.Cap.ROUND);
                            p.setStrokeJoin(Paint.Join.ROUND);
                            p.setColor(
                                    index == selected
                                            ? light ? 0xff14687e : 0xffaff3fc
                                            : light ? 0xff52647c : 0xffa8b7cf);
                            canvas.save();
                            canvas.scale(getWidth() / 24f, getHeight() / 24f);
                            if (index == 0) {
                                Path path = new Path();
                                path.moveTo(3, 10);
                                path.lineTo(12, 3);
                                path.lineTo(21, 10);
                                path.moveTo(5, 9);
                                path.lineTo(5, 21);
                                path.lineTo(10, 21);
                                path.lineTo(10, 15);
                                path.lineTo(14, 15);
                                path.lineTo(14, 21);
                                path.lineTo(19, 21);
                                path.lineTo(19, 9);
                                canvas.drawPath(path, p);
                            } else if (index == 1) {
                                canvas.drawRoundRect(3, 4, 21, 11, 3, 3, p);
                                canvas.drawRoundRect(3, 14, 21, 21, 3, 3, p);
                            } else {
                                canvas.drawCircle(12, 12, 9, p);
                                Path path = new Path();
                                path.moveTo(5, 12);
                                path.lineTo(9, 12);
                                path.lineTo(11, 7);
                                path.lineTo(14, 17);
                                path.lineTo(16, 12);
                                path.lineTo(19, 12);
                                canvas.drawPath(path, p);
                            }
                            canvas.restore();
                        }
                    };
            // Stroke width above is expressed in the 24-unit icon coordinate space.
            item.addView(icon, new LinearLayout.LayoutParams(dp(23), dp(23)));
            TextView label = labels[i] = new TextView(c);
            label.setText(names[i]);
            label.setTextSize(11);
            label.setGravity(Gravity.CENTER);
            label.setTextColor(
                    i == selected
                            ? light ? 0xff14687e : 0xffb8f5fa
                            : light ? 0xff52647c : 0xffa8b7cf);
            item.addView(label, new LinearLayout.LayoutParams(-1, dp(19)));
            addView(item, new LinearLayout.LayoutParams(0, -1, 1));
        }
        post(capture);
    }

    private int dp(float n) {
        return UiStyle.dp(getContext(), n);
    }

    public void scrolling(int y, int old) {
        boolean next = y > old + dp(3) && y > dp(70);
        if (y < old - dp(3) || y < dp(30)) next = false;
        else if (!next) next = compact;
        collapse(next);
        if (android.os.SystemClock.uptimeMillis() - sampled > 80) {
            removeCallbacks(capture);
            postOnAnimation(capture);
        }
    }

    private void collapse(boolean next) {
        if (compact == next) return;
        compact = next;
        if (animator != null) animator.cancel();
        animator = ValueAnimator.ofFloat(collapsed, next ? 1 : 0);
        animator.setDuration(200);
        animator.addUpdateListener(
                a -> {
                    collapsed = (float) a.getAnimatedValue();
                    int full =
                            Math.min(
                                    getResources().getDisplayMetrics().widthPixels - dp(40),
                                    dp(400));
                    ViewGroup.LayoutParams lp = getLayoutParams();
                    if (lp == null) return;
                    lp.width = Math.round(full + (dp(204) - full) * collapsed);
                    lp.height = dp(72 - 12 * collapsed);
                    setLayoutParams(lp);
                    for (TextView label : labels) {
                        label.setAlpha(1 - collapsed);
                        label.getLayoutParams().height = dp(19 * (1 - collapsed));
                        label.requestLayout();
                    }
                    invalidate();
                });
        animator.start();
    }

    private void choose(int index) {
        if (bubbleAnimator != null) bubbleAnimator.cancel();
        bubbleAnimator = ValueAnimator.ofFloat(bubble, index);
        bubbleAnimator.setDuration(150);
        bubbleAnimator.addUpdateListener(
                a -> {
                    bubble = (float) a.getAnimatedValue();
                    invalidate();
                });
        bubbleAnimator.start();
        if (navigation != null) removeCallbacks(navigation);
        navigation =
                () -> {
                    if (isAttachedToWindow()) onSelect.accept(index);
                };
        postDelayed(navigation, 155);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downX = e.getX();
            dragging = false;
        }
        if (e.getActionMasked() == MotionEvent.ACTION_MOVE && Math.abs(e.getX() - downX) > dp(12)) {
            dragging = true;
            getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!dragging) return super.onTouchEvent(e);
        if (e.getActionMasked() == MotionEvent.ACTION_MOVE) {
            bubble =
                    Math.max(
                            0,
                            Math.min(2, (e.getX() - dp(7)) / ((getWidth() - dp(14)) / 3f) - .5f));
            invalidate();
        }
        if (e.getActionMasked() == MotionEvent.ACTION_UP) {
            choose(Math.round(bubble));
            dragging = false;
        }
        if (e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            bubble = selected;
            dragging = false;
            invalidate();
        }
        return true;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        removeCallbacks(capture);
        postOnAnimation(capture);
    }

    private void sample() {
        if (!isAttachedToWindow() || source.getWidth() < 1 || getWidth() < 1) return;
        sampled = android.os.SystemClock.uptimeMillis();
        // One small buffer, bounded and reused. Draw excludes this overlay by construction.
        int w = Math.max(1, getWidth() / 3), h = Math.max(1, getHeight() / 3);
        if (snapshot == null || snapshot.getWidth() != w || snapshot.getHeight() != h) {
            if (snapshot != null) snapshot.recycle();
            snapshot = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        }
        snapshot.eraseColor(light ? 0xffedf1f7 : 0xff101722);
        int[] src = new int[2], dst = new int[2];
        source.getLocationInWindow(src);
        getLocationInWindow(dst);
        Canvas canvas = new Canvas(snapshot);
        canvas.scale(1 / 3f, 1 / 3f);
        canvas.translate(src[0] - dst[0], src[1] - dst[1]);
        source.draw(canvas);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        bounds.set(0, 0, getWidth(), getHeight());
        if (snapshot != null && canvas.isHardwareAccelerated()) {
            glass.setPosition(0, 0, getWidth(), getHeight());
            RecordingCanvas rc = glass.beginRecording();
            rc.drawBitmap(snapshot, null, bounds, null);
            glass.endRecording();
            glass.setRenderEffect(
                    RenderEffect.createBlurEffect(dp(14), dp(14), Shader.TileMode.CLAMP));
            canvas.drawRenderNode(glass);
            canvas.save();
            Path inset = new Path();
            inset.addRoundRect(
                    new RectF(dp(5), dp(5), getWidth() - dp(5), getHeight() - dp(5)),
                    dp(31),
                    dp(31),
                    Path.Direction.CW);
            canvas.clipOutPath(inset);
            canvas.translate(dp(2), -dp(1));
            paint.setAlpha(110);
            canvas.drawBitmap(snapshot, null, bounds, paint);
            paint.setAlpha(255);
            canvas.restore();
        }
        paint.setShader(
                new LinearGradient(
                        0,
                        0,
                        getWidth(),
                        getHeight(),
                        light
                                ? new int[] {0xbbe5f8fc, 0xc2e5e4f8}
                                : new int[] {0xc0334b61, 0xc329334b},
                        null,
                        Shader.TileMode.CLAMP));
        canvas.drawRoundRect(bounds, dp(36), dp(36), paint);
        paint.setShader(null);
        float cell = (getWidth() - dp(14)) / 3f;
        bounds.set(dp(7) + cell * bubble, dp(7), dp(7) + cell * (bubble + 1), getHeight() - dp(7));
        paint.setShader(
                new LinearGradient(
                        bounds.left,
                        0,
                        bounds.right,
                        getHeight(),
                        light ? 0xb8c5ecf3 : 0xb4497287,
                        light ? 0x99d6cdf0 : 0x994f4b78,
                        Shader.TileMode.CLAMP));
        canvas.drawRoundRect(bounds, dp(27), dp(27), paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(light ? 0x66ffffff : 0x556fd9e7);
        canvas.drawArc(bounds, 195, 105, false, paint);
        paint.setStyle(Paint.Style.FILL);
        super.onDraw(canvas);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(capture);
        if (navigation != null) removeCallbacks(navigation);
        if (animator != null) animator.cancel();
        if (bubbleAnimator != null) bubbleAnimator.cancel();
        if (snapshot != null) {
            snapshot.recycle();
            snapshot = null;
        }
        glass.discardDisplayList();
        super.onDetachedFromWindow();
    }
}
