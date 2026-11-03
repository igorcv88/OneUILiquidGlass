package com.samsung.android.app.facewidget.presentation;

import android.content.Context;
import android.view.View;

/** Test double: the gradient layer and its visibility writer. */
public class NowBarCardGradientView extends View {
    public boolean gradientVisible = true;
    public NowBarCardGradientView(Context c) { super(c); }
    public void setGradientBackgroundVisible(boolean visible) { gradientVisible = visible; }
    public void setGradientColor(int color) { }
    public boolean isGradientBackgroundVisible() { return gradientVisible; }
}
