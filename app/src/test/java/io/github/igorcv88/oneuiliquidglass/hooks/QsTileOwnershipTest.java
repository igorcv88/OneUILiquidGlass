package io.github.igorcv88.oneuiliquidglass.hooks;

import static org.junit.Assert.*;
import io.github.igorcv88.oneuiliquidglass.glass.QuickSettingsGlass.Kind;
import org.junit.Test;

public class QsTileOwnershipTest {
    private static final class Model {
        int state;
        Model(int state) { this.state=state; }
        @Override public int hashCode() { return state; }
        @Override public boolean equals(Object value) { return value instanceof Model && ((Model)value).state==state; }
    }
    @Test public void togglingMutableModelDoesNotLoseItsDiscAndEqualModelsRemainSeparate() {
        QsTileProbe probe = new QsTileProbe();
        Model wifi = new Model(1), bluetooth = new Model(1);
        probe.disc(wifi, Kind.IDLE);
        probe.disc(bluetooth, Kind.ACTIVE);
        assertEquals(Kind.IDLE, probe.disc(wifi));
        assertEquals(Kind.ACTIVE, probe.disc(bluetooth));
        wifi.state=2;
        probe.disc(wifi, Kind.ACTIVE);
        assertEquals(Kind.ACTIVE, probe.disc(wifi));
        wifi.state=1;
        probe.disc(wifi, Kind.IDLE);
        assertEquals(Kind.IDLE, probe.disc(wifi));
        assertEquals(Kind.ACTIVE, probe.disc(bluetooth));
    }
    @Test public void retainedDiscUsesLatestModelStateRatherThanConstructionState() {
        QsTileProbe probe = new QsTileProbe();
        Model wifi = new Model(2);
        probe.disc(wifi, Kind.ACTIVE);
        QsDiscBackgroundHooks.Spec disc = new QsDiscBackgroundHooks.Spec(Kind.ACTIVE, 0xffffffff, wifi);
        assertEquals(Kind.ACTIVE, disc.liveKind(probe::disc));
        wifi.state=1;
        probe.disc(wifi, Kind.IDLE);
        assertEquals(Kind.IDLE, disc.liveKind(probe::disc));
        wifi.state=2;
        probe.disc(wifi, Kind.ACTIVE);
        assertEquals(Kind.ACTIVE, disc.liveKind(probe::disc));
        probe.disc(wifi, Kind.NONE);
        assertEquals(Kind.NONE, disc.liveKind(probe::disc));
    }
    @Test public void paletteInvalidationIncludesEachEqualPainterIdentity() {
        WeakIdentityMap<Object,String> nodes = new WeakIdentityMap<>();
        Model first = new Model(1), second = new Model(1);
        nodes.put(first,"wifi"); nodes.put(second,"bluetooth");
        assertEquals(2, nodes.keys().size());
        assertEquals("wifi", nodes.get(first));
        assertEquals("bluetooth", nodes.get(second));
    }
}
