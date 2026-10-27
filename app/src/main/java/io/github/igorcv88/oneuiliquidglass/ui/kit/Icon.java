package io.github.igorcv88.oneuiliquidglass.ui.kit;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * One outline icon set on a 24-unit grid: same 1.8 stroke, round caps and joins, optical size
 * 20 inside the 24 box. Drawn, not bitmaps, so every icon tints and scales the same way.
 */
public enum Icon {
    HOME, LAYERS, SETTINGS, POPUP, LOCK, SHADE, BELL, CHEVRON_RIGHT, CHEVRON_DOWN, CHEVRON_LEFT,
    RESET, INFO, STAR, STAR_FILLED, SEARCH, SUN, MOON, CLOSE, CHECK, MORE, WARNING, RESTART,
    BACKUP, COPY, LENS, CONTRAST, COMPARE, SHIELD, CODE, CLOCK, STACK, PLUS, MINUS, EDIT, PALETTE,
    PULSE, UNDO, DOT;

    private static final Paint STROKE = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Path P = new Path();
    private static final RectF R = new RectF();

    /** Draws into a 24x24 unit box already scaled by the caller. */
    public void draw(Canvas c, int color) {
        Paint p = STROKE;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.8f);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setColor(color);
        Path path = P;
        path.reset();
        switch (this) {
            case HOME:
                path.moveTo(3.5f, 10.5f);
                path.lineTo(12, 3.8f);
                path.lineTo(20.5f, 10.5f);
                path.moveTo(5.5f, 9.2f);
                path.lineTo(5.5f, 19);
                path.quadTo(5.5f, 20, 6.5f, 20);
                path.lineTo(9.8f, 20);
                path.lineTo(9.8f, 14.5f);
                path.lineTo(14.2f, 14.5f);
                path.lineTo(14.2f, 20);
                path.lineTo(17.5f, 20);
                path.quadTo(18.5f, 20, 18.5f, 19);
                path.lineTo(18.5f, 9.2f);
                c.drawPath(path, p);
                break;
            case LAYERS:
                c.drawRoundRect(4, 4, 20, 11, 3, 3, p);
                c.drawRoundRect(4, 13, 20, 20, 3, 3, p);
                break;
            case SETTINGS:
                for (int i = 0; i < 3; i++) {
                    float y = 6 + i * 6, knob = i == 1 ? 15 : i == 0 ? 8 : 11;
                    c.drawLine(4, y, knob - 2.2f, y, p);
                    c.drawLine(knob + 2.2f, y, 20, y, p);
                    c.drawCircle(knob, y, 2.2f, p);
                }
                break;
            case POPUP:
                c.drawRoundRect(6, 2.8f, 18, 21.2f, 3, 3, p);
                c.drawRoundRect(8.2f, 5.6f, 15.8f, 9.6f, 1.6f, 1.6f, p);
                break;
            case LOCK:
                c.drawRoundRect(5, 10.5f, 19, 20.5f, 3, 3, p);
                path.moveTo(8, 10.5f);
                path.lineTo(8, 7.5f);
                path.cubicTo(8, 2.5f, 16, 2.5f, 16, 7.5f);
                path.lineTo(16, 10.5f);
                c.drawPath(path, p);
                c.drawLine(12, 14.3f, 12, 16.7f, p);
                break;
            case SHADE:
                c.drawRoundRect(4, 3.5f, 20, 10, 2.6f, 2.6f, p);
                c.drawRoundRect(4, 12.5f, 20, 16.5f, 2, 2, p);
                c.drawLine(6.5f, 19.8f, 17.5f, 19.8f, p);
                break;
            case BELL:
                path.moveTo(6, 16.5f);
                path.lineTo(6, 11);
                path.cubicTo(6, 7.2f, 8.6f, 4.6f, 12, 4.6f);
                path.cubicTo(15.4f, 4.6f, 18, 7.2f, 18, 11);
                path.lineTo(18, 16.5f);
                path.lineTo(19.5f, 18);
                path.lineTo(4.5f, 18);
                path.close();
                c.drawPath(path, p);
                c.drawLine(10.2f, 20.6f, 13.8f, 20.6f, p);
                c.drawLine(12, 2.8f, 12, 4.6f, p);
                break;
            case CHEVRON_RIGHT:
                path.moveTo(9.5f, 6);
                path.lineTo(15.5f, 12);
                path.lineTo(9.5f, 18);
                c.drawPath(path, p);
                break;
            case CHEVRON_LEFT:
                path.moveTo(14.5f, 6);
                path.lineTo(8.5f, 12);
                path.lineTo(14.5f, 18);
                c.drawPath(path, p);
                break;
            case CHEVRON_DOWN:
                path.moveTo(6, 9.5f);
                path.lineTo(12, 15.5f);
                path.lineTo(18, 9.5f);
                c.drawPath(path, p);
                break;
            case RESET:
            case UNDO:
                R.set(5, 5, 19, 19);
                path.arcTo(R, 200, 290);
                c.drawPath(path, p);
                path.reset();
                path.moveTo(4.2f, 5.6f);
                path.lineTo(5.6f, 9.6f);
                path.lineTo(9.6f, 8.6f);
                c.drawPath(path, p);
                break;
            case INFO:
                c.drawCircle(12, 12, 8.5f, p);
                c.drawLine(12, 11, 12, 16.2f, p);
                dot(c, p, 12, 7.9f);
                break;
            case STAR:
            case STAR_FILLED:
                for (int i = 0; i < 10; i++) {
                    double a = Math.PI / 2 + i * Math.PI / 5;
                    float r = i % 2 == 0 ? 8.6f : 3.8f;
                    float x = 12 + (float) Math.cos(a) * r, y = 12.6f - (float) Math.sin(a) * r;
                    if (i == 0) path.moveTo(x, y);
                    else path.lineTo(x, y);
                }
                path.close();
                if (this == STAR_FILLED) p.setStyle(Paint.Style.FILL_AND_STROKE);
                c.drawPath(path, p);
                break;
            case SEARCH:
                c.drawCircle(10.8f, 10.8f, 6.2f, p);
                c.drawLine(15.4f, 15.4f, 20, 20, p);
                break;
            case SUN:
                c.drawCircle(12, 12, 4, p);
                for (int i = 0; i < 8; i++) {
                    double a = i * Math.PI / 4;
                    c.drawLine(12 + (float) Math.cos(a) * 6.8f, 12 + (float) Math.sin(a) * 6.8f,
                            12 + (float) Math.cos(a) * 8.6f, 12 + (float) Math.sin(a) * 8.6f, p);
                }
                break;
            case MOON:
                path.moveTo(19.5f, 14.2f);
                path.cubicTo(17, 19.5f, 9.5f, 20.5f, 6, 16);
                path.cubicTo(2.8f, 11.8f, 5, 5.2f, 10.2f, 4.2f);
                path.cubicTo(8.2f, 9.5f, 13.5f, 15.6f, 19.5f, 14.2f);
                c.drawPath(path, p);
                break;
            case CLOSE:
                c.drawLine(6.5f, 6.5f, 17.5f, 17.5f, p);
                c.drawLine(17.5f, 6.5f, 6.5f, 17.5f, p);
                break;
            case CHECK:
                path.moveTo(5, 12.5f);
                path.lineTo(9.6f, 17);
                path.lineTo(19, 7.2f);
                c.drawPath(path, p);
                break;
            case MORE:
                dot(c, p, 6, 12);
                dot(c, p, 12, 12);
                dot(c, p, 18, 12);
                break;
            case WARNING:
                path.moveTo(12, 4);
                path.lineTo(20.5f, 19);
                path.lineTo(3.5f, 19);
                path.close();
                c.drawPath(path, p);
                c.drawLine(12, 9.5f, 12, 13.6f, p);
                dot(c, p, 12, 16.4f);
                break;
            case RESTART:
                R.set(4.8f, 4.8f, 19.2f, 19.2f);
                path.arcTo(R, -60, 300);
                c.drawPath(path, p);
                path.reset();
                path.moveTo(15.8f, 3.6f);
                path.lineTo(15.8f, 7.8f);
                path.lineTo(19.8f, 7.8f);
                c.drawPath(path, p);
                break;
            case BACKUP:
                c.drawRoundRect(4, 4, 20, 9, 2, 2, p);
                path.moveTo(5.5f, 9);
                path.lineTo(5.5f, 18.5f);
                path.quadTo(5.5f, 20, 7, 20);
                path.lineTo(17, 20);
                path.quadTo(18.5f, 20, 18.5f, 18.5f);
                path.lineTo(18.5f, 9);
                c.drawPath(path, p);
                c.drawLine(10, 13, 14, 13, p);
                break;
            case COPY:
                c.drawRoundRect(8.5f, 8.5f, 19.5f, 19.5f, 2.6f, 2.6f, p);
                path.moveTo(15.5f, 8.5f);
                path.lineTo(15.5f, 6.5f);
                path.quadTo(15.5f, 4.5f, 13.5f, 4.5f);
                path.lineTo(6.5f, 4.5f);
                path.quadTo(4.5f, 4.5f, 4.5f, 6.5f);
                path.lineTo(4.5f, 13.5f);
                path.quadTo(4.5f, 15.5f, 6.5f, 15.5f);
                path.lineTo(8.5f, 15.5f);
                c.drawPath(path, p);
                break;
            case LENS:
                c.drawCircle(12, 12, 8.5f, p);
                R.set(6.5f, 6.5f, 17.5f, 17.5f);
                c.drawArc(R, 200, 70, false, p);
                break;
            case CONTRAST:
                c.drawCircle(12, 12, 8.5f, p);
                path.addArc(new RectF(3.5f, 3.5f, 20.5f, 20.5f), -90, 180);
                path.close();
                p.setStyle(Paint.Style.FILL);
                c.drawPath(path, p);
                break;
            case COMPARE:
                c.drawRoundRect(3.5f, 5, 20.5f, 19, 3, 3, p);
                c.drawLine(12, 3, 12, 21, p);
                break;
            case SHIELD:
                path.moveTo(12, 3.5f);
                path.lineTo(19, 6.2f);
                path.lineTo(19, 11.5f);
                path.cubicTo(19, 15.8f, 16, 19, 12, 20.5f);
                path.cubicTo(8, 19, 5, 15.8f, 5, 11.5f);
                path.lineTo(5, 6.2f);
                path.close();
                c.drawPath(path, p);
                break;
            case CODE:
                path.moveTo(8.5f, 7);
                path.lineTo(3.5f, 12);
                path.lineTo(8.5f, 17);
                path.moveTo(15.5f, 7);
                path.lineTo(20.5f, 12);
                path.lineTo(15.5f, 17);
                c.drawPath(path, p);
                break;
            case CLOCK:
                c.drawCircle(12, 12, 8.5f, p);
                path.moveTo(12, 7.5f);
                path.lineTo(12, 12);
                path.lineTo(15, 14);
                c.drawPath(path, p);
                break;
            case STACK:
                c.drawRoundRect(4, 8, 20, 20, 3, 3, p);
                c.drawLine(6, 5, 18, 5, p);
                break;
            case PLUS:
                c.drawLine(12, 5.5f, 12, 18.5f, p);
                c.drawLine(5.5f, 12, 18.5f, 12, p);
                break;
            case MINUS:
                c.drawLine(5.5f, 12, 18.5f, 12, p);
                break;
            case EDIT:
                path.moveTo(4.5f, 19.5f);
                path.lineTo(5.2f, 15.6f);
                path.lineTo(15.4f, 5.4f);
                path.quadTo(16.6f, 4.2f, 17.8f, 5.4f);
                path.lineTo(18.6f, 6.2f);
                path.quadTo(19.8f, 7.4f, 18.6f, 8.6f);
                path.lineTo(8.4f, 18.8f);
                path.close();
                c.drawPath(path, p);
                break;
            case PALETTE:
                path.moveTo(12, 3.5f);
                path.cubicTo(6.5f, 3.5f, 3.5f, 7.5f, 3.5f, 12);
                path.cubicTo(3.5f, 16.8f, 7.2f, 20.5f, 11.5f, 20.5f);
                path.cubicTo(13.4f, 20.5f, 13.4f, 18.4f, 12.4f, 17.3f);
                path.cubicTo(11.4f, 16.2f, 12.2f, 14.6f, 13.8f, 14.6f);
                path.lineTo(16, 14.6f);
                path.cubicTo(18.8f, 14.6f, 20.5f, 12.8f, 20.5f, 10.6f);
                path.cubicTo(20.5f, 6.6f, 16.8f, 3.5f, 12, 3.5f);
                c.drawPath(path, p);
                dot(c, p, 8, 10);
                dot(c, p, 12, 7.6f);
                dot(c, p, 16, 9.6f);
                break;
            case PULSE:
                path.moveTo(3.5f, 12.5f);
                path.lineTo(7.5f, 12.5f);
                path.lineTo(10, 6.5f);
                path.lineTo(14, 18);
                path.lineTo(16.5f, 12.5f);
                path.lineTo(20.5f, 12.5f);
                c.drawPath(path, p);
                break;
            case DOT:
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(12, 12, 4, p);
                break;
        }
    }

    private static void dot(Canvas c, Paint p, float x, float y) {
        Paint.Style s = p.getStyle();
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(x, y, 1.25f, p);
        p.setStyle(s);
    }

    public Drawable drawable(int color, float sizePx) {
        Icon icon = this;
        return new Drawable() {
            @Override
            public void draw(Canvas canvas) {
                Rect b = getBounds();
                canvas.save();
                canvas.translate(b.left, b.top);
                canvas.scale(b.width() / 24f, b.height() / 24f);
                icon.draw(canvas, color);
                canvas.restore();
            }

            @Override
            public int getIntrinsicWidth() {
                return Math.round(sizePx);
            }

            @Override
            public int getIntrinsicHeight() {
                return Math.round(sizePx);
            }

            @Override
            public void setAlpha(int alpha) {}

            @Override
            public void setColorFilter(ColorFilter filter) {}

            @Override
            public int getOpacity() {
                return PixelFormat.TRANSLUCENT;
            }
        };
    }

    /** Decorative by default; give it a content description where it stands alone. */
    @android.annotation.SuppressLint("ViewConstructor") // Built in code only, never inflated.
    public static final class View extends android.view.View {
        private Icon icon;
        private int color;
        private float rotation;

        public View(Context c, Icon icon, int color) {
            super(c);
            this.icon = icon;
            this.color = color;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        public void set(Icon icon, int color) {
            if (this.icon == icon && this.color == color) return;
            this.icon = icon;
            this.color = color;
            invalidate();
        }

        public void color(int color) {
            set(icon, color);
        }

        /** Rotation of the glyph only (chevrons). */
        public void glyphRotation(float degrees) {
            rotation = degrees;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float s = Math.min(getWidth() - getPaddingLeft() - getPaddingRight(),
                    getHeight() - getPaddingTop() - getPaddingBottom());
            canvas.save();
            canvas.translate((getWidth() - s) / 2f, (getHeight() - s) / 2f);
            canvas.rotate(rotation, s / 2f, s / 2f);
            canvas.scale(s / 24f, s / 24f);
            icon.draw(canvas, color);
            canvas.restore();
        }
    }
}
