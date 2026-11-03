package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import android.view.View;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import io.github.igorcv88.oneuiliquidglass.glass.BackgroundBlurBridge;

/** Gate, blur capability and policy the tests switch by hand. */
public class Env implements LiveGlass.Blur, LiveGlass.Gate, LiveGlass.Policy, LiveGlass.NativeBlur {
    /** What semSetBlurInfo installs as background on One UI. */
    public static class SamsungBlurDrawable extends android.graphics.drawable.ColorDrawable { }
    public final java.util.Map<View, Object> sem = new java.util.WeakHashMap<>();
    /** Samsung's writer (setBlurBG, a.N): its own blur on the host. */
    public Object samsungBlur(View v) { Object info = new Object(); set(v, info); return info; }
    @Override public Object held(View host) { return sem.get(host); }
    @Override public void clear(View host) {
        sem.remove(host);
        if (host.getBackground() instanceof SamsungBlurDrawable) host.setBackground(null);
    }
    @Override public void set(View host, Object info) { sem.put(host, info); host.setBackground(new SamsungBlurDrawable()); }
    public final Set<LiveSurface> on = EnumSet.allOf(LiveSurface.class);
    public boolean blurEnabled = true, glassAllowed = true, failCreate, failUpdate;
    public final List<FakeBlurDrawable> regions = new ArrayList<>();
    @Override public BackgroundBlurBridge create(View host) throws ReflectiveOperationException {
        if (failCreate) throw new NoSuchMethodException("createBackgroundBlurDrawable");
        FakeBlurDrawable d = new FakeBlurDrawable();
        d.fail = failUpdate;
        regions.add(d);
        return BackgroundBlurBridge.of(d);
    }
    @Override public boolean enabled(View host) { return blurEnabled; }
    @Override public boolean allows(LiveSurface s) { return on.contains(s); }
    @Override public boolean glassAllowed(LiveSurface s, View host) { return glassAllowed; }
    public LiveGlass glass() { return new LiveGlass(this, this, this, this); }
}
