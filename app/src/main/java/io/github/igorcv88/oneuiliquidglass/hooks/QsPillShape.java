package io.github.igorcv88.oneuiliquidglass.hooks;

import java.lang.reflect.*;
import io.github.igorcv88.oneuiliquidglass.glass.QuickSettingsGlass;

/** Shape's outline may be inset inside its cell; layout, clipping host and hitbox stay native. */
final class QsPillShape {
    static Object create(ClassLoader loader, float ratio) throws ReflectiveOperationException {
        Class<?> shape=Class.forName("androidx.compose.ui.graphics.Shape",false,loader);
        Class<?> round=Class.forName("androidx.compose.ui.geometry.RoundRect",false,loader);
        Constructor<?> rr=round.getDeclaredConstructor(float.class,float.class,float.class,float.class,
                long.class,long.class,long.class,long.class);
        rr.setAccessible(true);
        Constructor<?> outline=Class.forName("androidx.compose.ui.graphics.Outline$Rounded",false,loader).getDeclaredConstructor(round);
        return Proxy.newProxyInstance(loader,new Class<?>[]{shape},(proxy,m,args)->{
            if(m.getName().startsWith("createOutline-")) {
                long size=(Long)args[0];
                float w=Float.intBitsToFloat((int)(size>>>32)),h=Float.intBitsToFloat((int)size);
                float[] b=QuickSettingsGlass.envelope(w,h,ratio);
                float r=Math.min(b[2]-b[0],b[3]-b[1])/2;
                long radius=QuickSettingsGlass.color(Float.floatToRawIntBits(r))|(Float.floatToRawIntBits(r)&0xffffffffL);
                return outline.newInstance(rr.newInstance(b[0],b[1],b[2],b[3],radius,radius,radius,radius));
            }
            if(m.getName().equals("hashCode"))return System.identityHashCode(proxy);
            if(m.getName().equals("equals"))return proxy==args[0];
            if(m.getName().equals("toString"))return "QsPillShape";
            throw new UnsupportedOperationException(m.getName());
        });
    }
}
