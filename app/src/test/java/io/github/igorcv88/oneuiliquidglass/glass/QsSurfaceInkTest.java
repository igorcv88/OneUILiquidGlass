package io.github.igorcv88.oneuiliquidglass.glass;
import org.junit.Test;
import static org.junit.Assert.*;
public class QsSurfaceInkTest {
    @Test public void activeDiscAndDisclessGlyphChooseDifferentInkOnWhite(){
        assertEquals(0xffffffff,QsSurfaceInk.onSurface(0xff000000,0xff000000,true));
        assertEquals(0xff1d1d1d,QsSurfaceInk.onSurface(0xffffffff,0,true));
        assertEquals(0xff1d1d1d,QsSurfaceInk.onSurface(0xffffffff,0xffffffff,false));
    }
    @Test public void zeroSliderFollowsGlassAndFilledSliderFollowsProgress(){
        assertEquals(0xff1d1d1d,QsSurfaceInk.onSurface(0xffffffff,0,true));
        assertEquals(0xffffffff,QsSurfaceInk.onSurface(0xff000000,0,false));
        assertEquals(0xff1d1d1d,QsSurfaceInk.onSurface(0xff000000,0xffffffff,false));
    }
    @Test public void alphaAndAccentWarningsArePreserved(){
        assertEquals(0x801d1d1d,QsSurfaceInk.onSurface(0x80ffffff,0,true));
        assertEquals(0xffff2200,QsSurfaceInk.onSurface(0xffff2200,0,true));
        assertEquals(0x00ffffff,QsSurfaceInk.onSurface(0x00ffffff,0,true));
        assertEquals(0xff1d1d1d,QsSurfaceInk.onSurface(0xffffffff,0x20000000,true));
    }
}
