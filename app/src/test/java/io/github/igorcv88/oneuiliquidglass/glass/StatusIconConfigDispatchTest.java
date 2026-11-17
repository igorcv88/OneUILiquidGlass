package io.github.igorcv88.oneuiliquidglass.glass;

import static org.junit.Assert.*;
import io.github.igorcv88.oneuiliquidglass.config.ConfigClient;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class StatusIconConfigDispatchTest {
    private static Field field(Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    @Test public void isolatedIconSettingsPublishAndRevertWithoutChangingMaterial() throws Exception {
        Field current = field(ConfigClient.class, "current");
        String[] names = {"cached", "lastLogged", "generation", "readAt", "configRevision"};
        Object previous = current.get(null);
        Object[] saved = new Object[names.length];
        for (int i = 0; i < names.length; i++) saved[i] = field(Tuning.class, names[i]).get(null);
        Method refresh = Tuning.class.getDeclaredMethod("refresh");
        refresh.setAccessible(true);
        Field cached = field(Tuning.class, "cached");
        try {
            Map<String,String> config = new HashMap<>();
            config.put("statusmaxicons", "0");
            current.set(null, Collections.unmodifiableMap(new HashMap<>(config)));
            refresh.invoke(null);
            Tuning first = (Tuning) cached.get(null);
            assertEquals(0, first.statusMaxIcons);
            int initialGeneration = Tuning.generation;

            config.put("statusmaxicons", "4");
            current.set(null, Collections.unmodifiableMap(new HashMap<>(config)));
            refresh.invoke(null);
            Tuning four = (Tuning) cached.get(null);
            assertEquals(4, four.statusMaxIcons);
            assertEquals(initialGeneration + 1, Tuning.generation);
            assertEquals(first.blur, four.blur, 0f);
            refresh.invoke(null);
            assertSame("identical snapshot must not republish", four, cached.get(null));
            assertEquals(initialGeneration + 1, Tuning.generation);

            config.put("statusinkmode", "1");
            current.set(null, Collections.unmodifiableMap(new HashMap<>(config)));
            refresh.invoke(null);
            assertEquals(1, ((Tuning) cached.get(null)).statusIconInkMode);
            config.put("statusfixedink", "123456");
            current.set(null, Collections.unmodifiableMap(new HashMap<>(config)));
            refresh.invoke(null);
            assertEquals(0xff123456, ((Tuning) cached.get(null)).statusIconFixedInk);
            config.put("statusmaxicons", "0");
            current.set(null, Collections.unmodifiableMap(new HashMap<>(config)));
            refresh.invoke(null);
            assertEquals(0, ((Tuning) cached.get(null)).statusMaxIcons);
        } finally {
            current.set(null, previous);
            for (int i = 0; i < names.length; i++) field(Tuning.class, names[i]).set(null, saved[i]);
        }
    }
}
