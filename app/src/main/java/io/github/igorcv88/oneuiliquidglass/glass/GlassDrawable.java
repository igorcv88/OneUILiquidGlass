package io.github.igorcv88.oneuiliquidglass.glass;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.drawable.Drawable;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;

/** Drawn only in the native notification background draw call. */
public final class GlassDrawable extends Drawable {
    private final BackgroundBlurBridge bridge;
    private final float density;
    private final GlassSpec spec;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF rect = new RectF();
    private final float[] radii = new float[8];
    private RuntimeShader shader;
    private Drawable nativeDrawable;
    private int alpha = 255;
    private boolean failed;
    private boolean configured;
    private int lastTint;
    public GlassDrawable(BackgroundBlurBridge bridge, float density, GlassSpec spec) {
        this.bridge = bridge; this.density = density; this.spec = spec;
        try { shader = new RuntimeShader(LiquidGlassShader.SOURCE); paint.setShader(shader); }
        catch (RuntimeException e) { Probe.error("SHADER_UNAVAILABLE", e); }
    }
    public boolean failed() { return failed; }
    public void configure(Drawable original, float[] shape, int tint) throws ReflectiveOperationException {
        nativeDrawable = original;
        boolean changed = !configured || lastTint != tint || !java.util.Arrays.equals(shape, radii);
        setAlpha(original.getAlpha());
        bridge.drawable.setVisible(true, false);
        if (changed) {
            System.arraycopy(shape, 0, radii, 0, 8);
            bridge.update(Math.round(spec.blurDp * density), tint, radii);
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
            bridge.drawable.setBounds(bounds);
            bridge.drawable.draw(canvas);
            if (shader != null) {
                shader.setFloatUniform("size", (float) bounds.width(), (float) bounds.height());
                shader.setFloatUniform("origin", (float) bounds.left, (float) bounds.top);
                shader.setFloatUniform("corners", radii[0], radii[2], radii[4], radii[6]);
                shader.setFloatUniform("density", density);
                shader.setFloatUniform("strength", spec.edgeStrength);
                canvas.drawRect(rect, paint);
            }
        } catch (RuntimeException e) {
            failed = true; bridge.release(); Probe.error("GLASS_DRAW_FAILED", e);
        } finally { canvas.restoreToCount(save); }
        if (failed && nativeDrawable != null) {
            nativeDrawable.setBounds(getBounds()); nativeDrawable.draw(canvas);
        }
    }
    @Override public void setAlpha(int value) {
        alpha = value; bridge.drawable.setAlpha(value); paint.setAlpha(value);
    }
    @Override public int getAlpha() { return alpha; }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    public void release() { bridge.release(); nativeDrawable = null; setCallback(null); }
}
