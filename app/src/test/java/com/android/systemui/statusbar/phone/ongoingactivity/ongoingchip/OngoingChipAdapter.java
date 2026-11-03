package com.android.systemui.statusbar.phone.ongoingactivity.ongoingchip;

import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.view.ViewGroup;
import androidx.recyclerview.widget.RecyclerView;
import com.android.systemui.statusbar.phone.ongoingactivity.OngoingActivityData;
import java.util.ArrayList;
import java.util.List;

/** Test double of the DZIF chip adapter: setChipBg rebuilds the background on every bind, as on device. */
public class OngoingChipAdapter {
    public final List<OngoingActivityData> mItems = new ArrayList<>();
    public final List<String> mLabels = new ArrayList<>();
    public int color = 0xff1e6bff;
    public float radius = 40f;
    public boolean writeOnBind = true;
    /** The hooked process runs the setChipBg hook for nested calls too; tests wire it here. */
    public static java.util.function.BiConsumer<Object, Object[]> nested;
    public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
        if (writeOnBind) setChipBg((ViewGroup) holder.itemView, getChipBg(position));
    }
    public ColorStateList getChipBg(int position) { return ColorStateList.valueOf(color); }
    public void setChipBg(ViewGroup chip, ColorStateList color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(radius);
        d.setColor(0xffffffff);
        chip.setBackground(d);
        chip.setBackgroundTintList(color);
        if (nested != null) nested.accept(this, new Object[]{chip, color});
    }
}
