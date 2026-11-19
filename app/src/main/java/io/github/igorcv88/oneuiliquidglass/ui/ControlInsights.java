package io.github.igorcv88.oneuiliquidglass.ui;

import io.github.igorcv88.oneuiliquidglass.config.Controls;
import io.github.igorcv88.oneuiliquidglass.config.product.ProductLayout;
import io.github.igorcv88.oneuiliquidglass.config.product.ProductLayout.Route;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Presentation-only facts for the editor of the lens multipliers: which controls a parent scales,
 * and the nominal result of a child times its parent. Nothing here changes a stored value or a
 * formula (docs/OULG_UI_PLAN/EFFECT_MAP.md sections 2 and 3).
 */
final class ControlInsights {
    private static final List<String> BLUR_PARENTS = Arrays.asList("sf.hublur", "sf.kgblur");
    private static final List<String> REFRACT_PARENTS = Arrays.asList("sf.hurefract", "sf.kgrefract");
    private static final List<String> HU_BLUR_CHILDREN = Arrays.asList("sf.hcedge", "sf.hccore", "sf.edgeblur", "sf.core");
    private static final List<String> KG_BLUR_CHILDREN = Arrays.asList("sf.kcedge", "sf.kccore", "sf.kgedgeblur", "sf.kgcore");
    private static final List<String> MENISCUS = Arrays.asList("sf.hcfold", "sf.kcfold");

    private ControlInsights() {}

    /** The multiplier a blur child is scaled by; null for any other key. */
    static String parentOf(String key) {
        if (HU_BLUR_CHILDREN.contains(key)) return "sf.hublur";
        if (KG_BLUR_CHILDREN.contains(key)) return "sf.kgblur";
        return null;
    }

    /** Whether a parent is on the main list of this surface and route (it is not on standard or capture). */
    static boolean routeHasParent(Controls.Surface surface, Route route, String parent) {
        return parent != null && ProductLayout.main(surface, route).contains(parent);
    }

    /** The controls a lens multiplier scales on this route: what the person sees as "Affects". */
    static List<String> affected(Controls.Surface surface, Route route, String parent) {
        List<String> out = new ArrayList<>();
        boolean blur = BLUR_PARENTS.contains(parent);
        if (!blur && !REFRACT_PARENTS.contains(parent)) return out;
        if (!ProductLayout.main(surface, route).contains(parent)) return out;
        List<String> pool = new ArrayList<>(ProductLayout.main(surface, route));
        for (ProductLayout.Group g : ProductLayout.details(surface, route))
            if (g.id.equals(blur ? "blur" : "refraction")) pool.addAll(g.keys);
        List<String> children = "sf.hublur".equals(parent) ? HU_BLUR_CHILDREN : KG_BLUR_CHILDREN;
        for (String key : pool) {
            boolean match = blur ? children.contains(key)
                    : !REFRACT_PARENTS.contains(key) && (MENISCUS.contains(key) || inGroup(surface, route, key, "refraction"));
            if (match && !out.contains(key)) out.add(key);
        }
        return Collections.unmodifiableList(out);
    }

    private static boolean inGroup(Controls.Surface surface, Route route, String key, String group) {
        for (ProductLayout.Group g : ProductLayout.details(surface, route))
            if (g.id.equals(group) && g.keys.contains(key)) return true;
        return false;
    }

    /** "27,6 px": one decimal at most, in the app locale. */
    static String nominalPx(Locale locale, double pixels, double multiplier) {
        double value = BigDecimal.valueOf(pixels).multiply(BigDecimal.valueOf(multiplier)).doubleValue();
        DecimalFormat f = new DecimalFormat("0.#", DecimalFormatSymbols.getInstance(locale));
        f.setRoundingMode(RoundingMode.HALF_UP);
        return f.format(value) + " px";
    }
}
