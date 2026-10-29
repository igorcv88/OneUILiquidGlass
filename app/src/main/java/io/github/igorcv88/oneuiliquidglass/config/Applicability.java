package io.github.igorcv88.oneuiliquidglass.config;

import java.util.*;
import java.util.function.Function;

/**
 * Whether a key can change what the device draws right now, and if not, why and how to enable
 * it. Mirrors the reader branches in glass/ and hooks/ (see docs/CONTROL_MATRIX.md); the
 * generated registry's {@code dependencies} string is only a coarse hint and is not used here.
 */
public final class Applicability {
    public enum Kind {
        /** Read by SystemUI in the current branch. */
        ACTIVE,
        /** Saved; the compositor reads it only when it compiles its shaders. */
        NEEDS_RESTART,
        /** No effect in the current mode or with a related setting. */
        INACTIVE,
        /** The surface or the glass is off. */
        OFF,
        /** State, not a control. */
        READ_ONLY
    }

    public static final class Result {
        public final Kind kind;
        /** One short sentence; empty for ACTIVE. */
        public final String reason;

        Result(Kind kind, String reason) {
            this.kind = kind;
            this.reason = reason;
        }

        public boolean usable() {
            return kind == Kind.ACTIVE || kind == Kind.NEEDS_RESTART;
        }

        @Override
        public String toString() {
            return kind + (reason.isEmpty() ? "" : ": " + reason);
        }
    }

    private static final Result ACTIVE = new Result(Kind.ACTIVE, "");

    /** {@code value}: what SystemUI would read for a registry key, or a product flag ("1"/"0"). */
    private final Function<String, String> value;

    public final Mode mode;

    public Applicability(Function<String, String> value) {
        this.value = value;
        mode = Mode.of(value);
    }

    private String v(String key) {
        String s = value.apply(key);
        if (s != null) return s;
        SettingSpec spec = SettingsRegistry.get(key);
        return spec == null ? "" : spec.defaultValue;
    }

    private boolean on(String key) {
        return Mode.number(v(key)) >= 1;
    }

    private boolean flag(String key) {
        return !"0".equals(value.apply(key));
    }

    public boolean globalOn() {
        return flag("enabled");
    }

    public boolean surfaceOn(Controls.Surface s) {
        return flag("surface." + s.id + ".enabled");
    }

    public boolean layerOn(Controls.Surface s, String feature) {
        return flag("surface." + s.id + ".feature." + feature + ".enabled");
    }

    private static Result inactive(String reason) {
        return new Result(Kind.INACTIVE, reason);
    }

    private static final String NEEDS_STANDARD = "Usado no vidro padrão",
            NEEDS_LENS = "Usado com a lente dinâmica",
            NEEDS_CAPTURE = "Usado no modo de captura";

    /** The rule for one key in the current mode, ignoring surface and layer switches. */
    public Result rule(String key) {
        boolean standard = mode == Mode.STANDARD,
                lens = mode == Mode.DYNAMIC_LENS,
                capture = mode == Mode.CAPTURE;
        // The pop-up's captured lens band: standard mode with livelens, or a sampling mode.
        boolean band = standard && on("livelens") || capture;
        boolean snell = "snell".equals(v("profile"));
        switch (key) {
            case "qs.track":
            case "qs.lightstyle":
            case "qs.darkfill":
            case "qs.darkcolor":
            case "qs.active":
            case "qs.idle":
                return new Result(Kind.READ_ONLY, "Legado sem efeito visual");
            case "qs.tilelighton":
            case "qs.tilelightonink":
            case "qs.tilelightoffink":
                return on("qs.tilelight") ? ACTIVE : inactive("Ative Discos adaptativos no fundo claro");
            case "qs.tilelightoff":
            case "qs.tilelightoffalpha":
                return on("qs.hideoffdiscs") ? inactive("Desative Discos desligados sem fundo")
                        : on("qs.tilelight") ? ACTIVE : inactive("Ative Discos adaptativos no fundo claro");
            case "qs.tiledarkon":
            case "qs.tiledarkonink":
            case "qs.tiledarkoffink":
                return on("qs.tiledark") ? ACTIVE : inactive("Ative Personalizar discos no fundo colorido");
            case "qs.tiledarkoff":
            case "qs.tiledarkoffalpha":
                return on("qs.hideoffdiscs") ? inactive("Desative Discos desligados sem fundo")
                        : on("qs.tiledark") ? ACTIVE : inactive("Ative Personalizar discos no fundo colorido");
            case "sfrefract":
            case "sf.optics":
            case "sf.norewrite":
                return new Result(Kind.READ_ONLY, "Estado lido do aparelho");
            case "backdrop":
            case "liveblur":
                return lens ? inactive(NEEDS_STANDARD + " ou na captura") : ACTIVE;
            case "profile":
            case "disp":
            case "rimblur":
            case "rimhz":
            case "lensmaxage":
            case "lenssettle":
            case "lenstransition":
            case "lensgate":
                return band ? ACTIVE : inactive(requiresBand(standard));
            case "lens":
                if (!band) return inactive(requiresBand(standard));
                return snell ? inactive("Usado com o modelo Lente suave") : ACTIVE;
            case "refract":
            case "ior":
                if (!band) return inactive(requiresBand(standard));
                return snell ? ACTIVE : inactive("Usado com o modelo Física");
            case "blur":
            case "sat":
            case "tint":
            case "hz":
            case "hzkg":
                return capture ? ACTIVE : inactive(NEEDS_CAPTURE);
            case "livelens":
            case "liveradius":
            case "liveshaderadius":
            case "liveoptics":
            case "livesheen":
            case "liveglint":
            case "shadeglint":
            case "kgglint":
            case "liverim":
            case "kgrim":
            case "livedarkalpha":
            case "livedarkfill":
            case "kgdarkalpha":
            case "kgdarkfill":
            case "lightedge":
                return standard ? ACTIVE : inactive(NEEDS_STANDARD);
            case "semradius":
            case "semshape":
            case "shadeblurpath":
                return standard ? inactive("Sem efeito no vidro padrão") : ACTIVE;
            case "semradiuslens":
            case "sflens":
            case "kgoptics":
            case "huoptics":
            case "kgblurpath":
                return lens ? ACTIVE : inactive(NEEDS_LENS);
            case "kglens":
                if (!lens) return inactive(NEEDS_LENS);
                return on("kgoptics")
                        ? ACTIVE
                        : inactive("Com o material do pop-up, vale a refração do pop-up");
            case "kgblurradius":
                if (standard) return ACTIVE;
                if (lens)
                    return on("kgblurpath")
                            ? ACTIVE
                            : inactive("Exige o desfoque desenhado no cartão");
                return inactive(NEEDS_STANDARD + " ou com a lente dinâmica");
            case "kgwinblur":
                return capture || mode == Mode.PLAIN_BLUR
                        ? inactive(NEEDS_STANDARD + " ou com a lente dinâmica")
                        : ACTIVE;
            case "pillglass":
                return pillPath() ? ACTIVE : inactive(pillReason());
            case "pillblur":
                if (!pillPath()) return inactive(pillReason());
                return on("pillglass") ? ACTIVE : inactive("Ative o vidro no resumo de ícones");
            case "lightglass":
            case "shadelightglass":
            case "lighttone":
                return on("textswap") ? ACTIVE : inactive("Ative o texto adaptativo");
            case "popuptone":
                return standard || lens ? ACTIVE : inactive(NEEDS_STANDARD);
            case "popuplock":
                return on("popuptone") ? ACTIVE : inactive("Ative o contraste medido no pop-up");
            case "headertone":
                if (!on("textswap")) return inactive("Ative o texto adaptativo");
                return adaptiveShade() ? ACTIVE : inactive("Use a proteção Adaptativa");
            case "orphandraw":
            case "shadelocal":
            case "paneldim":
                return adaptiveShade() ? ACTIVE : inactive("Use a proteção Adaptativa");
            case "shadescaleveil":
                if (adaptiveShade()) return inactive("Sem efeito na proteção Adaptativa");
                return "2".equals(v("shadetone")) || lens && "1".equals(v("shadetone"))
                        ? ACTIVE
                        : inactive("Use o véu fixo ou o mapa de tons da lente");
            case "sf.shadecap":
            case "sf.shadelift":
                if (!lens) return inactive(NEEDS_LENS);
                return "1".equals(v("shadetone"))
                        ? new Result(Kind.NEEDS_RESTART, restartReason())
                        : inactive("Use o mapa de tons da lente na central");
            case "qs.edgeblur":
                // The blur of the profile-3 regions: only the dynamic lens draws them. Live.
                return lens ? ACTIVE : inactive(NEEDS_LENS);
            default:
                if (key.startsWith("sf."))
                    return lens
                            ? new Result(Kind.NEEDS_RESTART, restartReason())
                            : inactive(NEEDS_LENS);
                return ACTIVE;
        }
    }

    private static String requiresBand(boolean standard) {
        return standard ? "Ative a faixa de lente no pop-up" : NEEDS_STANDARD + " com a faixa de lente";
    }

    private static String restartReason() {
        return "Aplicado ao reiniciar o userspace";
    }

    private boolean adaptiveShade() {
        return "3".equals(v("shadetone"));
    }

    /** HeadsUpHooks.pillSurface: the drawable blur path (standard mode, or the lens with kgblurpath=1). */
    private boolean pillPath() {
        return mode == Mode.STANDARD || mode == Mode.DYNAMIC_LENS && on("kgblurpath");
    }

    private String pillReason() {
        return mode == Mode.DYNAMIC_LENS
                ? "Exige o desfoque desenhado no cartão"
                : NEEDS_STANDARD + " ou com a lente dinâmica";
    }

    /**
     * The full answer for a key shown on a surface: global switch, surface switch, the effect
     * layer it belongs to and the mode rule, in that order.
     */
    public Result on(String key, Controls.Surface surface) {
        Result rule = rule(key);
        if (rule.kind == Kind.READ_ONLY) return rule;
        Controls.Control c = Controls.get(key);
        Set<Controls.Surface> where =
                c == null || c.surfaces.isEmpty()
                        ? EnumSet.allOf(Controls.Surface.class)
                        : c.surfaces;
        if (!globalOn()) return new Result(Kind.OFF, "O vidro está desligado");
        if (key.startsWith("qs.") || key.startsWith("sf.qs")) {
            if (!flag(ProductFlags.QUICK_SETTINGS)) return new Result(Kind.OFF, "Ative Central de controle");
            return rule;
        }
        if (surface != null && where.contains(surface)) {
            if (!surfaceOn(surface))
                return new Result(Kind.OFF, "O vidro está desligado em " + surface.label);
        } else {
            boolean any = false;
            for (Controls.Surface s : where) any |= surfaceOn(s);
            if (!any) return new Result(Kind.OFF, "O vidro está desligado onde este ajuste vale");
        }
        if (c != null && c.feature != null) {
            boolean any = false;
            for (Controls.Surface s : where)
                if ((surface == null || s == surface || !where.contains(surface))
                        && layerOn(s, c.feature)) any = true;
            if (!any) return inactive("A camada " + layerName(c.feature) + " está desligada");
        }
        return rule;
    }

    /** Whether a layer switch changes anything on a surface in the current mode. */
    public Result layer(Controls.Surface surface, String feature) {
        if (!globalOn()) return new Result(Kind.OFF, "O vidro está desligado");
        if (!surfaceOn(surface))
            return new Result(Kind.OFF, "O vidro está desligado em " + surface.label);
        switch (feature) {
            case "glint":
                return mode == Mode.STANDARD ? ACTIVE : inactive(NEEDS_STANDARD);
            case "lens":
                if (surface == Controls.Surface.SHADE)
                    return inactive("A central não usa lente");
                if (mode == Mode.DYNAMIC_LENS || mode == Mode.CAPTURE) return ACTIVE;
                if (surface == Controls.Surface.HEADSUP)
                    return on("livelens") ? ACTIVE : inactive("Ative a faixa de lente no pop-up");
                return inactive(NEEDS_LENS);
            case "per_card_blur":
                return on("shadeblur") ? ACTIVE : inactive("Ative o desfoque próprio por cartão");
            default:
                return ACTIVE;
        }
    }

    static String layerName(String feature) {
        for (Controls.Surface s : Controls.Surface.values()) {
            String name = Controls.layers(s).get(feature);
            if (name != null) return name;
        }
        return feature;
    }
}
