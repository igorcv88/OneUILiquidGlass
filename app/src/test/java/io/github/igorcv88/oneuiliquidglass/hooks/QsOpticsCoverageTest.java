package io.github.igorcv88.oneuiliquidglass.hooks;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class QsOpticsCoverageTest {
    @Test public void selectionAndBindingNeverCountAsDrawingAndFailureRestoresFallback() {
        QsOpticsCoverage c=new QsOpticsCoverage((e,d)->{}); c.begin();
        QsOpticsCoverage.Token a=c.select("slider"), b=c.select("container");
        c.stage(a,"registered","element"); c.stage(a,"bound","node");
        assertTrue(c.snapshot("test").contains("drawn=0 fallback=2"));
        c.stage(a,"attempt","hardware"); c.stage(a,"drawn","agsl");
        assertTrue(c.snapshot("test").contains("drawn=1 fallback=1"));
        c.stage(a,"fallback","shader");
        assertTrue(c.snapshot("test").contains("drawn=0 fallback=2"));
        assertFalse(b.bound);
    }
    @Test public void resetIsPerSessionAndRepeatedDrawDoesNotSpamPaths() {
        List<String> logs=new ArrayList<>();QsOpticsCoverage c=new QsOpticsCoverage((e,d)->logs.add(d));
        c.begin();QsOpticsCoverage.Token a=c.select("media-empty");
        for(int i=0;i<1000;i++)c.stage(a,"drawn","agsl");
        assertEquals(2,logs.size());
        c.begin();assertTrue(c.snapshot("test").contains("selected=0"));
        assertTrue(c.snapshot("test").contains("session=2"));
    }
    @Test public void missingConstructorAndExcessCandidatesAreReported() {
        QsOpticsCoverage c=new QsOpticsCoverage((e,d)->{});
        for(int i=0;i<600;i++)c.select("unknown");
        assertTrue(c.snapshot("test").contains("selected=512 registered=0 bound=0"));
        assertTrue(c.snapshot("test").contains("evicted=88"));
    }
    /**
     * R4 trace: rimWithoutOptics always equalled rimAttenuated. A lit box recomposes with a new
     * token whose border is attenuated while the drawing stays with the bound token of the same
     * box: that box has optics. Only a box with an attenuated outline and no drawn token counts.
     */
    @Test public void outlineInvariantIsCountedPerBoxNotPerRecompositionToken() {
        QsOpticsCoverage c=new QsOpticsCoverage((e,d)->{}); c.begin();
        Object box=new Object(), other=new Object();
        QsOpticsCoverage.Token first=c.select("container");
        first.box=new java.lang.ref.WeakReference<>(box); first.rimAttenuated=false;
        c.stage(first,"registered","e"); c.stage(first,"bound","n"); c.stage(first,"drawn","agsl");
        // Recomposition after the box lit: new token, attenuated border, element unchanged (never bound).
        for(int i=0;i<3;i++){
            QsOpticsCoverage.Token again=c.select("container");
            again.box=new java.lang.ref.WeakReference<>(box); again.rimAttenuated=true;
            c.stage(again,"registered","e");
        }
        String s=c.snapshot("stabilized");
        assertTrue(s, s.contains("boxes=1 rimAttenuated=1 rimWithoutOptics=0 rimAttenuatedTokens=3"));
        // A different box attenuated whose optics never drew is a real violation.
        QsOpticsCoverage.Token bad=c.select("container");
        bad.box=new java.lang.ref.WeakReference<>(other); bad.rimAttenuated=true;
        c.stage(bad,"bound","n"); c.stage(bad,"fallback","shader");
        s=c.snapshot("stabilized");
        assertTrue(s, s.contains("boxes=2 rimAttenuated=2 rimWithoutOptics=1"));
        // Its optics draw later in the session: the box is covered again.
        c.stage(bad,"drawn","agsl");
        assertTrue(c.snapshot("stabilized").contains("rimWithoutOptics=0"));
    }

}
