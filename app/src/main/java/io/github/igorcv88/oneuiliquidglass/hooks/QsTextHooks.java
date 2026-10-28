package io.github.igorcv88.oneuiliquidglass.hooks;

import android.graphics.Paint;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.*;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;
import io.github.igorcv88.oneuiliquidglass.glass.*;

/**
 * Render-only ink for text nodes created by the allowlisted Samsung QS content owners.
 * Never hooks Material3 Text, mutates a TextStyle, or writes Compose's synthetic arguments.
 * A node's identity, rather than an ambient composition flag, limits later drawing/rebinding.
 */
final class QsTextHooks {
    private final ClassLoader loader;
    private final Supplier<QsContentHooks.Frame> content;
    private final BooleanSupplier enabled;
    private final Supplier<QsScene.Tone> scene, header;
    private final Map<Object,Boolean> elements=Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Object,Boolean> nodes=Collections.synchronizedMap(new WeakHashMap<>());
    private final ThreadLocal<Boolean> drawing=new ThreadLocal<>();
    private Method invalidate;
    QsTextHooks(ClassLoader loader,Supplier<QsContentHooks.Frame> content,BooleanSupplier enabled,
            Supplier<QsScene.Tone> scene,Supplier<QsScene.Tone> header){
        this.loader=loader;this.content=content;this.enabled=enabled;this.scene=scene;this.header=header;
    }
    void install(){
        try {
            Class<?> node=Class.forName("androidx.compose.ui.node.DrawModifierNode",false,loader);
            invalidate=Class.forName("androidx.compose.ui.node.DrawModifierNodeKt",false,loader).getMethod("invalidateDraw",node);
            installText("TextStringSimple");
            installText("TextAnnotatedString");
            installPaint();
        }catch(ReflectiveOperationException|RuntimeException|LinkageError e){
            nodes.clear();elements.clear();Probe.error("QS_TEXT_INCOMPATIBLE",e);
        }
    }
    private void installText(String family) throws ReflectiveOperationException {
        String base="androidx.compose.foundation.text.modifiers.";
        Class<?> element=Class.forName(base+family+"Element",false,loader);
        Class<?> node=Class.forName(base+family+"Node",false,loader);
        Class<?> scope=Class.forName("androidx.compose.ui.graphics.drawscope.ContentDrawScope",false,loader);
        Method create=element.getDeclaredMethod("create"),update=element.getDeclaredMethod("update",node);
        Method draw=node.getDeclaredMethod("draw",scope);
        if(!node.isAssignableFrom(create.getReturnType()) || update.getReturnType()!=void.class || draw.getReturnType()!=void.class)
            throw new NoSuchMethodException(family+" node ABI");
        XposedBridge.hookAllConstructors(element,new XC_MethodHook(){
            @Override protected void afterHookedMethod(MethodHookParam p){
                QsContentHooks.Frame f=content.get();
                if(f!=null)elements.put(p.thisObject,f.header);
            }
        });
        XC_MethodHook bind=new XC_MethodHook(){
            @Override protected void afterHookedMethod(MethodHookParam p){
                bindNode(p.args.length==0?p.getResult():p.args[0],elements.get(p.thisObject));
            }
        };
        XposedBridge.hookMethod(create,bind);XposedBridge.hookMethod(update,bind);
        XposedBridge.hookMethod(draw,new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam p){
                p.setObjectExtra("qsTextPrevious",drawing.get());
                Boolean h=nodes.get(p.thisObject);
                // An unrelated nested node must not inherit the outer node's ownership.
                if(h!=null && enabled.getAsBoolean() && (h?header:scene).get()!=QsScene.Tone.UNKNOWN)
                    drawing.set((h?header:scene).get()==QsScene.Tone.LIGHT_SCENE);
                else drawing.remove();
            }
            @Override protected void afterHookedMethod(MethodHookParam p){
                Boolean previous=(Boolean)p.getObjectExtra("qsTextPrevious");
                if(previous==null)drawing.remove();else drawing.set(previous);
            }
        });
        Probe.log("QS_TEXT_READY","family="+family+" renderOnly=true");
    }
    void bindNode(Object node,Boolean isHeader){
        if(node==null)return;
        if(isHeader==null)nodes.remove(node);else nodes.put(node,isHeader);
    }
    private void installPaint() throws ClassNotFoundException {
        Class<?> canvas=Class.forName("android.graphics.BaseCanvas");
        for(Method m:canvas.getDeclaredMethods()){
            if(!(m.getName().equals("drawText")||m.getName().equals("drawTextRun")))continue;
            Class<?>[] t=m.getParameterTypes();
            if(t.length==0||t[t.length-1]!=Paint.class||Modifier.isNative(m.getModifiers()))continue;
            int index=t.length-1;
            XposedBridge.hookMethod(m,new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam p){
                    Boolean dark=drawing.get();if(dark==null||!enabled.getAsBoolean())return;
                    Paint paint=(Paint)p.args[index];
                    if(paint==null || paint.getShader()!=null || paint.getColorFilter()!=null)return;
                    int original=paint.getColor(),mapped=QuickSettingsGlass.ink(original,dark);
                    if(mapped==original)return;
                    p.setObjectExtra("qsTextColor",original);paint.setColor(mapped);
                }
                @Override protected void afterHookedMethod(MethodHookParam p){
                    Object original=p.getObjectExtra("qsTextColor");
                    if(original instanceof Integer)((Paint)p.args[index]).setColor((Integer)original);
                }
            });
        }
    }
    void invalidate(){
        if(invalidate==null)return;
        synchronized(nodes){
            for(Object node:nodes.keySet())try{invalidate.invoke(null,node);}
            catch(ReflectiveOperationException|RuntimeException e){Probe.error("QS_TEXT_INVALIDATE_FAILED",e);}
        }
    }
}
