package io.github.igorcv88.oneuiliquidglass.hooks;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;
import io.github.igorcv88.oneuiliquidglass.glass.Tuning;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * The One UI 9 QS media page draws its artwork in Compose, not in the shade's
 * QSMediaCornerRoundedView. Keep the native painter and its transition intact; lower only its
 * construction-time alpha so the QS media container can show through if the layers below are
 * transparent. The actual background composition still needs a visual check on DZIF.
 *
 * <p>The selection is strict: a BitmapPainter created by OneUI90MediaPlayerPageKt. This does not
 * affect notification media, Now Bar, the QS buttons or the app icon rendered by other owners.
 * The value is changed only in the canonical PainterElement constructor; Compose copies it into
 * the PainterNode on create/update. No draw or node callbacks, maps, or per-frame lookups. A new
 * composition (including a new media page or track) reads the existing debug property.</p>
 */
final class QsMediaArtworkHooks {
    static final String PAGE = "media.components.OneUI90MediaPlayerPageKt";
    static final String BITMAP_PAINTER = "androidx.compose.ui.graphics.painter.BitmapPainter";
    /**
     * DZIF (QS_MEDIA_PAINTER_SOURCE, 04/11/2026): inside the page frame the artwork is an
     * EmptyPainter while it loads, then this DrawablePainter; BitmapPainter never appears there.
     */
    static final String DRAWABLE_PAINTER = "com.android.compose.ui.graphics.painter.DrawablePainter";
    /**
     * DZIF (QS_MEDIA_DRAW_SOURCE): MediaArtworkKt builds a DrawWithContentElement around the
     * artwork, the QS counterpart of the shade's sec_album_art film. While the artwork is
     * translucent it is replaced by a block that only draws its content; debug.oulg.qsmediaoverlay
     * = native keeps Samsung's block.
     */
    static final String ARTWORK_BLOCK = "com.android.systemui.samsung.quicksetting.ui.media.components.MediaArtworkKt";
    static final String OVERLAY_PROPERTY = "debug.oulg.qsmediaoverlay";
    private Object contentOnly;
    private boolean loggedOverlay;
    private final ClassLoader loader;
    private final QsContentHooks content;
    private final BooleanSupplier enabled;
    private final Set<String> candidatePainters = new HashSet<>();
    /**
     * Opt-in source map ({@code debug.oulg.qsmediaprobe=1}, read once at install; SystemUI must
     * restart): OULG_MEDIA_REPRO (DZIF 04/11/2026) shows no QS_MEDIA_ART_CANDIDATE at all, i.e. no
     * PainterElement is built inside the OneUI90MediaPlayerPageKt frame. docs/quick-settings-glass.md
     * describes the active player's artwork as a draw block (art and gradients), and a pager page
     * is composed outside that frame. With the probe on, the constructors of the draw-modifier
     * elements and of PainterElement report, once per distinct class, the lambda or painter class
     * and the frame owner, so the block that draws the artwork is named before anything is changed.
     */
    static final String PROBE = "debug.oulg.qsmediaprobe";
    private static final int PROBE_BUDGET = 32;
    private final Set<String> probed = new HashSet<>();
    private boolean loggedApplied;

    QsMediaArtworkHooks(ClassLoader loader, QsContentHooks content, BooleanSupplier enabled) {
        this.loader = loader;
        this.content = content;
        this.enabled = enabled;
    }

    static boolean matches(String owner, String painterClass) {
        return PAGE.equals(owner) && (BITMAP_PAINTER.equals(painterClass) || DRAWABLE_PAINTER.equals(painterClass));
    }

    /** Samsung's draw block around the QS artwork (one of MediaArtworkKt's lambdas). */
    static boolean artworkBlock(String lambdaClass) {
        return lambdaClass != null && lambdaClass.startsWith(ARTWORK_BLOCK + "$");
    }

    /** Drop the block only while the artwork is translucent, the module is on and not opted out. */
    static boolean dropOverlay(boolean enabled, float artOpacity, String override) {
        return enabled && Float.isFinite(artOpacity) && artOpacity > Tuning.MEDIA_ART_NATIVE && artOpacity < 1f
                && !"native".equals(override == null ? null : override.trim());
    }

    /**
     * A kotlin Function1 that only calls ContentDrawScope.drawContent(): the artwork itself, no
     * film. One stateless instance (equal across recompositions), built from SystemUI's loader.
     */
    private Object contentOnly() throws ReflectiveOperationException {
        if (contentOnly != null) return contentOnly;
        Class<?> function1 = Class.forName("kotlin.jvm.functions.Function1", false, loader);
        Class<?> scope = Class.forName("androidx.compose.ui.graphics.drawscope.ContentDrawScope", false, loader);
        final java.lang.reflect.Method draw = scope.getMethod("drawContent");
        final Object unit = Class.forName("kotlin.Unit", false, loader).getField("INSTANCE").get(null);
        contentOnly = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[] {function1}, (proxy, m, args) -> {
            switch (m.getName()) {
                case "invoke":
                    if (args != null && args.length == 1 && scope.isInstance(args[0])) draw.invoke(args[0]);
                    return unit;
                case "equals": return args != null && args.length == 1 && proxy == args[0];
                case "hashCode": return System.identityHashCode(proxy);
                case "toString": return "OulgQsArtworkContentOnly";
                default: return null;
            }
        });
        return contentOnly;
    }

    /** The primary PainterElement constructor has six parameters, alpha being the fifth. */
    static boolean alphaSlot(Object[] args) {
        return args != null && args.length == 6 && args[4] instanceof Float;
    }

    static float opacity(float nativeAlpha, float artOpacity, boolean enabled) {
        if (!enabled || !Float.isFinite(artOpacity) || artOpacity <= Tuning.MEDIA_ART_NATIVE
                || !Float.isFinite(nativeAlpha))
            return nativeAlpha;
        return nativeAlpha * Math.min(1f, Math.max(Tuning.MEDIA_ART_MIN, artOpacity));
    }

    /** MediaArtworkKt's draw block: content only while the artwork is translucent. Construction only. */
    private void installOverlay() {
        Class<?> element;
        try { element = Class.forName("androidx.compose.ui.draw.DrawWithContentElement", false, loader); }
        catch (ClassNotFoundException | LinkageError e) { Probe.log("QS_MEDIA_OVERLAY", "installed=false reason=noElement"); return; }
        XposedBridge.hookAllConstructors(element, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (p.args.length != 1 || p.args[0] == null || !artworkBlock(p.args[0].getClass().getName())) return;
                if (!dropOverlay(enabled.getAsBoolean(), Tuning.get().qsMediaArt, property(OVERLAY_PROPERTY))) return;
                try {
                    Object replacement = contentOnly();
                    if (!loggedOverlay) {
                        loggedOverlay = true;
                        Probe.log("QS_MEDIA_OVERLAY", "state=contentOnly lambda=" + p.args[0].getClass().getName());
                    }
                    p.args[0] = replacement;
                } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                    Probe.log("QS_MEDIA_OVERLAY", "state=native reason=" + e.getClass().getSimpleName());
                }
            }
        });
    }

    /** Read-only: logs which element classes the QS media code builds; changes nothing. */
    private void installProbe(Class<?> painterElement) {
        int hooked = 0;
        for (String name : new String[] {"androidx.compose.ui.draw.DrawBehindElement",
                "androidx.compose.ui.draw.DrawWithCacheElement", "androidx.compose.ui.draw.DrawWithContentElement"}) {
            Class<?> c;
            try { c = Class.forName(name, false, loader); } catch (ClassNotFoundException | LinkageError e) { continue; }
            final String element = c.getSimpleName();
            XposedBridge.hookAllConstructors(c, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.args.length == 0 || p.args[0] == null) return;
                    QsContentHooks.Frame frame = content.current();
                    String lambda = p.args[0].getClass().getName();
                    if (!mediaSource(lambda, frame == null ? null : frame.owner)) return;
                    report("QS_MEDIA_DRAW_SOURCE", element + "|" + lambda, "element=" + element + " lambda=" + lambda
                            + " frame=" + (frame == null ? "none" : frame.owner));
                }
            });
            hooked++;
        }
        XposedBridge.hookAllConstructors(painterElement, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (p.args.length == 0 || p.args[0] == null) return;
                QsContentHooks.Frame frame = content.current();
                String owner = frame == null ? null : frame.owner;
                String painter = p.args[0].getClass().getName();
                // Every media frame, and bitmaps built with no frame at all (a pager page composes
                // after its owner returned); vector icons outside media are not interesting.
                if (!(owner != null && owner.startsWith("media.")) && !(owner == null && painter.equals(BITMAP_PAINTER))) return;
                report("QS_MEDIA_PAINTER_SOURCE", painter + "|" + owner, "painter=" + painter + " frame=" + (owner == null ? "none" : owner)
                        + " args=" + p.args.length);
            }
        });
        Probe.log("QS_MEDIA_PROBE", "installed=true drawElements=" + hooked);
    }

    /** A draw lambda from the QS media package, or one built while a media composable runs. */
    static boolean mediaSource(String lambdaClass, String frameOwner) {
        return (lambdaClass != null && lambdaClass.contains("quicksetting.ui.media"))
                || (frameOwner != null && frameOwner.startsWith("media."));
    }

    private void report(String event, String key, String detail) {
        synchronized (probed) {
            if (probed.size() >= PROBE_BUDGET || !probed.add(key)) return;
        }
        Probe.log(event, detail);
    }

    private static String property(String name) {
        try { return (String) Class.forName("android.os.SystemProperties").getMethod("get", String.class).invoke(null, name); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError e) { return null; }
    }

    void install() {
        try {
            Class<?> element = Class.forName("androidx.compose.ui.draw.PainterElement", false, loader);
            XposedBridge.hookAllConstructors(element, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    QsContentHooks.Frame frame = content.current();
                    if (frame == null || !PAGE.equals(frame.owner) || p.args.length == 0 || p.args[0] == null)
                        return;
                    String painter = p.args[0].getClass().getName();
                    if (candidatePainters.size() < 8 && candidatePainters.add(painter)) {
                        Probe.log("QS_MEDIA_ART_CANDIDATE", "owner=" + frame.owner + " painter=" + painter);
                    }
                    // Synthetic default-argument constructors delegate to the six-argument
                    // constructor. Touch only the primary one to avoid multiplying twice.
                    if (!matches(frame.owner, painter) || !alphaSlot(p.args)) return;
                    float original = (Float) p.args[4];
                    float adjusted = opacity(original, Tuning.get().qsMediaArt, enabled.getAsBoolean());
                    if (adjusted == original) return;
                    p.args[4] = adjusted;
                    if (!loggedApplied) {
                        loggedApplied = true;
                        Probe.log("QS_MEDIA_ART_APPLIED", "native=" + original + " applied=" + adjusted);
                    }
                }
            });
            installOverlay();
            Probe.log("QS_MEDIA_ART_HOOK", "installed=true painter=BitmapPainter,DrawablePainter scope=OneUI90MediaPlayerPageKt phase=constructor");
            if ("1".equals(property(PROBE))) installProbe(element);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Probe.log("QS_MEDIA_ART_HOOK", "installed=false reason=" + e.getClass().getSimpleName());
        }
    }
}
