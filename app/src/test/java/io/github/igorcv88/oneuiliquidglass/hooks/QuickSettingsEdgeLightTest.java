package io.github.igorcv88.oneuiliquidglass.hooks;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.view.View;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;
import io.github.igorcv88.oneuiliquidglass.glass.BackgroundBlurBridge;
import io.github.igorcv88.oneuiliquidglass.glass.Tuning;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class QuickSettingsEdgeLightTest {
    private Activity activity;
    private View host;
    private Tuning tuning;
    private boolean oldRefract;
    private int oldOptics;

    @Before public void prepare() throws Exception {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        host = new View(activity);
        activity.setContentView(host);
        tuning = Tuning.get();
        oldRefract = tuning.sfRefract; oldOptics = tuning.sfOptics;
        set(tuning, "sfRefract", true); set(tuning, "sfOptics", 11);
        ShadowLog.clear();
    }

    @After public void restore() throws Exception {
        set(tuning, "sfRefract", oldRefract); set(tuning, "sfOptics", oldOptics);
        activity.finish();
    }

    /** Equal Compose values must remain independent; an equal unregistered value cannot bind. */
    @Test public void elementsAreMatchedByIdentityAndRecompositionReleasesTheOldRegion() throws Exception {
        QuickSettingsEdgeLight edge = edge(true, true, true);
        Object a = new EqualElement(), b = new EqualElement();
        Object n1 = new Object(), n2 = new Object();
        edge.element(a, new Object(), 1f);
        edge.element(b, new Object(), 1f);
        call(edge, "bind", new Class<?>[]{Object.class, Object.class}, new EqualElement(), n1);
        assertEquals(0, nodes(edge).size());
        call(edge, "bind", new Class<?>[]{Object.class, Object.class}, a, n1);
        call(edge, "bind", new Class<?>[]{Object.class, Object.class}, b, n2);
        assertEquals(2, nodes(edge).size());
        assertEquals(0, ((List<?>) get(edge, "elements")).size());
        BlurDrawable d = seed(edge, n1);
        Object replacement = new EqualElement();
        edge.element(replacement, new Object(), 1f);
        call(edge, "bind", new Class<?>[]{Object.class, Object.class}, replacement, n1);
        assertReleased(d);
        assertEquals(2, nodes(edge).size());
        call(edge, "unbind", new Class<?>[]{Object.class}, n1);
        assertEquals(1, nodes(edge).size());
        call(edge, "unbind", new Class<?>[]{Object.class}, n2);
        assertTrue(nodes(edge).isEmpty());
    }

    @Test public void everyInactiveGateReleasesAnExistingDrawable() throws Exception {
        for (String gate : new String[]{"option-off", "master-off", "sfrefract-off", "optics-version", "missing-host", "not-ready"}) {
            set(tuning, "sfRefract", true); set(tuning, "sfOptics", 11);
            boolean option = !gate.equals("option-off"), master = !gate.equals("master-off");
            QuickSettingsEdgeLight edge = edge(option && master, option, master);
            Object node = bind(edge);
            BlurDrawable d = seed(edge, node);
            if (gate.equals("sfrefract-off")) set(tuning, "sfRefract", false);
            if (gate.equals("optics-version")) set(tuning, "sfOptics", 9);
            if (gate.equals("missing-host")) set(edge, "host", new WeakReference<View>(null));
            if (gate.equals("not-ready")) set(edge, "ready", false);
            edge.status("gate-test");
            assertReleased(d);
            assertTrue(gate, ShadowLog.getLogsForTag("OULG").stream()
                    .anyMatch(log -> log.msg.contains("blocked=" + gate)));
        }
    }

    @Test public void unreadableExpansionKeepsTheFadeGuardAndNeverClaimsVisibility() throws Exception {
        QuickSettingsEdgeLight edge = edge(true, true, true);
        edge.panel(new Controller(host, new Object()));
        Object node = bind(edge);
        BlurDrawable d = seed(edge, node);
        edge.status("unreadable-test");
        assertEquals(0f, (Float) get(edge, "edgeFade"), 0f);
        assertTrue(ShadowLog.getLogsForTag("OULG").stream()
                .anyMatch(log -> log.msg.contains("expansionReadable=false")));
        assertFalse(ShadowLog.getLogsForTag("OULG").stream()
                .anyMatch(log -> log.msg.contains("phase=region-visible")));
        // A retained display list intentionally uses 1/255, not permanent full opacity.
        assertTrue(d.getAlpha() <= 1);
    }

    @Test public void hostDetachAndHostReplacementReleaseRegionsAndRemoveObservers() throws Exception {
        QuickSettingsEdgeLight edge = edge(true, true, true);
        edge.panel(new Controller(host, new Adapter()));
        Object node = bind(edge);
        BlurDrawable first = seed(edge, node);
        activity.setContentView(new View(activity));
        assertReleased(first);
        assertNull(get(edge, "observed"));
        assertNull(get(edge, "settledSnapshot"));
        BlurDrawable second = seed(edge, node);
        edge.panel(new Controller(new View(activity), new Adapter()));
        assertReleased(second);
        assertNotNull(get(edge, "observed"));
        call(edge, "unbind", new Class<?>[]{Object.class}, node);
        assertTrue(nodes(edge).isEmpty());
    }

    @Test public void alphaSnapshotIsTakenAfterRefreshEvenWhenNoNewDrawOccurs() throws Exception {
        QuickSettingsEdgeLight edge = edge(true, true, true);
        edge.panel(new Controller(host, new Adapter()));
        Object node = bind(edge);
        BlurDrawable d = seed(edge, node);
        set(nodes(edge).get(node), "drawn", true);
        host.setAlpha(.4f);
        edge.status("alpha-test");
        assertEquals(102, d.getAlpha());
        assertTrue(ShadowLog.getLogsForTag("OULG").stream()
                .anyMatch(log -> log.msg.contains("alphaSentMin=102") && log.msg.contains("edgeFade=0.4")));
        host.setScaleX(.9f);
        edge.status("scale-test");
        assertEquals(1, d.getAlpha());
        assertEquals(0f, (Float) get(edge, "edgeFade"), 0f);
    }

    private QuickSettingsEdgeLight edge(boolean enabled, boolean option, boolean master) throws Exception {
        QuickSettingsEdgeLight e = new QuickSettingsEdgeLight(getClass().getClassLoader(),
                () -> enabled, () -> option, () -> master);
        set(e, "ready", true);
        set(e, "host", new WeakReference<>(host));
        return e;
    }

    private Object bind(QuickSettingsEdgeLight edge) throws Exception {
        Object element = new Object(), node = new Object();
        edge.element(element, new Object(), 1f);
        call(edge, "bind", new Class<?>[]{Object.class, Object.class}, element, node);
        return node;
    }

    private BlurDrawable seed(QuickSettingsEdgeLight edge, Object node) throws Exception {
        BlurDrawable d = new BlurDrawable();
        Constructor<BackgroundBlurBridge> ctor = BackgroundBlurBridge.class
                .getDeclaredConstructor(android.graphics.drawable.Drawable.class, boolean.class);
        ctor.setAccessible(true);
        BackgroundBlurBridge b = ctor.newInstance(d, false);
        b.update(8, 0, new float[8]);
        // Same alpha policy as QuickSettingsEdgeLight.draw (including its 1/255 display-list guard).
        d.setAlpha(Math.max(1, (Integer) get(edge, "alpha")));
        set(nodes(edge).get(node), "bridge", b);
        return d;
    }

    private void assertReleased(BlurDrawable d) {
        assertEquals(0, d.radius);
        assertEquals(0, d.getAlpha());
        assertFalse(d.isVisible());
    }

    @SuppressWarnings("unchecked")
    private Map<Object, Object> nodes(Object e) throws Exception { return (Map<Object, Object>) get(e, "nodes"); }
    private static Object get(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(o);
    }
    private static void set(Object o, String name, Object value) throws Exception {
        Field f = o.getClass().getDeclaredField(name); f.setAccessible(true); f.set(o, value);
    }
    private static void call(Object o, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = o.getClass().getDeclaredMethod(name, types); m.setAccessible(true); m.invoke(o, args);
    }

    private static final class EqualElement {
        @Override public boolean equals(Object o) { return o instanceof EqualElement; }
        @Override public int hashCode() { return 1; }
    }
    public static final class Controller {
        public final View view;
        public final Object composeAdapter;
        Controller(View view, Object adapter) { this.view = view; composeAdapter = adapter; }
    }
    public static final class Adapter { public final Flow expansionFraction = new Flow(); }
    public static final class Flow { public Float getValue() { return 1f; } }
    public static final class BlurDrawable extends Drawable {
        int radius, alpha = 255;
        @Override public void draw(Canvas canvas) { }
        @Override public void setAlpha(int alpha) { this.alpha = alpha; }
        @Override public int getAlpha() { return alpha; }
        @Override public void setColorFilter(ColorFilter filter) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        public void setColor(int color) { }
        public void setBlurRadius(int px) { radius = px; }
        public void setCornerRadius(float tl, float tr, float bl, float br) { }
    }
}
