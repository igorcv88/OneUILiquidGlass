package io.github.igorcv88.oneuiliquidglass.hooks.ongoing;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import io.github.igorcv88.oneuiliquidglass.hooks.Reflect;

/**
 * Classification before any mutation. Every rule denies on doubt: a missing field, an unreadable
 * flag or an unknown native verdict keeps the host native. Media is excluded structurally (the
 * flag of the data bound to that position, Samsung's own isMediaView, the card class and the
 * item's media payload), never by package, colour or text.
 */
public final class LiveEligibility {
    private LiveEligibility() {}

    public enum Verdict {
        OK, NO_DATA, MEDIA_FLAG, MEDIA_VIEW, MEDIA_UNKNOWN, MEDIA_DATA, MEDIA_CLASS, AUTOMATION,
        FLAG_UNREADABLE, NO_PACKAGE, WRONG_CLASS, BRIEF, NOT_TEXT, INTERACTIVE;
        public boolean ok() { return this == OK; }
    }

    static final String DATA = "com.android.systemui.statusbar.phone.ongoingactivity.OngoingActivityData";

    /** L1/L2 data: OngoingActivityData with mIsMediaOngoingData == false, no automation, a package. */
    public static Verdict ongoing(Object data) {
        if (data == null || !DATA.equals(data.getClass().getName())) return Verdict.NO_DATA;
        Boolean media = bool(data, "mIsMediaOngoingData");
        if (media == null) return Verdict.FLAG_UNREADABLE;
        if (media) return Verdict.MEDIA_FLAG;
        Boolean automation = bool(data, "mIsAutomation");
        if (automation == null) return Verdict.FLAG_UNREADABLE;
        // No proven support for automation chips: restricted, not treated as media.
        if (automation) return Verdict.AUTOMATION;
        Object pkg = Reflect.read(data, "mPackageName");
        if (!(pkg instanceof String) || ((String) pkg).isEmpty()) return Verdict.NO_PACKAGE;
        return Verdict.OK;
    }

    /** L2: Samsung's isMediaView(View) must answer false (null = could not ask) and the data must pass. */
    public static Verdict card(Object data, Boolean nativeIsMedia) {
        if (nativeIsMedia == null) return Verdict.MEDIA_UNKNOWN;
        if (nativeIsMedia) return Verdict.MEDIA_VIEW;
        return ongoing(data);
    }

    static final String ONGOING_CARD = "com.samsung.android.app.facewidget.presentation.NowBarOngoingCardView";
    static final String MEDIA_CARD = "com.samsung.android.app.facewidget.presentation.NowBarMediaCardView";

    /**
     * L3: the exact NowBarOngoingCardView class (never the shared base, never a subclass), an item
     * without media payload (the getter must exist), a package, and not a briefing item.
     */
    public static Verdict nowBar(Object card, Object item) {
        if (card == null || !ONGOING_CARD.equals(card.getClass().getName())) return Verdict.WRONG_CLASS;
        for (Class<?> c = card.getClass(); c != null; c = c.getSuperclass())
            if (MEDIA_CARD.equals(c.getName())) return Verdict.MEDIA_CLASS;
        if (item == null) return Verdict.NO_DATA;
        Method mediaGetter = Reflect.method(item.getClass(), "getFaceWidgetMediaData");
        if (mediaGetter == null) return Verdict.MEDIA_UNKNOWN;
        Object media;
        try { media = mediaGetter.invoke(item); } catch (ReflectiveOperationException | RuntimeException e) { return Verdict.MEDIA_UNKNOWN; }
        // A media payload vetoes; its absence alone is not taken as proof (the other rules still apply).
        if (media != null) return Verdict.MEDIA_DATA;
        Method pkgGetter = Reflect.method(item.getClass(), "getNowBarPackage");
        Object pkg = null;
        try { if (pkgGetter != null) pkg = pkgGetter.invoke(item); } catch (ReflectiveOperationException | RuntimeException ignored) { }
        if (!(pkg instanceof String) || ((String) pkg).isEmpty()) return Verdict.NO_PACKAGE;
        if (briefing((String) pkg)) return Verdict.BRIEF;
        return Verdict.OK;
    }
    /** Now Brief and smart-suggestion feeds keep their native card. */
    static boolean briefing(String pkg) {
        String p = pkg.toLowerCase(Locale.ROOT);
        return p.contains("brief") || p.contains("moments") || p.contains("smartsuggestion");
    }

    /**
     * T1: a toast root holding text, with nothing interactive (no clickable descendant): the text
     * toast of ToastFactory, not a custom view.
     */
    public static Verdict toast(View root) {
        if (!(root instanceof ViewGroup)) return Verdict.NOT_TEXT;
        int[] counts = new int[2];
        walk(root, counts, 0);
        if (counts[1] > 0) return Verdict.INTERACTIVE;
        return counts[0] > 0 ? Verdict.OK : Verdict.NOT_TEXT;
    }
    private static void walk(View v, int[] counts, int depth) {
        if (depth > 6) return;
        if (v.isClickable() || v.isLongClickable()) counts[1]++;
        if (v instanceof TextView) counts[0]++;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i), counts, depth + 1);
        }
    }

    /** A boolean field read strictly by name and type; null if absent or of another type. */
    static Boolean bool(Object o, String name) {
        Field f = Reflect.field(o.getClass(), name);
        if (f == null || f.getType() != boolean.class) return null;
        try { return f.getBoolean(o); } catch (IllegalAccessException | RuntimeException e) { return null; }
    }
}
