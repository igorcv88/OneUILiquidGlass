package io.github.igorcv88.oneuiliquidglass.hooks;
import org.junit.Test;
import static org.junit.Assert.*;
public class QsTextTargetsTest {
    @Test public void structurallyEqualUnrelatedComposeElementsNeverShareOwnership(){
        QsTextTargets targets=new QsTextTargets();Object qs=new String("same"),other=new String("same");
        assertEquals(qs,other);targets.put(qs,true);
        assertEquals(Boolean.TRUE,targets.get(qs));assertNull(targets.get(other));
        targets.put(qs,false);assertEquals(Boolean.FALSE,targets.get(qs));
        targets.put(qs,null);assertNull(targets.get(qs));
    }
}
