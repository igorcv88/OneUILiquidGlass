package io.github.igorcv88.oneuiliquidglass.shots;

import android.app.Activity;
import android.content.Context;
import android.view.*;
import android.widget.FrameLayout;

import io.github.igorcv88.oneuiliquidglass.config.Controls;
import io.github.igorcv88.oneuiliquidglass.ui.*;
import io.github.igorcv88.oneuiliquidglass.ui.kit.*;

/** Host for layoutlib renders: same pages, nav and theme as MainActivity, no lifecycle. */
final class ShotHost implements Host {
    static final class ShotActivity extends Activity {
        ShotActivity(Context base) {
            try {
                java.lang.reflect.Method m = android.content.ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class);
                m.setAccessible(true);
                m.invoke(this, base);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public android.content.res.Resources.Theme getTheme() {
            return getBaseContext().getTheme();
        }

        @Override
        public android.content.res.Resources getResources() {
            return getBaseContext().getResources();
        }

        final java.util.Map<String, MemoryPrefs> prefs = new java.util.HashMap<>();

        @Override
        public android.content.SharedPreferences getSharedPreferences(String name, int mode) {
            return prefs.computeIfAbsent(name, n -> new MemoryPrefs());
        }

        @Override
        public Object getSystemService(String name) {
            return getBaseContext().getSystemService(name);
        }
    }

    final ShotActivity activity;
    final Theme theme;
    final AppModel model;
    Page[] pages;
    HomePage home;
    SurfacesPage surfaces;
    SettingsPage settings;

    ShotHost(Context base, boolean dark) {
        activity = new ShotActivity(base);
        theme = new Theme(base, dark, true);
        model = new AppModel(activity, java.util.concurrent.Executors.newSingleThreadExecutor());
        model.repo.preferences().edit().putBoolean("enabled", true).apply();
        // The module answered for the current generation (what a working install shows).
        activity.getSharedPreferences("engine_status", 0).edit().putLong("readAt", System.currentTimeMillis())
                .putLong("readGeneration", 1000).apply();
    }

    /** Pretends the phone reported these debug.oulg.* values (SystemPropertyReader's last read). */
    static void observe(java.util.Map<String, String> values) {
        try {
            Class<?> r = io.github.igorcv88.oneuiliquidglass.config.SystemPropertyReader.class;
            java.lang.reflect.Field f = r.getDeclaredField("observed");
            f.setAccessible(true);
            f.set(null, java.util.Collections.unmodifiableMap(new java.util.HashMap<>(values)));
            java.lang.reflect.Field a = r.getDeclaredField("available");
            a.setAccessible(true);
            a.set(null, true);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    /** A sheet over a dimmed screen, as it shows on the phone. */
    View overlay(View screen, View panel) {
        FrameLayout root = new FrameLayout(activity);
        root.addView(screen, new FrameLayout.LayoutParams(-1, -1));
        View scrim = new View(activity);
        scrim.setBackgroundColor(theme.dark ? 0x8c000000 : 0x59000000);
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        panel.setPadding(0, 0, 0, theme.dp(16));
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        p.topMargin = theme.dp(80);
        root.addView(panel, p);
        return root;
    }

    /** One destination with the floating bar, laid out like MainActivity. */
    View screen(int tab, Controls.Surface surface, int width, int height) {
        FrameLayout root = new FrameLayout(activity);
        root.setBackgroundColor(theme.bg);
        home = new HomePage(this);
        surfaces = new SurfacesPage(this);
        settings = new SettingsPage(this);
        pages = new Page[] {home, surfaces, settings};
        if (surface != null) surfaces.show(surface);
        View page = pages[tab].view();
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));
        GlassNav nav = new GlassNav(activity, theme, new String[] {"Início", "Superfícies", "Ajustes"},
                new Icon[] {Icon.HOME, Icon.LAYERS, Icon.SETTINGS}, tab, i -> {});
        FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        int gesture = theme.dp(16);
        np.bottomMargin = gesture + theme.dp(12);
        root.addView(nav, np);
        nav.source(page);
        int top = theme.dp(36);
        int bottom = gesture + theme.dp(12) + nav.expandedHeight() + theme.dp(24);
        for (Page p : pages) p.insets(top, bottom);
        for (Page p : pages) p.bind();
        return root;
    }

    @Override public Activity activity() { return activity; }
    @Override public Theme theme() { return theme; }
    @Override public AppModel model() { return model; }
    @Override public void preview(String key, String value, Controls.Channel channel) { model.draft(key, value); surfaces.previewChanged(channel); }
    @Override public void endPreview() { surfaces.previewChanged(Controls.Channel.NONE); }
    @Override public void feedback(String message) {}
    @Override public void openControl(String key, Controls.Surface surface) {}
    @Override public void showSurface(Controls.Surface surface) {}
    @Override public void testNotification() {}
    @Override public void openTestOptions() {}
    @Override public void openLens() {}
    @Override public void openSync() {}
    @Override public void openConflicts() {}
    @Override public void requestTest(int count, int delayMs) {}
    @Override public void copy(String text) {}
    @Override public void rebuild() {}
    @Override public void exportSettings() {}
    @Override public void importSettings() {}
}
