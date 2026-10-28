package io.github.igorcv88.oneuiliquidglass.hooks;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Resolve the complete node contract before installing any hooks for that family. */
final class QsNodeAbi {
    final Method create,update,draw;
    private QsNodeAbi(Method create,Method update,Method draw){this.create=create;this.update=update;this.draw=draw;}
    static QsNodeAbi resolve(Class<?> element,Class<?> node,Class<?> scope) throws NoSuchMethodException {
        Method create=null;
        for(Method m:element.getDeclaredMethods())if(m.getName().equals("create")&&m.getParameterCount()==0
                &&m.getReturnType()==node&&!m.isBridge()&&!Modifier.isStatic(m.getModifiers()))create=m;
        if(create==null)throw new NoSuchMethodException(element.getName()+".create");
        Method update=element.getDeclaredMethod("update",node),draw=node.getDeclaredMethod("draw",scope);
        if(update.getReturnType()!=void.class||draw.getReturnType()!=void.class
                ||Modifier.isStatic(update.getModifiers())||Modifier.isStatic(draw.getModifiers()))
            throw new NoSuchMethodException(node.getName()+" node ABI");
        return new QsNodeAbi(create,update,draw);
    }
}
