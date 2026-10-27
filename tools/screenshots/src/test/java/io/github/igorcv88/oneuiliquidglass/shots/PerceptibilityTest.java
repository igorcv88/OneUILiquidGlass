package io.github.igorcv88.oneuiliquidglass.shots;

import static org.junit.Assert.assertTrue;

import android.widget.FrameLayout;
import app.cash.paparazzi.*;
import com.android.ide.common.rendering.api.SessionParams;
import io.github.igorcv88.oneuiliquidglass.config.*;
import io.github.igorcv88.oneuiliquidglass.ui.preview.NotificationPreview;
import io.github.igorcv88.oneuiliquidglass.ui.preview.PreviewParams;
import org.junit.Rule;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.*;
import java.util.function.Function;

/**
 * Renders the sample with each represented control at its minimum and maximum and measures the
 * pixel difference. A control whose two extremes look the same fails: the preview would be
 * promising a change it does not show.
 */
public class PerceptibilityTest {
    // java.awt is not on the Android test compile path: the image is handled reflectively.
    private Object last;
    private final SnapshotHandler capture = (SnapshotHandler) java.lang.reflect.Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[] {SnapshotHandler.class}, (proxy, method, args) -> {
                if (!method.getName().equals("newFrameHandler")) return null;
                return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[] {SnapshotHandler.FrameHandler.class}, (p2, m2, a2) -> {
                            if (m2.getName().equals("handle")) last = a2[0];
                            return null;
                        });
            });

    @Rule public Paparazzi paparazzi = new Paparazzi(EnvironmentKt.detectEnvironment(), DeviceConfig.PIXEL_5,
            "android:Theme.Material.NoActionBar.Fullscreen", SessionParams.RenderingMode.NORMAL, false, 0.1, capture);

    private static Function<String, String> with(Function<String, String> f, String k, String v) {
        return key -> key.equals(k) ? v : f.apply(key);
    }

    private boolean zoom;

    private Object render(Function<String, String> values, Controls.Surface s, boolean light, NotificationPreview.Backdrop b) {
        NotificationPreview p = new NotificationPreview(paparazzi.getContext());
        if (zoom) p.zoomOnly(channel, "Atual");
        p.surface(s);
        p.lightTheme(light);
        p.backdrop(b);
        p.values(values, null);
        FrameLayout root = new FrameLayout(paparazzi.getContext());
        root.addView(p, new FrameLayout.LayoutParams(-1, zoom ? 600 : -2));
        paparazzi.snapshot(root);
        return last;
    }

    /** Fraction of pixels whose colour moved by more than 6/255 in any channel, and the mean shift. */
    private static int call(Object image, String name, Object... args) throws Exception {
        Class<?>[] types = new Class<?>[args.length];
        Arrays.fill(types, int.class);
        return (int) image.getClass().getMethod(name, types).invoke(image, args);
    }

    static double[] diff(Object a, Object b) throws Exception {
        int w = Math.min(call(a, "getWidth"), call(b, "getWidth")), h = Math.min(call(a, "getHeight"), call(b, "getHeight"));
        long changed = 0, n = 0;
        double sum = 0;
        for (int y = 0; y < h; y += 2)
            for (int x = 0; x < w; x += 2) {
                int p = call(a, "getRGB", x, y), q = call(b, "getRGB", x, y);
                int d = Math.max(Math.abs(((p >> 16) & 255) - ((q >> 16) & 255)),
                        Math.max(Math.abs(((p >> 8) & 255) - ((q >> 8) & 255)), Math.abs((p & 255) - (q & 255))));
                if (d > 6) changed++;
                sum += d;
                n++;
            }
        return new double[] {changed / (double) n, sum / n};
    }

    private Controls.Channel channel = Controls.Channel.NONE;

    private static Map<String, Object> fields(PreviewParams p) throws IllegalAccessException {
        Map<String, Object> m = new TreeMap<>();
        for (Field f : PreviewParams.class.getFields()) m.put(f.getName(), f.get(p));
        return m;
    }

    @Test public void everyRepresentedControlChangesPixels() throws Exception {
        StringBuilder report = new StringBuilder("key\tsurface\tcontext\ttheme\tbackdrop\tfull_changed\tfull_mean\tfocused_changed\tfocused_mean\n");
        List<String> weak = new ArrayList<>();
        for (Controls.Control c : Controls.all()) {
            if (c.channel == Controls.Channel.NONE || (c.preview != Controls.Preview.FAITHFUL && c.preview != Controls.Preview.DIDACTIC)) continue;
            SettingSpec s = c.spec();
            String lo, hi;
            if (s.type == SettingSpec.Type.BOOLEAN) { lo = "0"; hi = "1"; }
            else if (s.type == SettingSpec.Type.RGB) { lo = "ffffff"; hi = "ff8040"; }
            else if (s.type == SettingSpec.Type.ENUM) { lo = c.choices.keySet().iterator().next(); hi = "0"; }
            else { Scale sc = Scale.of(c); lo = sc.value(sc.auto ? 1 : 0); hi = sc.value(sc.steps); }
            channel = c.channel;
            double[] best = null;
            String where = "";
            outer:
            for (Map<String, String> ctx : contexts(c.key))
                for (Controls.Surface surface : c.surfaces)
                    for (boolean light : new boolean[] {false, true})
                        for (NotificationPreview.Backdrop b : NotificationPreview.Backdrop.values()) {
                            ControlState st = new ControlState(Collections.emptyMap(), ctx, Collections.emptyMap(), true);
                            Function<String, String> f = st::shown;
                            if (fields(PreviewParams.of(with(f, c.key, lo), surface, light, .5f)).equals(fields(PreviewParams.of(with(f, c.key, hi), surface, light, .5f)))
                                    && fields(PreviewParams.of(with(f, c.key, lo), surface, light, .9f)).equals(fields(PreviewParams.of(with(f, c.key, hi), surface, light, .9f)))
                                    && fields(PreviewParams.of(with(f, c.key, lo), surface, light, .15f)).equals(fields(PreviewParams.of(with(f, c.key, hi), surface, light, .15f))))
                                continue;
                            zoom = false;
                            double[] full = diff(render(with(f, c.key, lo), surface, light, b), render(with(f, c.key, hi), surface, light, b));
                            zoom = true;
                            double[] focused = diff(render(with(f, c.key, lo), surface, light, b), render(with(f, c.key, hi), surface, light, b));
                            double[] d = {full[0], full[1], focused[0], focused[1]};
                            if (best == null || d[2] > best[2]) { best = d; where = surface + "\t" + ctx + "\t" + (light ? "claro" : "escuro") + "\t" + b; }
                            if (d[2] > .05) break outer;
                        }
            if (best == null) best = new double[] {0, 0, 0, 0};
            report.append(c.key).append('\t').append(where).append('\t')
                    .append(String.format(Locale.ROOT, "%.4f\t%.2f\t%.4f\t%.2f", best[0], best[1], best[2], best[3])).append('\n');
            // The focused sample (editor triptych / magnifier) must show at least 2 % of pixels moving.
            if (best[2] < .02) weak.add(c.key + " " + String.format(Locale.ROOT, "%.4f", best[2]));
        }
        java.nio.file.Files.write(java.nio.file.Paths.get("build/perceptibility.tsv"), report.toString().getBytes("UTF-8"));
        assertTrue("Imperceptible in the sample: " + weak, weak.isEmpty());
    }

    private static List<Map<String, String>> contexts(String key) {
        List<Map<String, String>> out = new ArrayList<>();
        for (Map<String, String> mode : Arrays.asList(Collections.<String, String>emptyMap(), Collections.singletonMap("sfrefract", "1"), Collections.singletonMap("backdrop", "hybrid"))) {
            Map<String, String> m = new HashMap<>(mode);
            if (key.equals("shadescaleveil")) m.put("shadetone", "2");
            if (key.startsWith("sf.shade")) m.put("shadetone", "1");
            if (key.equals("refract") || key.equals("ior")) m.put("profile", "snell");
            out.add(m);
            Map<String, String> noSwap = new HashMap<>(m);
            noSwap.put("textswap", "0");
            out.add(noSwap);
        }
        return out;
    }
}
