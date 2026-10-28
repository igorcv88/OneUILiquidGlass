package io.github.igorcv88.oneuiliquidglass;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;

import io.github.igorcv88.oneuiliquidglass.config.*;
import io.github.igorcv88.oneuiliquidglass.ui.*;
import io.github.igorcv88.oneuiliquidglass.ui.kit.*;

/**
 * Shell: three destinations under a floating glass bar, a shared model, and the flows that need
 * the Activity (permission, documents, clipboard). Pages are built once and bound in place; a
 * theme, font or orientation change rebuilds the views without recreating the Activity and keeps
 * the destination, surface and scroll positions.
 */
public final class MainActivity extends Activity implements Host {
    private static final int REQ_NOTIFY = 71, REQ_EXPORT = 91, REQ_IMPORT = 92;
    private final java.util.concurrent.ExecutorService io = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private AppModel model;
    private Theme theme;
    private FrameLayout root, pages;
    private GlassNav nav;
    private TextView toast;
    private HomePage home;
    private SurfacesPage surfaces;
    private SettingsPage settings;
    private Page[] all;
    private int tab;
    private int pendingCount = 1, pendingDelay;
    private boolean permissionPending, imeVisible;
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            model.pollSync();
            handler.postDelayed(this, 2000);
        }
    };
    private final AppModel.Listener bindAll = this::bindAll;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        model = new AppModel(this, io);
        model.listen(bindAll);
        Window w = getWindow();
        w.setDecorFitsSystemWindows(false);
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        w.setNavigationBarContrastEnforced(false);
        Bundle ui = state == null ? new Bundle() : state.getBundle("ui");
        if (state != null) {
            permissionPending = state.getBoolean("permissionPending");
            pendingCount = state.getInt("pendingCount", 1);
            pendingDelay = state.getInt("pendingDelay");
        }
        build(ui == null ? new Bundle() : ui);
        handleIntent(getIntent());
    }

    private boolean dark() {
        String mode = model.ui.getString("theme", "system");
        return mode.equals("dark") || mode.equals("system") && Theme.systemDark(this);
    }

    private void build(Bundle ui) {
        theme = new Theme(this, dark(), model.ui.getBoolean("reduceMotion", false));
        tab = ui.getInt("tab", tab);
        root = new FrameLayout(this);
        root.setBackgroundColor(theme.bg);
        pages = new FrameLayout(this);
        root.addView(pages, new FrameLayout.LayoutParams(-1, -1));
        home = new HomePage(this);
        surfaces = new SurfacesPage(this);
        settings = new SettingsPage(this);
        all = new Page[] {home, surfaces, settings};
        for (int i = 0; i < all.length; i++) {
            View v = all[i].view();
            v.setVisibility(i == tab ? View.VISIBLE : View.GONE);
            pages.addView(v, new FrameLayout.LayoutParams(-1, -1));
            final int index = i;
            if (all[i] != surfaces)
                all[i].scroller().setOnScrollChangeListener((sv, x, y, ox, oy) -> {
                    if (index == tab) nav.scrolled(y, y - oy);
                });
        }
        surfaces.onScroll = (y, dy) -> {
            if (tab == 1) nav.scrolled(y, dy);
        };
        Bundle surfaceState = ui.getBundle("surfaces");
        if (surfaceState != null) surfaces.restore(surfaceState);
        nav = new GlassNav(this, theme, new String[] {"Início", "Superfícies", "Ajustes"},
                new Icon[] {Icon.HOME, Icon.LAYERS, Icon.SETTINGS}, tab, this::go);
        FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        root.addView(nav, np);
        nav.source(all[tab].view());
        toast = Ui.text(this, theme, "", Theme.LABEL, theme.dark ? 0xff0e141c : 0xfff2f6fb, true);
        toast.setBackground(Ui.rounded(theme.dark ? 0xf2e9f0f8 : 0xf21b2330, theme.dpf(20)));
        toast.setPadding(theme.dp(18), theme.dp(11), theme.dp(18), theme.dp(11));
        toast.setGravity(Gravity.CENTER);
        toast.setAlpha(0);
        toast.setVisibility(View.GONE);
        toast.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        root.addView(toast, tp);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            boolean ime = insets.isVisible(WindowInsets.Type.ime());
            int navBottom = bars.bottom + theme.dp(12);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) nav.getLayoutParams();
            lp.bottomMargin = navBottom;
            lp.leftMargin = bars.left + theme.dp(20);
            lp.rightMargin = bars.right + theme.dp(20);
            nav.setLayoutParams(lp);
            FrameLayout.LayoutParams fp = (FrameLayout.LayoutParams) toast.getLayoutParams();
            fp.bottomMargin = navBottom + nav.expandedHeight() + theme.dp(12);
            toast.setLayoutParams(fp);
            int bottom = navBottom + nav.expandedHeight() + theme.dp(24);
            pages.setPadding(bars.left, 0, bars.right, 0);
            for (Page p : all) p.insets(bars.top, bottom);
            if (ime != imeVisible) {
                imeVisible = ime;
                nav.imeVisible(ime);
            }
            return insets;
        });
        setContentView(root);
        appearance();
        updateBack();
        int[] scrolls = ui.getIntArray("scrolls");
        bindAll();
        if (scrolls != null)
            for (int i = 0; i < all.length && i < scrolls.length; i++) {
                final int y = scrolls[i], index = i;
                if (index != 1) all[index].scroller().post(() -> all[index].scroller().scrollTo(0, y));
            }
    }

    private void appearance() {
        WindowInsetsController c = getWindow().getInsetsController();
        if (c == null) return;
        int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        c.setSystemBarsAppearance(theme.dark ? 0 : light, light);
    }

    /** Rebuild after a theme, motion, font or size change, keeping where the person was. */
    @Override
    public void rebuild() {
        Bundle ui = saveUi();
        model.clearDrafts();
        build(ui);
    }

    private Bundle saveUi() {
        Bundle b = new Bundle();
        b.putInt("tab", tab);
        int[] scrolls = new int[all.length];
        for (int i = 0; i < all.length; i++) scrolls[i] = all[i].scroller().getScrollY();
        b.putIntArray("scrolls", scrolls);
        Bundle s = new Bundle();
        surfaces.save(s);
        b.putBundle("surfaces", s);
        return b;
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        rebuild();
    }

    private void go(int index) {
        if (index == tab) return;
        View from = all[tab].view(), to = all[index].view();
        tab = index;
        to.setAlpha(theme.reduceMotion ? 1 : 0);
        to.setVisibility(View.VISIBLE);
        to.animate().alpha(1).setDuration(theme.ms(Theme.FAST + 40)).start();
        from.setVisibility(View.GONE);
        nav.source(to);
        nav.expand();
        all[index].bind();
        updateBack();
    }

    private void bindAll() {
        if (all == null) return;
        for (Page p : all) p.bind();
    }

    @Override
    protected void onResume() {
        super.onResume();
        model.refreshObserved(null);
        handler.removeCallbacks(poll);
        handler.post(poll);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(poll);
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle b) {
        super.onSaveInstanceState(b);
        b.putBundle("ui", saveUi());
        b.putBoolean("permissionPending", permissionPending);
        b.putInt("pendingCount", pendingCount);
        b.putInt("pendingDelay", pendingDelay);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        model.close();
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent != null && "surfaces".equals(intent.getStringExtra("destination"))) {
            nav.select(1, false);
            if (tab != 1) go(1);
        }
    }

    /** Back from another destination returns to Início; registered only while away from it. */
    private final android.window.OnBackInvokedCallback backHome = () -> {
        nav.select(0, true);
        go(0);
    };
    private boolean backRegistered;

    private void updateBack() {
        boolean want = tab != 0;
        if (want == backRegistered) return;
        backRegistered = want;
        if (want) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, backHome);
        else getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backHome);
    }

    // ---------------------------------------------------------------- Host

    @Override
    public Activity activity() {
        return this;
    }

    @Override
    public Theme theme() {
        return theme;
    }

    @Override
    public AppModel model() {
        return model;
    }

    @Override
    public void preview(String key, String value, Controls.Channel channel) {
        model.draft(key, value);
        surfaces.previewChanged(channel);
    }

    @Override
    public void endPreview() {
        surfaces.previewChanged(Controls.Channel.NONE);
    }

    private final Runnable hideToast = () -> toast.animate().alpha(0).setDuration(theme.ms(Theme.STANDARD_MS))
            .withEndAction(() -> toast.setVisibility(View.GONE)).start();

    @Override
    public void feedback(String message) {
        if (message == null || message.isEmpty()) return;
        toast.setText(message);
        toast.setVisibility(View.VISIBLE);
        toast.animate().alpha(1).setDuration(theme.ms(Theme.FAST)).start();
        handler.removeCallbacks(hideToast);
        handler.postDelayed(hideToast, 2400);
    }

    @Override
    public void openControl(String key, Controls.Surface surface) {
        if (!Controls.editable(key)) return;
        ControlSheets.open(this, key, surface);
    }

    @Override
    public void showSurface(Controls.Surface s) {
        surfaces.show(s);
        nav.select(1, true);
        go(1);
    }

    @Override
    public void testNotification() {
        requestTest(1, 0);
    }

    @Override
    public void openTestOptions() {
        ControlSheets.tests(this);
    }

    @Override
    public void openLens() {
        ControlSheets.lens(this);
    }

    @Override
    public void openSync() {
        ControlSheets.sync(this);
    }

    @Override
    public void openConflicts() {
        ControlSheets.conflicts(this);
    }

    // ---------------------------------------------------------------- test notification

    @Override
    public void requestTest(int count, int delay) {
        pendingCount = count;
        pendingDelay = delay;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissionPending = true;
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
            return;
        }
        sendTest();
    }

    private void sendTest() {
        if (pendingDelay > 0) {
            TestNotifications.schedule(this, pendingCount, pendingDelay, o -> {
                if (!isDestroyed()) home.showTestResult(TestNotifications.describe(o), problem(o));
            });
            home.showTestResult(TestNotifications.describe(TestNotifications.Outcome.SCHEDULED), false);
            feedback("Chega em 5 segundos");
            return;
        }
        TestNotifications.Outcome o = TestNotifications.post(this, pendingCount, true);
        home.showTestResult(TestNotifications.describe(o), problem(o));
        if (o == TestNotifications.Outcome.BLOCKED_APP) openAppNotifications();
        else if (o == TestNotifications.Outcome.BLOCKED_CHANNEL) ControlSheets.tests(this);
        else feedback(pendingCount > 1 ? "Notificações enviadas" : "Notificação enviada");
    }

    private static boolean problem(TestNotifications.Outcome o) {
        return o != TestNotifications.Outcome.POSTED && o != TestNotifications.Outcome.SCHEDULED;
    }

    private void openAppNotifications() {
        startActivity(new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName()));
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request != REQ_NOTIFY || !permissionPending) return;
        permissionPending = false;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) sendTest();
        else {
            home.showTestResult("Sem permissão, o teste não foi enviado. Permita notificações nos ajustes do app.", true);
            feedback("Permissão de notificações negada");
        }
    }

    // ---------------------------------------------------------------- backup

    @Override
    public void exportSettings() {
        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json")
                .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "OneUILiquidGlass-ajustes.json"), REQ_EXPORT);
    }

    @Override
    public void importSettings() {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json")
                .addCategory(Intent.CATEGORY_OPENABLE), REQ_IMPORT);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent intent) {
        super.onActivityResult(request, result, intent);
        if (result != RESULT_OK || intent == null || intent.getData() == null) return;
        Uri uri = intent.getData();
        if (request != REQ_EXPORT && request != REQ_IMPORT) return;
        boolean enabled = model.state().enabled;
        java.util.Map<String, String> values = model.repo.overrides();
        io.execute(() -> {
            try {
                if (request == REQ_EXPORT) {
                    SystemPropertyReader.refresh();
                    android.content.SharedPreferences sf = model.lensPrefs();
                    String json = PresetCodec.encode(values, enabled,
                            Build.MANUFACTURER + " " + Build.MODEL + " · " + Build.DISPLAY,
                            SystemPropertyReader.snapshot(), SystemPropertyReader.available(),
                            io.github.igorcv88.oneuiliquidglass.live.CompositorValues.compiledValues(sf),
                            sf.getString("compiledGeneration", ""), sf.getLong("compiledAt", 0));
                    try (java.io.OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                        if (out == null) throw new java.io.IOException("destino indisponível");
                        out.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                    deliver(() -> feedback("Ajustes exportados. O perfil restaura os valores salvos no app; os snapshots de diagnóstico não reproduzem toda a aparência."));
                } else {
                    String data;
                    try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                        if (in == null) throw new java.io.IOException("arquivo indisponível");
                        byte[] bytes = in.readNBytes(PresetCodec.MAX_BYTES + 1);
                        if (bytes.length > PresetCodec.MAX_BYTES) throw new java.io.IOException("arquivo muito grande");
                        data = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                    }
                    deliver(() -> ControlSheets.review(this, data));
                }
            } catch (Exception e) {
                deliver(() -> feedback("Falhou: " + e.getMessage()));
            }
        });
    }

    @Override
    public void copy(String text) {
        ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("One UI Liquid Glass", text));
        feedback("Copiado");
    }

    private void deliver(Runnable r) {
        runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed()) r.run();
        });
    }
}
