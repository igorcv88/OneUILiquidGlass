package com.samsung.android.app.facewidget.presentation;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.FrameLayout;
import com.android.systemui.plugins.keyguardstatusview.NowBarItem;

/** Test double of the obfuscated base card (shared by ongoing and media cards). */
public class a extends FrameLayout {
    /** Set before constructing: the container is a CardView-like view, with a separate blur layer. */
    public static java.util.function.Function<Context, View> containerFactory;
    public final View container, blurLayer;
    public final NowBarCardGradientView gradient;
    public a(Context c) {
        super(c);
        blurLayer = new FrameLayout(c);
        addView(blurLayer, new LayoutParams(-1, -1));
        container = containerFactory != null ? containerFactory.apply(c) : new FrameLayout(c);
        addView(container, new LayoutParams(-1, -1));
        gradient = new NowBarCardGradientView(c);
        ((FrameLayout) container).addView(gradient, new LayoutParams(-1, -1));
    }
    public View getCardViewContainer() { return container; }
    public View getGradientBgView() { return gradient; }
    /** DZIF: with a CardView container the "blur container" is that same CardView (field J). */
    public View getBlurContainer() { return containerFactory != null ? container : blurLayer; }
    public void N(NowBarItem item, View target, float depth, int color) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(64f);
        d.setColor(color);
        target.setBackground(d);
    }
    public void U(NowBarItem item, Integer depth) { gradient.setGradientBackgroundVisible(true); }
}
