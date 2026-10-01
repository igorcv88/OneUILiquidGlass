package io.github.igorcv88.oneuiliquidglass.glass;

import android.graphics.drawable.Drawable;
import android.view.View;
import java.lang.reflect.Method;
import io.github.igorcv88.oneuiliquidglass.hooks.Reflect;

/** Compositor blur is not a texture and cannot be passed to an AGSL sampler. */
public final class BackgroundBlurBridge {
    public final Drawable drawable;
    private final Method radius;
    private final Method color;
    private final Method corners;
    private BackgroundBlurBridge(Drawable d) throws ReflectiveOperationException {
        drawable = d;
        radius = d.getClass().getMethod("setBlurRadius", int.class);
        color = d.getClass().getMethod("setColor", int.class);
        corners = d.getClass().getMethod("setCornerRadius", float.class, float.class, float.class, float.class);
    }
    public static BackgroundBlurBridge create(View host) throws ReflectiveOperationException {
        Object root = Reflect.call(host, "getViewRootImpl");
        if (root == null) throw new IllegalStateException("No attached ViewRootImpl");
        Object d = Reflect.call(root, "createBackgroundBlurDrawable");
        if (!(d instanceof Drawable)) throw new IllegalStateException("No background blur drawable");
        return new BackgroundBlurBridge((Drawable) d);
    }
    public void update(int px, int tint, float[] radii) throws ReflectiveOperationException {
        radius.invoke(drawable, px);
        color.invoke(drawable, tint);
        // API order: top-left, top-right, bottom-left, bottom-right.
        corners.invoke(drawable, radii[0], radii[2], radii[6], radii[4]);
    }
    public void release() {
        try { radius.invoke(drawable, 0); } catch (ReflectiveOperationException | RuntimeException ignored) { }
        drawable.setVisible(false, false);
        drawable.setAlpha(0);
        drawable.setCallback(null);
    }
}
