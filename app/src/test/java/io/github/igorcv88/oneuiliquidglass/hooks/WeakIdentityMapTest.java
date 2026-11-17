package io.github.igorcv88.oneuiliquidglass.hooks;

import org.junit.Test;
import static org.junit.Assert.*;

public class WeakIdentityMapTest {
    private static final class SameValue {
        @Override public boolean equals(Object other) {return other instanceof SameValue;}
        @Override public int hashCode() {return 42;}
    }

    @Test public void snapshotsDoNotExposeMutableRegistryIterator() throws Exception {
        WeakIdentityMap<Object,String> registry = new WeakIdentityMap<>();
        Object first = new Object(), second = new Object();
        registry.put(first,"first");
        java.util.List<String> snapshot=registry.valuesSnapshot();
        registry.put(second,"second");
        registry.remove(first);
        assertEquals(java.util.Collections.singletonList("first"),snapshot);
        assertEquals(java.util.Collections.singletonList("second"),registry.valuesSnapshot());
        java.lang.ref.Reference.reachabilityFence(first);
        java.lang.ref.Reference.reachabilityFence(second);
    }

    @Test public void concurrentWritesAndSnapshotsNeverInvalidateAnIterator() throws Exception {
        WeakIdentityMap<Object,Integer> registry = new WeakIdentityMap<>();
        Object[] keys=new Object[64];
        for(int i=0;i<keys.length;i++)keys[i]=new Object();
        java.util.concurrent.ExecutorService workers=java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch start=new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Future<?> writer=workers.submit(()->{
                try {start.await();}catch(InterruptedException e){throw new AssertionError(e);}
                for(int i=0;i<5000;i++){
                    Object key=keys[i%keys.length];
                    registry.put(key,i);
                    if((i&1)==0)registry.remove(key);
                }
            });
            java.util.concurrent.Future<?> reader=workers.submit(()->{
                try {start.await();}catch(InterruptedException e){throw new AssertionError(e);}
                for(int i=0;i<5000;i++){
                    for(Integer value:registry.valuesSnapshot())assertNotNull(value);
                }
            });
            start.countDown();
            writer.get(10,java.util.concurrent.TimeUnit.SECONDS);
            reader.get(10,java.util.concurrent.TimeUnit.SECONDS);
        } finally {workers.shutdownNow();}
    }

    @Test public void equalModelsStillHaveSeparateOwners() {
        WeakIdentityMap<SameValue,String> values=new WeakIdentityMap<>();
        SameValue first=new SameValue(),second=new SameValue();
        assertEquals(first,second);
        values.put(first,"first");values.put(second,"second");
        assertEquals(2,values.size());
        assertEquals("first",values.get(first));
        assertEquals("second",values.get(second));
        assertNull(values.get(new SameValue()));
        values.remove(first);
        assertEquals("second",values.get(second));
        assertEquals(1,values.size());
    }
}
