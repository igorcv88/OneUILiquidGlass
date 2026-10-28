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
    private final Consumer<Object> capture;
    private final java.util.function.Function<Boolean,Float> level;
    private QsTextHooks text;
    QsContentHooks(ClassLoader loader, BooleanSupplier enabled, Supplier<QsStyle> style,
            Supplier<QsScene.Tone> scene, Supplier<QsScene.Tone> headerScene,
            java.util.function.Function<Boolean,Float> level, Consumer<Object> capture) {
        this.loader=loader; this.enabled=enabled; this.style=style; this.scene=scene; this.headerScene=headerScene; this.capture=capture;
        this.level=level;
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
        // Tile icons keep Samsung's colours: only labels follow the backdrop.
        hookMediaCapsule(prefix+"media.components.MediaArtworkKt$$ExternalSyntheticLambda4");
        Probe.log("QS_CONTENT_READY","owners="+hooked+" firmware=DZIF");
    }
    /** The player's distance to its glass capsule (dp), close to the slider progress's margin. */
    static final float MEDIA_INSET_DP = 6f, MEDIA_RIM_DP = 1.15f;
    /** Each artwork draw block's own page shape: one entry per player, never a shared field. */
    private final java.util.Map<Object,Object> mediaShapes=java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    /**
     * The active media player as a player inside a glass capsule. MediaArtworkKt's drawWithContent
     * block (artwork, gradients and the content it draws) is scaled uniformly about the page
     * centre so it ends {@link #MEDIA_INSET_DP} inside the outline: nothing is cut, only resized
     * (~3 %). It is drawn inside its own shape (the page shape scaled the same way), and the
     * capsule's rim is drawn on the page's outline. The ring between them is the same glass as
     * every capsule (the page background is transparent, QuickSettingsHooks). Drawing only: layout
     * and touch targets are Samsung's, so a control drawn by this block can appear up to the inset
     * closer to the centre than its touch area. Each block instance keeps the page shape it was
     * built under (its constructor runs inside the page's composition). A missing class or member
     * leaves the player native (QS_MEDIA_CAPSULE_MISSING).
     */
    private void hookMediaCapsule(String name) {
        Class<?> c=optional(name);
        if(c==null){Probe.log("QS_MEDIA_CAPSULE_MISSING","member=class");return;}
        try {
            Class<?> scopeType=Class.forName("androidx.compose.ui.graphics.drawscope.DrawScope",false,loader);
            Method getSize=scopeType.getMethod("getSize-NH-jbRc");
            Class<?> densityType=Class.forName("androidx.compose.ui.unit.Density",false,loader);
            Method density=densityType.getMethod("getDensity");
            Method context=scopeType.getMethod("getDrawContext");
            Method canvas=context.getReturnType().getMethod("getCanvas");
            java.lang.reflect.Field raw=Class.forName("androidx.compose.ui.graphics.AndroidCanvas",false,loader).getDeclaredField("internalCanvas");
            raw.setAccessible(true);
            Method toPx=Class.forName("androidx.compose.foundation.shape.CornerSize",false,loader).getMethod("toPx-TmRCtEA",densityType,long.class);
            java.lang.reflect.Field topStart=Class.forName("androidx.compose.foundation.shape.CornerBasedShape",false,loader).getDeclaredField("topStart");
            topStart.setAccessible(true);
            XposedBridge.hookAllConstructors(c,new XC_MethodHook(){
                @Override protected void afterHookedMethod(MethodHookParam p){
                    Frame f=content.get();
                    if(f!=null&&f.media&&f.shape!=null)mediaShapes.put(p.thisObject,f.shape);
                }
            });
            android.graphics.Paint rim=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            rim.setStyle(android.graphics.Paint.Style.STROKE);
            int hooked=0;
            for(Method m:c.getDeclaredMethods()){
                if(!m.getName().equals("invoke")||m.getParameterCount()!=1)continue;
                hooked++;
                XposedBridge.hookMethod(m,new XC_MethodHook(){
                    private boolean failed, logged;
                    @Override protected void beforeHookedMethod(MethodHookParam p){
                        if(failed||!enabled.getAsBoolean()||!scopeType.isInstance(p.args[0]))return;
                        try{
                            Object surface=raw.get(canvas.invoke(context.invoke(p.args[0])));
                            if(!(surface instanceof android.graphics.Canvas))return;
                            android.graphics.Canvas cv=(android.graphics.Canvas)surface;
                            long size=(Long)getSize.invoke(p.args[0]);
                            float w=Float.intBitsToFloat((int)(size>>>32)),h=Float.intBitsToFloat((int)size);
                            float d=(Float)density.invoke(p.args[0]);
                            Object shape=mediaShapes.get(p.thisObject);
                            Object corner=shape==null||!topStart.getDeclaringClass().isInstance(shape)?null:topStart.get(shape);
                            float r=corner==null?Math.min(w,h)*.18f:(Float)toPx.invoke(corner,p.args[0],size);
                            float[] g=QuickSettingsGlass.mediaCapsule(w,h,r,MEDIA_INSET_DP*d,MEDIA_RIM_DP*d);
                            if(g==null)return;
                            int save=cv.save();
                            p.setObjectExtra("qsMedia",new Object[]{save,cv,g,w,h});
                            cv.translate(g[1],g[2]);
                            cv.scale(g[0],g[0]);
                            // In the block's own coordinates: its page shape, scaled with it.
                            android.graphics.Path own=new android.graphics.Path();
                            own.addRoundRect(0,0,w,h,g[3],g[3],android.graphics.Path.Direction.CW);
                            cv.clipPath(own);
                            if(!logged){logged=true;Probe.log("QS_MEDIA_CAPSULE","size="+Math.round(w)+"x"+Math.round(h)+" radius="+r
                                    +" scale="+g[0]+" shape="+(shape!=null));}
                        }catch(ReflectiveOperationException|RuntimeException|LinkageError e){failed=true;Probe.error("QS_MEDIA_CAPSULE_FAILED",e);}
                    }
                    @Override protected void afterHookedMethod(MethodHookParam p){
                        Object extra=p.getObjectExtra("qsMedia");
                        if(!(extra instanceof Object[]))return;
                        Object[] x=(Object[])extra;
                        android.graphics.Canvas cv=(android.graphics.Canvas)x[1];
                        cv.restoreToCount((Integer)x[0]);
                        float[] g=(float[])x[2];float w=(Float)x[3],h=(Float)x[4];
                        try{
                            synchronized(rim){
                                rim.setStrokeWidth(g[4]);
                                rim.setColor(QuickSettingsGlass.rim(true,scene.get(),style.get()));
                                float half=g[4]/2f;
                                cv.drawRoundRect(half,half,w-half,h-half,Math.max(0,g[3]-half),Math.max(0,g[3]-half),rim);
                            }
                        }catch(RuntimeException|LinkageError e){failed=true;Probe.error("QS_MEDIA_CAPSULE_FAILED",e);}
                    }
                });
                deoptimize(m);
            }
            Probe.log(hooked>0?"QS_MEDIA_CAPSULE_READY":"QS_MEDIA_CAPSULE_MISSING","invokes="+hooked);
        }catch(ReflectiveOperationException|RuntimeException|LinkageError e){Probe.error("QS_MEDIA_CAPSULE_MISSING",e);}
    }
    private static void deoptimize(Method method) {
        try{XposedBridge.class.getMethod("deoptimizeMethod",java.lang.reflect.Member.class).invoke(null,method);}
        catch(ReflectiveOperationException|RuntimeException ignored){}
    }
    /** Text and header icons re-read the ink at draw time: dirty their draw, no recomposition. */
    void invalidateDraw() {
        if(text!=null)text.invalidate();
    }
}
