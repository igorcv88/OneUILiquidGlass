package io.github.igorcv88.oneuiliquidglass;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;
import io.github.igorcv88.oneuiliquidglass.hooks.HeadsUpHooks;

public final class SystemUIEntry implements IXposedHookLoadPackage {
    private static boolean installed;
    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam p) {
        if (!"com.android.systemui".equals(p.packageName) || !"com.android.systemui".equals(p.processName)) return;
        synchronized (SystemUIEntry.class) { if (installed) return; installed = true; }
        try {
            Probe.firmware();
            XSharedPreferences prefs = new XSharedPreferences("io.github.igorcv88.oneuiliquidglass", "glass");
            prefs.reload();
            boolean enabled = prefs.getBoolean("enabled", false);
            Probe.log("CONFIG", "enabled=" + enabled + " readable=" + prefs.getFile().canRead());
            new HeadsUpHooks(p.classLoader, enabled).install();
        } catch (RuntimeException | LinkageError e) { Probe.error("INSTALL_FAILED", e); }
    }
}
