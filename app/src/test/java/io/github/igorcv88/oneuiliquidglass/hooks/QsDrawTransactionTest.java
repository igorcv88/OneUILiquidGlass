package io.github.igorcv88.oneuiliquidglass.hooks;

import android.graphics.Canvas;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=34)
public class QsDrawTransactionTest {
    public static class Context {
        long size=10;boolean fail;
        public void size(long size){if(fail)throw new IllegalStateException();this.size=size;}
    }
    @Test public void drawFailureRestoresCanvasAndContextExactlyOnce() throws Exception {
        Canvas canvas=new Canvas();int count=canvas.getSaveCount();Context context=new Context();
        QsDrawTransaction t=new QsDrawTransaction(canvas,context,Context.class.getMethod("size",long.class),10);
        try{try(t){canvas.save();context.size=20;throw new IllegalArgumentException();}}
        catch(IllegalArgumentException expected){}
        assertEquals(count,canvas.getSaveCount());assertEquals(10,context.size);
        context.size=30;t.close();assertEquals(30,context.size);
    }
    @Test public void sizeRestoreFailureStillRestoresCanvas() throws Exception {
        Canvas canvas=new Canvas();int count=canvas.getSaveCount();Context context=new Context();
        QsDrawTransaction t=new QsDrawTransaction(canvas,context,Context.class.getMethod("size",long.class),10);
        canvas.save();context.fail=true;
        assertThrows(ReflectiveOperationException.class,t::close);
        assertEquals(count,canvas.getSaveCount());
    }
}
