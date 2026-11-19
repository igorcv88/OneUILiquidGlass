package io.github.igorcv88.oneuiliquidglass.ui;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;
import io.github.igorcv88.oneuiliquidglass.Config;
import io.github.igorcv88.oneuiliquidglass.MainActivity;
import io.github.igorcv88.oneuiliquidglass.R;
import io.github.igorcv88.oneuiliquidglass.config.Controls;
import io.github.igorcv88.oneuiliquidglass.config.GlassMaterial;
import io.github.igorcv88.oneuiliquidglass.config.SettingsRegistry;
import io.github.igorcv88.oneuiliquidglass.config.SystemPropertyReader;
import io.github.igorcv88.oneuiliquidglass.live.CompositorValues;
import io.github.igorcv88.oneuiliquidglass.live.PublicationPlan;
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
 * The single edit guard and the Apply revalidation, through the model: resets keep inactive
 * values, drafts never reach rules or the plan, dependent thresholds keep their order, and a
 * confirmation made on an older list is detected as stale.
 */
@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 33)
public class AppModelGuardTest {
    private static final Controls.Surface HU = Controls.Surface.HEADSUP;
    private Context context;
    private SharedPreferences config;
    private ActivityController<MainActivity> controller;

    @Before
    public void reset() {
        context = RuntimeEnvironment.getApplication();
        config = Config.open(context);
        config.edit().clear().putBoolean(Config.KEY_ENABLED, true).commit();
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

    private static void observe(Map<String, String> values) {
        lastObserved = values;
        io.github.igorcv88.oneuiliquidglass.config.ReaderState.set(values, !values.isEmpty());
    }

    private static Map<String, String> map(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private MainActivity open() {
        controller = Robolectric.buildActivity(MainActivity.class).setup();
        // The activity starts a real getprop read, which fails off a device and marks the reader
        // unavailable: put the test's read back (an unreadable system blocks the plan by design).
        observe(lastObserved);
        return controller.get();
    }

    private static Map<String, String> lastObserved = Collections.emptyMap();

    /** Clear pop-up on Gaussian kernel 1 with adaptive blur: sf.hubloom is inactive. */
    private static void gaussianAdaptive(MainActivity a) {
        assertTrue(a.model().selectMaterial(GlassMaterial.CLEAR).ok);
        assertTrue(a.model().save("sf.hckernel", "1").ok);
    }

    @Test
    public void resetKeepsTheLatentValueOfAnInactiveKeyAndResetsTheUsableOnes() {
        MainActivity a = open();
        assertTrue(a.model().save("sf.hubloom", "1").ok);
        gaussianAdaptive(a);
        assertTrue(a.model().save("sf.hclift", "0.4").ok);
        List<String> refused = a.model().reset(Arrays.asList("sf.hubloom", "sf.hclift"), HU);
        assertEquals(Collections.singletonList("sf.hubloom"), refused);
        assertEquals("the latent value is untouched", "1", a.model().state().saved.get("sf.hubloom"));
        assertEquals(Double.parseDouble(SettingsRegistry.get("sf.hclift").defaultValue),
                Double.parseDouble(a.model().state().saved.get("sf.hclift")), 1e-9);
    }

    @Test
    public void aRowResetOfAnInactiveKeyIsRefusedLikeAnEdit() {
        MainActivity a = open();
        assertTrue(a.model().save("sf.hubloom", "1").ok);
        gaussianAdaptive(a);
        AppModel.Result r = a.model().saveControl("sf.hubloom", "0", HU,
                io.github.igorcv88.oneuiliquidglass.config.state.EditGuard.Action.RESET_ONE);
        assertFalse(r.ok);
        assertEquals("1", a.model().state().saved.get("sf.hubloom"));
    }

    @Test
    public void aDraftNeverUnlocksAControlOrEntersThePlan() throws Exception {
        MainActivity a = open();
        assertTrue(a.model().selectMaterial(GlassMaterial.CLEAR).ok);
        assertTrue(a.model().save("sf.hubloom", "1").ok);
        PublicationPlan before = a.model().plan();
        // Dragging the kernel to 1 (not saved) would make hubloom inactive in a draft-aware rule.
        a.model().draft("sf.hckernel", "1");
        assertTrue(a.model().saveControl("sf.hubloom", "1.5", HU).ok);
        assertEquals(before.writes.keySet(), a.model().plan().writes.keySet());
        a.model().draft("sf.hubloom", "0.2");
        assertEquals("1.5", a.model().plan().writes.get("sf.hubloom").value);
    }

    @Test
    public void theStyleThresholdsKeepTheirOrder() {
        MainActivity a = open();
        AppModel.Result bad = a.model().saveControl("stylelo", "0.7", HU);
        assertFalse(bad.ok);
        assertEquals(context.getString(R.string.error_style_order), bad.message);
        assertNull(a.model().state().saved.get("stylelo"));
        assertFalse(a.model().saveControl("stylehi", "0.4", HU).ok);
        assertTrue(a.model().saveControl("stylelo", "0.5", HU).ok);
        assertTrue(a.model().saveControl("stylehi", "0.8", HU).ok);
        assertFalse("0.8 is now the other limit", a.model().saveControl("stylelo", "0.8", HU).ok);
    }

    @Test
    public void anApplyConfirmationOnAnOlderListIsStale() {
        MainActivity a = open();
        assertTrue(a.model().selectMaterial(GlassMaterial.CLEAR).ok);
        observe(map("sfrefract", "1", "sf.huclear", "1"));
        assertTrue(a.model().save("sf.hclift", "0.4").ok);
        String shown = a.model().plan().fingerprint;
        assertEquals(AppModel.PlanCheck.OK, a.model().confirmPlan(shown));
        assertTrue(a.model().save("sf.hclift", "0.5").ok);
        assertEquals(AppModel.PlanCheck.STALE, a.model().confirmPlan(shown));
    }

    @Test
    public void aTerminalChangeWhileTheDialogIsOpenMakesItStale() {
        MainActivity a = open();
        observe(map("sfrefract", "1", "sf.huclear", "1", "sf.hcsat", "1.05"));
        CompositorValues.recordCompiled(a.model().lensPrefs(), map("sf.huclear", "1", "sf.hcsat", "1.05"), 1, "g");
        a.model().notifyChanged();
        PublicationPlan plan = a.model().plan();
        assertFalse(plan.needsRestart());
        assertEquals(AppModel.PlanCheck.OK, a.model().confirmPlan(plan.fingerprint));
        observe(map("sfrefract", "1", "sf.huclear", "1", "sf.hcsat", "1.2"));
        assertEquals(AppModel.PlanCheck.STALE, a.model().confirmPlan(plan.fingerprint));
        assertEquals("the pending list is the plan's", a.model().plan().listed(), a.model().pendingCompositor());
    }

    @Test
    public void undoIsAReviewThatChangesNothingUntilItIsApplied() {
        MainActivity a = open();
        assertTrue(a.model().save("sf.hcsat", "1.2").ok);
        assertTrue(a.model().save("sf.hcsat", "1.5").ok);
        io.github.igorcv88.oneuiliquidglass.config.ImportPlan plan = a.model().undoPlan();
        assertNotNull(plan);
        assertEquals("1.2", plan.result.get("sf.hcsat"));
        assertEquals("reviewing saves nothing", "1.5", a.model().state().saved.get("sf.hcsat"));
        a.model().applyPlan(plan, io.github.igorcv88.oneuiliquidglass.config.ConfigRepository.Source.UNDO);
        assertEquals("1.2", a.model().state().saved.get("sf.hcsat"));
        assertEquals(io.github.igorcv88.oneuiliquidglass.config.ConfigRepository.Source.UNDO,
                a.model().repo.provenance("sf.hcsat").source);
    }

    @Test
    public void adoptingAnExternalValueSavesItWithItsOwnProvenance() {
        MainActivity a = open();
        assertTrue(a.model().adoptExternal("sf.hcsat", "1.3").ok);
        assertEquals("1.3", a.model().state().saved.get("sf.hcsat"));
        assertEquals(io.github.igorcv88.oneuiliquidglass.config.ConfigRepository.Source.ADOPT,
                a.model().repo.provenance("sf.hcsat").source);
        assertFalse("an invalid value is refused", a.model().adoptExternal("sf.hcsat", "bad").ok);
    }

    @Test
    public void anUnloadedLensWithTheIntentSavedShowsPendingActivationNotInactive() {
        MainActivity a = open();
        observe(map("helper", "1"));
        assertTrue(a.model().selectMaterial(GlassMaterial.CLEAR).ok);
        io.github.igorcv88.oneuiliquidglass.config.state.LensIntent.record(a.model().lensPrefs(), true);
        a.model().notifyChanged();
        assertTrue(a.model().rules().pendingActivation("sf.hcsat", HU));
        assertTrue(a.model().rules().on("sf.hcsat", HU).usable());
    }
}
