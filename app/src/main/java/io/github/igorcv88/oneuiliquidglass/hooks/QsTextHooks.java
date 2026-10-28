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
    /** Ink darkness per region (true: header band), animated by QsBackdrop; null: native ink. */
    private final Function<Boolean,Float> level;
    private final QsTextTargets elements=new QsTextTargets();
    private final QsTextTargets nodes=new QsTextTargets();
    private final QsTextTargets headerElements=new QsTextTargets(), headerNodes=new QsTextTargets();
    private final ThreadLocal<Float> iconDrawing=new ThreadLocal<>();
    private final SparseArray<ColorFilter> iconFilters=new SparseArray<>();
    private final ThreadLocal<Float> drawing=new ThreadLocal<>();
    private final ThreadLocal<Object> drawingNode=new ThreadLocal<>();
    private final java.util.Map<Object,String> owners=new java.util.WeakHashMap<>();
    private final java.util.Map<Object,Integer> drawnTones=new java.util.WeakHashMap<>();
    private volatile long unboundClockLoggedAt;
    private Method invalidate;
    private boolean ready,headerReady;
    QsTextHooks(ClassLoader loader,Supplier<QsContentHooks.Frame> content,BooleanSupplier enabled,
            Supplier<QsScene.Tone> scene,Supplier<QsScene.Tone> header){
        this(loader,content,enabled,scene,header,h->level((h?header:scene).get()));
    }
    QsTextHooks(ClassLoader loader,Supplier<QsContentHooks.Frame> content,BooleanSupplier enabled,
            Supplier<QsScene.Tone> scene,Supplier<QsScene.Tone> header,Function<Boolean,Float> level){
        this.loader=loader;this.content=content;this.enabled=enabled;this.scene=scene;this.header=header;this.level=level;
    }
    static Float level(QsScene.Tone tone){
        return tone==null||tone==QsScene.Tone.UNKNOWN?null:tone==QsScene.Tone.LIGHT_SCENE?1f:0f;
    }
    /**
     * Each part installs on its own and reports its own failure with the member it missed
     * (QS_TEXT_INCOMPATIBLE step=...): one text family, the header icons or the draw invalidation
     * being absent on a firmware no longer takes every label with it (device log 28/10: one
     * NoSuchMethodException, no member named, no label adapted).
     */
    void install(){
        invalidate=resolveInvalidate();
        int families=0,paints=0;
        for(String family:new String[]{"TextStringSimple","TextAnnotatedString"}){
            try{installText(family);families++;}
            catch(ReflectiveOperationException|RuntimeException|LinkageError e){Probe.error("QS_TEXT_INCOMPATIBLE","family="+family,e);}
        }
        installHeaderIcons();
        try{paints=installPaint();}
        catch(ReflectiveOperationException|RuntimeException|LinkageError e){Probe.error("QS_TEXT_INCOMPATIBLE","paint",e);}
        ready=families>0&&paints>0;
        if(!ready){nodes.clear();elements.clear();headerNodes.clear();headerElements.clear();}
        Probe.log("QS_TEXT_STATUS","ready="+ready+" families="+families+" paintHooks="+paints
                +" headerIcons="+headerReady+" invalidateDraw="+(invalidate!=null));
        // Constructors may be inlined into BasicText by ART; deoptimize their caller only.
        try{
            Class<?> basic=Class.forName("androidx.compose.foundation.text.BasicTextKt",false,loader);
            for(Method m:basic.getDeclaredMethods())if(m.getName().startsWith("BasicText"))try{
                XposedBridge.class.getMethod("deoptimizeMethod",java.lang.reflect.Member.class).invoke(null,m);
            }catch(ReflectiveOperationException|RuntimeException ignored){}
        }catch(ClassNotFoundException|LinkageError ignored){}
    }
    /**
     * DrawModifierNodeKt.invalidateDraw(DrawModifierNode), or any static one-argument method of
     * that class taking the node interface (R8 may rename or merge it); null when absent: a tone
     * change then reaches labels on their next redraw (and the recomposition QuickSettingsHooks
     * runs), not at once.
     */
    private Method resolveInvalidate(){
        try{
            Class<?> node=Class.forName("androidx.compose.ui.node.DrawModifierNode",false,loader);
            Class<?> kt=Class.forName("androidx.compose.ui.node.DrawModifierNodeKt",false,loader);
            try{return kt.getMethod("invalidateDraw",node);}catch(NoSuchMethodException ignored){}
            for(Method m:kt.getDeclaredMethods())
                if(java.lang.reflect.Modifier.isStatic(m.getModifiers())&&m.getParameterCount()==1&&m.getParameterTypes()[0]==node
                        &&m.getReturnType()==void.class){m.setAccessible(true);return m;}
            Probe.log("QS_TEXT_INCOMPATIBLE","step=invalidateDraw optional=true candidates="+QsNodeAbi.candidates(kt,"invalidateDraw"));
        }catch(ClassNotFoundException|LinkageError|RuntimeException e){Probe.error("QS_TEXT_INCOMPATIBLE","invalidateDraw",e);}
        return null;
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
                if(f!=null) {
                    elements.put(p.thisObject,f.header);
                    if (Probe.trace) Probe.log("QS_TEXT_PATH", "family=" + family + " role=" + (f.header ? "header-unverified" : "label")
                            + " id=" + Integer.toHexString(System.identityHashCode(p.thisObject)) + " registered=true bound=false drawn=false");
                }
            }
        });
        XC_MethodHook bind=new XC_MethodHook(){
            @Override protected void afterHookedMethod(MethodHookParam p){
                Object node=p.args.length==0?p.getResult():p.args[0];
                bindNode(node,elements.get(p.thisObject));
                if (node != null && nodes.get(node) != null) {
                    owners.put(node, family);
                    if (Probe.trace) Probe.log("QS_TEXT_PATH", "family=" + family + " id=" + Integer.toHexString(System.identityHashCode(node))
                            + " element=" + Integer.toHexString(System.identityHashCode(p.thisObject)) + " registered=true bound=true drawn=false");
                }
            }
        };
        XposedBridge.hookMethod(abi.create,bind);XposedBridge.hookMethod(abi.update,bind);
        XposedBridge.hookMethod(abi.draw,new XC_MethodHook(){
            @Override protected void beforeHookedMethod(MethodHookParam p){
                p.setObjectExtra("qsTextPrevious",drawing.get());
                p.setObjectExtra("qsTextNodePrevious",drawingNode.get());
                drawingNode.set(p.thisObject);
                p.setObjectExtra("qsIconPrevious",iconDrawing.get());iconDrawing.remove();
                Float tone=inkFor(p.thisObject);
                // An unrelated nested node must not inherit the outer node's ownership.
                if(tone==null)drawing.remove();else drawing.set(tone);
            }
            @Override protected void afterHookedMethod(MethodHookParam p){
                Object previousNode=p.getObjectExtra("qsTextNodePrevious");
                if(previousNode==null)drawingNode.remove();else drawingNode.set(previousNode);
                Float previousIcon=(Float)p.getObjectExtra("qsIconPrevious");
                if(previousIcon==null)iconDrawing.remove();else iconDrawing.set(previousIcon);
                Float previous=(Float)p.getObjectExtra("qsTextPrevious");
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
                    Boolean header=headerElements.get(p.thisObject);
                    // Same rule as text nodes: an update from an unknown element keeps the node.
                    if(target!=null&&(header!=null||headerNodes.get(target)==null))headerNodes.put(target,header);
                }
            };
            XposedBridge.hookMethod(abi.create,bind);XposedBridge.hookMethod(abi.update,bind);
            XposedBridge.hookMethod(abi.draw,new XC_MethodHook(){
                @Override protected void beforeHookedMethod(MethodHookParam p){
                    p.setObjectExtra("qsHeaderIconPrevious",iconDrawing.get());
                    // The header follows the panel or the notification shade: the level function gates it.
                    Float tone=ready&&headerReady&&headerNodes.get(p.thisObject)!=null?level.apply(true):null;
                    if(tone!=null)iconDrawing.set(tone);
                    else iconDrawing.remove();
                }
                @Override protected void afterHookedMethod(MethodHookParam p){
                    Float previous=(Float)p.getObjectExtra("qsHeaderIconPrevious");
                    if(previous==null)iconDrawing.remove();else iconDrawing.set(previous);
                }
            });
            Class<?> factory=Class.forName("androidx.compose.ui.draw.PainterModifierKt",false,loader);
            for(Method m:factory.getDeclaredMethods())if(m.getName().startsWith("paint"))try{
                XposedBridge.class.getMethod("deoptimizeMethod",java.lang.reflect.Member.class).invoke(null,m);
            }catch(ReflectiveOperationException|RuntimeException ignored){}
            headerReady=true;Probe.log("QS_HEADER_ICONS_READY","renderOnly=true");
        }catch(ReflectiveOperationException|RuntimeException|LinkageError e){
            headerReady=false;headerNodes.clear();headerElements.clear();Probe.error("QS_HEADER_ICONS_INCOMPATIBLE","painter",e);
        }
    }
    ColorFilter applyIconPaint(Paint paint,Boolean dark,boolean on){
        return applyIconPaint(paint,dark==null?null:dark?1f:0f,on);
    }
    ColorFilter applyIconPaint(Paint paint,Float dark,boolean on){
        if(dark==null||!on||paint==null||paint.getShader()!=null)return null;
        ColorFilter original=paint.getColorFilter();
        Integer nativeColor=Foreground.filterColor(original);
        if(nativeColor==null)return null; // Untinted painters/logos remain untouched.
        int mapped=QuickSettingsGlass.ink(nativeColor,(float)dark);
        if(mapped==nativeColor)return null;
        ColorFilter filter=iconFilters.get(mapped);
        if(filter==null){
            // Bounded: a transition walks through at most a few dozen greys.
            if(iconFilters.size()>=96)iconFilters.clear();
            filter=new PorterDuffColorFilter(mapped,PorterDuff.Mode.SRC_IN);iconFilters.put(mapped,filter);
        }
        paint.setColorFilter(filter);return original;
    }
    /** Decided tone of a registered node (true: dark ink); null for native ink. */
    Boolean toneFor(Object node){
        Float level=inkFor(node);
        return level==null?null:level>=.5f;
    }
    /** Ink darkness of a registered node at this frame (mid-transition included); null for native ink. */
    Float inkFor(Object node){
        Boolean h=nodes.get(node);
        if(!ready||h==null)return null;
        // Body labels need QS on; the header also follows the notification shade (level gates it).
        if(!h&&!enabled.getAsBoolean())return null;
        Float l=level.apply(h);

        return l;
    }
    /** Called only after a Canvas text primitive completed, never merely on node entry. */
    private void painted(Object node, int original, int result, String canvas, boolean clock) {
        if (node == null) return;
        int bucket = result & 0xffffff;
        boolean h = Boolean.TRUE.equals(nodes.get(node));
        // A header node that drew a time of day is the clock: recorded once, and logged again.
        boolean newClock = h && clock && clocks.put(node, Boolean.TRUE) == null;
        Integer previous = drawnTones.put(node, bucket);
        if (previous != null && previous == bucket && !newClock) return;
        boolean isClock = h && clocks.get(node) != null;
        String detail = "family=" + owners.get(node) + " role=" + (h ? isClock ? "header-clock" : "header-unverified" : "label")
                + " id=" + Integer.toHexString(System.identityHashCode(node))
                + " registered=true bound=true drawn=true originalTone=" + Integer.toHexString(original)
                + " finalTone=" + Integer.toHexString(result) + " canvas=" + canvas;
        if (Probe.trace) {
            Probe.log("QS_TEXT_PATH", detail);
            Probe.log("INK_DRAWN", detail);
        }
        if (h) Probe.log("QS_HEADER_TEXT_DRAWN", detail + " ink="
                + (bucket == QuickSettingsGlass.DARK_INK ? "dark" : bucket == 0xffffff ? "light" : "native")
                + " clockVerified=" + isClock);
    }
    /** Header nodes whose drawn text had the shape of a time of day (weak keys). */
    private final java.util.Map<Object, Boolean> clocks = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private static final java.util.regex.Pattern CLOCK =
            java.util.regex.Pattern.compile("\\s*\\d{1,2}[:.\\u2236]\\d{2}(\\s*[AaPp]\\.?[Mm]\\.?)?\\s*");
    /**
     * Whether a Canvas text call draws a time of day ("18:44", "6:44 PM"). drawText/drawTextRun
     * take (char[], index, count, ...) or (CharSequence|String, start, end, ...) or the whole
     * string; only the shape is tested, nothing is kept or logged.
     */
    static boolean clockText(Object[] args) {
        if (args == null || args.length == 0) return false;
        CharSequence run = null;
        try {
            if (args[0] instanceof char[]) {
                char[] c = (char[]) args[0];
                int i = args.length > 2 && args[1] instanceof Integer ? (Integer) args[1] : 0;
                int n = args.length > 2 && args[2] instanceof Integer ? (Integer) args[2] : c.length;
                if (i >= 0 && n >= 0 && i + n <= c.length && n <= 16) run = new String(c, i, n);
            } else if (args[0] instanceof CharSequence) {
                CharSequence t = (CharSequence) args[0];
                boolean range = args.length > 2 && args[1] instanceof Integer && args[2] instanceof Integer;
                int a = range ? (Integer) args[1] : 0, b = range ? (Integer) args[2] : t.length();
                if (a >= 0 && b >= a && b <= t.length() && b - a <= 16) run = t.subSequence(a, b);
            }
        } catch (RuntimeException e) { return false; }
        return run != null && CLOCK.matcher(run).matches();
    }
    static Integer applyPaint(Paint paint,Boolean dark,boolean enabled){
        return applyPaint(paint,dark==null?null:dark?1f:0f,enabled);
    }
    static Integer applyPaint(Paint paint,Float dark,boolean enabled){
        return applyPaint(paint,dark,enabled,false);
    }
    /** Only QS labels: Samsung's semi-transparent native text made dark ink look washed-out. */
    static Integer applyLabelPaint(Paint paint,Float dark,boolean enabled){
        return applyPaint(paint,dark,enabled,true);
    }
    private static Integer applyPaint(Paint paint,Float dark,boolean enabled,boolean opaqueLabel){
        if(dark==null||!enabled||paint==null||paint.getShader()!=null||paint.getColorFilter()!=null)return null;
        int original=paint.getColor(),mapped=QuickSettingsGlass.ink(original,(float)dark);
        // Never recolour non-neutral logos or decorative text: ink() leaves those unchanged.
        if(opaqueLabel && QuickSettingsGlass.neutral(original))
            mapped=(mapped&0xffffff)|0xff000000;
        if(mapped==original)return null;
        paint.setColor(mapped);return original;
    }
    /**
     * A node keeps its registration when Compose updates it from an element built outside an
     * allowlisted composable (a label's own scope recomposing alone, e.g. a new Wi-Fi name): only
     * a node rebound to a known foreign element, or collected, leaves the registry. Otherwise a
     * tile label lost its ink at its first independent recomposition and stayed white over white.
     */
    void bindNode(Object node,Boolean isHeader){
        if(node==null||isHeader==null&&nodes.get(node)!=null)return;
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
                        Float tone=drawing.get();
                        if(tone==null){
                            // The shade's visible clock may be a distinct, unregistered Compose
                            // owner. Diagnose actual Canvas paint without logging any text.
                            if(Probe.trace)probeUnboundClock(p.args,(Paint)p.args[index],owner);
                            return;
                        }
                        // The tone was authorised when the node's draw began (inkFor / the header
                        // gate), including the shade-driven header with QS glass off.
                        Paint paint=(Paint)p.args[index];
                        if (paint == null) return;
                        p.setObjectExtra("qsPaintBefore",paint.getColor());
                        Object owningNode=drawingNode.get();
                        p.setObjectExtra("qsPaintNode",owningNode);
                        // Header nodes only: whether this run is a time of day (shape, never content).
                        if(owningNode!=null&&Boolean.TRUE.equals(nodes.get(owningNode)))
                            p.setObjectExtra("qsPaintClock",clockText(p.args));
                        Integer original=Boolean.FALSE.equals(nodes.get(owningNode))
                                ? applyLabelPaint(paint,tone,true) : applyPaint(paint,tone,true);
                        p.setObjectExtra("qsPaintAfter",paint.getColor());
                        if(original!=null)p.setObjectExtra("qsTextColor",original);
                    }else{
                        Float tone=iconDrawing.get();if(tone==null)return;
                        ColorFilter original=applyIconPaint((Paint)p.args[index],tone,true);
                        if(original!=null)p.setObjectExtra("qsHeaderFilter",original);
                    }
                }
                @Override protected void afterHookedMethod(MethodHookParam p){
                    if(text && !p.hasThrowable() && p.getObjectExtra("qsPaintBefore") instanceof Integer)
                        painted(p.getObjectExtra("qsPaintNode"), (Integer)p.getObjectExtra("qsPaintBefore"),
                                (Integer)p.getObjectExtra("qsPaintAfter"), owner,
                                Boolean.TRUE.equals(p.getObjectExtra("qsPaintClock")));
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
    private void probeUnboundClock(Object[] args,Paint paint,String canvas){
        if(paint==null||!clockText(args))return;
        long now=android.os.SystemClock.uptimeMillis();
        if(now-unboundClockLoggedAt<2000)return;
        unboundClockLoggedAt=now;
        Probe.log("QS_CLOCK_CANDIDATE","role=unbound canvas="+canvas
                +" paint="+Integer.toHexString(paint.getColor())+" alpha="+paint.getAlpha()
                +" registered=false mutation=false");
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
