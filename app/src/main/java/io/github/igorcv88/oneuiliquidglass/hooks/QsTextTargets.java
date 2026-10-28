package io.github.igorcv88.oneuiliquidglass.hooks;

import java.lang.ref.WeakReference;
import java.util.*;
import java.util.function.Consumer;

/** Bounded weak identity registry. Compose elements implement structural equals: never key by it. */
final class QsTextTargets {
    private static final int LIMIT=256;
    private static final class Target {
        final WeakReference<Object> object;
        boolean header;
        Target(Object o,boolean h){object=new WeakReference<>(o);header=h;}
    }
    private final List<Target> targets=new ArrayList<>();
    synchronized Boolean get(Object object){
        for(Target t:targets)if(t.object.get()==object)return t.header;
        return null;
    }
    synchronized void put(Object object,Boolean header){
        targets.removeIf(t->t.object.get()==null||t.object.get()==object);
        if(header==null)return;
        if(targets.size()>=LIMIT)targets.remove(0);
        targets.add(new Target(object,header));
    }
    synchronized void clear(){targets.clear();}
    synchronized void each(Consumer<Object> action){
        for(Target t:targets){Object object=t.object.get();if(object!=null)action.accept(object);}
    }
}
