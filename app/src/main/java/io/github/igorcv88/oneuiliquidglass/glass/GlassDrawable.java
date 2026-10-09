package io.github.igorcv88.oneuiliquidglass.glass;

import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Perf;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;

/**
 * Drawn only in the native notification background draw call. Over a compositor or shade blur it
 * adds a veil and edge optics; over a {@link SampledBackdrop} it draws the whole opaque material
 * (refracted backdrop, veil, edge) with {@link LiquidGlassShader#REFRACT_SOURCE}.
 */
public final class GlassDrawable extends Drawable {
    private final Backdrop backdrop;
    private final float density;
    private final GlassSpec spec;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF rect = new RectF();
    private final float[] radii = new float[8];
    private RuntimeShader shader;
    private RuntimeShader refract;
    private RenderNode node;
    private RenderEffect blur;
    private float blurRadius = -1f;
    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final android.graphics.Matrix imageMatrix = new android.graphics.Matrix();
    private CaptureHub.Frame boundFrame;
    private final int[] screen = new int[2];
    private int tintColor;
    private Drawable nativeDrawable;
    private int alpha = 255;
    private boolean failed;
    private boolean configured;
    private int lastTint, fillColor, tuningGeneration;
    /** Touch feedback in place of the native pressed background: a light wash over the material. */
    private static final int PRESSED_WASH = 0x24ffffff;
    private boolean pressed;
    public GlassDrawable(Backdrop backdrop, float density, GlassSpec spec) {
        this.backdrop = backdrop; this.density = density; this.spec = spec;
        if (backdrop instanceof SampledBackdrop && !refractBroken) {
            try { refract = new RuntimeShader(LiquidGlassShader.REFRACT_SOURCE); }
            catch (RuntimeException e) {
                // Compiled on the device only: keep the compiler message, and stop choosing capture.
                refractBroken = true;
                Probe.log("REFRACT_SHADER_FAILED", "error=" + e.getClass().getSimpleName() + " message=" + e.getMessage());
            }
        }
        try { shader = new RuntimeShader(LiquidGlassShader.SOURCE); paint.setShader(shader); }
        catch (RuntimeException e) { Probe.error("SHADER_UNAVAILABLE", e); }
    }
    public boolean failed() { return failed; }
    public void setPressed(boolean on) { if (pressed != on) { pressed = on; invalidateSelf(); } }
    /** See {@link Backdrop#reassert()}. */
    public void reassertBackdrop() { if (!failed) backdrop.reassert(); }
    /** Compositor-blur body with a captured lens band (see {@link HybridBackdrop}). */
    public boolean hybrid() { return backdrop instanceof HybridBackdrop; }
    /** A sampled backdrop that can no longer be used: the owner swaps to another backdrop kind. */
    public boolean stale() {
        return backdrop instanceof SampledBackdrop && (refract == null || ((SampledBackdrop) backdrop).unavailable());
    }
    private static boolean refractBroken;
    /** False once the refraction program failed to compile on this device. */
    public static boolean refractionAvailable() { return !refractBroken; }
    /** fillColor is drawn by the material; blurColor is handed to the backdrop. */
    public void configure(Drawable original, float[] shape, int fillColor, int tint) throws ReflectiveOperationException {
        nativeDrawable = original;
        boolean changed = !configured || lastTint != tint || !java.util.Arrays.equals(shape, radii)
                || tuningGeneration != Tuning.generation;
        this.fillColor = fillColor;
        this.tintColor = tint;
        // Over a capture the native alpha is Samsung's translucency, not a fade (row fades use view
        // alpha): honoring it would let the unrefracted app show through the refracted one.
        setAlpha(backdrop instanceof SampledBackdrop ? 255 : original.getAlpha());
        if (changed) {
            System.arraycopy(shape, 0, radii, 0, 8);
            backdrop.update(Math.round(spec.blurDp * density), tint, radii);
            configured = true; lastTint = tint; tuningGeneration = Tuning.generation;
        }
    }
    @Override public void draw(Canvas canvas) {
        long started = Perf.start();
        try { drawGlass(canvas); } finally { Perf.end(Perf.GLASS_DRAW, started); }
    }
    private void drawGlass(Canvas canvas) {
        if (failed) { if (nativeDrawable != null) { nativeDrawable.setBounds(getBounds()); nativeDrawable.draw(canvas); } return; }
        int save = canvas.save();
        try {
            Rect bounds = getBounds(); rect.set(bounds);
            clip.reset(); clip.addRoundRect(rect, radii, Path.Direction.CW);
            canvas.clipPath(clip);
            if (refract != null && drawSampled(canvas, bounds)) {
                if (backdrop instanceof HybridBackdrop) {
                    // The lens band is drawn; the body is the compositor blur: veil both alike.
                    fill.setColor(fillColor);
                    fill.setAlpha(android.graphics.Color.alpha(fillColor) * alpha / 255);
                    canvas.drawRect(rect, fill);
                }
                drawPressed(canvas);
                return;
            }
            backdrop.draw(canvas, bounds);
            fill.setColor(fillColor);
            fill.setAlpha(android.graphics.Color.alpha(fillColor) * alpha / 255);
            canvas.drawRect(rect, fill);
            drawPressed(canvas);
            if (shader != null) {
                edgeUniforms(shader, bounds);
                canvas.drawRect(rect, paint);
            }
        } catch (RuntimeException e) {
            failed = true; backdrop.release(); Probe.error("GLASS_DRAW_FAILED", e);
        } finally { canvas.restoreToCount(save); }
        if (failed && nativeDrawable != null) {
            nativeDrawable.setBounds(getBounds()); nativeDrawable.draw(canvas);
        }
    }
    private void drawPressed(Canvas canvas) {
        if (!pressed) return;
        fill.setColor(PRESSED_WASH);
        fill.setAlpha(android.graphics.Color.alpha(PRESSED_WASH) * alpha / 255);
        canvas.drawRect(rect, fill);
    }
    private void edgeUniforms(RuntimeShader s, Rect bounds) {
        s.setFloatUniform("size", (float) bounds.width(), (float) bounds.height());
        s.setFloatUniform("origin", (float) bounds.left, (float) bounds.top);
        s.setFloatUniform("corners", radii[0], radii[2], radii[4], radii[6]);
        s.setFloatUniform("bevel", GlassSpec.bevelPx(spec.rimDp, density, bounds.width(), bounds.height()));
        s.setFloatUniform("hair", GlassSpec.hairPx(spec.hairDp, density));
        s.setFloatUniform("fringe", spec.fringe);
        s.setFloatUniform("light", spec.lightX, spec.lightY);
        s.setFloatUniform("specular", spec.specular);
        s.setFloatUniform("shadow", spec.innerShadow);
    }
    /**
     * False until the first frame arrives; the caller then draws veil and edge alone.
     *
     * <p>The frame is drawn into a RenderNode covering the bounds plus a blur margin, mapped so node
     * pixels line up with the screen. The node's effect chain blurs it (Skia Gaussian, on the GPU at
     * render time) and then runs the refraction program with the blurred image as its input, so the
     * shader displaces frosted pixels instead of approximating a blur with a few taps.</p>
     */
    private boolean drawSampled(Canvas canvas, Rect bounds) {
        if (!(canvas instanceof RecordingCanvas)) return false;
        SampledBackdrop sampled = (SampledBackdrop) backdrop;
        CaptureHub.Frame frame = sampled.frame();
        android.view.View view = sampled.view();
        if (frame == null || view == null || frame.bitmap.isRecycled()) return false;
        if (frame != boundFrame) {
            BitmapShader image = new BitmapShader(frame.bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            image.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
            imagePaint.setShader(image);
            frame.retain();
            unbind(view);
            boundFrame = frame;
        }
        Tuning t = Tuning.get();
        boolean hybrid = backdrop instanceof HybridBackdrop;
        float radius = hybrid ? t.rimBlur : t.blur;
        view.getLocationOnScreen(screen);
        int margin = (int) Math.ceil(radius * 2f) + 2;
        int w = bounds.width() + 2 * margin, h = bounds.height() + 2 * margin;
        if (node == null) node = new RenderNode("oulg-glass");
        node.setPosition(0, 0, w, h);
        RecordingCanvas content = node.beginRecording(w, h);
        try {
            // node (0,0) is view-local (bounds.left - margin, bounds.top - margin); bitmap px = (screen - crop) * scale.
            // CLAMP extends the edge pixels where the crop stops short of the node (top of the screen).
            imageMatrix.setScale(1f / frame.scale, 1f / frame.scale);
            imageMatrix.postTranslate(frame.crop.left - (screen[0] + bounds.left - margin), frame.crop.top - (screen[1] + bounds.top - margin));
            imagePaint.getShader().setLocalMatrix(imageMatrix);
            content.drawRect(0f, 0f, w, h, imagePaint);
        } finally { node.endRecording(); }

        edgeUniforms(refract, bounds);
        refract.setFloatUniform("origin", (float) margin, (float) margin);
        refract.setFloatUniform("refractScale", t.refract);
        refract.setFloatUniform("lensRatio", t.lens);
        refract.setFloatUniform("profile", t.profile.equals("snell") ? 1f : 0f);
        refract.setFloatUniform("rimOnly", hybrid ? 1f : 0f);
        refract.setFloatUniform("ior", t.ior);
        // The hybrid rim must match the compositor body's colour, which has no saturation boost.
        refract.setFloatUniform("saturation", hybrid ? 1f : t.saturation);
        refract.setFloatUniform("dispersion", t.dispersion);
        int override = hybrid ? t.semAlpha : t.tintAlpha;
        int veil = override >= 0 ? (tintColor & 0x00ffffff) | (override << 24) : tintColor;
        premultiplied(refract, "tint", veil);
        premultiplied(refract, "fillColor", hybrid ? 0 : fillColor);
        RenderEffect optics = RenderEffect.createRuntimeShaderEffect(refract, "backdrop");
        if (radius >= 0.5f) {
            if (blur == null || blurRadius != radius) { blur = RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP); blurRadius = radius; }
            optics = RenderEffect.createChainEffect(optics, blur);
        }
        node.setRenderEffect(optics);
        int save = canvas.save();
        canvas.translate(bounds.left - margin, bounds.top - margin);
        ((RecordingCanvas) canvas).drawRenderNode(node);
        canvas.restoreToCount(save);
        return true;
    }
    /**
     * The previous frame stays in the display list being replaced until this recording is committed;
     * release it from the commit callback. A detached view gets a delay of several frames instead.
     */
    private void unbind(android.view.View view) {
        CaptureHub.Frame old = boundFrame;
        boundFrame = null;
        if (old == null) return;
        if (view != null && view.isAttachedToWindow()) view.getViewTreeObserver().registerFrameCommitCallback(old::release);
        else MAIN.postDelayed(old::release, 250);
    }
    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());
    private static void premultiplied(RuntimeShader s, String name, int color) {
        float a = Color.alpha(color) / 255f;
        s.setFloatUniform(name, Color.red(color) / 255f * a, Color.green(color) / 255f * a, Color.blue(color) / 255f * a, a);
    }
    private int requestedAlpha = 255;
    private float fade = 1f;
    @Override public void setAlpha(int value) {
        requestedAlpha = value;
        alpha = Math.round(value * fade); backdrop.setAlpha(alpha); paint.setAlpha(alpha);
    }
    /**
     * SystemUI fades a card's blur with its content (setBlurAlphaFromParent / setContentAlpha)
     * without touching view alpha; the glass and its compositor blur region fade with it.
     */
    public void setFade(float f) {
        f = Math.max(0f, Math.min(1f, f));
        if (f == fade) return;
        fade = f;
        setAlpha(requestedAlpha);
        invalidateSelf();
    }
    @Override public int getAlpha() { return alpha; }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    public void release() {
        if (backdrop instanceof SampledBackdrop) unbind(((SampledBackdrop) backdrop).view());
        backdrop.release(); nativeDrawable = null; setCallback(null);
        if (node != null) { node.discardDisplayList(); node = null; }
    }
}
