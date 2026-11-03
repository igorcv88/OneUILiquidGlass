package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.widget.FrameLayout;

/** A CardView-like container: its own background class, radius, colour and elevation setters. */
public class FakeCardView extends FrameLayout {
    private ColorStateList color = ColorStateList.valueOf(0xffe07020);
    private float elevation = 6f;
    private float radius = 64f;
    public FakeCardView(Context c) { super(c); setBackground(new ColorDrawable(0xffe07020)); }
    public void setCardBackgroundColor(int c) { setCardBackgroundColor(ColorStateList.valueOf(c)); }
    public int colorWrites, elevationWrites;
    public void setCardBackgroundColor(ColorStateList c) { color = c; colorWrites++; }
    public ColorStateList getCardBackgroundColor() { return color; }
    public float getRadius() { return radius; }
    public void setRadius(float r) { radius = r; }
    public void setCardElevation(float e) { elevation = e; elevationWrites++; }
    public float getCardElevation() { return elevation; }
}
