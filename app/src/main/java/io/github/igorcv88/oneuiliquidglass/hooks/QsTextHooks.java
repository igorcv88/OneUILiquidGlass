package io.github.igorcv88.oneuiliquidglass.hooks;

import android.graphics.Paint;
import android.graphics.ColorFilter;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.util.SparseArray;
import java.lang.reflect.*;
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
    private final QsTextTargets elements=new QsTextTargets();
    private final QsTextTargets nodes=new QsTextTargets();
    private final QsTextTargets headerElements=new QsTextTargets(), headerNodes=new QsTextTargets();
    private final ThreadLocal<Boolean> iconDrawing=new ThreadLocal<>();
    private final SparseArray<ColorFilter> iconFilters=new SparseArray<>();
    private final ThreadLocal<Boolean> drawing=new ThreadLocal<>();
    private Method invalidate;
    private boolean ready,headerReady;
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
            installHeaderIcons();
            if(installPaint()==0)throw new NoSuchMethodException("Canvas text ABI");
            ready=true;
            // Constructors may be inlined into BasicText by ART; deoptimize their caller only.
            Class<?> basic=Class.forName("androidx.compose.foundation.text.BasicTextKt",false,loader);
            for(Method m:basic.getDeclaredMethods())if(m.getName().startsWith("BasicText"))try{
                XposedBridge.class.getMethod("deoptimizeMethod",java.lang.reflect.Member.class).invoke(null,m);
            }catch(ReflectiveOperationException|RuntimeException ignored){}
        }catch(ReflectiveOperationException|RuntimeException|LinkageError e){
            ready=false;nodes.clear();elements.clear();headerNodes.clear();headerElements.clear();Probe.error("QS_TEXT_INCOMPATIBLE",e);
        }
    }
    private void installText(String family) throws ReflectiveOperationException {
        String base="androidx.compose.foundation.text.modifiers.";
        Class<?> element=Class.forName(base+family+"Element",false,loader);
        Class<?> node=Class.forName(base+family+"Node",false,loader);
        Class<?> scope=Class.forName("androidx.compose.ui.graphics.drawscope.ContentDrawScope",false,loader);
        QsNodeAbi abi=QsNodeAbi.resolve(element,node,scope);
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
        XposedBridge.hookMethod(abi.create,bind);XposedBridge.hookMethod(abi.update,bind);
        XposedBridge.hookMethod(abi.draw,new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam p){
                p.setObjectExtra("qsTextPrevious",drawing.get());
                p.setObjectExtra("qsIconPrevious",iconDrawing.get());iconDrawing.remove();
                Boolean tone=toneFor(p.thisObject);
                // An unrelated nested node must not inherit the outer node's ownership.
                if(tone==null)drawing.remove();else drawing.set(tone);
            }
            @Override protected void afterHookedMethod(MethodHookParam p){
                Boolean previousIcon=(Boolean)p.getObjectExtra("qsIconPrevious");
                if(previousIcon==null)iconDrawing.remove();else iconDrawing.set(previousIcon);
                Boolean previous=(Boolean)p.getObjectExtra("qsTextPrevious");
                if(previous==null)drawing.remove();else drawing.set(previous);
            }
        });
        Probe.log("QS_TEXT_READY","family="+family+" renderOnly=true");
    }
    private void installHeaderIcons(){
        try{
            Class<?> element=Class.forName("androidx.compose.ui.draw.PainterElement",false,loader);
            Class<?> node=Class.forName("androidx.compose.ui.draw.PainterNode",false,loader);
            Class<?> scope=Class.forName("androidx.compose.ui.graphics.drawscope.ContentDrawScope",false,loader);
            QsNodeAbi abi=QsNodeAbi.resolve(element,node,scope);
            XposedBridge.hookAllConstructors(element,new XC_MethodHook(){
                @Override protected void afterHookedMethod(MethodHookParam p){
                    QsContentHooks.Frame f=content.get();
                    if(f!=null&&f.header)headerElements.put(p.thisObject,true);
                }
            });
            XC_MethodHook bind=new XC_MethodHook(){
                @Override protected void afterHookedMethod(MethodHookParam p){
                    Object target=p.args.length==0?p.getResult():p.args[0];
                    if(target!=null)headerNodes.put(target,headerElements.get(p.thisObject));
                }
            };
            XposedBridge.hookMethod(abi.create,bind);XposedBridge.hookMethod(abi.update,bind);
            XposedBridge.hookMethod(abi.draw,new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam p){
                    p.setObjectExtra("qsHeaderIconPrevious",iconDrawing.get());
                    QsScene.Tone tone=header.get();
                    if(ready&&headerReady&&headerNodes.get(p.thisObject)!=null&&enabled.getAsBoolean()&&tone!=QsScene.Tone.UNKNOWN)
                        iconDrawing.set(tone==QsScene.Tone.LIGHT_SCENE);
                    else iconDrawing.remove();
                }
                @Override protected void afterHookedMethod(MethodHookParam p){
                    Boolean previous=(Boolean)p.getObjectExtra("qsHeaderIconPrevious");
                    if(previous==null)iconDrawing.remove();else iconDrawing.set(previous);
                }
            });
            Class<?> factory=Class.forName("androidx.compose.ui.draw.PainterModifierKt",false,loader);
            for(Method m:factory.getDeclaredMethods())if(m.getName().startsWith("paint"))try{
                XposedBridge.class.getMethod("deoptimizeMethod",java.lang.reflect.Member.class).invoke(null,m);
            }catch(ReflectiveOperationException|RuntimeException ignored){}
            headerReady=true;Probe.log("QS_HEADER_ICONS_READY","renderOnly=true");
        }catch(ReflectiveOperationException|RuntimeException|LinkageError e){
            headerReady=false;headerNodes.clear();headerElements.clear();Probe.error("QS_HEADER_ICONS_INCOMPATIBLE",e);
        }
    }
    ColorFilter applyIconPaint(Paint paint,Boolean dark,boolean on){
        if(dark==null||!on||paint==null||paint.getShader()!=null)return null;
        ColorFilter original=paint.getColorFilter();
        Integer nativeColor=Foreground.filterColor(original);
        if(nativeColor==null)return null; // Untinted painters/logos remain untouched.
        int mapped=QuickSettingsGlass.ink(nativeColor,dark);
        if(mapped==nativeColor)return null;
        ColorFilter filter=iconFilters.get(mapped);
        if(filter==null){filter=new PorterDuffColorFilter(mapped,PorterDuff.Mode.SRC_IN);iconFilters.put(mapped,filter);}
        paint.setColorFilter(filter);return original;
    }
    Boolean toneFor(Object node){
        Boolean h=nodes.get(node);
        if(!ready||h==null||!enabled.getAsBoolean())return null;
        QsScene.Tone tone=(h?header:scene).get();
        return tone==QsScene.Tone.UNKNOWN?null:tone==QsScene.Tone.LIGHT_SCENE;
    }
    static Integer applyPaint(Paint paint,Boolean dark,boolean enabled){
        if(dark==null||!enabled||paint==null||paint.getShader()!=null||paint.getColorFilter()!=null)return null;
        int original=paint.getColor(),mapped=QuickSettingsGlass.ink(original,dark);
        if(mapped==original)return null;
        paint.setColor(mapped);return original;
    }
    void bindNode(Object node,Boolean isHeader){
        if(node==null)return;
        nodes.put(node,isHeader);
    }
    private int installPaint() throws ClassNotFoundException {
        int count=0;
        // Hardware RecordingCanvas uses BaseRecordingCanvas overrides, not BaseCanvas's wrappers.
        for(String owner:new String[]{"android.graphics.BaseCanvas","android.graphics.BaseRecordingCanvas"}){
        Class<?> canvas=Class.forName(owner);
        for(Method m:canvas.getDeclaredMethods()){
            boolean text=m.getName().equals("drawText")||m.getName().equals("drawTextRun");
            boolean icon=m.getName().equals("drawPath")||m.getName().equals("drawBitmap")
                    ||m.getName().equals("drawRect")||m.getName().equals("drawRoundRect")
                    ||m.getName().equals("drawCircle")||m.getName().equals("drawOval")||m.getName().equals("drawLine");
            if(!text&&!icon)continue;
            Class<?>[] t=m.getParameterTypes();
            if(t.length==0||t[t.length-1]!=Paint.class||Modifier.isNative(m.getModifiers()))continue;
            int index=t.length-1;count++;
            XposedBridge.hookMethod(m,new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam p){
                    if(text){
                        Boolean tone=drawing.get();if(tone==null)return;
                        Integer original=applyPaint((Paint)p.args[index],tone,enabled.getAsBoolean());
                        if(original!=null)p.setObjectExtra("qsTextColor",original);
                    }else{
                        Boolean tone=iconDrawing.get();if(tone==null)return;
                        ColorFilter original=applyIconPaint((Paint)p.args[index],tone,enabled.getAsBoolean());
                        if(original!=null)p.setObjectExtra("qsHeaderFilter",original);
                    }
                }
                @Override protected void afterHookedMethod(MethodHookParam p){
                    Object original=p.getObjectExtra("qsTextColor");
                    if(original instanceof Integer)((Paint)p.args[index]).setColor((Integer)original);
                    Object filter=p.getObjectExtra("qsHeaderFilter");
                    if(filter instanceof ColorFilter)((Paint)p.args[index]).setColorFilter((ColorFilter)filter);
                }
            });
        }
        }
        return count;
    }
    void invalidate(){
        if(invalidate==null)return;
        nodes.each(this::invalidateNode);headerNodes.each(this::invalidateNode);
    }
    private void invalidateNode(Object node){
            try{invalidate.invoke(null,node);}
            catch(ReflectiveOperationException|RuntimeException e){Probe.error("QS_TEXT_INVALIDATE_FAILED",e);}
    }
}
