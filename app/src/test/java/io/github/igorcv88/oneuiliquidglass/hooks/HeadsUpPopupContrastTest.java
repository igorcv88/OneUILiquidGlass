package io.github.igorcv88.oneuiliquidglass.hooks;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import org.robolectric.RuntimeEnvironment;

import io.github.igorcv88.oneuiliquidglass.glass.LiveGlassDrawable;
import io.github.igorcv88.oneuiliquidglass.glass.Material;
import io.github.igorcv88.oneuiliquidglass.glass.ShadeTone;
import io.github.igorcv88.oneuiliquidglass.glass.ToneGate;
import io.github.igorcv88.oneuiliquidglass.glass.VeilRamp;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "night")
public class HeadsUpPopupContrastTest {
    @Test public void liveDrawableMeasuresAndAdaptsInBothDirections() {
        assertTrue(HeadsUpHooks.popupContrastRoute(true, false, true, false, false));
        contrastChangesWithBackdrop();
    }

    @Test public void samsungLensMeasuresAndAdaptsInBothDirections() {
        assertTrue(HeadsUpHooks.popupContrastRoute(false, true, false, true, false));
        contrastChangesWithBackdrop();
    }

    @Test public void sfLensOnCardMeasuresAndAdaptsInBothDirections() {
        assertTrue(HeadsUpHooks.popupContrastRoute(false, true, true, false, true));
        contrastChangesWithBackdrop();
    }

    @Test public void unrelatedDrawablesAndMissingBridgesDoNotEnterPopupTone() {
        assertFalse(HeadsUpHooks.popupContrastRoute(false, true, true, false, false));
        assertFalse(HeadsUpHooks.popupContrastRoute(false, true, false, false, false));
        assertFalse(HeadsUpHooks.popupContrastRoute(false, false, true, false, true));
        assertFalse(HeadsUpHooks.popupContrastRoute(true, false, false, true, false));
    }

    @Test public void liveCardOwnsItsTextUntilItReturnsToNative() {
        FrameLayout parent = new FrameLayout(RuntimeEnvironment.getApplication());
        FrameLayout liveCard = new FrameLayout(parent.getContext());
        FrameLayout ownedFill = new FrameLayout(parent.getContext());
        FrameLayout otherFill = new FrameLayout(parent.getContext());
        FrameLayout privateFill = new FrameLayout(parent.getContext());
        Context remoteViewsContext = new ContextWrapper(parent.getContext()) {
            @Override public String getPackageName() { return "com.example.liveupdate"; }
        };
        TextView label = new TextView(remoteViewsContext);
        TextView foreignLabel = new TextView(remoteViewsContext);
        TextView badge = new TextView(remoteViewsContext);
        label.setTextColor(0xffffffff);
        badge.setTextColor(0xffffffff);
        liveCard.setBackground(new ColorDrawable(0xffdddddd));
        privateFill.setBackground(new ColorDrawable(0xff101010));
        parent.addView(liveCard);
        LiveGlassDrawable ownedGlass = cardGlass();
        ownedFill.setBackground(ownedGlass);
        otherFill.setBackground(cardGlass());
        liveCard.addView(ownedFill);
        liveCard.addView(otherFill);
        ownedFill.addView(label);
        ownedFill.addView(privateFill);
        otherFill.addView(foreignLabel);
        privateFill.addView(badge);
        Predicate<View> before = Foreground.ownsLiveCardTone;
        BiPredicate<View, View> beforeFill = Foreground.ownsLiveCardFill;
        try {
            Foreground.ownsLiveCardTone = view -> view == liveCard;
            Foreground.ownsLiveCardFill = (root, fill) -> root == liveCard && fill == ownedFill
                    && fill.getBackground() == ownedGlass;
            assertTrue(Foreground.customSurface(label));
            assertFalse(Foreground.customSurface(badge));
            assertFalse(Foreground.customSurface(foreignLabel));
            Foreground.apply(liveCard, true, true, null);
            assertNotEquals(0xffffffff, label.getCurrentTextColor());
            assertEquals(0xffffffff, badge.getCurrentTextColor());
            Foreground.apply(parent, false, true, null);
            assertNotEquals(0xffffffff, label.getCurrentTextColor());
            ownedGlass.setGlass(false);
            assertFalse(Foreground.customSurface(label));
            Foreground.apply(liveCard, false, true, null);
            assertEquals(0xffffffff, label.getCurrentTextColor());
        } finally {
            Foreground.ownsLiveCardTone = before;
            Foreground.ownsLiveCardFill = beforeFill;
        }
    }

    private static LiveGlassDrawable cardGlass() {
        LiveGlassDrawable glass = new LiveGlassDrawable(new ColorDrawable(0xffdddddd), new float[8],
                0xffdddddd, new LiveGlassDrawable.Spec(0.5f, 14, 0, 0, 1f, 0), 1f);
        glass.setRegionless(true);
        return glass;
    }

    private static void contrastChangesWithBackdrop() {
        assertTrue(ShadeTone.lightText());
        ToneGate gate = new ToneGate(100, 220);
        Material dark = ShadeTone.materialFor(0.10f, null, false);
        assertFalse(dark.light);
        assertFalse(dark.swap);
        assertTrue(gate.offer(dark.light, 0));

        float lightReading = 0.67f;
        boolean lightSide = ShadeTone.lightBackdrop(lightReading, dark.light);
        assertTrue(lightSide);
        assertFalse(gate.offer(lightSide, 220));
        assertFalse(gate.offer(lightSide, 319));
        assertTrue(gate.offer(lightSide, 320));

        Material light = ShadeTone.materialFor(lightReading, dark.light, false);
        assertTrue(light.light);
        assertTrue(light.swap);
        assertNotEquals(0, light.veil >>> 24);
        int nativeTint = 0x20102030;
        assertArrayEquals(new int[] {nativeTint, light.veil}, VeilRamp.ends(dark.veil, light.veil, nativeTint));

        float darkReading = 0.42f;
        boolean darkSide = ShadeTone.lightBackdrop(darkReading, light.light);
        assertFalse(darkSide);
        assertFalse(gate.offer(darkSide, 540));
        assertTrue(gate.offer(darkSide, 640));
        Material restored = ShadeTone.materialFor(darkReading, light.light, false);
        assertFalse(restored.light);
        assertFalse(restored.swap);
        assertArrayEquals(new int[] {light.veil, nativeTint}, VeilRamp.ends(light.veil, restored.veil, nativeTint));
    }
}
