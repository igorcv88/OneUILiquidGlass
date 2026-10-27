package io.github.igorcv88.oneuiliquidglass.ui.preview;

import android.graphics.*;

/**
 * Synthetic backdrops with fine detail (lines, text-like bars, saturated shapes) so blur,
 * refraction and transparency read clearly. Nothing here comes from the screen or other apps.
 */
final class Backdrops {
    private Backdrops() {}

    static void draw(Canvas c, NotificationPreview.Backdrop b, float w, float h) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        switch (b) {
            case LIGHT: app(c, p, w, h, false); break;
            case DARK: app(c, p, w, h, true); break;
            case GRID: grid(c, p, w, h); break;
            default: wallpaper(c, p, w, h);
        }
    }

    /** A chat-like app: header, bubbles, text bars and a photo. */
    private static void app(Canvas c, Paint p, float w, float h, boolean dark) {
        c.drawColor(dark ? 0xff11161d : 0xfffafbfc);
        p.setColor(dark ? 0xff1b222c : 0xffeef2f6);
        c.drawRect(0, 0, w, 64, p);
        p.setColor(dark ? 0xff3a83ff : 0xff1a73e8);
        c.drawCircle(30, 44, 12, p);
        p.setColor(dark ? 0xffdfe6ee : 0xff1b2430);
        c.drawRoundRect(52, 36, 150, 44, 4, 4, p);
        p.setColor(dark ? 0xff7f8b99 : 0xff8d98a6);
        c.drawRoundRect(52, 49, 110, 55, 3, 3, p);
        float y = 80;
        int[] accents = {0xffe8453c, 0xfff2a93b, 0xff34a853, 0xff4285f4, 0xff9b51e0};
        for (int i = 0; i < 9; i++) {
            boolean mine = i % 3 == 1;
            float bw = 120 + (i * 37) % 110, x = mine ? w - 14 - bw : 14;
            p.setColor(mine ? (dark ? 0xff1f4c54 : 0xffd2f0f3) : (dark ? 0xff222a35 : 0xffeceff3));
            c.drawRoundRect(x, y, x + bw, y + 34, 14, 14, p);
            p.setColor(dark ? 0xffc9d3df : 0xff2a3644);
            c.drawRoundRect(x + 10, y + 9, x + bw - 18, y + 14, 2.5f, 2.5f, p);
            p.setColor(dark ? 0xff8996a6 : 0xff6c7889);
            c.drawRoundRect(x + 10, y + 20, x + bw * .55f, y + 25, 2.5f, 2.5f, p);
            if (i == 4) {
                float py = y + 42;
                p.setShader(new LinearGradient(14, py, 200, py + 80, accents[0], accents[3], Shader.TileMode.CLAMP));
                c.drawRoundRect(14, py, 200, py + 80, 14, 14, p);
                p.setShader(null);
                p.setColor(0xffffffff);
                c.drawCircle(60, py + 34, 14, p);
                y += 88;
            }
            y += 42;
        }
        p.setStrokeWidth(1);
        p.setColor(dark ? 0x22ffffff : 0x18000000);
        for (float x = 0; x < w; x += 8) c.drawLine(x, 64, x, 66, p);
    }

    /** Wallpaper: saturated blobs, diagonal stripes and dots for edges to bend. */
    private static void wallpaper(Canvas c, Paint p, float w, float h) {
        p.setShader(new LinearGradient(0, 0, w, h, new int[] {0xff16346a, 0xff4a2a7a, 0xff0f6b74}, null, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        int[][] blobs = {{60, 70, 110, 0xffff7a59}, {300, 60, 90, 0xff5be3ff}, {250, 230, 120, 0xffb36bff}, {70, 260, 90, 0xff3cf0a4}, {180, 160, 70, 0xffffd166}};
        for (int[] b : blobs) {
            p.setShader(new RadialGradient(b[0], b[1], b[2], b[3], b[3] & 0x00ffffff, Shader.TileMode.CLAMP));
            c.drawCircle(b[0], b[1], b[2], p);
        }
        p.setShader(null);
        p.setStrokeWidth(2.2f);
        p.setColor(0x55ffffff);
        for (float x = -h; x < w; x += 14) c.drawLine(x, h, x + h, 0, p);
        p.setColor(0x33000000);
        p.setStrokeWidth(1);
        for (float x = -h + 7; x < w; x += 14) c.drawLine(x, h, x + h, 0, p);
        p.setColor(0xccffffff);
        for (float y = 12; y < h; y += 26) for (float x = 12; x < w; x += 26) c.drawCircle(x, y, 1.6f, p);
    }

    /** Neutral checker with thin lines: refraction and blur on geometry. */
    private static void grid(Canvas c, Paint p, float w, float h) {
        c.drawColor(0xffd9dee5);
        p.setColor(0xff9aa6b6);
        for (float y = 0; y < h; y += 24)
            for (float x = ((int) (y / 24) % 2) * 24; x < w; x += 48) c.drawRect(x, y, x + 24, y + 24, p);
        p.setStrokeWidth(1);
        p.setColor(0xff2a3646);
        for (float x = 0; x <= w; x += 12) c.drawLine(x, 0, x, h, p);
        for (float y = 0; y <= h; y += 12) c.drawLine(0, y, w, y, p);
        p.setColor(0xffe8453c);
        p.setStrokeWidth(3);
        c.drawLine(0, h * .3f, w, h * .55f, p);
        p.setColor(0xff1a73e8);
        c.drawLine(0, h * .7f, w, h * .2f, p);
    }
}
