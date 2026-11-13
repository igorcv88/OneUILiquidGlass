package io.github.igorcv88.oneuiliquidglass.ui;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import io.github.igorcv88.oneuiliquidglass.Config;
import io.github.igorcv88.oneuiliquidglass.MainActivity;
import io.github.igorcv88.oneuiliquidglass.config.Controls;
import io.github.igorcv88.oneuiliquidglass.config.GlassMaterial;
import io.github.igorcv88.oneuiliquidglass.config.SystemPropertyReader;
import io.github.igorcv88.oneuiliquidglass.i18n.AppLanguage;
import io.github.igorcv88.oneuiliquidglass.ui.kit.Segmented;
import java.util.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;

/**
 * The Frosted/Clear selector on Home and the controls each surface shows for it, driven through
 * the real activity: the choice only saves, hidden values survive, and nothing privileged runs.
 */
@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 33)
public class GlassMaterialUiTest {
    private Context context;
    private SharedPreferences config;
    private ActivityController<MainActivity> controller;

    @Before
    public void reset() {
        context = RuntimeEnvironment.getApplication();
        config = Config.open(context);
        config.edit().clear().commit();
        context.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("sf_live_ui", Context.MODE_PRIVATE).edit().clear().commit();
        observe(Collections.singletonMap("sfrefract", "1"));
    }

    @After
    public void cleanUp() {
        if (controller != null) controller.pause().stop().destroy();
        observe(Collections.emptyMap());
        config.edit().clear().commit();
        context.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().clear().commit();
        context.getSharedPreferences("sf_live_ui", Context.MODE_PRIVATE).edit().clear().commit();
    }

    /** The property snapshot the screens read; the real one is filled by an off-thread getprop. */
    private static void observe(Map<String, String> values) {
        try {
            java.lang.reflect.Field f = SystemPropertyReader.class.getDeclaredField("observed");
            f.setAccessible(true);
            f.set(null, Collections.unmodifiableMap(new HashMap<>(values)));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private MainActivity open() {
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        return controller.get();
    }

    // ---- view helpers

    private static void collect(View v, List<String> texts, List<Segmented> segmented) {
        if (v instanceof TextView && ((TextView) v).getText().length() > 0) texts.add(((TextView) v).getText().toString());
        if (v instanceof Segmented) segmented.add((Segmented) v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), texts, segmented);
        }
    }

    private static List<String> texts(View root) {
        List<String> out = new ArrayList<>();
        collect(root, out, new ArrayList<>());
        return out;
    }

    private static Segmented materialChoice(MainActivity a) {
        List<Segmented> found = new ArrayList<>();
        collect(a.findViewById(android.R.id.content), new ArrayList<>(), found);
        for (Segmented s : found) {
            for (int i = 0; i < s.getChildCount(); i++)
                if (s.getChildAt(i) instanceof TextView
                        && ((TextView) s.getChildAt(i)).getText().toString().startsWith("Clear")) return s;
        }
        throw new AssertionError("no material selector on Home");
    }

    /** The surfaces screen: the root above the tab strip. */
    private static View surfacesPage(MainActivity a) {
        List<Segmented> found = new ArrayList<>();
        collect(a.findViewById(android.R.id.content), new ArrayList<>(), found);
        for (Segmented s : found)
            if (s.getChildCount() == 3) return (View) s.getParent().getParent();
        throw new AssertionError("no surfaces screen");
    }

    private List<String> surfaceTexts(MainActivity a, Controls.Surface surface) {
        a.showSurface(surface);
        return texts(surfacesPage(a));
    }

    // ---- Home

    @Test
    public void homeShowsTheMaterialChoiceInEnglishAndKeepsStylesAndTheMasterSwitch() {
        MainActivity a = open();
        List<String> t = texts(a.findViewById(android.R.id.content));
        for (String expected : new String[] {"Glass material", "Choose how pop-ups and lock-screen cards look.",
                "Frosted", "Clear (iOS style)", "Styles", "Surfaces"})
            assertTrue(expected + " in " + t, t.contains(expected));
        assertTrue(t.contains(a.getString(io.github.igorcv88.oneuiliquidglass.R.string.home_system_glass)));
        assertEquals(2, materialChoice(a).getChildCount());
        assertEquals("nothing chosen, nothing saved: the library default is Frosted", 0, materialChoice(a).selected());
    }

    @Test
    public void homeShowsTheMaterialChoiceInBrazilianPortuguese() {
        AppLanguage.set(context, AppLanguage.PORTUGUESE);
        MainActivity a = open();
        List<String> t = texts(a.findViewById(android.R.id.content));
        for (String expected : new String[] {"Material do vidro",
                "Escolha o visual dos pop-ups e cartões da tela de bloqueio.", "Fosco", "Transparente (estilo iOS)"})
            assertTrue(expected + " in " + t, t.contains(expected));
        assertFalse(t.contains("Glass material"));
    }

    @Test
    public void choosingClearOnlySavesTheTwoSelectorsAndRunsNothingPrivileged() {
        MainActivity a = open();
        Map<String, ?> before = new HashMap<>(config.getAll());
        materialChoice(a).select(1, true);
        assertEquals("1", config.getString("desired.sf.huclear", null));
        assertEquals("1", config.getString("desired.sf.kgclear", null));
        Map<String, Object> changed = new HashMap<>(config.getAll());
        changed.keySet().removeAll(before.keySet());
        for (String key : changed.keySet())
            assertTrue(key, key.startsWith("desired.sf.") || key.startsWith("baseline") || key.equals("undo")
                    || key.equals("undo.enabled") || key.equals("generation") || key.equals("lastChange"));
        assertEquals(GlassMaterial.CLEAR, a.model().material());
        assertFalse(a.model().root.granted());
        assertFalse(a.model().root.busy());
        assertFalse(a.model().lens.model().busy);
        assertTrue("no helper was asked", a.model().lens.model().status.isEmpty());
        List<String> t = texts(a.findViewById(android.R.id.content));
        assertTrue(t.toString(), t.contains("Saved, not applied yet. It takes effect after the compositor restarts."));
        assertTrue(t.contains("Review and apply"));
    }

    @Test
    public void openingTheAppAndItsScreensNeverWritesSettings() {
        observe(new HashMap<String, String>() {{
            put("sfrefract", "1");
            put("sf.huclear", "1");
            put("sf.kgclear", "1");
            put("sf.hcfold", "2");
        }});
        MainActivity a = open();
        a.showSurface(Controls.Surface.HEADSUP);
        a.showSurface(Controls.Surface.KEYGUARD);
        a.showSurface(Controls.Surface.SHADE);
        for (String key : config.getAll().keySet()) assertFalse(key, key.startsWith("desired."));
        // The Terminal's choice is shown, not replaced.
        assertEquals(GlassMaterial.CLEAR, a.model().material());
        assertEquals(1, materialChoice(a).selected());
    }

    @Test
    public void valuesSetOutsideTheSingleChoiceAreShownAsSuchAndNothingIsSelected() {
        observe(new HashMap<String, String>() {{
            put("sfrefract", "1");
            put("sf.huclear", "1");
            put("sf.kgclear", "2");
        }});
        MainActivity a = open();
        assertNull(a.model().material());
        assertEquals(-1, materialChoice(a).selected());
        List<String> t = texts(a.findViewById(android.R.id.content));
        assertTrue(t.toString(), t.contains("Set outside the app: pop-ups Clear (iOS style), lock screen Per card (advanced). Pick a material to set both."));
    }

    // ---- persistence across switches

    @Test
    public void switchingBackAndForthKeepsEverySavedValueOfBothMaterials() {
        MainActivity a = open();
        assertTrue(a.model().save("sf.hcfold", "2").ok);
        assertTrue(a.model().save("sf.kcedge", "20").ok);
        assertTrue(a.model().save("sf.core", "50").ok);
        assertTrue(a.model().save("sf.kgcore", "70").ok);
        for (GlassMaterial m : new GlassMaterial[] {GlassMaterial.CLEAR, GlassMaterial.FROSTED,
                GlassMaterial.CLEAR, GlassMaterial.FROSTED}) {
            assertTrue(a.model().selectMaterial(m).ok);
            assertEquals(m, a.model().material());
            assertEquals("2", config.getString("desired.sf.hcfold", null));
            assertEquals("20", config.getString("desired.sf.kcedge", null));
            assertEquals("50", config.getString("desired.sf.core", null));
            assertEquals("70", config.getString("desired.sf.kgcore", null));
        }
    }

    @Test
    public void theChoiceIsOneUndoStepAndTheSelectorsMoveTogether() {
        MainActivity a = open();
        assertTrue(a.model().selectMaterial(GlassMaterial.CLEAR).ok);
        assertEquals("1", config.getString("desired.sf.huclear", null));
        assertEquals("1", config.getString("desired.sf.kgclear", null));
        assertTrue(a.model().undo());
        assertNull(config.getString("desired.sf.huclear", null));
        assertNull(config.getString("desired.sf.kgclear", null));
    }

    @Test
    public void aPendingChoiceCanBeRevertedToWhatTheCompositorWasCompiledWith() {
        MainActivity a = open();
        // A verified compile with Frosted on both profiles.
        io.github.igorcv88.oneuiliquidglass.live.CompositorValues.recordCompiled(a.model().lensPrefs(),
                io.github.igorcv88.oneuiliquidglass.live.CompositorValues.restartInputs(
                        Collections.emptyMap(), GlassMaterial.FROSTED.selection()), 1000, "boot:1:1");
        assertEquals(GlassMaterial.FROSTED, a.model().compiledMaterial());
        assertFalse(a.model().canRevertMaterial());
        assertTrue(a.model().selectMaterial(GlassMaterial.CLEAR).ok);
        assertTrue(a.model().canRevertMaterial());
        assertTrue(a.model().pendingCompositor().containsKey("sf.huclear"));
        assertTrue(a.model().revertMaterial().ok);
        assertEquals(GlassMaterial.FROSTED, a.model().material());
        assertFalse(a.model().pendingCompositor().containsKey("sf.huclear"));
        assertFalse(a.model().canRevertMaterial());
    }

    // ---- the controls each surface shows

    @Test
    public void frostedShowsFrostedControlsAndHidesClearOnes() {
        MainActivity a = open();
        List<String> hu = surfaceTexts(a, Controls.Surface.HEADSUP);
        assertTrue(hu.toString(), hu.contains("Inner blur"));
        assertFalse(hu.toString(), hu.contains("Meniscus"));
        assertFalse(hu.contains("Adaptive blur"));
        List<String> kg = surfaceTexts(a, Controls.Surface.KEYGUARD);
        assertTrue(kg.contains("Uniformity"));
        assertFalse(kg.contains("Meniscus"));
    }

    @Test
    public void clearShowsItsOwnControlsPerSurfaceAndHidesTheReplacedOnes() {
        MainActivity a = open();
        assertTrue(a.model().selectMaterial(GlassMaterial.CLEAR).ok);
        List<String> hu = surfaceTexts(a, Controls.Surface.HEADSUP);
        for (String expected : new String[] {"Meniscus", "Outer edge blur", "Center blur", "Adaptive blur",
                "Glass saturation", "Brightening", "Neutral whites"})
            assertTrue(expected + " in " + hu, hu.contains(expected));
        assertFalse("replaced by the clear field", hu.contains("Inner blur"));
        assertTrue("shared knobs keep acting", hu.contains("Full lens refraction"));
        List<String> kg = surfaceTexts(a, Controls.Surface.KEYGUARD);
        assertTrue(kg.contains("Meniscus"));
        assertTrue(kg.contains("Brightening"));
        assertTrue(kg.contains("Leveling veil"));
        assertFalse("the lock screen has no adaptive blur", kg.contains("Adaptive blur"));
        assertFalse("pop-up only", kg.contains("Neutral whites"));
        assertFalse(kg.contains("Uniformity"));
    }

    @Test
    public void theMainListLeadsWithTheLookControlsInOrder() {
        MainActivity a = open();
        assertTrue(a.model().selectMaterial(GlassMaterial.CLEAR).ok);
        List<String> hu = surfaceTexts(a, Controls.Surface.HEADSUP);
        List<String> main = Arrays.asList("Meniscus", "Outer edge blur", "Center blur", "Adaptive blur",
                "Glass saturation", "Brightening");
        int last = -1;
        for (String title : main) {
            int at = hu.indexOf(title);
            assertTrue(title + " after the previous in " + hu, at > last);
            last = at;
        }
    }

    @Test
    public void thePopUpAndLockScreenFollowTheirOwnSelector() {
        observe(new HashMap<String, String>() {{
            put("sfrefract", "1");
            put("sf.huclear", "1");
            put("sf.kgclear", "0");
        }});
        MainActivity a = open();
        assertNull(a.model().material());
        List<String> hu = surfaceTexts(a, Controls.Surface.HEADSUP);
        assertTrue(hu.contains("Meniscus"));
        assertFalse(hu.contains("Inner blur"));
        List<String> kg = surfaceTexts(a, Controls.Surface.KEYGUARD);
        assertFalse(kg.contains("Meniscus"));
        assertTrue(kg.contains("Uniformity"));
    }

    @Test
    public void theShadeAndQuickSettingsAreNotAffectedByTheMaterial() {
        MainActivity a = open();
        List<String> frosted = surfaceTexts(a, Controls.Surface.SHADE);
        assertTrue(a.model().selectMaterial(GlassMaterial.CLEAR).ok);
        List<String> clear = surfaceTexts(a, Controls.Surface.SHADE);
        assertEquals(frosted, clear);
        assertTrue(clear.contains("Edge lighting (lens)"));
    }

    // ---- first use and values from the Terminal

    @Test
    public void aNewUserIsOfferedTheRecommendedClearSettingsAndNothingIsAppliedOnItsOwn() {
        MainActivity a = open();
        assertTrue(a.model().selectMaterial(GlassMaterial.CLEAR).ok);
        List<String> t = texts(a.findViewById(android.R.id.content));
        assertTrue(t.toString(), t.contains("Use recommended Clear settings"));
        assertFalse(t.contains("Keep this device's Clear settings"));
        for (String key : config.getAll().keySet())
            assertFalse("not applied by itself: " + key, key.startsWith("desired.sf.hc") || key.startsWith("desired.sf.kc"));
        Map<String, String> offer = a.model().recommendedClearOffer();
        assertEquals("2", offer.get("sf.hcfold"));
        assertTrue(a.model().saveClearValues(offer).ok);
        assertEquals("2", config.getString("desired.sf.hcfold", null));
        assertEquals("0.42", config.getString("desired.sf.kcband", null));
        assertTrue("offered once: values now exist", a.model().recommendedClearOffer().isEmpty());
    }

    @Test
    public void clearValuesAlreadyOnTheDeviceAreKeptAndOfferedForImportNotReplaced() {
        observe(new HashMap<String, String>() {{
            put("sfrefract", "1");
            put("sf.huclear", "1");
            put("sf.kgclear", "1");
            put("sf.hcfold", "2");
            put("sf.hclift", "0.5");
        }});
        MainActivity a = open();
        assertTrue("the preset is never offered over existing values", a.model().recommendedClearOffer().isEmpty());
        assertEquals(new TreeSet<>(Arrays.asList("sf.hcfold", "sf.hclift")),
                new TreeSet<>(a.model().clearValuesOnDevice().keySet()));
        List<String> t = texts(a.findViewById(android.R.id.content));
        assertTrue(t.toString(), t.contains("Keep this device's Clear settings"));
        assertFalse(t.contains("Use recommended Clear settings"));
        // The rows show what the device runs.
        assertEquals("2", a.model().state().shown("sf.hcfold"));
        assertEquals("0.5", a.model().state().shown("sf.hclift"));
        assertTrue(a.model().saveClearValues(a.model().clearValuesOnDevice()).ok);
        assertEquals("0.5", config.getString("desired.sf.hclift", null));
        assertTrue(a.model().clearValuesOnDevice().isEmpty());
    }

    @Test
    public void withoutTheLensClearIsSavedButCannotBeApplied() {
        observe(Collections.emptyMap());
        MainActivity a = open();
        assertTrue(a.model().selectMaterial(GlassMaterial.CLEAR).ok);
        assertEquals("1", config.getString("desired.sf.huclear", null));
        List<String> t = texts(a.findViewById(android.R.id.content));
        assertTrue(t.toString(), t.contains("Saved. Clear needs the dynamic lens, which is not running."));
        assertTrue(t.contains("Set up"));
        // Frosted keeps working and says there is nothing to apply.
        assertTrue(a.model().selectMaterial(GlassMaterial.FROSTED).ok);
        assertTrue(texts(a.findViewById(android.R.id.content)).contains("Standard look. Nothing to apply."));
    }
}
