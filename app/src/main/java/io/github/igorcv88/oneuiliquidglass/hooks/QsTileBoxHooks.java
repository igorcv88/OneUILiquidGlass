package io.github.igorcv88.oneuiliquidglass.hooks;

import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.*;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;
import io.github.igorcv88.oneuiliquidglass.glass.*;

/** DZIF tile-box semantic colours. No global resource picker or Drawable tint hook. */
final class QsTileBoxHooks {
    private static final String UI="com.android.systemui.samsung.quicksetting.ui.";
    private static final String LAYOUT=UI+"tilebox.QuickTileBoxItemLayoutKt";
    private final ClassLoader loader;
    private final BooleanSupplier enabled;
    private final Supplier<QsStyle> style;
    private final Supplier<QsScene.Tone> scene;
    private final Supplier<Float> lightFraction;
    private final Consumer<Object> capture;
    private final ThreadLocal<Object> model=new ThreadLocal<>();
    private final WeakIdentityMap<Object,WeakReference<Object>> painters=new WeakIdentityMap<>();
    private final WeakIdentityMap<Object,Integer> states=new WeakIdentityMap<>();
    private final WeakIdentityMap<Object,String> roundLogged=new WeakIdentityMap<>();
    private final WeakIdentityMap<Object,String> logged=new WeakIdentityMap<>();
    private final WeakIdentityMap<Object,Boolean> ambiguous=new WeakIdentityMap<>();
    private final WeakIdentityMap<Object,Boolean> covered=new WeakIdentityMap<>();
    private QsComposeSignal signal;
    private Method box;
    private Field value, item, delegate, picker, available, tileState, painter;
    private Method getState, circle;
    private boolean ready;
    private Object paletteKey;
    private int coverageRevision;
    private int runtimeFailures;
    QsTileBoxHooks(ClassLoader l,BooleanSupplier on,Supplier<QsStyle> s,Supplier<QsScene.Tone> tone,
            Supplier<Float> lightFraction,Consumer<Object> c) {
        loader=l;enabled=on;style=s;scene=tone;this.lightFraction=lightFraction;capture=c;
    }
    private Class<?> type(String n) throws ClassNotFoundException {return Class.forName(n,false,loader);}
    private static Field field(Class<?> c,String n) throws NoSuchFieldException {
        Field f=c.getDeclaredField(n);f.setAccessible(true);return f;
    }
    void install() {
        try {
            Class<?> color=type("androidx.compose.ui.graphics.Color");
            value=field(color,"value");box=color.getDeclaredMethod("box-impl",long.class);box.setAccessible(true);
            Class<?> lambda=type(LAYOUT+"$QuickTileBoxItemLayout$iconColor$2$1");
            item=field(lambda,"$item");delegate=field(lambda,"$state$delegate");picker=field(lambda,"$resourceColorPicker");
            available=field(item.getType(),"availableItem");
            getState=type("androidx.compose.runtime.State").getMethod("getValue");
            tileState=field(type("com.android.systemui.plugins.qs.QSTile$State"),"state");
            circle=type("com.android.systemui.samsung.common.resources.ResourceColorPickerImpl")
                    .getDeclaredMethod("getTileIconCircleColor-vNxB06k",int.class);
            circle.setAccessible(true);
            signal=new QsComposeSignal(loader);
            Method layout=type(LAYOUT).getDeclaredMethod("QuickTileBoxItemLayout",
                    type("androidx.compose.ui.Modifier"),item.getType(),boolean.class,
                    type("kotlin.jvm.functions.Function0"),type("kotlin.jvm.functions.Function0"),boolean.class,
                    type("androidx.compose.runtime.Composer"),int.class,int.class);
            hook(layout,new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    p.setObjectExtra("qtbModel",model.get());model.set(p.args[1]);
                    capture.accept(p.args[6]);
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Object old=p.getObjectExtra("qtbModel");if(old==null)model.remove();else model.set(old);
                }
            });
            Method invoke=lambda.getDeclaredMethod("invoke");
            if(invoke.getReturnType()!=Object.class)throw new NoSuchMethodException("iconColor boxed Object");
            hook(invoke,new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    try {signal.read();}catch(ReflectiveOperationException e){fail(e);}
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if(!ready||p.hasThrowable())return;
                    try {
                        Object tile=item.get(p.thisObject);
                        int state=available.get(tile)!=null?1:tileState.getInt(getState.invoke(delegate.get(p.thisObject)));
                        states.put(tile,state);
                        long nativeInk=value.getLong(p.getResult());
                        long nativeBg=(Long)circle.invoke(picker.get(p.thisObject),state);
                        if(!QuickSettingsGlass.srgb(nativeInk)||!QuickSettingsGlass.srgb(nativeBg))return;
                        int bg=background(state,QuickSettingsGlass.argb(nativeBg));
                        int ink=ink(state,QuickSettingsGlass.argb(nativeInk),bg);
                        if(ink!=QuickSettingsGlass.argb(nativeInk))p.setResult(box.invoke(null,QuickSettingsGlass.color(ink)));
                        trace(tile,state,nativeInk,ink,bg,"derived");
                    }catch(ReflectiveOperationException|RuntimeException e){runtimeFailure(e);}
                }
            });
            Class<?> result=type(UI+"tiles.QsTileIconPainterState");
            painter=field(result,"painter");Field layer=field(result,"applyImageLayerColorFilter");
            Method factory=type(UI+"tiles.QsTileIconDrawablePainterKt").getDeclaredMethod(
                    "rememberQsTileIconDrawablePainter-ZLcQsz0",android.graphics.drawable.Drawable.class,color,
                    type("androidx.compose.runtime.Composer"));
            hook(factory,new XC_MethodHook(){
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if(model.get()==null||p.hasThrowable()||p.getResult()==null)return;
                    try {
                        Object icon=painter.get(p.getResult());
                        synchronized(painters) {
                            WeakReference<Object> prior=painters.get(icon);
                            if(prior!=null&&prior.get()!=null&&prior.get()!=model.get()) {ambiguous.put(icon,true);painters.remove(icon);}
                            else if(!ambiguous.containsKey(icon))painters.put(icon,new WeakReference<>(model.get()));
                        }
                        if(Probe.trace&&covered.put(model.get(),true)==null)Probe.log("QS_TILEBOX_COVERAGE",
                                "revision="+coverageRevision+" captured="+covered.size()+" model="+id(model.get())+" painter="+id(icon)
                                +" iconClass="+p.args[0].getClass().getName()+" imageLayerFilter="+layer.getBoolean(p.getResult()));
                    }catch(ReflectiveOperationException|RuntimeException e){runtimeFailure(e);}
                }
            });
            // The native pure background lambda carries $bgColor. Replace its value through a
            // new Function0; never mutate a remembered lambda that another composition may share.
            Class<?> bgLambda=type(LAYOUT+"$QuickTileBoxItemLayout$5$1$1$2$1$1");
            Field bgColor=field(bgLambda,"$bgColor");
            Constructor<?> bgConstructor=bgLambda.getDeclaredConstructor(long.class);bgConstructor.setAccessible(true);
            int roundHooks=0;
            for(Method m:type(UI+"tiles.RoundIconKt").getDeclaredMethods()) {
                if(!m.getName().equals("RoundIcon-It-hVyY")||m.getParameterCount()!=15
                        ||m.getParameterTypes()[4]!=long.class)continue;
                roundHooks++;
                hook(m,new XC_MethodHook(){
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        WeakReference<Object> ref=painters.get(p.args[3]);Object tile=ref==null?null:ref.get();
                        if(tile==null||!bgLambda.isInstance(p.args[1]))return;
                        try {
                            signal.read();capture.accept(p.args[12]);
                            Integer state=states.get(tile);if(state==null)return;
                            long raw=bgColor.getLong(p.args[1]);long rawInk=(Long)p.args[4];
                            if(!QuickSettingsGlass.srgb(raw)||!QuickSettingsGlass.srgb(rawInk))return;
                            int bg=background(state,QuickSettingsGlass.argb(raw));
                            if(bg!=QuickSettingsGlass.argb(raw))p.args[1]=bgConstructor.newInstance(QuickSettingsGlass.color(bg));
                            int ink=ink(state,QuickSettingsGlass.argb(rawInk),bg);
                            p.args[4]=QuickSettingsGlass.color(ink);
                            // Icon colour is param 4: clear its stale Compose dirty bits so the
                            // child observes the new argument even when its caller marked it same.
                            p.args[13]=((Integer)p.args[13])&~(7<<15);
                            trace(tile,state,rawInk,ink,bg,"round");
                        }catch(ReflectiveOperationException|RuntimeException e){runtimeFailure(e);}
                    }
                });
            }
            if(roundHooks!=1)throw new NoSuchMethodException("RoundIcon ABI count="+roundHooks);
            ready=true;refresh();
            Probe.log("QS_TILEBOX_READY","firmware=DZIF derivedState=true painterOwnership=true");
        }catch(ReflectiveOperationException|RuntimeException|LinkageError e){fail(e);}
    }
    private int background(int state,int nativeBg) {
        if(!ready||!enabled.getAsBoolean()||state<1||state>2)return nativeBg;
        // A transparent native circle is intentional: don't invent an active disc.
        QuickSettingsGlass.Kind kind=(nativeBg>>>24)==0?QuickSettingsGlass.Kind.NONE:
                state==2?QuickSettingsGlass.Kind.ACTIVE:QuickSettingsGlass.Kind.IDLE;
        return style.get().tileBackground(kind,scene.get(),nativeBg,lightFraction.get());
    }
    private int ink(int state,int nativeInk,int bg) {
        if(!ready||!enabled.getAsBoolean()||state<0||state>2||scene.get()==QsScene.Tone.UNKNOWN)return nativeInk;
        QsStyle s=style.get();
        QsScene.Tone tone=scene.get();
        if(!s.tileLight&&!s.tileDark)return nativeInk;
        return QsSurfaceInk.onVerifiedTile(nativeInk,bg,state,tone,s,lightFraction.get());
    }
    void refresh() {
        if(!ready)return;
        try {
            Float f=lightFraction.get();
            Object next=Arrays.asList(enabled.getAsBoolean(),scene.get(),style.get(),
                    f==null?-1:Math.round(f*4f));
            if(!next.equals(paletteKey)){paletteKey=next;coverageRevision++;covered.clear();}
            signal.update(next);
        }
        catch(ReflectiveOperationException|RuntimeException e){runtimeFailure(e);}
    }
    private void trace(Object tile,int state,long before,int after,int bg,String stage) {
        if(!Probe.trace)return;
        String key=stage+":"+state+":"+scene.get()+":"+before+":"+after+":"+bg;
        if(key.equals((stage.equals("round")?roundLogged:logged).put(tile,key)))return;
        Probe.log("QS_TILEBOX_INK","model="+id(tile)+" state="+state+" stage="+stage
                +" scene="+scene.get()+" native="+Integer.toHexString(QuickSettingsGlass.argb(before))
                +" mapped="+Integer.toHexString(after)+" surface="+Integer.toHexString(bg));
    }
    private static String id(Object o){return Integer.toHexString(System.identityHashCode(o));}
    private void fail(Throwable e){ready=false;Probe.error("QS_TILEBOX_INCOMPATIBLE",e);}
    /** One unusual tile or recomposition must not disable every other tile for the process. */
    private void runtimeFailure(Throwable e) {
        if(runtimeFailures++<3)Probe.error("QS_TILEBOX_ITEM_FAILED",e);
    }
    private static void hook(Method m,XC_MethodHook callback) {
        XposedBridge.hookMethod(m,callback);
        try{XposedBridge.class.getMethod("deoptimizeMethod",Member.class).invoke(null,m);}
        catch(ReflectiveOperationException|RuntimeException ignored){}
    }
}
