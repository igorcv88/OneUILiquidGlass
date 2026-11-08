package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import androidx.recyclerview.widget.RecyclerView;
import com.android.systemui.shared.plugins.PluginInstance;
import com.android.systemui.statusbar.phone.ongoingactivity.OngoingActivityData;
import com.android.systemui.statusbar.phone.ongoingactivity.OngoingActivityDataHelper;
import com.android.systemui.statusbar.phone.ongoingactivity.OngoingActivityController;
import com.android.systemui.statusbar.phone.ongoingactivity.OngoingCardAdapter;
import com.android.systemui.statusbar.phone.ongoingactivity.ongoingchip.OngoingChipAdapter;
import com.android.systemui.statusbar.phone.ongoingactivity.CardStackview.CardStackView;
import com.android.systemui.toast.SystemUIToast;
import com.android.systemui.toast.ToastFactory;
import io.github.igorcv88.oneuiliquidglass.config.ConfigClient;
import io.github.igorcv88.oneuiliquidglass.config.ProductFlags;
import io.github.igorcv88.oneuiliquidglass.glass.LiveGlassDrawable;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Wiring: exact members hooked, opt-ins and master read from the config, per-surface failure budget. */
@RunWith(RobolectricTestRunner.class) @Config(sdk = 34)
public class LiveSurfaceHooksTest {
    private Object previous;
    private Env env;
    private RecordingSite site;
    private boolean master = true;
    private int configStarts;
    private LiveSurfaceHooks hooks;
    private Activity activity;

    private static Field current() throws Exception { Field f = ConfigClient.class.getDeclaredField("current"); f.setAccessible(true); return f; }
    private void flags(String... on) throws Exception {
        Map<String, String> v = new HashMap<>();
        for (String k : ProductFlags.OPT_IN) v.put(k, "0");
        for (String k : on) v.put(k, "1");
        current().set(null, v);
    }

    @Before public void setUp() throws Exception {
        previous = current().get(null);
        flags(ProductFlags.LIVE_ACTIVITY, ProductFlags.NOW_BAR, ProductFlags.TOAST);
        env = new Env();
        site = new RecordingSite();
        hooks = new LiveSurfaceHooks(getClass().getClassLoader(), () -> master, c -> configStarts++, site, env, env);
        OngoingActivityDataHelper.reset();
        hooks.install();
        OngoingChipAdapter.nested = (thiz, args) -> site.after(thiz, "setChipBg", args);
        activity = Robolectric.buildActivity(Activity.class).setup().get();
    }
    @After public void tearDown() throws Exception { current().set(null, previous); OngoingChipAdapter.nested = null; }

    @Test public void installsOnlyTheExactSamsungMembers() {
        assertTrue(site.hooked(OngoingChipAdapter.class, "onBindViewHolder"));
        assertTrue(site.hooked(OngoingChipAdapter.class, "setChipBg"));
        assertFalse("a getter is not a writer", site.hooked(OngoingChipAdapter.class, "getChipBg"));
        assertTrue(site.hooked(OngoingCardAdapter.class, "getView"));
        assertTrue(site.hooked(CardStackView.class, "changeCardBg"));
        assertTrue(site.hooked(CardStackView.class, "updateItemBg"));
        assertFalse("the native verdict is called, not hooked", site.hooked(OngoingCardAdapter.class, "isMediaView"));
        assertTrue(site.hooked(ToastFactory.class, "createToast"));
        assertTrue(site.hooked(PluginInstance.class, "loadPlugin"));
        assertTrue(site.hooked(OngoingActivityController.class, "onEntryRemoved"));
        assertTrue(site.hooked(OngoingActivityController.class, "onEntryUpdated"));
        for (java.lang.reflect.Method m : site.hooks.keySet()) {
            if (ToastHooks.PRESENTER.equals(m.getDeclaringClass().getName()))
                assertTrue("only scoped presenter lifecycle", m.getName().equals("show") || m.getName().equals("hide"));
            else assertFalse("no generic framework hook: " + m, DataLocator.framework(m.getDeclaringClass()));
        }
        for (LiveSurface s : LiveSurface.values()) assertTrue(s.name(), hooks.available(s));
    }

    @Test public void diagnosticRemovalHookDoesNotChangeSamsungLifecycle() throws Exception {
        OngoingActivityController controller = new OngoingActivityController();
        site.call(controller, "onEntryRemoved", new Object());
        assertEquals(1, controller.removed);
        assertTrue(LiveRemovalDiagnostics.removalMethod("onEntryRemoved"));
        assertFalse(LiveRemovalDiagnostics.removalMethod("onEntryUpdated"));
    }

    @Test public void admissionProbeDoesNotChangeSamsungLifecycle() throws Exception {
        OngoingActivityController controller = new OngoingActivityController();
        site.call(controller, "onEntryUpdated", new Object());
        assertTrue(site.hooked(OngoingActivityController.class, "onEntryUpdated"));
        assertTrue(LiveAdmissionDiagnostics.interesting("com.sec.android.app.clockpackage"));
        assertTrue(LiveAdmissionDiagnostics.interesting("com.igcv.gradus"));
        assertFalse(LiveAdmissionDiagnostics.interesting("com.example.other"));
        assertEquals("com.igcv.gradus", LiveAdmissionDiagnostics.packageFromKey("0|com.igcv.gradus|998|null|10432"));
        assertNull(LiveAdmissionDiagnostics.packageFromKey("malformed"));
    }

    @Test public void chipBindThroughTheHooksGetsGlassAndConfigStarts() throws Exception {
        OngoingChipAdapter adapter = new OngoingChipAdapter();
        OngoingActivityDataHelper.add(new OngoingActivityData("com.example.timer", false));
        RecyclerView.ViewHolder h = new RecyclerView.ViewHolder(new FrameLayout(activity));
        site.call(adapter, "onBindViewHolder", h, 0);
        assertTrue(h.itemView.getBackground() instanceof LiveGlassDrawable);
        assertEquals(1, configStarts);
        // Recolour from outside the bind, through the hooked writer.
        site.call(adapter, "setChipBg", (ViewGroup) h.itemView, ColorStateList.valueOf(0xff00ff00));
        assertTrue(h.itemView.getBackground() instanceof LiveGlassDrawable);
    }

    @Test public void eachOptInIsIndependentAndMasterGatesAll() throws Exception {
        flags(ProductFlags.TOAST);
        OngoingChipAdapter adapter = new OngoingChipAdapter();
        OngoingActivityDataHelper.add(new OngoingActivityData("com.example.timer", false));
        RecyclerView.ViewHolder h = new RecyclerView.ViewHolder(new FrameLayout(activity));
        site.call(adapter, "onBindViewHolder", h, 0);
        assertTrue("live update off: native", h.itemView.getBackground() instanceof GradientDrawable);
        SystemUIToast t = (SystemUIToast) site.call(new ToastFactory(), "createToast", activity, activity, "Copied", "pkg", 0, 0);
        assertTrue(t.getView().getBackground() instanceof LiveGlassDrawable);
        flags(ProductFlags.TOAST, ProductFlags.LIVE_ACTIVITY);
        hooks.refresh();
        assertTrue("candidate applied on config change", h.itemView.getBackground() instanceof LiveGlassDrawable);
        master = false;
        hooks.refresh();
        assertTrue(h.itemView.getBackground() instanceof GradientDrawable);
        assertTrue(t.getView().getBackground() instanceof GradientDrawable);
    }

    @Test public void failuresDisableOnlyTheirSurfaceAndRestoreIt() throws Exception {
        OngoingChipAdapter adapter = new OngoingChipAdapter();
        OngoingActivityDataHelper.add(new OngoingActivityData("com.example.timer", false));
        RecyclerView.ViewHolder h = new RecyclerView.ViewHolder(new FrameLayout(activity));
        site.call(adapter, "onBindViewHolder", h, 0);
        assertTrue(h.itemView.getBackground() instanceof LiveGlassDrawable);
        for (int i = 0; i < LiveSurfaceHooks.FAILURE_BUDGET; i++) hooks.guard(LiveSurface.CHIP, () -> { throw new IllegalStateException("abi"); });
        assertTrue(hooks.broken(LiveSurface.CHIP));
        assertFalse(hooks.allows(LiveSurface.CHIP));
        assertTrue("restored on persistent failure", h.itemView.getBackground() instanceof GradientDrawable);
        assertFalse(hooks.broken(LiveSurface.TOAST));
        SystemUIToast t = (SystemUIToast) site.call(new ToastFactory(), "createToast", activity, activity, "Copied", "pkg", 0, 0);
        assertTrue(t.getView().getBackground() instanceof LiveGlassDrawable);
    }

    @Test public void missingMembersMakeASurfaceUnavailableWithoutHooks() {
        RecordingSite empty = new RecordingSite();
        LiveSurfaceHooks none = new LiveSurfaceHooks(new ClassLoader(null) { }, () -> true, c -> { }, empty, env, env);
        none.install();
        assertTrue(empty.hooks.isEmpty());
        for (LiveSurface s : LiveSurface.values()) assertFalse(none.available(s));
    }

    @Test public void cardThroughTheHooksIncludesCompatibleMedia() throws Exception {
        OngoingCardAdapter adapter = new OngoingCardAdapter();
        adapter.mList.add(new OngoingActivityData("com.samsung.android.health", false));
        adapter.mList.add(new OngoingActivityData("com.spotify.music", true));
        CardStackView stack = new CardStackView(activity);
        View ok = (View) site.call(adapter, "getView", 0, null, stack);
        View media = (View) site.call(adapter, "getView", 1, null, stack);
        // DZIF: the root has no background; its fill carries the card colour.
        assertNull(ok.getBackground());
        assertTrue(((com.android.systemui.statusbar.phone.ongoingactivity.CardStackview.CardItemView) ok).fill().getBackground() instanceof LiveGlassDrawable);
        assertTrue(((com.android.systemui.statusbar.phone.ongoingactivity.CardStackview.CardItemView) media).fill().getBackground() instanceof LiveGlassDrawable);
    }

    /** DZIF reports no cross-window blur; the glass follows the system only on Battery Saver or the developer switch. */
    @Test public void blurPolicyIgnoresCrossWindowFlagAndHonoursSaver() {
        assertTrue(LiveSurfaceHooks.allowed(false, null));
        assertTrue(LiveSurfaceHooks.allowed(false, "0"));
        assertFalse(LiveSurfaceHooks.allowed(true, null));
        assertFalse(LiveSurfaceHooks.allowed(false, "1"));
    }

    @Test public void defaultsKeepEveryNewSurfaceOff() {
        for (String k : new String[]{ProductFlags.LIVE_ACTIVITY, ProductFlags.NOW_BAR, ProductFlags.TOAST}) {
            assertTrue(ProductFlags.known(k));
            assertFalse(ProductFlags.defaultEnabled(k));
        }
        assertTrue("existing notification flags keep their default", ProductFlags.defaultEnabled("surface.shade.enabled"));
    }
}
