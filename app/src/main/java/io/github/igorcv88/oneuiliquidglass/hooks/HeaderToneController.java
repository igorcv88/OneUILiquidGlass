package io.github.igorcv88.oneuiliquidglass.hooks;

import android.content.res.ColorStateList;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.util.*;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;
import io.github.igorcv88.oneuiliquidglass.glass.QuickSettingsGlass;
import io.github.igorcv88.oneuiliquidglass.glass.ShadeTone;
import io.github.igorcv88.oneuiliquidglass.glass.ToneGate;
import io.github.igorcv88.oneuiliquidglass.glass.ToneGrid;

/**
 * Ink of the shade's fixed text targets (clock, date, carrier; the footer's actions), checked
 * before draw. No row walk or global TextView interception.
 *
 * <p>Three states are kept apart. Observed: the latest backdrop sample ({@link ToneGrid}), read
 * under each group's <em>layout</em> bounds (View.getTop up the tree, translations excluded), so
 * the header sliding in during the opening animation never changes what is measured. Decided:
 * one tone per group (clock, date and carrier share one), taken only when a sample arrives, with
 * the classifier's hysteresis and a {@link ToneGate} (a different tone needs a confirming later
 * sample). Shown: the colour on the view; the pre-draw pass only re-applies the decided colour
 * when Samsung wrote its own (a rebind), it never decides. A decided change on a shown view runs
 * a short colour transition ({@link InkAnimator}); the first decision of a session applies at
 * once.</p>
 */
final class HeaderToneController {
    private final java.util.function.BiFunction<View,String,View> lookup;
    HeaderToneController() { this((host,name)->{
        int id=host.getResources().getIdentifier(name,"id",host.getContext().getPackageName());
        return id==0?null:host.findViewById(id);
    }); }
    HeaderToneController(java.util.function.BiFunction<View,String,View> lookup) { this.lookup=lookup; }
    private static final String[] IDS={"header_clock","header_date","shade_carrier_text","noti_setting","clear_all"};
    /** A different tone needs a second sample this much later, and no sooner than DWELL after the last change. */
    static final long CONFIRM_MS=150, DWELL_MS=400;
    /** The decision unit: the header's labels share one, each footer action has its own. */
    private static String group(String id){
        return id.equals("noti_setting")||id.equals("clear_all")?id:"header";
    }
    private static final class Group {
        final ToneGate gate=new ToneGate(CONFIRM_MS,DWELL_MS);
        /** Decided tone (true: light backdrop, dark ink); null before the first valid sample. */
        Boolean light;
        /** The sample last decided from, so a pre-draw never decides twice on the same one. */
        ToneGrid decidedOn;
    }
    private static final class Ink {
        final WeakReference<TextView> view;
        final String id, group;
        ColorStateList original;
        int applied;
        Ink(TextView v,String id){view=new WeakReference<>(v);this.id=id;group=group(id);original=v.getTextColors();applied=v.getCurrentTextColor();}
    }
    private WeakReference<View> root=new WeakReference<>(null);
    private final List<Ink> inks=new ArrayList<>();
    private final Map<String,Group> groups=new HashMap<>();
    private final List<Runnable> checks=new ArrayList<>();
    private ViewTreeObserver observer;
    private ToneGrid grid;
    private int generation, logs;
    private final ViewTreeObserver.OnPreDrawListener draw=()->{
        try{repair();}
        catch(RuntimeException e){Probe.error("HEADER_INK_FAILED",e);clear();}
        return true;
    };
    private final View.OnAttachStateChangeListener attach=new View.OnAttachStateChangeListener(){
        @Override public void onViewAttachedToWindow(View v){}
        @Override public void onViewDetachedFromWindow(View v){clear();}
    };

    /** A backdrop sample of the current session: decides every group it can measure. */
    void update(View host,ToneGrid sample){
        if(sample==null)return;
        bind(host);
        grid=sample;
        long now=SystemClock.uptimeMillis();
        for(Group g:groupsInUse())decide(g,now);
        repair();
    }
    /** An explicit decision for every group (no sample): the same tone everywhere. */
    void update(View host,boolean lightBackdrop){
        bind(host);
        grid=null;
        for(Group g:groupsInUse()){
            boolean was=g.light!=null;
            if(g.light!=null&&g.light==lightBackdrop)continue;
            g.gate.reset();g.gate.offer(lightBackdrop,SystemClock.uptimeMillis());
            g.light=lightBackdrop;
            transition(g,was,"explicit");
        }
        repair();
    }
    private void bind(View host){
        if(root.get()==host)return;
        clear();root=new WeakReference<>(host);host.addOnAttachStateChangeListener(attach);
        observer=host.getViewTreeObserver();observer.addOnPreDrawListener(draw);
        acquire();
        // Views Samsung creates or binds after the first frame (the footer's actions).
        for(int delay:new int[]{0,100,350,700,1500}){
            int ticket=generation;
            Runnable check=()->{
                if(ticket!=generation)return;
                acquire();repair();
                if(Probe.trace)for(Ink i:inks){TextView v=i.view.get();if(v!=null)log("sample",i,v,v.getCurrentTextColor(),"delay="+delay);}
            };
            checks.add(check);host.postDelayed(check,delay);
        }
    }
    /** Whether a group waits for the sample that confirms a change (the caller samples sooner). */
    boolean pending(){
        for(Group g:groups.values())if(g.gate.pending())return true;
        return false;
    }
    private Collection<Group> groupsInUse(){
        for(Ink i:inks)groups.computeIfAbsent(i.group,k->new Group());
        return groups.values();
    }
    /**
     * One group's tone from the current sample, under its views' layout bounds. A group with no
     * laid-out view yet stays undecided (retried on pre-draw against the same sample).
     */
    private void decide(Group g,long now){
        if(grid==null||g.decidedOn==grid)return;
        int top=Integer.MAX_VALUE,bottom=Integer.MIN_VALUE;
        for(Ink i:inks){
            if(groups.get(i.group)!=g)continue;
            TextView v=i.view.get();
            if(v==null||v.getHeight()<=0||!v.isAttachedToWindow())continue;
            int y=layoutTop(v);
            top=Math.min(top,y);bottom=Math.max(bottom,y+v.getHeight());
        }
        if(top>=bottom)return; // No stale/global fallback before layout.
        float luma=grid.luma(top,bottom);
        g.decidedOn=grid;
        if(!Float.isFinite(luma)||luma<0||luma>1)return;
        boolean light=ShadeTone.lightBackdrop(luma,g.light);
        boolean was=g.light!=null;
        io.github.igorcv88.oneuiliquidglass.glass.Tuning t=io.github.igorcv88.oneuiliquidglass.glass.Tuning.get();
        if(!g.gate.offer(light,now,ToneGate.strong(luma,(t.styleLo+t.styleHi)*.5f)))return;
        if(was&&g.light==light)return;
        g.light=light;
        transition(g,was,"sample luma="+String.format(Locale.ROOT,"%.2f",luma)+" rows="+top+"-"+bottom);
    }
    /**
     * The view's top in its window from layout positions alone: the opening animation moves the
     * header with translations and clips, which this ignores, so the measured region is where the
     * header rests, not where it happens to be on this frame.
     */
    static int layoutTop(View v){
        int y=v.getTop();
        for(ViewParent p=v.getParent();p instanceof View;p=p.getParent()){
            View g=(View)p;
            y+=g.getTop()-g.getScrollY();
        }
        return y;
    }
    /** Shows a group's new tone: animated on a shown view whose tone was already decided, else at once. */
    private void transition(Group g,boolean animate,String reason){
        for(Ink i:inks){
            if(groups.get(i.group)!=g)continue;
            TextView v=i.view.get();if(v==null)continue;
            int before=v.getCurrentTextColor();
            if(before!=i.applied&&!InkAnimator.running(v))i.original=v.getTextColors();
            int desired=desired(i,v,g.light);
            i.applied=desired;
            boolean shown=animate&&v.isShown();
            InkAnimator.run(v,new Text(v),desired,shown,null);
            log("apply",i,v,before,"tone="+(g.light?"dark":"light")+" target="+Integer.toHexString(desired)
                    +" animated="+shown+" shown="+v.isShown()+" alpha="+v.getAlpha()+" "+reason);
        }
    }
    private static final class Text implements InkAnimator.Target {
        final TextView v;
        Text(TextView v){this.v=v;}
        @Override public int shown(){return v.getCurrentTextColor();}
        @Override public void show(int color){v.setTextColor(color);}
    }
    private static int desired(Ink i,TextView v,boolean light){
        boolean action=i.id.equals("noti_setting")||i.id.equals("clear_all");
        int nativeColor=i.original.getColorForState(v.getDrawableState(),i.original.getDefaultColor());
        int desired=action&&!light?nativeColor:QuickSettingsGlass.ink(nativeColor,light);
        return (desired&0xffffff)|(nativeColor&0xff000000); // Keep disabled/secondary alpha.
    }
    private void acquire(){
        View host=root.get();if(host==null)return;
        for(String name:IDS){
            View v=lookup.apply(host,name);
            if(!(v instanceof TextView))continue;
            boolean known=false;for(Ink i:inks)if(i.view.get()==v){known=true;break;}
            if(!known)inks.add(new Ink((TextView)v,name));
        }
        for(Iterator<Ink> it=inks.iterator();it.hasNext();){
            Ink i=it.next();TextView v=i.view.get();
            if(v==null||!v.isAttachedToWindow()){restore(i);it.remove();}
        }
    }
    /** Pre-draw: decides groups still waiting for layout, re-applies decided colours Samsung replaced. */
    private void repair(){
        boolean lost=false;
        for(Ink i:inks){TextView v=i.view.get();if(v==null||!v.isAttachedToWindow()){lost=true;break;}}
        if(lost)acquire();
        if(grid!=null){
            long now=SystemClock.uptimeMillis();
            for(Group g:groupsInUse())if(g.decidedOn!=grid)decide(g,now);
        }
        for(Ink i:inks){
            TextView v=i.view.get();if(v==null||InkAnimator.running(v))continue;
            Group g=groups.get(i.group);
            if(g==null||g.light==null)continue;
            int current=v.getCurrentTextColor();
            if(current!=i.applied)i.original=v.getTextColors();
            int desired=desired(i,v,g.light);
            i.applied=desired;
            if(current!=desired){
                v.setTextColor(desired);
                log("repair",i,v,current,"tone="+(g.light?"dark":"light"));
            }
        }
    }
    private void log(String phase,Ink ink,TextView v,int before,String extra){
        if(!Probe.trace&&logs++>=24)return;
        Probe.log("HEADER_INK","phase="+phase+" id="+ink.id+" class="+v.getClass().getSimpleName()
                +" view="+Integer.toHexString(System.identityHashCode(v))+" before="+Integer.toHexString(before)
                +" color="+Integer.toHexString(v.getCurrentTextColor())+" "+extra+" at="+SystemClock.uptimeMillis());
    }
    private void restore(Ink i){
        TextView v=i.view.get();if(v==null)return;
        boolean ours=InkAnimator.running(v)||v.getCurrentTextColor()==i.applied;
        InkAnimator.cancel(v);
        if(ours)v.setTextColor(i.original);
    }
    void clear(){
        generation++;View host=root.get();
        if(host!=null){for(Runnable r:checks)host.removeCallbacks(r);host.removeOnAttachStateChangeListener(attach);}
        checks.clear();
        if(observer!=null&&observer.isAlive())observer.removeOnPreDrawListener(draw);
        observer=null;grid=null;for(Ink i:inks)restore(i);inks.clear();groups.clear();root.clear();logs=0;
    }
}
