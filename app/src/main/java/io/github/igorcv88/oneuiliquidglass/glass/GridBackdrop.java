package io.github.igorcv88.oneuiliquidglass.glass;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.View;
import android.view.WindowManager;
import java.lang.ref.WeakReference;

/**
 * Synthetic backdrop (debug.oulg.backdrop=grid): a numbered grid in screen coordinates, mapped
 * exactly like a capture. It shows the refraction profile, its direction and the mapping without
 * depending on what an app draws: lines every 40 px, labels every 200 px.
 */
public final class GridBackdrop implements SampledBackdrop {
    private static CaptureHub.Frame shared;
    private final WeakReference<View> view;
    private final CaptureHub.Frame frame;

    public GridBackdrop(View view) {
        this.view = new WeakReference<>(view);
        if (shared == null) shared = build(view);
        frame = shared;
    }

    private static CaptureHub.Frame build(View v) {
        WindowManager wm = v.getContext().getSystemService(WindowManager.class);
        Rect display = wm == null ? new Rect(0, 0, 1440, 3120) : wm.getMaximumWindowMetrics().getBounds();
        float s = CaptureHub.SCALE;
        Bitmap b = Bitmap.createBitmap(Math.max(1, Math.round(display.width() * s)), Math.max(1, Math.round(display.height() * s)), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(Color.rgb(36, 44, 66));
        Paint thin = new Paint(Paint.ANTI_ALIAS_FLAG), thick = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
        thin.setColor(Color.rgb(150, 160, 190)); thin.setStrokeWidth(1f);
        thick.setColor(Color.rgb(255, 196, 64)); thick.setStrokeWidth(2f);
        text.setColor(Color.WHITE); text.setTextSize(14f);
        for (int x = 0; x <= display.width(); x += 40) c.drawLine(x * s, 0, x * s, b.getHeight(), x % 200 == 0 ? thick : thin);
        for (int y = 0; y <= display.height(); y += 40) c.drawLine(0, y * s, b.getWidth(), y * s, y % 200 == 0 ? thick : thin);
        for (int y = 0; y < display.height(); y += 200)
            for (int x = 0; x < display.width(); x += 200) c.drawText(x + "," + y, x * s + 3, y * s + 15, text);
        return new CaptureHub.Frame(b, new Rect(display), s, false);
    }

    @Override public String name() { return "grid"; }
    @Override public void update(int radiusPx, int tint, float[] radii) { }
    @Override public void draw(Canvas canvas, Rect bounds) { }
    @Override public void setAlpha(int alpha) { }
    @Override public void release() { }
    @Override public CaptureHub.Frame frame() { return frame; }
    @Override public View view() { return view.get(); }
    @Override public boolean unavailable() { return false; }
}
