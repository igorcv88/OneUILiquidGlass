package io.github.igorcv88.oneuiliquidglass.config;

import org.junit.Test;
import static org.junit.Assert.*;

public class RgbaTest {
    @Test public void opaqueHexMatchesLegacyRgb() {
        assertEquals(0xffd2e3f8, Rgba.argb("d2e3f8"));
        assertEquals(0xffd2e3f8, Rgba.argb("#D2E3F8"));
        assertEquals(0x00d2e3f8, Rgba.rgb("d2e3f8aa"));
        assertEquals("d2e3f8ff", Rgba.hex(Rgba.argb("d2e3f8")));
    }
    @Test public void alphaIsLastTwoDigitsInTheEditorAndFirstByteInRendering() {
        assertEquals(0x80d2e3f8, Rgba.argb("D2E3F880"));
        assertEquals(0x00ffffff, Rgba.argb("ffffff00"));
        assertEquals("abcdef12", Rgba.hex(0x12abcdef));
        assertEquals(0x20000000, Rgba.argb("00000020"));
    }
    @Test public void rejectsMalformedAndOverlongColours() {
        for(String invalid : new String[]{"fff", "#fffff", "fffffffff", "ffffffzz", ""}) {
            try { Rgba.argb(invalid); fail(invalid); }
            catch(IllegalArgumentException expected) { /* expected */ }
        }
    }
}
