package io.github.igorcv88.oneuiliquidglass.hooks;

import android.content.res.ColorStateList;
import android.view.View;
import android.widget.ImageView;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.*;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;
import io.github.igorcv88.oneuiliquidglass.glass.*;

/** Native brightness Lottie property and volume AndroidView, verified in DZIF DEX. */
final class QsSliderIconHooks {
    private static final String UI="com.android.systemui.samsung.quicksetting.ui.tiles.";
    private final ClassLoader loader;
    private final BooleanSupplier enabled;
    private final Supplier<QsStyle> style;
    private final Supplier<QsScene.Tone> scene;
    private final Consumer<Object> capture;
    private final ThreadLocal<Float> brightness=new ThreadLocal<>();
    private final ThreadLocal<Integer> volume=new ThreadLocal<>();
    private final Map<Object,Integer> volumes=new WeakHashMap<>();
    private final Set<String> logged=new HashSet<>();
    private Method updateVolume;
    private QsComposeSignal signal;
    private Object lastKey;
    QsSliderIconHooks(ClassLoader l,BooleanSupplier on,Supplier<QsStyle> s,Supplier<QsScene.Tone> t,Consumer<Object> c) {
        loader=l;enabled=on;style=s;scene=t;capture=c;
    }
    private Class<?> type(String name)throws ClassNotFoundException{return Class.forName(name,false,loader);}
    void install() {
        try {
            signal=new QsComposeSignal(loader);
            for(String owner:new String[]{"BrightBar","BrightBarWithTile"}) {
            Class<?> bright=type(UI+owner+"Kt");
            for(Method m:bright.getDeclaredMethods()) {
                if(m.getName().equals(owner)) hook(m,new XC_MethodHook(){
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        p.setObjectExtra("qsBrightLevel",brightness.get());brightness.set(Float.NaN);
                        p.args[4]=((Integer)p.args[4])|1;
                        try{signal.read();capture.accept(p.args[3]);}catch(ReflectiveOperationException e){Probe.error("QS_BRIGHT_ICON_FAILED",e);}
                    }
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        Float old=(Float)p.getObjectExtra("qsBrightLevel");if(old==null)brightness.remove();else brightness.set(old);
                    }
                });
                if(m.getName().equals(owner+"$lambda$1")&&m.getReturnType()==float.class)hook(m,new XC_MethodHook(){
                    @Override protected void afterHookedMethod(MethodHookParam p){
                        if(brightness.get()!=null&&!p.hasThrowable())brightness.set((Float)p.getResult());
                    }
                });
                if(m.getName().equals("rememberColorFilterProperty-RPmYEkk")&&m.getParameterCount()==2)hook(m,new XC_MethodHook(){
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        Float progress=brightness.get();long raw=(Long)p.args[0];
                        if(progress==null||!Float.isFinite(progress)||!QuickSettingsGlass.srgb(raw))return;
                        int mapped=map(QuickSettingsGlass.argb(raw),progress<=0);
                        p.args[0]=QuickSettingsGlass.color(mapped);
                        trace("brightness",progress<=0,mapped);
                    }
                });
            }
        }
        }catch(ReflectiveOperationException|RuntimeException|LinkageError e){Probe.error("QS_BRIGHT_ICON_INCOMPATIBLE",e);}
        try {
            Class<?> icon=type("com.android.systemui.volume.view.icon.QPVolumeIcon");
            updateVolume=icon.getDeclaredMethod("updateLayout",int.class);updateVolume.setAccessible(true);
            // Only instances actually returned/passed by VolumeBar's AndroidView factory/update.
            Method factory=type(UI+"VolumeBarKt$$ExternalSyntheticLambda4").getDeclaredMethod("invoke",Object.class);
            hook(factory,new XC_MethodHook(){
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Object root=icon.isInstance(p.getResult())?p.getResult():icon.isInstance(p.args[0])?p.args[0]:null;
                    if(root==null)return;
                    // updateLayout has already observed its exact numeric level during initialize.
                    Integer level=initialLevels.remove(root);
                    if(level!=null)volumes.put(root,level);
                    if(volumes.containsKey(root))try{updateVolume.invoke(root,volumes.get(root));}
                    catch(ReflectiveOperationException e){Probe.error("QS_VOLUME_ICON_FAILED",e);}
                }
            });
            hook(updateVolume,new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    int level=(Integer)p.args[0];initialLevels.put(p.thisObject,level);
                    p.setObjectExtra("qsVolumePrevious",volume.get());
                    if(volumes.containsKey(p.thisObject)){volumes.put(p.thisObject,level);volume.set(level);}
                    else volume.remove();
                }
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Integer old=(Integer)p.getObjectExtra("qsVolumePrevious");if(old==null)volume.remove();else volume.set(old);
                }
            });
            hook(ImageView.class.getDeclaredMethod("setImageTintList",ColorStateList.class),new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    Integer level=volume.get();if(level==null||p.args[0]==null)return;
                    ColorStateList nativeTint=(ColorStateList)p.args[0];
                    // The native method uses a single resource colour. Keep stateful/accent warnings.
                    if(nativeTint.isStateful())return;
                    int nativeColor=nativeTint.getDefaultColor(),mapped=map(nativeColor,level<=0);
                    if(mapped!=nativeColor)p.args[0]=ColorStateList.valueOf(mapped);
                    trace("volume",level<=0,mapped);
                }
            });
            Probe.log("QS_SLIDER_ICON_NATIVE_READY","brightness=LottieProperty volume=QPVolumeIcon");
        }catch(ReflectiveOperationException|RuntimeException|LinkageError e){Probe.error("QS_VOLUME_ICON_INCOMPATIBLE",e);}
    }
    private final Map<Object,Integer> initialLevels=new WeakHashMap<>();
    private int map(int nativeColor,boolean empty) {
        if(!enabled.getAsBoolean()||scene.get()==QsScene.Tone.UNKNOWN)return nativeColor;
        QsStyle s=style.get();boolean light=scene.get()==QsScene.Tone.LIGHT_SCENE;
        return QsSurfaceInk.onSurface(nativeColor,empty?0:light?s.sliderLightColor:s.sliderDarkColor,light);
    }
    void refresh() {
        Object key=Arrays.asList(enabled.getAsBoolean(),scene.get(),style.get());
        if(key.equals(lastKey))return;lastKey=key;
        try {if(signal!=null)signal.update(key);}catch(ReflectiveOperationException e){Probe.error("QS_BRIGHT_ICON_FAILED",e);}
        if(updateVolume!=null)for(Map.Entry<Object,Integer> entry:new ArrayList<>(volumes.entrySet())) {
            if(!((View)entry.getKey()).isAttachedToWindow())continue;
            try{updateVolume.invoke(entry.getKey(),entry.getValue());}
            catch(ReflectiveOperationException|RuntimeException e){Probe.error("QS_VOLUME_ICON_FAILED",e);}
        }
    }
    private void trace(String role,boolean empty,int ink) {
        if(!Probe.trace)return;
        String key=role+":"+empty+":"+scene.get()+":"+ink;
        if(logged.size()<64&&logged.add(key))Probe.log("QS_SLIDER_ICON_ROUTE","owner=native role="+role+" empty="+empty
                +" scene="+scene.get()+" ink="+Integer.toHexString(ink));
    }
    private static void hook(Method m,XC_MethodHook callback) {
        XposedBridge.hookMethod(m,callback);
        try{XposedBridge.class.getMethod("deoptimizeMethod",Member.class).invoke(null,m);}
        catch(ReflectiveOperationException|RuntimeException ignored){}
    }
}
