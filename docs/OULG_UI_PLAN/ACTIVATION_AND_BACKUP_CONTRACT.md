# Ativação da lente, plano de publicação e backup (adendo R6, P0)

Origem: `ClearGlass_Visual_Handoff/OULG_R6_ADENDO_ATIVACAO/` (fora do repositório). Este contrato **prevalece** sobre `EFFECTIVE_STATE_CONTRACT.md §4` e §8 onde houver diferença, e entra antes da Fase C (tarefas T21–T28 em `TASKS.md`).

## 1. Causa confirmada (cadeia de código)

Cenário físico: aparelho reiniciado → propriedades `debug.oulg.*` (voláteis) ausentes; leitor disponível com mapa vazio; lente instalada mas não carregada (estado `RESTART`, "Pronta para ativar"); preferências intactas (Clear, kernel 1, adapt 1, `hclift=0.30`, `hubloom=1`); checkpoint compilado de outra geração (`…:3932:5298135`, SF atual PID 28017).

| Base | Cadeia | Resultado |
|---|---|---|
| `main` 4f33114 | `AppModel.pendingCompositor` → regra atual com `sfrefract` ausente = não-lente → todas as `sf.*` INACTIVE → 1ª passagem de `CompositorValues.pending` descarta as salvas; 2ª passagem compara observado ausente (= default por `same`) com o checkpoint para **toda** chave gravável, sem regra nem geração → põe o **default** no mapa | As 14 linhas da captura = exatamente as chaves salvas cujo checkpoint ≠ default. Confirmar escreveria Frosted, kernel 0, etc. |
| Fase B (1278fc5) | `PublicationPlan` avalia regras no alvo sem a lente → `writes = {}`; deriva vai para `inactiveDrift` | Lista vazia, mas o restart compila **defaults** (nada é escrito, props ausentes) → mesma perda de aparência, agora silenciosa. A Fase B não corrige; precisa deste contrato. |

Erro de raiz: (1) avaliar a aplicabilidade da **próxima** compilação com o estado de renderização **atual** (lente desligada é indisponibilidade transitória, não incompatibilidade); (2) tratar um checkpoint de outra geração como estado atual; (3) tratar propriedade ausente como valor conhecido.

## 2. Contextos de avaliação

| Contexto | Uso | `sfrefract` | Demais chaves |
|---|---|---|---|
| `NOW` | só rótulo "efeito agora" | observado | confirmados (salvo > observado > default) |
| `NEXT` | plano, guard de edição de `sf.*`, estado da linha `sf.*`, rota de layout quando há intenção de lente | **virtual `1` em memória** (todo restart do app usa modo `rewrite`, que carrega a lente; nunca escrito, nunca salvo; helper-owned intacto) | confirmados, sem drafts, resolvidos em ponto fixo (já em `PublicationPlan`) |

Incompatibilidade real (material, kernel, adapt, menisco, dependências) continua INACTIVE em `NEXT` → valor dormente, não publicado. Indisponibilidade transitória (`NEEDS_LENS` quando há intenção de lente) vira estado `PENDING_ACTIVATION`: editável, rotulado "Vale ao ativar a lente".

**Intenção de lente** (`config/state/LensIntent`): `1` se `sfrefract` observado = 1; senão pref `lens.intent` do app (`1` gravado quando um restart do app é pedido; `0` quando o usuário desinstala a lente pela tela); se a pref não existir (upgrade), derivada: `CompositorValues.hasCompiled(prefs) || !attemptValues(prefs).isEmpty()`. Sem intenção, `NEXT` = `NOW` para layout e linhas (usuário de vidro padrão não vê nada mudar); o plano de restart continua usando `NEXT`, pois só existe quando o usuário pede ativação.

LIVE keys usam sempre `NOW` (aplicam sem restart).

## 3. Evidência de compilação

`live/CompiledEvidence { Map inputs; String generation; long at; Freshness freshness }`, `Freshness = CURRENT | HISTORICAL | NONE`:

- `CURRENT` somente se: status fresco do helper com `sf_loaded=1`, `sfrefract=1`, `sf_rewrite=1`, e `generation` do baseline == `status.sf_generation` (não PID isolado; PID reutilizado com geração diferente = HISTORICAL).
- `HISTORICAL`: há baseline mas geração diferente, status ausente, ou lente não carregada. **Nunca** usado para diffs; mostrado só como "Último compilado: <data>, outra sessão do sistema".
- `NONE`: sem baseline.
- Tentativa (`restartValues`) e compilado confirmado (`compiledValues`) são separados: tentativa pendente só vira CURRENT via `confirmCompiled` existente.

## 4. Plano de publicação (substitui o algoritmo §4 do contrato anterior)

Entradas: `ConfirmedSnapshot` (sem drafts; `propsAvailable` distingue leitura falha de mapa vazio), `CompiledEvidence`, `LensIntent`, status do helper.

```text
if !propsAvailable: return Plan.BLOCKED(reason=PROPS_UNREADABLE)          # sem diffs artificiais; Apply desabilitado
R := Applicability(NEXT target, ponto fixo como hoje)                     # sfrefract virtual 1
for k in WRITABLE:
   s := saved[k]; o := observed[k] (null/"" = ausente); d := default(k)
   usable := s != null && R.rule(k).usable()
   T[k] := usable ? s : (o present ? o : d)                               # entradas alvo; = restartInputs(observed, writes)
   if usable && !sameNumeric(s, o ?? d):
        writes[k] := Write(value=s, reason = o present ? CHANGE : RESTORE) # RESTORE: valor salvo que o sistema perdeu
   if s != null && !usable && !sameNumeric(s, o ?? d): dormant += k        # guardado, não publicado
   if s == null && o present: external += k
if evidence.CURRENT:
   for k in WRITABLE \ writes, R.rule(k) != INACTIVE, k in C:
        if !sameNumeric(T[k], C[k]): drift[k] := Drift(T[k], reason = o present ? EXTERNAL : LOST)
if evidence.HISTORICAL or NONE:
   for k in WRITABLE, s == null, o absent, C has k, !sameNumeric(C[k], d):
        lostExternal += k        # valor do Terminal/sessão anterior que não existe mais; nunca escrito automaticamente
activation := status.sfrefract != 1 (lente não carregada)
needsRestart := activation || writes ≠ ∅ || drift ≠ ∅
verifyOnly := !needsRestart && loaded && evidence != CURRENT              # oferecer restart de verificação, sem lista
fingerprint := sha1(writes, drift, activation, status.sf_generation, saved revision)
```

`sameNumeric` = `ExternalChanges.same` (BigDecimal; RGB sem caixa). `restartInputs` existente continua sendo `T`.

Fixture do adendo (§6.1) → `activation=true`; `writes` contém, como RESTORE: `huclear=1, kgclear=1, hckernel=1, hcedge=30, hccore=32, hcblurexp=1, hublur=1.2, hurefract=1.09, hcneutral=1, hcsat=1, kgdepth=4.4, kgflat=0, kglevel=0.6` e as demais salvas ≠ default e usáveis; `hclift` **fora** de writes (salvo 0.30 = default; `T=0.30`); `hubloom` em `dormant`; nenhuma escrita de default; `drift = ∅` (evidência HISTORICAL); `lostExternal = ∅` (as 26 chaves só do checkpoint são iguais ao default).

UI (`LensSheet`): título "Ativar a lente"; grupos "Restaurar seus ajustes salvos (N)" (recolhido, valor = salvo; origem "ausente no sistema"), "Suas alterações (N)" (`atual → novo`, atual = observado), "Alterados fora do app (N)" (só com evidência CURRENT), "Guardados sem efeito (N)" (informativo), "Valores anteriores do Terminal que não existem mais (N)" com ação explícita "Adotar no app" por item (abre revisão; grava via `EditGuard.ADOPT_EXTERNAL`, proveniência ADOPT). Nunca usar checkpoint como "valor atual". No toque: reler status + props; recalcular; fingerprint diferente → nova confirmação, nunca publicação automática.

## 5. Conflitos externos

`ExternalChanges.conflict`: propriedade **ausente** (leitor disponível) nunca é evidência de edição externa (é reset/reboot) → sem conflito. Leitor indisponível → sem conflito e plano BLOCKED. Boot desconhecido continua como hoje para valores presentes.

## 6. Backup, estilos, restaurar, desfazer

### 6.1 Formato

Exportação nova: `schemaVersion 2`:

```json
{ "schemaVersion": 2, "kind": "oulg-settings",
  "app": {"versionCode": N, "registry": "<sha1 de key=default ordenado>"},
  "createdAt": ms, "device": "...", "enabled": true,
  "desired": {..todas as overrides, inclusive dormentes..},
  "implicitDefaults": {..chave editável sem override -> default desta versão..},
  "terminal": {"included": false, "values": {}},
  "diagnostic": {"observed": {"available": b, "capturedAt": ms, "values": {}},
                 "compiled": {"generation": "...", "at": ms, "freshness": "CURRENT|HISTORICAL|NONE", "inputs": {}}},
  "provenance": "desired e implicitDefaults são importáveis; terminal só com escolha explícita; diagnostic nunca" }
```

- `terminal.values`: só se o usuário marcar "Incluir valores definidos pelo Terminal" na exportação e o leitor estiver disponível (chaves `external` do plano, valores observados).
- `diagnostic.compiled.at` = momento da compilação confirmada (não da serialização); `requestedAt` antigo deixa de ser rotulado como tal.
- Tamanho máximo 128 KiB (existente). Exportar não altera nada.

Import aceita schema 1 (lê só `desired`, como hoje) e 2.

### 6.2 Plano de importação (`config/ImportPlan`, puro)

```text
ImportPlan.of(current overrides, parsed, mode ∈ {REPLACE, MERGE}, useBackupDefaults: bool, adoptTerminal: bool, NEXT rules after)
 result := mode==REPLACE ? {} : copy(current)
 result ⊕= parsed.desired
 if useBackupDefaults: for k in parsed.implicitDefaults where k ∉ parsed.desired and default(k) != value: result[k] := value
 if adoptTerminal: result ⊕= parsed.terminal.values (só chaves sem desired)
 groups: changed (k em ambos, !sameNumeric), added, removed (REPLACE: k em current e não em result → volta ao default; mostra default), kept, dormant (regra NEXT INACTIVE em result), unknown/ignored, flags
 needsLens := PublicationPlan(simulado com result).needsRestart
```

A revisão renderiza **o mesmo objeto** que a confirmação aplica (`repo.replaceAll(plan.result, plan.enabled)`, uma transação, um undo, proveniência IMPORT/STYLE). Igualdade numérica. Nenhuma publicação ou restart ao importar.

Estilos (`preset.custom`): salvar = encoder schema 2 (sem diagnostic); usar = `ImportPlan` em REPLACE.

### 6.3 Restaurar e desfazer

- "Restaurar padrões" (Estilos) e "Restaurar grupo"/linha: mantêm valores default **explícitos** (necessário para republicar `sf.*` que estavam publicados), com proveniência RESET; revisão lista o que muda.
- Desfazer: o snapshot passa a guardar `{label, at, overrides, enabled}`; tocar em Desfazer abre revisão (`ImportPlan` REPLACE do snapshot) antes de aplicar. Nenhuma mudança silenciosa.

### 6.3.1 Remover × restaurar × liberar (correção da revisão independente de 7db5b66)

Defeito: `ImportPlan.removed` prometia "volta ao padrão", mas `result` apenas omitia a chave; `replaceAll(clear)` apagava a override e `PublicationPlan.writesFor` só percorre chaves salvas. Uma `sf.*` publicada (ex.: `hcsat` salvo/observado/compilado 1.30) continuava 1.30 e passava a `external`; um parâmetro Java (LIVE) passava a ler `debug.oulg.<key>` (Tuning cai na propriedade quando não há override). Afetava importar em Substituir, estilo e Desfazer.

Regra:

| Ação | O que é salvo | Próximo Aplicar | Rótulo |
|---|---|---|---|
| Restaurar padrão (linha, grupo, Estilos › Restaurar padrões) | default **explícito** | publica o default se a propriedade difere | "Restaurar padrão" |
| Importar/estilo em Substituir, Desfazer: chave salva agora e ausente no arquivo/snapshot | default **explícito** (`ImportPlan.restoreDefaults`), listado em "Voltam ao padrão" com `de → padrão` | publica o default | "Voltam ao padrão (N)" |
| Mesclar | nada removido | — | — |
| Usar o valor do Terminal (`AppModel.useExternal` → `ConfigRepository.releaseToExternal`) | override **removida** | a propriedade atual fica; a chave passa a "Alterado fora do app" | ação explícita, só no aviso de conflito |

Importar, estilo e Desfazer **nunca** liberam uma chave para o Terminal. Consequência aceita: desfazer uma edição de uma chave que antes não tinha override salva o default explícito (o valor anterior do Terminal não é recuperável do snapshot); para devolver a chave ao Terminal use a ação explícita. Valor removido já igual ao default vai para "Mantidos". Flags de superfície removidas também gravam o default. Chaves helper-owned/somente leitura nunca entram. A revisão e a confirmação usam o mesmo `result`. Testes: `ImportRemovalRestoresDefaultTest` (8).

### 6.4 Proveniência por chave

`ConfigRepository` grava `src.<key>` = `EDIT|RESET|UNDO|IMPORT|STYLE|MATERIAL|ADOPT` + `srcAt.<key>` (ms) em toda escrita de override; uma entrada por chave (não é log). `ControlSheet` mostra "Última alteração: Desfazer, 18/11 14:02". Export não inclui proveniência.

## 7. `sf.hclift = 0.30` — investigação

Fatos: default de `sf.hclift` é 0.30 desde `966388d` (era 0 em `d0c8ca2`); nunca 0.40 no código. O checkpoint de 00:28:47 tem 0.40 → naquela compilação a propriedade era 0.40 (salva e escrita pelo app, ou Terminal). O export tem `"sf.hclift": "0.3"` **explícito** em `desired` → houve uma escrita posterior de override com 0.3 (remoção da override deixaria a chave ausente).

Caminhos do código que gravam exatamente `0.3` explícito: (a) edição manual (slider/entrada); (b) reset da linha/sheet (`resetToDefaults` grava o default); (c) Estilos › Restaurar padrões (grava todos os defaults — mas `hcneutral=1`, `hcsat=1`, `hcedge=30` no export mostram que não foi a última ação global); (d) Estilos › Desfazer (`undo` = `replace(snapshot, clear=true)` do estado anterior à última edição; se essa edição foi `hclift 0.3→0.4`, Desfazer volta a 0.3 sem mostrar diff); (e) aplicar estilo/importar arquivo com 0.3; (f) `GlassMaterial.recommendedClear()` contém `sf.hclift=0.3` apesar do comentário dizer que deixa Lift intacto (inconsistência real; só oferecido quando não há valores Clear salvos — improvável aqui, pois `hcedge=30/hccore=32` não são do recomendado). Upgrade do APK não escreve preferências; nenhuma migração toca `hclift`.

Conclusão: **origem não comprovada**; (d) e (b) são as hipóteses compatíveis com todas as evidências. Não reproduzível sem dados do aparelho. Ações: proveniência por chave (§6.4) e revisão antes de Desfazer (§6.3) tornam a próxima ocorrência atribuível/visível; corrigir (f): remover `sf.hclift` de `recommendedClear()` (alinha código ao comentário e ao mandato de preservar Clareamento). Opcional, com consentimento do usuário: inspecionar a chave `undo` do arquivo de preferências do app (contém o snapshot anterior à última edição) — sem captura contínua.

## 8. Testes obrigatórios (T21–T28)

1. `ActivationFixtureTest`: fixture sintética equivalente ao JSON (observado disponível e vazio; checkpoint HISTORICAL; `sfrefract` ausente; desired do arquivo). Asserções do §4. Variante "main": rodar `CompositorValues.pending` deprecado com a regra atual e afirmar que produz as 14 linhas (documenta a regressão; teste nomeado `legacyPendingReproducesAdendum`).
2. `CompiledEvidenceTest`: mesma geração + valores iguais → zero pendências; processo novo (geração diferente) com props preservadas/ausentes; status ausente; PID igual geração diferente; leitor indisponível → BLOCKED; boot desconhecido sem falso conflito.
3. `NextContextTest`: Frosted↔Clear, K0↔K1, adapt 0↔1, seletores e dependências alterados juntos; dormentes preservados; valor publicado incompatível não contamina (reusa T04).
4. `ActivationAfterEditTest`: editar com lente desligada (guard permite em NEXT), plano contém edição + restaurações, um restart.
5. `PlanRaceTest`: mudança de salvo, Terminal, status ou geração entre prévia e toque → STALE.
6. `BackupRoundTripTest`, `ImportPlanTest`: schema 1 e 2, REPLACE/MERGE, remoções listadas, igualdade numérica, dormentes, implicitDefaults opcionais, terminal opt-in, diagnostic nunca importado, revisão == aplicado, estilo, desfazer com revisão, nenhuma escrita ao exportar.
7. `ProvenanceTest`; `GlassMaterialTest.recommendedClearLeavesLift`.
8. `ExternalChangesTest.absentIsNotExternal`.
