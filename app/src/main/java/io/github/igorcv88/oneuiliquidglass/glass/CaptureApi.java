package io.github.igorcv88.oneuiliquidglass.glass;

import android.graphics.ColorSpace;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.view.SurfaceControl;
import io.github.igorcv88.oneuiliquidglass.diagnostics.CaptureSurvey;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.ObjIntConsumer;

/**
 * Reflective binding to IWindowManager.captureDisplay. The argument, listener and result classes are
 * taken from the method's own signature: their outer class moved between releases
 * (android.window.ScreenCapture on Android 14-16, android.window.ScreenCaptureInternal on One UI 9).
 */
public final class CaptureApi {
    private static CaptureApi instance;
    private static boolean failed;

    final Class<?> builderClass, argsClass, listenerClass, shotClass;
    final Method setCrop, setScale, setSecure, setProtected, setExclude, build;
    final Constructor<?> listenerCtor;
    final Object windowManager;
    final Method captureDisplay;
    final Method getBuffer, getSecure, getHdr, getColorSpace;

    /** The binding, or null when this firmware exposes no usable capture path (logged once). */
    public static CaptureApi get() {
        if (instance == null && !failed) {
            try { instance = new CaptureApi(); Probe.log("CAPTURE_API", instance.describe()); }
            catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                failed = true;
                Probe.error("CAPTURE_API_FAILED", e);
                Probe.log("CAPTURE_API_DETAIL", "message=" + e.getMessage()
                        + " cause=" + (e.getCause() == null ? "null" : e.getCause().getClass().getSimpleName() + ":" + e.getCause().getMessage()));
            }
        }
        return instance;
    }

    private CaptureApi() throws ReflectiveOperationException {
        windowManager = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null);
        if (windowManager == null) throw new IllegalStateException("no window manager service");
        Method found = null;
        for (Method m : windowManager.getClass().getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (m.getName().equals("captureDisplay") && p.length == 3 && p[0] == int.class) { found = m; break; }
        }
        if (found == null) { CaptureSurvey.run(windowManager); throw new NoSuchMethodException("IWindowManager.captureDisplay(int, ?, ?)"); }
        captureDisplay = found;
        argsClass = found.getParameterTypes()[1];
        listenerClass = found.getParameterTypes()[2];
        try {
            builderClass = nested(argsClass, "Builder");
            shotClass = shotClass(argsClass, listenerClass);
            setCrop = builderClass.getMethod("setSourceCrop", Rect.class);
            setScale = builderClass.getMethod("setFrameScale", float.class);
            setExclude = builderClass.getMethod("setExcludeLayers", SurfaceControl[].class);
            build = builderClass.getMethod("build");
            listenerCtor = listenerCtor(listenerClass);
            getBuffer = shotClass.getMethod("getHardwareBuffer");
            getSecure = shotClass.getMethod("containsSecureLayers");
            getColorSpace = shotClass.getMethod("getColorSpace");
        } catch (ReflectiveOperationException | RuntimeException e) {
            CaptureSurvey.run(windowManager);
            throw e;
        }
        setSecure = optional(builderClass, "setCaptureSecureLayers", boolean.class);
        setProtected = optional(builderClass, "setAllowProtected", boolean.class);
        getHdr = optional(shotClass, "containsHdrLayers");
    }

    private static Class<?> nested(Class<?> owner, String simpleName) throws ClassNotFoundException {
        for (Class<?> c : owner.getDeclaredClasses()) if (c.getSimpleName().equals(simpleName)) return c;
        return Class.forName(owner.getName() + "$" + simpleName, false, owner.getClassLoader());
    }

    /** The result type: whichever sibling of the argument/listener classes exposes getHardwareBuffer(). */
    private static Class<?> shotClass(Class<?> args, Class<?> listener) throws ClassNotFoundException {
        List<Class<?>> candidates = new ArrayList<>();
        for (Class<?> c : new Class<?>[]{args, listener}) {
            Class<?> outer = c.getEnclosingClass();
            if (outer != null) Collections.addAll(candidates, outer.getDeclaredClasses());
            Package pkg = c.getPackage();
            if (pkg != null) {
                try { candidates.add(Class.forName(pkg.getName() + ".ScreenshotHardwareBuffer", false, c.getClassLoader())); }
                catch (ClassNotFoundException ignored) { }
            }
        }
        for (Class<?> c : candidates) {
            try { if (c.getMethod("getHardwareBuffer").getReturnType() == HardwareBuffer.class) return c; }
            catch (NoSuchMethodException | LinkageError ignored) { }
        }
        throw new ClassNotFoundException("ScreenshotHardwareBuffer next to " + args.getName());
    }

    /** Android 14-15 listeners take ObjIntConsumer(buffer, status); later ones take Consumer(buffer). */
    private static Constructor<?> listenerCtor(Class<?> listener) throws NoSuchMethodException {
        try { return listener.getConstructor(ObjIntConsumer.class); }
        catch (NoSuchMethodException e) { return listener.getConstructor(Consumer.class); }
    }

    private static Method optional(Class<?> owner, String name, Class<?>... args) {
        try { return owner.getMethod(name, args); } catch (NoSuchMethodException e) { return null; }
    }

    String describe() {
        return "path=IWindowManager.captureDisplay args=" + argsClass.getName() + " listener=" + listenerClass.getName()
                + " shot=" + shotClass.getName() + " callback=" + listenerCtor.getParameterTypes()[0].getSimpleName()
                + " secureSetter=" + (setSecure != null) + " protectedSetter=" + (setProtected != null) + " hdrGetter=" + (getHdr != null);
    }

    Object args(Rect crop, float scale, SurfaceControl[] exclude) throws ReflectiveOperationException {
        Object b = builderClass.getConstructor().newInstance();
        setCrop.invoke(b, crop);
        setScale.invoke(b, scale);
        if (setSecure != null) setSecure.invoke(b, false);
        if (setProtected != null) setProtected.invoke(b, false);
        setExclude.invoke(b, (Object) exclude);
        return build.invoke(b);
    }

    Object listener(ObjIntConsumer<Object> consumer) throws ReflectiveOperationException {
        if (listenerCtor.getParameterTypes()[0] == ObjIntConsumer.class) return listenerCtor.newInstance(consumer);
        // No status in this shape: a delivered buffer is success, a null one is failure.
        Consumer<Object> single = shot -> consumer.accept(shot, shot == null ? -1 : 0);
        return listenerCtor.newInstance(single);
    }

    void capture(int displayId, Object args, Object listener) throws ReflectiveOperationException {
        captureDisplay.invoke(windowManager, displayId, args, listener);
    }

    HardwareBuffer hardwareBuffer(Object shot) throws ReflectiveOperationException { return (HardwareBuffer) getBuffer.invoke(shot); }
    boolean secure(Object shot) throws ReflectiveOperationException { return (Boolean) getSecure.invoke(shot); }
    ColorSpace colorSpace(Object shot) throws ReflectiveOperationException { return (ColorSpace) getColorSpace.invoke(shot); }

    void closeQuietly(Object shot) {
        if (shot == null) return;
        try { HardwareBuffer hb = hardwareBuffer(shot); if (hb != null) hb.close(); }
        catch (ReflectiveOperationException | RuntimeException ignored) { }
    }
}
