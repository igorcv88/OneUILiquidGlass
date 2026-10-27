package io.github.igorcv88.oneuiliquidglass.ui.kit;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.*;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.function.IntConsumer;

/**
 * Floating navigation pill. The page behind it is recorded into a RenderNode every frame it
 * changes, blurred and bent at the rim by {@link Lens}; the selection is a brighter lens that
 * slides between destinations. Expanded (icon + label) at rest and when scrolling up, compact
 * (icons) when scrolling down; hidden while the keyboard is open. It only samples this app's
 * own views, never the screen.
 */
@android.annotation.SuppressLint("ViewConstructor") // Built in code only, never inflated.
public final class GlassNav extends ViewGroup {
    private final Theme t;
    private final String[] labels;
    private final Icon[] icons;
    private final Icon.View[] iconViews;
    private final TextView[] labelViews;
    private final View[] cells;
    private final IntConsumer onSelect;
    private final RenderNode backdrop = new RenderNode("nav-backdrop");
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF box = new RectF(), pill = new RectF();
    private View source;
    private int selected;
    private float bubble, compact;
    private ValueAnimator bubbleAnim, compactAnim;
    private boolean compactTarget, echo, hiddenForIme;
    private final ViewTreeObserver.OnPreDrawListener preDraw = () -> {
        // Follow the page: one redraw per page frame; our own redraw does not request another.
        if (echo) echo = false;
        else {
            echo = true;
            invalidate();
        }
        return true;
    };

    public GlassNav(Context c, Theme t, String[] labels, Icon[] icons, int selected, IntConsumer onSelect) {
        super(c);
        this.t = t;
        this.labels = labels;
        this.icons = icons;
        this.selected = selected;
        this.bubble = selected;
        this.onSelect = onSelect;
        setWillNotDraw(false);
        setClipToPadding(false);
        setElevation(t.dpf(10));
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), view.getHeight() / 2f);
                outline.setAlpha(t.dark ? .5f : .25f);
            }
        });
        iconViews = new Icon.View[labels.length];
        labelViews = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            iconViews[i] = new Icon.View(c, icons[i], 0);
            addView(iconViews[i]);
            TextView l = Ui.text(c, t, labels[i], Theme.SMALL, 0, true);
            l.setGravity(Gravity.CENTER);
            l.setSingleLine(true);
            l.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            labelViews[i] = l;
            addView(l);
        }
        cells = new View[labels.length];
        for (int i = 0; i < labels.length; i++) addView(cells[i] = cell(i));
        tint();
    }

    /** The page whose content shows through the bar. */
    public void source(View page) {
        source = page;
        invalidate();
    }

    public int selected() {
        return selected;
    }

    public void select(int index, boolean animate) {
        if (index == selected && !animate) return;
        selected = index;
        tint();
        if (bubbleAnim != null) bubbleAnim.cancel();
        if (!animate || t.reduceMotion) {
            bubble = index;
            invalidate();
            return;
        }
        bubbleAnim = ValueAnimator.ofFloat(bubble, index);
        bubbleAnim.setDuration(Theme.EMPHASIZED);
        bubbleAnim.setInterpolator(new android.view.animation.OvershootInterpolator(.9f));
        bubbleAnim.addUpdateListener(a -> {
            bubble = (float) a.getAnimatedValue();
            invalidate();
        });
        bubbleAnim.start();
    }

    private void tint() {
        for (int i = 0; i < labels.length; i++) {
            int color = i == selected ? (t.dark ? 0xffeafcff : 0xff0b4f5a) : t.dark ? 0xffb9c5d6 : 0xff42526a;
            iconViews[i].color(color);
            labelViews[i].setTextColor(color);
        }
        if (cells != null) for (View c : cells) c.setSelected(false);
    }

    /** Scroll delta of the current page: down collapses, up or near the top expands. */
    public void scrolled(int y, int dy) {
        boolean next = compactTarget;
        if (y < t.dp(24) || dy < -t.dp(4)) next = false;
        else if (dy > t.dp(4)) next = true;
        if (next == compactTarget) return;
        compactTarget = next;
        animateCompact(next ? 1 : 0);
    }

    public void expand() {
        compactTarget = false;
        animateCompact(0);
    }

    private void animateCompact(float to) {
        if (compactAnim != null) compactAnim.cancel();
        if (t.reduceMotion) {
            compact = to;
            requestLayout();
            return;
        }
        compactAnim = ValueAnimator.ofFloat(compact, to);
        compactAnim.setDuration(Theme.STANDARD_MS + 40);
        compactAnim.setInterpolator(new android.view.animation.PathInterpolator(.2f, 0, 0, 1));
        compactAnim.addUpdateListener(a -> {
            compact = (float) a.getAnimatedValue();
            requestLayout();
            invalidate();
        });
        compactAnim.start();
    }

    public void imeVisible(boolean visible) {
        if (hiddenForIme == visible) return;
        hiddenForIme = visible;
        animate().translationY(visible ? getHeight() + t.dp(48) : 0).alpha(visible ? 0 : 1)
                .setDuration(t.ms(Theme.STANDARD_MS)).start();
        setImportantForAccessibility(visible ? IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS : IMPORTANT_FOR_ACCESSIBILITY_AUTO);
    }

    /** Height of the expanded bar, for page bottom padding. */
    public int expandedHeight() {
        return t.dp(36) + Math.round(labelViews[0].getLineHeight()) + t.dp(8);
    }

    private int compactHeight() {
        return t.dp(52);
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int available = MeasureSpec.getSize(wSpec);
        int full = Math.min(available, t.dp(380)), small = Math.min(available, t.dp(188));
        int w = Math.round(full + (small - full) * compact);
        int h = Math.round(expandedHeight() + (compactHeight() - expandedHeight()) * compact);
        for (int i = 0; i < labels.length; i++) {
            iconViews[i].measure(MeasureSpec.makeMeasureSpec(t.dp(24), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(t.dp(24), MeasureSpec.EXACTLY));
            labelViews[i].measure(MeasureSpec.makeMeasureSpec(w / labels.length, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        }
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onLayout(boolean changed, int l, int tp, int r, int b) {
        int w = r - l, h = b - tp, cell = (w - t.dp(12)) / labels.length;
        float label = 1 - compact;
        int iconSize = t.dp(24), labelH = labelViews[0].getMeasuredHeight();
        int contentH = Math.round(iconSize + (t.dp(2) + labelH) * label);
        int top = (h - contentH) / 2;
        for (int i = 0; i < labels.length; i++) {
            int cx = t.dp(6) + cell * i + cell / 2;
            iconViews[i].layout(cx - iconSize / 2, top, cx + iconSize / 2, top + iconSize);
            int lw = labelViews[i].getMeasuredWidth();
            int ly = top + iconSize + t.dp(2);
            labelViews[i].layout(cx - lw / 2, ly, cx + lw / 2, ly + labelH);
            labelViews[i].setAlpha(Math.max(0, 1 - compact * 2.2f));
            cells[i].layout(t.dp(6) + cell * i, 0, t.dp(6) + cell * (i + 1), h);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnPreDrawListener(preDraw);
    }

    @Override
    protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnPreDrawListener(preDraw);
        if (bubbleAnim != null) bubbleAnim.cancel();
        if (compactAnim != null) compactAnim.cancel();
        backdrop.discardDisplayList();
        super.onDetachedFromWindow();
    }

    private final int[] a = new int[2], b = new int[2];
    private int shaderW, shaderH;
    private Shader tintShader, rimShader, pillShader, pillRimShader;
    private final Matrix pillMatrix = new Matrix();
    private RenderEffect effect;

    /** Shaders and effects depend only on the size: built once per size, not per frame. */
    private void prepare(int w, int h) {
        if (w == shaderW && h == shaderH && effect != null) return;
        shaderW = w;
        shaderH = h;
        tintShader = new LinearGradient(0, 0, 0, h,
                t.dark ? new int[] {0x8c2a3446, 0xa61b2331} : new int[] {0xb8ffffff, 0xccf1f5fa}, null, Shader.TileMode.CLAMP);
        rimShader = new LinearGradient(0, 0, 0, h, new int[] {t.dark ? 0x55ffffff : 0xccffffff, 0x00ffffff, t.dark ? 0x22ffffff : 0x33ffffff},
                new float[] {0, .5f, 1}, Shader.TileMode.CLAMP);
        float cellW = (w - t.dpf(12)) / labels.length, pillH = h - 2 * t.dpf(5);
        pillShader = new LinearGradient(0, 0, cellW, pillH,
                t.dark ? new int[] {0x5c7fdfe9, 0x40bca8f4} : new int[] {0x5580dfe9, 0x40b4a2ee}, null, Shader.TileMode.CLAMP);
        pillRimShader = new LinearGradient(0, 0, 0, pillH, new int[] {t.dark ? 0x66ffffff : 0xaaffffff, 0x00ffffff}, null, Shader.TileMode.CLAMP);
        int pad = t.dp(24);
        float r = h / 2f;
        effect = RenderEffect.createChainEffect(Lens.effect(pad, pad, pad + w, pad + h, r, Math.min(r, t.dpf(18)), .55f, .08f), Lens.blur(t.dpf(10)));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        float r = h / 2f;
        box.set(0, 0, w, h);
        clip.reset();
        clip.addRoundRect(box, r, r, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clip);
        boolean drew = false;
        if (source != null && canvas.isHardwareAccelerated() && source.isAttachedToWindow()) {
            source.getLocationInWindow(a);
            getLocationInWindow(b);
            int pad = t.dp(24);
            backdrop.setPosition(0, 0, w + 2 * pad, h + 2 * pad);
            RecordingCanvas rc = backdrop.beginRecording();
            rc.drawColor(t.bg);
            rc.translate(a[0] - b[0] + pad - source.getScrollX(), a[1] - b[1] + pad - source.getScrollY());
            source.draw(rc);
            backdrop.endRecording();
            prepare(w, h);
            backdrop.setRenderEffect(effect);
            canvas.save();
            canvas.translate(-pad, -pad);
            canvas.drawRenderNode(backdrop);
            canvas.restore();
            drew = true;
        }
        // Tint: keeps labels legible over any content, lighter than a solid bar.
        prepare(w, h);
        paint.setShader(drew ? tintShader : null);
        if (!drew) paint.setColor(t.dark ? 0xf01d2533 : 0xf5ffffff);
        canvas.drawRect(box, paint);
        paint.setShader(null);
        // Selection lens.
        float cell = (w - t.dpf(12)) / labels.length;
        float inset = t.dpf(5);
        pill.set(t.dpf(6) + cell * bubble + t.dpf(1), inset, t.dpf(6) + cell * (bubble + 1) - t.dpf(1), h - inset);
        float pr = pill.height() / 2f;
        pillMatrix.setTranslate(pill.left, pill.top);
        pillShader.setLocalMatrix(pillMatrix);
        pillRimShader.setLocalMatrix(pillMatrix);
        paint.setShader(pillShader);
        canvas.drawRoundRect(pill, pr, pr, paint);
        paint.setShader(null);
        // Specular rims: a thin light line along the top of bar and selection.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(t.dpf(1));
        paint.setShader(rimShader);
        box.inset(t.dpf(.5f), t.dpf(.5f));
        canvas.drawRoundRect(box, r, r, paint);
        paint.setShader(pillRimShader);
        canvas.drawRoundRect(pill, pr, pr, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        canvas.restore();
    }

    private void choose(int index) {
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
        if (index == selected) return;
        select(index, true);
        onSelect.accept(index);
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setCollectionInfo(AccessibilityNodeInfo.CollectionInfo.obtain(1, labels.length, false));
    }

    /** Transparent, focusable tab targets over each cell: touch, TalkBack and keyboard. */
    private View cell(int index) {
        View v = new View(getContext());
        v.setContentDescription(labels[index]);
        v.setFocusable(true);
        v.setClickable(true);
        v.setOnClickListener(x -> choose(index));
        v.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName("android.widget.TabWidget");
                info.setSelected(index == selected);
                info.setCollectionItemInfo(AccessibilityNodeInfo.CollectionItemInfo.obtain(0, 1, index, 1, false, index == selected));
            }
        });
        return v;
    }
}
