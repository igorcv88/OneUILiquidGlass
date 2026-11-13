package io.github.igorcv88.oneuiliquidglass.live;

import static org.junit.Assert.*;

import io.github.igorcv88.oneuiliquidglass.config.GlassMaterial;
import io.github.igorcv88.oneuiliquidglass.config.Mode;
import java.util.*;
import org.junit.Test;

/** A saved choice, a written property and a compiled shader are different facts. */
public class MaterialStatusTest {
    private static final Set<String> NONE = Collections.emptySet();
    private static final Set<String> PENDING = new HashSet<>(Arrays.asList("sf.huclear", "sf.kgclear"));
    private static final CompositorValues.Outcome OK = CompositorValues.Outcome.CONFIRMED;

    @Test
    public void aChoiceNeitherSelectorAgreesWithIsMixed() {
        assertEquals(MaterialStatus.MIXED, MaterialStatus.of(null, Mode.DYNAMIC_LENS, NONE, false, OK));
    }

    @Test
    public void clearWithoutTheLensIsSavedButUnavailableEvenWhenAValueIsPending() {
        assertEquals(MaterialStatus.UNAVAILABLE,
                MaterialStatus.of(GlassMaterial.CLEAR, Mode.STANDARD, NONE, true, CompositorValues.Outcome.NONE));
        assertEquals(MaterialStatus.UNAVAILABLE,
                MaterialStatus.of(GlassMaterial.CLEAR, Mode.CAPTURE, PENDING, false, OK));
    }

    @Test
    public void aSavedSelectorTheCompileDoesNotHaveIsPendingNotApplied() {
        assertEquals(MaterialStatus.PENDING,
                MaterialStatus.of(GlassMaterial.CLEAR, Mode.DYNAMIC_LENS, PENDING, false, OK));
        assertEquals(MaterialStatus.PENDING,
                MaterialStatus.of(GlassMaterial.FROSTED, Mode.DYNAMIC_LENS, Collections.singleton("sf.kgclear"), false, OK));
        // Only the selectors decide this; another waiting value is not the material's business.
        assertEquals(MaterialStatus.APPLIED,
                MaterialStatus.of(GlassMaterial.CLEAR, Mode.DYNAMIC_LENS, Collections.singleton("sf.hcfold"), false, OK));
    }

    @Test
    public void matchingPropertiesWithoutAVerifiedRestartAreNotConfirmed() {
        assertEquals(MaterialStatus.UNCONFIRMED,
                MaterialStatus.of(GlassMaterial.CLEAR, Mode.DYNAMIC_LENS, NONE, true, OK));
        for (CompositorValues.Outcome o : new CompositorValues.Outcome[] {
                CompositorValues.Outcome.NOT_LOADED, CompositorValues.Outcome.CHANGED})
            assertEquals(o.name(), MaterialStatus.UNCONFIRMED,
                    MaterialStatus.of(GlassMaterial.CLEAR, Mode.DYNAMIC_LENS, NONE, false, o));
    }

    @Test
    public void onlyAVerifiedCompileWithNothingWaitingIsApplied() {
        assertEquals(MaterialStatus.APPLIED,
                MaterialStatus.of(GlassMaterial.CLEAR, Mode.DYNAMIC_LENS, NONE, false, OK));
        assertEquals(MaterialStatus.APPLIED,
                MaterialStatus.of(GlassMaterial.FROSTED, Mode.DYNAMIC_LENS, NONE, false, CompositorValues.Outcome.NONE));
    }

    @Test
    public void frostedWithoutTheCompositorNeedsNothingToApply() {
        assertEquals(MaterialStatus.NOT_REQUIRED,
                MaterialStatus.of(GlassMaterial.FROSTED, Mode.STANDARD, NONE, true, CompositorValues.Outcome.NONE));
        // A saved selector is still pending: the compositor would have to compile Frosted again.
        assertEquals(MaterialStatus.PENDING,
                MaterialStatus.of(GlassMaterial.FROSTED, Mode.STANDARD, PENDING, false, CompositorValues.Outcome.NONE));
    }
}
