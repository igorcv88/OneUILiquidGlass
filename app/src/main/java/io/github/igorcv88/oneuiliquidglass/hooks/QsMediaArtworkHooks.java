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
 * composition (including a new media page or track) reads the current module setting.</p>
 */
final class QsMediaArtworkHooks {
    static final String PAGE = "media.components.OneUI90MediaPlayerPageKt";
    static final String BITMAP_PAINTER = "androidx.compose.ui.graphics.painter.BitmapPainter";
    private final ClassLoader loader;
    private final QsContentHooks content;
    private final BooleanSupplier enabled;
    private final Set<String> candidatePainters = new HashSet<>();
    private boolean loggedApplied;

    QsMediaArtworkHooks(ClassLoader loader, QsContentHooks content, BooleanSupplier enabled) {
        this.loader = loader;
        this.content = content;
        this.enabled = enabled;
    }

    static boolean matches(String owner, String painterClass) {
        return PAGE.equals(owner) && BITMAP_PAINTER.equals(painterClass);
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
                    float adjusted = opacity(original, Tuning.get().mediaArt, enabled.getAsBoolean());
                    if (adjusted == original) return;
                    p.args[4] = adjusted;
                    if (!loggedApplied) {
                        loggedApplied = true;
                        Probe.log("QS_MEDIA_ART_APPLIED", "native=" + original + " applied=" + adjusted);
                    }
                }
            });
            Probe.log("QS_MEDIA_ART_HOOK", "installed=true painter=BitmapPainter scope=OneUI90MediaPlayerPageKt phase=constructor");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Probe.log("QS_MEDIA_ART_HOOK", "installed=false reason=" + e.getClass().getSimpleName());
        }
    }
}
