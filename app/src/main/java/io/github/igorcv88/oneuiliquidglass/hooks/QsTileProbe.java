package io.github.igorcv88.oneuiliquidglass.hooks;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.WeakHashMap;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;
import io.github.igorcv88.oneuiliquidglass.glass.QuickSettingsGlass;

/** Read-only diagnostic of DZIF's real QuickTile and IconTextTile composition arguments. */
final class QsTileProbe {
    private static final int MAX_SAMPLES = 96;
    private final Set<String> samples = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> models = Collections.synchronizedSet(new HashSet<>());
    private final ThreadLocal<Object> currentModel = new ThreadLocal<>();
    /** IconTextTile receives deferred Compose lambdas. Their invocation can outlive QuickTile(). */
    private final Map<Object,Object> tileLambdas = Collections.synchronizedMap(new WeakHashMap<>());
    private final Set<Object> ambiguousLambdas = Collections.newSetFromMap(new WeakHashMap<>());
    private final ThreadLocal<Boolean> inTileLambda = new ThreadLocal<>();
    private final Set<Object> routedLambdas = Collections.newSetFromMap(new WeakHashMap<>());
    private final Map<Object,QuickSettingsGlass.Kind> discStates =
            java.util.Collections.synchronizedMap(new WeakHashMap<>());

    Object currentModel() { return currentModel.get(); }
    boolean inTileLambda() { return Boolean.TRUE.equals(inTileLambda.get()); }
    static boolean tileLambdaType(String className) {
        return className != null && className.startsWith("androidx.compose.runtime.internal.ComposableLambda");
    }
    void disc(Object model,QuickSettingsGlass.Kind state) {
        if (model != null && (state == QuickSettingsGlass.Kind.ACTIVE
                || state == QuickSettingsGlass.Kind.IDLE || state == QuickSettingsGlass.Kind.NONE))
            discStates.put(model,state);
    }
    QuickSettingsGlass.Kind disc(Object model) { return model == null ? null : discStates.get(model); }

    String currentId() {
        Object model = currentModel.get();
        return model == null ? "unbound" : Integer.toHexString(System.identityHashCode(model));
    }

    void install(ClassLoader loader) {
        hook(loader, "com.android.systemui.samsung.quicksetting.ui.tiles.QuickTileKt", "QuickTile");
        hook(loader, "com.android.systemui.samsung.quicksetting.ui.tiles.IconTextTileKt", "IconTextTile");
        hookDeferredTileLambdas(loader);
    }

    /** Restore a verified model only during the exact lambdas passed by IconTextTile. */
    private void hookDeferredTileLambdas(ClassLoader loader) {
        try {
            Class<?> type=Class.forName("androidx.compose.runtime.internal.ComposableLambdaImpl",false,loader);
            int hooks=0;
            for(Method m:type.getDeclaredMethods()) {
                if(!m.getName().equals("invoke") || Modifier.isStatic(m.getModifiers())
                        || m.getParameterCount()!=2) continue;
                XposedBridge.hookMethod(m,new XC_MethodHook(){
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        Object model=tileLambdas.get(p.thisObject);
                        if(model==null)return;
                        p.setObjectExtra("qsDeferredTileBound",true);
                        p.setObjectExtra("qsDeferredTileModelPrevious",currentModel.get());
                        p.setObjectExtra("qsDeferredTileLambdaPrevious",inTileLambda.get());
                        currentModel.set(model);
                        inTileLambda.set(true);
                        if(Probe.trace && routedLambdas.add(p.thisObject))
                            Probe.log("QS_TILE_DEFERRED_ROUTE", "model="+currentId()+" lambda="
                                    +Integer.toHexString(System.identityHashCode(p.thisObject))
                                    +" method="+m.getName()+" provenance=IconTextTile");
                    }
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        if(!Boolean.TRUE.equals(p.getObjectExtra("qsDeferredTileBound")))return;
                        Object old=p.getObjectExtra("qsDeferredTileModelPrevious");
                        if(old==null)currentModel.remove();else currentModel.set(old);
                        Object oldLambda=p.getObjectExtra("qsDeferredTileLambdaPrevious");
                        if(oldLambda==null)inTileLambda.remove();else inTileLambda.set((Boolean)oldLambda);
                    }
                });
                try { XposedBridge.class.getMethod("deoptimizeMethod",java.lang.reflect.Member.class).invoke(null,m); }
                catch(ReflectiveOperationException|RuntimeException ignored){}
                hooks++;
            }
            Probe.log("QS_TILE_DEFERRED_READY", "methods="+hooks+" owner=IconTextTile");
        } catch(ClassNotFoundException|RuntimeException|LinkageError e) {
            Probe.error("QS_TILE_DEFERRED_MISSING",e);
        }
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
                                    String key = QsTileTrace.modelSnapshotKey(model, schema);
                                    synchronized (models) {
                                        if (models.size() < 48 && models.add(key))
                                            Probe.log("QS_TILE_MODEL", "model=" + currentId() + " fields=" + schema);
                                    }
                                }
                            }
                            if ("IconTextTile".equals(prefix) && currentModel.get()!=null) {
                                // DZIF ABI: two Function2 composable slots (icon and text) at
                                // indices 2 and 3; no global ComposableLambda tinting.
                                for(int index=2;index<=3&&index<p.args.length;index++) {
                                    Object lambda=p.args[index];
                                    if(lambda!=null && tileLambdaType(lambda.getClass().getName())
                                            && !ambiguousLambdas.contains(lambda)) {
                                        Object previous=tileLambdas.get(lambda);
                                        if(previous!=null && previous!=currentModel.get()) {
                                            tileLambdas.remove(lambda);
                                            ambiguousLambdas.add(lambda);
                                            Probe.log("QS_TILE_DEFERRED_AMBIGUOUS", "sharedLambda=true skipped=true");
                                        } else tileLambdas.put(lambda,currentModel.get());
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
