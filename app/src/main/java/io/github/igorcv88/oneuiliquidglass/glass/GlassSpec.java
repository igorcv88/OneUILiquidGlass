package io.github.igorcv88.oneuiliquidglass.glass;

/** Distances are dp until converted at the host; colors are ARGB. */
public final class GlassSpec {
    public final float blurDp = 30f;
    /** Fill drawn by the material on every surface; light enough for the backdrop to read through. */
    public final int lightTint = 0x47ffffff;
    public final int darkTint = 0x4d1c1f26;
    /** Color handed to the blur itself; near-transparent so the fill alone sets the tone. */
    public final int lightBlurColor = 0x14ffffff;
    public final int darkBlurColor = 0x14000000;
    public final float edgeStrength = 0.55f;
}
