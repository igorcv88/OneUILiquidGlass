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
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
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
    private final Paint refractPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private CaptureHub.Frame boundFrame;
    private final int[] screen = new int[2];
    private int tintColor;
    private Drawable nativeDrawable;
    private int alpha = 255;
    private boolean failed;
    private boolean configured;
    private int lastTint, fillColor;
    public GlassDrawable(Backdrop backdrop, float density, GlassSpec spec) {
        this.backdrop = backdrop; this.density = density; this.spec = spec;
        if (backdrop instanceof SampledBackdrop && !refractBroken) {
            try { refract = new RuntimeShader(LiquidGlassShader.REFRACT_SOURCE); refractPaint.setShader(refract); }
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
        boolean changed = !configured || lastTint != tint || !java.util.Arrays.equals(shape, radii);
        this.fillColor = fillColor;
        this.tintColor = tint;
        // Over a capture the native alpha is Samsung's translucency, not a fade (row fades use view
        // alpha): honoring it would let the unrefracted app show through the refracted one.
        setAlpha(backdrop instanceof SampledBackdrop ? 255 : original.getAlpha());
        if (changed) {
            System.arraycopy(shape, 0, radii, 0, 8);
            backdrop.update(Math.round(spec.blurDp * density), tint, radii);
            configured = true; lastTint = tint;
        }
    }
    @Override public void draw(Canvas canvas) {
        if (failed) { if (nativeDrawable != null) { nativeDrawable.setBounds(getBounds()); nativeDrawable.draw(canvas); } return; }
        int save = canvas.save();
        try {
            Rect bounds = getBounds(); rect.set(bounds);
            clip.reset(); clip.addRoundRect(rect, radii, Path.Direction.CW);
            canvas.clipPath(clip);
            if (refract != null && drawSampled(canvas, bounds)) return;
            backdrop.draw(canvas, bounds);
            fill.setColor(fillColor);
            fill.setAlpha(android.graphics.Color.alpha(fillColor) * alpha / 255);
            canvas.drawRect(rect, fill);
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
    /** False until the first frame arrives; the caller then draws veil and edge alone. */
    private boolean drawSampled(Canvas canvas, Rect bounds) {
        SampledBackdrop sampled = (SampledBackdrop) backdrop;
        CaptureHub.Frame frame = sampled.frame();
        android.view.View view = sampled.view();
        if (frame == null || view == null || frame.bitmap.isRecycled()) return false;
        if (frame != boundFrame) {
            BitmapShader image = new BitmapShader(frame.bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            image.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
            refract.setInputShader("backdrop", image);
            frame.retain();
            unbind(view);
            boundFrame = frame;
        }
        Tuning t = Tuning.get();
        view.getLocationOnScreen(screen);
        edgeUniforms(refract, bounds);
        refract.setFloatUniform("bdOrigin", screen[0] - frame.crop.left, screen[1] - frame.crop.top);
        refract.setFloatUniform("bdScale", frame.scale);
        refract.setFloatUniform("bdSize", frame.bitmap.getWidth(), frame.bitmap.getHeight());
        refract.setFloatUniform("refractScale", t.refract);
        refract.setFloatUniform("ior", t.ior);
        refract.setFloatUniform("blurPx", t.blur);
        refract.setFloatUniform("saturation", t.saturation);
        refract.setFloatUniform("dispersion", t.dispersion);
        int veil = t.tintAlpha >= 0 ? (tintColor & 0x00ffffff) | (t.tintAlpha << 24) : tintColor;
        premultiplied(refract, "tint", veil);
        premultiplied(refract, "fillColor", fillColor);
        canvas.drawRect(rect, refractPaint);
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
    @Override public void setAlpha(int value) {
        alpha = value; backdrop.setAlpha(value); paint.setAlpha(value); refractPaint.setAlpha(value);
    }
    @Override public int getAlpha() { return alpha; }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); refractPaint.setColorFilter(filter); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    public void release() {
        if (backdrop instanceof SampledBackdrop) unbind(((SampledBackdrop) backdrop).view());
        backdrop.release(); nativeDrawable = null; setCallback(null);
    }
}
