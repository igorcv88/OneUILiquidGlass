package io.github.igorcv88.oneuiliquidglass.ui;

import android.content.Context;
import android.graphics.*;
import android.view.View;

import io.github.igorcv88.oneuiliquidglass.TestNotificationModel;
import io.github.igorcv88.oneuiliquidglass.config.*;

import java.util.*;

/**
 * Synthetic, cached backdrop only. No screen capture, hook or privileged renderer is loaded here.
 */
public final class MaterialPreview extends View {
    public String surface = "headsup";
    public int backdrop = 2;
    public boolean light;
    private final Map<String, String> values = new HashMap<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Bitmap background;
    private final RenderNode node = new RenderNode("oulg-offline-preview");
    private SettingSpec focus;
    private String focusedValue;

    public MaterialPreview(Context c) {
        super(c);
        setContentDescription("Simulação aproximada de notificação, sem captura da tela");
    }

    public void bind(Map<String, String> next) {
        values.clear();
        values.putAll(next);
        invalidate();
    }

    public void focus(SettingSpec spec, String value) {
        focus = spec;
        if (!spec.surfaceScope.equals("all")) surface = spec.surfaceScope;
        if (spec.key.equals("liveradius")) surface = "headsup";
        if (spec.key.equals("lighttone")
                || spec.key.equals("lightglass")
                || spec.key.equals("shadelightglass")) light = true;
        if (spec.key.contains("dark") || spec.key.equals("veilmax")) light = false;
        focusedValue = value;
        values.put(spec.key, value);
        postInvalidateOnAnimation();
    }

    public void background(int selected) {
        backdrop = selected;
        if (background != null) {
            background.recycle();
            background = null;
        }
        invalidate();
    }

    private float n(String key, float fallback) {
        try {
            return Float.parseFloat(
                    values.getOrDefault(
                            key,
                            SettingsRegistry.get(key) == null
                                    ? Float.toString(fallback)
                                    : SettingsRegistry.get(key).defaultValue));
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private boolean feature(String name) {
        return !"0".equals(values.get("surface." + surface + ".feature." + name + ".enabled"));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getWidth() < 1 || getHeight() < 1) return;
        if (background == null) createBackdrop();
        canvas.save();
        canvas.scale(getWidth() / 600f, getHeight() / 390f);
        canvas.drawBitmap(background, 0, 0, null);
        if (surface.equals("keyguard")) {
            text(canvas, "09:41", 36, 61, 42, light ? 0xff253340 : 0xffedf6ff);
            text(
                    canvas,
                    "Segunda-feira, 26 de outubro",
                    38,
                    88,
                    15,
                    light ? 0xff354458 : 0xffdde7f2);
        } else if (surface.equals("shade"))
            text(canvas, "Central de notificações", 30, 43, 23, light ? 0xff253340 : 0xffedf6ff);
        int count = surface.equals("shade") ? 2 : 1;
        float top = surface.equals("headsup") ? 58 : surface.equals("shade") ? 63 : 112;
        for (int index = 0; index < count; index++)
            card(canvas, new RectF(26, top + index * 146, 574, top + 132 + index * 146), index);
        if (surface.equals("keyguard")) {
            paint.setColor(0x99778398);
            canvas.drawRoundRect(new RectF(241, 264, 359, 298), 17, 17, paint);
            text(canvas, "+ 3", 282, 287, 16, Color.WHITE);
        }
        if (focus != null && !focus.previewStrategy.equals("MATERIAL")) {
            paint.setColor(light ? 0xeef5f7fc : 0xee101c2c);
            canvas.drawRoundRect(new RectF(18, 310, 582, 381), 15, 15, paint);
            text(
                    canvas,
                    "Diagrama de domínio · sem simulação física",
                    32,
                    331,
                    13,
                    light ? 0xff253340 : 0xffcdd9e9);
            paint.setColor(0xff6edce8);
            canvas.drawRoundRect(new RectF(33, 343, 565, 347), 2, 2, paint);
            float fraction =
                    (float) ((n(focus.key, 0) - focus.min) / Math.max(.001, focus.max - focus.min));
            canvas.drawCircle(33 + 532 * Math.max(0, Math.min(1, fraction)), 345, 5, paint);
            text(
                    canvas,
                    "Menor  ·  " + focusedValue + " " + focus.unit + "  ·  Maior",
                    33,
                    370,
                    14,
                    light ? 0xff253340 : 0xffeef6ff);
        }
        canvas.restore();
    }

    private void createBackdrop() {
        background = Bitmap.createBitmap(600, 390, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(background);
        int base = backdrop == 0 ? 0xffeff2f8 : backdrop == 1 ? 0xff101c2d : 0xff283a64;
        c.drawColor(base);
        paint.setShader(null);
        if (backdrop == 3) {
            for (int y = 0; y < 390; y += 30)
                for (int x = 0; x < 600; x += 30) {
                    paint.setColor(((x + y) / 30 % 2) == 0 ? 0xff8795a8 : 0xff354961);
                    c.drawRect(x, y, x + 30, y + 30, paint);
                }
        } else {
            paint.setShader(
                    new RadialGradient(
                            440,
                            120,
                            260,
                            new int[] {backdrop == 0 ? 0xffbdcee8 : 0xff8d75bd, Color.TRANSPARENT},
                            null,
                            Shader.TileMode.CLAMP));
            c.drawRect(0, 0, 600, 390, paint);
            paint.setShader(
                    new RadialGradient(
                            100,
                            300,
                            240,
                            new int[] {backdrop == 1 ? 0xff1d4867 : 0xff4fadb8, Color.TRANSPARENT},
                            null,
                            Shader.TileMode.CLAMP));
            c.drawRect(0, 0, 600, 390, paint);
            paint.setShader(null);
            for (int i = 0; i < 12; i++) {
                paint.setColor(backdrop == 0 ? 0x44355171 : 0x339df1ff);
                c.drawRoundRect(new RectF(38 + i * 47, 30, 52 + i * 47, 365), 7, 7, paint);
            }
        }
    }

    private void card(Canvas c, RectF box, int index) {
        boolean nativeStyle = "0".equals(values.get("surface." + surface + ".enabled"));
        c.save();
        Path path = new Path();
        path.addRoundRect(box, 27, 27, Path.Direction.CW);
        c.clipPath(path);
        float blur =
                surface.equals("shade")
                        ? n("liveshaderadius", 120)
                        : surface.equals("keyguard") ? n("kgblurradius", -1) : n("liveradius", 80);
        if (blur < 0) blur = n("liveradius", 80);
        if ("1".equals(values.getOrDefault("sfrefract", "0")))
            blur = surface.equals("keyguard") ? n("sf.kgcore", 80) : n("sf.core", 48);
        if (focus != null
                && java.util.Arrays.asList(
                                "liveradius",
                                "liveshaderadius",
                                "kgblurradius",
                                "sf.core",
                                "sf.kgcore")
                        .contains(focus.key)) {
            blur = n(focus.key, blur);
            if (blur < 0) blur = n("liveradius", 80);
        }
        node.setPosition(0, 0, 600, 390);
        RecordingCanvas recording = node.beginRecording(600, 390);
        paint.setColor(Color.WHITE);
        ColorMatrix matrix = new ColorMatrix();
        matrix.setSaturation(
                focus != null && focus.key.equals("sat")
                        ? n("sat", 1.2f)
                        : surface.equals("keyguard")
                                ? n("sf.kgsat", n("sat", 1.2f))
                                : n("sat", 1.2f));
        paint.setColorFilter(new ColorMatrixColorFilter(matrix));
        recording.drawBitmap(background, 0, 0, paint);
        paint.setColorFilter(null);
        node.endRecording();
        float radius = Math.max(.1f, Math.min(80, blur / 5));
        node.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP));
        if (c.isHardwareAccelerated()) c.drawRenderNode(node);
        else c.drawBitmap(background, 0, 0, null);
        // Controlled copy in a narrow rim approximates displacement. Does not claim shader
        // fidelity.
        if (feature("lens")) {
            c.save();
            Path inner = new Path();
            inner.addRoundRect(
                    new RectF(box.left + 12, box.top + 12, box.right - 12, box.bottom - 12),
                    17,
                    17,
                    Path.Direction.CW);
            c.clipOutPath(inner);
            float lens = n(surface.equals("keyguard") ? "kglens" : "sflens", .45f);
            if (lens < 0) lens = n("sflens", .45f);
            c.translate((float) Math.sin(index + 1) * lens * 13, -lens * 9);
            paint.setAlpha(85);
            c.drawBitmap(background, 0, 0, paint);
            paint.setAlpha(255);
            c.restore();
        }
        float a =
                light
                        ? n(surface.equals("shade") ? "shadelightglass" : "lightglass", .75f)
                        : n(surface.equals("keyguard") ? "kgdarkfill" : "livedarkfill", 10) / 255f;
        int color = light ? 0xffffff : 0x020916;
        if (light)
            try {
                color = (int) Long.parseLong(values.getOrDefault("lighttone", "ffffff"), 16);
            } catch (RuntimeException ignored) {
            }
        paint.setColor(((int) (Math.max(0, Math.min(.95, a)) * 255) << 24) | color);
        c.drawRect(box, paint);
        if (!light) {
            int white =
                    Math.round(n(surface.equals("keyguard") ? "kgdarkalpha" : "livedarkalpha", 20));
            paint.setColor((Math.max(0, Math.min(255, white)) << 24) | 0xffffff);
            c.drawRect(box, paint);
        }
        if (feature("tonemap") && !light) {
            paint.setColor(((int) (n("veilmax", .25f) * .6f * 255) << 24) | 0x05101e);
            c.drawRect(box, paint);
        }
        if (nativeStyle) {
            paint.setColor(light ? 0xfff2f3f7 : 0xff273342);
            c.drawRect(box, paint);
        }
        c.restore();
        float rim = n(surface.equals("keyguard") ? "kgrim" : "liverim", 2);
        if (feature("edge") && !nativeStyle) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(.6f + rim * .6f);
            paint.setColor((Math.min(180, (int) (rim * 35)) << 24) | 0xc8e9ef);
            c.drawRoundRect(box, 27, 27, paint);
            paint.setStyle(Paint.Style.FILL);
        }
        float glint =
                n(
                        surface.equals("keyguard")
                                ? "kgglint"
                                : surface.equals("shade") ? "shadeglint" : "liveglint",
                        .1f);
        if (feature("glint") && !nativeStyle) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2 + glint * 3);
            paint.setColor((Math.min(240, (int) (glint * 255)) << 24) | 0xffffff);
            c.drawArc(
                    new RectF(box.left + 1, box.top + 1, box.left + 55, box.top + 55),
                    190,
                    85,
                    false,
                    paint);
            paint.setStyle(Paint.Style.FILL);
        }
        int ink = light ? 0xff17283c : 0xfff1f6ff;
        paint.setColor(0xff75d6e5);
        c.drawRoundRect(
                new RectF(box.left + 17, box.top + 15, box.left + 35, box.top + 33), 5, 5, paint);
        text(c, "One UI Liquid Glass · agora", box.left + 45, box.top + 29, 13, ink);
        text(c, TestNotificationModel.TITLE, box.left + 17, box.top + 55, 20, ink);
        text(
                c,
                "Teste expandido de vidro — expanda para comparar.",
                box.left + 17,
                box.top + 79,
                15,
                ink);
        text(
                c,
                TestNotificationModel.OPEN,
                box.left + 17,
                box.top + 113,
                15,
                light ? 0xff146879 : 0xff8de3ed);
        text(
                c,
                TestNotificationModel.DISMISS,
                box.left + 202,
                box.top + 113,
                15,
                light ? 0xff146879 : 0xff8de3ed);
    }

    private void text(Canvas c, String value, float x, float y, float size, int color) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        paint.setTextSize(size);
        paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        c.drawText(value, x, y, paint);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        node.discardDisplayList();
        if (background != null) {
            background.recycle();
            background = null;
        }
    }
}
