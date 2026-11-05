package io.github.igorcv88.oneuiliquidglass.live;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.res.Configuration;
import android.os.LocaleList;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class SfLiveControllerLocaleTest {
    @Test public void flowFailureIsLocalizedAndPreservesHelperOutput() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Configuration config = new Configuration(activity.getResources().getConfiguration());
            config.setLocales(new LocaleList(Locale.ENGLISH));
            activity.getResources().updateConfiguration(config, activity.getResources().getDisplayMetrics());
            SfLiveController controller = new SfLiveController(activity, executor);
            Method translate = SfLiveController.class.getDeclaredMethod("localizedFailure", Exception.class);
            translate.setAccessible(true);
            assertEquals("Self-test did not pass.\nraw=error", translate.invoke(controller,
                    new SfLiveFlow.Failure("Selftest não aprovado.\nraw=error", true)));
            assertEquals("The value of sf.core was not confirmed; nothing was restarted.",
                    translate.invoke(controller, new SfLiveFlow.Failure(
                            "O valor de sf.core não foi confirmado; nada foi reiniciado.", false)));
            SfLiveController.Model failure = new SfLiveController.Model(false, true,
                    Collections.emptyMap(), "failure", "", true);
            assertTrue(failure.failed);
            controller.close();
        } finally {
            executor.shutdownNow();
        }
    }
    @Test public void existingRootAndSurfaceFlingerStatesFollowLanguageWithoutCommands() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            RootAccess root = new RootAccess(activity, executor);
            SfLiveController controller = new SfLiveController(activity, executor);
            java.lang.reflect.Field rootId = RootAccess.class.getDeclaredField("messageId");
            rootId.setAccessible(true);
            java.lang.reflect.Field modelField = SfLiveController.class.getDeclaredField("model");
            modelField.setAccessible(true);
            for (int id : new int[] {io.github.igorcv88.oneuiliquidglass.R.string.root_unchecked,
                    io.github.igorcv88.oneuiliquidglass.R.string.root_confirmed,
                    io.github.igorcv88.oneuiliquidglass.R.string.root_denied_unavailable}) {
                rootId.setInt(root, id);
                for (Locale locale : new Locale[] {Locale.ENGLISH, new Locale("pt", "BR"), Locale.ENGLISH}) {
                    Configuration config = new Configuration(activity.getResources().getConfiguration());
                    config.setLocales(new LocaleList(locale));
                    activity.getResources().updateConfiguration(config, activity.getResources().getDisplayMetrics());
                    assertEquals(activity.getString(id), root.message());
                }
            }
            for (int id : new int[] {io.github.igorcv88.oneuiliquidglass.R.string.sf_bundle_ready,
                    io.github.igorcv88.oneuiliquidglass.R.string.sf_prepared,
                    io.github.igorcv88.oneuiliquidglass.R.string.sf_failure_selftest}) {
                modelField.set(controller, new SfLiveController.Model(false, true,
                        Collections.emptyMap(), () -> activity.getString(id), "technical log", true));
                for (Locale locale : new Locale[] {Locale.ENGLISH, new Locale("pt", "BR"), Locale.ENGLISH}) {
                    Configuration config = new Configuration(activity.getResources().getConfiguration());
                    config.setLocales(new LocaleList(locale));
                    activity.getResources().updateConfiguration(config, activity.getResources().getDisplayMetrics());
                    assertEquals(activity.getString(id), controller.model().message);
                    assertEquals("technical log", controller.model().log);
                }
            }
            root.close(); controller.close();
        } finally { executor.shutdownNow(); }
    }
}
