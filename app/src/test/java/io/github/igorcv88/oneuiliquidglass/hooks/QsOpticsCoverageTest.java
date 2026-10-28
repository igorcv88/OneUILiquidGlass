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
}
