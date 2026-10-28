package io.github.igorcv88.oneuiliquidglass.config;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

/**
 * Builds docs/CONTROL_MATRIX.md from the code: every registry key and product flag, who reads it
 * (found by scanning the readers, not typed by hand), where it shows, how it applies, in which
 * mode it is active and what the preview does. Fails when the committed file is stale; run with
 * OULG_UPDATE_MATRIX=1 to rewrite it.
 */
public class ControlMatrixTest {
    private static final Path SRC = Paths.get("src/main/java/io/github/igorcv88/oneuiliquidglass");
    private static final Path DOC = Paths.get("../docs/CONTROL_MATRIX.md");

    /** Tuning fields per key, parsed from Tuning's constructor. */
    static Map<String, Set<String>> fields() throws IOException {
        String tuning = new String(Files.readAllBytes(SRC.resolve("glass/Tuning.java")), StandardCharsets.UTF_8);
        Map<String, Set<String>> out = new TreeMap<>();
        Matcher m = Pattern.compile("(?m)^\\s*(?:int |String |float )?(\\w+) = [^;]*?(?:number|prop)\\(\"([a-z0-9.]+)\"").matcher(tuning);
        while (m.find()) out.computeIfAbsent(m.group(2), k -> new TreeSet<>()).add(m.group(1));
        // Derived fields read through locals or helpers.
        out.put("shadetone", new TreeSet<>(Arrays.asList("shadeTone", "shadeVeil", "shadeAdaptive")));
        out.put("liveblur", new TreeSet<>(Collections.singletonList("live")));
        out.computeIfAbsent("sfrefract", k -> new TreeSet<>()).add("live");
        out.computeIfAbsent("backdrop", k -> new TreeSet<>()).add("live");
        out.put("kglens", new TreeSet<>(Collections.singletonList("kgLens")));
        out.put("semshape", new TreeSet<>(Collections.singletonList("semShape")));
        return out;
    }

    /** Files outside the app UI and config that use any of the fields. */
    static Set<String> readers(Set<String> fieldNames) throws IOException {
        Set<String> out = new TreeSet<>();
        try (Stream<Path> files = Files.walk(SRC)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).collect(Collectors.toList())) {
                String rel = SRC.relativize(p).toString();
                if (rel.startsWith("ui/") || rel.startsWith("config/") || rel.equals("glass/Tuning.java") || rel.equals("MainActivity.java")) continue;
                String text = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                // Reads go through Tuning.get() or a local Tuning named t / tn.
                for (String f : fieldNames)
                    if (Pattern.compile("(Tuning\\.get\\(\\)|\\bt|\\btn|\\btuning)\\.(" + f + ")\\b(?!\\s*\\()").matcher(text).find()) {
                        out.add(rel.replace(".java", ""));
                        break;
                    }
            }
        }
        return out;
    }

    static String cell(Applicability.Result r) {
        switch (r.kind) {
            case ACTIVE: return "ativo";
            case NEEDS_RESTART: return "ao reiniciar";
            case READ_ONLY: return "leitura";
            default: return "inativo: " + r.reason;
        }
    }

    static String render() throws IOException {
        Map<String, Set<String>> fields = fields();
        String c = new String(Files.readAllBytes(Paths.get("../sfhook/oulg_sf.c")), StandardCharsets.UTF_8);
        Map<String, Applicability> modes = new LinkedHashMap<>();
        modes.put("Padrão", new ControlState(Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(), true).applicability());
        modes.put("Lente", new ControlState(Collections.emptyMap(), Collections.singletonMap("sfrefract", "1"), Collections.emptyMap(), true).applicability());
        modes.put("Captura", new ControlState(Collections.emptyMap(), Collections.singletonMap("backdrop", "hybrid"), Collections.emptyMap(), true).applicability());
        StringBuilder b = new StringBuilder();
        b.append("# Matriz de controles\n\n")
                .append("Gerada por `ControlMatrixTest` a partir de `SettingsRegistry`, `Controls`, `Applicability`,")
                .append(" `glass/Tuning.java` e `sfhook/oulg_sf.c`. Não edite à mão: rode os testes com")
                .append(" `OULG_UPDATE_MATRIX=1`.\n\n")
                .append("- **Lido por**: arquivos que usam o campo de `Tuning` (Java, ao vivo via `ConfigClient` ou")
                .append(" `getprop`) ou a biblioteca do compositor (lido ao compilar os shaders).\n")
                .append("- **Aplicação**: `ao vivo` = SystemUI relê em até ~1 s ou na notificação do provider; `reinício`")
                .append(" = só depois de gravar a propriedade e reiniciar o userspace pela lente dinâmica; `leitura` = estado.\n")
                .append("- **Padrão / Lente / Captura**: `Applicability.rule` com os valores padrão em cada modo; ")
                .append("dependências entre chaves e camadas aparecem nas linhas dos switches.\n")
                .append("- **Prévia**: estratégia e canal de `Controls`; `FAITHFUL` e `DIDACTIC` são cobertos por ")
                .append("`ControlModelTest.everyRepresentedControlVisiblyChangesThePreview` (parâmetros) e por ")
                .append("`tools/screenshots` `PerceptibilityTest` (pixels).\n")
                .append("- **Teste**: `CM` = ControlModelTest, `SC` = ScaleTest, `CV` = CompositorValuesTest, ")
                .append("`LH` = sfhook/tools/live_check.sh, `PT` = PerceptibilityTest.\n\n");
        b.append("| Chave | Nome na UI | Ambientes | Grupo · nível | Tipo · faixa · padrão | Lido por | Aplicação | Padrão | Lente | Captura | Camada | Prévia | Teste |\n");
        b.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (SettingSpec s : SettingsRegistry.all()) {
            Controls.Control ctl = Controls.get(s.key);
            String readers;
            if (s.key.startsWith("sf.")) {
                assertTrue(s.key + " not read by the library", c.contains("debug.oulg." + s.key));
                readers = "sfhook/oulg_sf.c" + (fields.containsKey(s.key) ? ", " + String.join(", ", readers(fields.get(s.key))) : "");
            } else if (s.key.startsWith("qs.")) {
                String source = new String(Files.readAllBytes(SRC.resolve("glass/QsStyle.java")), StandardCharsets.UTF_8);
                assertTrue(s.key, source.contains("\"" + s.key + "\""));
                readers = "glass/QsStyle, glass/QuickSettingsGlass, hooks/QuickSettingsHooks, hooks/QsSliderHooks";
            } else {
                Set<String> f = fields.get(s.key);
                assertNotNull("no Tuning field for " + s.key, f);
                Set<String> r = readers(f);
                if (s.key.equals("perf")) r.add("diagnostics/Perf");
                if (s.key.equals("trace")) r.add("diagnostics/Probe");
                assertFalse("nobody reads " + s.key, r.isEmpty());
                readers = String.join(", ", r);
            }
            String range = s.type == SettingSpec.Type.INT || s.type == SettingSpec.Type.FLOAT
                    ? ValueFormat.plain(s.min) + "–" + ValueFormat.plain(s.max) : s.type == SettingSpec.Type.ENUM ? s.choices.replace(",", "/") : "";
            String apply = s.type == SettingSpec.Type.READONLY || !Controls.editable(s.key) ? "leitura"
                    : s.applyMode == SettingSpec.ApplyMode.SF_RECOMPILE ? "reinício" : "ao vivo";
            StringBuilder where = new StringBuilder();
            for (Controls.Surface surface : ctl.surfaces) where.append(where.length() == 0 ? "" : ", ").append(surface.label);
            String test = s.key.startsWith("sf.") ? "CM, CV" + (io.github.igorcv88.oneuiliquidglass.live.CompositorValues.writable(s.key) ? ", LH" : "") : "CM";
            if (s.type == SettingSpec.Type.INT || s.type == SettingSpec.Type.FLOAT) test += ", SC";
            if (ctl.preview == Controls.Preview.FAITHFUL || ctl.preview == Controls.Preview.DIDACTIC) test += ", PT";
            b.append("| `").append(s.key).append("` | ").append(ctl.title).append(" | ").append(where)
                    .append(" | ").append(ctl.section.label).append(" · ").append(ctl.tier.name().toLowerCase(Locale.ROOT))
                    .append(" | ").append(s.type.name().toLowerCase(Locale.ROOT)).append(range.isEmpty() ? "" : " · " + range)
                    .append(" · ").append(s.defaultValue).append(" | ").append(readers).append(" | ").append(apply);
            for (Applicability a : modes.values()) b.append(" | ").append(cell(a.rule(s.key)));
            b.append(" | ").append(ctl.feature == null ? "" : ctl.feature)
                    .append(" | ").append(ctl.preview.name().toLowerCase(Locale.ROOT))
                    .append(ctl.channel == Controls.Channel.NONE ? "" : " · " + ctl.channel.name().toLowerCase(Locale.ROOT))
                    .append(" | ").append(test).append(" |\n");
        }
        b.append("\n## Interruptores de produto (fora de `debug.oulg.*`)\n\n")
                .append("Guardados em `glass` SharedPreferences, entregues ao SystemUI pelo `ConfigProvider` (`/v1/snapshot`)")
                .append(" e lidos por `ConfigClient.flag`; ausentes = ligado.\n\n")
                .append("| Chave | Lido por | Efeito ao desligar | Padrão | Lente | Captura |\n| --- | --- | --- | --- | --- | --- |\n");
        b.append("| `enabled` | SystemUIEntry, hooks/HeadsUpHooks (`enabled`) | todos os ambientes voltam ao fundo nativo | ativo | ativo | ativo |\n");
        String hooks = new String(Files.readAllBytes(SRC.resolve("hooks/HeadsUpHooks.java")), StandardCharsets.UTF_8);
        for (Controls.Surface s : Controls.Surface.values()) {
            assertTrue(hooks.contains("surfaceEnabled()"));
            b.append("| `surface.").append(s.id).append(".enabled` | hooks/HeadsUpHooks (`surfaceEnabled`, `eligible`) | ")
                    .append(s.label).append(" volta ao fundo nativo | ativo | ativo | ativo |\n");
            for (Map.Entry<String, String> layer : Controls.layers(s).entrySet()) {
                assertTrue(layer.getKey(), hooks.contains("feature(\"" + layer.getKey() + "\")"));
                b.append("| `surface.").append(s.id).append(".feature.").append(layer.getKey()).append(".enabled` | hooks/HeadsUpHooks (`feature(\"")
                        .append(layer.getKey()).append("\")`) | ").append(s.label).append(": camada ").append(layer.getValue()).append(" desligada");
                for (Applicability a : modes.values()) b.append(" | ").append(cell(a.layer(s, layer.getKey())));
                b.append(" |\n");
            }
        }
        b.append("\n## Funções sem propriedade\n\n")
                .append("| Função | Onde | Verificação |\n| --- | --- | --- |\n")
                .append("| Notificação de teste (agora, 5 s, várias, remover, canal) | `TestNotifications`, `MainActivity.requestTest` | resultado sempre descrito (`TestNotifications.describe`); permissão negada, canal/app bloqueado e Não perturbe tratados |\n")
                .append("| Estilos (salvar, usar com diferenças, padrão, desfazer) | `PresetsSheet`, `ConfigRepository.replace/undo` | `PresetCodecTest`; aplicar estilo não grava no compositor |\n")
                .append("| Exportar/importar | `MainActivity`, `PresetCodec` | validação completa antes de escrever; diff antes de usar |\n")
                .append("| Alterados fora do app | `AppModel.conflicts`, `InfoSheets.conflicts` | nunca sobrescreve sem escolha explícita |\n")
                .append("| Comunicação com o sistema | `AppModel.sync`, `ConfigReadReceiver` | confirma leitura, não efeito visual |\n")
                .append("| Lente dinâmica (verificar, instalar/testar, ativar, aplicar pendentes, remover, registro) | `LensSheet`, `SfLiveController`, `SfLiveFlow`, `oulg-live.sh` | `SfLiveFlowTest`, `CompositorValuesTest`, `live_check.sh` |\n")
                .append("| Reiniciar a interface do sistema | `InfoSheets.restartSystemUi`, `RootAccess` | comando fixo, confirmação explícita |\n")
                .append("| Busca e favoritos | `SearchSheet`, `ControlSheet` | todos os controles editáveis encontráveis; favoritos por ambiente |\n");
        return b.toString();
    }

    @Test
    public void committedMatrixMatchesTheCode() throws IOException {
        String now = render();
        if ("1".equals(System.getenv("OULG_UPDATE_MATRIX"))) Files.write(DOC, now.getBytes(StandardCharsets.UTF_8));
        String committed = Files.exists(DOC) ? new String(Files.readAllBytes(DOC), StandardCharsets.UTF_8) : "";
        assertEquals("docs/CONTROL_MATRIX.md is stale: run the unit tests with OULG_UPDATE_MATRIX=1", now, committed);
    }

    @Test
    public void everyKeyHasAReaderAndEveryLayerAHook() throws IOException {
        render(); // asserts inside
    }
}
