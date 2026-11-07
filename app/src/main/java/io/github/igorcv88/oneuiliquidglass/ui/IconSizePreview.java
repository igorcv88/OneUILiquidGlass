package io.github.igorcv88.oneuiliquidglass.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import io.github.igorcv88.oneuiliquidglass.R;
import io.github.igorcv88.oneuiliquidglass.ui.kit.Theme;

/** Draw-only preview: displays effective and pending sizes without touching SystemUI. */
final class IconSizePreview extends View {
    private final Theme theme;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float currentStatus = 1f, currentNotification = 1f;
    private float selectedStatus = 1f, selectedNotification = 1f;
    private int currentMaximum, selectedMaximum;

    IconSizePreview(Context context, Theme theme) {
        super(context);
        this.theme = theme;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    void update(String beforeStatus, String afterStatus, String beforeNotification,
                String afterNotification, String beforeMax, String afterMax) {
        currentStatus = fraction(beforeStatus);
        selectedStatus = fraction(afterStatus);
        currentNotification = fraction(beforeNotification);
        selectedNotification = fraction(afterNotification);
        currentMaximum = number(beforeMax);
        selectedMaximum = number(afterMax);
        setContentDescription(getResources().getString(R.string.icons_preview) + ". "
                + getResources().getString(R.string.icons_preview_live) + " "
                + Math.round(currentStatus * 100) + "% / " + Math.round(currentNotification * 100)
                + "%. " + getResources().getString(R.string.icons_preview_edited) + " "
                + Math.round(selectedStatus * 100) + "% / " + Math.round(selectedNotification * 100) + "%");
        invalidate();
    }

    private static float fraction(String v) {
        try { return Math.max(.4f, Math.min(1f, Float.parseFloat(v))); }
        catch (RuntimeException ignored) { return 1f; }
    }

    private static int number(String v) {
        try { return Integer.parseInt(v); }
        catch (RuntimeException ignored) { return 0; }
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), theme.dp(172));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float half = width / 2f;
        column(canvas, half / 2f, currentStatus, currentNotification, currentMaximum,
                getResources().getString(R.string.icons_preview_live));
        column(canvas, half + half / 2f, selectedStatus, selectedNotification, selectedMaximum,
                getResources().getString(R.string.icons_preview_edited));
        paint.setColor(theme.inkFaint);
        paint.setStrokeWidth(theme.dpf(1));
        canvas.drawLine(half, theme.dp(8), half, getHeight() - theme.dp(8), paint);
    }

    private void column(Canvas canvas, float x, float status, float notification,
                        int maximum, String title) {
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        paint.setTextSize(theme.dpf(12));
        paint.setColor(theme.inkMuted);
        canvas.drawText(title, x, theme.dp(20), paint);

        // Status icon: multiple compact glyphs and a count hint, with unchanged slot spacing.
        int visible = maximum == 0 ? 4 : Math.max(1, Math.min(6, maximum));
        float spacing = theme.dpf(22);
        float start = x - (visible - 1) * spacing / 2f;
        for (int i = 0; i < visible; i++) icon(canvas, start + i * spacing, theme.dpf(55),
                theme.dpf(17) * status, theme.ink);
        paint.setColor(theme.inkMuted);
        paint.setTextSize(theme.dpf(10));
        canvas.drawText(maximum == 0 ? "Auto" : "Max " + maximum, x, theme.dp(83), paint);

        paint.setColor(theme.inkFaint);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(new RectF(x - theme.dp(42), theme.dp(96), x + theme.dp(42),
                theme.dp(147)), theme.dpf(20), theme.dpf(20), paint);
        icon(canvas, x, theme.dpf(121), theme.dpf(30) * notification, theme.ink);
        paint.setColor(theme.inkMuted);
        paint.setTextSize(theme.dpf(10));
        canvas.drawText(Math.round(notification * 100) + "%", x, theme.dp(164), paint);
    }

    private void icon(Canvas canvas, float x, float y, float size, int color) {
        // Same vector drawing at each scale: a bell-like silhouette, never bitmap interpolation.
        float r = size * .32f;
        paint.setColor(color);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(new RectF(x - r, y - size * .31f, x + r,
                y + size * .2f), r, r, paint);
        canvas.drawRoundRect(new RectF(x - size * .42f, y + size * .12f,
                x + size * .42f, y + size * .28f), size * .07f, size * .07f, paint);
        canvas.drawCircle(x, y + size * .32f, size * .09f, paint);
    }
}
