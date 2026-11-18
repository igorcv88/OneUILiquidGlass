# Execution tracker (R6)

Atualizado pelo executor a cada tarefa. Status: TODO / DOING / DONE / BLOCKED.

| Tarefa | Status | Commit | Comandos e resultado real | Desvios |
|---|---|---|---|---|
| Plano (Opus) | DONE | (commit de plano) | `build_inventory.py --check` OK; mapeamento cobre 208 chaves uma vez | — |
| T01 | DONE | (commit da fase B) | `./gradlew testDebugUnitTest --tests *ConfirmedSnapshotTest --tests *ControlModelTest --tests *GlassMaterialApplicabilityTest` BUILD SUCCESSFUL (ConfirmedSnapshotTest 8/8). `applicability()` passou a `confirmed`; `previewApplicability()` sem chamadores (preview não usa regras). | `ConfirmedSnapshot` tem construtor público de 6 campos; `ControlState.snapshot(compiled, awaiting)` preenche `propsAvailable` com `SystemPropertyReader.available()`. |
| T02 | DONE | (commit da fase B) | `PublicationPlanTest` 24/24 (18 casos da §8 + HU/KG/shade/LIVE/externo/imutabilidade). `CompositorValuesTest` intacto (métodos `pending` marcados `@Deprecated`). | Caso 16 usa uma função de valores distorcida passada a `Applicability` (a classe é final). `fingerprint` = SHA-1 de `k=v,` ordenado de writes, `|`, activeDrift. `external` exclui chaves helper-owned/READONLY. |
| T03 | DONE | (commit da fase B) | `EffectiveStateTest` 10/10, um por ramo, latente em INACTIVE/OFF, draft só em ACTIVE. | `notes` é `Set<Note>` (enum APPLY_TO_TAKE_EFFECT, CHANGED_OUTSIDE_APP, UNKNOWN_COMPILED); `displayValue` de INACTIVE/OFF são tokens `Inativo`/`Desligado` (a UI mapeia para recursos em T11). |
| T04 | DONE (com BLOQUEIO P-NEUTRAL) | (commit da fase B) | `sfhook/tools/inactive_identity_check.sh` + chamada em `check.sh`. WSL Ubuntu 26.04 (`cc`, `glslangValidator`): `sh sfhook/tools/inactive_identity_check.sh` => `58 pairs, 39 byte-identical, 19 divergent (pinned: 19)`, exit 0; `sh sfhook/tools/check.sh` exit 0; `INACTIVE_IDENTITY_STRICT=1` exit 1. | O script fixa em `EXPECTED_DIVERGENT=19` e falha se o número mudar; divergências são impressas como `BLOCKER P-NEUTRAL`. Nenhum shader/regra alterado. Ver Bloqueios. |
| T05 | DONE | (commit da fase B) | `EditGuardTest` 8/8 (tabela §7 + `noDraftsInRules`); `AppModelGuardTest` (reset mantém latente, reset de linha recusado, draft não destrava, ordem stylelo/stylehi). | `saveControl(key,value,surface,Action)` novo; `reset(keys, surface)` retorna as recusadas (RESET_ONE). `SearchSheet`/`AppModel.selectMaterial` não ganharam chamada: OPEN_EDITOR nunca recusa (a `ControlSheet` já abre somente leitura pela regra) e MATERIAL_SELECT é sempre permitido (já é uma transação, um undo). Favorito: só regra, sem filtro de destino (vem com T13). PresetReview marca latentes com `state_inactive`, avaliando as regras no estado pós-importação. Ordem stylelo/stylehi também vale para o reset de linha. |
| T06 | DONE | (commit da fase B) | `AppModelGuardTest`: `confirmPlan` OK/STALE (mudança salva e deriva do Terminal, caso 14). `SfLiveFlowTest`/`CompositorValuesTest` sem mudança. | `AppModel.plan()` retorna null em contradição (`contradiction()` guarda a causa); `confirmPlan` retorna `PlanCheck {OK, STALE, CONTRADICTION}`. Revalidação usa `refreshObserved`. Teste de `LensSheet` é no modelo (sem View). Lista do diálogo não rotula "Seu ajuste" (não há copy no CSV); só `state_external`. Strings novas em `values*/product_copy.xml` (subconjunto de B; T08 regenera o arquivo inteiro). |
| T04b | DONE | (commit "fix: correct Clear applicability of shared lighting keys") | Removidas `sf.ramp/kgramp/kggradient/kgband` de `GlassMaterial.FROSTED_ONLY`; `GlassMaterialTest`, `GlassMaterialApplicabilityTest`, `PublicationPlanTest` (caso 8b) atualizados; `MATERIAL_MATRIX.md` regenerado (`OULG_UPDATE_MATRIX=1`); `CONTROL_MAPPING.json` `sf.kgband@F,C`; `CONTROL_INVENTORY.csv` regenerado (`--check` OK, 208). WSL: `sh sfhook/tools/inactive_identity_check.sh` => `54 pairs, 39 byte-identical, 15 divergent and class-checked (allowlist: 13 keys), 4 ACTIVE controls`, exit 0; `INACTIVE_IDENTITY_STRICT=1` exit 1; `check.sh` e `live_check.sh` exit 0. | Padrões de linha de `OVERWRITTEN` ampliados além do texto da decisão: a execução real mostrou linhas `s -= ` (`oulg_field_base`, `hubodylens`), `highp float core =` e `band1 <= 0.0 &&` (guarda de `oulg_br`, em `oulg_shift_base`) e linhas de `oulg_material_base` (`body`/`lbody`, inalcançável); `OVERWRITTEN` aceita também linhas dentro de funções UNREACHABLE. Opus deve confirmar. A allowlist tem 13 chaves (15 pares: `hubloom` x3 e as outras uma vez; a decisão citou 15 "entradas" = pares). Adicionado controle positivo: os 4 pares ACTIVE devem divergir. |
| T07 | TODO | | | |
| T08 | TODO | | | |
| T09 | TODO | | | |
| T10 | TODO | | | |
| T11 | TODO | | | |
| T12 | TODO | | | |
| T13 | TODO | | | |
| T14 | TODO | | | |
| T15 | TODO | | | |
| T16 | TODO | | | |
| T17 | TODO | | | |
| T18 | TODO | | | |
| T19 | TODO | | | |
| T20 | TODO | | | |

## Bloqueios para Opus

### P-NEUTRAL (T04): 19 pares INACTIVE sem prova de identidade de GLSL

Comando: `sh sfhook/tools/inactive_identity_check.sh` (WSL, `cc` + `refract_check.c` + `testdata/synthetic_dump.txt`, debug 0, cada par varia só a chave inativa entre o default do registro e um extremo do domínio). 58 pares, 39 idênticos, 19 divergem. Nada foi alterado em shader, regra ou `refract_check.c`.

Divergem (contexto => chave harness; chave do registro):
- `huclear=1,kgclear=1`: `hucore`, `huedge`, `huramp`, `hubodylens` (sf.core, edgeblur, ramp, hubodylens); `core`, `kgedge`, `ccap`, `ramp`, `blurexp`, `gradient`, `band`, `bodylens`, `sat`, `cool`, `fcap` (sf.kgcore, kgedgeblur, kgcorecap, kgramp, kgblurexp, kggradient, kgband, kgbodylens, kgsat, kgcool, kgflatcap). Idênticos: `tone`, `flat`, `level`, `cdim`.
- `kgclear=1,kcveil=0`: `kclevel` (0.85 vs 0.1): `yt = y + 0.000 * (0.850 - y)` muda só o literal do alvo.
- `huclear=1,hcadapt=1,hckernel=1|2|3`: `hubloom` (0 vs 2): `oulg_disc0` ganha `bloom`/`w709`/`lc`.

Evidência: para `hucore` as linhas que mudam são `... 56.00 * smoothstep(0.25 * bevel, ...)` e `oulg_br = mix(8.00, 56.00, ...)`; para `hubloom` a função `oulg_disc0` continua emitida e `oulg_body` despacha em tempo de execução (`oulg_pf > 0.5 ? oulg_disc1 : oulg_cl > 0.5 ? oulg_body_hu : oulg_disc0`). Ou seja, `huclear`/`kgclear` não tornam o ramo Frosted inalcançável em tempo de compilação: a identidade textual falha e a neutralidade depende de `oulg_cl` (tag por cartão) em tempo de execução. Pode ser código morto (literal sobrescrito pela saída Clear) ou alcançável com seletor por cartão; o teste não distingue. Decisão do Opus: manter INACTIVE (aceitando prova por leitura de runtime), tornar ACTIVE/NEEDS_RESTART, ou exigir valor neutro (`NEEDS_NEUTRAL`). Enquanto isso, a Fase B continua gravando nada para essas chaves (o plano as deixa em `deferred`), o que é o comportamento R5.

Pares Clear-only sob material Fosco (31), `hcamp/kcamp` com menisco, `hchlneutral` sem adaptativo e `hcneutral` sem bloco de material: idênticos.

#### Decisão Opus (P-NEUTRAL) — 2026-11-18

Leitura de `sfhook/refract.h` (linhas 349-395 `oulg_shift_base`, 912-945 `oulg_clearN`, 1054-1077 despacho/material, 1104-1127 `oulg_field`): com seletor 1, `oulg_cl = 1` para todo cartão marcado do perfil, e o caminho Clear **ainda executa `oulg_shift_base`** e depois sobrescreve só `s`, `oulg_br` e `oulg_bw`. Sobrevivem `oulg_lw` (= `oulg_bw` na linha 375, antes da sobrescrita), `oulg_t`, `oulg_bv`, `oulg_n` (coordenadas de luz), lidos por `oulg_material_clear` (linha 1061) e pela borda. Regra: na dúvida, ACTIVE (erro inofensivo = um restart a mais; INACTIVE errado esconde efeito real).

1. **Passam a ACTIVE sob Transparente** (remover de `GlassMaterial.FROSTED_ONLY`; tarefa T04b): `sf.ramp`, `sf.kgramp` (alteram `oulg_bw` antes de `oulg_lw = oulg_bw`), `sf.kggradient` (mistura em `oulg_bw` antes de `oulg_lw`), `sf.kgband` (ramo `band1 > 0` define `oulg_t/oulg_bv/oulg_n`). `CONTROL_MAPPING.json`: `sf.kgband@F` → `sf.kgband@F,C`.
2. **Permanecem INACTIVE, prova por leitor (não por identidade textual)**, cada uma com classe:
   - `OVERWRITTEN` (só mudam `oulg_br`/`oulg_bw` após 375 ou o `s` retornado por `oulg_shift_base`, ambos sobrescritos por `oulg_clearN` e `s = oulg_clearN(...)`): `sf.core`, `sf.edgeblur`, `sf.hubodylens`, `sf.kgcore`, `sf.kgedgeblur`, `sf.kgcorecap`, `sf.kgblurexp`, `sf.kgbodylens`.
   - `UNREACHABLE` (função só chamada quando `oulg_cl < 0.5`: `oulg_material_base`, `oulg_disc0`): `sf.kgsat`, `sf.kgcool`, `sf.kgflatcap`, `sf.hubloom` (kernel 1-3 com adapt>0: `oulg_body` despacha `oulg_body_hu`).
   - `ZERO_FACTOR` (multiplicado por literal `0.000`): `sf.kclevel` com `kcveil=0`.
3. **Script (T04b)**: trocar `EXPECTED_DIVERGENT=19` por allowlist explícita `chave:classe` (15 entradas acima); para `OVERWRITTEN` exigir que toda linha alterada contenha `oulg_br =`, `oulg_bw =`, `* smoothstep(0.25 * bevel` (continuação da atribuição de `oulg_br`), `highp vec2 s =` ou `return s`; para `ZERO_FACTOR` exigir `0.000 *` na linha; `UNREACHABLE` exige que a linha esteja dentro de `oulg_material_base`/`oulg_disc0` (rastrear a última linha `... oulg_<nome>(` de definição antes da linha alterada). Divergência fora da allowlist ou fora do padrão = falha. Os 4 pares de (1) saem do script (agora ACTIVE). Seletor 2 (por cartão) continua com regra de material misto (todas ativas).
4. P-NEUTRAL resolvido; nenhuma escrita de neutralidade necessária.

## Ambiente local

`python tools/generate-*.py` no Windows exige `PYTHONUTF8=1` (sem isso o gerador de motivos produz hashes errados). `local.properties` (git-ignorado): `sdk.dir=C\:/Users/igorv/AppData/Local/Android/Sdk`. `check.sh`/`live_check.sh` rodados no WSL Ubuntu 26.04 com `MSYS_NO_PATHCONV=1`. `tools/generate-product-layout.py` e `generate-product-copy.py` do comando V ainda não existem (Fase C).

## Artefatos

PR: — · CI: — · APK: — · SHA-256: —
