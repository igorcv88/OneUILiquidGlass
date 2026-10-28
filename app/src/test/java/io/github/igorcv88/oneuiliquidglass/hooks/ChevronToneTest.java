package io.github.igorcv88.oneuiliquidglass.hooks;

import android.graphics.*;
import android.graphics.drawable.ColorDrawable;
import android.widget.ImageView;
import android.content.res.ColorStateList;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=34)
public class ChevronToneTest {
    ImageView icon;List<android.view.View> tracked;
    @Before public void setup(){icon=new ImageView(RuntimeEnvironment.getApplication());
        int id=icon.getResources().getIdentifier("expand_button_icon","id","android");assertTrue(id!=0);
        icon.setId(id);icon.setImageDrawable(new ColorDrawable(0xffffffff));tracked=new ArrayList<>();}
    @After public void restore(){Foreground.apply(icon,false,true,null);}
    @Test public void drawableFilterWithoutTintListIsRecolouredAndRestored(){
        ColorFilter original=new PorterDuffColorFilter(0xffffffff,PorterDuff.Mode.SRC_IN);
        icon.getDrawable().setColorFilter(original);icon.setImageAlpha(201);
        Foreground.apply(icon,true,true,tracked);
        assertEquals(1,tracked.size());assertEquals(0xff000000,icon.getImageTintList().getDefaultColor());
        Foreground.apply(icon,false,true,null);
        assertNull(icon.getImageTintList());assertSame(original,icon.getDrawable().getColorFilter());assertEquals(201,icon.getImageAlpha());
    }
    @Test public void tintStateListSurvivesNativeRebindAndRestore(){
        ColorStateList original=ColorStateList.valueOf(0xffeeeeee);icon.setImageTintList(original);
        Foreground.apply(icon,true,true,tracked);
        ColorStateList rebound=ColorStateList.valueOf(0xffdddddd);icon.setImageTintList(rebound);
        Foreground.apply(icon,true,true,tracked);assertEquals(0xff222222,icon.getImageTintList().getDefaultColor());
        Foreground.apply(icon,false,true,null);assertSame(rebound,icon.getImageTintList());
    }
    @Test public void replacementDrawableKeepsItsOwnOriginalFilter(){
        Foreground.apply(icon,true,true,tracked);
        ColorDrawable replacement=new ColorDrawable(0xffffffff);
        ColorFilter nativeFilter=new PorterDuffColorFilter(0xffeeeeee,PorterDuff.Mode.SRC_IN);
        replacement.setColorFilter(nativeFilter);
        Foreground.beforeChevronDrawable(icon,replacement);icon.setImageDrawable(replacement);
        Foreground.apply(icon,true,true,tracked);Foreground.apply(icon,false,true,null);
        assertSame(nativeFilter,icon.getDrawable().getColorFilter());
    }
    @Test public void arbitraryAppImageIsNotRecoloured(){
        icon.setId(android.view.View.NO_ID);Foreground.apply(icon,true,true,tracked);
        assertTrue(tracked.isEmpty());assertNull(icon.getImageTintList());assertNull(icon.getDrawable().getColorFilter());
    }
}
