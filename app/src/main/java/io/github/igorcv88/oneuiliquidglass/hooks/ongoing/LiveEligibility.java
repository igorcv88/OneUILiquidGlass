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
 * flag keeps the host native. Media has no extra veto: a verified view class and compatible
 * native background structure decide whether glass can be installed.
 */
public final class LiveEligibility {
    private LiveEligibility() {}

    public enum Verdict {
        OK, NO_DATA, AUTOMATION,
        FLAG_UNREADABLE, NO_PACKAGE, WRONG_CLASS, BRIEF, NOT_TEXT, INTERACTIVE;
        public boolean ok() { return this == OK; }
    }

    static final String DATA = "com.android.systemui.statusbar.phone.ongoingactivity.OngoingActivityData";

    /** L1/L2 data: OngoingActivityData with no automation and a package. */
    public static Verdict ongoing(Object data) {
        if (data == null || !DATA.equals(data.getClass().getName())) return Verdict.NO_DATA;
        Boolean automation = bool(data, "mIsAutomation");
        if (automation == null) return Verdict.FLAG_UNREADABLE;
        // No proven support for automation chips: restricted, not treated as media.
        if (automation) return Verdict.AUTOMATION;
        Object pkg = Reflect.read(data, "mPackageName");
        if (!(pkg instanceof String) || ((String) pkg).isEmpty()) return Verdict.NO_PACKAGE;
        return Verdict.OK;
    }

    /** L2: action/album layouts are handled by the fill selector, not a media classifier. */
    public static Verdict card(Object data) { return ongoing(data); }

    static final String ONGOING_CARD = "com.samsung.android.app.facewidget.presentation.NowBarOngoingCardView";
    /**
     * L3: the exact NowBarOngoingCardView class (never the shared base or NowBarMediaCardView),
     * a package and a supported item. The DZIF Now Brief package is an ongoing card as well;
     * other briefing/suggestion providers stay native until their layouts are verified.
     */
    public static Verdict nowBar(Object card, Object item) {
        if (card == null || !ONGOING_CARD.equals(card.getClass().getName())) return Verdict.WRONG_CLASS;
        if (item == null) return Verdict.NO_DATA;
        Method pkgGetter = Reflect.method(item.getClass(), "getNowBarPackage");
        Object pkg = null;
        try { if (pkgGetter != null) pkg = pkgGetter.invoke(item); } catch (ReflectiveOperationException | RuntimeException ignored) { }
        if (!(pkg instanceof String) || ((String) pkg).isEmpty()) return Verdict.NO_PACKAGE;
        if (briefing((String) pkg) && !nowBrief((String) pkg)) return Verdict.BRIEF;
        return Verdict.OK;
    }
    /** Exact DZIF Now Brief package; do not widen this to other unverified briefing cards. */
    static boolean nowBrief(String pkg) {
        return "com.samsung.android.app.moments".equalsIgnoreCase(pkg);
    }
    /** Unverified briefing and smart-suggestion feeds keep their native card. */
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
