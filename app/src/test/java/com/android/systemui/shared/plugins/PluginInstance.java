package com.android.systemui.shared.plugins;

import android.content.ComponentName;

/** Test double of SystemUI's PluginInstance: package, component and the loaded plugin object. */
public class PluginInstance {
    private final String pkg;
    private final ComponentName component;
    private Object plugin;
    private final Object loaded;
    public PluginInstance(String pkg, String cls, Object plugin) { this.pkg = pkg; this.component = new ComponentName(pkg, cls); this.loaded = plugin; }
    public void loadPlugin() { plugin = loaded; }
    public Object getPlugin() { return plugin; }
    public ComponentName getComponentName() { return component; }
    public String getPackageName() { return pkg; }
}
