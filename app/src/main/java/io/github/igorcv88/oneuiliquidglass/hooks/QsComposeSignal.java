package io.github.igorcv88.oneuiliquidglass.hooks;

import java.lang.reflect.Method;

/** A real snapshot dependency for Samsung's remembered/derived colours. */
final class QsComposeSignal {
    private final Object state;
    private final Method get, set;
    private Object key;
    private int revision;
    QsComposeSignal(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> states=Class.forName("androidx.compose.runtime.SnapshotStateKt",false,loader);
        state=states.getMethod("mutableStateOf$default",Object.class).invoke(null,Integer.valueOf(0));
        get=Class.forName("androidx.compose.runtime.State",false,loader).getMethod("getValue");
        set=Class.forName("androidx.compose.runtime.MutableState",false,loader).getMethod("setValue",Object.class);
    }
    void read() throws ReflectiveOperationException { get.invoke(state); }
    void update(Object next) throws ReflectiveOperationException {
        if (java.util.Objects.equals(key,next)) return;
        set.invoke(state,Integer.valueOf(++revision));
        key=next;
    }
}
