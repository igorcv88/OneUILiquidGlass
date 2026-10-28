package io.github.igorcv88.oneuiliquidglass.hooks;

import android.os.Looper;
import android.os.SystemClock;
import com.android.systemui.shared.system.TaskStackChangeListener;
import com.android.systemui.shared.system.TaskStackChangeListeners;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk = 34)
public class BackdropEventsTest {
    /** A task change reaches the surfaces on the main thread; other callbacks are inert and answered safely. */
    @Test public void taskChangesFireAndOtherCallbacksAreIgnored() {
        int[] fired = {0};
        BackdropEvents.add(() -> fired[0]++);
        BackdropEvents.install(getClass().getClassLoader());
        TaskStackChangeListeners registry = TaskStackChangeListeners.getInstance();
        assertEquals(1, registry.registered.size());
        TaskStackChangeListener l = registry.registered.get(0);
        l.onLockTaskModeChanged(1);
        assertFalse(l.onTaskSnapshotChanged(3, null));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0, fired[0]);
        l.onTaskMovedToFront(7);
        assertTrue(BackdropEvents.burst(SystemClock.uptimeMillis()));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, fired[0]);
        l.onTaskStackChanged();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(2, fired[0]);
        assertFalse(BackdropEvents.burst(SystemClock.uptimeMillis() + BackdropEvents.BURST_MS + 1));
        // Registered once, even if installed again.
        BackdropEvents.install(getClass().getClassLoader());
        assertEquals(1, registry.registered.size());
        assertEquals(l, l);
    }

    /** No getInstance (DZIF: NoSuchMethodException): a static singleton field and a renamed register still work. */
    static final class FieldRegistry {
        static final FieldRegistry sInstance = new FieldRegistry();
        Object added;
        void registerTaskStackListenerInternal(TaskStackChangeListener l) { added = l; }
        void unregisterTaskStackListener(String other) { }
    }
    static final class NoRegistry { void register(Object o) { } }
    @Test public void singletonAndRegisterAreFoundWithoutGetInstance() throws Exception {
        assertSame(FieldRegistry.sInstance, BackdropEvents.instance(FieldRegistry.class));
        assertEquals("registerTaskStackListenerInternal",
                BackdropEvents.register(FieldRegistry.class, TaskStackChangeListener.class).getName());
        try { BackdropEvents.instance(NoRegistry.class); fail(); }
        catch (NoSuchMethodException e) { assertTrue(e.getMessage(), e.getMessage().contains("candidates=")); }
        try { BackdropEvents.register(NoRegistry.class, TaskStackChangeListener.class); fail(); }
        catch (NoSuchMethodException e) { assertTrue(e.getMessage(), e.getMessage().contains("candidates=")); }
    }
}
