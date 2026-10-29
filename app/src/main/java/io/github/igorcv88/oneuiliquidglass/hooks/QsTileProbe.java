package io.github.igorcv88.oneuiliquidglass.hooks;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;

/** Read-only diagnostic of DZIF's real QuickTile and IconTextTile composition arguments. */
final class QsTileProbe {
    private static final int MAX_SAMPLES = 96;
    private final Set<String> samples = Collections.synchronizedSet(new HashSet<>());

    void install(ClassLoader loader) {
        hook(loader, "com.android.systemui.samsung.quicksetting.ui.tiles.QuickTileKt", "QuickTile");
        hook(loader, "com.android.systemui.samsung.quicksetting.ui.tiles.IconTextTileKt", "IconTextTile");
    }

    private void hook(ClassLoader loader, String name, String prefix) {
        int installed = 0;
        try {
            Class<?> type = Class.forName(name, false, loader);
            for (Method method : type.getDeclaredMethods()) {
                if (!Modifier.isStatic(method.getModifiers()) || !method.getName().startsWith(prefix)
                        || !QsTileTrace.hasComposer(method.getParameterTypes()) || installed >= 8) continue;
                try {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam p) {
                            if (!Probe.trace) return;
                            try {
                                String data = method.getName() + " " + QsTileTrace.arguments(p.args);
                                synchronized (samples) {
                                    if (samples.size() >= MAX_SAMPLES || !samples.add(data)) return;
                                }
                                Probe.log("QS_TILE_BIND", "owner=" + prefix + " " + data);
                            } catch (RuntimeException | LinkageError e) {
                                Probe.error("QS_TILE_PROBE_FAILED", prefix, e);
                            }
                        }
                    });
                    installed++;
                    Probe.log("QS_TILE_ABI", "method=" + method.toGenericString());
                } catch (RuntimeException | LinkageError e) {
                    Probe.error("QS_TILE_PROBE_FAILED", method.getName(), e);
                }
            }
        } catch (ClassNotFoundException | RuntimeException | LinkageError e) {
            Probe.error("QS_TILE_PROBE_MISSING", name, e);
        }
        Probe.log("QS_TILE_PROBE_READY", "owner=" + prefix + " methods=" + installed + " tracing=opt-in");
    }
}
