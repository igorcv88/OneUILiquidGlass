package com.android.systemui.toast;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Test double of the DZIF factory: a LinearLayout with the rounded grey toast shape and its text. */
public class ToastFactory {
    public SystemUIToast createToast(Context ui, Context app, CharSequence text, String pkg, int user, int orientation) {
        LinearLayout root = new LinearLayout(ui);
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(60f);
        d.setColor(0xff303030);
        root.setBackground(d);
        TextView t = new TextView(ui);
        t.setText(text);
        root.addView(t);
        return new SystemUIToast(root);
    }
}
