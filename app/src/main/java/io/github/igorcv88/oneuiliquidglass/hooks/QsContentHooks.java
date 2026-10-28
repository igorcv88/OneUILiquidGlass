package io.github.igorcv88.oneuiliquidglass.hooks;

import java.lang.reflect.Method;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;
import io.github.igorcv88.oneuiliquidglass.glass.*;

/** Verified DZIF composables and their content lambdas. No window-wide colour filter. */
final class QsContentHooks {
    static final class Frame {
        final boolean header, media;
        final Object composer;
        Object shape;
        boolean background;
        Frame(boolean header, boolean media, Object composer) {
            this.header=header; this.media=media; this.composer=composer;
        }
    }
    private final ThreadLocal<Frame> content = new ThreadLocal<>();
    private final ClassLoader loader;
    private final BooleanSupplier enabled;
    private final Supplier<QsStyle> style;
    private final Supplier<QsScene.Tone> scene, headerScene;
    private final Supplier<QuickSettingsGlass.Kind> circle;
    private static final class IconState {
        final java.lang.ref.WeakReference<Object> painter;
        QuickSettingsGlass.Kind kind;
        IconState(Object painter,QuickSettingsGlass.Kind kind){this.painter=new java.lang.ref.WeakReference<>(painter);this.kind=kind;}
    }
    private final java.util.List<IconState> icons=new java.util.ArrayList<>();
    private final Consumer<Object> capture;
    private int inkLogged;
    private Method invalidateDraw;
    private final java.util.List<java.lang.ref.WeakReference<Object>> artworkNodes=new java.util.ArrayList<>();
    QsContentHooks(ClassLoader loader, BooleanSupplier enabled, Supplier<QsStyle> style,
            Supplier<QsScene.Tone> scene, Supplier<QsScene.Tone> headerScene,
            Supplier<QuickSettingsGlass.Kind> circle, Consumer<Object> capture) {
        this.loader=loader; this.enabled=enabled; this.style=style; this.scene=scene; this.headerScene=headerScene; this.capture=capture;
        this.circle=circle;
    }
    Frame current() { return content.get(); }
    private Class<?> optional(String name) {
        try { return Class.forName(name, false, loader); }
        catch (ClassNotFoundException | LinkageError e) { return null; }
    }
    void install() {
        String prefix="com.android.systemui.samsung.quicksetting.ui.";
        String[] owners={
            "panel.SecQuickSettingHeaderKt", "panel.SecQuickSettingHeaderKt$$ExternalSyntheticLambda9",
            "panel.SecQuickSettingHeaderKt$$ExternalSyntheticLambda31", "panel.SecQuickSettingHeaderKt$$ExternalSyntheticLambda5",
            "tiles.QuickTileKt", "tiles.IconTextTileKt", "tiles.QuickTileKt$$ExternalSyntheticLambda6",
            "tiles.QuickTileKt$$ExternalSyntheticLambda14", "tiles.QuickTileKt$$ExternalSyntheticLambda15",
            "tiles.QuickTileBoxKt$$ExternalSyntheticLambda5", "tiles.QuickTileBoxKt$$ExternalSyntheticLambda11",
            "tiles.OneUI90EmptyMediaPlayerKt", "tiles.OneUI90EmptyMediaPlayerKt$$ExternalSyntheticLambda7",
            "tiles.EmptyMediaPlayerKt$$ExternalSyntheticLambda11", "tiles.MultiSIMPreferredBarKt",
            "media.components.OneUI90MediaPlayerPageKt", "media.components.OneUI90MediaPlayerInfoKt",
            "media.components.OneUI90MediaPlayerInfoKt$$ExternalSyntheticLambda1",
            "media.components.OneUI90MediaPlayerInfoKt$$ExternalSyntheticLambda2",
            "media.components.OneUI90MediaPlayerHeaderKt", "media.components.OneUI90MediaPlayerHeaderKt$$ExternalSyntheticLambda7",
            "media.components.OneUI90MediaPlayerHeaderKt$$ExternalSyntheticLambda16",
            "media.components.OneUI90MediaActionButtonsKt", "media.components.MediaOutputButtonKt"
        };
        int hooked=0;
        for (String name:owners) {
            Class<?> c=optional(prefix+name); if(c==null) continue;
            for(Method m:c.getDeclaredMethods()) {
                boolean composable=false;
                for(Class<?> t:m.getParameterTypes()) composable |= t.getName().equals("androidx.compose.runtime.Composer");
                if(!composable && !m.getName().equals("invoke")) continue;
                final boolean header=name.startsWith("panel."), media=name.equals("media.components.OneUI90MediaPlayerPageKt");
                XposedBridge.hookMethod(m,new XC_MethodHook(){
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        Object composer=null;
                        for(Object a:p.args) if(a!=null && a.getClass().getName().contains("ComposerImpl")) { composer=a; break; }
                        if(composer!=null) capture.accept(composer); // retain live OFF -> ON recomposition
                        Frame previous=content.get(); p.setObjectExtra("qsContentPrevious",previous);
                        if(enabled.getAsBoolean()) content.set(new Frame(header,media,composer));
                    }
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        Frame previous=(Frame)p.getObjectExtra("qsContentPrevious");
                        if(previous==null) content.remove(); else content.set(previous);
                    }
                });
                deoptimize(m);
                hooked++;
            }
        }
        Class<?> clip=optional("androidx.compose.ui.draw.ClipKt");
        if(clip!=null) for(Method m:clip.getDeclaredMethods()) if(m.getName().equals("clip"))
            XposedBridge.hookMethod(m,new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    Frame f=content.get();
                    if(f!=null && f.media && !f.background && p.args.length==2) f.shape=p.args[1];
                }
            });
        hookInk("androidx.compose.material3.TextKt",false,false);
        hookInk("androidx.compose.material3.IconKt",true,false);
        hookInk(prefix+"tiles.QSIconKt",true,true);
        hookArtwork(prefix+"media.components.MediaArtworkKt$$ExternalSyntheticLambda4");
        Probe.log("QS_CONTENT_READY","owners="+hooked+" firmware=DZIF");
    }
    private void hookArtwork(String name) {
        Class<?> c=optional(name);if(c==null){Probe.log("QS_ARTWORK_MISSING","firmware=DZIF");return;}
        try {
            Class<?> node=Class.forName("androidx.compose.ui.node.DrawModifierNode",false,loader);
            invalidateDraw=Class.forName("androidx.compose.ui.node.DrawModifierNodeKt",false,loader).getMethod("invalidateDraw",node);
            Class<?> element=Class.forName("androidx.compose.ui.draw.DrawWithContentElement",false,loader);
            java.lang.reflect.Field block=element.getDeclaredField("onDraw");block.setAccessible(true);
            for(Method m:element.getDeclaredMethods())if(m.getName().equals("create")||m.getName().equals("update"))
                XposedBridge.hookMethod(m,new XC_MethodHook(){
                    @Override protected void afterHookedMethod(MethodHookParam p){
                        try{bindArtwork(p.args.length==0?p.getResult():p.args[0],c.isInstance(block.get(p.thisObject)));}
                        catch(ReflectiveOperationException|RuntimeException e){Probe.error("QS_ARTWORK_BIND_FAILED",e);}
                    }
                });
            Method context=Class.forName("androidx.compose.ui.graphics.drawscope.DrawScope",false,loader).getMethod("getDrawContext");
            Method canvas=context.getReturnType().getMethod("getCanvas");
            java.lang.reflect.Field raw=Class.forName("androidx.compose.ui.graphics.AndroidCanvas",false,loader).getDeclaredField("internalCanvas");
            raw.setAccessible(true);
            for(Method m:c.getDeclaredMethods())if(m.getName().equals("invoke")&&m.getParameterCount()==1)
                XposedBridge.hookMethod(m,new XC_MethodHook(){
                    private boolean failed;
                    private boolean logged;
                    @Override protected void afterHookedMethod(MethodHookParam p){
                        if(failed||!enabled.getAsBoolean()||scene.get()!=QsScene.Tone.LIGHT_SCENE)return;
                        try{
                            Object wrapper=canvas.invoke(context.invoke(p.args[0]));
                            Object nativeCanvas=raw.get(wrapper);
                            if(nativeCanvas instanceof android.graphics.Canvas) {
                                ((android.graphics.Canvas)nativeCanvas).drawColor(QuickSettingsGlass.fill(
                                        QuickSettingsGlass.Kind.CONTAINER,0,false,scene.get(),style.get()));
                                if(!logged){logged=true;Probe.log("QS_ARTWORK_APPLIED","scene=light");}
                            }
                        }catch(ReflectiveOperationException|RuntimeException|LinkageError e){failed=true;Probe.error("QS_ARTWORK_FAILED",e);}
                    }
                });
            Probe.log("QS_ARTWORK_READY","firmware=DZIF");
        }catch(ReflectiveOperationException|RuntimeException|LinkageError e){Probe.error("QS_ARTWORK_FAILED",e);}
    }
    private void hookInk(String name, boolean icon, boolean qsIcon) {
        Class<?> c=optional(name); if(c==null) return;
        for(Method m:c.getDeclaredMethods()) {
            int color=icon?3:2;
            if(!(m.getName().startsWith(icon ? qsIcon?"QSIcon-":"Icon-":"Text-"))
                    ||m.getParameterCount()<=color||m.getParameterTypes()[color]!=long.class) continue;
            XposedBridge.hookMethod(m,new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    Frame f=content.get();
                    if(!qsIcon&&f==null || !enabled.getAsBoolean())return;
                    QuickSettingsGlass.Kind state=qsIcon?iconState(p.args[1],circle.get()):QuickSettingsGlass.Kind.NONE;
                    boolean header=f!=null&&f.header;
                    QsScene.Tone local=header?headerScene.get():scene.get();
                    if(local!=QsScene.Tone.LIGHT_SCENE) return;
                    if(qsIcon && (p.args.length<6 || !Boolean.TRUE.equals(p.args[5]))) return; // untinted provider logo
                    long original=(Long)p.args[color];
                    if(!QuickSettingsGlass.srgb(original) && original!=16L) return;
                    int nativeInk=original==16L?0xffffffff:QuickSettingsGlass.argb(original);
                    boolean darkInk=header||style.get().lightGlass;
                    p.args[color]=QuickSettingsGlass.color(icon&&!header?QuickSettingsGlass.iconInk(nativeInk,state,darkInk)
                            :QuickSettingsGlass.ink(nativeInk,darkInk));
                    int family=header?1:qsIcon?2:icon?4:8;
                    if((inkLogged&family)==0){inkLogged|=family;Probe.log("QS_INK","family="+family+" dark="+darkInk);}
                    int composer=-1;
                    Class<?>[] types=m.getParameterTypes();
                    for(int i=0;i<types.length;i++) if(types[i].getName().equals("androidx.compose.runtime.Composer")) composer=i;
                    if(composer>=0) {
                        capture.accept(p.args[composer]);
                        if(composer+1<p.args.length && p.args[composer+1] instanceof Integer)
                            p.args[composer+1]=((Integer)p.args[composer+1])|1;
                    }
                    // Color's default bit is index 2 for Text and 3 for Icon. QSIcon has no default mask.
                    boolean defaults=!qsIcon && (icon ? p.args.length==8 : true);
                    if(defaults) {
                        int last=p.args.length-1;
                        p.args[last]=((Integer)p.args[last])&~(1<<color);
                    }
                }
            });
            deoptimize(m);
        }
    }
    private static void deoptimize(Method method) {
        try{XposedBridge.class.getMethod("deoptimizeMethod",java.lang.reflect.Member.class).invoke(null,method);}
        catch(ReflectiveOperationException|RuntimeException ignored){}
    }
    private QuickSettingsGlass.Kind iconState(Object painter,QuickSettingsGlass.Kind observed) {
        synchronized(icons){
            for(java.util.Iterator<IconState> it=icons.iterator();it.hasNext();){
                IconState s=it.next();Object p=s.painter.get();
                if(p==null){it.remove();continue;}
                if(p==painter){
                    if(observed==QuickSettingsGlass.Kind.ACTIVE||observed==QuickSettingsGlass.Kind.IDLE)s.kind=observed;
                    return s.kind;
                }
            }
            if(observed==QuickSettingsGlass.Kind.ACTIVE||observed==QuickSettingsGlass.Kind.IDLE){
                if(icons.size()>=64)icons.remove(0);
                icons.add(new IconState(painter,observed));return observed;
            }
            return QuickSettingsGlass.Kind.NONE;
        }
    }
    void bindArtwork(Object node,boolean target) {
        if(node==null)return;
        synchronized(artworkNodes){
            artworkNodes.removeIf(ref->ref.get()==null||ref.get()==node);
            if(target){
                if(artworkNodes.size()>=32)artworkNodes.remove(0);
                artworkNodes.add(new java.lang.ref.WeakReference<>(node));
            }
        }
    }
    /** A Compose recomposition alone may reuse the same draw block. Dirty its display list too. */
    void invalidateArtwork() {
        if(invalidateDraw==null)return;
        synchronized(artworkNodes){
            for(java.util.Iterator<java.lang.ref.WeakReference<Object>> it=artworkNodes.iterator();it.hasNext();){
                Object node=it.next().get();if(node==null){it.remove();continue;}
                try{invalidateDraw.invoke(null,node);}
                catch(ReflectiveOperationException|RuntimeException e){it.remove();Probe.error("QS_ARTWORK_INVALIDATE_FAILED",e);}
            }
        }
    }
}
