package io.github.igorcv88.oneuiliquidglass.config;

import static org.junit.Assert.*;

import io.github.igorcv88.oneuiliquidglass.ui.preview.PreviewParams;

import org.junit.Test;

import java.lang.reflect.Field;
import java.util.*;
import java.util.function.Function;

/** Catalogue coverage, mode rules, value precedence and the preview contract. */
public class ControlModelTest {
    private static final Map<String, String> LENS = Collections.singletonMap("sfrefract", "1");
    private static final Map<String, String> CAPTURE = Collections.singletonMap("backdrop", "hybrid");

    private static ControlState state(Map<String, String> observed, Map<String, String> saved) {
        return new ControlState(saved, observed, Collections.emptyMap(), true);
    }

    private static Function<String, String> with(Function<String, String> base, String k, String v) {
        return key -> key.equals(k) ? v : base.apply(key);
    }

    @Test
    public void everyRegistryKeyHasExactlyOnePresentation() {
        Set<String> registry = new TreeSet<>(), catalogue = new TreeSet<>();
        for (SettingSpec s : SettingsRegistry.all()) registry.add(s.key);
        for (Controls.Control c : Controls.all()) {
            catalogue.add(c.key);
            assertFalse(c.key, c.title.isEmpty());
            assertFalse("help " + c.key, c.help.isEmpty());
            assertFalse("surfaces " + c.key, c.surfaces.isEmpty());
            assertFalse("no engineering names in titles: " + c.key,
                    c.title.matches("(?i).*(systemui|surfaceflinger|\\bsf\\b|shader|apk|prop).*"));
        }
        assertEquals(registry, catalogue);
    }

    @Test
    public void choicesCoverEveryValidValue() {
        for (Controls.Control c : Controls.all()) {
            if (c.format != Controls.Format.CHOICE) continue;
            SettingSpec s = c.spec();
            if (s.type == SettingSpec.Type.ENUM)
                assertEquals(c.key, new TreeSet<>(Arrays.asList(s.choices.split(","))),
                        new TreeSet<>(c.choices.keySet()));
            for (String v : c.choices.keySet()) assertEquals(c.key, v, s.validate(v));
        }
    }

    @Test
    public void helperStateIsNeverAnEditableControl() {
        for (String key : new String[] {"sfrefract", "sf.optics", "sf.norewrite"})
            assertFalse(key, Controls.editable(key));
        assertTrue(Controls.editable("sf.kgcore"));
        assertTrue(Controls.editable("liveradius"));
    }

    @Test
    public void modeFollowsTuningDerivation() {
        assertEquals(Mode.STANDARD, state(Collections.emptyMap(), Collections.emptyMap()).applicability().mode);
        assertEquals(Mode.DYNAMIC_LENS, state(LENS, Collections.emptyMap()).applicability().mode);
        assertEquals(Mode.CAPTURE, state(CAPTURE, Collections.emptyMap()).applicability().mode);
        Map<String, String> off = new HashMap<>();
        off.put("liveblur", "0");
        off.put("backdrop", "off");
        assertEquals(Mode.PLAIN_BLUR, state(off, Collections.emptyMap()).applicability().mode);
        // An unknown backdrop reads as auto, like Tuning.
        assertEquals(Mode.STANDARD,
                state(Collections.singletonMap("backdrop", "nonsense"), Collections.emptyMap())
                        .applicability().mode);
    }

    @Test
    public void primaryControlsAreUsableInTheirMode() {
        for (Map<String, String> observed : Arrays.asList(Collections.<String, String>emptyMap(), LENS, CAPTURE)) {
            Applicability app = state(observed, Collections.emptyMap()).applicability();
            for (Controls.Surface s : Controls.Surface.values())
                for (String key : Controls.primary(s, app.mode)) {
                    assertNotNull(key, Controls.get(key));
                    assertTrue(app.mode + " " + s + " " + key + " " + app.on(key, s),
                            app.on(key, s).usable());
                    assertTrue(key + " shown on " + s, Controls.get(key).on(s));
                }
        }
    }

    @Test
    public void lensBandParametersApplyInStandardModeNotOnlyCapture() {
        // Registry said backdrop=capture|hybrid|grid; GlassDrawable.drawLens uses them for the
        // standard-mode pop-up band too.
        Applicability std = state(Collections.emptyMap(), Collections.emptyMap()).applicability();
        assertTrue(std.rule("lens").usable());
        assertTrue(std.rule("disp").usable());
        assertFalse(std.rule("blur").usable());
        Applicability noBand = state(Collections.emptyMap(), Collections.singletonMap("livelens", "0")).applicability();
        assertFalse(noBand.rule("lens").usable());
        assertEquals("Ative a faixa de lente no pop-up", noBand.rule("lens").reason);
    }

    @Test
    public void standardOnlyAndLensOnlyKeysAreExclusive() {
        Applicability std = state(Collections.emptyMap(), Collections.emptyMap()).applicability();
        Applicability lens = state(LENS, Collections.emptyMap()).applicability();
        for (String key : new String[] {"liveradius", "liverim", "liveglint", "kgdarkfill", "livelens"}) {
            assertTrue(key, std.rule(key).usable());
            assertFalse(key, lens.rule(key).usable());
        }
        for (String key : new String[] {"sflens", "semradiuslens", "kgoptics"}) {
            assertFalse(key, std.rule(key).usable());
            assertTrue(key, lens.rule(key).usable());
        }
        assertEquals(Applicability.Kind.NEEDS_RESTART, lens.rule("sf.kgcore").kind);
        assertEquals(Applicability.Kind.INACTIVE, std.rule("sf.kgcore").kind);
        // kglens only with the lockscreen material.
        Map<String, String> material0 = new HashMap<>(LENS);
        material0.put("kgoptics", "0");
        assertFalse(state(material0, Collections.emptyMap()).applicability().rule("kglens").usable());
    }

    @Test
    public void switchesGateControlsAndLayers() {
        Map<String, String> saved = new HashMap<>();
        saved.put("surface.headsup.feature.glint.enabled", "0");
        Applicability app = state(Collections.emptyMap(), saved).applicability();
        assertEquals(Applicability.Kind.INACTIVE, app.on("liveglint", Controls.Surface.HEADSUP).kind);
        assertTrue(app.on("kgglint", Controls.Surface.KEYGUARD).usable());
        saved.put("surface.keyguard.enabled", "0");
        app = state(Collections.emptyMap(), saved).applicability();
        assertEquals(Applicability.Kind.OFF, app.on("kgrim", Controls.Surface.KEYGUARD).kind);
        // A shared key stays usable while one of its surfaces is on.
        assertTrue(app.on("lightglass", null).usable());
        ControlState off = new ControlState(Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(), false);
        assertEquals(Applicability.Kind.OFF, off.applicability().on("liveradius", Controls.Surface.HEADSUP).kind);
        // Glint layer does nothing with the lens; the shade has no lens layer.
        Applicability lens = state(LENS, Collections.emptyMap()).applicability();
        assertFalse(lens.layer(Controls.Surface.HEADSUP, "glint").usable());
        assertFalse(app.layer(Controls.Surface.SHADE, "lens").usable());
    }

    @Test
    public void devicePrecedenceMatchesConfigClientAndTheCompositor() {
        Map<String, String> observed = new HashMap<>(), saved = new HashMap<>();
        observed.put("liveradius", "60");
        observed.put("sf.kgcore", "70");
        observed.put("sfrefract", "1");
        ControlState s = state(observed, saved);
        assertEquals("60", s.device("liveradius"));
        assertEquals("60", s.shown("liveradius"));
        saved.put("liveradius", "90");
        saved.put("sf.kgcore", "100");
        saved.put("sfrefract", "0"); // never honoured: the injector owns it
        s = state(observed, saved);
        assertEquals("90", s.device("liveradius"));
        assertEquals("70", s.device("sf.kgcore"));
        assertEquals("100", s.shown("sf.kgcore"));
        assertTrue(s.pendingRestart("sf.kgcore"));
        assertFalse(s.pendingRestart("liveradius"));
        assertEquals("1", s.shown("sfrefract"));
        assertEquals("120", s.withDraft("liveradius", "120").shown("liveradius"));
        assertEquals("90", s.withDraft("liveradius", "120").device("liveradius"));
        observed.put("sf.kgcore", "100");
        assertFalse(state(observed, saved).pendingRestart("sf.kgcore"));
    }

    @Test
    public void flagsDefaultOnAndAppearInTheSharedMap() {
        Map<String, String> m = state(Collections.emptyMap(), Collections.singletonMap("surface.shade.enabled", "0")).shownMap();
        assertEquals("0", m.get("surface.shade.enabled"));
        assertEquals("1", m.get("surface.headsup.enabled"));
        assertEquals("1", m.get("surface.keyguard.feature.glint.enabled"));
        assertEquals("1", m.get("enabled"));
    }

    @Test
    public void valuesReadInRealUnits() {
        assertEquals("80 px", ValueFormat.display(Controls.get("liveradius"), "80"));
        assertEquals("4 %", ValueFormat.display(Controls.get("livedarkfill"), "10"));
        assertEquals("75 %", ValueFormat.display(Controls.get("lightglass"), "0.75"));
        assertEquals("×2,00", ValueFormat.display(Controls.get("liverim"), "2"));
        assertEquals("Automático", ValueFormat.display(Controls.get("kgblurradius"), "-1"));
        assertEquals("0,45", ValueFormat.display(Controls.get("sflens"), "0.45"));
        assertEquals("81°", ValueFormat.display(Controls.get("sf.kgtilt"), "1.42"));
        assertEquals("Adaptativa", ValueFormat.display(Controls.get("shadetone"), "3"));
        assertEquals("0.6", ValueFormat.fromEdit(Controls.get("lightglass"), "60"));
        assertEquals("26", ValueFormat.fromEdit(Controls.get("livedarkfill"), "10 %"));
        assertEquals("0.45", ValueFormat.fromEdit(Controls.get("sflens"), "0,45"));
        assertThrows(IllegalArgumentException.class, () -> ValueFormat.fromEdit(Controls.get("liveradius"), "900"));
        assertThrows(IllegalArgumentException.class, () -> ValueFormat.fromEdit(Controls.get("liveradius"), "abc"));
        assertThrows(IllegalArgumentException.class, () -> ValueFormat.fromEdit(Controls.get("kglens"), "0.05"));
    }

    // ---- Preview contract ----


    private static Map<String, Object> fields(PreviewParams p) throws IllegalAccessException {
        Map<String, Object> m = new TreeMap<>();
        for (Field f : PreviewParams.class.getFields()) m.put(f.getName(), f.get(p));
        return m;
    }

    /** Contexts in which a control could be active: mode overrides x theme x backdrop luma. */
    private static List<Map<String, String>> contexts(String key) {
        List<Map<String, String>> out = new ArrayList<>();
        for (Map<String, String> mode : Arrays.asList(Collections.<String, String>emptyMap(), LENS, CAPTURE)) {
            Map<String, String> m = new HashMap<>(mode);
            if (key.equals("pillblur") && !mode.isEmpty()) continue;
            if (key.equals("shadescaleveil")) m.put("shadetone", "2");
            if (key.startsWith("sf.shade")) m.put("shadetone", "1");
            if (key.equals("refract") || key.equals("ior")) m.put("profile", "snell");
            out.add(m);
            // The veil limits matter where the card keeps its style: text swap off.
            Map<String, String> noSwap = new HashMap<>(m);
            noSwap.put("textswap", "0");
            out.add(noSwap);
        }
        return out;
    }

    @Test
    public void everyRepresentedControlVisiblyChangesThePreview() throws Exception {
        List<String> failures = new ArrayList<>();
        for (Controls.Control c : Controls.all()) {
            if (c.channel == Controls.Channel.NONE
                    || (c.preview != Controls.Preview.FAITHFUL && c.preview != Controls.Preview.DIDACTIC))
                continue;
            SettingSpec s = c.spec();
            String lo, hi;
            if (s.type == SettingSpec.Type.BOOLEAN) { lo = "0"; hi = "1"; }
            else if (s.type == SettingSpec.Type.RGB) { lo = "ffffff"; hi = "ff8040"; }
            else if (s.type == SettingSpec.Type.ENUM) { lo = c.choices.keySet().iterator().next(); hi = "0"; }
            else {
                lo = ValueFormat.plain(c.autoLabel != null ? Math.max(0, s.min) : s.min);
                if (c.key.equals("kglens")) lo = "0.1";
                hi = ValueFormat.plain(s.max);
            }
            boolean changed = false;
            search:
            for (Map<String, String> ctx : contexts(c.key))
                for (Controls.Surface surface : c.surfaces)
                    for (boolean light : new boolean[] {false, true})
                        for (float luma : new float[] {.15f, .5f, .9f}) {
                            Map<String, String> base = new HashMap<>(ctx);
                            ControlState st = state(base, Collections.emptyMap());
                            Function<String, String> f = st::shown;
                            PreviewParams a = PreviewParams.of(with(f, c.key, lo), surface, light, luma);
                            PreviewParams b = PreviewParams.of(with(f, c.key, hi), surface, light, luma);
                            if (!fields(a).equals(fields(b))) { changed = true; break search; }
                        }
            if (!changed) failures.add(c.key + " (" + c.preview + "/" + c.channel + ")");
        }
        assertTrue("Controls promising a preview with no visible change: " + failures, failures.isEmpty());
    }

    @Test
    public void switchesTurnPreviewLayersOff() {
        ControlState on = state(Collections.emptyMap(), Collections.emptyMap());
        PreviewParams base = PreviewParams.of(on::shown, Controls.Surface.HEADSUP, false, .9f);
        assertFalse(base.nativeCard);
        assertTrue(base.rim > 0 && base.glint > 0 && base.lens > 0);
        assertNotEquals(0, base.veil | base.region);
        for (String[] t : new String[][] {
                {"surface.headsup.enabled", "nativeCard"},
                {"surface.headsup.feature.edge.enabled", "rim"},
                {"surface.headsup.feature.glint.enabled", "glint"},
                {"surface.headsup.feature.lens.enabled", "lens"}}) {
            ControlState off = state(Collections.emptyMap(), Collections.singletonMap(t[0], "0"));
            PreviewParams p = PreviewParams.of(off::shown, Controls.Surface.HEADSUP, false, .9f);
            switch (t[1]) {
                case "nativeCard": assertTrue(p.nativeCard); break;
                case "rim": assertEquals(0f, p.rim, 0); assertEquals(0f, p.sheen, 0); break;
                case "glint": assertEquals(0f, p.glint, 0); break;
                default: assertEquals(0f, p.lens, 0);
            }
            // The other surfaces are untouched.
            PreviewParams kg = PreviewParams.of(off::shown, Controls.Surface.KEYGUARD, false, .9f);
            assertFalse(t[0], kg.nativeCard);
        }
        ControlState tone = state(Collections.emptyMap(), Collections.singletonMap("surface.shade.feature.tonemap.enabled", "0"));
        PreviewParams withTone = PreviewParams.of(on::shown, Controls.Surface.SHADE, false, .9f);
        PreviewParams noTone = PreviewParams.of(tone::shown, Controls.Surface.SHADE, false, .9f);
        assertTrue(withTone.lightGlass || withTone.veil != 0);
        assertFalse(noTone.lightGlass);
        assertEquals(0, noTone.veil);
        ControlState global = new ControlState(Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(), false);
        assertTrue(PreviewParams.of(global::shown, Controls.Surface.SHADE, false, .5f).nativeCard);
    }
}
