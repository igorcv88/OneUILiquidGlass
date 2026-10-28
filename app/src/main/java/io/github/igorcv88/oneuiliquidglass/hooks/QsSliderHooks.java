package io.github.igorcv88.oneuiliquidglass.hooks;

import android.graphics.Canvas;
import java.lang.reflect.*;
import java.util.function.*;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;
import io.github.igorcv88.oneuiliquidglass.glass.*;

/**
 * DZIF's slider primitives (track rect, progress gradient, thumb roundrect), scoped to the exact
 * bar DrawScope, drawn as exactly two surfaces: the glass capsule (Samsung's BoxWithOutline, whose
 * interior QuickSettingsHooks makes transparent) and one white progress shape inside it. The track
 * rect is never drawn; the thumb is drawn as the progress's own moving end cap, so the two white
 * primitives form one shape. The progress keeps the same margin to the capsule on all four sides
 * and a full circle at the minimum (QuickSettingsGlass.sliderPrimitive). Drawing only: layout,
 * hit area, drag and the slider's value are Samsung's.
 */
final class QsSliderHooks {
    private final ClassLoader loader;
    private final BooleanSupplier enabled;
    private final BooleanSupplier geometryAvailable;
    private final Supplier<QsStyle> style;
    private final Consumer<Object> capture;
    private Method drawContext, size, setSize, getCanvas;
    private Field nativeCanvas;
    private boolean broken, trackHooked;
    private final boolean[] staged=new boolean[3];
    private Object whiteBrush;
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
        final float[] geometry=new float[5], body=new float[5];
        boolean bodyDrawn;
        Frame(Object scope,Object context,Canvas canvas,long original,QsDrawTransaction transaction,float w,float h,boolean rtl,QsStyle style,float outer){
            this.scope=scope;this.context=context;this.canvas=canvas;this.original=original;this.transaction=transaction;
            this.w=w;this.h=h;this.rtl=rtl;
            this.style=style;this.outer=outer;
        }
    }
    /** A finger is down (any SystemUI input), and what to call when a bar moves under it. */
    private final BooleanSupplier touching;
    private final Runnable dragged;
    /** Last native body length per bar DrawScope: a change under a finger is a drag. */
    private final java.util.Map<Object,Float> lengths=new java.util.WeakHashMap<>();
    QsSliderHooks(ClassLoader loader,BooleanSupplier enabled,Supplier<QsStyle> style,BooleanSupplier geometryAvailable,
            Consumer<Object> capture){
        this(loader,enabled,style,geometryAvailable,capture,()->false,()->{});
    }
    QsSliderHooks(ClassLoader loader,BooleanSupplier enabled,Supplier<QsStyle> style,BooleanSupplier geometryAvailable,
            Consumer<Object> capture,BooleanSupplier touching,Runnable dragged){
        this.loader=loader;this.enabled=enabled;this.style=style;this.capture=capture;
        this.geometryAvailable=geometryAvailable;this.touching=touching;this.dragged=dragged;
    }
    /** Whether a bar's value moved while a finger is down (the user is dragging it). */
    static boolean drag(Float previous,float length,boolean touching){
        return touching&&previous!=null&&Math.abs(previous-length)>.5f;
    }
    void install(Class<?> bar) throws ReflectiveOperationException {
        Constructor<?> solid=Class.forName("androidx.compose.ui.graphics.SolidColor",false,loader).getDeclaredConstructor(long.class);
        solid.setAccessible(true);whiteBrush=solid.newInstance(QuickSettingsGlass.color(0xffffffff));
        Class<?> scope=Class.forName("androidx.compose.ui.graphics.drawscope.DrawScope",false,loader);
        drawContext=scope.getMethod("getDrawContext");
        Class<?> ctx=drawContext.getReturnType();
        size=ctx.getMethod("getSize-NH-jbRc");setSize=ctx.getMethod("setSize-uvyYCjk",long.class);
        getCanvas=ctx.getMethod("getCanvas");
        nativeCanvas=Class.forName("androidx.compose.ui.graphics.AndroidCanvas",false,loader).getDeclaredField("internalCanvas");
        nativeCanvas.setAccessible(true);
        boolean[] found=new boolean[3];
        for(Method m:scope.getDeclaredMethods()) {
            String n=m.getName();
            int stage=n.equals("drawRect-n-J9OG0$default")?0:n.equals("drawRoundRect-ZuiqVtQ$default")?1
                    :n.equals("drawRoundRect-u-Aw5IA$default")?2:-1;
            if(stage<0)continue;
            if(!primitiveSignature(m,stage,scope)){
                // Logged with its real parameter list: a mismatch here once left the whole slider
                // native from #78 on (the track check expected a Brush where DZIF passes a colour).
                Probe.log("QS_SLIDER_SIGNATURE","stage="+stage+" accepted=false method="+m.toGenericString());
                continue;
            }
            XposedBridge.hookMethod(m,new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam p){primitive(p,stage);}
            });
            found[stage]=true;deoptimize(m);
        }
        // Body and thumb carry the geometry; without them the slider stays native. A missing track
        // hook only leaves Samsung's track visible inside the glass.
        if(!found[1]||!found[2])throw new NoSuchMethodException("slider primitives track="+found[0]+" body="+found[1]+" thumb="+found[2]);
        trackHooked=found[0];
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
        Probe.log("QS_SLIDER_READY","surfaces=2 track="+(trackHooked?"hidden":"native")+" thumb=endCap firmware=DZIF");
    }
    private void begin(XC_MethodHook.MethodHookParam p){
        if(broken||!enabled.getAsBoolean())return;
        try {
            Object context=drawContext.invoke(p.args[0]);Object c=getCanvas.invoke(context);
            Object raw=nativeCanvas.getDeclaringClass().isInstance(c)?nativeCanvas.get(c):null;
            if(!(raw instanceof Canvas))return;
            Canvas canvas=(Canvas)raw;long original=(Long)size.invoke(context);
            float w=x(original),h=y(original);
            if(!(w>0)||!(h>0)||!Float.isFinite(w)||!Float.isFinite(h))return;
            QsStyle snapshot=style.get();
            float outer=geometryAvailable.getAsBoolean()?snapshot.outer:1f;
            // The progress's margin to the capsule, also kept at both ends of the long axis: the
            // native progress is computed over this shorter length, so 100 % ends inside the glass.
            float m=Math.min(QuickSettingsGlass.sliderMargin(Math.min(w,h),outer,snapshot.fill),Math.max(w,h)/4f);
            float iw=w<=h?w:w-2*m, ih=w<=h?h-2*m:h;
            Frame frame=new Frame(p.args[0],context,canvas,original,new QsDrawTransaction(canvas,context,setSize,original),iw,ih,
                    "Rtl".equals(String.valueOf(p.args[3])),snapshot,outer);
            p.setObjectExtra("qsSliderFrame",frame);p.setObjectExtra("qsSliderPrevious",current.get());current.set(frame);
            canvas.translate(w<=h?0:m,w<=h?m:0);
            setSize.invoke(context,pack(iw,ih));
        }catch(ReflectiveOperationException|RuntimeException|LinkageError e){end(p);fail(e);}
    }
    private void primitive(XC_MethodHook.MethodHookParam p,int stage){
        Frame f=current.get();if(broken||f==null||p.args[0]!=f.scope)return;
        if(!staged[stage]){
            // Once per stage and process: proof that the primitive really ran in the bar's scope.
            staged[stage]=true;
            Probe.log("QS_SLIDER_STAGE","stage="+stage+" action="+(suppressTrack(stage)?"trackSuppressed":stage==1?"bodyDrawn":"capDrawn")
                    +" size="+Math.round(f.w)+"x"+Math.round(f.h)+" fill="+f.style.fill);
        }
        if(suppressTrack(stage)){p.setResult(null);return;} // No visual track, even for legacy presets.
        try {
            int last=p.args.length-1, mask=(Integer)p.args[last];
            long offset=(mask&2)!=0?0L:(Long)p.args[2];
            long extent=(mask&4)!=0?pack(f.w,f.h):(Long)p.args[3];
            QsStyle s=f.style;
            // The thumb is the fill's end cap: it takes the fill's thickness, as in Samsung's draw.
            float[] g=QuickSettingsGlass.sliderPrimitive(f.w,f.h,f.outer,s.fill,x(offset),y(offset),x(extent),y(extent),
                    stage==2,f.bodyDrawn?f.body:null,f.geometry);
            if(!(g[2]>0)||!(g[3]>0)){p.setResult(null);return;}
            if(stage==1){
                System.arraycopy(g,0,f.body,0,5);f.bodyDrawn=true;
                float length=f.w<=f.h?y(extent):x(extent);
                Float previous=lengths.put(f.scope,length);
                if(drag(previous,length,touching.getAsBoolean()))dragged.run();
            }
            p.args[2]=pack(g[0],g[1]);p.args[3]=pack(g[2],g[3]);
            p.args[last]=explicitGeometryMask(mask,stage!=0);
            p.args[4]=pack(g[4],g[4]);
            p.args[1]=stage==1?whiteBrush:QuickSettingsGlass.color(0xffffffff);
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
    static boolean suppressTrack(int stage){return stage==0;}
    /**
     * The $default bridges, checked by the parameters this class reads or writes, not by count:
     * [0] the DrawScope, [1] the paint (a long colour for stages 0 and 2, a Brush for stage 1),
     * [2] topLeft and [3] size (longs), [4] the corner radius for the round rects (a long), and the
     * $default mask last (an int). R8 drops parameters a firmware never passes: DZIF's thumb bridge
     * has no ColorFilter (9 parameters, device log 28/10), and a fixed count of 10 rejected it,
     * failing the whole slider install. Everything between [4] and the mask is passed through
     * untouched, so its shape is not required.
     */
    static boolean primitiveSignature(Method m,int stage,Class<?> scope){
        Class<?>[] t=m.getParameterTypes();
        int min=stage==0?5:6;
        if(t.length<min||t[0]!=scope||t[t.length-1]!=int.class)return false;
        if(stage==1?!t[1].getName().equals("androidx.compose.ui.graphics.Brush"):t[1]!=long.class)return false;
        if(t[2]!=long.class||t[3]!=long.class)return false;
        if(stage!=0&&t[4]!=long.class)return false;
        // The mask must follow at least one more int (the blend mode) or the alpha/style group.
        for(int i=stage==0?4:5;i<t.length-1;i++)
            if(t[i]==long.class)return false; // a fifth long would mean the geometry is not where we write it
        return true;
    }
    static int explicitGeometryMask(int mask,boolean rounded){return mask&~(2|4|(rounded?8:0));}
    private static float x(long v){return Float.intBitsToFloat((int)(v>>>32));}
    private static float y(long v){return Float.intBitsToFloat((int)v);}
    private static long pack(float x,float y){return ((long)Float.floatToRawIntBits(x)<<32)|(Float.floatToRawIntBits(y)&0xffffffffL);}
    private static void deoptimize(Method method){
        try{XposedBridge.class.getMethod("deoptimizeMethod",Member.class).invoke(null,method);}
        catch(ReflectiveOperationException|RuntimeException ignored){}
    }
}
