package io.github.igorcv88.oneuiliquidglass.config;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import org.junit.Test;

/**
 * Builds docs/clear-glass/MATERIAL_MATRIX.md: for each control of the pop-up and the lock screen,
 * the material it acts on and whether the ordinary lists show it for Frosted and for Clear, with
 * the main controls of each. Fails when the committed file is stale; run with OULG_UPDATE_MATRIX=1
 * to rewrite it (and review the diff).
 */
public class MaterialMatrixTest {
    private static final Path DOC = Paths.get("../docs/clear-glass/MATERIAL_MATRIX.md");

    private static String yes(boolean b) {
        return b ? "sim" : "não";
    }

    static String render() {
        StringBuilder b = new StringBuilder();
        b.append("# Matriz de visibilidade por material do vidro\n\n")
                .append("Gerada por `MaterialMatrixTest` a partir de `Controls` e `GlassMaterial`. Não edite à mão: rode os testes com")
                .append(" `OULG_UPDATE_MATRIX=1` e revise o diff.\n\n")
                .append("- **Escopo**: `comum` age em qualquer material (inclui botões antigos que continuam valendo no Clear);")
                .append(" `fosco` só age no Fosco (óptica 11, substituído pelo campo Clear); `clear` só age no Transparente.\n")
                .append("- **Fosco / Transparente**: se a lista comum do ambiente mostra o controle quando o material do ambiente é esse.")
                .append(" A pesquisa e os favoritos seguem a mesma regra; **Todos os parâmetros** lista tudo e indica o material.\n")
                .append("- O material de cada perfil vem do seu seletor (`sf.huclear` no pop-up, `sf.kgclear` no bloqueio); o seletor 2")
                .append(" (por cartão) mostra os dois grupos. A central e a Central de controle não são filtradas.\n")
                .append("- Valores ocultos continuam salvos; trocar de material não os apaga.\n\n");
        b.append("## Principais por ambiente\n\n| Ambiente | Material | Controles principais |\n| --- | --- | --- |\n");
        for (Controls.Surface s : new Controls.Surface[] {Controls.Surface.HEADSUP, Controls.Surface.KEYGUARD}) {
            for (GlassMaterial m : GlassMaterial.values())
                b.append("| ").append(s.label).append(" | ").append(m.id).append(" (lente) | ")
                        .append(String.join(", ", Controls.primary(s, Mode.DYNAMIC_LENS, m))).append(" |\n");
        }
        b.append("\n## Controles do pop-up e do bloqueio\n\n")
                .append("| Chave | Nome na UI | Ambientes | Seção · nível | Escopo | Fosco | Transparente |\n")
                .append("| --- | --- | --- | --- | --- | --- | --- |\n");
        List<Controls.Control> rows = new ArrayList<>();
        for (Controls.Control c : Controls.all())
            if (Controls.editable(c.key) && (c.on(Controls.Surface.HEADSUP) || c.on(Controls.Surface.KEYGUARD))
                    && GlassMaterial.scope(c.key) != GlassMaterial.Scope.COMMON) rows.add(c);
        for (Controls.Control c : rows) {
            StringBuilder where = new StringBuilder();
            for (Controls.Surface s : c.surfaces) where.append(where.length() == 0 ? "" : ", ").append(s.label);
            GlassMaterial.Scope scope = GlassMaterial.scope(c.key);
            b.append("| `").append(c.key).append("` | ").append(c.title).append(" | ").append(where).append(" | ")
                    .append(c.section.label).append(" · ").append(c.tier.name().toLowerCase(Locale.ROOT)).append(" | ")
                    .append(scope == GlassMaterial.Scope.CLEAR_ONLY ? "clear" : "fosco").append(" | ")
                    .append(yes(GlassMaterial.FROSTED.acts(c.key))).append(" | ")
                    .append(yes(GlassMaterial.CLEAR.acts(c.key))).append(" |\n");
        }
        b.append("\n## Comuns do compositor que continuam valendo no Transparente\n\n");
        List<String> common = new ArrayList<>();
        for (Controls.Control c : Controls.all())
            if (c.key.startsWith("sf.") && Controls.editable(c.key)
                    && (c.on(Controls.Surface.HEADSUP) || c.on(Controls.Surface.KEYGUARD))
                    && GlassMaterial.scope(c.key) == GlassMaterial.Scope.COMMON) common.add("`" + c.key + "`");
        b.append(String.join(", ", common)).append("\n");
        return b.toString();
    }

    @Test
    public void committedMatrixMatchesTheCode() throws IOException {
        String now = render();
        if ("1".equals(System.getenv("OULG_UPDATE_MATRIX"))) Files.write(DOC, now.getBytes(StandardCharsets.UTF_8));
        String committed = Files.exists(DOC) ? new String(Files.readAllBytes(DOC), StandardCharsets.UTF_8) : "";
        assertEquals("docs/clear-glass/MATERIAL_MATRIX.md is stale: run the unit tests with OULG_UPDATE_MATRIX=1",
                now.replace("\r\n", "\n"), committed.replace("\r\n", "\n"));
    }
}
