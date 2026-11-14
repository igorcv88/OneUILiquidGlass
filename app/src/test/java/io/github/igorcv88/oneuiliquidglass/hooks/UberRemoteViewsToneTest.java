package io.github.igorcv88.oneuiliquidglass.hooks;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE)
public final class UberRemoteViewsToneTest {
    private final Context base = RuntimeEnvironment.getApplication();

    /** Mirrors the published RemoteViews hierarchy without the Uber APK or its private content. */
    private static final class ExpandableNotificationRow extends FrameLayout {
        ExpandableNotificationRow(Context c) { super(c); }
    }
    private final class Fixture {
        final Map<Integer,String> ids = new HashMap<>();
        int nextId=0x7f300001;
        final Resources resources = new Resources(base.getAssets(),
                base.getResources().getDisplayMetrics(), base.getResources().getConfiguration()) {
            @Override public String getResourcePackageName(int id) throws NotFoundException {
                if (ids.containsKey(id)) return "com.ubercab";
                return super.getResourcePackageName(id);
            }
            @Override public String getResourceEntryName(int id) throws NotFoundException {
                String s=ids.get(id);
                return s == null ? super.getResourceEntryName(id) : s;
            }
        };
        final Context ctx = new ContextWrapper(base) {
            @Override public Resources getResources() { return resources; }
            @Override public String getPackageName() { return "com.ubercab"; }
        };
        final ExpandableNotificationRow row=new ExpandableNotificationRow(base);
        <T extends View> T id(T v,String name) {
            int n=nextId++;
            ids.put(n,name);
            v.setId(n);
            return v;
        }
        FrameLayout accessory() {
            FrameLayout a=id(new FrameLayout(ctx),"ub__ooa_layout_rider_trip_expanded_accessory_container");
            row.addView(a);return a;
        }
        FrameLayout progress() {
            FrameLayout a=id(new FrameLayout(ctx),"ub__ooa_layout_rider_trip_expanded_progress_container");
            row.addView(a);return a;
        }
    }

    @Test public void exactPublisherAndIdsOnly() {
        assertEquals(UberRemoteViewsTone.Role.PLATE,UberRemoteViewsTone.classify(
                "com.ubercab","ub__ooa_element_expanded_vehicle_plate"));
        assertEquals(UberRemoteViewsTone.Role.MODEL,UberRemoteViewsTone.classify(
                "com.ubercab","ub__ooa_element_expanded_vehicle_model"));
        assertEquals(UberRemoteViewsTone.Role.PROGRESS_FILLED,UberRemoteViewsTone.classify(
                "com.ubercab","progress_bar_filled"));
        assertEquals(UberRemoteViewsTone.Role.PROGRESS_REMAINING,UberRemoteViewsTone.classify(
                "com.ubercab","progress_bar_not_filled"));
        assertEquals(UberRemoteViewsTone.Role.PROGRESS_INDICATOR,UberRemoteViewsTone.classify(
                "com.ubercab","progress_bar_indicator"));
        assertEquals(UberRemoteViewsTone.Role.PROGRESS_END,UberRemoteViewsTone.classify(
                "com.ubercab","progress_bar_end_image"));
        assertEquals(UberRemoteViewsTone.Role.NONE,UberRemoteViewsTone.classify(
                "com.other","progress_bar_filled"));
        assertEquals(UberRemoteViewsTone.Role.NONE,UberRemoteViewsTone.classify(
                "com.ubercab","ub__ooa_element_expanded_driver_image"));
        assertEquals(UberRemoteViewsTone.Role.NONE,UberRemoteViewsTone.classify(
                "com.ubercab","ub__ooa_element_expanded_vehicle_image"));
    }

    @Test public void onlyVehicleLabelsInKnownAccessoryBypassPrivateSurface() {
        Fixture f=new Fixture();FrameLayout accessory=f.accessory();
        accessory.setBackground(new ColorDrawable(0x50ffffff));
        TextView plate=f.id(new TextView(f.ctx),"ub__ooa_element_expanded_vehicle_plate");
        TextView model=f.id(new TextView(f.ctx),"ub__ooa_element_expanded_vehicle_model");
        TextView other=f.id(new TextView(f.ctx),"other_white_badge");
        plate.setTextColor(0xfffafaff);model.setTextColor(0xfffafaff);other.setTextColor(Color.WHITE);
        accessory.addView(plate);accessory.addView(model);accessory.addView(other);
        assertFalse(Foreground.customSurface(plate));
        assertTrue(Foreground.textEligible(plate));assertTrue(Foreground.textEligible(model));
        assertFalse(Foreground.textEligible(other));
        ArrayList<View> tracked=new ArrayList<>();
        Foreground.apply(f.row,true,true,tracked);
        assertEquals(0xff050500,plate.getCurrentTextColor());
        assertEquals(0xff050500,model.getCurrentTextColor());
        assertEquals(Color.WHITE,other.getCurrentTextColor());
        assertEquals(2,tracked.size());
        Foreground.apply(f.row,false,true,tracked);
        assertEquals(0xfffafaff,plate.getCurrentTextColor());
        assertEquals(0xfffafaff,model.getCurrentTextColor());

        // A label with its own coloured badge must not be overridden.
        plate.setBackground(new ColorDrawable(0xff173d94));
        assertFalse(Foreground.textEligible(plate));
    }

    @Test public void progressSegmentsKeepShapeAndReturnToNativeTint() {
        Fixture f=new Fixture();FrameLayout progress=f.progress();
        FrameLayout filled=f.id(new FrameLayout(f.ctx),"progress_bar_filled");
        FrameLayout remainder=f.id(new FrameLayout(f.ctx),"progress_bar_not_filled");
        GradientDrawable rounded=new GradientDrawable();rounded.setColor(0xfffafaff);rounded.setCornerRadius(9f);
        filled.setBackground(rounded);
        remainder.setBackground(new ColorDrawable(0xffeeeeee));
        progress.addView(filled);progress.addView(remainder);
        ArrayList<View> tracked=new ArrayList<>();
        Foreground.apply(f.row,true,true,tracked);
        assertEquals(0xff050500,filled.getBackgroundTintList().getDefaultColor());
        assertEquals(0xff111111,remainder.getBackgroundTintList().getDefaultColor());
        assertSame(rounded,filled.getBackground());
        assertEquals(0xfffafaff,rounded.getColor().getDefaultColor());
        assertEquals(2,tracked.size());
        assertNull(Foreground.drift(tracked));
        Foreground.apply(f.row,false,true,tracked);
        assertNull(filled.getBackgroundTintList());
        assertNull(remainder.getBackgroundTintList());
    }

    @Test public void originalPublisherTintAndDynamicRebindAreRespected() {
        Fixture f=new Fixture();FrameLayout progress=f.progress();
        FrameLayout filled=f.id(new FrameLayout(f.ctx),"progress_bar_filled");
        ColorDrawable original=new ColorDrawable(Color.WHITE);
        filled.setBackground(original);
        ColorStateList nativeTint=ColorStateList.valueOf(0xffdedede);
        filled.setBackgroundTintList(nativeTint);
        progress.addView(filled);
        ArrayList<View> tracked=new ArrayList<>();
        Foreground.apply(f.row,true,true,tracked);
        assertEquals(0xff212121,filled.getBackgroundTintList().getDefaultColor());
        // Rebind replaces the drawable while we own the tint; ownership cannot leak to new art.
        filled.setBackground(new ColorDrawable(0xffddcc11));
        assertEquals("uber-rebind",Foreground.drift(tracked));
        Foreground.apply(f.row,true,true,tracked);
        assertSame(nativeTint,filled.getBackgroundTintList());
        Foreground.apply(f.row,false,true,tracked);
        assertSame(nativeTint,filled.getBackgroundTintList());
    }

    @Test public void originalAccentsAndGradientsRemainUnmodified() {
        Fixture f=new Fixture();FrameLayout progress=f.progress();
        FrameLayout colored=f.id(new FrameLayout(f.ctx),"progress_bar_filled");
        colored.setBackground(new ColorDrawable(0xff1279f4));
        FrameLayout gradient=f.id(new FrameLayout(f.ctx),"progress_bar_not_filled");
        gradient.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xffffffff,0xff000000}));
        progress.addView(colored);progress.addView(gradient);
        Foreground.apply(f.row,true,true,new ArrayList<>());
        assertNull(colored.getBackgroundTintList());
        assertNull(gradient.getBackgroundTintList());
    }

    @Test public void onlyTintedOrVerifiedMonochromeProgressImagesMayChange() {
        Fixture f=new Fixture();FrameLayout progress=f.progress();
        ImageView endpoint=f.id(new ImageView(f.ctx),"progress_bar_end_image");
        endpoint.setImageDrawable(new ColorDrawable(Color.WHITE));
        endpoint.setImageTintList(ColorStateList.valueOf(0xffffffff));
        ImageView indicator=f.id(new ImageView(f.ctx),"progress_bar_indicator");
        Bitmap artwork=Bitmap.createBitmap(24,24,Bitmap.Config.ARGB_8888);
        artwork.eraseColor(0xff1180ef);
        indicator.setImageDrawable(new BitmapDrawable(f.ctx.getResources(),artwork));
        ImageView vehicle=f.id(new ImageView(f.ctx),"ub__ooa_element_expanded_vehicle_image");
        vehicle.setImageDrawable(new ColorDrawable(0xffffffff));
        progress.addView(endpoint);progress.addView(indicator);progress.addView(vehicle);
        Foreground.apply(f.row,true,true,new ArrayList<>());
        assertEquals(0xff000000,endpoint.getImageTintList().getDefaultColor());
        assertNull(indicator.getImageTintList());
        assertNull(vehicle.getImageTintList());
        Foreground.apply(f.row,false,true,new ArrayList<>());
        assertEquals(0xffffffff,endpoint.getImageTintList().getDefaultColor());
        artwork.recycle();
    }

    @Test public void darkGlassCanInvertDarkMonochromeSegments() {
        Fixture f=new Fixture();FrameLayout progress=f.progress();
        FrameLayout remaining=f.id(new FrameLayout(f.ctx),"progress_bar_not_filled");
        remaining.setBackground(new ColorDrawable(0xff202020));
        progress.addView(remaining);
        Foreground.apply(f.row,true,false,new ArrayList<>());
        assertEquals(0xffdfdfdf,remaining.getBackgroundTintList().getDefaultColor());
        Foreground.apply(f.row,false,false,new ArrayList<>());
        assertNull(remaining.getBackgroundTintList());
    }
}
