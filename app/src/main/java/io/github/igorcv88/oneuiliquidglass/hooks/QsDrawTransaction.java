package io.github.igorcv88.oneuiliquidglass.hooks;

import android.graphics.Canvas;
import java.lang.reflect.Method;

/** Canvas restore is unconditional, including when the native context-size setter fails. */
final class QsDrawTransaction implements AutoCloseable {
    private final Canvas canvas;
    private final Object context;
    private final Method setSize;
    private final long size;
    private final int save;
    private boolean closed;
    QsDrawTransaction(Canvas canvas,Object context,Method setSize,long size) {
        this.canvas=canvas;this.context=context;this.setSize=setSize;this.size=size;save=canvas.save();
    }
    @Override public void close() throws ReflectiveOperationException {
        if(closed)return;closed=true;
        try{canvas.restoreToCount(save);}finally{setSize.invoke(context,size);}
    }
}
