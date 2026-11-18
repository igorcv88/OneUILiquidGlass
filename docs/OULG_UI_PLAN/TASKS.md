# Tarefas sequenciais (R6) — para execução pelo Sonnet

Branch única: `feat/r6-ui-state-energy` (já criada a partir de `origin/main` 4f33114). Um PR draft. Um commit por fase (B, C, D, E), mensagens `<type>: <description>` terminando com `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` (linha exigida pela sessão). Pacote Java: `app/src/main/java/io/github/igorcv88/oneuiliquidglass` (abaixo `PKG/`). Testes: `app/src/test/java/io/github/igorcv88/oneuiliquidglass` (`TEST/`).

Regras invioláveis em todas as tarefas: não alterar `sfhook/*.c|*.h` nem bundle nativo, `refract.h`, versão nativa, defaults/domínios de chaves existentes, `hclift` (nome/fórmula/comportamento), R5 (#202, #205 `hufadecut=-1` herda, #208 default 0, #210, #204). Não tocar release/signing/secrets. Não reiniciar SurfaceFlinger. Não apagar preferências. Não fechar issues. Não fazer merge. Se uma tarefa exigir violar isso, **parar e registrar em `EXECUTION_TRACKER.md` como BLOQUEIO para Opus**.

Comando de validação local (`V`): 
```
python tools/generate-control-reasons.py && python tools/generate-control-options.py && python tools/generate-control-descriptions.py && python tools/generate-control-titles.py && python tools/generate-settings.py && python tools/generate-product-layout.py && python tools/generate-product-copy.py && git diff --exit-code -- app/src/main/res app/src/main/java   # após commit dos gerados
./gradlew testDebugUnitTest lintDebug assembleDebug
python tools/test-distribution.py && sh sfhook/tools/live_check.sh && sh sfhook/tools/check.sh
python docs/OULG_UI_PLAN/tools/build_inventory.py --check
```
Sem `ANDROID_HOME`: criar `local.properties` (git-ignorado) com `sdk.dir=C\:\\Users\\igorv\\AppData\\Local\\Android\\Sdk`. Se `cc`/`sh` não existirem localmente, rodar `check.sh` só no CI e registrar.

---

## Fase B — Estado efetivo (commit `feat: resolve effective control state without drafts`)

### T01 Snapshot confirmado
- Arquivos: novo `PKG/config/state/ConfirmedSnapshot.java`; `PKG/config/ControlState.java`.
- Fazer: classe imutável conforme `EFFECTIVE_STATE_CONTRACT.md §3`. Em `ControlState`: adicionar `public String confirmed(String k)` (mesma lógica de `shown` sem consultar `drafts`), `public Applicability applicability()` passa a usar `this::confirmed`; adicionar `public Applicability previewApplicability()` usando `this::shown`; `public ConfirmedSnapshot snapshot(Map<String,String> compiled, boolean awaiting)`.
- Procurar todos os chamadores de `applicability()` (`grep -rn "applicability()" PKG`): manter os de regras/guard/publicação; trocar para `previewApplicability()` somente em `ui/preview/*` (prévia do cartão).
- Testes `TEST/config/state/ConfirmedSnapshotTest`: draft não muda `confirmed`; helper-owned (`sfrefract`) lê observado; READONLY lê observado; flag sem valor usa `ProductFlags.defaultEnabled`; chave sem saved usa observado, depois default.
- Aceite: testes antigos de `ControlState`/`ControlSheetApplicabilityTest` passam; novo teste de arquitetura (T05) cobre uso.
- Rollback: reverter arquivo; `applicability()` volta a `shown`.

### T02 Plano de publicação
- Arquivos: novo `PKG/live/PublicationPlan.java`; `PKG/live/CompositorValues.java` (sem mudar `pending` existente: marcar `@Deprecated`, ainda usado por testes antigos até T06).
- API:
```java
public final class PublicationPlan {
  public final Map<String,String> writes, activeDrift; public final Set<String> inactiveDrift, deferred, external;
  public final boolean unknownBaseline; public final String fingerprint;
  public boolean needsCompile(); public Map<String,String> listed();
  public static PublicationPlan of(ConfirmedSnapshot s, java.util.function.Function<java.util.function.Function<String,String>,Applicability> rules) throws PlanContradiction;
}
public final class PlanContradiction extends Exception { public final Set<String> keys; }
```
- Algoritmo: pseudocódigo `EFFECTIVE_STATE_CONTRACT.md §4` literalmente (até 4 iterações; `same` = comparação `SettingSpec.validate` canônica já usada em `CompositorValues.same`; apenas `SfLive.WRITABLE`). `fingerprint` = SHA-1 hex de `sorted(writes)+"|"+sorted(activeDrift)` (`java.security.MessageDigest`).
- Testes `TEST/live/PublicationPlanTest`: os 18 casos da tabela §8 + casos fora do HU listados abaixo dela. Regra fake para o caso 16 (uma `Function` que alterna INACTIVE conforme valor de outra chave em W).
- Aceite: todos os casos; `CompositorValuesTest` intacto.
- Rollback: arquivo novo, sem consumidores até T06.

### T03 Estado efetivo por chave
- Arquivo novo `PKG/config/state/EffectiveState.java` (`enum Kind {ACTIVE, PENDING_COMPILE, INACTIVE, OFF, READ_ONLY, EXTERNAL, UNKNOWN}`; `static Effective of(ControlState st, PublicationPlan plan, String key, Controls.Surface s)`), algoritmo §6.
- Testes `TEST/config/state/EffectiveStateTest`: um por ramo; latente exposto em INACTIVE/OFF; draft só altera `displayValue` quando ACTIVE (passar draft via parâmetro `draft` opcional).
- Rollback: arquivo novo.

### T04 Prova de identidade das regras INACTIVE (host-only)
- Arquivo novo `sfhook/tools/inactive_identity_check.sh` + chamada em `sfhook/tools/check.sh` após `material_controls_check.sh`. **Não alterar `refract_check.c` nem fontes nativas.**
- Mapeamento de nomes do harness `refract_check` (argv[4] `k=v,...`): HU Frosted `hublur,hurefract,hubloom,hubodylens,hucore,huedge,hutaps,huramp,hulight`; KG Frosted `core,taps,ramp,sat,tone,rim,spec,flat,level,cool,tilt,line,ccap,fcap,cdim,glow,gradient,band,bodylens,blurexp,kgedge,kgblur,kgrefract,kglight,kgdepth`; Clear `huclear,kgclear,hc<campo>,kc<campo>` (campo = sufixo da chave, ex. `hcamp`, `hcfold`, `hcadapt`, `hckernel`, `hchlneutral`, `hcneutral`).
- Para cada linha da tabela `EFFECTIVE_STATE_CONTRACT.md §5` com contexto GLSL: gerar `A` (contexto + chave no default do registro) e `B` (contexto + chave no extremo do domínio, ex. `hubloom=2`), comparar `cat mod_*.frag` com `cmp`. Casos mínimos: `hcamp` com `huclear=1,hcfold=2`; `hchlneutral=1` com `huclear=1,hcadapt=0`; `hcneutral=0` vs `0.75` com `huclear=1,hcsat=1,hcscrim=0,hcscrimmax=0,hclift=0`; `hubloom=2` com `huclear=1,hcadapt=1,hckernel=1` (e 2, 3); `kclevel` com `kgclear=1,kcveil=0`; cada chave Frosted-only do KG (`sat,tone,flat,level,cool,cdim,band,bodylens,blurexp,gradient,ccap,fcap,core,ramp`) com `kgclear=1`; `hucore,huedge,huramp,hubodylens` com `huclear=1`; todas `hc*` com `huclear=0` (amostra: `hcamp,hcfold,hcsat,hclift,hcedge`).
- Se algum par diferir: **não editar shader**; deixar o script falhar só para os casos já confirmados e registrar a chave divergente em `EXECUTION_TRACKER.md` como BLOQUEIO P-NEUTRAL (Opus decide entre ajustar a regra em `Applicability` para ACTIVE ou exigir neutro). Os casos estruturais (sem lente, perfil 2 sem shadetone) não têm teste GLSL.
- Aceite: script verde no CI (`ubuntu`); `check.sh` chama-o.
- Rollback: remover a linha do `check.sh`.

### T05 Guard único
- Arquivo novo `PKG/config/state/EditGuard.java` (tabela §7). Ligações: `AppModel.saveControl` (substitui o guard inline), novo `AppModel.reset(keys)` → para cada chave `RESET_ONE`, recusadas ficam; `ConfigRepository.resetToDefaults` passa a ser chamado só com as chaves aprovadas; `ControlRow` (commit/reset), `ControlSheet` (commit, estrela de favorito), `SearchSheet` (abrir), `PresetsSheet.PresetReview` (marcar latentes com `state_inactive`), `AppModel.selectMaterial` (sempre permite; uma transação).
- Validação dependente: `stylelo < stylehi` — em `AppModel.saveControl`, se a chave for `stylelo`/`stylehi` e o par resultante violar, recusar com `error_style_order`.
- Testes `TEST/config/state/EditGuardTest`: cada linha da tabela; `noDraftsInRules` = teste de fonte que lê `ui/AppModel.java`, `live/PublicationPlan.java`, `ui/SurfacesPage.java`, `config/state/*.java` e falha se contiverem `previewApplicability(`; reset de chave inativa não muda o valor salvo.
- Rollback: reverter ligações; guard antigo inline volta.

### T06 Apply com revalidação
- Arquivos: `PKG/ui/AppModel.java`, `PKG/ui/LensSheet.java`, `PKG/live/SfLiveController.java` (assinatura inalterada).
- `AppModel`: `public PublicationPlan plan()` (snapshot confirmado + baseline `CompositorValues.baseline(prefs)` + `awaiting`); `pendingCompositor()` passa a retornar `plan().listed()` (captura `PlanContradiction` → mapa vazio e `lastContradiction` setado); `clearDrafts()` antes de abrir confirmação.
- `LensSheet.confirmRestart`: guardar `plan.fingerprint`; no toque: `SystemPropertyReader.refresh()` no executor de IO existente, recomputar; se fingerprint diferente → `dismiss`, toast `apply_list_changed`, reabrir `confirmRestart()`; senão `controller.restartConfirmed(plan.writes)`. Contradição: botão desabilitado + `apply_contradiction`. Lista mostra origem (writes = "Seu ajuste"; activeDrift ∩ external = `state_external`).
- Testes: `TEST/ui/LensSheetPlanTest` (Robolectric se já usado no projeto; senão teste puro do método `AppModel.confirmPlan(String fingerprintShown)` que retorna `STALE|OK|CONTRADICTION`).
- Aceite: `SfLiveFlowTest` e `CompositorValuesTest` passam sem mudança de asserções; caso 14 coberto.
- Rollback: reverter AppModel/LensSheet.

**Commit da fase B** após `V` verde.

---

## Fase C — Layout de produto e telas (commit `feat: reorganize surfaces into main controls and appearance details`)

### T07 Gerador de layout
- Novo `tools/generate-product-layout.py`: lê `docs/OULG_UI_PLAN/CONTROL_MAPPING.json`, valida (cada chave do registro aparece exatamente uma vez entre surfaces/technical/icons/energy — mesma checagem que o Opus rodou; rotas válidas; chaves existentes), deduplica `main` dos `details` por (superfície, rota), e gera `PKG/config/product/ProductLayout.java` (gerado, cabeçalho "do not edit"):
```java
public final class ProductLayout {
  public enum Route { STANDARD, LENS_FROSTED, LENS_CLEAR, LENS_MIXED, CAPTURE, PLAIN_BLUR }
  public enum Destination { MAIN, DETAIL, BEHAVIOR, QS_MAIN, QS_DETAIL, TECHNICAL, DIAGNOSTIC, LEGACY, ICONS, MATERIAL, ENERGY }
  public enum Display { DEFAULT, MULTIPLIER_PERCENT }
  public static List<String> main(Controls.Surface s, Route r);
  public static List<Group> details(Controls.Surface s, Route r);   // Group{id, titleRes, keys}
  public static List<String> behavior(Controls.Surface s);
  public static List<String> quickMain(), quickDetails();
  public static List<Group> technical(); public static List<String> readOnly(), legacy();
  public static Destination destination(String key); public static Display display(String key);
  public static int productTitle(String key); // 0 = usar ControlTitles
}
```
LENS_MIXED = união F depois C sem duplicatas. Central sem material: rota C mapeia para F.
- Novo `PKG/config/product/Routes.java`: `static Route of(ConfirmedSnapshot s, Controls.Surface surf)` = `EFFECT_MAP.md §1` usando `Mode.of` e `GlassMaterial.forSurface` (sem drafts).
- CI: adicionar o gerador ao passo de diff em `.github/workflows/controls.yml` (mesmo padrão dos outros).
- Testes `TEST/config/product/ProductLayoutTest`: cobertura 208/214 exata; nenhum TECHNICAL/DIAGNOSTIC/LEGACY em main/details; HU C principais = `[sf.hublur, sf.hurefract, sf.hclift, sf.hcsat, sf.hcfold]`; `sf.hclift` acima de qualquer realce (índice no main); `hcneutral` depois de `hchlneutral` no grupo color; LENS_MIXED sem duplicatas; `RoutesTest` para cada modo/material e draft ignorado.
- Rollback: remover gerado + gerador + passo CI.

### T08 Copy
- Novo `tools/generate-product-copy.py`: lê `docs/OULG_UI_PLAN/COPY_PT_EN.csv` (UTF-8, `csv` module) e gera `app/src/main/res/values/product_copy.xml` (en) e `values-pt-rBR/product_copy.xml` (pt), escapando `'`, `&`, `<`, `%` literal (`%%` já no CSV apenas em strings formatadas; strings sem `%1$` com `%` recebem `formatted="false"`). Adicionar ao diff do CI.
- `LocalizationParityTest` deve continuar passando (paridade de chaves).
- Rollback: remover arquivos.

### T09 Superfícies
- `PKG/ui/SurfacesPage.java`: substituir a construção por `ProductLayout`: ordem `SCREEN_SPECS.md §2`. Remover disclosures por `Section` STD/ADV e o bloco técnico por superfície. Reaproveitar `ControlRow`. Rótulo de linha: `ProductLayout.productTitle(key)` se ≠0, senão título atual. Rota recalculada em cada `bind` via `Routes.of(state.snapshot(...), surface)`; mudança de rota reconstrói a lista preservando scroll e grupos abertos.
- Arquivo deve ficar < 800 linhas: extrair `ui/surfaces/DetailGroupsView.java` e `ui/surfaces/BehaviorView.java` se necessário.
- Testes: `TEST/ui/SurfacesLayoutTest` (puro: função `SurfacesPage.plan(surface, route, favorites)` que devolve a lista ordenada de seções/chaves, sem View).
- Rollback: reverter arquivos.

### T10 Grupos e restaurar grupo
- Novo `PKG/ui/kit/GroupHeader.java` (DESIGN_SYSTEM §4); estado aberto em ui prefs `group.<surface>.<route>.<id>`.
- `AppModel.resetGroup(Collection<String> keys)`: filtra por `EditGuard RESET_GROUP`, chama `repo.resetToDefaults(approved)` (uma transação, um undo), retorna recusadas para o texto da confirmação.
- Testes: `AppModelResetGroupTest` (um snapshot de undo; inativa mantém latente).

### T11 Linha e editor com estado
- `ControlRow.bind(Effective e)`: valor `state_inactive`/`state_off` em vez de número; motivo; ponto `warn` pendente; ponto `lilac` externo; `contentDescription` = título, valor exibido, estado.
- `ControlSheet`: cabeçalho de estado; somente leitura quando guard recusa, com `state_latent`; `MULTIPLIER_PERCENT` (`ValueFormat` novo método `percentOfMultiplier(float)` e parser que aceita `65`, `65 %`, `65%`); linha "Afeta: …" para `sf.hublur/kgblur/hurefract/kgrefract`; linha `blur_effective_nominal` para `hcedge/hccore/edgeblur/core` (e KG) quando a rota tem o pai, com `blur_chain_note`.
- Aviso de Lift ≥ 1 mantido exatamente.
- Testes: `ValueFormatPercentTest`, `ControlRowStateTest` (texto e descrição por Kind).

### T12 Busca
- `SearchSheet`: índice inclui `productTitle`; mostra caminho; exclui LEGACY sempre e TECHNICAL/DIAGNOSTIC sem "Mostrar técnico"; abertura via guard.
- Teste: `SearchIndexTest`.

### T13 Favoritos
- Lista de favoritos filtra `ProductLayout.destination ∈ {MAIN, DETAIL, BEHAVIOR, QS_MAIN, QS_DETAIL}`. Preferências `favorite.*` **não são apagadas**. Primeira execução após atualização: se existir favorito filtrado e `ui pref favorites.migrated.r6` ausente, mostrar uma vez `favorites_migrated` e gravar a flag.
- Teste: `FavoritesFilterTest`.

### T14 Técnico e diagnóstico
- Novo `PKG/ui/TechnicalSheet.java` (Sheet cheia) com grupos `ProductLayout.technical()`, somente leitura `readOnly()`, legado `legacy()`, seletor de material incluindo valor 2. Entrada em `SettingsPage` entre Diagnóstico e Recuperação; "Todos os parâmetros" e "Copiar relatório" movidos para dentro (mantendo os existentes funcionando).
- `MaterialSheet`: opção "Por cartão" oculta salvo se valor atual for 2.
- Teste: `TechnicalLayoutTest` (nenhuma chave técnica fora daqui).

**Commit da fase C** após `V` verde.

---

## Fase D — Energia (commit `feat: user-controlled energy policy without battery saver gate`)

### T15 Chaves de energia no registro
- `docs/PROPERTY_CATALOG_2026-10-26.md`: adicionar 6 linhas no formato `| \`energymode\` | 0 | 0–1 | … |` (domínios de `ENERGY_POLICY.md §3`).
- `tools/generate-settings.py`: `assert len(rows)==214`; `energymode, energythreshold, energyhysteresis, energysurfaces` em `ints`; `energycharging, energysaver` em `bools`; títulos pt em `titles`. Regenerar `SettingsRegistry.java`.
- `PKG/glass/Tuning.java`: campos `energyMode, energyThreshold, energyHysteresis, energySurfaces` (int) e `energyCharging, energySaver` (boolean) lidos com o helper `number("energy…")` existente e clamp aos domínios (exigido por `SettingsRegistryTest.everyReaderKeyIsRegistered` e `ControlMatrixTest`).
- `PKG/config/Controls.java`: nova `Section.ENERGY` (tier STANDARD, `.all()`), uma entrada por chave com help; `SurfacesPage`/`SearchSheet` não exibem `Section.ENERGY` (Search abre Configurações › Energia).
- Traduções: `tools/control-title-translations.json`, `control-descriptions.json`, `control-option-translations.json` (choices de `energymode`); rodar todos os geradores.
- Rodar `ControlMatrixTest` (regenera `docs/CONTROL_MATRIX.md`) e `build_inventory.py` (passa a 214).
- Testes existentes que fixam 208 (`ClearGlassRegistryTest` usa 33 WRITABLE — inalterado). Atualizar apenas asserts de contagem total se houver.

### T16 Política pura
- Novo `PKG/glass/EnergyPolicy.java` conforme `ENERGY_POLICY.md §4` (imutável; `Config.from(Tuning t, boolean master)`).
- `TEST/glass/EnergyPolicyTest`: tabela §4 completa + `surfaces==0` + master off.

### T17 Governor e gate
- Novo `PKG/config/EnergyGovernor.java` (§5). `ConfigClient`: campo `static volatile int energyMask`; `flag(key)` aplica máscara (mapa bit→flag em `EnergyGovernor.FLAG_BITS`); `start(...)` cria o governor uma vez com o mesmo handler/worker; no `refresh` quando `energy*` mudarem, `governor.reevaluate()`.
- `hooks/ongoing/LiveSurfaceHooks.java`: `allowed(String disableWindowBlurs)`; `enabled()` deixa de ler `isPowerSaveMode`; remover receiver de Saver (linhas ~263-271). Manter listener de cross-window blur e `disable_window_blurs`.
- `TEST/hooks/ongoing/LiveSurfaceHooksTest`: substituir o caso `allowed(true,null)` por `allowedIgnoresSaver` (`allowed(null)` true, `allowed("1")` false).
- `TEST/config/EnergyGovernorTest`: com fakes (injetar `Clock`-free `Input` via método package-private `onState(Input)`), transições despacham uma vez, repetição não despacha, máscara afeta só bits, flag salva intacta, reevaluate com config nova.
- Não registrar alarmes/wakelocks; `RECEIVER_NOT_EXPORTED`.

### T18 Estado visível e UI
- Governor envia `ENERGY_STATE` (explícito, pacote do app) com extras §6. `ConfigReadReceiver`: tratar a ação, validar tipos/limites (reason ∈ enum, 0≤mask≤127, -1≤level≤100), gravar `engine_status energy.*`.
- `SettingsPage`: seção Energia (`SCREEN_SPECS.md §5.1`), extraída para `ui/EnergySection.java`; máscara nunca 0.
- `HomePage`: aviso compacto quando `energy.reason != NONE`.
- Testes: `ConfigReadReceiverEnergyTest` (valores inválidos ignorados), `EnergySectionTextTest` (textos por estado, unknown quando `at==0`).

**Commit da fase D** após `V` verde.

---

## Fase E — Documentação, CI, PR, APK (commit `docs: record R6 control inventory and validation`)

### T19 Documentos vivos
- Regenerar `CONTROL_INVENTORY.csv` (`build_inventory.py`), `docs/CONTROL_MATRIX.md`. Atualizar `docs/OULG_UI_PLAN/EXECUTION_TRACKER.md` (status por tarefa, comandos e resultados reais, desvios). Adicionar em `docs/architecture.md` uma seção curta "Estado efetivo e publicação (R6)" apontando para o contrato. Não editar `ClearGlass_Visual_Handoff/` além do complemento já feito pelo Opus.

### T20 PR e artefato
- `git push -u origin feat/r6-ui-state-energy`; `gh pr create --draft --base main` com título `feat: R6 UI reorganization, effective state and energy policy`, corpo: resumo por fase, decisões (link para `docs/OULG_UI_PLAN/PLAN_MASTER.md`), o que **não** mudou (óptica, Lift, nativo), plano de teste físico (`VALIDATION_MATRIX.md §3`), "Track #209" (sem `Closes`), terminando com `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.
- Aguardar CI (`gh pr checks --watch`). Obter artefato assinado (job isolado já existente, retenção 1 dia) e SHA-256 (`gh run download` + `sha256sum`). Registrar no tracker.
- Nunca `gh pr merge`, nunca release.
