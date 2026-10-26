package io.github.igorcv88.oneuiliquidglass.ui;

import android.app.*;
import android.graphics.Color;
import android.os.Bundle;
import android.text.*;
import android.view.*;
import android.widget.*;

import io.github.igorcv88.oneuiliquidglass.config.*;

import java.util.*;

/** One disposable editor owns a draft. Slider drags update only local preview. */
public final class SettingsSheet extends Dialog {
    private final Activity activity;
    private final ConfigRepository repo;
    private final SettingSpec spec;
    private final Runnable saved;
    private EditText input;
    private final boolean light;
    private MaterialPreview preview;

    public SettingsSheet(
            Activity activity,
            ConfigRepository repo,
            SettingSpec spec,
            String surface,
            boolean light,
            int backdrop,
            Runnable saved) {
        super(activity);
        this.activity = activity;
        this.repo = repo;
        this.spec = spec;
        this.saved = saved;
        this.light = light;
        preview = new MaterialPreview(activity);
        preview.surface = surface;
        preview.light = light;
        preview.background(backdrop);
    }

    private TextView label(String text, int size) {
        TextView v = new TextView(activity);
        v.setText(text);
        v.setTextSize(size);
        v.setTextColor(light ? 0xff182538 : 0xffeaf1fa);
        v.setPadding(0, 10, 0, 10);
        return v;
    }

    private void button(LinearLayout root, String title, Runnable action) {
        Button b = new Button(activity);
        b.setText(title);
        UiStyle.button(b, light, true);
        UiStyle.space(b, 4);
        b.setOnClickListener(v -> action.run());
        root.addView(b);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(activity);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (22 * activity.getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);
        root.setBackground(UiStyle.pane(activity, light, false, 26));
        scroll.addView(root);
        setContentView(scroll);
        root.addView(label(spec.title, 23));
        root.addView(label(spec.explanation, 15));
        TextView technical = label(spec.fullKey(), 12);
        technical.setTextIsSelectable(true);
        root.addView(technical);
        preview.bind(current());
        preview.focus(spec, repo.desired(spec));
        root.addView(preview, new LinearLayout.LayoutParams(-1, pad * 10));
        root.addView(
                label(
                        "Simulação aproximada. Resultado real depende do fundo, Samsung blur e"
                                + " SurfaceFlinger.",
                        12));
        root.addView(
                label(
                        "Observado: "
                                + (SystemPropertyReader.read(spec.key).isEmpty()
                                        ? "sem override / leitura indisponível"
                                        : SystemPropertyReader.read(spec.key))
                                + " · efetivo: desconhecido",
                        13));
        root.addView(
                label(
                        "Padrão: "
                                + spec.defaultValue
                                + " "
                                + spec.unit
                                + " · "
                                + ControlLayout.application(spec)
                                + "\nSuperfícies: "
                                + spec.surfaceScope
                                + " · "
                                + spec.applicability
                                + "\nDependências: "
                                + (spec.dependencies.isEmpty()
                                        ? "nenhuma adicional"
                                        : spec.dependencies),
                        13));
        if (spec.advanced)
            root.addView(
                    label(
                            "Avançado · pode aumentar o custo de GPU/captura. Os limites do shader"
                                    + " são limites conservadores da interface, não clamps do C.",
                            12));
        if (spec.helperOwned()) {
            root.addView(
                    label(
                            "Modo controlado pelo helper SurfaceFlinger. Use o botão Refração"
                                + " SurfaceFlinger no Início/Diagnóstico. Somente o injetor liga a"
                                + " prop após carregar a biblioteca; ela não é persistida em"
                                + " presets nem na ponte Java.",
                            15));
            return;
        }
        if (spec.type == SettingSpec.Type.READONLY) {
            root.addView(
                    label(
                            "Somente leitura. Valor observado: "
                                    + SystemPropertyReader.read(spec.key)
                                    + ". Estado efetivo do shader: desconhecido.",
                            15));
            return;
        }
        input = new EditText(activity);
        input.setTextColor(light ? 0xff182538 : Color.WHITE);
        input.setSingleLine(true);
        input.setText(repo.desired(spec));
        input.setContentDescription("Valor desejado em " + spec.unit);
        root.addView(input);
        if (spec.type == SettingSpec.Type.FLOAT || spec.type == SettingSpec.Type.INT) {
            input.setInputType(
                    android.text.InputType.TYPE_CLASS_NUMBER
                            | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                            | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
            SeekBar slider = new SeekBar(activity);
            UiStyle.slider(slider, light);
            slider.setMax(ControlLayout.steps(spec));
            slider.setProgress(ControlLayout.progress(spec, repo.desired(spec)));
            slider.setContentDescription(spec.title + " · menor a maior");
            root.addView(slider);
            root.addView(
                    label("Menor " + spec.min + "  ↔  Maior " + spec.max + " " + spec.unit, 12));
            slider.setOnSeekBarChangeListener(
                    new SeekBar.OnSeekBarChangeListener() {
                        public void onStartTrackingTouch(SeekBar s) {}

                        public void onStopTrackingTouch(SeekBar s) {}

                        public void onProgressChanged(SeekBar s, int p, boolean user) {
                            if (!user) return;
                            String text = ControlLayout.at(spec, p);
                            input.setText(text);
                        }
                    });
        } else if (spec.type == SettingSpec.Type.BOOLEAN) {
            Switch toggle = new Switch(activity);
            toggle.setText("Ativar");
            toggle.setTextColor(light ? 0xff182538 : Color.WHITE);
            UiStyle.toggle(toggle, light);
            toggle.setChecked(input.getText().toString().equals("1"));
            toggle.setOnCheckedChangeListener((v, on) -> input.setText(on ? "1" : "0"));
            root.addView(toggle);
        } else if (spec.type == SettingSpec.Type.ENUM) {
            Spinner choices = new Spinner(activity);
            String[] options = spec.choices.split(",");
            choices.setAdapter(
                    new ArrayAdapter<>(
                            activity, android.R.layout.simple_spinner_dropdown_item, options));
            int selected = Arrays.asList(options).indexOf(repo.desired(spec));
            choices.setSelection(Math.max(0, selected), false);
            choices.setOnItemSelectedListener(
                    new android.widget.AdapterView.OnItemSelectedListener() {
                        boolean first = true;

                        public void onNothingSelected(android.widget.AdapterView<?> p) {}

                        public void onItemSelected(
                                android.widget.AdapterView<?> p, View v, int index, long id) {
                            if (first) {
                                first = false;
                                return;
                            }
                            input.setText(options[index]);
                        }
                    });
            root.addView(choices);
        } else if (spec.type == SettingSpec.Type.RGB) {
            root.addView(label("RGB hexadecimal · exemplo: ffffff", 13));
            LinearLayout swatches = new LinearLayout(activity);
            root.addView(swatches);
            for (String hex : new String[] {"ffffff", "b8c2ce", "c8e9ef", "c6b7ed"}) {
                Button b = new Button(activity);
                b.setText("●");
                UiStyle.button(b, light, false);
                b.setTextColor(Color.parseColor("#" + hex));
                b.setContentDescription("Cor " + hex);
                b.setOnClickListener(v -> input.setText(hex));
                swatches.addView(b, new LinearLayout.LayoutParams(0, UiStyle.dp(activity, 48), 1));
            }
        }
        input.addTextChangedListener(
                new TextWatcher() {
                    public void beforeTextChanged(CharSequence s, int a, int c, int f) {}

                    public void onTextChanged(CharSequence s, int a, int b, int c) {
                        try {
                            String value = spec.validate(s.toString());
                            input.setError(null);
                            preview.focus(spec, value);
                        } catch (IllegalArgumentException e) {
                            input.setError(e.getMessage());
                        }
                    }

                    public void afterTextChanged(Editable e) {}
                });
        button(root, "Restaurar padrão no editor", () -> input.setText(spec.defaultValue));
        button(
                root,
                spec.applyMode == SettingSpec.ApplyMode.SF_RECOMPILE
                        ? "Salvar desejado · aplicação manual"
                        : "Salvar ajuste",
                () -> {
                    try {
                        if (repo.conflict(spec)) {
                            new AlertDialog.Builder(activity)
                                    .setMessage(
                                            "O Terminal alterou esta prop desde a última gravação."
                                                + " Recarregue o valor externo antes de decidir.")
                                    .setPositiveButton(
                                            "Recarregar externo",
                                            (d, w) -> {
                                                repo.releaseToExternal(spec);
                                                input.setText(repo.desired(spec));
                                            })
                                    .setNegativeButton("Cancelar", null)
                                    .show();
                            return;
                        }
                        repo.save(spec, input.getText().toString());
                        saved.run();
                        dismiss();
                    } catch (IllegalArgumentException e) {
                        input.setError(e.getMessage());
                    }
                });
        button(
                root,
                "Usar override externo / desfazer adoção",
                () -> {
                    repo.releaseToExternal(spec);
                    saved.run();
                    dismiss();
                });
        button(
                root,
                repo.preferences().getBoolean("favorite." + spec.key, false)
                        ? "Remover favorito"
                        : "Favoritar",
                () -> {
                    repo.preferences()
                            .edit()
                            .putBoolean(
                                    "favorite." + spec.key,
                                    !repo.preferences().getBoolean("favorite." + spec.key, false))
                            .apply();
                    saved.run();
                    dismiss();
                });
        if (spec.applyMode == SettingSpec.ApplyMode.SF_RECOMPILE)
            button(
                    root,
                    "Copiar comando manual",
                    () -> {
                        try {
                            String value = spec.validate(input.getText().toString());
                            ((android.content.ClipboardManager)
                                            activity.getSystemService(Activity.CLIPBOARD_SERVICE))
                                    .setPrimaryClip(
                                            android.content.ClipData.newPlainText(
                                                    "Comando SF",
                                                    "su -c 'setprop "
                                                            + spec.fullKey()
                                                            + " "
                                                            + value
                                                            + "'"));
                            Toast.makeText(
                                            activity,
                                            "Copiado. Shader ainda requer recompilação; não foi"
                                                    + " aplicado.",
                                            Toast.LENGTH_LONG)
                                    .show();
                        } catch (IllegalArgumentException e) {
                            input.setError(e.getMessage());
                        }
                    });
    }

    private Map<String, String> current() {
        Map<String, String> map = new LinkedHashMap<>();
        for (SettingSpec s : SettingsRegistry.all()) map.put(s.key, repo.desired(s));
        map.putAll(repo.overrides());
        return map;
    }

    @Override
    public void show() {
        super.show();
        Window w = getWindow();
        if (w != null) {
            w.setLayout(-1, (int) (activity.getResources().getDisplayMetrics().heightPixels * .9));
            w.setGravity(Gravity.BOTTOM);
        }
    }
}
