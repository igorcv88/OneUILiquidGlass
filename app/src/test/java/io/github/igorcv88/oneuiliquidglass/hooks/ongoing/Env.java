package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import android.view.View;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import io.github.igorcv88.oneuiliquidglass.glass.BackgroundBlurBridge;

/** Gate, blur capability and policy the tests switch by hand. */
public class Env implements LiveGlass.Blur, LiveGlass.Gate, LiveGlass.Policy {
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
    public LiveGlass glass() { return new LiveGlass(this, this, this); }
}
