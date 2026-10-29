package io.github.igorcv88.oneuiliquidglass.hooks;

import org.junit.Test;
import static org.junit.Assert.*;

public class QsTileTraceTest {
    @Test public void tileArgumentsLogTypesAndScalarValuesButNeverUserLabels() {
        String result=QsTileTrace.arguments(new Object[]{"Private wifi name",true,0xff332211L,4,1.5f});
        assertFalse(result.contains("Private wifi name"));
        assertTrue(result.contains("type=java.lang.String"));
        assertTrue(result.contains("b=true"));
        assertTrue(result.contains("l=ff332211"));
        assertTrue(result.contains("i=4"));
    }
    @Test public void probeDoesNotReadMoreThanTwentyTwoArguments() {
        Object[] args=new Object[30];
        args[29]="never read";
        String result=QsTileTrace.arguments(args);
        assertTrue(result.contains("21:null"));
        assertFalse(result.contains("29:"));
        assertFalse(result.contains("never read"));
        assertFalse(QsTileTrace.hasComposer(new Class<?>[]{Object.class,String.class}));
    }
    @Test public void modelIdentityCanBeCorrelatedWithoutItsToString() {
        String result=QsTileTrace.arguments(new Object[]{new TileModel()});
        assertTrue(result.contains("TileModel@"));
        assertFalse(result.contains("private tile state"));
    }
    private static final class TileModel {
        @Override public String toString() { return "private tile state"; }
    }
    @Test public void modelProbeReadsBooleanAndEnumStateWithoutLeakingPrivateFields() {
        StateModel model = new StateModel();
        String result = QsTileTrace.modelFields(model);
        assertTrue(result.contains("enabled:boolean=true"));
        assertTrue(result.contains("state:" + State.class.getName() + "=ON"));
        assertTrue(result.contains("ssid:java.lang.String"));
        assertTrue(result.contains("secretCount:int"));
        assertFalse(result.contains("Home WiFi"));
        assertFalse(result.contains("8192"));
    }
    private enum State { ON, OFF }
    private static final class StateModel {
        private final boolean enabled = true;
        private final State state = State.ON;
        private final String ssid = "Home WiFi";
        private final int secretCount = 8192;
    }
    @Test public void equalStateModelsEmitSeparateSnapshotsByIdentity() {
        StateModel first = new StateModel(), second = new StateModel();
        String fields = QsTileTrace.modelFields(first);
        assertEquals(fields, QsTileTrace.modelFields(second));
        String a = QsTileTrace.modelSnapshotKey(first, fields);
        String b = QsTileTrace.modelSnapshotKey(second, fields);
        assertNotEquals(a, b);
        assertEquals(a, QsTileTrace.modelSnapshotKey(first, fields));
        assertFalse(a.contains("Home WiFi"));
        assertFalse(a.contains("8192"));
        // Recomposition of the same model with a changed state must also be retained.
        assertNotEquals(a, QsTileTrace.modelSnapshotKey(first, fields.replace("=true", "=false")));
    }
}
