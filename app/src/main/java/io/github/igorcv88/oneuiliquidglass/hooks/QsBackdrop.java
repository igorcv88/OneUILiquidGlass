package io.github.igorcv88.oneuiliquidglass.hooks;

import android.os.SystemClock;
import android.view.View;
import android.view.ViewTreeObserver;
import java.lang.ref.WeakReference;
import java.util.function.BooleanSupplier;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;
import io.github.igorcv88.oneuiliquidglass.glass.*;

/** One cancellable, low-resolution capture below the QS window for each opening. */
final class QsBackdrop {
    private final BooleanSupplier enabled;
    private final Runnable changed;
    private final QsScene scene=new QsScene();
    private final QsScene header=new QsScene();
    private volatile QsScene.Tone tone=QsScene.Tone.UNKNOWN;
    private volatile QsScene.Tone headerTone=QsScene.Tone.UNKNOWN;
    private WeakReference<View> host=new WeakReference<>(null);
    private ViewTreeObserver observer;
    private final ViewTreeObserver.OnPreDrawListener draw=()->{ poll(); return true; };
    private final View.OnAttachStateChangeListener attach=new View.OnAttachStateChangeListener(){
        @Override public void onViewAttachedToWindow(View v){ watch(v); }
        @Override public void onViewDetachedFromWindow(View v){ stop(); }
    };
    private boolean shown, pending;
    private long sampledAt;
    private int generation;
    private Runnable cancel;
    private android.content.Context application;
    private final android.content.ComponentCallbacks configuration=new android.content.ComponentCallbacks(){
        @Override public void onConfigurationChanged(android.content.res.Configuration c){
            if(!enabled.getAsBoolean())return;
            generation++;pending=false;if(cancel!=null)cancel.run();cancel=null;
            scene.reset();header.reset();sampledAt=0;publish();prefetch();
        }
        @Override public void onLowMemory(){}
    };
    QsBackdrop(BooleanSupplier enabled,Runnable changed){this.enabled=enabled;this.changed=changed;}
    QsScene.Tone tone(){return tone;}
    QsScene.Tone headerTone(){return headerTone;}
    void panel(View v){
        View previous=host.get();
        if(previous==v)return;
        stop(); if(previous!=null) previous.removeOnAttachStateChangeListener(attach);
        host=new WeakReference<>(v);
        v.addOnAttachStateChangeListener(attach);watch(v);
    }
    private void watch(View v){
        if(application==null){application=v.getContext().getApplicationContext();
            if(application!=null)application.registerComponentCallbacks(configuration);}
        if(observer!=null&&observer.isAlive())observer.removeOnPreDrawListener(draw);
        observer=v.getViewTreeObserver();observer.addOnPreDrawListener(draw);
    }
    private void poll(){
        if(!enabled.getAsBoolean())return; // OFF: no ancestor walk, config lookup, reflection or capture
        View v=host.get();boolean now=v!=null&&v.isShown();
        if(now&&!shown){
            if(SystemClock.uptimeMillis()-sampledAt>600){scene.reset();header.reset();publish();}
            prefetch();
        }
        if(!now&&shown){generation++;pending=false;if(cancel!=null)cancel.run();cancel=null;}
        shown=now;
    }
    /** May be called by the top-edge DOWN event, before Samsung makes the panel visible. */
    void prefetch(){
        View v=host.get();
        if(v==null||!v.isAttachedToWindow()||!enabled.getAsBoolean()||pending)return;
        pending=true;int ticket=++generation;
        cancel=ShadeTone.prefetch(v,ticket,grid->{
            if(ticket!=generation)return;
            pending=false;cancel=null;
            if(!enabled.getAsBoolean())return;
            if(grid==null){scene.reset();header.reset();sampledAt=0;}
            else{
                scene.accept(grid.whole(),SystemClock.uptimeMillis());
                header.accept(grid.luma(grid.top,grid.top+4*grid.band),SystemClock.uptimeMillis());
                sampledAt=grid.takenAt;
            }
            publish();
        });
    }
    void configuration(){
        if(!enabled.getAsBoolean()){generation++;pending=false;if(cancel!=null)cancel.run();cancel=null;shown=false;scene.reset();header.reset();publish();}
        else {shown=false;poll();}
    }
    private void publish(){
        QsScene.Tone next=scene.tone(), nextHeader=header.tone();if(next==tone&&nextHeader==headerTone)return;
        tone=next;headerTone=nextHeader;
        Probe.log("QS_BACKDROP","state="+next+" header="+nextHeader+" generation="+generation);changed.run();
    }
    private void stop(){
        if(application!=null)application.unregisterComponentCallbacks(configuration);application=null;
        generation++;pending=false;shown=false;
        if(cancel!=null)cancel.run();cancel=null;
        if(observer!=null&&observer.isAlive())observer.removeOnPreDrawListener(draw);
        observer=null;scene.reset();header.reset();sampledAt=0;publish();
    }
}
