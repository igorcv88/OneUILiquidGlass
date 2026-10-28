package io.github.igorcv88.oneuiliquidglass.hooks;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Resolve the complete node contract before installing any hooks for that family. Each member is
 * matched by name and by what the hooks need (an instance method, the node or the draw scope as
 * its argument or result), preferring the exactly typed method and accepting the erased bridge R8
 * may leave in its place (update(Modifier.Node), create() returning Modifier.Node) and the
 * narrowed draw scope it may specialise to (draw(LayoutNodeDrawScope)). A miss names
 * the member and lists the candidates the firmware actually has.
 */
final class QsNodeAbi {
    final Method create,update,draw;
    private QsNodeAbi(Method create,Method update,Method draw){this.create=create;this.update=update;this.draw=draw;}
    static QsNodeAbi resolve(Class<?> element,Class<?> node,Class<?> scope) throws NoSuchMethodException {
        Method create=null,update=null,draw=null;
        for(Method m:element.getDeclaredMethods()){
            if(Modifier.isStatic(m.getModifiers()))continue;
            if(m.getName().equals("create")&&m.getParameterCount()==0&&specific(m.getReturnType(),node)
                    &&(create==null||m.getReturnType()==node&&!m.isBridge()))create=m;
            if(m.getName().equals("update")&&m.getParameterCount()==1&&m.getReturnType()==void.class
                    &&specific(m.getParameterTypes()[0],node)
                    &&(update==null||m.getParameterTypes()[0]==node&&!m.isBridge()))update=m;
        }
        for(Method m:node.getDeclaredMethods()){
            if(Modifier.isStatic(m.getModifiers()))continue;
            if(m.getName().equals("draw")&&m.getParameterCount()==1&&m.getReturnType()==void.class
                    &&drawScope(m.getParameterTypes()[0],scope)
                    &&(draw==null||rank(m,scope)>rank(draw,scope)))draw=m;
        }
        if(create==null)throw new NoSuchMethodException(element.getName()+".create candidates="+candidates(element,"create"));
        if(update==null)throw new NoSuchMethodException(element.getName()+".update candidates="+candidates(element,"update"));
        if(draw==null)throw new NoSuchMethodException(node.getName()+".draw candidates="+candidates(node,"draw"));
        return new QsNodeAbi(create,update,draw);
    }
    /**
     * The draw parameter is the scope, a supertype of it (an erased bridge), or a concrete class
     * implementing it: DZIF's R8 narrowed {@code draw(ContentDrawScope)} to
     * {@code draw(LayoutNodeDrawScope)}, its only implementation (device log 28/10 18:49,
     * candidates=[void(androidx.compose.ui.node.LayoutNodeDrawScope)]). Object is never accepted.
     */
    static boolean drawScope(Class<?> type,Class<?> scope){
        return type!=Object.class&&(type.isAssignableFrom(scope)||scope.isAssignableFrom(type));
    }
    /** Exact scope first, then a narrowed concrete scope, then a supertype; a bridge loses a tie. */
    private static int rank(Method m,Class<?> scope){
        Class<?> t=m.getParameterTypes()[0];
        int r=t==scope?3:scope.isAssignableFrom(t)?2:1;
        return r*2+(m.isBridge()?0:1);
    }
    /** {@code type} is the node itself or a supertype more specific than Object (Modifier.Node). */
    static boolean specific(Class<?> type,Class<?> node){
        return type!=Object.class&&type.isAssignableFrom(node);
    }
    /** The firmware's methods of that name, with their parameter and return types. */
    static String candidates(Class<?> c,String name){
        StringBuilder b=new StringBuilder("[");
        for(Method m:c.getDeclaredMethods()){
            if(!m.getName().equals(name))continue;
            if(b.length()>1)b.append("; ");
            b.append(m.getReturnType().getSimpleName()).append('(');
            Class<?>[] t=m.getParameterTypes();
            for(int i=0;i<t.length;i++)b.append(i==0?"":",").append(t[i].getName());
            b.append(')');
            if(m.isBridge())b.append(" bridge");
        }
        return b.append(']').toString();
    }
}
