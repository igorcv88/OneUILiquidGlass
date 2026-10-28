package io.github.igorcv88.oneuiliquidglass.hooks;

import android.graphics.Canvas;
import android.graphics.Path;
import java.lang.reflect.*;
import java.util.function.*;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;
import io.github.igorcv88.oneuiliquidglass.glass.*;

/** DZIF's track rect, progress gradient and thumb roundrect, scoped to the exact bar DrawScope. */
final class QsSliderHooks {
    private final ClassLoader loader;
    private final BooleanSupplier enabled;
    private final BooleanSupplier geometryAvailable;
    private final Supplier<QsStyle> style;
    private final Consumer<Object> capture;
    private Method drawContext, size, setSize, getCanvas;
    private Field nativeCanvas;
    private boolean broken;
    private final ThreadLocal<Frame> current=new ThreadLocal<>();
    private static final class Frame {
        final Object scope, context;
        final Canvas canvas;
        final long original;
        final QsDrawTransaction transaction;
        final float w,h;
        final boolean rtl;
        final QsStyle style;
        final float outer;
        Frame(Object scope,Object context,Canvas canvas,long original,QsDrawTransaction transaction,float w,float h,boolean rtl,QsStyle style,float outer){
            this.scope=scope;this.context=context;this.canvas=canvas;this.original=original;this.transaction=transaction;
            this.w=w;this.h=h;this.rtl=rtl;
            this.style=style;this.outer=outer;
        }
    }
    QsSliderHooks(ClassLoader loader,BooleanSupplier enabled,Supplier<QsStyle> style,BooleanSupplier geometryAvailable,
            Consumer<Object> capture){
        this.loader=loader;this.enabled=enabled;this.style=style;this.capture=capture;
        this.geometryAvailable=geometryAvailable;
    }
    void install(Class<?> bar) throws ReflectiveOperationException {
        Class<?> scope=Class.forName("androidx.compose.ui.graphics.drawscope.DrawScope",false,loader);
        drawContext=scope.getMethod("getDrawContext");
        Class<?> ctx=drawContext.getReturnType();
        size=ctx.getMethod("getSize-NH-jbRc");setSize=ctx.getMethod("setSize-uvyYCjk",long.class);
        getCanvas=ctx.getMethod("getCanvas");
        nativeCanvas=Class.forName("androidx.compose.ui.graphics.AndroidCanvas",false,loader).getDeclaredField("internalCanvas");
        nativeCanvas.setAccessible(true);
        int primitives=0;
        for(Method m:scope.getDeclaredMethods()) {
            String n=m.getName();
            int stage=n.equals("drawRect-n-J9OG0$default")?0:n.equals("drawRoundRect-ZuiqVtQ$default")?1
                    :n.equals("drawRoundRect-u-Aw5IA$default")?2:-1;
            if(stage<0)continue;
            XposedBridge.hookMethod(m,new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam p){primitive(p,stage);}
                @Override protected void afterHookedMethod(MethodHookParam p){
                    Object saved=p.getObjectExtra("qsPrimitiveCanvasSave");
                    Frame f=current.get();if(saved instanceof Integer&&f!=null)f.canvas.restoreToCount((Integer)saved);
                }
            });
            primitives++;deoptimize(m);
        }
        if(primitives!=3)throw new NoSuchMethodException("slider primitives="+primitives);
        for(Method m:bar.getDeclaredMethods()) {
            if(m.getName().startsWith("BidirectionalBar-"))XposedBridge.hookMethod(m,new XC_MethodHook(){
                // The caller's scope: invalidating it re-runs the bar, so its thickness changes live.
                @Override protected void beforeHookedMethod(MethodHookParam p){
                    for(int i=0;i<m.getParameterCount();i++)if(m.getParameterTypes()[i].getName().equals("androidx.compose.runtime.Composer"))capture.accept(p.args[i]);
                }
            });
            if(!m.getName().equals("drawBidirectionalBarTrackProgressThumbContent-Uzs9YN0"))continue;
            XposedBridge.hookMethod(m,new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam p){begin(p);}
                @Override protected void afterHookedMethod(MethodHookParam p){end(p);}
            });
            deoptimize(m);
        }
        Probe.log("QS_SLIDER_READY","primitives="+primitives+" independent=true firmware=DZIF");
    }
    private void begin(XC_MethodHook.MethodHookParam p){
        if(broken||!enabled.getAsBoolean())return;
        try {
            Object context=drawContext.invoke(p.args[0]);Object c=getCanvas.invoke(context);
            Object raw=nativeCanvas.getDeclaringClass().isInstance(c)?nativeCanvas.get(c):null;
            if(!(raw instanceof Canvas))return;
            Canvas canvas=(Canvas)raw;long original=(Long)size.invoke(context);
            float w=x(original),h=y(original),m=QuickSettingsGlass.sliderInset(w,h);
            if(w<=0||h<=0)return;
            // Keep the established longitudinal inset; transverse dimensions belong to each primitive.
            float iw=w<=h?w:w-2*m, ih=w<=h?h-2*m:h;
            QsStyle snapshot=style.get();
            Frame frame=new Frame(p.args[0],context,canvas,original,new QsDrawTransaction(canvas,context,setSize,original),iw,ih,
                    "Rtl".equals(String.valueOf(p.args[3])),snapshot,geometryAvailable.getAsBoolean()?snapshot.outer:1f);
            p.setObjectExtra("qsSliderFrame",frame);p.setObjectExtra("qsSliderPrevious",current.get());current.set(frame);
            canvas.translate(w<=h?0:m,w<=h?m:0);
            setSize.invoke(context,pack(iw,ih));
        }catch(ReflectiveOperationException|RuntimeException|LinkageError e){end(p);fail(e);}
    }
    private void primitive(XC_MethodHook.MethodHookParam p,int stage){
        Frame f=current.get();if(broken||f==null||p.args[0]!=f.scope)return;
        try {
            int last=p.args.length-1, mask=(Integer)p.args[last];
            long offset=(mask&2)!=0?0L:(Long)p.args[2];
            long extent=(mask&4)!=0?pack(f.w,f.h):(Long)p.args[3];
            QsStyle s=f.style;
            // The thumb is the fill's end cap: it takes the fill's thickness, as in Samsung's draw.
            float ratio=stage==0?s.track:s.fill;
            float[] g=QuickSettingsGlass.sliderPrimitive(f.w,f.h,f.outer,ratio,x(offset),y(offset),x(extent),y(extent),stage==2,f.rtl);
            p.args[2]=pack(g[0],g[1]);p.args[3]=pack(g[2],g[3]);
            p.args[last]=explicitGeometryMask(mask,stage!=0);
            if(stage!=0)p.args[4]=pack(g[4],g[4]);
            else {
                int saved=f.canvas.save();p.setObjectExtra("qsPrimitiveCanvasSave",saved);
                Path pill=new Path();pill.addRoundRect(g[0],g[1],g[0]+g[2],g[1]+g[3],g[4],g[4],Path.Direction.CW);
                f.canvas.clipPath(pill);
            }
        }catch(RuntimeException|LinkageError e){fail(e);}
    }
    private void end(XC_MethodHook.MethodHookParam p){
        Object extra=p.getObjectExtra("qsSliderFrame");if(!(extra instanceof Frame))return;
        p.setObjectExtra("qsSliderFrame",null);Frame f=(Frame)extra;
        current.set((Frame)p.getObjectExtra("qsSliderPrevious"));
        if(current.get()==null)current.remove();
        try{f.transaction.close();}catch(ReflectiveOperationException|RuntimeException|LinkageError e){fail(e);}
    }
    private void fail(Throwable e){if(!broken){broken=true;Probe.error("QS_SLIDER_FAILED",e);}}
    static int explicitGeometryMask(int mask,boolean rounded){return mask&~(2|4|(rounded?8:0));}
    private static float x(long v){return Float.intBitsToFloat((int)(v>>>32));}
    private static float y(long v){return Float.intBitsToFloat((int)v);}
    private static long pack(float x,float y){return ((long)Float.floatToRawIntBits(x)<<32)|(Float.floatToRawIntBits(y)&0xffffffffL);}
    private static void deoptimize(Method method){
        try{XposedBridge.class.getMethod("deoptimizeMethod",Member.class).invoke(null,method);}
        catch(ReflectiveOperationException|RuntimeException ignored){}
    }
}
