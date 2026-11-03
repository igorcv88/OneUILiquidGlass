package io.github.igorcv88.oneuiliquidglass.glass;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Sends a Samsung colour curve (s, c, x0, x1, y0, y1) to a blur drawable through whatever setter
 * the firmware's class actually has. DZIF's BackgroundBlurDrawable has no setColorCurve(6 floats):
 * it exposes {@code setBlurColorCurve/1} (and {@code setBGColorAdjustment/1}), BLUR_DRAWABLE_API
 * log 03/11/2026. Accepted shapes, resolved once per drawable class:
 *
 * <ol>
 *   <li>{@code setColorCurve(float x6)};</li>
 *   <li>{@code setBlurColorCurve(float[])};</li>
 *   <li>{@code setBlurColorCurve(T)} where T has a public (float x6) constructor;</li>
 *   <li>{@code setBlurColorCurve(T)} where T is what {@code SemBlurInfo.getColorCurve()} returns,
 *       built by Samsung's own {@code SemBlurInfo.Builder.setColorCurve(float x6)}.</li>
 * </ol>
 *
 * Anything else is "unavailable": nothing is sent and nothing claims a curve was applied.
 */
public final class BlurCurve {
    private BlurCurve() {}

    /** One resolved way of sending a curve; {@link #send} returns whether a setter really ran. */
    public interface Sender {
        boolean send(Object drawable, float[] curve) throws ReflectiveOperationException;
        String describe();
    }
    static final Sender NONE = new Sender() {
        @Override public boolean send(Object drawable, float[] curve) { return false; }
        @Override public String describe() { return "none"; }
    };

    private static final Map<Class<?>, Sender> SENDERS = new WeakHashMap<>();

    public static Sender forClass(Class<?> drawableClass) {
        synchronized (SENDERS) {
            Sender s = SENDERS.get(drawableClass);
            if (s == null) { s = resolve(drawableClass); SENDERS.put(drawableClass, s); }
            return s;
        }
    }

    static Sender resolve(Class<?> c) {
        try {
            Method six = c.getMethod("setColorCurve", float.class, float.class, float.class, float.class, float.class, float.class);
            return new Sender() {
                @Override public boolean send(Object d, float[] v) throws ReflectiveOperationException { six.invoke(d, v[0], v[1], v[2], v[3], v[4], v[5]); return true; }
                @Override public String describe() { return "setColorCurve(float x6)"; }
            };
        } catch (NoSuchMethodException | LinkageError | RuntimeException ignored) { }
        Method one = null;
        try {
            for (Method m : c.getMethods()) if (m.getName().equals("setBlurColorCurve") && m.getParameterCount() == 1) { one = m; break; }
        } catch (LinkageError | RuntimeException ignored) { }
        if (one == null) return NONE;
        Class<?> p = one.getParameterTypes()[0];
        final Method setter = one;
        if (p == float[].class) {
            return new Sender() {
                @Override public boolean send(Object d, float[] v) throws ReflectiveOperationException { setter.invoke(d, (Object) v.clone()); return true; }
                @Override public String describe() { return "setBlurColorCurve(float[])"; }
            };
        }
        try {
            Constructor<?> k = p.getConstructor(float.class, float.class, float.class, float.class, float.class, float.class);
            return new Sender() {
                @Override public boolean send(Object d, float[] v) throws ReflectiveOperationException {
                    setter.invoke(d, k.newInstance(v[0], v[1], v[2], v[3], v[4], v[5]));
                    return true;
                }
                @Override public String describe() { return "setBlurColorCurve(" + p.getName() + "(float x6))"; }
            };
        } catch (NoSuchMethodException | LinkageError | RuntimeException ignored) { }
        Method get = semGetter(p);
        if (get != null) {
            return new Sender() {
                @Override public boolean send(Object d, float[] v) throws ReflectiveOperationException {
                    Object curve = semCurve(get, v);
                    if (curve == null || !p.isInstance(curve)) return false;
                    setter.invoke(d, curve);
                    return true;
                }
                @Override public String describe() { return "setBlurColorCurve(" + p.getName() + " via SemBlurInfo)"; }
            };
        }
        return new Sender() {
            @Override public boolean send(Object d, float[] v) { return false; }
            @Override public String describe() { return "setBlurColorCurve(" + p.getName() + ") unsupported"; }
        };
    }

    /** SemBlurInfo.getColorCurve() when it returns something the setter accepts; null otherwise. */
    private static Method semGetter(Class<?> param) {
        try {
            Class<?> info = Class.forName(SemBlurBridge.INFO);
            Method get = info.getMethod("getColorCurve");
            return param.isAssignableFrom(get.getReturnType()) ? get : null;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) { return null; }
    }
    /** The curve object Samsung builds for these values: Builder(window mode).setColorCurve(...).build().getColorCurve(). */
    private static Object semCurve(Method get, float[] v) throws ReflectiveOperationException {
        Class<?> info = Class.forName(SemBlurBridge.INFO);
        Class<?> builderClass = Class.forName(SemBlurBridge.INFO + "$Builder");
        int mode = info.getField("BLUR_MODE_WINDOW").getInt(null);
        Object b;
        try { b = builderClass.getConstructor(int.class).newInstance(mode); }
        catch (NoSuchMethodException e) {
            b = builderClass.getConstructor().newInstance();
            builderClass.getMethod("setBlurMode", int.class).invoke(b, mode);
        }
        builderClass.getMethod("setColorCurve", float.class, float.class, float.class, float.class, float.class, float.class)
                .invoke(b, v[0], v[1], v[2], v[3], v[4], v[5]);
        return get.invoke(builderClass.getMethod("build").invoke(b));
    }
}
