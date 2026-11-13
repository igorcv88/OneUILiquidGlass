package io.github.igorcv88.oneuiliquidglass.ui;

import android.content.Context;
import io.github.igorcv88.oneuiliquidglass.R;
import io.github.igorcv88.oneuiliquidglass.config.Controls;
import io.github.igorcv88.oneuiliquidglass.config.GlassMaterial;
import io.github.igorcv88.oneuiliquidglass.ui.preview.NotificationPreview;

/** Localized names for structural UI elements. Stable identifiers remain in the model. */
final class UiLabels {
    static String section(Context c, Controls.Section section) {
        switch (section) {
            case GLASS: return c.getString(R.string.section_glass);
            case EDGES: return c.getString(R.string.section_edges);
            case COLOR: return c.getString(R.string.section_color);
            case BEHAVIOR: return c.getString(R.string.section_behavior);
            case MOTION: return c.getString(R.string.section_motion);
            case LENS_TUNING: return c.getString(R.string.section_lens);
            case QUICK_SETTINGS: return c.getString(R.string.section_qs);
            default: return c.getString(R.string.section_technical);
        }
    }
    static String layer(Context c, String key) {
        switch (key) {
            case "edge": return c.getString(R.string.layer_edge);
            case "glint": return c.getString(R.string.layer_glint);
            case "lens": return c.getString(R.string.layer_lens);
            case "tonemap": return c.getString(R.string.layer_tonemap);
            case "per_card_blur": return c.getString(R.string.layer_card_blur);
            default: return key;
        }
    }
    static String material(Context c, GlassMaterial material) {
        switch (material) {
            case CLEAR: return c.getString(R.string.material_clear);
            default: return c.getString(R.string.material_frosted);
        }
    }
    /** A compositor selector value in words: a material, per card, or the raw value. */
    static String selector(Context c, String raw) {
        GlassMaterial material = GlassMaterial.ofSelector(raw);
        if (material != null) return material(c, material);
        return "2".equals(raw == null ? "" : raw.trim()) ? c.getString(R.string.material_per_card) : String.valueOf(raw);
    }
    static String backdrop(Context c, NotificationPreview.Backdrop backdrop) {
        switch (backdrop) {
            case LIGHT: return c.getString(R.string.preview_light);
            case DARK: return c.getString(R.string.preview_dark);
            case GRID: return c.getString(R.string.preview_grid);
            default: return c.getString(R.string.preview_color);
        }
    }
    static String count(Context c, int n) {
        return n == 1 ? c.getString(R.string.settings_conflict_one) : c.getString(R.string.settings_conflict_many, n);
    }
    private UiLabels() {}
}
