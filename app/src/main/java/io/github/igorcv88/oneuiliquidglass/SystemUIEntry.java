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
            XSharedPreferences prefs = new XSharedPreferences(Config.PACKAGE, Config.PREFS);
            prefs.reload();
            boolean readable = prefs.getFile().canRead();
            boolean enabled = prefs.getBoolean(Config.KEY_ENABLED, false);
            Probe.log("CONFIG", "enabled=" + enabled + " readable=" + readable);
            // Unreadable file: the value is fetched from ConfigProvider once SystemUI has a Context.
            new HeadsUpHooks(p.classLoader, readable ? Boolean.valueOf(enabled) : null).install();
        } catch (RuntimeException | LinkageError e) { Probe.error("INSTALL_FAILED", e); }
    }
}
