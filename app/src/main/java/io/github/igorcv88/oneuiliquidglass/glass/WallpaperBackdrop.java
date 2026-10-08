package io.github.igorcv88.oneuiliquidglass.glass;

import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import io.github.igorcv88.oneuiliquidglass.diagnostics.Probe;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The lockscreen wallpaper as the sampled backdrop: decoded once per wallpaper id, center-cropped
 * to the display at half resolution and uploaded as a hardware bitmap. Rows on the keyguard sit
 * directly over it, so the material refracts the real background with no capture and no lag: the
 * bitmap is static and redrawn on the GPU each frame at the row's current position.
 *
 * <p>A live wallpaper has no static image to sample; this backdrop then reports unavailable and
 * the rows fall back to the Samsung blur.</p>
 */
public final class WallpaperBackdrop implements SampledBackdrop {
    private static final float SCALE = 0.5f;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor(r -> new Thread(r, "oulg-wallpaper"));
    private static final List<WeakReference<View>> waiting = new ArrayList<>();
    private static CaptureHub.Frame frame;
    private static int frameId = Integer.MIN_VALUE, loadingId = Integer.MIN_VALUE, failedId = Integer.MIN_VALUE;

    private final WeakReference<View> view;

    public WallpaperBackdrop(View view) {
        this.view = new WeakReference<>(view);
        ensure(view);
    }

    /** False when the current wallpaper is known to be unusable (live, missing, unreadable). */
    public static boolean available(Context context) {
        if (Build.VERSION.SDK_INT < 34) return false;
        int id = id(context);
        return id != failedId;
    }

    private static int cachedId = Integer.MIN_VALUE;
    private static long cachedAt;

    /** Wallpaper id, re-read at most once a second: it is a binder call and draws run per frame. */
    private static int id(Context context) {
        long now = android.os.SystemClock.uptimeMillis();
        if (cachedId != Integer.MIN_VALUE && now - cachedAt < 1000) return cachedId;
        cachedId = readId(context); cachedAt = now;
        return cachedId;
    }

    private static int readId(Context context) {
        try {
            WallpaperManager wm = context.getSystemService(WallpaperManager.class);
            if (wm == null) return Integer.MIN_VALUE + 1;
            int lock = wm.getWallpaperId(WallpaperManager.FLAG_LOCK);
            // A negative lock id means the lockscreen shows the system wallpaper.
            return lock >= 0 ? lock : 1_000_000 + wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM);
        } catch (RuntimeException e) { return Integer.MIN_VALUE + 1; }
    }

    private static void ensure(View v) {
        Context context = v.getContext().getApplicationContext();
        int id = id(context);
        if ((frame != null && id == frameId) || id == failedId) return;
        boolean known = false;
        for (WeakReference<View> ref : waiting) if (ref.get() == v) { known = true; break; }
        if (!known) waiting.add(new WeakReference<>(v));
        if (id == loadingId) return;
        loadingId = id;
        Rect display = display(v);
        LOADER.execute(() -> load(context, id, display));
    }

    // Runs inside com.android.systemui, which holds READ_WALLPAPER_INTERNAL (the module app does not
    // need it); a SecurityException on another firmware lands in the RuntimeException fallback.
    @android.annotation.SuppressLint("MissingPermission")
    private static void load(Context context, int id, Rect display) {
        CaptureHub.Frame result = null;
        String failure = null;
        try {
            if (Build.VERSION.SDK_INT < 34) throw new IllegalStateException("sdk");
            WallpaperManager wm = context.getSystemService(WallpaperManager.class);
            int which = wm.getWallpaperId(WallpaperManager.FLAG_LOCK) >= 0 ? WallpaperManager.FLAG_LOCK : WallpaperManager.FLAG_SYSTEM;
            if (wm.getWallpaperInfo(which) != null) failure = "live";
            Drawable d = failure == null ? wm.getDrawable(which) : null;
            if (failure == null && (d == null || d.getIntrinsicWidth() <= 0 || d.getIntrinsicHeight() <= 0)) failure = "noImage";
            if (failure == null) {
                int w = Math.max(1, Math.round(display.width() * SCALE)), h = Math.max(1, Math.round(display.height() * SCALE));
                Bitmap soft = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                // Center-crop, the way the keyguard shows a still wallpaper.
                float k = Math.max(w / (float) d.getIntrinsicWidth(), h / (float) d.getIntrinsicHeight());
                int dw = Math.round(d.getIntrinsicWidth() * k), dh = Math.round(d.getIntrinsicHeight() * k);
                d.setBounds((w - dw) / 2, (h - dh) / 2, (w - dw) / 2 + dw, (h - dh) / 2 + dh);
                d.draw(new Canvas(soft));
                Bitmap hardware = soft.copy(Bitmap.Config.HARDWARE, false);
                soft.recycle();
                if (hardware == null) failure = "upload";
                else result = new CaptureHub.Frame(hardware, new Rect(display), SCALE, false);
            }
        } catch (RuntimeException | OutOfMemoryError e) { failure = e.getClass().getSimpleName(); }
        CaptureHub.Frame loaded = result;
        String error = failure;
        MAIN.post(() -> deliver(id, loaded, error));
    }

    private static void deliver(int id, CaptureHub.Frame loaded, String error) {
        if (loadingId == id) loadingId = Integer.MIN_VALUE;
        if (loaded != null) {
            frame = loaded; frameId = id;
            Probe.log("WALLPAPER_BACKDROP", "id=" + id + " size=" + loaded.bitmap.getWidth() + "x" + loaded.bitmap.getHeight());
        } else {
            failedId = id;
            Probe.log("WALLPAPER_BACKDROP_UNAVAILABLE", "id=" + id + " reason=" + error);
        }
        for (WeakReference<View> ref : waiting) { View v = ref.get(); if (v != null) v.invalidate(); }
        waiting.clear();
    }

    private static Rect display(View v) {
        WindowManager wm = v.getContext().getSystemService(WindowManager.class);
        return wm == null ? new Rect(0, 0, v.getRootView().getWidth(), v.getRootView().getHeight())
                : wm.getMaximumWindowMetrics().getBounds();
    }

    @Override public String name() { return "wallpaper"; }
    @Override public void update(int radiusPx, int tint, float[] radii) { }
    @Override public void draw(Canvas canvas, Rect bounds) { }
    @Override public void setAlpha(int alpha) { }
    @Override public void release() { }
    @Override public CaptureHub.Frame frame() {
        View v = view.get();
        if (v == null) return null;
        if (frameId == id(v.getContext())) return frame;
        ensure(v); // wallpaper changed under a live row: reload, draw veil and edge meanwhile
        return null;
    }
    @Override public View view() { return view.get(); }
    @Override public boolean unavailable() { View v = view.get(); return v != null && failedId == id(v.getContext()); }
}
