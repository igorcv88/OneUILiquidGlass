package io.github.igorcv88.oneuiliquidglass.hooks;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;
import io.github.igorcv88.oneuiliquidglass.glass.QsScene;
import io.github.igorcv88.oneuiliquidglass.glass.QsStyle;
import io.github.igorcv88.oneuiliquidglass.glass.QuickSettingsGlass;

/**
 * Draw-time fill for verified QuickTile discs. The three large capsules can keep a remembered
 * BackgroundNode for hundreds of milliseconds while their glyphs already follow the new scene.
 * Its colour must therefore follow the same frame as the glyph rather than wait for Compose to
 * call BoxWithOutline again. No unbound background, notification or media node is modified.
 */
final class QsDiscBackgroundHooks {
    private static final class Spec {
        final QuickSettingsGlass.Kind kind;
        final int original;
        Spec(QuickSettingsGlass.Kind kind,int original) {this.kind=kind;this.original=original;}
    }
    private final ClassLoader loader;
    private final BooleanSupplier enabled;
    private final Supplier<QsStyle> style;
    private final Supplier<QsScene.Tone> scene;
    private final Supplier<Float> phase;
    private final WeakIdentityMap<Object,Spec> elements=new WeakIdentityMap<>();
    private final WeakIdentityMap<Object,Spec> nodes=new WeakIdentityMap<>();
    private final Set<Object> liveNodes=Collections.newSetFromMap(new WeakHashMap<>());
    private final WeakIdentityMap<Object,String> lastDrawn=new WeakIdentityMap<>();
    private Field nodeColor, nodeBrush;
    private Method invalidateDraw;
    private boolean ready;
    private int failures;
    private int tracked, bound;

    QsDiscBackgroundHooks(ClassLoader loader,BooleanSupplier enabled,Supplier<QsStyle> style,
            Supplier<QsScene.Tone> scene,Supplier<Float> phase) {
        this.loader=loader;this.enabled=enabled;this.style=style;this.scene=scene;this.phase=phase;
    }

    void install(Class<?> backgroundElement) {
        try {
            Class<?> node=Class.forName("androidx.compose.foundation.BackgroundNode",false,loader);
            Class<?> scope=Class.forName("androidx.compose.ui.graphics.drawscope.ContentDrawScope",false,loader);
            QsNodeAbi abi=QsNodeAbi.resolve(backgroundElement,node,scope);
            nodeColor=node.getDeclaredField("color");
            if(nodeColor.getType()!=long.class)throw new NoSuchFieldException("BackgroundNode.color not long");
            nodeColor.setAccessible(true);
            try {nodeBrush=node.getDeclaredField("brush");nodeBrush.setAccessible(true);}
            catch(NoSuchFieldException ignored){nodeBrush=null;}
            Class<?> drawNode=Class.forName("androidx.compose.ui.node.DrawModifierNode",false,loader);
            invalidateDraw=Class.forName("androidx.compose.ui.node.DrawModifierNodeKt",false,loader)
                    .getMethod("invalidateDraw",drawNode);
            XposedBridge.hookMethod(abi.create,new XC_MethodHook(){
                @Override protected void afterHookedMethod(MethodHookParam p){bind(p.thisObject,p.getResult());}
            });
            XposedBridge.hookMethod(abi.update,new XC_MethodHook(){
                @Override protected void afterHookedMethod(MethodHookParam p){bind(p.thisObject,p.args[0]);}
            });
            XposedBridge.hookMethod(abi.draw,new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    Spec spec=nodes.get(p.thisObject);
                    if(spec==null)return;
                    try {
                        if(nodeBrush!=null&&nodeBrush.get(p.thisObject)!=null)return;
                        long original=nodeColor.getLong(p.thisObject);
                        int mapped=enabled.getAsBoolean()
                                ?style.get().tileBackground(spec.kind,scene.get(),spec.original,phase.get())
                                :spec.original;
                        long current=QuickSettingsGlass.color(mapped);
                        if(current==original)return;
                        p.setObjectExtra("qsDiscRestore",original);
                        nodeColor.setLong(p.thisObject,current);
                        // Per-frame during a crossfade: build the signature only when tracing.
                        String signature=Probe.trace?scene.get()+":"+Integer.toHexString(mapped):null;
                        if(signature!=null&&!signature.equals(lastDrawn.put(p.thisObject,signature)))
                            Probe.log("QS_DISC_DRAW", "node="+Integer.toHexString(System.identityHashCode(p.thisObject))
                                    +" scene="+scene.get()+" original="+Integer.toHexString(spec.original)
                                    +" applied="+Integer.toHexString(mapped)+" phase="+phase.get());
                    } catch(ReflectiveOperationException|RuntimeException e){failure(e);}
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Object previous=p.getObjectExtra("qsDiscRestore");
                    if(previous instanceof Long)try {nodeColor.setLong(p.thisObject,(Long)previous);}
                    catch(ReflectiveOperationException|RuntimeException e){failure(e);}
                }
            });
            ready=true;
            Probe.log("QS_DISC_DRAW_READY", "background=verified-native-node paint=live scene=shared");
        }catch(ReflectiveOperationException|RuntimeException|LinkageError e){
            ready=false;Probe.error("QS_DISC_DRAW_UNAVAILABLE",e);
        }
    }

    /** Called only for a verified QuickTile's circle by BoxWithOutline's background constructor. */
    void element(Object element,QuickSettingsGlass.Kind kind,int original) {
        if(ready&&element!=null&&(kind==QuickSettingsGlass.Kind.ACTIVE||kind==QuickSettingsGlass.Kind.IDLE)) {
            elements.put(element,new Spec(kind,original));
            if(Probe.trace&&tracked++<12)
                Probe.log("QS_DISC_ELEMENT","element="+Integer.toHexString(System.identityHashCode(element))
                        +" kind="+kind+" native="+Integer.toHexString(original));
        }
    }

    private void bind(Object element,Object node) {
        if(node==null)return;
        Spec spec=elements.get(element);
        if(spec==null){nodes.remove(node);synchronized(liveNodes){liveNodes.remove(node);}return;}
        nodes.put(node,spec);
        synchronized(liveNodes){liveNodes.add(node);}
        if(Probe.trace&&bound++<12)
            Probe.log("QS_DISC_BOUND","node="+Integer.toHexString(System.identityHashCode(node))
                    +" kind="+spec.kind);
    }

    /** Draw invalidation, not a full Compose restart: the circle reads its palette every frame. */
    void refresh() {
        if(!ready)return;
        ArrayList<Object> snapshot;
        synchronized(liveNodes){snapshot=new ArrayList<>(liveNodes);}
        for(Object node:snapshot)try {invalidateDraw.invoke(null,node);}
        catch(ReflectiveOperationException|RuntimeException e){failure(e);}
    }

    private void failure(Throwable e){if(failures++<3)Probe.error("QS_DISC_DRAW_FAILED",e);}
}
