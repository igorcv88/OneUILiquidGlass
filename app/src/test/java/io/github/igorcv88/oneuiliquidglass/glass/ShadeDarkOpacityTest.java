package io.github.igorcv88.oneuiliquidglass.glass;

import org.junit.Test;
import static org.junit.Assert.*;

/** The independent dark-shade opacity must leave both existing paths identical at its default. */
public class ShadeDarkOpacityTest {
    @Test public void defaultPreservesBothDarkMaterials(){
        assertEquals(0x24ffffff,MaterialMath.shadeDarkOpacity(0x24ffffff,.14f));
        assertEquals(0x1cffffff,MaterialMath.shadeDarkOpacity(0x1cffffff,.14f));
    }

    @Test public void transparentAndBrighterChoicesAreIndependentOfHue(){
        assertEquals(0x001a2b3c,MaterialMath.shadeDarkOpacity(0x241a2b3c,0));
        assertEquals(0x481a2b3c,MaterialMath.shadeDarkOpacity(0x241a2b3c,.28f));
        assertEquals(0xf21a2b3c,MaterialMath.shadeDarkOpacity(0x241a2b3c,2f));
        assertEquals(0x241a2b3c,MaterialMath.shadeDarkOpacity(0x241a2b3c,Float.NaN));
    }
}
