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
    private final java.util.function.Function<Boolean,Float> level;
    private QsTextHooks text;
    QsContentHooks(ClassLoader loader, BooleanSupplier enabled, Supplier<QsStyle> style,
            Supplier<QsScene.Tone> scene, Supplier<QsScene.Tone> headerScene,
            java.util.function.Function<Boolean,Float> level,
            Supplier<QuickSettingsGlass.Kind> circle, Consumer<Object> capture) {
        this.loader=loader; this.enabled=enabled; this.style=style; this.scene=scene; this.headerScene=headerScene; this.capture=capture;
        this.circle=circle; this.level=level;
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
                        content.set(new Frame(header,media,composer));
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
        text = new QsTextHooks(loader, this::current, enabled, scene, headerScene, level);
        text.install();
        hookQsIcon(prefix+"tiles.QSIconKt");
        // No veil over the media artwork either: the capsule interior is transparent in every scene.
        Probe.log("QS_CONTENT_READY","owners="+hooked+" firmware=DZIF");
    }
    private void hookQsIcon(String name) {
        Class<?> c=optional(name); if(c==null) return;
        int count=0;
        for(Method m:c.getDeclaredMethods()) {
            if(!QsInkSignature.accepts(m))continue;
            count++;
            XposedBridge.hookMethod(m,new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if(!enabled.getAsBoolean() || scene.get()!=QsScene.Tone.LIGHT_SCENE || !Boolean.TRUE.equals(p.args[5]))return;
                    long original=(Long)p.args[3];
                    if(!QuickSettingsGlass.srgb(original))return;
                    QuickSettingsGlass.Kind state=iconState(p.args[1],circle.get());
                    p.args[3]=QuickSettingsGlass.color(QuickSettingsGlass.iconInk(QuickSettingsGlass.argb(original),state,true));
                    // No Composer, TextStyle, $changed or $default writes.
                }
            });
            deoptimize(m);
        }
        if(count==0)Probe.log("QS_ICON_INCOMPATIBLE","native=true");
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
    /** Text and header icons re-read the ink at draw time: dirty their draw, no recomposition. */
    void invalidateDraw() {
        if(text!=null)text.invalidate();
    }
}
