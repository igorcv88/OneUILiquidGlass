package io.github.igorcv88.oneuiliquidglass.config;

import static org.junit.Assert.*;

import java.util.Locale;
import org.junit.Test;

/** A multiplier is shown as a percentage of 1 and typed the same way; storage is unchanged. */
public class ValueFormatPercentTest {
    private static final Locale BR = Locale.forLanguageTag("pt-BR"), EN = Locale.US;
    private static final Controls.Control BLUR = Controls.get("sf.hublur");

    @Test
    public void showsTheMultiplierAsAPercentage() {
        assertEquals("100 %", ValueFormat.percentOfMultiplier(1f));
        assertEquals("65 %", ValueFormat.percentOfMultiplier(0.65f));
        assertEquals("115 %", ValueFormat.percentOfMultiplier(1.15f, EN));
        assertEquals("0 %", ValueFormat.percentOfMultiplier(0f, BR));
    }

    @Test
    public void rawValueToEditorNumber() {
        assertEquals("65", ValueFormat.multiplierToEdit("0.65", BR));
        assertEquals("100", ValueFormat.multiplierToEdit("1", EN));
    }

    @Test
    public void editorAcceptsThePlainNumberWithOrWithoutThePercentSign() {
        assertEquals("0.65", ValueFormat.multiplierFromEdit(BLUR, "65"));
        assertEquals("0.65", ValueFormat.multiplierFromEdit(BLUR, "65 %"));
        assertEquals("0.65", ValueFormat.multiplierFromEdit(BLUR, "65%"));
        assertEquals("1", ValueFormat.multiplierFromEdit(BLUR, " 100 % "));
        assertEquals("1.15", ValueFormat.multiplierFromEdit(BLUR, "115"));
    }

    @Test
    public void editorAcceptsADecimalCommaAndSnapsToTheRegistryStep() {
        assertEquals("0.66", ValueFormat.multiplierFromEdit(BLUR, "65,5"));
    }

    @Test
    public void editorRejectsOutOfRangeAndNonNumbers() {
        double max = SettingsRegistry.get("sf.hublur").max;
        try {
            ValueFormat.multiplierFromEdit(BLUR, String.valueOf((int) (max * 100) + 100));
            fail("above the range");
        } catch (IllegalArgumentException expected) {
            // out of range
        }
        try {
            ValueFormat.multiplierFromEdit(BLUR, "abc");
            fail("not a number");
        } catch (IllegalArgumentException expected) {
            // not a number
        }
    }
}
