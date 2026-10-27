package io.github.igorcv88.oneuiliquidglass.ui.preview;

import android.content.Context;
import android.graphics.*;
import android.view.MotionEvent;
import android.view.View;

import io.github.igorcv88.oneuiliquidglass.TestNotificationModel;
import io.github.igorcv88.oneuiliquidglass.config.Controls;
import io.github.igorcv88.oneuiliquidglass.ui.kit.Lens;

import java.util.function.Function;

/**
 * Offline sample of one notification surface over a synthetic backdrop. Nothing is captured
 * from the screen and no system renderer is loaded: the material comes from {@link
 * PreviewParams}, drawn with RenderNode blur, the app's {@link Lens} and plain paint. Press and
 * hold shows the "before" values for comparison. A magnifier can follow the part a control
 * changes. Always labelled as a simulation.
 */
@android.annotation.SuppressLint("ViewConstructor") // Built in code only, never inflated.
public final class NotificationPreview extends View {
    public enum Backdrop {
        LIGHT("Claro"), DARK("Escuro"), COLOR("Colorido"), GRID("Grade");
        public final String label;
        Backdrop(String label) { this.label = label; }
    }

    /** Sample space: 360 units wide (a phone in dp); device px = units x 4 (1440 px screen). */
    static final float W = 360, DEVICE = 4f;

    private Controls.Surface surface = Controls.Surface.HEADSUP;
    private Backdrop backdrop = Backdrop.COLOR;
    private boolean lightTheme;
    private Function<String, String> values = k -> null, before;
    private boolean comparing, holdToCompare = true;
    private Controls.Channel focus = Controls.Channel.NONE;
    private boolean zoomOnly;
    private String badge = "Simulação";

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RenderNode scene = new RenderNode("preview-scene");
    /** One node per drawn glass: a node recorded twice in one scene would show its last content. */
    private final java.util.List<RenderNode> pool = new java.util.ArrayList<>();
    private int used;
    private final RenderNode panel = new RenderNode("preview-panel");
    private Bitmap wallpaper;
    private Backdrop wallpaperFor;
    private final float density;

    public NotificationPreview(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        text.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        describe();
    }

    public void surface(Controls.Surface s) {
        surface = s;
        describe();
        invalidate();
    }

    public void backdrop(Backdrop b) {
        backdrop = b;
        invalidate();
    }

    public Backdrop backdrop() {
        return backdrop;
    }

    public void lightTheme(boolean light) {
        lightTheme = light;
        invalidate();
    }

    public boolean lightTheme() {
        return lightTheme;
    }

    /** Values to draw (ControlState::shown) and the "before" values held for comparison. */
    public void values(Function<String, String> now, Function<String, String> previous) {
        values = now;
        before = previous;
        invalidate();
    }

    /** Draw the "before" values while true (also driven by press and hold). */
    public void comparing(boolean on) {
        if (comparing == on) return;
        comparing = on;
        invalidate();
    }

    public void holdToCompare(boolean on) {
        holdToCompare = on;
    }

    /** Magnifier on the part a channel changes; NONE hides it. */
    public void focus(Controls.Channel channel) {
        if (focus == channel) return;
        focus = channel;
        invalidate();
    }

    /** Draw only the magnified focus region (triptych tiles in the editor). */
    public void zoomOnly(Controls.Channel channel, String label) {
        zoomOnly = true;
        focus = channel;
        badge = label;
        holdToCompare = false;
        invalidate();
    }

    private void describe() {
        setContentDescription("Simulação de " + surface.label.toLowerCase(java.util.Locale.ROOT)
                + " com o material atual. Toque e segure para comparar com antes.");
    }

    float sceneHeight() {
        switch (surface) {
            case KEYGUARD: return 300;
            case SHADE: return 300;
            default: return 232;
        }
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int natural = Math.round(w * sceneHeight() / W);
        int h = MeasureSpec.getMode(hSpec) == MeasureSpec.EXACTLY ? MeasureSpec.getSize(hSpec)
                : MeasureSpec.getMode(hSpec) == MeasureSpec.AT_MOST ? Math.min(natural, MeasureSpec.getSize(hSpec)) : natural;
        setMeasuredDimension(w, h);
    }

    // ---------------------------------------------------------------- input

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!holdToCompare || before == null) return super.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(false);
                postDelayed(holdStart, 160);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(holdStart);
                if (!comparing && e.getActionMasked() == MotionEvent.ACTION_UP) performClick();
                if (comparing) {
                    comparing(false);
                    announceForAccessibility("Agora");
                }
                return true;
        }
        return true;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private final Runnable holdStart = () -> {
        comparing(true);
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        announceForAccessibility("Antes");
    };

    // ---------------------------------------------------------------- backdrops

    /** Mean luma (0-1) of the backdrop under a rect in sample units. */
    private final java.util.Map<String, Float> lumaCache = new java.util.HashMap<>();

    float luma(RectF r) {
        ensureWallpaper();
        String key = r.toShortString();
        Float cached = lumaCache.get(key);
        if (cached != null) return cached;
        float value = measure(r);
        lumaCache.put(key, value);
        return value;
    }

    private float measure(RectF r) {
        float s = wallpaper.getWidth() / W;
        int x0 = clamp(Math.round(r.left * s), wallpaper.getWidth() - 1), x1 = clamp(Math.round(r.right * s), wallpaper.getWidth() - 1);
        int y0 = clamp(Math.round(r.top * s), wallpaper.getHeight() - 1), y1 = clamp(Math.round(r.bottom * s), wallpaper.getHeight() - 1);
        double sum = 0;
        int n = 0;
        for (int y = y0; y <= y1; y += 3)
            for (int x = x0; x <= x1; x += 3) {
                int c = wallpaper.getPixel(x, y);
                sum += (0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c)) / 255.0;
                n++;
            }
        return n == 0 ? .5f : (float) (sum / n);
    }

    private static int clamp(int v, int max) {
        return Math.max(0, Math.min(max, v));
    }

    private void ensureWallpaper() {
        if (wallpaper != null && wallpaperFor == backdrop) return;
        if (wallpaper != null) wallpaper.recycle();
        int px = Math.max(1, Math.round(W * 2)), py = Math.round(320 * 2);
        wallpaper = Bitmap.createBitmap(px, py, Bitmap.Config.ARGB_8888);
        wallpaperFor = backdrop;
        lumaCache.clear();
        Canvas c = new Canvas(wallpaper);
        c.scale(2, 2);
        Backdrops.draw(c, backdrop, W, 320);
    }

    // ---------------------------------------------------------------- drawing

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w < 2 || h < 2) return;
        ensureWallpaper();
        float sh = sceneHeight();
        // Fit the width; when the view is shorter than the scene (collapsed), keep the card in view.
        float scale = zoomOnly ? Math.min(w / W, h / sh) : w / W;
        Function<String, String> f = comparing && before != null ? before : values;
        boolean hw = canvas.isHardwareAccelerated();
        if (!hw) {
            // Software canvas (e.g. a snapshot): plain rendering, no effects.
            canvas.save();
            canvas.translate((w - W * scale) / 2, Math.min(0, (h - sh * scale) / 2));
            canvas.scale(scale, scale);
            drawScene(canvas, f, false, scale);
            canvas.restore();
            return;
        }
        int sw = Math.round(W * scale), shPx = Math.round(sh * scale);
        scene.setPosition(0, 0, sw, shPx);
        RecordingCanvas rc = scene.beginRecording(sw, shPx);
        rc.scale(scale, scale);
        drawScene(rc, f, true, scale);
        scene.endRecording();
        canvas.save();
        if (zoomOnly) {
            // A window of ~100 units around the focus, kept inside the scene.
            float win = W * (edgeChannel() ? .17f : .28f), winH = win * h / (float) w;
            PointF p = focusPoint();
            float cx = Math.max(win / 2, Math.min(W - win / 2, p.x));
            float cy = Math.max(winH / 2, Math.min(sh - winH / 2, p.y));
            float zoom = w / win;
            canvas.translate(w / 2f, h / 2f);
            canvas.scale(zoom / scale, zoom / scale);
            canvas.translate(-cx * scale, -cy * scale);
            canvas.drawRenderNode(scene);
            canvas.restore();
            chip(canvas, badge, w, h, false);
            return;
        }
        float ox = (w - sw) / 2f, oy = (h - shPx) / 2f;
        if (shPx > h) {
            RectF card = cardRect(0);
            // Tall enough for the context (clock, panel header): keep it; otherwise the card.
            boolean roomy = h / scale >= 210;
            float focusY = surface == Controls.Surface.KEYGUARD ? (roomy ? 150 : (card.top + pillRect().bottom) / 2)
                    : surface == Controls.Surface.SHADE ? (roomy ? 122 : card.centerY()) : card.centerY();
            oy = Math.max(h - shPx, Math.min(0, h / 2f - focusY * scale));
        }
        canvas.translate(ox, oy);
        canvas.drawRenderNode(scene);
        if (focus != Controls.Channel.NONE && !comparing) magnifier(canvas, sw, shPx, scale);
        canvas.restore();
        chip(canvas, comparing ? "Antes" : before != null && holdToCompare ? "Simulação · segure para comparar" : badge, w, h, comparing);
    }

    private void chip(Canvas c, String label, int w, int h, boolean strong) {
        text.setTextSize(10.5f * density);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        float tw = text.measureText(label), pad = 9 * density, ch = 22 * density;
        // Bottom-right on the pop-up; top-right where the lockscreen pill or shade cards sit below.
        boolean top = !zoomOnly && surface != Controls.Surface.HEADSUP;
        float y0 = top ? 8 * density : h - ch - 8 * density;
        RectF r = new RectF(w - tw - 2 * pad - 8 * density, y0, w - 8 * density, y0 + ch);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(strong ? 0xee80dfe9 : 0xb3111822);
        c.drawRoundRect(r, ch / 2, ch / 2, paint);
        text.setColor(strong ? 0xff05292f : 0xffe9f1fa);
        c.drawText(label, r.left + pad, r.centerY() + 3.6f * density, text);
        text.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
    }

    private void magnifier(Canvas canvas, int sw, int shPx, float scale) {
        PointF p = focusPoint();
        float radius = Math.min(sw, shPx) * .2f, z = edgeChannel() ? 3.4f : 2.4f;
        // On the side away from what it magnifies, so it never covers its own subject.
        boolean right = p.x < W / 2;
        float cx = right ? sw - radius - 10 * density : radius + 10 * density, cy = radius + 10 * density;
        if (surface == Controls.Surface.KEYGUARD) cy = shPx - radius - 40 * density;
        canvas.save();
        Path circle = new Path();
        circle.addCircle(cx, cy, radius, Path.Direction.CW);
        canvas.clipPath(circle);
        canvas.translate(cx, cy);
        canvas.scale(z, z);
        canvas.translate(-p.x * scale, -p.y * scale);
        canvas.drawRenderNode(scene);
        canvas.restore();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2 * density);
        paint.setColor(0xddffffff);
        canvas.drawCircle(cx, cy, radius, paint);
        paint.setStrokeWidth(1 * density);
        paint.setColor(0x55000000);
        canvas.drawCircle(cx, cy, radius + 1.5f * density, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    /** Thin effects at the outline: magnified more. */
    private boolean edgeChannel() {
        switch (focus) {
            case RIM: case OUTLINE: case EDGE_SHADOW: case GLINT: case LIGHT_ANGLE: case LENS: case OPTICS: return true;
            default: return false;
        }
    }

    /** Where in the sample a channel shows, in sample units. */
    PointF focusPoint() {
        RectF card = cardRect(0);
        switch (focus) {
            case RIM:
            case OUTLINE:
                // The upper-right edge: outline and its glow, with no text in the way.
                return new PointF(card.right - 6, card.top + 22);
            case EDGE_SHADOW:
                return new PointF(card.right - 14, card.bottom - 10);
            case GLINT:
            case OPTICS:
            case LIGHT_ANGLE:
                PreviewParams p = params(values, card);
                float[] g = glintPoint(card, surface == Controls.Surface.KEYGUARD ? 22 : 24, p.lightTurn);
                return new PointF(g[0], g[1]);
            case SHEEN:
                return new PointF(card.left + 40, card.top + 12);
            case LENS:
                return new PointF(card.left + 6, card.top + card.height() * .5f);
            case PILL:
            case PILL_BLUR:
                RectF pill = pillRect();
                return new PointF(pill.centerX(), pill.centerY());
            case HEADER_GAP:
                return new PointF(card.left + 60, card.top + 26);
            case PANEL_DIM:
                return new PointF(W * .5f, card.bottom + 26);
            default:
                return new PointF(card.centerX(), card.centerY());
        }
    }

    RectF cardRect(int index) {
        switch (surface) {
            case KEYGUARD:
                return new RectF(12, 136, W - 12, 136 + 86);
            case SHADE:
                return index == 0 ? new RectF(10, 108, W - 10, 108 + 118) : new RectF(10, 230, W - 10, 230 + 46);
            default:
                return new RectF(10, 34, W - 10, 34 + 130 + headerGap(values));
        }
    }

    RectF pillRect() {
        return new RectF(W / 2 - 46, 232, W / 2 + 46, 258);
    }

    private float headerGap(Function<String, String> f) {
        return PreviewParams.n(f, "headergap");
    }

    private RenderNode node() {
        if (used == pool.size()) pool.add(new RenderNode("preview-glass-" + used));
        return pool.get(used++);
    }

    private void drawScene(Canvas c, Function<String, String> f, boolean effects, float scale) {
        used = 0;
        // Backdrop; the shade draws its own blurred panel over it.
        c.drawBitmap(wallpaper, null, new RectF(0, 0, W, 320), null);
        boolean dark = !lightTheme;
        int fg = dark ? 0xfff3f6fb : 0xff17202b, fgMuted = dark ? 0xccdfe6ef : 0xcc2e3a49;
        switch (surface) {
            case KEYGUARD: {
                text(c, "12:30", 24, 74, 50, 0xfff7fbff, true, true);
                text(c, "segunda-feira, 27 de outubro", 26, 98, 12.5f, 0xeef7fbff, false, true);
                PreviewParams p = params(f, cardRect(0));
                card(c, cardRect(0), p, effects, scale, false, fg, fgMuted);
                pill(c, p, effects, scale);
                break;
            }
            case SHADE: {
                PreviewParams p0 = params(f, cardRect(0));
                shadePanel(c, p0, effects, scale);
                text(c, "12:30", 16, 34, 22, fg(p0, true), true, false);
                text(c, "seg., 27 de out.", 84, 34, 12, fg(p0, false), false, false);
                for (int i = 0; i < 4; i++) {
                    float cx = 40 + i * 80;
                    paint.setColor(i == 0 ? 0xff80dfe9 : lightTheme ? 0x33102030 : 0x33ffffff);
                    c.drawRoundRect(cx - 28, 52, cx + 28, 92, 20, 20, paint);
                }
                card(c, cardRect(0), p0, effects, scale, false, fg, fgMuted);
                card(c, cardRect(1), params(f, cardRect(1)), effects, scale, true, fg, fgMuted);
                break;
            }
            default: {
                statusBar(c);
                card(c, cardRect(0), params(f, cardRect(0)), effects, scale, false, fg, fgMuted);
            }
        }
    }

    private int fg(PreviewParams p, boolean strong) {
        boolean darkText = lightTheme;
        if (p.panelDim > 0) darkText = false;
        return darkText ? (strong ? 0xff17202b : 0xcc2e3a49) : (strong ? 0xfff3f6fb : 0xccdfe6ef);
    }

    private PreviewParams params(Function<String, String> f, RectF card) {
        return PreviewParams.of(f, surface, lightTheme, luma(card));
    }

    private void statusBar(Canvas c) {
        boolean lightBg = backdrop == Backdrop.LIGHT || backdrop == Backdrop.GRID;
        int ink = lightBg ? 0xff17202b : 0xfff3f6fb;
        text(c, "12:30", 16, 18, 12, ink, true, false);
        paint.setColor(ink);
        c.drawRoundRect(W - 40, 9, W - 18, 17, 2.5f, 2.5f, paint);
        c.drawCircle(W - 52, 13, 3, paint);
    }

    private void shadePanel(Canvas c, PreviewParams p, boolean effects, float scale) {
        // SystemUI blurs the whole backdrop under the panel; the card blur comes on top.
        if (effects) {
            panel.setPosition(0, 0, Math.round(W * scale), Math.round(320 * scale));
            RecordingCanvas r = panel.beginRecording();
            r.scale(scale, scale);
            r.drawBitmap(wallpaper, null, new RectF(0, 0, W, 320), null);
            panel.endRecording();
            panel.setRenderEffect(Lens.blur(28 * scale));
            c.save();
            c.scale(1 / scale, 1 / scale);
            c.drawRenderNode(panel);
            c.restore();
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(lightTheme ? 0x66f4f6fa : 0x8c0b1018);
        c.drawRect(0, 0, W, 320, paint);
        if (p.panelDim > 0) {
            paint.setColor(Math.round(255 * p.panelDim) << 24);
            c.drawRect(0, 0, W, 320, paint);
        }
    }

    private void pill(Canvas c, PreviewParams p, boolean effects, float scale) {
        RectF r = pillRect();
        float rad = r.height() / 2;
        if (p.nativeCard || !p.pillGlass) {
            paint.setColor(0x99778398);
            c.drawRoundRect(r, rad, rad, paint);
        } else {
            glass(c, r, rad, p.pillBlurPx, 0, 0, false, effects, scale, null);
            paint.setColor(0x40ffffff);
            c.drawRoundRect(r, rad, rad, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(.8f);
            paint.setColor(0x66ffffff);
            c.drawRoundRect(r, rad, rad, paint);
            paint.setStyle(Paint.Style.FILL);
        }
        paint.setColor(0xfff7fbff);
        for (int i = 0; i < 3; i++) c.drawCircle(r.left + 22 + i * 16, r.centerY(), 4.2f, paint);
        text(c, "+3", r.right - 26, r.centerY() + 4, 11, 0xfff7fbff, true, false);
    }

    /** Blurred, saturated, optionally refracted backdrop inside a rounded rect. */
    private void glass(Canvas c, RectF r, float radius, float blurPx, float lens, float coreBlur,
                       boolean band, boolean effects, float scale, PreviewParams p) {
        Path clip = new Path();
        clip.addRoundRect(r, radius, radius, Path.Direction.CW);
        c.save();
        c.clipPath(clip);
        if (!effects) {
            c.drawBitmap(wallpaper, null, new RectF(0, 0, W, 320), null);
            c.restore();
            return;
        }
        float margin = 40;
        int nw = Math.round((r.width() + 2 * margin) * scale), nh = Math.round((r.height() + 2 * margin) * scale);
        RenderNode glass = node();
        glass.setPosition(0, 0, nw, nh);
        RecordingCanvas rec = glass.beginRecording(nw, nh);
        rec.scale(scale, scale);
        rec.translate(margin - r.left, margin - r.top);
        Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG);
        if (p != null) bmp.setColorFilter(colorFilter(p));
        rec.drawBitmap(wallpaper, null, new RectF(0, 0, W, 320), bmp);
        glass.endRecording();
        RenderEffect effect = Lens.blur(blurPx / DEVICE * scale);
        if (lens > 0) {
            float bevel = 17.5f * scale;
            RenderEffect bend = Lens.effect(margin * scale, margin * scale, (margin + r.width()) * scale,
                    (margin + r.height()) * scale, radius * scale, bevel, lens * (band ? .85f : 1f), .06f);
            // The lens bends the blurred image, as the compositor bends its blurred body.
            effect = RenderEffect.createChainEffect(bend, effect);
        }
        glass.setRenderEffect(band ? Lens.blur(blurPx / DEVICE * scale) : effect);
        c.save();
        c.translate(r.left - margin, r.top - margin);
        c.scale(1 / scale, 1 / scale);
        c.drawRenderNode(glass);
        c.restore();
        if (band && lens > 0 && p != null) {
            // Standard mode: a captured, lightly blurred band refracted at the rim over the body.
            RenderNode ring = node();
            ring.setPosition(0, 0, nw, nh);
            RecordingCanvas rr = ring.beginRecording(nw, nh);
            rr.scale(scale, scale);
            rr.translate(margin - r.left, margin - r.top);
            rr.drawBitmap(wallpaper, null, new RectF(0, 0, W, 320), bmp);
            ring.endRecording();
            float bevel = 17.5f * scale;
            ring.setRenderEffect(RenderEffect.createChainEffect(
                    Lens.effect(margin * scale, margin * scale, (margin + r.width()) * scale, (margin + r.height()) * scale,
                            radius * scale, bevel, lens, .06f),
                    Lens.blur(p.bandBlurPx / DEVICE * scale)));
            Path inner = new Path();
            float inset = 9;
            inner.addRoundRect(new RectF(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset),
                    radius - inset, radius - inset, Path.Direction.CW);
            c.save();
            c.clipOutPath(inner);
            c.translate(r.left - margin, r.top - margin);
            c.scale(1 / scale, 1 / scale);
            c.drawRenderNode(ring);
            c.restore();
        }
        if (coreBlur > 0) {
            // Compositor core: a stronger blur inside the bevel.
            float inset = 17.5f;
            RectF in = new RectF(r.left + inset, r.top + inset * .6f, r.right - inset, r.bottom - inset * .6f);
            Path ic = new Path();
            ic.addRoundRect(in, Math.max(4, radius - inset), Math.max(4, radius - inset), Path.Direction.CW);
            RenderNode core = node();
            core.setPosition(0, 0, nw, nh);
            RecordingCanvas cr = core.beginRecording(nw, nh);
            cr.scale(scale, scale);
            cr.translate(margin - r.left, margin - r.top);
            cr.drawBitmap(wallpaper, null, new RectF(0, 0, W, 320), bmp);
            core.endRecording();
            core.setRenderEffect(Lens.blur(Math.max(blurPx, coreBlur) / DEVICE * scale * 1.6f));
            c.save();
            c.clipPath(ic);
            c.translate(r.left - margin, r.top - margin);
            c.scale(1 / scale, 1 / scale);
            c.drawRenderNode(core);
            c.restore();
        }
        c.restore();
    }

    private ColorFilter colorFilter(PreviewParams p) {
        ColorMatrix m = new ColorMatrix();
        m.setSaturation(p.saturation);
        if (p.flatten > 0 || p.highlight > 0) {
            // Pull luminance toward the level (flatten) and lift highlights (tone curve).
            float k = 1 - Math.min(.8f, p.flatten * .9f), add = (1 - k) * p.flattenLevel * 255 + p.highlight * 40;
            ColorMatrix flat = new ColorMatrix(new float[] {k, 0, 0, 0, add, 0, k, 0, 0, add, 0, 0, k, 0, add, 0, 0, 0, 1, 0});
            m.postConcat(flat);
        }
        if (p.cool > 0) {
            ColorMatrix cool = new ColorMatrix(new float[] {1 - .12f * p.cool, 0, 0, 0, 0, 0, 1, 0, 0, 6 * p.cool, 0, 0, 1, 0, 26 * p.cool, 0, 0, 0, 1, 0});
            m.postConcat(cool);
        }
        return new ColorMatrixColorFilter(m);
    }

    private void card(Canvas c, RectF r, PreviewParams p, boolean effects, float scale, boolean collapsed, int fg, int fgMuted) {
        float radius = surface == Controls.Surface.KEYGUARD ? 22 : 24;
        boolean darkText;
        if (p.nativeCard) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(lightTheme ? 0xfff4f5f8 : 0xff262d38);
            c.drawRoundRect(r, radius, radius, paint);
            darkText = lightTheme;
        } else {
            glass(c, r, radius, p.blurPx, p.lens, p.coreBlurPx, p.lensBand, effects, scale, p);
            paint.setStyle(Paint.Style.FILL);
            Path clip = new Path();
            clip.addRoundRect(r, radius, radius, Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            if (p.region != 0) { paint.setColor(p.region); c.drawRect(r, paint); }
            if (p.darkFill > 0) { paint.setColor(Math.round(255 * Math.min(1, p.darkFill)) << 24); c.drawRect(r, paint); }
            if (p.veil != 0) { paint.setColor(p.veil); c.drawRect(r, paint); }
            if (p.coreDim > 0) {
                paint.setColor(Math.round(255 * Math.min(.6f, p.coreDim * 3)) << 24);
                c.drawRoundRect(r.left + 17, r.top + 10, r.right - 17, r.bottom - 10, 10, 10, paint);
            }
            if (p.sheen > 0) {
                paint.setShader(new LinearGradient(0, r.top, 0, r.top + r.height() * .6f,
                        Color.argb(Math.round(255 * Math.min(1, p.sheen * p.optics * 4)), 255, 255, 255), 0, Shader.TileMode.CLAMP));
                c.drawRect(r, paint);
                paint.setShader(null);
            }
            c.restore();
            edges(c, r, radius, p);
            darkText = p.darkText;
        }
        content(c, r, collapsed, darkText, p);
    }

    private void edges(Canvas c, RectF r, float radius, PreviewParams p) {
        if (!p.edges) return;
        paint.setStyle(Paint.Style.STROKE);
        float optics = Math.max(0, p.optics);
        if (p.rim > 0) {
            float a = Math.min(1f, .16f * p.rim * optics);
            RectF in = new RectF(r);
            in.inset(.5f, .5f);
            // The lit line, brighter at the top-left and bottom-right like the device's edge light.
            paint.setStrokeWidth(.7f + .25f * p.rim);
            paint.setShader(new SweepGradient(r.centerX(), r.centerY(),
                    new int[] {Color.argb(Math.round(255 * a * .45f), 255, 255, 255), Color.argb(Math.round(255 * a), 255, 255, 255),
                            Color.argb(Math.round(255 * a * .45f), 255, 255, 255), Color.argb(Math.round(255 * a * .8f), 255, 255, 255),
                            Color.argb(Math.round(255 * a * .45f), 255, 255, 255)}, null));
            c.drawRoundRect(in, radius, radius, paint);
            // Its soft inner glow, whose width follows the rim width (sf.kgglow on the lockscreen).
            float band = 2.5f + 3f * p.rimWidth;
            RectF glow = new RectF(r);
            glow.inset(band / 2 + .8f, band / 2 + .8f);
            paint.setStrokeWidth(band);
            paint.setShader(null);
            paint.setColor(Color.argb(Math.round(255 * Math.min(.5f, a * .32f)), 255, 255, 255));
            c.drawRoundRect(glow, radius - band / 2, radius - band / 2, paint);
            paint.setShader(null);
        }
        if (p.edgeShadow > 0 && p.lightGlass) {
            paint.setStrokeWidth(3f);
            paint.setColor(Color.argb(Math.round(Math.min(255, 40 * p.edgeShadow)), 0, 0, 0));
            RectF in = new RectF(r);
            in.inset(1.8f, 1.8f);
            c.drawArc(new RectF(in.right - 2 * radius, in.bottom - 2 * radius, in.right, in.bottom), 0, 90, false, paint);
            c.drawLine(in.left + radius, in.bottom, in.right - radius, in.bottom, paint);
            c.drawLine(in.right, in.top + radius, in.right, in.bottom - radius, paint);
        }
        if (p.outline > 0) {
            paint.setStrokeWidth(1.5f);
            paint.setColor(Color.argb(Math.round(Math.min(255, 120 * p.outline)), 255, 255, 255));
            c.drawRoundRect(r, radius, radius, paint);
            // Light bent around the contour: a soft band just inside it.
            RectF band = new RectF(r);
            band.inset(2.6f, 2.6f);
            paint.setStrokeWidth(3.2f);
            paint.setColor(Color.argb(Math.round(Math.min(160, 55 * p.outline)), 255, 255, 255));
            c.drawRoundRect(band, radius - 2.6f, radius - 2.6f, paint);
        }
        if (p.glint > 0) {
            float a = Math.min(1f, p.glint * Math.max(.4f, optics));
            float[] g = glintPoint(r, radius, p.lightTurn);
            c.save();
            Path clip = new Path();
            clip.addRoundRect(r, radius, radius, Path.Direction.CW);
            c.clipPath(clip);
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new RadialGradient(g[0], g[1], 30, Color.argb(Math.round(150 * a), 255, 255, 255), 0, Shader.TileMode.CLAMP));
            c.drawCircle(g[0], g[1], 30, paint);
            paint.setShader(null);
            // A short bright stroke along the edge at the lit point.
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.4f + 2.2f * a);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(Color.argb(Math.round(235 * a), 255, 255, 255));
            float tx = g[3], ty = -g[2], len = 13;
            float ex = g[0] + g[2] * 3.2f, ey = g[1] + g[3] * 3.2f;
            c.drawLine(ex - tx * len, ey - ty * len, ex + tx * len, ey + ty * len, paint);
            c.restore();
            paint.setStrokeCap(Paint.Cap.BUTT);
        }
        paint.setStyle(Paint.Style.FILL);
    }

    /**
     * The lit point inside a card for a light turned {@code turn} degrees from the upper left:
     * {x, y, outward normal x, outward normal y}, a little inside the rounded outline.
     */
    static float[] glintPoint(RectF r, float radius, float turn) {
        double phi = Math.toRadians(225 + turn);
        float dx = (float) Math.cos(phi), dy = (float) Math.sin(phi);
        float hw = r.width() / 2 - radius * .55f, hh = r.height() / 2 - radius * .55f;
        float t = Math.min(Math.abs(dx) < 1e-4 ? Float.MAX_VALUE : hw / Math.abs(dx), Math.abs(dy) < 1e-4 ? Float.MAX_VALUE : hh / Math.abs(dy));
        float x = r.centerX() + dx * t, y = r.centerY() + dy * t;
        boolean side = Math.abs(dx) * hh > Math.abs(dy) * hw;
        float nx = side ? Math.signum(dx) : 0, ny = side ? 0 : Math.signum(dy);
        if (Math.abs(x - r.centerX()) >= hw - .5f && Math.abs(y - r.centerY()) >= hh - .5f) {
            nx = dx;
            ny = dy;
        }
        return new float[] {x, y, nx, ny};
    }

    private void content(Canvas c, RectF r, boolean collapsed, boolean darkText, PreviewParams p) {
        int ink = darkText ? 0xff17202b : 0xfff3f6fb, muted = darkText ? 0xcc364352 : 0xccd9e1ec,
                action = darkText ? 0xff0d6f7d : 0xff8de6ef;
        float x = r.left + 16, y = r.top + 22;
        paint.setColor(0xff7fdfe9);
        c.drawRoundRect(x, y - 10, x + 13, y + 3, 4, 4, paint);
        text(c, "Liquid Glass · agora", x + 20, y, 11.5f, muted, false, false);
        if (collapsed) {
            text(c, TestNotificationModel.TITLE, x, y + 19, 14, ink, true, false);
            return;
        }
        float gap = surface == Controls.Surface.KEYGUARD ? 0 : p.headerGapDp;
        y += 22 + gap;
        text(c, TestNotificationModel.TITLE, x, y, 15.5f, ink, true, false);
        y += 19;
        text(c, "Teste expandido de vidro — expanda para comparar.", x, y, 12.5f, muted, false, false);
        if (surface == Controls.Surface.KEYGUARD) return;
        y += 17;
        text(c, "Compare desfoque, bordas e contraste.", x, y, 12.5f, muted, false, false);
        y += 30;
        text(c, TestNotificationModel.OPEN, x, y, 13, action, true, false);
        text(c, TestNotificationModel.DISMISS, x + 120, y, 13, action, true, false);
    }

    private void text(Canvas c, String s, float x, float y, float size, int color, boolean medium, boolean shadow) {
        text.setTextSize(size);
        text.setColor(color);
        text.setTypeface(Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        if (shadow) text.setShadowLayer(3, 0, 1, 0x55000000);
        c.drawText(s, x, y, text);
        text.clearShadowLayer();
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(holdStart);
        scene.discardDisplayList();
        for (RenderNode n : pool) n.discardDisplayList();
        panel.discardDisplayList();
        if (wallpaper != null) {
            wallpaper.recycle();
            wallpaper = null;
        }
        super.onDetachedFromWindow();
    }
}
