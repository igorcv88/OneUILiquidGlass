package com.android.systemui.plugins.keyguardstatusview;

/** Test double: the NowBarItem getters the Now Bar classification reads. */
public class NowBarItem {
    public Object media;
    public String pkg;
    public NowBarItem(String pkg, Object media) { this.pkg = pkg; this.media = media; }
    public Object getFaceWidgetMediaData() { return media; }
    public String getNowBarPackage() { return pkg; }
}
