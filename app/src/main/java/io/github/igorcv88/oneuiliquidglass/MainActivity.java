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
    private LinearLayout root, content, rows;
    private MaterialPreview preview;
    private TextView testStatus;
    private int destination, backdrop = 2;
    private String surface = "headsup", query = "";
    private boolean advanced, favorites, allSettings, light;
    private int pendingCount = 1, pendingDelay;
    private boolean permissionPending, postingExpanded = true;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService io =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private Runnable scheduled;
    private int ink, muted, bg, card;

    private int dp(float n) {
        return (int) (getResources().getDisplayMetrics().density * n + .5f);
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        repo = new ConfigRepository(this);
        sfLive = new SfLiveController(this, io);
        light = repo.preferences().getBoolean("ui.light", false);
        if (state != null) {
            destination = state.getInt("destination");
            surface = state.getString("surface", "headsup");
            backdrop = state.getInt("backdrop", 2);
            advanced = state.getBoolean("advanced");
            allSettings = state.getBoolean("all");
            query = state.getString("query", "");
            permissionPending = state.getBoolean("permissionPending");
            pendingCount = state.getInt("pendingCount", 1);
            pendingDelay = state.getInt("pendingDelay");
            postingExpanded = state.getBoolean("expanded", true);
        }
        if ("surfaces".equals(getIntent().getStringExtra("destination"))) destination = 1;
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        io.execute(
                () -> {
                    SystemPropertyReader.refresh();
                    deliver(
                            () -> {
                                if (!isFinishing() && !isDestroyed()) render();
                            });
                });
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
        b.putBoolean("advanced", advanced);
        b.putBoolean("all", allSettings);
        b.putString("query", query);
        b.putBoolean("permissionPending", permissionPending);
        b.putInt("pendingCount", pendingCount);
        b.putInt("pendingDelay", pendingDelay);
        b.putBoolean("expanded", postingExpanded);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (sfLive != null) sfLive.close();
        io.shutdownNow();
        super.onDestroy();
    }

    private void render() {
        ink = light ? 0xff182538 : 0xffedf3fc;
        muted = light ? 0xff4c6178 : 0xff9eafc5;
        bg = light ? 0xffedf1f7 : 0xff101722;
        card = light ? 0xfff9fbff : 0xff1a2535;
        root = new LinearLayout(this);
        root.setOrientation(1);
        root.setBackgroundColor(bg);
        root.setOnApplyWindowInsetsListener(
                (v, insets) -> {
                    android.graphics.Insets system =
                            insets.getInsets(WindowInsets.Type.systemBars());
                    v.setPadding(system.left, system.top, system.right, system.bottom);
                    return insets;
                });
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = column();
        content.setPadding(dp(20), dp(14), dp(20), dp(24));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout nav = new LinearLayout(this);
        nav.setPadding(dp(12), dp(4), dp(12), dp(8));
        nav.setBackgroundColor(card);
        String[] labels = {"Início", "Superfícies", "Diagnóstico"};
        for (int i = 0; i < 3; i++) {
            final int target = i;
            Button b =
                    button(
                            labels[i],
                            () -> {
                                destination = target;
                                render();
                            });
            b.setTextColor(i == destination ? 0xff51b9ca : muted);
            nav.addView(b, new LinearLayout.LayoutParams(0, dp(56), 1));
        }
        root.addView(nav);
        setContentView(root);
        root.requestApplyInsets();
        if (destination == 0) home();
        else if (destination == 1) surfaces();
        else diagnostics();
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(1);
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
        b.setAllCaps(false);
        b.setText(s);
        b.setTextColor(ink);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private void action(LinearLayout p, String s, Runnable r) {
        p.addView(button(s, r));
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
        note(content, "Material das notificações · controle por superfície");
        LinearLayout main = group(content, "Motor");
        toggle(
                main,
                "Experimental glass",
                repo.preferences().getBoolean(Config.KEY_ENABLED, false),
                on -> {
                    repo.flag(Config.KEY_ENABLED, on);
                    render();
                });
        note(main, engineStatus());
        note(
                main,
                "Modo solicitado: "
                        + (repo.desired(SettingsRegistry.get("sfrefract")).equals("1")
                                ? "SurfaceFlinger (execução não confirmada)"
                                : "APK-only")
                        + ". Overrides do Terminal são preservados até adoção explícita.");
        action(
                main,
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
        notifications(content);
        presets();
        moduleCard(content);
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
        action(tests, "Heads-up em 5 s", () -> requestTest(1, 5000, true));
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
        title("Superfícies");
        LinearLayout segmented = new LinearLayout(this);
        String[] ids = {"headsup", "keyguard", "shade"}, labels = {"Pop-up", "Bloqueio", "Central"};
        for (int i = 0; i < 3; i++) {
            String id = ids[i];
            Button b =
                    button(
                            labels[i],
                            () -> {
                                surface = id;
                                allSettings = false;
                                render();
                            });
            b.setTextColor(surface.equals(id) && !allSettings ? 0xff51b9ca : muted);
            segmented.addView(b, new LinearLayout.LayoutParams(0, dp(52), 1));
        }
        content.addView(segmented);
        LinearLayout controls = group(content, "Amostra local");
        preview = new MaterialPreview(this);
        preview.surface = surface;
        preview.light = light;
        preview.background(backdrop);
        preview.bind(values());
        controls.addView(preview, new LinearLayout.LayoutParams(-1, dp(248)));
        note(
                controls,
                "Simulação aproximada. Resultado real depende do fundo, Samsung blur e"
                        + " SurfaceFlinger. Sem captura de aplicativos.");
        Spinner backgrounds = new Spinner(this);
        String[] options = {"Fundo claro", "Fundo escuro", "Colorido", "Quadriculado"};
        backgrounds.setAdapter(
                new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, options));
        backgrounds.setSelection(backdrop);
        backgrounds.setOnItemSelectedListener(
                new AdapterView.OnItemSelectedListener() {
                    public void onNothingSelected(AdapterView<?> p) {}

                    public void onItemSelected(AdapterView<?> p, View v, int i, long id) {
                        backdrop = i;
                        preview.background(i);
                    }
                });
        controls.addView(backgrounds);
        toggle(
                controls,
                "Amostra em tema claro",
                light,
                on -> {
                    light = on;
                    preview.light = on;
                    preview.invalidate();
                });
        LinearLayout gates = group(content, "Efeitos nesta superfície");
        String flag = "surface." + surface + ".enabled";
        toggle(
                gates,
                "Vidro nesta superfície",
                repo.preferences().getBoolean(flag, true),
                on -> {
                    repo.flag(flag, on);
                    preview.bind(values());
                });
        String[] features = {"edge", "tonemap", "glint", "lens", "per_card_blur"},
                names =
                        {
                            "Iluminação de borda APK",
                            "Proteção de contraste APK",
                            "Reflexo APK",
                            "Lente nesta superfície",
                            "Blur próprio por cartão da central"
                        };
        for (int i = 0; i < features.length; i++) {
            String key = "surface." + surface + ".feature." + features[i] + ".enabled";
            toggle(
                    gates,
                    names[i],
                    repo.preferences().getBoolean(key, true),
                    on -> {
                        repo.flag(key, on);
                        preview.bind(values());
                    });
        }
        note(
                gates,
                "Borda, reflexo e contraste controlam a camada APK. A iluminação embutida no shader"
                        + " SF tem parâmetros próprios e requer recompilação. Blur por cartão só se"
                        + " aplica à central. Proteções e limpeza permanecem obrigatórias.");
        action(
                content,
                allSettings ? "Mostrar esta superfície" : "Material e óptica · todos os controles",
                () -> {
                    allSettings = !allSettings;
                    render();
                });
        EditText search = new EditText(this);
        search.setTextColor(ink);
        search.setHintTextColor(muted);
        search.setHint("Buscar nome, efeito ou debug.oulg.*");
        search.setSingleLine(true);
        search.setText(query);
        content.addView(search);
        toggle(
                content,
                "Mostrar avançados",
                advanced,
                on -> {
                    advanced = on;
                    renderRows();
                });
        toggle(
                content,
                "Somente favoritos",
                favorites,
                on -> {
                    favorites = on;
                    renderRows();
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
        action(
                content,
                "Restaurar grupo desta superfície",
                () ->
                        confirm(
                                "Restaurar padrões?",
                                "Um snapshot permite desfazer. Não reinicia o compositor.",
                                () -> {
                                    Map<String, String> defaults = new LinkedHashMap<>();
                                    for (SettingSpec s : SettingsRegistry.all())
                                        if (s.type != SettingSpec.Type.READONLY
                                                && s.surfaceScope.equals(surface))
                                            defaults.put(s.key, s.defaultValue);
                                    repo.replace(defaults, false);
                                    render();
                                }));
    }

    private void renderRows() {
        if (rows == null) return;
        rows.removeAllViews();
        Map<String, String> state = values();
        String last = "";
        int count = 0;
        String q = query.toLowerCase(Locale.ROOT).trim();
        for (SettingSpec s : SettingsRegistry.all()) {
            if (!q.isEmpty()) {
                if (!(s.title + " " + s.explanation + " " + s.fullKey())
                        .toLowerCase(Locale.ROOT)
                        .contains(q)) continue;
            } else if (!allSettings
                    && !s.surfaceScope.equals("all")
                    && !s.surfaceScope.equals(surface)) continue;
            if (q.isEmpty() && !advanced && s.advanced) continue;
            if (favorites && !repo.preferences().getBoolean("favorite." + s.key, false)) continue;
            if (!last.equals(s.group)) {
                rows.addView(text(s.group, 18, ink));
                last = s.group;
            }
            LinearLayout row = column();
            row.setPadding(dp(12), dp(7), dp(12), dp(9));
            row.setMinimumHeight(dp(96));
            GradientDrawable background = new GradientDrawable();
            background.setColor(card);
            background.setCornerRadius(dp(14));
            row.setBackground(background);
            LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
            layout.bottomMargin = dp(7);
            rows.addView(row, layout);
            TextView heading = text(s.title + "   " + repo.desired(s) + " " + s.unit, 16, ink);
            row.addView(heading);
            TextView explanation = text(s.explanation, 12, muted);
            explanation.setMaxLines(2);
            row.addView(explanation);
            String inactive = SettingStatus.inactive(s, state);
            note(
                    row,
                    s.fullKey()
                            + " · padrão "
                            + s.defaultValue
                            + "\n"
                            + (repo.conflict(s)
                                    ? "Conflito com override externo"
                                    : inactive != null
                                            ? inactive
                                            : s.helperOwned()
                                                    ? "Controlado pelo helper live"
                                                    : s.applyMode == SettingSpec.ApplyMode.READONLY
                                                            ? "Somente leitura"
                                                            : s.applyMode
                                                                            == SettingSpec.ApplyMode
                                                                                    .SF_RECOMPILE
                                                                    ? "Manual · efetivo"
                                                                          + " desconhecido /"
                                                                          + " recompilação pendente"
                                                                    : "Desejado · leitura/efeito a"
                                                                            + " confirmar"));
            row.setAlpha(inactive == null ? 1 : .65f);
            row.setClickable(true);
            row.setFocusable(true);
            row.setContentDescription(
                    s.title + ". " + s.explanation + ". Toque para editar e ver detalhes.");
            row.setOnClickListener(
                    v ->
                            new SettingsSheet(
                                            this,
                                            repo,
                                            s,
                                            surface,
                                            light,
                                            backdrop,
                                            () -> {
                                                if (preview != null) preview.bind(values());
                                                renderRows();
                                            })
                                    .show());
            count++;
        }
        note(
                rows,
                count
                        + " controles visíveis · "
                        + SettingsRegistry.all().size()
                        + " no catálogo. A busca inclui os avançados de todas as superfícies.");
    }

    private void presets() {
        LinearLayout presets = group(content, "Presets e backup");
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
                "Se o SystemUI não responder, desative o módulo no LSPosed e reinicie. Módulo SF:"
                    + " desative pelo método root usado na instalação e reinicie o aparelho. Não"
                    + " apague caches ou arquivos de outros módulos. Logs OULG/OULG_SF não são"
                    + " lidos sem acesso autorizado.");
    }

    private void moduleCard(LinearLayout parent) {
        new ModulePanel(this, sfLive, this::copy).attach(parent, light, ink, muted, card);
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
