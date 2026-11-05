package io.github.igorcv88.oneuiliquidglass.hooks;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;
import io.github.igorcv88.oneuiliquidglass.glass.Tuning;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;

/**
 * The One UI 9 QS media page draws its artwork in Compose, not in the shade's
 * QSMediaCornerRoundedView. Keep the native painter and its transition intact; lower only its
 * draw-time alpha so the existing transparent media container and QS backdrop can show through.
 *
 * <p>The selection is strict: a BitmapPainter created by OneUI90MediaPlayerPageKt. This does not
 * affect notification media, Now Bar, the QS buttons or the app icon rendered by other owners.
 * The node's native alpha is restored after each draw, including when the module is disabled:
 * there is no persistent mutation to clean up at detach or when SystemUI reuses a node.</p>
 */
final class QsMediaArtworkHooks {
    static final String PAGE = "media.components.OneUI90MediaPlayerPageKt";
    static final String BITMAP_PAINTER = "androidx.compose.ui.graphics.painter.BitmapPainter";
    private final ClassLoader loader;
    private final QsContentHooks content;
    private final BooleanSupplier enabled;
    private final Map<Object, Boolean> elements = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Object, Boolean> nodes = Collections.synchronizedMap(new WeakHashMap<>());
    private final Set<String> candidatePainters = new HashSet<>();
    private Field nodeAlpha;
    private boolean loggedApplied;

    QsMediaArtworkHooks(ClassLoader loader, QsContentHooks content, BooleanSupplier enabled) {
        this.loader = loader;
        this.content = content;
        this.enabled = enabled;
    }

    static boolean matches(String owner, String painterClass) {
        return PAGE.equals(owner) && BITMAP_PAINTER.equals(painterClass);
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
            Class<?> node = Class.forName("androidx.compose.ui.draw.PainterNode", false, loader);
            Class<?> scope = Class.forName("androidx.compose.ui.graphics.drawscope.ContentDrawScope", false, loader);
            QsNodeAbi abi = QsNodeAbi.resolve(element, node, scope);
            Field alpha = node.getDeclaredField("alpha");
            if (alpha.getType() != float.class) throw new NoSuchFieldException("PainterNode.alpha isn't float");
            alpha.setAccessible(true);
            nodeAlpha = alpha;

            XposedBridge.hookAllConstructors(element, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    QsContentHooks.Frame frame = content.current();
                    if (frame == null || !PAGE.equals(frame.owner) || p.args.length == 0 || p.args[0] == null)
                        return;
                    String painter = p.args[0].getClass().getName();
                    if (candidatePainters.size() < 8 && candidatePainters.add(painter)) {
                        Probe.log("QS_MEDIA_ART_CANDIDATE", "owner=" + frame.owner + " painter=" + painter);
                    }
                    if (matches(frame.owner, painter)) elements.put(p.thisObject, Boolean.TRUE);
                }
            });

            XC_MethodHook bind = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Object target = p.args.length == 0 ? p.getResult() : p.args[0];
                    if (target == null) return;
                    if (Boolean.TRUE.equals(elements.get(p.thisObject))) nodes.put(target, Boolean.TRUE);
                    else nodes.remove(target);
                }
            };
            XposedBridge.hookMethod(abi.create, bind);
            XposedBridge.hookMethod(abi.update, bind);
            XposedBridge.hookMethod(abi.draw, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                    if (!Boolean.TRUE.equals(nodes.get(p.thisObject))) return;
                    float original = nodeAlpha.getFloat(p.thisObject);
                    float adjusted = opacity(original, Tuning.get().mediaArt, enabled.getAsBoolean());
                    if (adjusted == original) return;
                    p.setObjectExtra("oulgQsMediaNativeAlpha", original);
                    nodeAlpha.setFloat(p.thisObject, adjusted);
                    if (!loggedApplied) {
                        loggedApplied = true;
                        Probe.log("QS_MEDIA_ART_APPLIED", "native=" + original + " applied=" + adjusted);
                    }
                }
                @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                    Object original = p.getObjectExtra("oulgQsMediaNativeAlpha");
                    if (original instanceof Float) nodeAlpha.setFloat(p.thisObject, (Float) original);
                }
            });
            Probe.log("QS_MEDIA_ART_HOOK", "installed=true painter=BitmapPainter scope=OneUI90MediaPlayerPageKt");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            Probe.log("QS_MEDIA_ART_HOOK", "installed=false reason=" + e.getClass().getSimpleName());
        }
    }
}
