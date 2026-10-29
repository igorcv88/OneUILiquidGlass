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
    private final Set<String> models = Collections.synchronizedSet(new HashSet<>());
    private final ThreadLocal<Object> currentModel = new ThreadLocal<>();

    String currentId() {
        Object model = currentModel.get();
        return model == null ? "unbound" : Integer.toHexString(System.identityHashCode(model));
    }

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
                            if ("QuickTile".equals(prefix)) {
                                p.setObjectExtra("qsTilePrevious", currentModel.get());
                                Object model = p.args.length > 2 && p.args[2] != null
                                        && p.args[2].getClass().getName().endsWith(".QuickTile") ? p.args[2] : null;
                                if (model == null) currentModel.remove(); else currentModel.set(model);
                                if (Probe.trace && model != null) {
                                    String schema = QsTileTrace.modelFields(model);
                                    String key = model.getClass().getName() + '|' + schema;
                                    synchronized (models) {
                                        if (models.size() < 48 && models.add(key))
                                            Probe.log("QS_TILE_MODEL", "model=" + currentId() + " fields=" + schema);
                                    }
                                }
                            }
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
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            if (!"QuickTile".equals(prefix)) return;
                            Object previous = p.getObjectExtra("qsTilePrevious");
                            if (previous == null) currentModel.remove(); else currentModel.set(previous);
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
