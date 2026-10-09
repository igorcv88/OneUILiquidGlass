package io.github.igorcv88.oneuiliquidglass.hooks;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ReflectTest {
    static class Base { private boolean mFlag = true; private boolean isOn() { return false; } }
    static final class Leaf extends Base { private int mCount = 7; }

    @Test public void readsFieldsUpTheHierarchy() {
        Leaf leaf = new Leaf();
        assertEquals(7, Reflect.read(leaf, "mCount"));
        assertEquals(Boolean.TRUE, Reflect.read(leaf, "mFlag"));
    }
    @Test public void missesAreCachedAndStayNull() {
        assertNull(Reflect.field(Leaf.class, "mAbsent"));
        assertNull(Reflect.field(Leaf.class, "mAbsent"));
        assertNull(Reflect.method(Leaf.class, "absent"));
        assertNull(Reflect.read(new Leaf(), "mAbsent"));
    }
    @Test public void lookupsAreReused() {
        assertSame(Reflect.field(Leaf.class, "mFlag"), Reflect.field(Leaf.class, "mFlag"));
        assertSame(Reflect.method(Leaf.class, "isOn"), Reflect.method(Leaf.class, "isOn"));
    }
    @Test public void boolPrefersTheMethodThenTheField() {
        Leaf leaf = new Leaf();
        assertEquals(Boolean.FALSE, Reflect.bool(leaf, "isOn", "mFlag"));
        assertEquals(Boolean.TRUE, Reflect.bool(leaf, "absent", "mFlag"));
        assertNull(Reflect.bool(leaf, "absent", "mAbsent"));
        assertNull(Reflect.bool(null, "isOn", "mFlag"));
    }
    @Test(expected = NoSuchMethodException.class)
    public void callReportsAMissingMethod() throws ReflectiveOperationException { Reflect.call(new Leaf(), "absent"); }
}
