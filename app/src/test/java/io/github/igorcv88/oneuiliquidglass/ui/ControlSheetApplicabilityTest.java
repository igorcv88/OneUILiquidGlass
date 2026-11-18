package io.github.igorcv88.oneuiliquidglass.ui;

import static org.junit.Assert.*;

import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import io.github.igorcv88.oneuiliquidglass.Config;
import io.github.igorcv88.oneuiliquidglass.MainActivity;
import io.github.igorcv88.oneuiliquidglass.R;
import io.github.igorcv88.oneuiliquidglass.config.Applicability;
import io.github.igorcv88.oneuiliquidglass.config.Controls;
import io.github.igorcv88.oneuiliquidglass.config.GlassMaterial;
import io.github.igorcv88.oneuiliquidglass.config.SystemPropertyReader;
import io.github.igorcv88.oneuiliquidglass.ui.kit.Sheet;
import io.github.igorcv88.oneuiliquidglass.ui.kit.Slider;
import java.util.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowDialog;

/**
 * The control editor (All Parameters, search, favorites, rows) follows the same applicability
 * rule as the main screens: an inactive control shows its reason and saved value but no slider,
 * exact field, reset or Save, and no programmatic save goes through; it becomes editable again,
 * with the value saved earlier, when the condition is met, even while the sheet is open.
 */
@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 33)
public class ControlSheetApplicabilityTest {
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

    private static void collect(View v, List<View> out) {
        out.add(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), out);
        }
    }

    private static List<View> views(Sheet s) {
        List<View> out = new ArrayList<>();
        collect(s.panel(), out);
        return out;
    }

    private static boolean has(Sheet s, Class<?> type) {
        for (View v : views(s)) if (type.isInstance(v) && v.getVisibility() == View.VISIBLE) return true;
        return false;
    }

    private static List<String> texts(Sheet s) {
        List<String> out = new ArrayList<>();
        for (View v : views(s))
            if (v instanceof TextView && !(v instanceof EditText) && v.isShown() && ((TextView) v).getText().length() > 0)
                out.add(((TextView) v).getText().toString());
        return out;
    }

    private static boolean contains(List<String> texts, String part) {
        for (String t : texts) if (t.contains(part)) return true;
        return false;
    }

    /** Clear on the pop-up, Gaussian kernel 1; adaptive blur keeps its non-zero default. */
    private static void gaussianAdaptive(MainActivity a) {
        assertTrue(a.model().selectMaterial(GlassMaterial.CLEAR).ok);
        assertTrue(a.model().save("sf.hckernel", "1").ok);
    }

    private static Sheet sheet(MainActivity a, String key) {
        a.openControl(key, Controls.Surface.HEADSUP);
        Dialog d = ShadowDialog.getLatestDialog();
        assertTrue("the editor opened", d instanceof Sheet && d.isShowing());
        return (Sheet) d;
    }

    @Test
    public void anInactiveControlOffersNoEditingAndRefusesProgrammaticSaves() {
        MainActivity a = open();
        assertTrue(a.model().save("sf.hubloom", "1").ok);
        gaussianAdaptive(a);
        assertEquals(Applicability.Kind.INACTIVE,
                a.model().state().applicability().on("sf.hubloom", Controls.Surface.HEADSUP).kind);

        Sheet s = sheet(a, "sf.hubloom");
        List<String> shown = texts(s);
        assertFalse("no slider", has(s, Slider.class));
        assertFalse("no exact field", has(s, EditText.class));
        assertFalse("no Save: " + shown, shown.contains(a.getString(R.string.action_save)));
        assertFalse("no reset: " + shown, contains(shown, a.getString(R.string.control_default, "").trim()));
        assertTrue("the reason names the way out: " + shown, contains(shown, "Adaptive blur") || contains(shown, "Desfoque adaptativo"));
        assertTrue(shown.toString(), contains(shown, a.getString(R.string.control_effective_none)));

        AppModel.Result r = a.model().saveControl("sf.hubloom", "1.5", Controls.Surface.HEADSUP);
        assertFalse(r.ok);
        assertFalse(r.conflict);
        assertEquals("the value saved earlier is kept", "1", a.model().state().saved.get("sf.hubloom"));
    }

    @Test
    public void theSheetFollowsTheConditionWhileOpenAndKeepsTheSavedValue() {
        MainActivity a = open();
        assertTrue(a.model().save("sf.hubloom", "1").ok);
        gaussianAdaptive(a);
        Sheet s = sheet(a, "sf.hubloom");
        assertFalse(has(s, Slider.class));

        // Adaptive blur off: kernel 1 compiles oulg_disc0 again and reads hubloom.
        assertTrue(a.model().save("sf.hcadapt", "0").ok);
        assertEquals(Applicability.Kind.NEEDS_RESTART,
                a.model().state().applicability().on("sf.hubloom", Controls.Surface.HEADSUP).kind);
        assertTrue("NEEDS_RESTART is editable", has(s, Slider.class));
        assertTrue(has(s, EditText.class));
        assertTrue(texts(s).contains(a.getString(R.string.action_save)));
        assertTrue(a.model().saveControl("sf.hubloom", "1.25", Controls.Surface.HEADSUP).ok);

        // Back on: locked again, the value stays saved for when it applies.
        assertTrue(a.model().save("sf.hcadapt", "0.5").ok);
        assertFalse(has(s, Slider.class));
        assertFalse(texts(s).contains(a.getString(R.string.action_save)));
        assertEquals("1.25", a.model().state().saved.get("sf.hubloom"));
    }

    /**
     * R5: the editor locked sf.hubloom, but the restart would still have written the value saved
     * earlier. An inactive control is neither listed as pending nor published; it stays saved and
     * returns to the pending list when the condition is met.
     */
    @Test
    public void anInactiveSavedValueIsNeitherPendingNorPublished() {
        MainActivity a = open();
        assertTrue(a.model().save("sf.hubloom", "1").ok);
        gaussianAdaptive(a);
        assertTrue(a.model().save("sf.hclift", "0.4").ok);
        Map<String, String> pending = a.model().pendingCompositor();
        assertFalse("locked control is not published: " + pending, pending.containsKey("sf.hubloom"));
        assertTrue("other saved values still apply: " + pending, pending.containsKey("sf.hclift"));
        assertEquals("kept in the app", "1", a.model().state().saved.get("sf.hubloom"));
        assertTrue(a.model().save("sf.hcadapt", "0").ok);
        assertEquals("1", a.model().pendingCompositor().get("sf.hubloom"));
    }

    @Test
    public void opticalParametersAreNotDescribedAsTiming() {
        for (String key : new String[] {"sf.hubloom", "sf.hulight", "sf.kglight", "sf.qslight", "sf.qslens",
                "sf.qsbody", "sf.qsdepth", "qs.edgeblur", "popuplinegain", "kglinegain", "huoptics", "kgoptics"})
            assertEquals(key, Controls.Preview.ON_DEVICE, Controls.get(key).preview);
        assertEquals(Controls.Preview.BEHAVIOR, Controls.get("lenssettle").preview);
    }
}
