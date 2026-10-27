package io.github.igorcv88.oneuiliquidglass.hooks;

import org.junit.Test;
import static org.junit.Assert.*;

public final class CardExitTest {
    /**
     * Heads-up dismissed with its button (device log 27/10, 14:02:41): animatingAway, then the row is
     * removed and re-added as a transient view in the same message, then its disappear animation.
     * The material must survive the re-parent and must not be rebuilt while the card leaves.
     */
    @Test public void transientReparentKeepsTheMaterial() {
        CardExit e = new CardExit();
        // removeNotification: the row is detached with its glass; the release is deferred.
        assertTrue(e.detach(true));
        assertTrue(e.held());
        // addTransientView: re-attached to the same window before the deferred release runs.
        assertTrue(e.attach(true));
        assertFalse(e.held());
        // The deferred release then finds the card attached and does nothing.
        assertFalse(e.deferredRelease(true));
        // startAppearAnimation(false): the card leaves.
        assertTrue(e.begin(1000));
        assertTrue(e.leaving(1001));
        // The heads-up flag clears under the animation (kind SAMSUNG -> COMPOSITOR): kept, not rebuilt.
        assertTrue(CardExit.keepMaterial(e.leaving(1100), true, false));
        // onAppearAnimationFinished.
        assertTrue(e.end());
        assertFalse(e.leaving(1200));
    }
    /** A card removed for good (no transient view): the deferred release runs and releases it. */
    @Test public void removalWithoutReattachReleasesOnce() {
        CardExit e = new CardExit();
        e.begin(0);
        assertTrue(e.detach(true));
        assertTrue(e.deferredRelease(false));
        // It left for good: no longer leaving, and a second run does nothing.
        assertFalse(e.leaving(1));
        assertFalse(e.deferredRelease(false));
    }
    /** A region belongs to its window: a re-attach elsewhere releases the held material. */
    @Test public void reattachToAnotherWindowReleases() {
        CardExit e = new CardExit();
        e.detach(true);
        assertFalse(e.attach(false));
        assertFalse(e.held());
    }
    /** A detach without glass holds nothing: released at once, as before. */
    @Test public void detachWithoutGlassHoldsNothing() {
        CardExit e = new CardExit();
        assertFalse(e.detach(false));
        assertFalse(e.held());
        assertFalse(e.attach(true));
        assertFalse(e.deferredRelease(false));
    }
    /**
     * Heads-up pulled into the shade: animatingAway without a disappear animation. The card is not
     * leaving, so its material changes kind as before (no transition is blocked).
     */
    @Test public void headsUpIntoTheShadeIsNotAnExit() {
        CardExit e = new CardExit();
        assertFalse(e.leaving(0));
        assertFalse(CardExit.keepMaterial(e.leaving(0), true, false));
    }
    /** A stale sampled backdrop is replaced even while leaving: it can no longer draw. */
    @Test public void staleMaterialIsNotHeld() {
        assertFalse(CardExit.keepMaterial(true, true, true));
        assertFalse(CardExit.keepMaterial(true, false, false));
    }
    /** An exit whose finish never comes ends by itself instead of holding the card for good. */
    @Test public void exitExpires() {
        CardExit e = new CardExit();
        e.begin(0);
        assertTrue(e.leaving(CardExit.MAX_EXIT_MS));
        assertFalse(e.leaving(CardExit.MAX_EXIT_MS + 1));
        assertFalse(e.end());
    }
    /** A repeated begin (two overloads hooked) keeps the first start time. */
    @Test public void repeatedBeginKeepsTheStart() {
        CardExit e = new CardExit();
        assertTrue(e.begin(100));
        assertFalse(e.begin(150));
        assertEquals(100, e.since(200));
        e.end();
        assertEquals(-1, e.since(300));
    }
    @Test public void appearingArgument() {
        assertEquals(Boolean.FALSE, CardExit.appearing(new Object[]{false, 1f, 0L, 360L}));
        assertEquals(Boolean.TRUE, CardExit.appearing(new Object[]{true}));
        assertNull(CardExit.appearing(new Object[]{1f}));
        assertNull(CardExit.appearing(new Object[0]));
        assertNull(CardExit.appearing(null));
    }
    @Test public void exitAlphaTakesTheLowestKnownSignal() {
        assertEquals(0.3f, CardExit.exitAlpha(0.3f, Float.NaN), 1e-6);
        assertEquals(0.6f, CardExit.exitAlpha(Float.NaN, 0.6f), 1e-6);
        assertEquals(0.2f, CardExit.exitAlpha(0.2f, 0.6f), 1e-6);
        assertEquals(0.2f, CardExit.exitAlpha(0.9f, 0.2f), 1e-6);
        // AOSP's fraction is -1 outside an animation: not a signal.
        assertEquals(0.7f, CardExit.exitAlpha(0.7f, -1f), 1e-6);
        // Nothing known: the leaving card's region goes at once.
        assertEquals(0f, CardExit.exitAlpha(Float.NaN, Float.NaN), 0f);
        assertEquals(0f, CardExit.exitAlpha(Float.NaN, -1f), 0f);
        assertEquals(1f, CardExit.exitAlpha(1.4f, Float.NaN), 0f);
    }
    /** Cards that are not leaving get exactly the previous region policy. */
    @Test public void notLeavingKeepsTheEarlierPolicy() {
        for (float a : new float[]{0f, 0.2f, 0.49f, 0.5f, 0.8f, 1f}) {
            for (float cut : new float[]{0f, 0.5f}) {
                float shown = CardExit.shown(a, false, 0f);
                assertEquals(a, shown, 0f);
                assertEquals(cut > 0f ? (a >= cut ? 1f : 0f) : a, CardExit.drawableFade(shown, cut), 0f);
                assertEquals(cut, CardExit.samsungCut(cut, false), 0f);
            }
        }
    }
    /**
     * Shade removal with the action button (device log 27/10, 14:02:24): the view alpha stayed 1 and
     * the region stayed whole (alpha=1.00) for the whole removal. With the content's alpha the
     * region goes when the content does.
     */
    @Test public void shadeRemovalRegionFollowsTheContent() {
        float cut = 0.5f;
        float[] content = {1f, 0.8f, 0.55f, 0.45f, 0.1f, 0f};
        float[] expected = {1f, 1f, 1f, 0f, 0f, 0f};
        for (int i = 0; i < content.length; i++) {
            float shown = CardExit.shown(1f, true, CardExit.exitAlpha(content[i], Float.NaN));
            assertEquals("frame " + i, expected[i], CardExit.drawableFade(shown, cut), 0f);
        }
        // With shadefadecut=0 (the user's test) it fades with the content instead of staying whole.
        assertEquals(0.45f, CardExit.drawableFade(CardExit.shown(1f, true, 0.45f), 0f), 1e-6);
    }
    /**
     * The Samsung region ignores its drawable's alpha: a leaving card's is cut at SAMSUNG_CUT even
     * without a shade cut (the lockscreen, or shadefadecut=0), from ancestor and exit alpha together.
     */
    @Test public void samsungPathCutsALeavingCard() {
        assertEquals(CardExit.SAMSUNG_CUT, CardExit.samsungCut(0f, true), 0f);
        assertEquals(0.5f, CardExit.samsungCut(0.5f, true), 0f);
        float semCut = CardExit.samsungCut(0f, true);
        // Ancestor alpha 0.8 (the heads-up fading) and content 0.6: shown 0.48, below the cut.
        assertTrue(CardExit.shown(0.8f, true, 0.6f) < semCut);
        assertFalse(CardExit.shown(0.8f, true, 1f) < semCut);
    }
}
