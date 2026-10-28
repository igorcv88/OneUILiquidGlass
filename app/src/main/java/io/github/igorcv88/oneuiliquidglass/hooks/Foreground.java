package io.github.igorcv88.oneuiliquidglass.hooks;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The text and icon tone of a card that changed material style (ShadeTone.materialFor): over light
 * glass the theme's light text turns dark, over dark glass the theme's dark text turns light.
 * Standard template text and custom text on a clear path to the notification row qualify;
 * custom text with a background of its own is left alone. Icons retain their template-only rules.
 * Among those, only the theme's text tone
 * (near-neutral greys: primary, secondary, time) changes; accent text, coloured spans, app icons
 * and action colours are left alone. Each grey is mirrored (255 - value, alpha kept), so the primary/secondary hierarchy holds.
 * Applied to the inflated views, never the RemoteViews; switching back restores each view's own
 * colours. A view whose content set a new colour since is taken as it is. Main thread only.
 *
 * <p>Replaces the round-36 text halo, a shadow behind every glyph that read as a dirty double
 * outline on the device (25/10).</p>
 */
final class Foreground {
    private Foreground() {}
    /** Views whose tone this class changed: what they had, and what they were given. */
    private static final WeakHashMap<Object, Rec> swapped = new WeakHashMap<>();
    static final class Rec {
        Object saved;
        int applied;
        /** The theme's text tone when the view was swapped (what a colour set later is compared with). */
        boolean lightText;
        boolean custom;
        Rec(Object saved) { this.saved = saved; }
    }
    /** Whether a child row draws its own glass (and so its own tone); set by HeadsUpHooks. */
    static java.util.function.Predicate<View> ownsTone = v -> true;
    /** Set while this class changes colours itself: its own calls pass the interception untouched. */
    static boolean applying;
    /** Colour sets on swapped views redirected since the last read (interceptions()). */
    private static int intercepted;
    static int interceptions() { int n = intercepted; intercepted = 0; return n; }
    /** Whether any view carries a swapped tone (the hooks' fast path). */
    static boolean tracking() { return !swapped.isEmpty(); }
    /**
     * TextView.setTextColor / ImageView.setImageTintList on a view this class swapped, from SystemUI
     * (a rebind, a heads-up-to-shade transformation, an animated group title). Without this the
     * module repaired the colour in the next pre-draw and SystemUI set its own again in the next
     * frame: one view of a group row was "repaired" every 16 ms (device log, 25/10), and whichever
     * write came last was on screen. Now a colour of the theme's tone is taken as the new original
     * and the mirrored one is set instead, in the same call; any other colour (an accent) releases
     * the view. Returns the colour to set instead, or null to let the call through.
     */
    static Integer redirect(Object key, int color, Map<Object, Rec> swapped, java.util.function.IntFunction<Object> saver) {
        Rec r = swapped.get(key);
        if (r == null) return null;
        // A RemoteViews reapply may add a private background before writing the text colour.
        if (r.custom && key instanceof TextView && !customSurface((View) key)) {
            swapped.remove(key);
            reshadow((TextView) key);
            return null;
        }
        if (color == r.applied) return null;
        if (!themeTone(color, r.lightText)) { swapped.remove(key); if (key instanceof TextView) reshadow((TextView) key); return null; }
        r.saved = saver.apply(color);
        r.applied = mirror(color);
        intercepted++;
        return r.applied;
    }
    static Integer interceptColor(TextView t, int color) {
        return redirect(t, color, swapped, ColorStateList::valueOf);
    }
    /** A list set on a swapped view: its default colour decides; the original list is what gets restored. */
    static ColorStateList interceptList(View v, ColorStateList list) {
        if (list == null) return null;
        Integer c = redirect(v, list.getDefaultColor(), swapped, x -> list);
        return c == null ? null : ColorStateList.valueOf(c);
    }
    /** One view's tone; a seam so the bookkeeping runs without Android in tests. */
    interface Ink {
        Object key();
        /** The colour drawn now (text colour, or the default colour of an icon tint). */
        int color();
        void set(int color);
        Object save();
        void restore(Object saved);
        /** The colour {@code saved} shows in the view's current state (the end of an animated restore). */
        default int colorOf(Object saved) { return color(); }
    }
    private static final class Text implements Ink {
        final TextView t;
        Text(TextView t) { this.t = t; }
        @Override public Object key() { return t; }
        @Override public int color() { return t.getCurrentTextColor(); }
        @Override public void set(int color) { t.setTextColor(color); unshadow(t); }
        @Override public Object save() { return t.getTextColors(); }
        @Override public void restore(Object saved) { if (saved instanceof ColorStateList) t.setTextColor((ColorStateList) saved); reshadow(t); }
        @Override public int colorOf(Object saved) {
            return saved instanceof ColorStateList ? ((ColorStateList) saved).getColorForState(t.getDrawableState(), ((ColorStateList) saved).getDefaultColor()) : color();
        }
    }
    /**
     * A notification icon given the swapped tone. Either its own drawable carries our filter
     * ({@code original} null; {@code saved} is the filter it had), or a monochrome layer replaced it
     * ({@code original} is the drawable to put back). The disc behind it is hidden ({@code bg},
     * {@code bgAlpha}).
     */
    private static final class Glyph {
        final android.graphics.drawable.Drawable shown, original;
        final android.graphics.ColorFilter saved, applied;
        final android.graphics.drawable.Drawable bg;
        final int bgAlpha;
        Glyph(android.graphics.drawable.Drawable shown, android.graphics.drawable.Drawable original, android.graphics.ColorFilter saved,
              android.graphics.ColorFilter applied, android.graphics.drawable.Drawable bg, int bgAlpha) {
            this.shown = shown; this.original = original; this.saved = saved; this.applied = applied; this.bg = bg; this.bgAlpha = bgAlpha;
        }
        /** Whether the view still shows what this record applied. */
        boolean current(ImageView v) {
            android.graphics.drawable.Drawable d = v.getDrawable();
            return d == shown && d.getColorFilter() == applied;
        }
    }
    private static final WeakHashMap<ImageView, Glyph> glyphs = new WeakHashMap<>();
    private static int glyphLogged;
    /**
     * Whether {@code v} is a notification template's app or small icon, debug.oulg.icontone on: the
     * framework's android:icon, or a framework/SystemUI icon view (CachingIconView,
     * NotificationRowIconView) that is not a large icon. A conversation's app badge sits in the
     * corner of the sender's avatar and stayed white on light glass (WhatsApp, device screenshot
     * 26/10): it is an icon view of its own, which the id test alone may miss.
     */
    private static boolean smallIcon(View v) {
        int id = v.getId();
        if (id == View.NO_ID || (id >>> 24) == 0 || !io.github.igorcv88.oneuiliquidglass.glass.Tuning.get().iconTone) return false;
        try {
            android.content.res.Resources r = v.getResources();
            String pkg = r.getResourcePackageName(id), entry = r.getResourceEntryName(id);
            if (smallIconId(pkg, entry)) return true;
            String cls = v.getClass().getSimpleName();
            return iconView(cls) && ("android".equals(pkg) || (pkg != null && pkg.equals(host))) && !largeIconId(entry);
        } catch (RuntimeException e) { return false; }
    }
    static boolean smallIconId(String pkg, String entry) { return "android".equals(pkg) && "icon".equals(entry); }
    /** The template classes that show an app or small icon. */
    static boolean iconView(String simpleName) {
        return "CachingIconView".equals(simpleName) || "NotificationRowIconView".equals(simpleName);
    }
    /** Ids of the views that show a notification's own picture (large icon, avatar), never its app icon. */
    static boolean largeIconId(String entry) {
        if (entry == null) return true;
        String e = entry.toLowerCase(java.util.Locale.ROOT);
        if (e.contains("badge")) return e.endsWith("_bg") || e.contains("ring");
        return e.contains("right_icon") || e.contains("left_icon") || e.contains("large") || e.contains("avatar") || e.contains("picture")
                || e.equals("conversation_icon") || e.contains("profile");
    }
    /**
     * The glyph colour the template gave a monochrome small icon: its tint list, or CachingIconView's
     * original icon colour (the template tints the drawable with a colour filter); null otherwise.
     */
    private static Integer glyphColor(ImageView v, Glyph g) {
        ColorStateList tint = v.getImageTintList();
        if (tint != null) return tint.getDefaultColor();
        android.graphics.drawable.Drawable d = v.getDrawable();
        android.graphics.ColorFilter f = d == null ? null : d.getColorFilter();
        // The template's colour counts only while the drawable carries its filter (or did, before ours):
        // an app icon shown instead of the small icon keeps the notification's colour as its original
        // colour without being tinted, and that accent (WhatsApp's green) released the icon unsampled.
        boolean ours = g != null && f != null && f == g.applied;
        if (untinted(f != null, ours, g != null && g.saved != null)) return null;
        try {
            java.lang.reflect.Method m = Reflect.method(v.getClass(), "getOriginalIconColor");
            if (m == null) return null;
            Object c = m.invoke(v);
            // Notification.COLOR_INVALID (1): no colour, the app's own icon is shown.
            return c instanceof Integer && (Integer) c != 1 ? (Integer) c : null;
        } catch (ReflectiveOperationException | RuntimeException e) { return null; }
    }
    /** Whether a drawable without a tint list shows no template colour: no filter, or only ours over none. */
    static boolean untinted(boolean filtered, boolean ours, boolean hadFilter) {
        return !filtered || (ours && !hadFilter);
    }
    /** lightGlyph() per drawable: its colour, or 0 when it is not a one-colour glyph. */
    private static final WeakHashMap<android.graphics.drawable.Drawable, Integer> sampled = new WeakHashMap<>();
    private static final int SAMPLE = 24;
    /**
     * The colour of an app icon drawn as one neutral colour on transparency (an icon pack's white
     * glyph without a background), or null: such an icon is the app's own icon, not a tinted small
     * icon, so the template gives it no glyph colour (device: Termux, ChatGPT, Gmail with an Icon
     * Pack Studio pack stayed white on light glass, 26/10). Full-colour icons fail the test.
     */
    private static Integer lightGlyph(android.graphics.drawable.Drawable d) {
        Integer known = sampled.get(d);
        if (known != null) return known == 0 ? null : known;
        int found = 0;
        try {
            android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(SAMPLE, SAMPLE, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Rect bounds = d.copyBounds();
            d.setBounds(0, 0, SAMPLE, SAMPLE);
            d.draw(new android.graphics.Canvas(b));
            d.setBounds(bounds);
            int[] px = new int[SAMPLE * SAMPLE];
            b.getPixels(px, 0, SAMPLE, 0, 0, SAMPLE, SAMPLE);
            b.recycle();
            found = oneColourGlyph(px);
        } catch (RuntimeException e) { found = 0; }
        sampled.put(d, found);
        return found == 0 ? null : found;
    }
    /**
     * The mean colour of a glyph image (ARGB pixels), or 0 unless it is one neutral colour on
     * transparency: at least 15 % of it transparent, 3-85 % covered, and nine covered pixels in ten
     * within 40 of grey and of the mean's lightness.
     */
    static int oneColourGlyph(int[] px) {
        int covered = 0, clear = 0;
        long r = 0, g = 0, b = 0;
        for (int c : px) {
            int a = c >>> 24;
            if (a < 26) { clear++; continue; }
            if (a < 128) continue;
            covered++; r += (c >> 16) & 255; g += (c >> 8) & 255; b += c & 255;
        }
        int n = px.length;
        if (covered < n * 3 / 100 || covered > n * 85 / 100 || clear < n * 15 / 100) return 0;
        int mr = (int) (r / covered), mg = (int) (g / covered), mb = (int) (b / covered);
        int ml = (mr * 2126 + mg * 7152 + mb * 722) / 10000, odd = 0;
        for (int c : px) {
            if ((c >>> 24) < 128) continue;
            int cr = (c >> 16) & 255, cg = (c >> 8) & 255, cb = c & 255;
            int max = Math.max(cr, Math.max(cg, cb)), min = Math.min(cr, Math.min(cg, cb));
            int l = (cr * 2126 + cg * 7152 + cb * 722) / 10000;
            if (max - min > 40 || Math.abs(l - ml) > 40) odd++;
        }
        if (odd * 10 > covered) return 0;
        return 0xff000000 | mr << 16 | mg << 8 | mb;
    }
    /**
     * A notification icon drawn in the theme's text tone follows the text's swapped tone: over light
     * glass in the dark theme, light glyphs read as nearly invisible (device screenshots, 26/10).
     * <ul>
     * <li>a monochrome small icon (tinted by the template) takes the mirrored colour as a filter;</li>
     * <li>an app icon with a monochrome layer (AdaptiveIconDrawable#getMonochrome) is replaced by
     * that layer in the swapped tone;</li>
     * <li>an app icon drawn as one light colour on transparency (an icon pack's glyph) is filtered
     * like a small icon.</li>
     * </ul>
     * The light disc behind the icon is hidden while it is swapped. Restore puts back the filter,
     * the drawable and the disc; a new drawable (a rebind) is taken by the next walk. Full-colour
     * app icons and avatars are left as they are.
     */
    static boolean glyph(ImageView v, boolean swap, boolean lightText) {
        Glyph g = glyphs.get(v);
        if (!swap) {
            if (g == null) return false;
            glyphs.remove(v);
            restore(v, g);
            return true;
        }
        android.graphics.drawable.Drawable d = v.getDrawable();
        Integer c = d == null ? null : glyphColor(v, g);
        if (g != null && g.current(v)) {
            // The template recoloured the icon in place (an accent tint): released, as text is.
            if (c != null && !themeTone(c, lightText)) { glyphs.remove(v); restore(v, g); }
            return false;
        }
        android.graphics.drawable.Drawable mono = null;
        if (d != null && c == null) {
            if (d instanceof android.graphics.drawable.AdaptiveIconDrawable) mono = ((android.graphics.drawable.AdaptiveIconDrawable) d).getMonochrome();
            if (mono == null) c = lightGlyph(d);
        }
        if (io.github.igorcv88.oneuiliquidglass.diagnostics.Probe.trace && glyphLogged++ < 60)
            io.github.igorcv88.oneuiliquidglass.diagnostics.Probe.log("ICON_TONE", describe(v)
                    + " drawable=" + (d == null ? "null" : d.getClass().getSimpleName()) + " tint=" + (v.getImageTintList() != null)
                    + " glyph=" + (c == null ? "none" : Integer.toHexString(c)) + " mono=" + (mono != null)
                    + " bg=" + (v.getBackground() == null ? "null" : v.getBackground().getClass().getSimpleName()));
        int target;
        if (mono != null) target = lightText ? 0xff000000 : 0xffffffff;
        else if (c != null && themeTone(c, lightText)) target = mirror(c);
        else {
            if (g != null) { glyphs.remove(v); restore(v, g); }
            return false;
        }
        // A disc hidden by a stale record keeps that record's alpha to give back.
        android.graphics.drawable.Drawable bg = v.getBackground();
        int bgAlpha = g != null && g.bg == bg ? g.bgAlpha : bg != null ? bg.getAlpha() : 255;
        android.graphics.ColorFilter applied = new android.graphics.PorterDuffColorFilter(target, android.graphics.PorterDuff.Mode.SRC_IN);
        Glyph next;
        if (mono != null) {
            android.graphics.drawable.Drawable.ConstantState cs = mono.getConstantState();
            android.graphics.drawable.Drawable m = (cs != null ? cs.newDrawable(v.getResources()) : mono).mutate();
            m.setColorFilter(applied);
            v.setImageDrawable(m);
            next = new Glyph(m, d, null, applied, bg, bgAlpha);
        } else {
            // Its own state: the drawable may share a constant state (and paint) with the status bar's.
            // Not our filter (returned above): the template's current one is what restore gives back.
            d = d.mutate();
            android.graphics.ColorFilter saved = d.getColorFilter();
            d.setColorFilter(applied);
            next = new Glyph(d, null, saved, applied, bg, bgAlpha);
        }
        if (bg != null) bg.mutate().setAlpha(0);
        glyphs.put(v, next);
        return true;
    }
    private static void restore(ImageView v, Glyph g) {
        if (g.current(v)) {
            if (g.original != null) v.setImageDrawable(g.original);
            else g.shown.setColorFilter(g.saved);
        }
        if (g.bg != null && v.getBackground() == g.bg && g.bg.getAlpha() == 0) g.bg.setAlpha(g.bgAlpha);
    }
    /** Shadows taken off swapped text: radius, dx, dy and colour, given back with the text's colour. */
    private static final WeakHashMap<TextView, float[]> shadows = new WeakHashMap<>();
    /**
     * A label drawn with a dark shadow for white text (a group's name over the shade) kept that
     * shadow once its text turned dark: a dark halo behind dark letters, which read as blurred
     * (device screenshot, 26/10). The shadow is taken off while the text carries the swapped tone.
     */
    static void unshadow(TextView t) {
        if (t.getShadowRadius() <= 0f || Color.alpha(t.getShadowColor()) == 0) return;
        // A shadow SystemUI set again (a rebind) is taken off too; the first one is what comes back.
        if (!shadows.containsKey(t)) shadows.put(t, new float[] { t.getShadowRadius(), t.getShadowDx(), t.getShadowDy(), Float.intBitsToFloat(t.getShadowColor()) });
        t.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT);
    }
    /** The shadow follows the swap: off while the view carries the swapped tone, back otherwise. */
    private static void shadowFor(TextView t, boolean swap) {
        if (swap && swapped.containsKey(t)) unshadow(t); else if (!swapped.containsKey(t)) reshadow(t);
    }
    static void reshadow(TextView t) {
        float[] s = shadows.remove(t);
        if (s != null) t.setShadowLayer(s[0], s[1], s[2], Float.floatToRawIntBits(s[3]));
    }
    private static final class Chevron {
        ColorStateList tint;
        android.graphics.PorterDuff.Mode mode;
        android.graphics.ColorFilter imageFilter, drawableFilter, appliedFilter;
        java.lang.ref.WeakReference<android.graphics.drawable.Drawable> drawable;
        int color;
        boolean lightText;
        Chevron(ImageView v) { remember(v); }
        void remember(ImageView v) {
            tint=v.getImageTintList(); mode=v.getImageTintMode(); imageFilter=v.getColorFilter();
            android.graphics.drawable.Drawable d=v.getDrawable();
            drawable=new java.lang.ref.WeakReference<>(d);drawableFilter=d==null?null:d.getColorFilter();
        }
        boolean current(ImageView v) {
            android.graphics.drawable.Drawable d=v.getDrawable();
            return d==drawable.get() && d!=null && d.getColorFilter()==appliedFilter
                    && v.getColorFilter()==appliedFilter && v.getImageTintList()!=null
                    && v.getImageTintList().getDefaultColor()==color;
        }
        void restore(ImageView v) {
            android.graphics.drawable.Drawable d=v.getDrawable();
            boolean owns=d!=null&&d.getColorFilter()==appliedFilter;
            ColorStateList now=v.getImageTintList();
            if(now!=null&&now.getDefaultColor()==color){v.setImageTintList(tint);v.setImageTintMode(mode);}
            if(v.getColorFilter()==appliedFilter)v.setColorFilter(imageFilter);
            if(owns)d.setColorFilter(drawableFilter);
        }
    }
    private static final WeakHashMap<ImageView,Chevron> chevrons=new WeakHashMap<>();
    /** Chevrons moving back to their own colour (the record to restore when the transition ends). */
    private static final WeakHashMap<ImageView,Chevron> restoringChevrons=new WeakHashMap<>();
    static boolean trackingChevrons() { return !chevrons.isEmpty(); }
    static void beforeChevronDrawable(ImageView view,android.graphics.drawable.Drawable incoming) {
        Chevron r=chevrons.get(view);
        if(r==null||incoming==null)return;
        android.graphics.drawable.Drawable previous=r.drawable.get();
        if(previous!=null&&previous!=incoming&&previous.getColorFilter()==r.appliedFilter)
            previous.setColorFilter(r.drawableFilter);
        // Save the incoming drawable before ImageView copies our current filter onto it.
        r.drawable=new java.lang.ref.WeakReference<>(incoming);
        if(incoming.getColorFilter()!=r.appliedFilter)r.drawableFilter=incoming.getColorFilter();
    }
    private static int chevronLogs;
    private static boolean chevronId(View v) {
        try {
            String pkg=v.getResources().getResourcePackageName(v.getId());
            String id=v.getResources().getResourceEntryName(v.getId());
            if("android".equals(pkg)&&"expand_button_icon".equals(id))return true;
        }catch(RuntimeException ignored){}
        // Samsung may supply a different child resource id. Verify ownership by the framework
        // expand control's actual icon field, never by fuzzy id/name matching or arbitrary images.
        android.view.ViewParent parent=v.getParent();
        if(!(parent instanceof View))return false;
        for(Class<?> c=parent.getClass();c!=null;c=c.getSuperclass()){
            if(!c.getName().equals("com.android.internal.widget.NotificationExpandButton"))continue;
            try{return Reflect.read(parent,"mIconView")==v;}
            catch(RuntimeException ignored){return false;}
        }
        return false;
    }
    static Integer filterColor(android.graphics.ColorFilter f) {
        if(f instanceof android.graphics.PorterDuffColorFilter) {
            try { Object color=Reflect.call(f,"getColor");return color instanceof Integer?(Integer)color:null; }
            catch(ReflectiveOperationException|RuntimeException e){return null;}
        }
        if(f instanceof android.graphics.BlendModeColorFilter)return ((android.graphics.BlendModeColorFilter)f).getColor();
        return null;
    }
    private static boolean chevron(ImageView v,boolean swap,boolean lightText) {
        Chevron r=chevrons.get(v);
        if(!swap){
            if(r==null)return false;
            chevrons.remove(v);
            Integer back=animating&&r.current(v)?nativeChevron(v,r):null;
            if(back==null){InkAnimator.cancel(v);r.restore(v);return true;}
            Chevron record=r;
            restoringChevrons.put(v,r);
            Runnable restore=()->{
                restoringChevrons.remove(v);
                boolean before=applying;applying=true;
                try{record.restore(v);}finally{applying=before;}
            };
            // Done or overridden by SystemUI: either way only what is still ours is put back.
            InkAnimator.run(v,chevronTarget(v,r),back,true,restore,restore);
            return true;
        }
        Chevron leaving=restoringChevrons.remove(v);
        Integer resumeFrom=null;
        if(leaving!=null){
            // Swapped again while moving back: finish the restore now so the record below
            // remembers the native filters, then continue from the colour on screen.
            if(InkAnimator.running(v)&&leaving.current(v))resumeFrom=leaving.color;
            InkAnimator.cancel(v);
            boolean before=applying;applying=true;
            try{leaving.restore(v);}finally{applying=before;}
            r=null;
        }
        if(r!=null&&r.current(v)&&r.lightText==lightText)return false;
        if(v.getDrawable()==null)return false;
        boolean fresh=r==null;
        Integer shownBefore=r!=null&&r.current(v)?Integer.valueOf(r.color):resumeFrom;
        if(r==null){r=new Chevron(v);chevrons.put(v,r);}
        else {
            ColorStateList tint=v.getImageTintList();
            if(tint==null||tint.getDefaultColor()!=r.color)r.tint=tint;
            if(v.getColorFilter()!=r.appliedFilter)r.imageFilter=v.getColorFilter();
            if(v.getImageTintMode()!=android.graphics.PorterDuff.Mode.SRC_IN)r.mode=v.getImageTintMode();
            android.graphics.drawable.Drawable d=v.getDrawable();
            if(d.getColorFilter()!=r.appliedFilter)r.drawableFilter=d.getColorFilter();
            r.drawable=new java.lang.ref.WeakReference<>(d);
        }
        r.lightText=lightText;
        Integer nativeColor=nativeChevron(v,r);
        if(nativeColor==null&&effective(r)!=null){chevrons.remove(v);r.restore(v);return false;}
        int c=nativeColor==null?(lightText?0xffffffff:0xff1d1d1d):nativeColor;
        if(!themeTone(c,lightText)){chevrons.remove(v);r.restore(v);return false;}
        int mapped=mirror(c);
        if(animating&&v.isShown()){
            // From the colour on screen: the native one on a first swap, the current one otherwise.
            paintChevron(v,r,shownBefore!=null?shownBefore:c);
            InkAnimator.run(v,chevronTarget(v,r),mapped,true,null);
        }else{InkAnimator.cancel(v);paintChevron(v,r,mapped);}
        if(chevronLogs++<8||io.github.igorcv88.oneuiliquidglass.diagnostics.Probe.trace)io.github.igorcv88.oneuiliquidglass.diagnostics.Probe.log("CHEVRON_TONE",
                "view="+Integer.toHexString(System.identityHashCode(v))+" drawable="+Integer.toHexString(System.identityHashCode(v.getDrawable()))
                +" tint="+(r.tint!=null)+" filter="+(r.drawableFilter!=null)+" alpha="+v.getImageAlpha()
                +" target="+Integer.toHexString(mapped)+" first="+fresh+" animated="+(animating&&v.isShown()));
        return true;
    }
    /** The filter ImageView.applyColorMod uses: an explicit ImageView filter wins over the drawable's. */
    private static android.graphics.ColorFilter effective(Chevron r){return r.imageFilter!=null?r.imageFilter:r.drawableFilter;}
    /** The chevron's own colour (from the filter that renders it, else its tint), or null if unreadable. */
    private static Integer nativeChevron(ImageView v,Chevron r){
        android.graphics.ColorFilter effective=effective(r);
        Integer nativeColor=filterColor(effective);
        if(effective!=null)return nativeColor;
        return r.tint!=null?r.tint.getColorForState(v.getDrawableState(),r.tint.getDefaultColor()):null;
    }
    /** Shows {@code color} on every path that can colour the chevron: tint, view filter and drawable filter. */
    private static void paintChevron(ImageView v,Chevron r,int color){
        if(r.appliedFilter==null||r.color!=color)
            r.appliedFilter=new android.graphics.PorterDuffColorFilter(color,android.graphics.PorterDuff.Mode.SRC_IN);
        r.color=color;
        boolean before=applying;applying=true;
        try{
            v.setImageTintList(ColorStateList.valueOf(color));
            v.setImageTintMode(android.graphics.PorterDuff.Mode.SRC_IN);
            v.setColorFilter(r.appliedFilter);
            android.graphics.drawable.Drawable d=v.getDrawable();
            if(d!=null){d=d.mutate();r.drawable=new java.lang.ref.WeakReference<>(d);d.setColorFilter(r.appliedFilter);}
        }finally{applying=before;}
    }
    /** A chevron as an animator target; anything else replacing its colour ends the transition. */
    private static InkAnimator.Target chevronTarget(ImageView v,Chevron r){
        return new InkAnimator.Target(){
            @Override public int shown(){return r.current(v)?r.color:~r.color;}
            @Override public void show(int color){paintChevron(v,r,color);}
        };
    }

    /** Only icons tinted through imageTintList: the expand chevron and similar glyphs, never app icons. */
    private static final class Icon implements Ink {
        final ImageView i;
        Icon(ImageView i) { this.i = i; }
        @Override public Object key() { return i; }
        @Override public int color() { return i.getImageTintList().getDefaultColor(); }
        @Override public void set(int color) { i.setImageTintList(ColorStateList.valueOf(color)); }
        @Override public Object save() { return i.getImageTintList(); }
        @Override public void restore(Object saved) { i.setImageTintList((ColorStateList) saved); }
        @Override public int colorOf(Object saved) {
            return saved instanceof ColorStateList ? ((ColorStateList) saved).getColorForState(i.getDrawableState(), ((ColorStateList) saved).getDefaultColor()) : color();
        }
    }

    /**
     * swap false restores every view under {@code root}. Returns the views changed. {@code tracked},
     * when given, is refilled with the views that carry the swapped tone after the walk, for
     * {@link #drift}.
     */
    static int apply(View root, boolean swap, boolean lightText, java.util.List<View> tracked) {
        return apply(root, swap, lightText, tracked, false);
    }
    /**
     * {@code animate}: the tone changes on a card the user is looking at (a backdrop change while
     * it is shown), so text, tints and chevrons move to their new colour over a short transition
     * (InkAnimator) instead of jumping; glyph filters and drawables still switch at once.
     */
    static int apply(View root, boolean swap, boolean lightText, java.util.List<View> tracked, boolean animate) {
        if (tracked != null) tracked.clear();
        if (root == null) return 0;
        host = root.getContext().getPackageName();
        boolean before = animating;
        applying = true; animating = animate;
        try { return walk(root, swap, lightText, tracked); } finally { applying = false; animating = before; }
    }
    /** Set by apply(..., animate): the views changed by this walk transition instead of jumping. */
    private static boolean animating;
    /** Views moving back to their own colours: what they get when the transition ends. */
    private static final WeakHashMap<Object, Object> restoring = new WeakHashMap<>();
    /** One Ink as an animator target; its writes pass the colour interception as this class's own. */
    private static InkAnimator.Target target(Ink ink) {
        return new InkAnimator.Target() {
            @Override public int shown() { return ink.color(); }
            @Override public void show(int color) {
                boolean before = applying; applying = true;
                try { ink.set(color); } finally { applying = before; }
            }
        };
    }
    /** The view the last drift() found, for TEXT_TONE_DRIFT (class and id name only). */
    static View lastDrift;
    /** Class, id name and parent class of a view, never its text. */
    static String describe(View v) {
        if (v == null) return "null";
        String id = "none";
        try { if (v.getId() != View.NO_ID) id = v.getResources().getResourcePackageName(v.getId()) + ":" + v.getResources().getResourceEntryName(v.getId()); }
        catch (RuntimeException ignored) { }
        android.view.ViewParent p = v.getParent();
        return "class=" + v.getClass().getName() + " id=" + id + " parent=" + (p == null ? "null" : p.getClass().getSimpleName());
    }
    /**
     * Text views under {@code root} that are not inside a notification row and show the theme's
     * text tone: where a label outside the rows (the minimized group's title, a section header)
     * lives, for a trace-only TEXT_OUTSIDE survey. At most {@code max} entries; no text.
     */
    static java.util.List<String> survey(View root, boolean lightText, int max) {
        java.util.List<String> out = new java.util.ArrayList<>();
        surveyWalk(root, lightText, max, out);
        return out;
    }
    /** swap on a fixed set of views (the shade header's clock, date and carrier), outside any row. */
    static int applyViews(java.util.List<View> views, boolean swap, boolean lightText, java.util.List<View> tracked) {
        if (tracked != null) tracked.clear();
        int changed = 0;
        applying = true;
        try {
            for (View v : views) {
                if (!(v instanceof TextView)) continue;
                changed += ink(new Text((TextView) v), swap, lightText, swapped) ? 1 : 0;
                shadowFor((TextView) v, swap);
                if (tracked != null && swap && (swapped.containsKey(v) || chevrons.containsKey(v))) tracked.add(v);
            }
        } finally { applying = false; }
        return changed;
    }
    /**
     * Every text view in a row (or the shelf), with whether this class would touch it and why not:
     * class, id, colour, standard-template test, theme-tone test, swapped now. Trace only; no text.
     */
    static java.util.List<String> rowSurvey(View root, boolean lightText, int max) {
        java.util.List<String> out = new java.util.ArrayList<>();
        host = root.getContext().getPackageName();
        rowSurveyWalk(root, root, lightText, max, out);
        return out;
    }
    private static void rowSurveyWalk(View root, View v, boolean lightText, int max, java.util.List<String> out) {
        if (out.size() >= max) return;
        if (v != root && v.getClass().getName().endsWith("ExpandableNotificationRow")) return;
        if (v instanceof TextView) {
            int c = ((TextView) v).getCurrentTextColor();
            out.add(describe(v) + " color=" + Integer.toHexString(c) + " vis=" + v.getVisibility() + " standard=" + standard(v, false)
                    + " customSurface=" + customSurface(v) + " eligible=" + textEligible(v)
                    + " themeTone=" + themeTone(c, lightText) + " swapped=" + swapped.containsKey(v));
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) rowSurveyWalk(root, g.getChildAt(i), lightText, max, out);
        }
    }
    private static void surveyWalk(View v, boolean lightText, int max, java.util.List<String> out) {
        if (out.size() >= max || v.getVisibility() != View.VISIBLE) return;
        if (v.getClass().getName().endsWith("ExpandableNotificationRow")) return;
        if (v instanceof TextView && themeTone(((TextView) v).getCurrentTextColor(), lightText)) out.add(describe(v));
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) surveyWalk(g.getChildAt(i), lightText, max, out);
        }
    }
    /**
     * Why the swapped tone of a card is no longer on screen, or null: a tracked view left the
     * window (the notification was inflated again), or its colour is no longer the one it was given
     * (SystemUI bound the content again and set its own colour). Cheap: one colour read per view,
     * run on every pre-draw of a swapped card so a rebind is repaired before its next frame instead
     * of up to SWAP_WALK_MS later (white text on light glass for ~450 ms on the device, 25/10).
     */
    static String drift(java.util.List<View> tracked) {
        for (int i = 0; i < tracked.size(); i++) {
            View v = tracked.get(i);
            lastDrift = v;
            if (!v.isAttachedToWindow()) return "detached";
            if (v instanceof ImageView && chevrons.containsKey(v)) {
                Chevron c=chevrons.get(v);
                if (!c.current((ImageView)v)) {
                    boolean before=applying;applying=true;
                    try { chevron((ImageView)v,true,c.lightText); }
                    finally { applying=before; }
                }
                continue;
            }
            Rec r = swapped.get(v);
            // Read in place (no Ink wrapper): this runs for every swapped view on every pre-draw.
            if (r == null) return "rebind";
            if (v instanceof TextView) {
                if (r.custom && !customSurface(v)) return "surface";
                if (((TextView) v).getCurrentTextColor() != r.applied && !InkAnimator.running(v)) return "rebind";
            }
            else if (v instanceof ImageView) {
                ColorStateList tint = ((ImageView) v).getImageTintList();
                if ((tint == null || tint.getDefaultColor() != r.applied) && !InkAnimator.running(v)) return "rebind";
            }
        }
        lastDrift = null;
        return null;
    }
    /** Whether a view swapped by this class shows a colour other than the one it was given. */
    static boolean drifted(Ink ink, Map<Object, Rec> swapped) {
        Rec r = swapped.get(ink.key());
        return r == null || ink.color() != r.applied;
    }
    private static int walk(View v, boolean swap, boolean lightText, java.util.List<View> tracked) {
        int changed = 0;
        if (v instanceof TextView) {
            boolean eligible = textEligible(v);
            if (eligible || swapped.containsKey(v)) {
                changed += ink(new Text((TextView) v), swap && eligible, lightText, swapped, animating) ? 1 : 0;
                Rec record = swapped.get(v);
                if (record != null) record.custom = !standard(v, false);
                shadowFor((TextView) v, swap && eligible);
            }
        } else if (v instanceof ImageView && (chevrons.containsKey(v) || chevronId(v))) {
            changed += chevron((ImageView)v,swap,lightText)?1:0;
        } else if (v instanceof ImageView && (glyphs.containsKey(v) || smallIcon(v))) {
            // icontone turned off live restores the glyphs already swapped.
            changed += glyph((ImageView) v, swap && io.github.igorcv88.oneuiliquidglass.glass.Tuning.get().iconTone, lightText) ? 1 : 0;
        } else if (v instanceof ImageView && (((ImageView) v).getImageTintList() != null || swapped.containsKey(v))) {
            ImageView i = (ImageView) v;
            if (i.getImageTintList() == null) swapped.remove(v);
            else if (standard(v, true)) changed += ink(new Icon(i), swap, lightText, swapped, animating) ? 1 : 0;
        }
        if (tracked != null && swap && (swapped.containsKey(v) || chevrons.containsKey(v))) tracked.add(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                // A group's child rows that draw their own glass have their own material and tone; a
                // child row drawn inside the group's card (a collapsed or minimised group, whose
                // own background is hidden) takes the group's tone, or its text stayed white on
                // the group's light glass (TEXT_ROW: app_name_text ffffffff swapped=false, 25/10).
                if (c.getClass().getName().endsWith("ExpandableNotificationRow") && ownsTone.test(c)) continue;
                changed += walk(c, swap, lightText, tracked);
            }
        }
        return changed;
    }
    /**
     * One view. swap: a view of the theme's tone gets the mirrored tone; one already swapped is
     * left, unless its content set a colour since (then that colour is the new original, swapped
     * again if it is of the theme's tone, released otherwise). !swap: a view still showing the tone
     * it was given gets its own colours back.
     */
    static boolean ink(Ink ink, boolean swap, boolean lightText, Map<Object, Rec> swapped) {
        return ink(ink, swap, lightText, swapped, false);
    }
    static boolean ink(Ink ink, boolean swap, boolean lightText, Map<Object, Rec> swapped, boolean animate) {
        Object key = ink.key();
        Rec r = swapped.get(key);
        boolean moving = InkAnimator.running(key);
        int now = ink.color();
        if (!swap) {
            if (r == null) return false;
            swapped.remove(key);
            // Mid-transition the colour on screen is ours even though it is not r.applied yet.
            if (now != r.applied && !moving) return false;
            Object saved = r.saved;
            if (!animate) { InkAnimator.cancel(key); ink.restore(saved); return true; }
            restoring.put(key, saved);
            InkAnimator.run(key, target(ink), ink.colorOf(saved), true, () -> {
                restoring.remove(key);
                boolean before = applying; applying = true;
                try { ink.restore(saved); } finally { applying = before; }
            }, () -> {
                // SystemUI set its own colour mid-way: that colour stands; only the shadow comes back.
                restoring.remove(key);
                if (key instanceof TextView) reshadow((TextView) key);
            });
            return true;
        }
        // Already moving to the swapped tone: let it finish.
        if (r != null && (now == r.applied || moving)) return false;
        int shownNow = now;
        Object back = restoring.remove(key);
        // Only a restore still on screen is resumed; a finished or overridden one is history.
        if (!moving) back = null;
        if (back != null) {
            // Moving back to its own colours: those are the original again, and the swap
            // continues from the colour on screen instead of jumping to the original first.
            InkAnimator.cancel(key);
            ink.restore(back);
            now = ink.color();
        }
        if (!themeTone(now, lightText)) {
            if (r != null) swapped.remove(key);
            if (back != null && shownNow != now) ink.set(now);
            return false;
        }
        if (r == null) { r = new Rec(ink.save()); swapped.put(key, r); }
        else r.saved = ink.save();
        r.lightText = lightText;
        r.applied = mirror(now);
        if (animate) {
            if (back != null) ink.set(shownNow);
            InkAnimator.run(key, target(ink), r.applied, true, null);
        } else { InkAnimator.cancel(key); ink.set(r.applied); }
        return true;
    }
    static boolean textEligible(View v) { return standard(v, false) || customSurface(v); }

    /**
     * A custom label may follow the glass only inside a notification row, with no private
     * background between the label and that row. The row itself owns the module's material.
     * Unknown drawables, including images, gradients and selectors, fail closed. This deliberately
     * does not infer transparency from Drawable.getOpacity() (TRANSLUCENT can still paint a card).
     * No resource-id or publisher allowlist: RemoteViews can also contain labels without ids.
     */
    static boolean customSurface(View v) {
        for (View p = v; p != null; ) {
            if (p.getClass().getName().endsWith("ExpandableNotificationRow")) return p != v;
            if (!clear(p.getBackground()) || !clear(p.getForeground())) return false;
            ColorStateList tint = p.getBackgroundTintList();
            if (p.getBackground() != null && tint != null && tint.getColorForState(p.getDrawableState(), tint.getDefaultColor()) >>> 24 != 0)
                return false;
            android.view.ViewParent parent = p.getParent();
            p = parent instanceof View ? (View) parent : null;
        }
        return false;
    }
    private static boolean clear(android.graphics.drawable.Drawable d) {
        return d == null || d.getAlpha() == 0 || (d instanceof android.graphics.drawable.ColorDrawable
                && (((android.graphics.drawable.ColorDrawable) d).getColor() >>> 24) == 0);
    }

    /** Whether a view is part of a standard template; icon: only the expand icon. */
    private static boolean standard(View v, boolean icon) {
        int id = v.getId();
        if (id == View.NO_ID || (id >>> 24) == 0) {
            // No id: SystemUI's own views (a group's "+N" overflow text, inflated with SystemUI's
            // context) qualify; a RemoteViews layout is inflated with the app's context and does not.
            if (icon) return false;
            try { return standardNoId(v.getContext().getPackageName(), hostPackage(v)); } catch (RuntimeException e) { return false; }
        }
        try {
            android.content.res.Resources r = v.getResources();
            return standardId(r.getResourcePackageName(id), hostPackage(v), icon ? r.getResourceEntryName(id) : null);
        } catch (RuntimeException e) { return false; }
    }
    /**
     * pkg: the package of the view's id; host: SystemUI's package; entry: the id's name for an icon,
     * null for text. Framework and SystemUI ids only; an icon only when its name says expand.
     */
    /** A view without id qualifies only when it was inflated with SystemUI's own context. */
    static boolean standardNoId(String contextPkg, String host) { return host != null && host.equals(contextPkg); }
    /** SystemUI's package, taken from the row each walk starts at (a SystemUI view; a child's context may be an app's). */
    private static String host;
    private static String hostPackage(View v) { return host; }
    static boolean standardId(String pkg, String host, String entry) {
        boolean ours = "android".equals(pkg) || (pkg != null && pkg.equals(host));
        if (!ours) return false;
        return entry == null || entry.toLowerCase(java.util.Locale.ROOT).contains("expand");
    }
    /** The grey on the other side of the scale, alpha kept: white to black, a light grey to a dark one. */
    static int mirror(int c) {
        return (c & 0xff000000) | (~c & 0x00ffffff);
    }
    /** Whether a colour is the theme's notification text tone (near white or near black, greys included). */
    static boolean themeTone(int c, boolean lightText) {
        int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        if (max - min > 40) return false;
        float luma = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f;
        return lightText ? luma >= 0.55f : luma <= 0.45f;
    }
}
