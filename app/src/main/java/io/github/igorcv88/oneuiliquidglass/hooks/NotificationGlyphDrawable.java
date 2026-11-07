package io.github.igorcv88.oneuiliquidglass.hooks;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Full-resolution alpha mask, with artwork size independent of the ImageView's layout box. */
final class NotificationGlyphDrawable extends Drawable {
    private final Bitmap bitmap;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private float fraction;

    NotificationGlyphDrawable(Bitmap bitmap, float fraction) {
        this.bitmap = bitmap;
        scale(fraction);
    }

    void scale(float value) {
        float next = Math.max(.4f, Math.min(1f, value));
        if (Math.abs(next - fraction) < .001f) return;
        fraction = next;
        invalidateSelf();
    }

    @Override public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        float w = b.width() * fraction, h = b.height() * fraction;
        float x = b.left + (b.width() - w) / 2f;
        float y = b.top + (b.height() - h) / 2f;
        canvas.drawBitmap(bitmap, null, new RectF(x, y, x + w, y + h), paint);
    }

    @Override public int getIntrinsicWidth() { return bitmap.getWidth(); }
    @Override public int getIntrinsicHeight() { return bitmap.getHeight(); }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public ColorFilter getColorFilter() { return paint.getColorFilter(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
