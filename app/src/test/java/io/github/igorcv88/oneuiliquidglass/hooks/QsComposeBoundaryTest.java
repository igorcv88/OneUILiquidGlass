package io.github.igorcv88.oneuiliquidglass.hooks;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

/** Regression boundary, not an emulation of Samsung's Compose runtime. */
public class QsComposeBoundaryTest {
    @Test public void noMaterial3TextOrSyntheticArgumentWrites() throws Exception {
        Path base=Paths.get("src/main/java/io/github/igorcv88/oneuiliquidglass/hooks");
        String content=new String(Files.readAllBytes(base.resolve("QsContentHooks.java")), StandardCharsets.UTF_8);
        assertFalse(content.contains("androidx.compose.material3.TextKt"));
        assertFalse(content.contains("androidx.compose.material3.IconKt"));
        assertFalse(content.contains("p.args[last]="));
        assertFalse(content.contains("p.args[composer+1]="));
        String render=new String(Files.readAllBytes(base.resolve("QsTextHooks.java")), StandardCharsets.UTF_8);
        assertFalse(render.contains("p.args[2]="));
        assertFalse(render.contains("TextStyle.class"));
    }
    /** DZIF's DrawScope $default bridges, with Compose's parameter types (names are irrelevant here). */
    public interface Scope {}
    public static void rectColor(Scope s,long color,long topLeft,long size,float alpha,
            androidx.compose.ui.graphics.drawscope.DrawStyle style,androidx.compose.ui.graphics.ColorFilter filter,int blend,int mask){}
    public static void rectBrush(Scope s,androidx.compose.ui.graphics.Brush brush,long topLeft,long size,float alpha,
            androidx.compose.ui.graphics.drawscope.DrawStyle style,androidx.compose.ui.graphics.ColorFilter filter,int blend,int mask){}
    public static void roundRectBrush(Scope s,androidx.compose.ui.graphics.Brush brush,long topLeft,long size,long radius,float alpha,
            androidx.compose.ui.graphics.drawscope.DrawStyle style,androidx.compose.ui.graphics.ColorFilter filter,int blend,int mask){}
    public static void roundRectColor(Scope s,long color,long topLeft,long size,long radius,
            androidx.compose.ui.graphics.drawscope.DrawStyle style,float alpha,androidx.compose.ui.graphics.ColorFilter filter,int blend,int mask){}
    /** S938BXXUCDZIF, device log 28/10: R8 dropped the ColorFilter of the thumb's bridge (9 parameters). */
    public static void roundRectColorDzif(Scope s,long color,long topLeft,long size,long radius,
            androidx.compose.ui.graphics.drawscope.DrawStyle style,float alpha,int blend,int mask){}
    public static void roundRectNoRadius(Scope s,long color,long topLeft,long size,float alpha,int blend,int mask){}
    public static void roundRectExtraLong(Scope s,long color,long topLeft,long size,long radius,long extra,int mask){}
    private static java.lang.reflect.Method named(String n){
        for(java.lang.reflect.Method m:QsComposeBoundaryTest.class.getDeclaredMethods())if(m.getName().equals(n))return m;
        throw new AssertionError(n);
    }
    /**
     * drawRect-n-J9OG0 is drawRect(color: Color, ...): its colour is a long. Expecting a Brush there
     * (#78) rejected the track bridge, failed the whole install and left the slider native.
     */
    @Test public void sliderPrimitivesMatchTheirComposeSignatures() {
        assertTrue(QsSliderHooks.primitiveSignature(named("rectColor"),0,Scope.class));
        assertFalse(QsSliderHooks.primitiveSignature(named("rectBrush"),0,Scope.class));
        assertTrue(QsSliderHooks.primitiveSignature(named("roundRectBrush"),1,Scope.class));
        assertTrue(QsSliderHooks.primitiveSignature(named("roundRectColor"),2,Scope.class));
        assertTrue("DZIF thumb bridge",QsSliderHooks.primitiveSignature(named("roundRectColorDzif"),2,Scope.class));
        assertFalse(QsSliderHooks.primitiveSignature(named("roundRectNoRadius"),2,Scope.class));
        assertFalse(QsSliderHooks.primitiveSignature(named("roundRectExtraLong"),2,Scope.class));
        assertFalse(QsSliderHooks.primitiveSignature(named("roundRectColor"),1,Scope.class));
        assertFalse(QsSliderHooks.primitiveSignature(named("roundRectBrush"),2,Scope.class));
        assertFalse(QsSliderHooks.primitiveSignature(named("roundRectBrush"),1,Object.class));
    }
    @Test public void dragNeedsAFingerAndAMovingValue() {
        assertTrue(QsSliderHooks.drag(100f,140f,true));
        assertFalse(QsSliderHooks.drag(100f,140f,false));
        assertFalse(QsSliderHooks.drag(null,140f,true));
        assertFalse(QsSliderHooks.drag(140f,140.2f,true));
    }
    @Test public void tileIconsAndDiscsAreNotRecoloured() throws Exception {
        Path base=Paths.get("src/main/java/io/github/igorcv88/oneuiliquidglass/hooks");
        String content=new String(Files.readAllBytes(base.resolve("QsContentHooks.java")), StandardCharsets.UTF_8);
        assertFalse(content.contains("QSIconKt"));
    }
}
