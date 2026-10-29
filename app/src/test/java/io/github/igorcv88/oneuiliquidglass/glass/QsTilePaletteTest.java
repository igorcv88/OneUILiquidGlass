package io.github.igorcv88.oneuiliquidglass.glass;

import org.junit.Test;
import static org.junit.Assert.*;
import static io.github.igorcv88.oneuiliquidglass.glass.QsScene.Tone.*;
import static io.github.igorcv88.oneuiliquidglass.glass.QuickSettingsGlass.Kind.*;

public class QsTilePaletteTest {
    @Test public void lightBackdropHasSeparateActiveInactiveBackgroundsAndInk() {
        QsStyle style = new QsStyle(key -> null);
        assertEquals(0xffcdd7e5,style.tileBackground(ACTIVE,LIGHT_SCENE,0xffffffff));
        assertEquals(0,style.tileBackground(IDLE,LIGHT_SCENE,0x00234b60));
        assertEquals(0xff20242e,style.tileInk(ACTIVE,LIGHT_SCENE,0xffffffff));
        assertEquals(0x80343a46,style.tileInk(IDLE,LIGHT_SCENE,0x80ffffff));
    }

    @Test public void darkBackdropAndUnknownRemainNativeByDefault() {
        QsStyle style = new QsStyle(key -> null);
        for (QuickSettingsGlass.Kind kind : QuickSettingsGlass.Kind.values()) {
            assertEquals(kind == IDLE ? 0 : 0x99ffffff,style.tileBackground(kind,DARK_SCENE,0x99ffffff));
            assertEquals(0x99ffffff,style.tileBackground(kind,UNKNOWN,0x99ffffff));
            assertEquals(0xffeeeeee,style.tileInk(kind,DARK_SCENE,0xffeeeeee));
        }
    }

    @Test public void noDiscKeepsBackgroundAndOnlyTintsVerifiedMonochromeInk() {
        QsStyle style = new QsStyle(key -> null);
        for (int nativeColor : new int[]{0,0x00234b60,0xffffffff}) {
            assertEquals(nativeColor,style.tileBackground(NONE,LIGHT_SCENE,nativeColor));
            assertEquals(nativeColor,style.tileBackground(CONTAINER,LIGHT_SCENE,nativeColor));
        }
        // QsTextHooks may call tileInk(NONE) only for a Painter with a bound QuickTile model.
        assertEquals(0xff343a46,style.tileInk(NONE,LIGHT_SCENE,0xffffffff));
        assertEquals(0x80343a46,style.tileInk(NONE,LIGHT_SCENE,0x80ffffff));
        assertEquals(0xff12a0ef,style.tileInk(NONE,LIGHT_SCENE,0xff12a0ef));
        assertEquals(0xffffffff,style.tileInk(NONE,DARK_SCENE,0xffffffff));
        assertEquals(0xffffffff,style.tileInk(NONE,UNKNOWN,0xffffffff));
        assertEquals(0xffffffff,new QsStyle(k -> k.equals("qs.tilelight") ? "0" : null)
                .tileInk(NONE,LIGHT_SCENE,0xffffffff));
    }

    @Test public void dzifInactiveSentinelIsDifferentFromTrulyDiscLessAndUnbound() {
        assertEquals(IDLE,QuickSettingsGlass.tileDiscKind(NONE,0x00234b60,0x00234b60,true));
        assertEquals(NONE,QuickSettingsGlass.tileDiscKind(NONE,0,0,true));
        assertEquals(NONE,QuickSettingsGlass.tileDiscKind(NONE,0x00234b60,0x00234b60,false));
        assertEquals(NONE,QuickSettingsGlass.tileDiscKind(NONE,0x00234b60,0,true));
        assertEquals(ACTIVE,QuickSettingsGlass.tileDiscKind(ACTIVE,0xffffffff,0x00234b60,true));
    }

    @Test public void customLightAndDarkPalettesAreIndependentAndRejectColoredIcons() {
        QsStyle style = new QsStyle(k -> {
            switch (k) {
                case "qs.tilelighton": return "445566";
                case "qs.tilelightoffalpha": return ".50";
                case "qs.tiledark": return "1";
                case "qs.tiledarkon": return "ddccbb";
                case "qs.tiledarkonink": return "223344";
                default: return null;
            }
        });
        assertEquals(0xff445566,style.tileBackground(ACTIVE,LIGHT_SCENE,0xffffffff));
        assertEquals(0,style.tileBackground(IDLE,LIGHT_SCENE,0x00234b60));
        assertEquals(0xffddccbb,style.tileBackground(ACTIVE,DARK_SCENE,0xffffffff));
        assertEquals(0xff223344,style.tileInk(ACTIVE,DARK_SCENE,0xffffffff));
        assertEquals(0xff12a0ef,style.tileInk(ACTIVE,DARK_SCENE,0xff12a0ef));
        assertEquals(0,style.tileInk(ACTIVE,DARK_SCENE,0));
    }
    @Test public void blackActiveDiscDoesNotForceItsMonochromeIconToRemainBlack() {
        QsStyle style = new QsStyle(k -> {
            if (k.equals("qs.tilelighton")) return "000000";
            if (k.equals("qs.tilelightonink")) return "ffffff";
            return null;
        });
        assertEquals(0xff000000,style.tileBackground(ACTIVE,LIGHT_SCENE,0xffffffff));
        assertEquals(0xffffffff,style.tileInk(ACTIVE,LIGHT_SCENE,0xff000000));
        assertEquals(0xff000000,style.tileInk(ACTIVE,DARK_SCENE,0xff000000));
    }

    @Test public void disablingTheLightPaletteLeavesNativeColouredDiscs() {
        QsStyle disabled = new QsStyle(k -> k.equals("qs.tilelight") ? "0" : null);
        assertEquals(0xffffffff,disabled.tileBackground(ACTIVE,LIGHT_SCENE,0xffffffff));
        assertEquals(0xffffffff,disabled.tileInk(IDLE,LIGHT_SCENE,0xffffffff));
        assertNotEquals(new QsStyle(k -> null),disabled);
    }

    @Test public void themeParkOffHasNoInactiveDiscInEitherSceneByDefault() {
        QsStyle style = new QsStyle(k -> null);
        assertEquals(0, style.tileBackground(IDLE, LIGHT_SCENE, 0xff999999));
        assertEquals(0, style.tileBackground(IDLE, DARK_SCENE, 0xff999999));
        assertEquals(0xff999999, style.tileBackground(CONTAINER, LIGHT_SCENE, 0xff999999));
        assertEquals(0xff999999, style.tileBackground(NONE, LIGHT_SCENE, 0xff999999));
    }

    @Test public void inactiveDiscCanBeRestoredWithIndependentOpacityAndColors() {
        QsStyle style = new QsStyle(k -> {
            switch (k) {
                case "qs.hideoffdiscs": return "0";
                case "qs.tilelightoffalpha": return ".40";
                case "qs.tiledark": return "1";
                case "qs.tiledarkoffalpha": return ".20";
                default: return null;
            }
        });
        assertEquals(0x66202c3c, style.tileBackground(IDLE, LIGHT_SCENE, 0xff999999));
        assertEquals(0x33ffffff, style.tileBackground(IDLE, DARK_SCENE, 0xff999999));
    }
    @Test public void rgbaPaletteAndSlidersPreserveTheChosenAlphaInEachScene() {
        QsStyle style=new QsStyle(k -> {
            switch(k) {
                case "qs.tilelighton": return "e6f1fa80";
                case "qs.tilelightonink": return "20242eaa";
                case "qs.sliderlightcolor": return "ffffffa0";
                case "qs.sliderdarkcolor": return "e6eefcff";
                default: return null;
            }
        });
        assertEquals(0x80e6f1fa,style.tileBackground(ACTIVE,LIGHT_SCENE,0xffffffff));
        assertEquals(0xaa20242e,style.tileInk(ACTIVE,LIGHT_SCENE,0xffffffff));
        assertEquals(0xa0ffffff,style.sliderLightColor);
        assertEquals(0xffe6eefc,style.sliderDarkColor);
    }
}
