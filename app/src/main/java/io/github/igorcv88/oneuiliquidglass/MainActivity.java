package io.github.igorcv88.oneuiliquidglass;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;

import io.github.igorcv88.oneuiliquidglass.config.*;
import io.github.igorcv88.oneuiliquidglass.live.RootAccess;
import io.github.igorcv88.oneuiliquidglass.live.SfLiveController;
import io.github.igorcv88.oneuiliquidglass.ui.*;

import java.util.*;

/**
 * Navigation shell only; registry, bridge, preview, notification and module operations are
 * separate.
 */
public final class MainActivity extends Activity {
    private ConfigRepository repo;
    private SfLiveController sfLive;
    private RootAccess rootAccess;
    private final Set<String> expandedGroups = new HashSet<>();
    private final Map<String, String> drafts = new HashMap<>();
    private TextView applyStatus;
    private LinearLayout pinned;
    private LinearLayout root, content, rows;
    private MaterialPreview preview;
    private TextView testStatus;
    private int destination, backdrop = 2;
    private String surface = "headsup", query = "";
    private boolean favorites, allSettings, light;
    private int pendingCount = 1, pendingDelay;
    private boolean permissionPending, postingExpanded = true;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService io =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private Runnable scheduled;
    private final Runnable applicationPoll =
            new Runnable() {
                public void run() {
                    updateApplicationStatus();
                    handler.postDelayed(this, 1500);
                }
            };
    private int ink, muted, bg, card;

    private int dp(float n) {
        return (int) (getResources().getDisplayMetrics().density * n + .5f);
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        repo = new ConfigRepository(this);
        sfLive = new SfLiveController(this, io);
        rootAccess = new RootAccess(this, io);
        light = repo.preferences().getBoolean("ui.light", false);
        if (state != null) {
            destination = state.getInt("destination");
            surface = state.getString("surface", "headsup");
            backdrop = state.getInt("backdrop", 2);
            allSettings = state.getBoolean("all");
            query = state.getString("query", "");
            permissionPending = state.getBoolean("permissionPending");
            pendingCount = state.getInt("pendingCount", 1);
            pendingDelay = state.getInt("pendingDelay");
            postingExpanded = state.getBoolean("expanded", true);
            ArrayList<String> opened = state.getStringArrayList("openedGroups");
            if (opened != null) expandedGroups.addAll(opened);
        }
        if ("surfaces".equals(getIntent().getStringExtra("destination"))) destination = 1;
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(applicationPoll);
        handler.post(applicationPoll);
        io.execute(
                () -> {
                    SystemPropertyReader.refresh();
                    deliver(
                            () -> {
                                if (!isFinishing() && !isDestroyed()) render();
                            });
                });
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(applicationPoll);
        super.onPause();
    }

    private void refreshObserved() {
        io.execute(
                () -> {
                    SystemPropertyReader.refresh();
                    deliver(
                            () -> {
                                if (!isFinishing() && !isDestroyed()) render();
                            });
                });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if ("surfaces".equals(intent.getStringExtra("destination"))) {
            destination = 1;
            render();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle b) {
        super.onSaveInstanceState(b);
        b.putInt("destination", destination);
        b.putString("surface", surface);
        b.putInt("backdrop", backdrop);
        b.putBoolean("all", allSettings);
        b.putString("query", query);
        b.putBoolean("permissionPending", permissionPending);
        b.putInt("pendingCount", pendingCount);
        b.putInt("pendingDelay", pendingDelay);
        b.putBoolean("expanded", postingExpanded);
        b.putStringArrayList("openedGroups", new ArrayList<>(expandedGroups));
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (sfLive != null) sfLive.close();
        if (rootAccess != null) rootAccess.close();
        io.shutdownNow();
        super.onDestroy();
    }

    private void render() {
        ink = light ? 0xff182538 : 0xffedf3fc;
        muted = light ? 0xff4c6178 : 0xff9eafc5;
        bg = light ? 0xffedf1f7 : 0xff101722;
        card = light ? 0xfff9fbff : 0xff1a2535;
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);
        root.setOnApplyWindowInsetsListener(
                (v, insets) -> {
                    android.graphics.Insets system =
                            insets.getInsets(WindowInsets.Type.systemBars());
                    v.setPadding(system.left, system.top, system.right, system.bottom);
                    return insets;
                });
        sfLive.listen(model -> {});
        rootAccess.listen(() -> {});
        preview = null;
        rows = null;
        applyStatus = text("", 12, muted);
        applyStatus.setPadding(dp(22), dp(8), dp(22), dp(8));
        applyStatus.setOnClickListener(v -> applicationDetails());
        root.addView(applyStatus);
        updateApplicationStatus();
        pinned = column();
        root.addView(pinned);
        FrameLayout body = new FrameLayout(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        content = column();
        content.setPadding(dp(20), dp(14), dp(20), dp(112));
        scroll.addView(content);
        body.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        FloatingGlassNav nav =
                new FloatingGlassNav(
                        this,
                        scroll,
                        light,
                        destination,
                        target -> {
                            destination = target;
                            render();
                        });
        FrameLayout.LayoutParams navLayout =
                new FrameLayout.LayoutParams(
                        Math.min(getResources().getDisplayMetrics().widthPixels - dp(40), dp(400)),
                        dp(72),
                        Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        navLayout.bottomMargin = dp(14);
        body.addView(nav, navLayout);
        scroll.setOnScrollChangeListener((v, x, y, ox, oy) -> nav.scrolling(y, oy));
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        root.requestApplyInsets();
        if (destination == 0) home();
        else if (destination == 1) surfaces();
        else diagnostics();
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private TextView text(String title, int size, int color) {
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setPadding(0, dp(6), 0, dp(6));
        return t;
    }

    private void title(String s) {
        content.addView(text(s, 28, ink));
    }

    private void note(LinearLayout parent, String s) {
        parent.addView(text(s, 13, muted));
    }

    private Button button(String s, Runnable action) {
        Button b = new Button(this);
        b.setText(s);
        UiStyle.button(b, light, false);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private void action(LinearLayout p, String s, Runnable r) {
        Button b = button(s, r);
        UiStyle.space(b, 4);
        if (s.equals("Enviar notificação de teste")) UiStyle.button(b, light, true);
        p.addView(b);
    }

    private LinearLayout group(LinearLayout parent, String heading) {
        parent.addView(text(heading, 18, ink));
        LinearLayout p = column();
        p.setPadding(dp(16), dp(8), dp(16), dp(10));
        GradientDrawable d = new GradientDrawable();
        d.setColor(card);
        d.setCornerRadius(dp(22));
        p.setBackground(d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(14);
        parent.addView(p, lp);
        return p;
    }

    private void toggle(
            LinearLayout p,
            String name,
            boolean checked,
            java.util.function.Consumer<Boolean> listener) {
        Switch s = new Switch(this);
        s.setText(name);
        UiStyle.toggle(s, light);
        s.setTextColor(ink);
        s.setTextSize(15);
        s.setPadding(0, dp(8), 0, dp(8));
        s.setMinHeight(dp(52));
        s.setChecked(checked);
        s.setOnCheckedChangeListener((b, on) -> listener.accept(on));
        p.addView(s);
    }

    private Map<String, String> values() {
        Map<String, String> m = new LinkedHashMap<>();
        for (SettingSpec s : SettingsRegistry.all()) m.put(s.key, repo.desired(s));
        m.putAll(repo.overrides());
        m.putAll(drafts);
        return m;
    }

    private void home() {
        LinearLayout brand = new LinearLayout(this);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.oulg_launcher);
        logo.setContentDescription("Cartões de vidro One UI Liquid Glass");
        brand.addView(logo, new LinearLayout.LayoutParams(dp(72), dp(72)));
        TextView heading = text("One UI\nLiquid Glass", 27, ink);
        heading.setPadding(dp(14), 0, 0, 0);
        brand.addView(heading);
        content.addView(brand);
        note(content, "Escolha uma superfície, ajuste o material e compare na amostra.");
        LinearLayout main = group(content, "Notificações");
        toggle(
                main,
                "Ativar vidro nas notificações",
                repo.preferences().getBoolean(Config.KEY_ENABLED, false),
                on -> {
                    repo.flag(Config.KEY_ENABLED, on);
                    render();
                });
        note(
                main,
                "Personalize Pop-up, Bloqueio e Central na aba Superfícies. Os ajustes Java são"
                        + " enviados ao vivo.");
        moduleCard(content);
        notifications(content);
        presets();
        LinearLayout appearance = group(content, "Interface");
        toggle(
                appearance,
                "Tema claro",
                light,
                on -> {
                    light = on;
                    repo.preferences().edit().putBoolean("ui.light", on).apply();
                    render();
                });
    }

    private String engineStatus() {
        android.content.SharedPreferences status =
                getSharedPreferences("engine_status", MODE_PRIVATE);
        long read = status.getLong("readAt", 0);
        boolean fresh = System.currentTimeMillis() - read < 30000;
        long gen = status.getLong("readGeneration", -1);
        return fresh
                ? "SystemUI respondeu · geração lida "
                        + gen
                        + " / desejada "
                        + repo.generation()
                        + ". Renderização não confirmada."
                : "SystemUI sem confirmação recente. Verifique escopo LSPosed e reinicie SystemUI"
                        + " se necessário. Aplicação pendente/desconhecida.";
    }

    private void notifications(LinearLayout parent) {
        LinearLayout tests = group(parent, "Notificação de teste");
        testStatus = text(TestNotifications.status(this), 13, muted);
        tests.addView(testStatus);
        action(tests, "Enviar notificação de teste", () -> requestTest(1, 0, true));
        tests =
                disclosure(
                        tests, "Mais testes", "Agendamento, várias notificações e canal", "tests");
        final LinearLayout extraTests = tests;
        action(extraTests, "Heads-up em 5 s", () -> requestTest(1, 5000, true));
        action(tests, "Várias na central", () -> requestTest(3, 0, true));
        action(
                tests,
                "Descartar testes / cancelar agendamento",
                () -> {
                    if (scheduled != null) handler.removeCallbacks(scheduled);
                    scheduled = null;
                    TestNotifications.clear(this);
                    testStatus.setText("Testes descartados; agendamento cancelado.");
                });
        action(
                tests,
                "Ajustes do canal",
                () ->
                        startActivity(
                                new Intent(
                                                android.provider.Settings
                                                        .ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                                        .putExtra(
                                                android.provider.Settings.EXTRA_APP_PACKAGE,
                                                getPackageName())
                                        .putExtra(
                                                android.provider.Settings.EXTRA_CHANNEL_ID,
                                                TestNotifications.CHANNEL)));
        note(
                tests,
                "Use pop-up detalhado da Samsung. IMPORTANCE_HIGH não garante heads-up; canal, DND"
                        + " e preferências do sistema podem impedir.");
    }

    private void requestTest(int count, int delay, boolean expanded) {
        pendingCount = count;
        pendingDelay = delay;
        postingExpanded = expanded;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            permissionPending = true;
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 71);
            return;
        }
        scheduleTest();
    }

    private void scheduleTest() {
        if (scheduled != null) handler.removeCallbacks(scheduled);
        scheduled =
                () -> {
                    try {
                        TestNotifications.post(this, pendingCount, postingExpanded);
                        if (testStatus != null)
                            testStatus.setText(
                                    "Notificação enviada ao Android. "
                                            + TestNotifications.status(this));
                    } catch (RuntimeException e) {
                        toast("Não foi possível publicar: " + e.getClass().getSimpleName());
                    }
                    scheduled = null;
                };
        handler.postDelayed(scheduled, pendingDelay);
        if (testStatus != null)
            testStatus.setText(
                    pendingDelay > 0
                            ? "Agendada para 5 s. Pode trocar de app. Use Descartar para cancelar."
                            : "Publicando teste…");
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 71 && permissionPending) {
            permissionPending = false;
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED)
                scheduleTest();
            else {
                if (testStatus != null)
                    testStatus.setText("Permissão negada. O teste não foi publicado.");
                toast("Teste cancelado: permissão negada.");
            }
        }
    }

    private void surfaces() {
        pinned.setPadding(dp(20), 0, dp(20), 0);
        pinned.addView(text("Superfícies", 22, ink));
        LinearLayout segmented = new LinearLayout(this);
        String[] ids = {"headsup", "keyguard", "shade"}, labels = {"Pop-up", "Bloqueio", "Central"};
        for (int i = 0; i < 3; i++) {
            String id = ids[i];
            Button choice =
                    button(
                            labels[i],
                            () -> {
                                surface = id;
                                allSettings = false;
                                render();
                            });
            UiStyle.button(choice, light, surface.equals(id));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1);
            lp.setMarginEnd(dp(4));
            segmented.addView(choice, lp);
        }
        pinned.addView(segmented);
        preview = new MaterialPreview(this);
        preview.surface = surface;
        preview.light = light;
        preview.background(backdrop);
        preview.bind(values());
        pinned.addView(
                preview,
                new LinearLayout.LayoutParams(
                        -1, dp(getResources().getConfiguration().screenHeightDp < 550 ? 98 : 168)));
        note(
                pinned,
                "Simulação aproximada · fundo, Samsung blur e SurfaceFlinger mudam o resultado.");
        LinearLayout gates = group(content, "Esta superfície");
        String flag = "surface." + surface + ".enabled";
        toggle(
                gates,
                "Ativar vidro",
                repo.preferences().getBoolean(flag, true),
                on -> {
                    repo.flag(flag, on);
                    updateApplicationStatus();
                    preview.bind(values());
                });
        LinearLayout filters =
                disclosure(
                        content, "Busca e amostra", "Buscar, favoritos, fundo e tema", "filters");
        EditText search = new EditText(this);
        search.setTextColor(ink);
        search.setHintTextColor(muted);
        search.setHint("Buscar controle ou prop…");
        search.setSingleLine(true);
        search.setText(query);
        filters.addView(search);
        toggle(
                filters,
                "Somente favoritos",
                favorites,
                on -> {
                    favorites = on;
                    renderRows();
                });
        toggle(
                filters,
                "Incluir todas as superfícies",
                allSettings,
                on -> {
                    allSettings = on;
                    renderRows();
                });
        Spinner backgrounds = new Spinner(this);
        backgrounds.setAdapter(
                new ArrayAdapter<>(
                        this,
                        android.R.layout.simple_spinner_dropdown_item,
                        new String[] {"Fundo claro", "Fundo escuro", "Colorido", "Quadriculado"}));
        backgrounds.setSelection(backdrop);
        backgrounds.setOnItemSelectedListener(
                new AdapterView.OnItemSelectedListener() {
                    public void onNothingSelected(AdapterView<?> p) {}

                    public void onItemSelected(AdapterView<?> p, View v, int i, long id) {
                        backdrop = i;
                        preview.background(i);
                    }
                });
        filters.addView(backgrounds);
        toggle(
                filters,
                "Amostra em tema claro",
                light,
                on -> {
                    preview.light = on;
                    preview.invalidate();
                });
        rows = column();
        content.addView(rows);
        search.addTextChangedListener(
                new TextWatcher() {
                    public void beforeTextChanged(CharSequence s, int a, int c, int f) {}

                    public void onTextChanged(CharSequence s, int a, int b, int c) {
                        query = s.toString();
                        renderRows();
                    }

                    public void afterTextChanged(Editable e) {}
                });
        renderRows();
        LinearLayout reset =
                disclosure(content, "Restaurar", "Padrões desta superfície", "reset." + surface);
        action(
                reset,
                "Restaurar padrões desta superfície",
                () ->
                        confirm(
                                "Restaurar padrões?",
                                "Um snapshot permite desfazer. Não reinicia SystemUI nem o"
                                        + " compositor.",
                                () -> {
                                    Map<String, String> defaults = new LinkedHashMap<>();
                                    for (SettingSpec spec : SettingsRegistry.all())
                                        if (spec.type != SettingSpec.Type.READONLY
                                                && spec.surfaceScope.equals(surface))
                                            defaults.put(spec.key, spec.defaultValue);
                                    repo.replace(defaults, false);
                                    render();
                                }));
    }

    private void featureControls(LinearLayout parent) {
        LinearLayout gates =
                disclosure(
                        parent,
                        "Camadas do efeito",
                        "Lente, bordas, reflexo, contraste e blur",
                        "features." + surface);
        String[] features = {"edge", "tonemap", "glint", "lens", "per_card_blur"};
        String[] names = {
            "Iluminação da borda",
            "Proteção de contraste",
            "Reflexo de luz",
            "Lente",
            "Blur por cartão"
        };
        for (int i = 0; i < features.length; i++) {
            if (features[i].equals("per_card_blur") && !surface.equals("shade")) continue;
            String key = "surface." + surface + ".feature." + features[i] + ".enabled";
            toggle(
                    gates,
                    names[i],
                    repo.preferences().getBoolean(key, true),
                    on -> {
                        repo.flag(key, on);
                        updateApplicationStatus();
                        preview.bind(values());
                    });
        }
        note(
                gates,
                "Atuam na camada APK ao vivo. O shader SurfaceFlinger tem parâmetros próprios nos"
                        + " grupos Compositor.");
    }

    private LinearLayout disclosure(LinearLayout parent, String title, String caption, String id) {
        LinearLayout shell = group(parent, title);
        LinearLayout body = column();
        Button header = button((expandedGroups.contains(id) ? "⌃  " : "⌄  ") + caption, () -> {});
        UiStyle.button(header, light, false);
        shell.addView(header);
        shell.addView(body);
        body.setVisibility(expandedGroups.contains(id) ? View.VISIBLE : View.GONE);
        header.setOnClickListener(
                v -> {
                    boolean open = body.getVisibility() != View.VISIBLE;
                    if (open) expandedGroups.add(id);
                    else expandedGroups.remove(id);
                    body.setVisibility(open ? View.VISIBLE : View.GONE);
                    header.setText((open ? "⌃  " : "⌄  ") + caption);
                    header.setContentDescription(
                            title + (open ? ", expandido. Recolher" : ", recolhido. Expandir"));
                });
        header.setContentDescription(
                title
                        + (expandedGroups.contains(id)
                                ? ", expandido. Recolher"
                                : ", recolhido. Expandir"));
        return body;
    }

    private void renderRows() {
        if (rows == null) return;
        rows.removeAllViews();
        String q = query.toLowerCase(Locale.ROOT).trim();
        boolean filtered = !q.isEmpty() || favorites;
        Set<String> main = new LinkedHashSet<>(ControlLayout.primary(surface));
        if (!filtered && !allSettings) {
            LinearLayout principal = group(rows, "Ajustes principais");
            note(
                    principal,
                    "Arraste para comparar. Ao soltar, o ajuste Java é salvo ao vivo. Valores do"
                            + " compositor precisam de aplicação manual.");
            for (String key : main) {
                SettingSpec spec = SettingsRegistry.get(key);
                if (spec != null) settingRow(principal, spec);
            }
            featureControls(rows);
            rows.addView(text("Personalizar por efeito", 21, ink));
            note(
                    rows,
                    "Abra um grupo por vez. Os valores são preservados quando uma camada está"
                            + " desligada.");
        }
        Map<String, List<SettingSpec>> groups = new LinkedHashMap<>();
        int count = 0;
        for (SettingSpec spec : SettingsRegistry.all()) {
            if (filtered) {
                if (!q.isEmpty()
                        && !(spec.title + " " + spec.explanation + " " + spec.fullKey())
                                .toLowerCase(Locale.ROOT)
                                .contains(q)) continue;
                if (favorites && !repo.preferences().getBoolean("favorite." + spec.key, false))
                    continue;
            } else {
                if (!allSettings
                        && !spec.surfaceScope.equals("all")
                        && !spec.surfaceScope.equals(surface)) continue;
                if (!allSettings && main.contains(spec.key)) continue;
            }
            groups.computeIfAbsent(ControlLayout.category(spec), k -> new ArrayList<>()).add(spec);
            count++;
        }
        for (Map.Entry<String, List<SettingSpec>> entry : groups.entrySet()) {
            LinearLayout section =
                    filtered
                            ? group(rows, entry.getKey())
                            : disclosure(
                                    rows,
                                    entry.getKey(),
                                    entry.getValue().size() + " ajustes · tocar para abrir",
                                    surface + "." + allSettings + "." + entry.getKey());
            for (SettingSpec spec : entry.getValue()) settingRow(section, spec);
        }
        note(
                rows,
                count
                        + " controles nos grupos · "
                        + SettingsRegistry.all().size()
                        + " no catálogo. Buscar também encontra opções das outras superfícies.");
    }

    private void settingRow(LinearLayout parent, SettingSpec spec) {
        LinearLayout row = column();
        row.setPadding(0, dp(12), 0, dp(12));
        parent.addView(row);
        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading = text(spec.title, 16, ink);
        line.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        Button details =
                button(
                        "ⓘ",
                        () ->
                                new SettingsSheet(
                                                this,
                                                repo,
                                                spec,
                                                surface,
                                                light,
                                                backdrop,
                                                () -> {
                                                    if (preview != null) preview.bind(values());
                                                    renderRows();
                                                    updateApplicationStatus();
                                                })
                                        .show());
        UiStyle.button(details, light, false);
        details.setContentDescription("Detalhes, valor numérico e reset de " + spec.title);
        line.addView(details, new LinearLayout.LayoutParams(dp(48), dp(48)));
        row.addView(line);
        note(row, spec.explanation.replace("`", ""));
        TextView value =
                text(
                        repo.desired(spec) + " " + spec.unit + "  ·  padrão " + spec.defaultValue,
                        14,
                        light ? 0xff23758a : 0xff83dce8);
        row.addView(value);
        TextView status =
                text(
                        ControlLayout.application(spec),
                        12,
                        spec.applyMode == SettingSpec.ApplyMode.SF_RECOMPILE ? 0xffd39179 : muted);
        if (spec.applyMode == SettingSpec.ApplyMode.SF_RECOMPILE
                && repo.overrides().containsKey(spec.key)) {
            status.setText("* Salvo no APK · aplicar props e recompilar SF");
            status.setTextColor(light ? 0xffa94342 : 0xffeea19b);
        }
        row.addView(status);
        String inactive = SettingStatus.inactive(spec, values());
        if (inactive != null) note(row, inactive);
        if (!spec.helperOwned() && spec.type == SettingSpec.Type.BOOLEAN) {
            toggle(
                    row,
                    "Ativar",
                    repo.desired(spec).equals("1"),
                    on -> {
                        saveInline(spec, on ? "1" : "0", status);
                        if (preview != null) preview.bind(values());
                        value.setText(repo.desired(spec) + "  ·  padrão " + spec.defaultValue);
                    });
        } else if (spec.type == SettingSpec.Type.INT || spec.type == SettingSpec.Type.FLOAT) {
            SeekBar slider = new SeekBar(this);
            UiStyle.slider(slider, light);
            slider.setMax(ControlLayout.steps(spec));
            slider.setProgress(ControlLayout.progress(spec, repo.desired(spec)));
            slider.setContentDescription(
                    spec.title + ", de " + spec.min + " a " + spec.max + " " + spec.unit);
            row.addView(slider, new LinearLayout.LayoutParams(-1, dp(48)));
            note(row, "Menos  " + spec.min + "   ↔   " + spec.max + "  Mais · " + spec.unit);
            slider.setOnSeekBarChangeListener(
                    new SeekBar.OnSeekBarChangeListener() {
                        public void onStartTrackingTouch(SeekBar bar) {}

                        public void onProgressChanged(SeekBar bar, int p, boolean user) {
                            if (!user) return;
                            String next = ControlLayout.at(spec, p);
                            drafts.put(spec.key, next);
                            value.setText(
                                    next + " " + spec.unit + "  ·  padrão " + spec.defaultValue);
                            if (preview != null) {
                                preview.bind(values());
                                preview.focus(spec, next);
                            }
                            status.setText("Comparando na amostra · solte para salvar");
                        }

                        public void onStopTrackingTouch(SeekBar bar) {
                            String next = drafts.remove(spec.key);
                            if (next != null) saveInline(spec, next, status);
                            if (preview != null) preview.bind(values());
                            value.setText(
                                    repo.desired(spec)
                                            + " "
                                            + spec.unit
                                            + "  ·  padrão "
                                            + spec.defaultValue);
                            bar.setProgress(ControlLayout.progress(spec, repo.desired(spec)));
                        }
                    });
        } else if (spec.type != SettingSpec.Type.READONLY && !spec.helperOwned()) {
            action(row, "Escolher valor", () -> details.performClick());
        }
        View divider = new View(this);
        divider.setBackgroundColor(light ? 0x158096b0 : 0x187f9cbf);
        row.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
    }

    private void saveInline(SettingSpec spec, String value, TextView status) {
        if (repo.conflict(spec)) {
            status.setText("Conflito com Terminal · abra os detalhes para recarregar");
            toast("O Terminal alterou este valor. Abra os detalhes antes de salvar.");
            return;
        }
        try {
            repo.save(spec, value);
            boolean sf = spec.applyMode == SettingSpec.ApplyMode.SF_RECOMPILE;
            status.setText(
                    sf
                            ? "* Salvo no APK · falta aplicar props e recompilar SF"
                            : "Salvo · enviado ao vivo para SystemUI");
            status.setTextColor(sf ? 0xffed938d : muted);
            updateApplicationStatus();
        } catch (IllegalArgumentException e) {
            status.setText(e.getMessage());
        }
    }

    private void updateApplicationStatus() {
        if (applyStatus == null) return;
        long sf =
                repo.overrides().keySet().stream()
                        .map(SettingsRegistry::get)
                        .filter(s -> s != null && s.applyMode == SettingSpec.ApplyMode.SF_RECOMPILE)
                        .count();
        android.content.SharedPreferences ack = getSharedPreferences("engine_status", MODE_PRIVATE);
        boolean waiting =
                repo.generation() > 0
                        && (repo.generation() > ack.getLong("readGeneration", -1)
                                || System.currentTimeMillis() - ack.getLong("readAt", 0) > 30000);
        applyStatus.setText(
                sf > 0
                        ? "* " + sf + " ajustes SF salvos · aplicação manual / recompilação"
                        : waiting
                                ? "SystemUI pendente · toque aqui para verificar ou reiniciar"
                                : "Ajustes Java ao vivo · toque para ver aplicação");
        applyStatus.setTextColor(sf > 0 || waiting ? (light ? 0xffa94342 : 0xffeea19b) : muted);
    }

    private void applicationDetails() {
        String[] choices = {
            "Atualizar confirmação de leitura",
            "Reiniciar SystemUI (SU)",
            "Ver alterações do compositor"
        };
        new AlertDialog.Builder(this)
                .setTitle("Aplicação dos ajustes")
                .setItems(
                        choices,
                        (d, which) -> {
                            if (which == 0) refreshObserved();
                            else if (which == 2) {
                                destination = 2;
                                render();
                            } else
                                confirm(
                                        "Reiniciar SystemUI?",
                                        "A barra de status e o painel serão encerrados e reabertos"
                                            + " pelo Android. Os controles Java já são lidos ao"
                                            + " vivo; use esta ação para recuperar a conexão ou"
                                            + " recarregar os hooks. Não reinicia SurfaceFlinger"
                                            + " nem zygote.",
                                        () -> {
                                            if (rootAccess.granted())
                                                rootAccess.restartSystemUi(
                                                        ok -> toast(rootAccess.message()));
                                            else
                                                rootAccess.check(
                                                        ok -> {
                                                            if (ok)
                                                                rootAccess.restartSystemUi(
                                                                        done ->
                                                                                toast(
                                                                                        rootAccess
                                                                                                .message()));
                                                            else toast(rootAccess.message());
                                                        });
                                        });
                        })
                .show();
    }

    private void presets() {
        LinearLayout presets =
                disclosure(
                        content,
                        "Presets e backup",
                        "Salvar, restaurar e transferir ajustes",
                        "presets");
        action(
                presets,
                "Adotar valores atuais",
                () ->
                        confirm(
                                "Adotar overrides observados?",
                                "Salva somente valores válidos. Não escreve props SF nem reinicia"
                                        + " processos.",
                                () -> {
                                    Map<String, String> current = new LinkedHashMap<>();
                                    for (SettingSpec s : SettingsRegistry.all()) {
                                        if (s.type == SettingSpec.Type.READONLY) continue;
                                        String observed = SystemPropertyReader.read(s.key);
                                        if (!observed.isEmpty())
                                            try {
                                                current.put(s.key, s.validate(observed));
                                            } catch (IllegalArgumentException ignored) {
                                            }
                                    }
                                    repo.replace(current, false);
                                    render();
                                }));
        action(
                presets,
                "Atual do dispositivo · salvar snapshot",
                () -> {
                    try {
                        if (!SystemPropertyReader.available())
                            throw new IllegalStateException(
                                    "Atualize a leitura das props antes de criar o snapshot.");
                        Map<String, String> current = new LinkedHashMap<>();
                        for (SettingSpec s : SettingsRegistry.all())
                            if (s.type != SettingSpec.Type.READONLY) {
                                String observed = SystemPropertyReader.read(s.key);
                                current.put(
                                        s.key,
                                        observed.isEmpty() ? s.defaultValue : s.validate(observed));
                            }
                        repo.preferences()
                                .edit()
                                .putString(
                                        "preset.device",
                                        PresetCodec.encode(
                                                current,
                                                repo.preferences()
                                                        .getBoolean(Config.KEY_ENABLED, false),
                                                device()))
                                .apply();
                        toast("Snapshot salvo; nenhum ajuste alterado.");
                    } catch (Exception e) {
                        toast(e.getMessage());
                    }
                });
        action(
                presets,
                "Restaurar snapshot do dispositivo",
                () -> loadPreset(repo.preferences().getString("preset.device", null)));
        action(
                presets,
                "Salvar Personalizado",
                () -> {
                    try {
                        repo.preferences()
                                .edit()
                                .putString(
                                        "preset.custom",
                                        PresetCodec.encode(
                                                repo.overrides(),
                                                repo.preferences()
                                                        .getBoolean(Config.KEY_ENABLED, false),
                                                device()))
                                .apply();
                        toast("Personalizado salvo.");
                    } catch (Exception e) {
                        toast(e.getMessage());
                    }
                });
        action(
                presets,
                "Carregar Personalizado",
                () -> loadPreset(repo.preferences().getString("preset.custom", null)));
        action(
                presets,
                "Padrão do código",
                () ->
                        confirm(
                                "Restaurar todos os padrões?",
                                "Mantém propriedades externas e guarda um snapshot para desfazer."
                                        + " SF fica pendente de aplicação manual.",
                                () -> {
                                    Map<String, String> m = new LinkedHashMap<>();
                                    for (SettingSpec s : SettingsRegistry.all())
                                        if (s.type != SettingSpec.Type.READONLY)
                                            m.put(s.key, s.defaultValue);
                                    repo.replace(m, true);
                                    render();
                                }));
        action(
                presets,
                "Desfazer última alteração",
                () -> {
                    toast(
                            repo.undo()
                                    ? "Snapshot anterior restaurado."
                                    : "Nenhuma alteração para desfazer.");
                    render();
                });
        action(
                presets,
                "Exportar JSON",
                () ->
                        startActivityForResult(
                                new Intent(Intent.ACTION_CREATE_DOCUMENT)
                                        .setType("application/json")
                                        .addCategory(Intent.CATEGORY_OPENABLE)
                                        .putExtra(
                                                Intent.EXTRA_TITLE, "OneUILiquidGlass-preset.json"),
                                91));
        action(
                presets,
                "Importar JSON · revisar diff",
                () ->
                        startActivityForResult(
                                new Intent(Intent.ACTION_OPEN_DOCUMENT)
                                        .setType("application/json")
                                        .addCategory(Intent.CATEGORY_OPENABLE),
                                92));
        note(
                presets,
                "As escolhas da UI persistem no APK. Props externas são voláteis. Importar/salvar"
                        + " não aplica valores ao shader SF.");
    }

    private void loadPreset(String json) {
        if (json == null) {
            toast("Preset ainda não salvo.");
            return;
        }
        try {
            PresetCodec.Parsed parsed = PresetCodec.parse(json);
            StringBuilder diff = new StringBuilder("Origem: " + parsed.device + "\n");
            for (Map.Entry<String, String> e : parsed.values.entrySet()) {
                SettingSpec s = SettingsRegistry.get(e.getKey());
                String old =
                        s == null
                                ? Boolean.toString(repo.preferences().getBoolean(e.getKey(), true))
                                : repo.desired(s);
                if (!old.equals(e.getValue()))
                    diff.append(e.getKey())
                            .append(": ")
                            .append(old)
                            .append(" → ")
                            .append(e.getValue())
                            .append('\n');
            }
            diff.append("\nChaves ignoradas: ")
                    .append(parsed.unknown)
                    .append("\nShader SF permanece pendente. O interruptor global será ")
                    .append(parsed.enabled ? "ativado" : "desativado")
                    .append('.');
            confirm(
                    "Revisar preset",
                    diff.toString(),
                    () -> {
                        repo.replace(parsed.values, true);
                        repo.preferences()
                                .edit()
                                .putBoolean(Config.KEY_ENABLED, parsed.enabled)
                                .apply();
                        getContentResolver().notifyChange(ConfigRepository.SNAPSHOT, null);
                        render();
                    });
        } catch (Exception e) {
            toast("Preset recusado: " + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent intent) {
        super.onActivityResult(request, result, intent);
        if (result != RESULT_OK || intent == null || intent.getData() == null) return;
        Uri uri = intent.getData();
        if (request != 91 && request != 92) return;
        io.execute(
                () -> {
                    try {
                        if (request == 91) {
                            String json =
                                    PresetCodec.encode(
                                            repo.overrides(),
                                            repo.preferences()
                                                    .getBoolean(Config.KEY_ENABLED, false),
                                            device());
                            try (java.io.OutputStream stream =
                                    getContentResolver().openOutputStream(uri, "wt")) {
                                if (stream == null)
                                    throw new java.io.IOException("Destino indisponível");
                                stream.write(
                                        json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            }
                            deliver(() -> toast("Preset exportado."));
                        } else {
                            String data;
                            try (java.io.InputStream stream =
                                    getContentResolver().openInputStream(uri)) {
                                if (stream == null)
                                    throw new java.io.IOException("Arquivo indisponível");
                                byte[] bytes = stream.readNBytes(PresetCodec.MAX_BYTES + 1);
                                if (bytes.length > PresetCodec.MAX_BYTES)
                                    throw new java.io.IOException("Arquivo muito grande");
                                data = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                            }
                            deliver(() -> loadPreset(data));
                        }
                    } catch (Exception e) {
                        deliver(() -> toast("Falha: " + e.getMessage()));
                    }
                });
    }

    private void diagnostics() {
        title("Diagnóstico");
        LinearLayout status = group(content, "Estado observado");
        note(
                status,
                engineStatus()
                        + "\n"
                        + device()
                        + "\nPonte de preferências LSPosed: "
                        + Config.lsposedPrefs
                        + "\nLeitura de props disponível: "
                        + SystemPropertyReader.available()
                        + "\nSF optics observado: "
                        + SystemPropertyReader.read("sf.optics")
                        + "\n"
                        + "O marcador não confirma REWRITE_OK nem execução do shader. Sem acesso"
                        + " privilegiado, módulo e logs SF são desconhecidos.");
        action(status, "Atualizar estado", this::refreshObserved);
        action(status, "Copiar relatório sem conteúdo de notificações", () -> copy(report()));
        LinearLayout pending = group(content, "Alterações e valores");
        for (Map.Entry<String, String> e : repo.overrides().entrySet()) {
            SettingSpec s = SettingsRegistry.get(e.getKey());
            if (s == null) continue;
            note(
                    pending,
                    s.fullKey()
                            + "\npadrão="
                            + s.defaultValue
                            + " · observado="
                            + display(SystemPropertyReader.read(s.key))
                            + " · desejado="
                            + e.getValue()
                            + "\nefetivo=desconhecido"
                            + (repo.conflict(s) ? " · conflito externo" : "")
                            + (s.applyMode == SettingSpec.ApplyMode.SF_RECOMPILE
                                    ? " · aguardando recompilação"
                                    : ""));
        }
        action(
                pending,
                "Copiar comandos SF pendentes",
                () -> {
                    StringBuilder script = new StringBuilder();
                    for (Map.Entry<String, String> e : repo.overrides().entrySet()) {
                        SettingSpec s = SettingsRegistry.get(e.getKey());
                        if (s != null && s.applyMode == SettingSpec.ApplyMode.SF_RECOMPILE)
                            script.append("setprop ")
                                    .append(s.fullKey())
                                    .append(' ')
                                    .append(s.validate(e.getValue()))
                                    .append('\n');
                    }
                    copy(
                            script.length() == 0
                                    ? "# Nenhum parâmetro SF desejado salvo"
                                    : "su -c '\n"
                                            + script
                                            + "'\n"
                                            + "# Recompilação não confirmada. Não reiniciar"
                                            + " automaticamente.");
                });
        moduleCard(content);
        notifications(content);
        LinearLayout recovery = group(content, "Recuperação");
        action(
                recovery,
                "Modo seguro · desligar vidro",
                () ->
                        confirm(
                                "Desligar vidro?",
                                "Preserva ajustes e solicita fundo nativo nas três superfícies.",
                                () -> {
                                    repo.flag(Config.KEY_ENABLED, false);
                                    render();
                                }));
        note(
                recovery,
                "Se o SystemUI não responder, desative o módulo no LSPosed e reinicie. Componente"
                    + " SF live: use Remover arquivos da injeção e faça reboot; não há injeção no"
                    + " boot. Não apague arquivos de outros módulos. Logs OULG/OULG_SF exigem"
                    + " acesso autorizado.");
    }

    private void moduleCard(LinearLayout parent) {
        new ModulePanel(this, sfLive, rootAccess, this::copy)
                .attach(parent, light, ink, muted, card);
    }

    private String report() {
        StringBuilder r =
                new StringBuilder(
                        "One UI Liquid Glass\n" + device() + "\n" + engineStatus() + "\n");
        for (SettingSpec s : SettingsRegistry.all())
            r.append(s.fullKey())
                    .append(" default=")
                    .append(s.defaultValue)
                    .append(" observed=")
                    .append(display(SystemPropertyReader.read(s.key)))
                    .append(" desired=")
                    .append(repo.desired(s))
                    .append(" effective=unknown\n");
        return r.toString();
    }

    private String display(String s) {
        return s.isEmpty() ? "sem override / leitura indisponível" : s;
    }

    private String device() {
        return Build.MANUFACTURER + " " + Build.MODEL + " · " + Build.DISPLAY;
    }

    private void confirm(String title, String message, Runnable action) {
        TextView body = text(message, 14, ink);
        body.setTextIsSelectable(true);
        body.setPadding(dp(20), dp(10), dp(20), dp(10));
        body.setBackgroundColor(card);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(scroll)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Confirmar", (d, w) -> action.run())
                .show();
    }

    private void copy(String s) {
        ((android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                .setPrimaryClip(ClipData.newPlainText("One UI Liquid Glass", s));
        toast("Copiado.");
    }

    private void deliver(Runnable work) {
        runOnUiThread(
                () -> {
                    if (!isFinishing() && !isDestroyed()) work.run();
                });
    }

    private void toast(String s) {
        if (!isFinishing() && !isDestroyed())
            Toast.makeText(this, s == null ? "Operação falhou" : s, Toast.LENGTH_LONG).show();
    }
}
