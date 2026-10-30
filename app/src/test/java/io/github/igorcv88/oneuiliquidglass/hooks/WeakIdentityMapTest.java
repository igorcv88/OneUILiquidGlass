package io.github.igorcv88.oneuiliquidglass.hooks;

import org.junit.Test;
import static org.junit.Assert.*;

public class WeakIdentityMapTest {
    private static final class SameValue {
        @Override public boolean equals(Object other) {return other instanceof SameValue;}
        @Override public int hashCode() {return 42;}
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
