package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import static org.junit.Assert.*;

import com.android.systemui.statusbar.phone.ongoingactivity.OngoingActivityData;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** getDataByIndex resolved strictly, as a static method or on the adapter's / singleton helper. */
public class OngoingDataSourceTest {
    public static class InstanceHelper {
        final List<Object> data = new ArrayList<>();
        public Object getDataByIndex(int i) { return i < data.size() ? data.get(i) : null; }
    }
    public static class SingletonHelper {
        public static final SingletonHelper INSTANCE = new SingletonHelper();
        final List<Object> data = new ArrayList<>();
        public OngoingActivityData getDataByIndex(int i) { return i < data.size() ? (OngoingActivityData) data.get(i) : null; }
    }
    public static class PrimitiveHelper { public int getDataByIndex(int i) { return i; } }
    public static class HoldingAdapter { final InstanceHelper helper = new InstanceHelper(); public int getItemCount() { return helper.data.size(); } }
    public static class AmbiguousAdapter { final InstanceHelper a = new InstanceHelper(), b = new InstanceHelper(); }
    public static class PlainAdapter { }

    @Test public void instanceHelperHeldByTheAdapter() {
        OngoingDataSource s = OngoingDataSource.of(InstanceHelper.class);
        assertFalse(s.isStatic());
        HoldingAdapter adapter = new HoldingAdapter();
        OngoingActivityData d = new OngoingActivityData("com.example", false);
        adapter.helper.data.add(d);
        adapter.helper.data.add("not data");
        assertSame(d, s.at(adapter, 0));
        assertNull("wrong type: no data", s.at(adapter, 1));
        assertNull("beyond getItemCount", s.at(adapter, 2));
        assertNull("two helpers: ambiguous", s.at(new AmbiguousAdapter(), 0));
    }

    @Test public void singletonHelper() {
        OngoingDataSource s = OngoingDataSource.of(SingletonHelper.class);
        OngoingActivityData d = new OngoingActivityData("com.example", false);
        SingletonHelper.INSTANCE.data.add(d);
        assertSame(d, s.at(new PlainAdapter(), 0));
    }

    @Test public void contractMismatchIsAbsent() {
        assertNull(OngoingDataSource.of(PrimitiveHelper.class));
        assertNull(OngoingDataSource.of(PlainAdapter.class));
        assertNull(OngoingDataSource.of(null));
        assertNull(OngoingDataSource.resolve(new ClassLoader(null) { }));
        assertNotNull(OngoingDataSource.resolve(getClass().getClassLoader()));
    }
}
