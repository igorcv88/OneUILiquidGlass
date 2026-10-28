package io.github.igorcv88.oneuiliquidglass.hooks;

import android.content.res.ColorStateList;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.util.*;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;
import io.github.igorcv88.oneuiliquidglass.glass.QuickSettingsGlass;
import io.github.igorcv88.oneuiliquidglass.glass.ToneGrid;
import io.github.igorcv88.oneuiliquidglass.glass.ShadeTone;

/** Fixed header targets, checked before draw. No row walk or global TextView interception. */
final class HeaderToneController {
    private final java.util.function.BiFunction<View,String,View> lookup;
    HeaderToneController() { this((host,name)->{
        int id=host.getResources().getIdentifier(name,"id",host.getContext().getPackageName());
        return id==0?null:host.findViewById(id);
    }); }
    HeaderToneController(java.util.function.BiFunction<View,String,View> lookup) { this.lookup=lookup; }
    private static final String[] IDS={"header_clock","header_date","shade_carrier_text","noti_setting","clear_all"};
    private static final class Ink {
        final WeakReference<TextView> view;
        final String id;
        ColorStateList original;
        int applied;
        Boolean localLight;
        Ink(TextView v,String id){view=new WeakReference<>(v);this.id=id;original=v.getTextColors();applied=v.getCurrentTextColor();}
    }
    private WeakReference<View> root=new WeakReference<>(null);
    private final List<Ink> inks=new ArrayList<>();
    private final List<Runnable> checks=new ArrayList<>();
    private ViewTreeObserver observer;
    private boolean light;
    private ToneGrid grid;
    private final int[] location=new int[2];
    void update(View host,ToneGrid sample){
        if(sample==null)return;
        if(grid==null || grid.generation!=sample.generation)for(Ink i:inks)i.localLight=null;
        update(host,false,sample);
    }
    private int generation, repairs;
    private final ViewTreeObserver.OnPreDrawListener draw=()->{repair();return true;};
    private final View.OnAttachStateChangeListener attach=new View.OnAttachStateChangeListener(){
        @Override public void onViewAttachedToWindow(View v){}
        @Override public void onViewDetachedFromWindow(View v){clear();}
    };

    void update(View host,boolean lightBackdrop){ update(host,lightBackdrop,null); }
    private void update(View host,boolean lightBackdrop,ToneGrid sample){
        if(root.get()!=host){
            clear();root=new WeakReference<>(host);host.addOnAttachStateChangeListener(attach);
            observer=host.getViewTreeObserver();observer.addOnPreDrawListener(draw);
            acquire();
            for(int delay:new int[]{0,100,350,700,1500}){
                int ticket=generation;
                Runnable check=()->{
                    if(ticket!=generation)return;
                    acquire();repair();
                    if(Probe.trace)for(Ink i:inks){TextView v=i.view.get();if(v!=null)log("sample",i,v,delay);}
                };
                checks.add(check);host.postDelayed(check,delay);
            }
        }
        grid=sample;light=lightBackdrop;repair();
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
    private void repair(){
        boolean lost=false;
        for(Ink i:inks){TextView v=i.view.get();if(v==null||!v.isAttachedToWindow()){lost=true;break;}}
        if(lost)acquire();
        for(Ink i:inks){
            TextView v=i.view.get();if(v==null)continue;
            boolean local=light;
            if(grid!=null){
                if(v.getHeight()<=0)continue; // No stale/global fallback before layout.
                v.getLocationOnScreen(location);
                float luma=grid.luma(location[1],location[1]+v.getHeight());
                if(!Float.isFinite(luma)||luma<0||luma>1)continue;
                local=ShadeTone.lightBackdrop(luma,i.localLight);
                i.localLight=local;
            }
            int current=v.getCurrentTextColor();
            if(current!=i.applied)i.original=v.getTextColors();
            boolean action=i.id.equals("noti_setting")||i.id.equals("clear_all");
            int nativeColor=i.original.getColorForState(v.getDrawableState(),i.original.getDefaultColor());
            int desired=action&&!local?nativeColor:QuickSettingsGlass.ink(nativeColor,local);
            desired=(desired&0xffffff)|(nativeColor&0xff000000); // Keep disabled/secondary alpha.
            if(current!=desired){
                if(repairs++<5)log("repair",i,v,0);
                v.setTextColor(desired);
            }
            i.applied=desired;
        }
    }
    private void log(String phase,Ink ink,TextView v,int delay){
        Probe.log("HEADER_INK","phase="+phase+" id="+ink.id+" class="+v.getClass().getSimpleName()
                +" view="+Integer.toHexString(System.identityHashCode(v))+" color="+Integer.toHexString(v.getCurrentTextColor())
                +" delay="+delay+" at="+SystemClock.uptimeMillis());
    }
    private void restore(Ink i){TextView v=i.view.get();if(v!=null&&v.getCurrentTextColor()==i.applied)v.setTextColor(i.original);}
    void clear(){
        generation++;View host=root.get();
        if(host!=null){for(Runnable r:checks)host.removeCallbacks(r);host.removeOnAttachStateChangeListener(attach);}
        checks.clear();
        if(observer!=null&&observer.isAlive())observer.removeOnPreDrawListener(draw);
        observer=null;grid=null;for(Ink i:inks)restore(i);inks.clear();root.clear();repairs=0;
    }
}
