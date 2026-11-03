package com.android.systemui.statusbar.phone.ongoingactivity.ongoingchip;

import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.view.ViewGroup;
import androidx.recyclerview.widget.RecyclerView;
import com.android.systemui.statusbar.phone.ongoingactivity.OngoingActivityDataHelper;

/**
 * Test double with the DZIF structure: no getItem(int) and no data list; the data of a position
 * comes from OngoingActivityDataHelper.getDataByIndex(int), and setChipBg rebuilds the background
 * on every bind, as on device.
 */
public class OngoingChipAdapter {
    public int color = 0xff1e6bff;
    public float radius = 40f;
    public boolean writeOnBind = true;
    /** The hooked process runs the setChipBg hook for nested calls too; tests wire it here. */
    public static java.util.function.BiConsumer<Object, Object[]> nested;
    /** Runs inside the bind, between the data read and the writer (a list change mid-bind). */
    public Runnable duringBind;
    public int getItemCount() { return OngoingActivityDataHelper.getDataCount(); }
    public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
        OngoingActivityDataHelper.getDataByIndex(position);
        if (duringBind != null) duringBind.run();
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
