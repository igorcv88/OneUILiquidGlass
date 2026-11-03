package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import static org.junit.Assert.*;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.FrameLayout;
import com.android.systemui.statusbar.phone.ongoingactivity.OngoingActivityData;
import com.android.systemui.statusbar.phone.ongoingactivity.OngoingCardAdapter;
import com.android.systemui.statusbar.phone.ongoingactivity.CardStackview.CardStackView;
import io.github.igorcv88.oneuiliquidglass.glass.LiveGlassDrawable;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** L2: classified cards follow changeCardBg/updateItemBg; media cards in the same stack stay native. */
@RunWith(RobolectricTestRunner.class) @Config(sdk = 34)
public class LiveCardHooksTest {
    private Env env;
    private LiveGlass glass;
    private LiveCardHooks hooks;
    private OngoingCardAdapter adapter;
    private CardStackView stack;

    @Before public void setUp() throws Exception {
        env = new Env();
        glass = env.glass();
        hooks = new LiveCardHooks(glass);
        hooks.setIsMediaView(OngoingCardAdapter.class.getDeclaredMethod("isMediaView", View.class));
        adapter = new OngoingCardAdapter();
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        stack = new CardStackView(a);
    }
    private View card(int position) {
        View v = adapter.getView(position, null, stack);
        stack.addView(v);
        hooks.cardBound(adapter, v, position);
        return v;
    }

    @Test public void nonMediaCardIsWrappedAtGetViewAndFollowsChangeCardBg() {
        adapter.mList.add(new OngoingActivityData("com.samsung.android.health", false));
        View v = card(0);
        LiveGlassDrawable g = (LiveGlassDrawable) v.getBackground();
        stack.changeCardBg(v, 0xff0055ff);
        assertTrue(v.getBackground() instanceof GradientDrawable);
        hooks.cardWriter(v);
        assertSame(g, v.getBackground());
        assertEquals(0x000055ff, g.currentTint() & 0x00ffffff);
    }

    @Test public void mediaViewInTheSameStackIsNeverTouched() {
        adapter.mList.add(new OngoingActivityData("com.samsung.android.health", false));
        adapter.mList.add(new OngoingActivityData("com.spotify.music", false));
        View rest = card(0);
        View media = adapter.getView(1, null, stack);
        adapter.mediaViews.add(media); // Samsung's own verdict, even with the data flag false
        stack.addView(media);
        hooks.cardBound(adapter, media, 1);
        assertTrue(media.getBackground() instanceof GradientDrawable);
        stack.updateItemBg();
        hooks.stackUpdated(stack);
        assertTrue(rest.getBackground() instanceof LiveGlassDrawable);
        assertTrue(media.getBackground() instanceof GradientDrawable);
        stack.changeCardBg(media, 0xff000000);
        hooks.cardWriter(media);
        assertTrue(media.getBackground() instanceof GradientDrawable);
        assertFalse(glass.tracked(media));
    }

    /** Data flipped to media in place, then a writer before the next bind: re-read, restored. */
    @Test public void writerRereadsTheDataFlags() {
        OngoingActivityData d = new OngoingActivityData("com.samsung.android.health", false);
        adapter.mList.add(d);
        View v = card(0);
        assertTrue(v.getBackground() instanceof LiveGlassDrawable);
        d.mIsMediaOngoingData = true;
        stack.changeCardBg(v, 0xff0055ff);
        hooks.cardWriter(v);
        assertTrue(v.getBackground() instanceof GradientDrawable);
        assertFalse(glass.tracked(v));
    }

    /** Without a data list the helper supplies the card's data; isMediaView still vetoes. */
    @Test public void helperIsTheFallbackDataSourceAndNativeVerdictStillWins() {
        com.android.systemui.statusbar.phone.ongoingactivity.OngoingActivityDataHelper.reset();
        com.android.systemui.statusbar.phone.ongoingactivity.OngoingActivityDataHelper.add(new OngoingActivityData("com.samsung.android.health", false));
        com.android.systemui.statusbar.phone.ongoingactivity.OngoingActivityDataHelper.add(new OngoingActivityData("com.samsung.android.health", false));
        hooks.setHelper(OngoingDataSource.of(com.android.systemui.statusbar.phone.ongoingactivity.OngoingActivityDataHelper.class));
        View v = card(0); // mList is empty
        assertTrue(v.getBackground() instanceof LiveGlassDrawable);
        View media = adapter.getView(1, null, stack);
        adapter.mediaViews.add(media);
        hooks.cardBound(adapter, media, 1);
        assertTrue(media.getBackground() instanceof GradientDrawable);
        com.android.systemui.statusbar.phone.ongoingactivity.OngoingActivityDataHelper.reset();
    }

    @Test public void mediaDataFlagAloneExcludes() {
        adapter.mList.add(new OngoingActivityData("com.example.player", true));
        View v = card(0);
        assertTrue(v.getBackground() instanceof GradientDrawable);
    }

    @Test public void unknownNativeVerdictKeepsEverythingNative() {
        hooks.setIsMediaView(null);
        adapter.mList.add(new OngoingActivityData("com.samsung.android.health", false));
        View v = card(0);
        assertTrue(v.getBackground() instanceof GradientDrawable);
    }

    @Test public void convertViewReusedForMediaIsRestored() {
        adapter.mList.add(new OngoingActivityData("com.samsung.android.health", false));
        adapter.mList.add(new OngoingActivityData("com.spotify.music", true));
        View v = card(0);
        assertTrue(v.getBackground() instanceof LiveGlassDrawable);
        View again = adapter.getView(1, v, stack);
        hooks.cardBound(adapter, again, 1);
        assertTrue(again.getBackground() instanceof GradientDrawable);
        assertFalse(glass.tracked(again));
    }

    @Test public void writerOnAnUnclassifiedViewDoesNothing() {
        View stray = new FrameLayout(stack.getContext());
        stack.changeCardBg(stray, 0xff123456);
        hooks.cardWriter(stray);
        assertTrue(stray.getBackground() instanceof GradientDrawable);
        assertFalse(glass.tracked(stray));
    }
}
