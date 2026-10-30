package io.github.igorcv88.oneuiliquidglass.hooks;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

/** Weak keys compared by object identity, including when a Compose model overrides equals(). */
final class WeakIdentityMap<K,V> {
    private final ReferenceQueue<K> collected=new ReferenceQueue<>();
    private final Map<Key<K>,V> entries=new HashMap<>();

    private static final class Key<T> extends WeakReference<T> {
        private final int identity;
        Key(T object,ReferenceQueue<T> queue) {
            super(object,queue);
            identity=System.identityHashCode(object);
        }
        @Override public int hashCode() {return identity;}
        @Override public boolean equals(Object other) {
            if(this==other)return true;
            if(!(other instanceof Key))return false;
            Object value=get();
            return value!=null&&value==((Key<?>)other).get();
        }
    }

    @SuppressWarnings("unchecked")
    private void prune() {
        Key<K> key;
        while((key=(Key<K>)collected.poll())!=null)entries.remove(key);
    }
    synchronized V get(K key) {
        if(key==null)return null;
        prune();return entries.get(new Key<>(key,null));
    }
    synchronized V put(K key,V value) {
        if(key==null)throw new NullPointerException("identity key");
        prune();return entries.put(new Key<>(key,collected),value);
    }
    synchronized V remove(K key) {
        if(key==null)return null;
        prune();return entries.remove(new Key<>(key,null));
    }
    synchronized boolean containsKey(K key) {
        if(key==null)return false;
        prune();return entries.containsKey(new Key<>(key,null));
    }
    synchronized int size() {prune();return entries.size();}
    synchronized void clear() {entries.clear();while(collected.poll()!=null){} }
}
