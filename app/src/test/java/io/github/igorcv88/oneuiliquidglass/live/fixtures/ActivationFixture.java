package io.github.igorcv88.oneuiliquidglass.live.fixtures;

import io.github.igorcv88.oneuiliquidglass.live.CompiledEvidence;
import io.github.igorcv88.oneuiliquidglass.config.state.ConfirmedSnapshot;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * A synthetic equivalent of the activation report (ClearGlass_Visual_Handoff/OULG_R6_ADENDO_ATIVACAO):
 * the phone restarted, so the volatile properties are gone and the helper reads them successfully as
 * an empty map; the lens is installed but not loaded; the saved settings are intact (Clear, kernel 1,
 * adaptive blur on, Lift 0.30 and Bloom 1); and the last compile belongs to another compositor
 * generation. Values only: no device, no timestamps of the person.
 */
public final class ActivationFixture {
    public static final String OLD_GENERATION = "g-old", NEW_GENERATION = "g-new";

    private ActivationFixture() {}

    private static Map<String, String> pairs(String... kv) {
        Map<String, String> m = new TreeMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    /** Every saved value of the report (flags included), dormant ones too. */
    public static Map<String, String> desired() {
        return pairs(
            "blur", "8",
            "disp", "0.1",
            "headergap", "7",
            "headertone", "0",
            "hublurpath", "1",
            "huoptics", "0",
            "hz", "15",
            "hzkg", "5",
            "iconcardsize", "0.6",
            "iconmode", "3",
            "icontone", "1",
            "ior", "1.5",
            "kgblurpath", "1",
            "kgblurradius", "12",
            "kgdarkalpha", "20",
            "kgdarkfill", "5",
            "kgglint", "0.1",
            "kglens", "0.4",
            "kglinegain", "0.8",
            "kgoptics", "1",
            "kgrim", "2",
            "kgsharedoptics", "1",
            "kgwinblur", "0",
            "lensgate", "directional",
            "lensmaxage", "40",
            "lenssettle", "100",
            "lenstransition", "350",
            "lightedge", "1",
            "lightglass", "0.75",
            "lightoutline", "0.8",
            "lighttone", "ffffff",
            "liveblur", "1",
            "livedarkalpha", "20",
            "livedarkfill", "0",
            "liveglint", "0.1",
            "liveoptics", "1",
            "liveradius", "80",
            "liverim", "2",
            "liveshaderadius", "60",
            "livesheen", "0.02",
            "orphandraw", "1",
            "panelblurcap", "70",
            "paneldim", "0.08",
            "pillblur", "40",
            "pillglass", "1",
            "popuplinegain", "1.3",
            "popuplock", "1",
            "popuptone", "1",
            "profile", "lens",
            "qs.darkrim", "0.28",
            "qs.edgeblur", "6",
            "qs.fill", "0.85",
            "qs.lightcolor", "ffffffff",
            "qs.lightfill", "0",
            "qs.lightglassopacity", "0",
            "qs.lightoptics", "0.36",
            "qs.lightrim", "0.18",
            "qs.sliderdarkcolor", "f1f7ffe0",
            "qs.sliderlightcolor", "000c2189",
            "qs.tiledark", "1",
            "qs.tiledarkoff", "e6f0ff40",
            "qs.tiledarkoffink", "ffffffe6",
            "qs.tiledarkon", "ffffffd9",
            "qs.tiledarkonink", "17202ff2",
            "qs.tilelightoff", "24324666",
            "qs.tilelightoffalpha", "0.24",
            "qs.tilelightoffink", "000c21cd",
            "qs.tilelighton", "000c21cd",
            "qs.tilelightonink", "ffffffeb",
            "refract", "1",
            "rimblur", "24",
            "sat", "1.2",
            "semalpha", "0",
            "semcurve", "auto",
            "semradius", "0",
            "semradiuslens", "14",
            "semshape", "auto",
            "sf.core", "56",
            "sf.debug", "0",
            "sf.edgeblur", "8",
            "sf.hcadapt", "1",
            "sf.hcband", "0.42",
            "sf.hcblurexp", "1",
            "sf.hcbody", "0",
            "sf.hcbody2", "0",
            "sf.hccore", "32",
            "sf.hcedge", "30",
            "sf.hcfold", "2",
            "sf.hckernel", "1",
            "sf.hclift", "0.3",
            "sf.hcneutral", "1",
            "sf.hcsat", "1",
            "sf.hcscrim", "0",
            "sf.hcscrimmax", "0",
            "sf.hubloom", "1",
            "sf.hublur", "1.2",
            "sf.hubodylens", "0.06",
            "sf.huclear", "1",
            "sf.hurefract", "1.09",
            "sf.kcband", "0.42",
            "sf.kcbody", "0",
            "sf.kcbody2", "0",
            "sf.kccore", "24",
            "sf.kcedge", "24",
            "sf.kcfold", "2",
            "sf.kclift", "0",
            "sf.kgclear", "1",
            "sf.kgcore", "80",
            "sf.kgcoredim", "0",
            "sf.kgdepth", "4.4",
            "sf.kgfixedrim", "1",
            "sf.kgflat", "0",
            "sf.kgflatcap", "0.22",
            "sf.kglevel", "0.6",
            "sf.kglight", "0.5",
            "sf.kgramp", "1.5",
            "sf.kgrim", "0.72",
            "sf.kgsat", "0.95",
            "sf.kgspec", "0.17",
            "sf.kgtaps", "48",
            "sf.kgtilt", "1.42",
            "sf.kgtone", "0",
            "sf.ramp", "1",
            "sf.shadecap", "0.55",
            "sf.shadelift", "0.45",
            "sf.taps", "48",
            "sflens", "0.55",
            "shadeblur", "1",
            "shadeblurpath", "1",
            "shadecap", "0.55",
            "shadedarkglass", "0.14",
            "shadefadecut", "0.5",
            "shadeglint", "0.22",
            "shadelift", "0.45",
            "shadelightglass", "0.18",
            "shadelinegain", "1.3",
            "shadelocal", "0",
            "shadescaleveil", "115",
            "shadetone", "3",
            "statusiconsize", "0.9",
            "statusmaxicons", "4",
            "stylehi", "0.62",
            "stylelo", "0.48",
            "surface.headsup.enabled", "1",
            "surface.headsup.feature.lens.enabled", "1",
            "surface.keyguard.enabled", "1",
            "surface.keyguard.feature.lens.enabled", "1",
            "surface.keyguard.feature.tonemap.enabled", "1",
            "surface.liveactivity.enabled", "1",
            "surface.nowbar.enabled", "1",
            "surface.quicksettings.enabled", "1",
            "surface.shade.enabled", "1",
            "surface.shade.feature.per_card_blur.enabled", "1",
            "surface.toast.enabled", "1",
            "textswap", "1",
            "tint", "-1",
            "trace", "0",
            "veilmax", "0.25"
        );
    }

    /** The 75 inputs of the compile the previous system session confirmed. */
    public static Map<String, String> checkpoint() {
        return pairs(
            "sf.core", "56",
            "sf.debug", "0",
            "sf.edgeblur", "8",
            "sf.hcadapt", "1",
            "sf.hcamp", "0.40",
            "sf.hcband", "0.42",
            "sf.hcblurexp", "1",
            "sf.hcbody", "0",
            "sf.hcbody2", "0",
            "sf.hccore", "32",
            "sf.hcedge", "30",
            "sf.hcfold", "2",
            "sf.hchlneutral", "0",
            "sf.hckernel", "1",
            "sf.hclift", "0.4",
            "sf.hcneutral", "1",
            "sf.hcsat", "1",
            "sf.hcscrim", "0",
            "sf.hcscrimmax", "0",
            "sf.hubloom", "0",
            "sf.hublur", "1.2",
            "sf.hubodylens", "0.06",
            "sf.huclear", "1",
            "sf.hulight", "0.40",
            "sf.hurefract", "1.09",
            "sf.kcamp", "0.40",
            "sf.kcband", "0.42",
            "sf.kcblurexp", "2",
            "sf.kcbody", "0",
            "sf.kcbody2", "0",
            "sf.kccool", "0.50",
            "sf.kccore", "24",
            "sf.kcdim", "0",
            "sf.kcedge", "24",
            "sf.kcfold", "2",
            "sf.kcknee", "0",
            "sf.kclevel", "0.85",
            "sf.kclift", "0",
            "sf.kcsat", "1",
            "sf.kcveil", "0.04",
            "sf.kgband", "0.85",
            "sf.kgblur", "1",
            "sf.kgblurexp", "2",
            "sf.kgbodylens", "0.05",
            "sf.kgclear", "1",
            "sf.kgcool", "0.50",
            "sf.kgcore", "80",
            "sf.kgcorecap", "48",
            "sf.kgcoredim", "0",
            "sf.kgdepth", "4.4",
            "sf.kgedgeblur", "48",
            "sf.kgfixedrim", "1",
            "sf.kgflat", "0",
            "sf.kgflatcap", "0.22",
            "sf.kgglow", "0.70",
            "sf.kggradient", "1",
            "sf.kglevel", "0.6",
            "sf.kglight", "0.50",
            "sf.kgline", "0.62",
            "sf.kgramp", "1.5",
            "sf.kgrefract", "1",
            "sf.kgrim", "0.72",
            "sf.kgsat", "0.95",
            "sf.kgspec", "0.17",
            "sf.kgtaps", "48",
            "sf.kgtilt", "1.42",
            "sf.kgtone", "0",
            "sf.qsbody", "0.35",
            "sf.qsdepth", "18",
            "sf.qslens", "0.30",
            "sf.ramp", "1",
            "sf.shadecap", "0.55",
            "sf.shadelift", "0.45",
            "sf.shadelight", "0.30",
            "sf.taps", "48"
        );
    }

    /** The system properties read successfully: none are set after the restart. */
    public static Map<String, String> observedEmpty() {
        return Collections.emptyMap();
    }

    /** The helper's status: installed and self-tested, not loaded, a new compositor process. */
    public static Map<String, String> statusNotLoaded() {
        Map<String, String> s = new HashMap<>();
        s.put("helper", "1");
        s.put("fingerprint_ok", "1");
        s.put("update_needed", "0");
        s.put("installed_ok", "1");
        s.put("selftest_ok", "1");
        s.put("sf_loaded", "0");
        s.put("sf_generation", NEW_GENERATION);
        return s;
    }

    /** The compile of the previous session, which is history, not the current state. */
    public static CompiledEvidence historicalEvidence() {
        return CompiledEvidence.of(checkpoint(), OLD_GENERATION, 1_000, statusNotLoaded());
    }

    /** The report as the app saw it. */
    public static ConfirmedSnapshot snapshot() {
        return new ConfirmedSnapshot(desired(), observedEmpty(), historicalEvidence(), statusNotLoaded(),
                true, true, false, 7);
    }
}
