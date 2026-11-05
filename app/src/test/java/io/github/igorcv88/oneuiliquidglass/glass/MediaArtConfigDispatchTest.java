package io.github.igorcv88.oneuiliquidglass.glass;

import static org.junit.Assert.*;
import io.github.igorcv88.oneuiliquidglass.config.ConfigClient;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MediaArtConfigDispatchTest {
    private static Field field(Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    @Test public void mediaOnlyChangesPublishAndDoNotChangeQsArtwork() throws Exception {
        Field current = field(ConfigClient.class, "current");
        String[] names = {"cached", "lastLogged", "generation", "readAt", "configRevision"};
        Object previous = current.get(null);
        Object[] saved = new Object[names.length];
        for (int i = 0; i < names.length; i++) saved[i] = field(Tuning.class, names[i]).get(null);
        Method refresh = Tuning.class.getDeclaredMethod("refresh");
        refresh.setAccessible(true);
        try {
            current.set(null, Collections.singletonMap("mediaart", "0.5"));
            refresh.invoke(null);
            Tuning first = (Tuning) field(Tuning.class, "cached").get(null);
            assertEquals(.5f, first.mediaArt, 0f);
            float qs = first.qsMediaArt;

            current.set(null, Collections.singletonMap("mediaart", "0.8"));
            refresh.invoke(null);
            Tuning changed = (Tuning) field(Tuning.class, "cached").get(null);
            assertEquals(.8f, changed.mediaArt, 0f);
            assertEquals(qs, changed.qsMediaArt, 0f);

            current.set(null, Collections.singletonMap("mediaart", "0"));
            refresh.invoke(null);
            Tuning nativeArt = (Tuning) field(Tuning.class, "cached").get(null);
            assertEquals(Tuning.MEDIA_ART_NATIVE, nativeArt.mediaArt, 0f);
            assertEquals(qs, nativeArt.qsMediaArt, 0f);
        } finally {
            current.set(null, previous);
            for (int i = 0; i < names.length; i++) field(Tuning.class, names[i]).set(null, saved[i]);
        }
    }
}
