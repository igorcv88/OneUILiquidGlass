package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.widget.Button;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** No action is removed or replaced; only the native opaque fill layer stops drawing. */
@RunWith(RobolectricTestRunner.class) @Config(sdk = 34)
public class LiveActionFillTest {
    private final Activity activity = Robolectric.buildActivity(Activity.class).setup().get();

    private RippleDrawable ripple(int nativeColor, boolean inset) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(nativeColor);
        shape.setCornerRadius(42f);
        Drawable content = inset ? new InsetDrawable(shape, 6) : shape;
        return new RippleDrawable(ColorStateList.valueOf(0x44ffffff), content, null);
    }

    @Test public void actionContentIsSuppressedButNativeRippleAndListenerRemain() {
        for (boolean inset : new boolean[]{false, true}) {
            Button button = new Button(activity);
            RippleDrawable background = ripple(0xffffffff, inset);
            button.setBackground(background);
            Drawable content = background.getDrawable(0);
            int[] clicks = {0};
            button.setOnClickListener(v -> clicks[0]++);
            LiveActionFill lease = new LiveActionFill();
            lease.sync(button, true);
            assertTrue(lease.active());
            assertSame(background, button.getBackground());
            assertNotSame(content, background.getDrawable(0));
            assertEquals(android.graphics.PixelFormat.OPAQUE, background.getDrawable(0).getOpacity());
            background.setBounds(0, 0, 300, 84);
            android.graphics.Outline outline = new android.graphics.Outline();
            background.getDrawable(0).getOutline(outline);
            assertFalse("geometry remains available for native clipping", outline.isEmpty());
            assertEquals("hidden fill must not cast a capsule shadow", 0f, outline.getAlpha(), 0f);
            // RippleDrawable.mutate() may clone its content; the native material to restore is
            // the content *after* mutation, when this instance no longer shares ConstantState.
            Drawable isolatedContent = ((android.graphics.drawable.DrawableWrapper) background.getDrawable(0)).getDrawable();
            assertTrue(NativeShape.unwrap(isolatedContent) instanceof GradientDrawable);
            assertEquals(0xffffffff, ((GradientDrawable) NativeShape.unwrap(isolatedContent)).getColor().getDefaultColor());
            lease.sync(button, true);
            assertSame("idempotent", isolatedContent, ((android.graphics.drawable.DrawableWrapper) background.getDrawable(0)).getDrawable());
            button.performClick();
            assertEquals(1, clicks[0]);
            lease.restore();
            assertSame("exact isolated native content restored", isolatedContent, background.getDrawable(0));
        }
    }

    @Test public void samsungRebindRestoresOldRippleBeforeTakingNewOne() {
        Button button = new Button(activity);
        RippleDrawable old = ripple(0xffffffff, false);
        button.setBackground(old);
        LiveActionFill lease = new LiveActionFill();
        lease.sync(button, true);
        Drawable oldIsolated = ((android.graphics.drawable.DrawableWrapper) old.getDrawable(0)).getDrawable();
        RippleDrawable next = ripple(0xffffffff, true);
        button.setBackground(next);
        lease.sync(button, true);
        Drawable nextIsolated = ((android.graphics.drawable.DrawableWrapper) next.getDrawable(0)).getDrawable();
        assertSame(oldIsolated, old.getDrawable(0));
        assertNotSame(nextIsolated, next.getDrawable(0));
        lease.sync(button, false);
        assertSame(nextIsolated, next.getDrawable(0));
    }

    @Test public void nonWhiteContentRemainsUnchanged() {
        Button button = new Button(activity);
        RippleDrawable nativeButton = ripple(0xff2456a8, false);
        button.setBackground(nativeButton);
        Drawable nativeContent = nativeButton.getDrawable(0);
        LiveActionFill lease = new LiveActionFill();
        lease.sync(button, true);
        assertFalse(lease.active());
        assertSame(nativeContent, nativeButton.getDrawable(0));
    }

    @Test public void siblingFromSharedConstantStateDoesNotLoseItsFill() {
        RippleDrawable template = ripple(0xffffffff, false);
        assertNotNull(template.getConstantState());
        RippleDrawable one = (RippleDrawable) template.getConstantState().newDrawable(activity.getResources());
        RippleDrawable other = (RippleDrawable) template.getConstantState().newDrawable(activity.getResources());
        Drawable otherContent = other.getDrawable(0);
        Button button = new Button(activity);
        button.setBackground(one);
        LiveActionFill lease = new LiveActionFill();
        lease.sync(button, true);
        assertTrue(lease.active());
        assertSame("only the selected button's content is hidden", otherContent, other.getDrawable(0));
        lease.restore();
        assertSame(otherContent, other.getDrawable(0));
    }
}

