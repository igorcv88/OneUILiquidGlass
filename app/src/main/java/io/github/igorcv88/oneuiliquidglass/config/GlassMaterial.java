package io.github.igorcv88.oneuiliquidglass.config;

import java.util.*;
import java.util.function.Function;

/**
 * The glass material chosen for the notification surfaces that the compositor can draw clear
 * (pop-up and lock screen). It is a different thing from {@link Mode} (which rendering path
 * SystemUI takes), from the saved styles (presets) and from the app's light/dark theme.
 *
 * <p>A material is stored as the two compositor selectors it stands for, {@code sf.huclear} and
 * {@code sf.kgclear}, so that choosing one goes through the same saved, pending and compiled
 * bookkeeping as every other compositor value. A new material is a new constant here, its
 * selector values, and the keys that only act on it ({@link #CLEAR_ONLY}/{@link #FROSTED_ONLY}
 * style lists); keys in neither list act on every material.</p>
 */
public enum GlassMaterial {
    /** The look before Clear Glass: optics 11 in the compositor, or the module's own glass. */
    FROSTED("frosted", "0"),
    /** Clear Glass optics 12 (needs the compositor bundle and a recompilation). */
    CLEAR("clear", "1");

    public static final String HU_SELECTOR = "sf.huclear", KG_SELECTOR = "sf.kgclear";

    public final String id;
    private final String selectorValue;

    GlassMaterial(String id, String selectorValue) {
        this.id = id;
        this.selectorValue = selectorValue;
    }

    /** The selector values that stand for this material, pop-up and lock screen together. */
    public Map<String, String> selection() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(HU_SELECTOR, selectorValue);
        m.put(KG_SELECTOR, selectorValue);
        return Collections.unmodifiableMap(m);
    }

    /** The material a single selector value stands for; null for per-card (2) or anything else. */
    public static GlassMaterial ofSelector(String raw) {
        if (raw == null) return null;
        String v = raw.trim();
        for (GlassMaterial m : values()) {
            if (m.selectorValue.equals(v)) return m;
        }
        try {
            double n = Double.parseDouble(v);
            for (GlassMaterial m : values()) {
                if (Double.parseDouble(m.selectorValue) == n) return m;
            }
        } catch (NumberFormatException ignored) {
            // Not a number: no material.
        }
        return null;
    }

    private static String selectorOf(Controls.Surface surface) {
        return surface == Controls.Surface.HEADSUP ? HU_SELECTOR : KG_SELECTOR;
    }

    /**
     * The material the whole selection stands for: both selectors agree on one material. Null
     * when they differ or one is per-card, that is, set outside the app's single choice.
     */
    public static GlassMaterial of(Function<String, String> value) {
        GlassMaterial hu = ofSelector(read(value, HU_SELECTOR)),
                kg = ofSelector(read(value, KG_SELECTOR));
        return hu != null && hu == kg ? hu : null;
    }

    /**
     * The material that decides which controls apply on a surface; null when no single material
     * does (shade and Quick Settings are never filtered; a per-card selector keeps both).
     */
    public static GlassMaterial forSurface(Function<String, String> value, Controls.Surface surface) {
        if (surface == Controls.Surface.SHADE) return null;
        return ofSelector(read(value, selectorOf(surface)));
    }

    private static String read(Function<String, String> value, String key) {
        String v = value.apply(key);
        if (v != null && !v.isEmpty()) return v;
        SettingSpec spec = SettingsRegistry.get(key);
        return spec == null ? "0" : spec.defaultValue;
    }

    // ---- which controls act on which material

    /** The classification the visibility rules use. */
    public enum Scope {
        /** Acts on every material (including old knobs that keep acting on Clear). */
        COMMON,
        /** Acts only on {@link #FROSTED}. */
        FROSTED_ONLY,
        /** Acts only on {@link #CLEAR}. */
        CLEAR_ONLY
    }

    /** Clear Glass parameters (docs/clear-glass/UI_HANDOFF.md sections 3 to 5), per profile. */
    private static final List<String> HU_CLEAR = Arrays.asList(
            "hcband", "hcfold", "hcamp", "hcbody", "hcbody2", "hcedge", "hccore", "hcblurexp",
            "hcadapt", "hcsat", "hcscrim", "hcscrimmax", "hclift", "hcneutral");
    private static final List<String> KG_CLEAR = Arrays.asList(
            "kcband", "kcfold", "kcamp", "kcbody", "kcbody2", "kcedge", "kccore", "kcblurexp",
            "kcsat", "kcveil", "kclevel", "kcdim", "kcknee", "kccool", "kclift");

    /**
     * Optics-11 parameters the clear variant replaces: no effect on a clear card, full effect on
     * a frosted one (docs/clear-glass/INTEGRATION_CONTRACT.md section 4 and UI_HANDOFF.md
     * sections 3 to 5). Shared knobs that keep acting on Clear (hurefract, hublur, taps,
     * hulight, hubloom, kgrefract, kgblur, kgtaps, kglight, kgdepth, kgglow, kgrim, kgspec,
     * kgline, kgtilt, kgfixedrim) are deliberately absent.
     */
    private static final List<String> FROSTED_ONLY = Arrays.asList(
            "core", "edgeblur", "ramp", "hubodylens",
            "kgcore", "kgedgeblur", "kgcorecap", "kgramp", "kgblurexp", "kggradient", "kgband",
            "kgbodylens", "kgsat", "kgtone", "kgflat", "kglevel", "kgcool", "kgflatcap",
            "kgcoredim");

    private static final Map<String, Scope> SCOPES = new HashMap<>();

    static {
        for (String k : HU_CLEAR) SCOPES.put("sf." + k, Scope.CLEAR_ONLY);
        for (String k : KG_CLEAR) SCOPES.put("sf." + k, Scope.CLEAR_ONLY);
        for (String k : FROSTED_ONLY) SCOPES.put("sf." + k, Scope.FROSTED_ONLY);
    }

    /** Registry keys of every Clear-only parameter, heads-up first. */
    public static List<String> clearKeys() {
        List<String> keys = new ArrayList<>();
        for (String k : HU_CLEAR) keys.add("sf." + k);
        for (String k : KG_CLEAR) keys.add("sf." + k);
        return Collections.unmodifiableList(keys);
    }

    public static Scope scope(String key) {
        Scope s = SCOPES.get(key);
        return s == null ? Scope.COMMON : s;
    }

    /** Whether a key acts on a material. */
    public boolean acts(String key) {
        switch (scope(key)) {
            case CLEAR_ONLY: return this == CLEAR;
            case FROSTED_ONLY: return this == FROSTED;
            default: return true;
        }
    }

    /**
     * Whether a control belongs in the surface's ordinary lists: it acts on the material that
     * decides that surface. Never hides anything on the shade; the full catalogue ignores this.
     */
    public static boolean visible(String key, Controls.Surface surface, Function<String, String> value) {
        if (surface == Controls.Surface.SHADE) return true;
        GlassMaterial material = forSurface(value, surface);
        return material == null || material.acts(key);
    }

    // ---- the recommended Clear configuration

    /**
     * The look approved on the device (docs/clear-glass/UI_HANDOFF.md, section 5 and the visual
     * review): the native Clear-only defaults, offered only when requested and never applied
     * on its own. {@code hclift} and {@code hcneutral} are left to their own values.
     */
    public static Map<String, String> recommendedClear() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("sf.hcband", "0.42");
        m.put("sf.hcfold", "2");
        m.put("sf.hcedge", "24");
        m.put("sf.hccore", "24");
        m.put("sf.hcbody", "0");
        m.put("sf.hcbody2", "0");
        m.put("sf.hcadapt", "1");
        m.put("sf.hcsat", "1.05");
        m.put("sf.hclift", "0.3");
        m.put("sf.hcscrim", "0");
        m.put("sf.hcscrimmax", "0");
        m.put("sf.kcband", "0.42");
        m.put("sf.kcfold", "2");
        m.put("sf.kcedge", "24");
        m.put("sf.kccore", "24");
        m.put("sf.kcbody", "0");
        m.put("sf.kcbody2", "0");
        return Collections.unmodifiableMap(m);
    }

    /** The Clear parameters that have a value set in the app or on the device (empty = default). */
    public static Map<String, String> clearValuesPresent(
            Map<String, String> saved, Map<String, String> observed) {
        Map<String, String> present = new LinkedHashMap<>();
        for (String key : clearKeys()) {
            String s = saved.get(key), o = observed.get(key);
            if (s != null && !s.isEmpty()) present.put(key, s);
            else if (o != null && !o.isEmpty()) present.put(key, o);
        }
        return present;
    }

    /** Clear parameters set only on the device (Terminal), not saved by the app. */
    public static Map<String, String> clearValuesOnlyOnDevice(
            Map<String, String> saved, Map<String, String> observed) {
        Map<String, String> onlyDevice = new LinkedHashMap<>();
        for (String key : clearKeys()) {
            String s = saved.get(key), o = observed.get(key);
            if ((s == null || s.isEmpty()) && o != null && !o.isEmpty()) onlyDevice.put(key, o);
        }
        return onlyDevice;
    }
}
