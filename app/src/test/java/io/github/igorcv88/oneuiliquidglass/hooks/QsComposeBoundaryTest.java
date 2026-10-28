package io.github.igorcv88.oneuiliquidglass.hooks;

import java.nio.file.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Regression boundary, not an emulation of Samsung's Compose runtime. */
public class QsComposeBoundaryTest {
    @Test public void noMaterial3TextOrSyntheticArgumentWrites() throws Exception {
        Path base=Paths.get("src/main/java/io/github/igorcv88/oneuiliquidglass/hooks");
        String content=Files.readString(base.resolve("QsContentHooks.java"));
        assertFalse(content.contains("androidx.compose.material3.TextKt"));
        assertFalse(content.contains("androidx.compose.material3.IconKt"));
        assertFalse(content.contains("p.args[last]="));
        assertFalse(content.contains("p.args[composer+1]="));
        String render=Files.readString(base.resolve("QsTextHooks.java"));
        assertFalse(render.contains("p.args[2]="));
        assertFalse(render.contains("TextStyle.class"));
    }
    public static void unrelatedText(long color,Object textStyle,int changed,int defaults) {}
    @Test public void unknownComposableSignatureIsRejected() throws Exception {
        assertFalse(QsInkSignature.accepts(getClass().getMethod("unrelatedText",long.class,Object.class,int.class,int.class)));
    }
}
