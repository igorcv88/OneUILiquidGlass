package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import androidx.recyclerview.widget.RecyclerView;
import com.android.systemui.statusbar.phone.ongoingactivity.OngoingActivityData;
import com.android.systemui.statusbar.phone.ongoingactivity.OngoingActivityDataHelper;
import com.android.systemui.statusbar.phone.ongoingactivity.ongoingchip.OngoingChipAdapter;
import io.github.igorcv88.oneuiliquidglass.glass.LiveGlassDrawable;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

/** L1: the chip keeps the glass across Samsung's setChipBg writer, recycling and media changes. */
@RunWith(RobolectricTestRunner.class) @Config(sdk = 34)
public class LiveChipHooksTest {
    private Env env;
    private LiveGlass glass;
    private LiveChipHooks hooks;
    private OngoingChipAdapter adapter;
    private Activity activity;

    @Before public void setUp() {
        env = new Env();
        glass = env.glass();
        hooks = new LiveChipHooks(glass);
        hooks.setLocator(OngoingDataSource.of(OngoingActivityDataHelper.class)::at);
        OngoingActivityDataHelper.reset();
        adapter = new OngoingChipAdapter();
        activity = Robolectric.buildActivity(Activity.class).setup().get();
    }

    /** Samsung's bind as the hooked process runs it: before, original (writer inside), after. */
    private void bind(RecyclerView.ViewHolder holder, int position) {
        hooks.bindStart(adapter, holder, position);
        adapter.onBindViewHolder(holder, position);
        if (adapter.writeOnBind) hooks.chipBackground(adapter, holder.itemView);
        hooks.bindEnd(adapter, holder, false);
    }
    private RecyclerView.ViewHolder holder() { return new RecyclerView.ViewHolder(new FrameLayout(activity)); }

    @Test public void defaultIconPolicyTreatsClockButPreservesUserAppPacks() {
        assertTrue(LiveChipHooks.shouldTreatChipPackage("com.sec.android.app.clockpackage", 1));
        assertTrue(LiveChipHooks.shouldTreatChipPackage("android", 1));
        assertFalse(LiveChipHooks.shouldTreatChipPackage("com.igcv.gradus", 1));
        assertFalse(LiveChipHooks.shouldTreatChipPackage("com.sec.android.app.clockpackage", 0));
        assertTrue(LiveChipHooks.shouldTreatChipPackage("com.igcv.gradus", 3));
    }

    @Test public void firstBindWrapsTheNativeChipBeforeAnyFrame() {
        OngoingActivityDataHelper.add(new OngoingActivityData("com.sec.android.app.clockpackage", false));
        RecyclerView.ViewHolder h = holder();
        bind(h, 0);
        Drawable bg = h.itemView.getBackground();
        assertTrue("glass installed inside the bind, not later", bg instanceof LiveGlassDrawable);
        LiveGlassDrawable g = (LiveGlassDrawable) bg;
        assertTrue(g.nativeDrawable() instanceof GradientDrawable);
        // The native tint stays on the view and colours the glass with the same RGB.
        assertEquals(0xff1e6bff, h.itemView.getBackgroundTintList().getDefaultColor());
        assertEquals(0x001e6bff, g.currentTint() & 0x00ffffff);
        assertTrue((g.currentTint() >>> 24) < 0xff);
        // Not attached yet: no region, the native drawable is what would draw.
        assertFalse(g.showsGlass());
        assertTrue(env.regions.isEmpty());
    }

    @Test public void regionIsBuiltOnAttachAndDroppedOnDetach() {
        OngoingActivityDataHelper.add(new OngoingActivityData("com.example.timer", false));
        RecyclerView.ViewHolder h = holder();
        bind(h, 0);
        activity.setContentView(h.itemView);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        LiveGlassDrawable g = (LiveGlassDrawable) h.itemView.getBackground();
        assertTrue(g.showsGlass());
        assertEquals(1, env.regions.size());
        ((android.view.ViewGroup) h.itemView.getParent()).removeView(h.itemView);
        assertFalse(g.showsGlass());
        assertSame("detach keeps the wrapper for a recycled chip", g, h.itemView.getBackground());
    }

    @Test public void rebindWriterIsFollowedAndNeverWrapsTwice() {
        OngoingActivityDataHelper.add(new OngoingActivityData("com.example.timer", false));
        RecyclerView.ViewHolder h = holder();
        bind(h, 0);
        LiveGlassDrawable first = (LiveGlassDrawable) h.itemView.getBackground();
        for (int i = 0; i < 5; i++) bind(h, 0);
        assertSame("one wrapper per host, reused", first, h.itemView.getBackground());
        assertFalse(first.nativeDrawable() instanceof LiveGlassDrawable);
        // A recolour outside a bind (setChipBg from the controller) is reasserted with the new native.
        adapter.color = 0xff00aa55;
        adapter.setChipBg((android.view.ViewGroup) h.itemView, ColorStateList.valueOf(adapter.color));
        Drawable written = h.itemView.getBackground();
        assertTrue(written instanceof GradientDrawable);
        hooks.chipBackground(adapter, h.itemView);
        assertSame(first, h.itemView.getBackground());
        assertSame(written, first.nativeDrawable());
        assertEquals(0x0000aa55, first.currentTint() & 0x00ffffff);
    }

    @Test public void holderRecycledForMediaKeepsGlass() {
        OngoingActivityDataHelper.add(new OngoingActivityData("com.example.timer", false));
        OngoingActivityDataHelper.add(new OngoingActivityData("com.spotify.music", true));
        RecyclerView.ViewHolder h = holder();
        bind(h, 0);
        assertTrue(h.itemView.getBackground() instanceof LiveGlassDrawable);
        bind(h, 1);
        assertTrue("media chip uses the same compatible background", h.itemView.getBackground() instanceof LiveGlassDrawable);
    }

    @Test public void recycledHolderWithoutWriterIsClassifiedAgainstTheNewData() {
        OngoingActivityDataHelper.add(new OngoingActivityData("com.example.timer", false));
        OngoingActivityDataHelper.add(new OngoingActivityData("com.spotify.music", true));
        RecyclerView.ViewHolder h = holder();
        bind(h, 0);
        Drawable nativeBg = ((LiveGlassDrawable) h.itemView.getBackground()).nativeDrawable();
        adapter.writeOnBind = false;
        bind(h, 1);
        assertTrue(h.itemView.getBackground() instanceof LiveGlassDrawable);
        assertSame("native drawable is retained under the glass", nativeBg,
                ((LiveGlassDrawable) h.itemView.getBackground()).nativeDrawable());
    }

    @Test public void mediaFlagFlippedInPlaceDoesNotDiscardGlass() {
        OngoingActivityData d = new OngoingActivityData("com.example.timer", false);
        OngoingActivityDataHelper.add(d);
        RecyclerView.ViewHolder h = holder();
        bind(h, 0);
        d.mIsMediaOngoingData = true;
        adapter.setChipBg((android.view.ViewGroup) h.itemView, ColorStateList.valueOf(0xff112233));
        hooks.chipBackground(adapter, h.itemView);
        assertTrue(h.itemView.getBackground() instanceof LiveGlassDrawable);
        assertTrue(glass.tracked(h.itemView));
    }

    @Test public void automationAndUnknownDataStayNative() {
        OngoingActivityData auto = new OngoingActivityData("com.samsung.android.app.routines", false);
        auto.mIsAutomation = true;
        OngoingActivityDataHelper.add(auto);
        RecyclerView.ViewHolder h = holder();
        bind(h, 0);
        assertTrue(h.itemView.getBackground() instanceof GradientDrawable);
        RecyclerView.ViewHolder h2 = holder();
        bind(h2, 7); // no data at that position
        assertTrue(h2.itemView.getBackground() instanceof GradientDrawable);
    }

    @Test public void gateOffKeepsNativeAndRefreshAppliesThenRestoresExactly() {
        OngoingActivityDataHelper.add(new OngoingActivityData("com.example.timer", false));
        env.on.remove(LiveSurface.CHIP);
        RecyclerView.ViewHolder h = holder();
        bind(h, 0);
        Drawable nativeBg = h.itemView.getBackground();
        ColorStateList nativeTint = h.itemView.getBackgroundTintList();
        assertTrue(nativeBg instanceof GradientDrawable);
        env.on.add(LiveSurface.CHIP);
        glass.refresh(LiveSurface.CHIP);
        assertTrue(h.itemView.getBackground() instanceof LiveGlassDrawable);
        env.on.remove(LiveSurface.CHIP);
        glass.refresh(LiveSurface.CHIP);
        assertSame(nativeBg, h.itemView.getBackground());
        assertSame(nativeTint, h.itemView.getBackgroundTintList());
        assertSame("callback handed back to the view", h.itemView, nativeBg.getCallback());
    }

    @Test public void otherSurfacesAreUnaffectedByTheChipGate() {
        OngoingActivityDataHelper.add(new OngoingActivityData("com.example.timer", false));
        env.on.clear();
        env.on.add(LiveSurface.CHIP);
        RecyclerView.ViewHolder h = holder();
        bind(h, 0);
        assertTrue(h.itemView.getBackground() instanceof LiveGlassDrawable);
        assertEquals(LiveSurface.CHIP, glass.surfaceOf(h.itemView));
        assertTrue(glass.hosts(LiveSurface.CARD).isEmpty());
    }

    /** A writer hook firing inside our own setBackground (reentrancy) is ignored: one wrapper, no recursion. */
    @Test public void reentrantWriterDuringOurOwnSetBackgroundIsIgnored() {
        OngoingActivityDataHelper.add(new OngoingActivityData("com.example.timer", false));
        int[] reentered = {0};
        FrameLayout chip = new FrameLayout(activity) {
            @Override public void setBackground(Drawable d) {
                super.setBackground(d);
                if (d instanceof LiveGlassDrawable) { reentered[0]++; hooks.chipBackground(adapter, this); }
            }
        };
        RecyclerView.ViewHolder h = new RecyclerView.ViewHolder(chip);
        bind(h, 0);
        bind(h, 0);
        assertTrue(chip.getBackground() instanceof LiveGlassDrawable);
        assertFalse(((LiveGlassDrawable) chip.getBackground()).nativeDrawable() instanceof LiveGlassDrawable);
        assertTrue(reentered[0] >= 1);
        assertFalse(glass.writing());
    }

    /** DZIF's adapter has no getItem(int) and no data list: only the helper yields its data. */
    @Test public void dataComesFromTheHelperNotFromAdapterFields() {
        OngoingActivityData d = new OngoingActivityData("com.example.timer", false);
        OngoingActivityDataHelper.add(d);
        assertNull(new DataLocator(LiveEligibility.DATA).at(adapter, 0));
        OngoingDataSource source = OngoingDataSource.of(OngoingActivityDataHelper.class);
        assertTrue(source.isStatic());
        assertSame(d, source.at(adapter, 0));
        assertNull("outside getItemCount", source.at(adapter, 1));
        assertNull(source.at(adapter, -1));
        LiveChipHooks listsOnly = new LiveChipHooks(glass);
        RecyclerView.ViewHolder h = holder();
        listsOnly.bindStart(adapter, h, 0);
        adapter.onBindViewHolder(h, 0);
        listsOnly.chipBackground(adapter, h.itemView);
        listsOnly.bindEnd(adapter, h, false);
        assertTrue("without the helper the chip stays native", h.itemView.getBackground() instanceof GradientDrawable);
    }

    /** The data at the bound position changed during the bind: no trustworthy owner, native. */
    @Test public void dataReplacedDuringTheBindStaysNative() {
        OngoingActivityDataHelper.add(new OngoingActivityData("com.example.timer", false));
        adapter.duringBind = () -> OngoingActivityDataHelper.set(0, new OngoingActivityData("com.spotify.music", true));
        RecyclerView.ViewHolder h = holder();
        bind(h, 0);
        assertTrue(h.itemView.getBackground() instanceof GradientDrawable);
    }

    /** A holder that keeps its own data: the item it shows wins over the helper index. */
    public static class DataHolder extends RecyclerView.ViewHolder {
        OngoingActivityData mData;
        DataHolder(View v) { super(v); }
    }

    @Test public void holderDataWinsOverAShiftedHelperIndex() {
        // Helper list holds a media item the chip adapter does not show: index 0 is media.
        OngoingActivityDataHelper.add(new OngoingActivityData("com.spotify.music", true));
        OngoingActivityData timer = new OngoingActivityData("com.sec.android.app.clockpackage", false);
        OngoingActivityDataHelper.add(timer);
        DataHolder h = new DataHolder(new FrameLayout(activity));
        h.mData = timer;
        bind(h, 0);
        assertTrue("the timer chip shown by this holder gets glass", h.itemView.getBackground() instanceof LiveGlassDrawable);
        assertSame(timer, glass.ownerOf(h.itemView));
        // The reverse still follows the holder, but media is now eligible too.
        DataHolder m = new DataHolder(new FrameLayout(activity));
        m.mData = new OngoingActivityData("com.spotify.music", true);
        bind(m, 1);
        assertTrue(m.itemView.getBackground() instanceof LiveGlassDrawable);
        assertSame(m.mData, glass.ownerOf(m.itemView));
    }

    /** L1 is wrapped before attach; the region exists only once attached, with the neutral curve. */
    @Test public void regionIsCreatedOnAttachWithTheNeutralCurveAndRedrawn() {
        OngoingActivityDataHelper.add(new OngoingActivityData("com.example.timer", false));
        RecyclerView.ViewHolder h = holder();
        bind(h, 0);
        LiveGlassDrawable g = (LiveGlassDrawable) h.itemView.getBackground();
        assertNull(g.blur());
        activity.setContentView(h.itemView);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(g.showsGlass());
        assertTrue("requested", g.blur().neutralCurve());
        assertFalse("not applied before a draw sends it", g.blur().curveApplied());
        h.itemView.layout(0, 0, 300, 80);
        // TestCanvas models the drawable contract, not Android's RecordingCanvas. Passing it
        // through View.draw() makes the framework cast the fake canvas to RecordingCanvas.
        g.setBounds(0, 0, h.itemView.getWidth(), h.itemView.getHeight());
        g.draw(new TestCanvas(300, 80));
        FakeBlurDrawable region = env.regions.get(0);
        assertEquals(1, region.draws);
        assertArrayEquals("neutral curve through DZIF's setBlurColorCurve/1", new float[]{0, 0, 0, 255, 0, 255}, region.curve, 0f);
        assertTrue(g.blur().curveApplied());
        assertEquals(LiveSurface.CHIP.spec.blurPx, region.radius);
        // Samsung replaces the background after attach: same wrapper and region come back.
        adapter.setChipBg((android.view.ViewGroup) h.itemView, ColorStateList.valueOf(0xff00aa55));
        hooks.chipBackground(adapter, h.itemView);
        assertSame(g, h.itemView.getBackground());
        assertEquals(1, env.regions.size());
    }

    @Test public void unbalancedBindIsDropped() {
        OngoingActivityDataHelper.add(new OngoingActivityData("com.example.timer", false));
        RecyclerView.ViewHolder h = holder();
        hooks.bindStart(adapter, h, 0);
        adapter.onBindViewHolder(h, 0);
        hooks.chipBackground(adapter, h.itemView);
        hooks.bindEnd(adapter, h, true); // the original threw
        assertTrue(h.itemView.getBackground() instanceof GradientDrawable);
    }
}
