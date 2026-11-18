# Contrato de estado efetivo (R6)

Baseline `4f33114`. Status: **planejado** (nada implementado). Este contrato substitui o filtro parcial R5.3 por uma resolução única. Ele não muda nenhuma chave persistida, domínio, default, shader ou bundle nativo.

## 1. O que existe hoje (auditado)

| Peça | Comportamento atual | Problema |
|---|---|---|
| `ControlState.shown(k)` | draft > saved > device | Usado também por `applicability()`: um draft não salvo muda regras de outros controles. |
| `AppModel.saveControl` | Recusa INACTIVE/OFF via `applicability().on()` | Avalia com drafts; `AppModel.reset(keys)` e `ConfigRepository.resetToDefaults` não passam pelo guard. |
| `AppModel.pendingCompositor()` | `CompositorValues.pending(saved, observed, baseline, rule==INACTIVE)` | 1ª passagem filtra salvos inativos (R5.3). 2ª passagem (observed≠compiled) não consulta regra: deriva de chave inativa volta à lista e pede restart. Regra avaliada com drafts. |
| `LensSheet.confirmRestart` | Captura `pending` ao abrir o diálogo; envia no toque | Mudança concorrente entre abrir e tocar envia lista velha. |
| `SfLiveController.RESTART` | `restartInputs(observed, values)`; `recordAttempt`; `applyAndRestart`; `adoptWritten` | Correto; recebe o mapa decidido pela UI. |
| `ControlRow.bind` | Mostra valor salvo em cinza + motivo | Valor latente parece intensidade atual (`1.00` cinza). |

## 2. Estados por chave (vocabulário fechado)

| Estado | Fonte | Significado |
|---|---|---|
| `latent` | `saved[k]` com regra INACTIVE/OFF | Intenção guardada para outro contexto. Nunca publicada enquanto inativa. |
| `draft` | `AppModel.drafts[k]` | Arrasto em curso. Só prévia; nunca afeta regras, publicação, guard. |
| `saved` | `ConfigRepository.overrides()` | Intenção confirmada pelo usuário. |
| `observed` | `SystemPropertyReader.snapshot()` | Propriedade lida (`getprop`), inclusive escrita por Terminal. Não prova compilação. |
| `target` | `T(k)` §3 | Configuração alvo autorizada que vai compilar/ser lida. |
| `write` | `PublicationPlan.writes` | Conjunto físico de `setprop` do próximo Apply. |
| `compiled` | `CompositorValues.baseline(prefs)` | Entradas do último restart confirmado (ou tentativa aguardando verificação). `null` = desconhecido. |
| `effective` | `EffectiveState.of(k, surface)` | O que a rota usa: ACTIVE, PENDING_COMPILE, INACTIVE, OFF, READ_ONLY, EXTERNAL, UNKNOWN. Nunca afirma resultado visual. |

## 3. Snapshot confirmado e alvo

Novo `config/state/ConfirmedSnapshot` (imutável, puro Java):

```java
public final class ConfirmedSnapshot {
  public final Map<String,String> saved, observed;   // cópias imutáveis
  public final Map<String,String> compiled;          // null = sem baseline
  public final boolean enabled, propsAvailable, awaitingVerification;
  /** Valor que os leitores usarão após o próximo Apply, sem drafts. */
  public String value(String k) {
    if (k.equals("enabled")) return enabled ? "1" : "0";
    if (ProductFlags.known(k)) { String v = saved.get(k); return v != null ? v : (ProductFlags.defaultEnabled(k) ? "1" : "0"); }
    SettingSpec s = SettingsRegistry.get(k); if (s == null) return null;
    if (s.helperOwned() || s.type == SettingSpec.Type.READONLY) return nonEmpty(observed.get(k), s.defaultValue);
    String v = saved.get(k); if (v != null) return v;      // intenção do app
    return nonEmpty(observed.get(k), s.defaultValue);     // Terminal/externo ou default da biblioteca
  }
}
```

`ControlState` ganha `confirmed(k)` (= `shown` sem drafts) e `applicability()` passa a usar `confirmed`. Um novo `previewApplicability()` usa `shown` e só pode ser chamado pela prévia (`NotificationPreview`/`PreviewParams`). Teste de arquitetura (`EditGuardTest.noDraftsInRules`) falha se `saveControl`, `reset`, `PublicationPlan` ou `SurfacesPage.bind` chamarem `previewApplicability`.

## 4. Algoritmo de publicação

`live/PublicationPlan` (puro Java, sem Android):

```text
plan(S: ConfirmedSnapshot):
  W := {}                                          # writes
  repeat at most 4 times:
     T := S.observed ⊕ W ⊕ (LIVE saved)            # alvo provisório
     R := Applicability(k -> T-value(k))           # regras avaliadas no alvo, nunca em drafts
     W' := { k -> S.saved[k] | k ∈ WRITABLE, k ∈ S.saved,
                               R.rule(k).kind ∈ {ACTIVE, NEEDS_RESTART},
                               !same(S.saved[k], S.observed[k]) }
     if W' == W: break
     W := W'
  if not converged: throw PlanContradiction(keys oscillating)   # UI: "Configuração contraditória", Apply desabilitado
  activeDrift   := { k -> obs(k) | S.compiled != null, k ∈ S.compiled, k ∉ W,
                                   !same(obs(k), S.compiled[k]), R.rule(k).kind != INACTIVE }
  inactiveDrift := { k | idem, mas R.rule(k).kind == INACTIVE }          # relatório, não fila
  deferred      := { k | k ∈ S.saved ∩ WRITABLE, R.rule(k).kind == INACTIVE, !same(saved, observed) }  # latentes
  external      := { k | k ∉ S.saved, k ∈ observed não vazio }           # ownership Terminal
  return Plan(W, activeDrift, inactiveDrift, deferred, external, unknownBaseline = S.compiled == null)
needsCompile = !W.isEmpty() || !activeDrift.isEmpty()
listed       = W ∪ activeDrift   (o que a UI conta como "N ajustes aguardando aplicação")
fingerprint  = sha1(sorted(W) + "|" + sorted(activeDrift))
```

Regras:

1. **Nenhum draft** entra em `S`. Drafts são descartados (`AppModel.clearDrafts`) antes de abrir a confirmação.
2. **Revalidação no Apply**: `LensSheet.confirmRestart` guarda `plan.fingerprint` ao abrir. No toque em "Reiniciar", recalcula `plan(confirmedSnapshot())` após `SystemPropertyReader.refresh()` (thread de IO, já usado por `refreshObserved`). Se o fingerprint mudou, não reinicia: fecha o diálogo, mostra `apply_list_changed` e reabre com a nova lista. `SfLiveController.restartConfirmed(plan.writes)` recebe **só writes**; a deriva ativa é recompilada pelo próprio restart (`restartInputs` captura todas as propriedades observadas).
3. **Nenhuma escrita de neutralidade** é emitida no catálogo atual (prova §5). Omissão de chave inativa é segura porque cada regra INACTIVE corresponde a um ramo onde o leitor ignora o valor.
4. **Retorno de contexto**: quando a regra volta a ACTIVE/NEEDS_RESTART no alvo, o valor latente entra em `W` se diferir do observado. A UI mostra o aviso `state_reactivated` ("O valor guardado voltará a valer ao aplicar") e nunca reinicia sozinha.
5. **Ownership externo**: chave sem `saved` e com `observed` não vazio é EXTERNAL. Nunca é escrita, nunca é zerada. Conflito (`ConfigRepository.conflict`) continua bloqueando save e sendo resolvido por `InfoSheets.conflicts` (usar Terminal / manter app). Deriva compiled≠observed de chave externa ativa entra em `activeDrift` (precisa compilar para valer) e é rotulada "alterado fora do app".
6. **Sem baseline** (`compiled == null`): `activeDrift` vazio; `unknownBaseline=true`; UI mostra "Estado compilado desconhecido" (`state_unknown_compiled`) e permite Apply explícito de verificação (comportamento atual de `compositorUnverified`).
7. **Atestação separada**: `CompositorValues.verify/recordCompiled/adoptWritten` permanecem inalterados. `PublicationPlan` não decide CONFIRMED.

## 5. Neutralidade por rota (prova de leitor)

Toda regra que retorna INACTIVE para uma chave `sf.*` precisa de uma linha `IGNORED_BY_READER` com prova. A prova automatizada é **identidade de GLSL**: `sfhook/tools/inactive_identity_check.sh` (tarefa T04) compila com `refract_check` o mesmo dump duas vezes, variando apenas a chave inativa entre o default e um extremo do domínio, no contexto que a torna inativa, e exige `cmp` byte a byte dos `mod_*.frag`. Se algum par diferir, a chave vira `NEEDS_NEUTRAL` e a tarefa para (decisão Opus), nunca é "corrigida" no shader.

| Regra INACTIVE (Applicability) | Chaves | Contexto do teste | Prova esperada | Política |
|---|---|---|---|---|
| `!lens` → NEEDS_LENS | todas `sf.*` | `sfrefract=0` | Biblioteca não carregada/sem tag; nenhum programa do módulo | IGNORED_BY_READER (prova estrutural, sem GLSL) |
| CLEAR_ONLY com material Fosco | `sf.hc*`, `sf.kc*` | `huclear=0,kgclear=0`; varia cada chave | `oulg_clear_on` falso, campo Clear não emitido (refract.h:1091) | IGNORED_BY_READER |
| FROSTED_ONLY com material Transparente | `sf.core, edgeblur, hubodylens, kgcore, kgedgeblur, kgcorecap, kgblurexp, kgbodylens, kgsat, kgtone, kgflat, kglevel, kgcool, kgflatcap, kgcoredim` (`sf.ramp, kgramp, kggradient, kgband` são ACTIVE sob Transparente, ver nota) | `huclear=1,kgclear=1` | Saída Clear sobrescreve shift/blur/material. **Teste obrigatório**; se o GLSL de `kgsat/kgtone/kgflat/kglevel/kgcool/kgcoredim` variar, parar T04 e reportar | IGNORED_BY_READER (a confirmar por T04) |
| `sf.hcamp`/`sf.kcamp` com menisco>0 | hcamp, kcamp | `huclear=1,hcfold=2` | Campo menisco não recebe `amp` (refract.h:1096-1100) | IGNORED_BY_READER |
| `sf.kclevel` com `kcveil=0` | kclevel | `kgclear=1,kcveil=0` | véu multiplica nível | IGNORED_BY_READER (T04 confirma) |
| `sf.hchlneutral` com `hcadapt=0` | hchlneutral | `huclear=1,hcadapt=0` | `oulg_hu_hl_on` falso (refract.h:844,1209) | IGNORED_BY_READER |
| `sf.hcneutral` sem bloco de material | hcneutral | `huclear=1,hcsat=1,hcscrim=0,hcscrimmax=0,hclift=0` | `legacy_material_on ? neutral : 0.0` (refract.h:1207) | IGNORED_BY_READER |
| `sf.hubloom` em Clear K1–3 com adapt>0 | hubloom | `huclear=1,hcadapt=1,hckernel=1/2/3` | disco A inline não lê bloom | IGNORED_BY_READER |
| `sf.shadecap/shadelift` sem `shadetone=1` | shadecap, shadelift | perfil 2 só recebe tag com shadetone=1 (Java) | estrutural: sem cartão perfil 2 | IGNORED_BY_READER (estrutural) |

Classes de prova de leitor (T04b, decisão P-NEUTRAL; `sfhook/tools/inactive_identity_check.sh` verifica por lista explícita `chave:classe` e por padrão de linha, e falha se uma divergência sair da lista ou do padrão, ou se uma entrada da lista deixar de divergir):

- Idêntica em GLSL (prova por `cmp`): `sf.kgtone`, `sf.kgflat`, `sf.kglevel`, `sf.kgcoredim` sob Transparente; todas as `hc*`/`kc*` sob Fosco; `hcamp`/`kcamp`, `hchlneutral`, `hcneutral` nos contextos acima.
- `OVERWRITTEN`: só mudam operandos de `oulg_br`/`oulg_bw` ou o `s` de `oulg_shift_base`/`oulg_field_base`, sobrescritos por `oulg_clearN` (e linhas de `oulg_material_base`, inalcançável): `sf.core`, `sf.edgeblur`, `sf.hubodylens`, `sf.kgcore`, `sf.kgedgeblur`, `sf.kgcorecap`, `sf.kgblurexp`, `sf.kgbodylens`.
- `UNREACHABLE`: toda linha alterada está em `oulg_material_base` ou `oulg_disc0` (só chamadas com `oulg_cl < 0.5`): `sf.kgsat`, `sf.kgcool`, `sf.kgflatcap`, `sf.hubloom` (kernel 1-3 com adaptativo).
- `ZERO_FACTOR`: a linha multiplica pelo literal `0.000 *`: `sf.kclevel` com `kcveil=0`.
- ACTIVE sob Transparente (não são regra INACTIVE; o script exige que mudem o GLSL): `sf.ramp`, `sf.kgramp`, `sf.kggradient`, `sf.kgband`.

Chaves LIVE (não `sf.`) inativas são lidas por `Tuning` mas os ramos de desenho que as usam só executam no modo/rota da regra (espelhado de `docs/CONTROL_MATRIX.md`). Nenhuma exige escrita de neutralidade: o valor salvo é entregue ao SystemUI como hoje e ignorado no ramo inativo. Isso não muda: o guard impede **edição**, não a entrega.

Identidades por tipo, para a UI e para futuros casos `NEEDS_NEUTRAL` (nenhum hoje): multiplicadores `1` (`hublur, kgblur, hurefract, kgrefract, hcsat, kcsat`), aditivos `0` (`hclift, kclift, hchlneutral, hubloom, hcbody, hcbody2, kcveil`), automático `-1` (`kgblurradius, semradiuslens, semradius, kglens, hufadecut, tint, semalpha, panelblurcap`), compartilhado (`kgblurradius` em Automático segue outra chave). **Nunca** `0` universal.

## 6. Estado efetivo exibido

`config/state/EffectiveState.of(ControlState st, PublicationPlan plan, String key, Surface s)` → `Effective{kind, displayValue, latentValue, reason, notes}`:

```text
a := Applicability(confirmed).on(key, s)
if a.kind == READ_ONLY            -> READ_ONLY,  display = confirmed(key)
if a.kind == OFF                  -> OFF,        display = "Desligado", latent = saved[key] if present
if a.kind == INACTIVE             -> INACTIVE,   display = "Inativo", latent = saved[key] ?: null, reason = a.reason
if key ∈ plan.writes              -> PENDING_COMPILE, display = saved, note "Aplicar para valer"
if key ∈ plan.activeDrift         -> PENDING_COMPILE, display = observed, note "Alterado fora do app; aplicar para valer" if key ∈ external
if key ∈ plan.external            -> EXTERNAL,   display = observed
if sf key && plan.unknownBaseline -> UNKNOWN,    display = confirmed, note "Estado compilado desconhecido"
else                              -> ACTIVE,     display = confirmed (draft só no texto durante arrasto)
```

## 7. Guard único de ação

`config/state/EditGuard`:

```java
public enum Action { EDIT, RESET_ONE, RESET_GROUP, FAVORITE_ADD, OPEN_EDITOR, BULK_IMPORT, PRESET_APPLY, MATERIAL_SELECT }
public static Decision check(Action a, String key, Controls.Surface s, Applicability confirmedRules)
```

| Ação | ACTIVE/NEEDS_RESTART | INACTIVE | OFF | READ_ONLY |
|---|---|---|---|---|
| EDIT, RESET_ONE | permite | recusa (motivo) | recusa | recusa |
| RESET_GROUP | permite para as chaves usáveis; inativas mantêm latente | pula | pula | pula |
| FAVORITE_ADD | permite se destino ∈ favoritos permitidos | permite (favorito mostra Inativo) | permite | recusa |
| OPEN_EDITOR | editor | sheet somente leitura com latente e motivo | idem | idem |
| BULK_IMPORT / PRESET_APPLY | permite (valor latente preservado, revisado em diff) | permite (marcado "fica guardado, inativo agora") | permite | ignora |
| MATERIAL_SELECT | sempre (uma transação, um undo) | — | — | — |

Pontos de chamada obrigatórios: `ControlRow.commit/resetToDefault`, `ControlSheet.commit`, `AppModel.saveControl`, `AppModel.reset` (novo `resetGroup`), favoritos (`ControlSheet` estrela), `SearchSheet` abertura, `PresetsSheet.PresetReview` (marca latentes), `AppModel.selectMaterial`.

## 8. Matriz de transições (testes obrigatórios em `EffectiveStateTest`/`PublicationPlanTest`)

Notação: S=saved, O=observed, C=compiled. Material Clear = `huclear=kgclear=1`, lente ligada (`sfrefract=1` observado).

| # | Caso | Entrada | W (writes) | activeDrift | inactiveDrift | UI de `sf.hubloom` |
|---|---|---|---|---|---|---|
| 1 | Clear K1, adapt=1, S.hubloom=1, O=0, C=0 | | {} | {} | {} | Inativo; latente 1,00 nos detalhes |
| 2 | idem, O.hubloom=1 (publicado antes/Terminal), C=1 | | {} | {} | {} | Inativo (ignorado pelo kernel); sem pendência |
| 3 | idem, O=1, C=0 | | {} | {} | {hubloom} | Inativo; relatório técnico "deriva sem efeito" |
| 4 | Retorno: S.hckernel=0 salvo (O=1) | | {hckernel:0, hubloom:1} | — | — | "Volta a valer ao aplicar" |
| 5 | Retorno: S.hcadapt=0 (O=1) | | {hcadapt:0, hubloom:1}; `hchlneutral` sai de W se estava | — | — | idem |
| 6 | Draft de material (arrasto/seleção não salva) | drafts.huclear=0 | igual ao caso sem draft | — | — | regras inalteradas |
| 7 | Clear→Frosted salvo | S.huclear=0,S.kgclear=0 | {huclear:0,kgclear:0} + Frosted-only salvos diferentes; nenhum `hc*/kc*` | — | — | `hc*` inativos com latente |
| 8 | Frosted→Clear com `core` salvo | S.huclear=1 | `core` fora de W | — | — | `core` inativo |
| 9 | Externo: S sem `sf.hcsat`, O=1.2, C=1.05 | | {} | {hcsat:1.2} (ativo) | — | "Alterado fora do app", Aplicar |
| 10 | Sem baseline | C=null | W normal | {} | {} | "Estado compilado desconhecido" |
| 11 | Pai/filho rápido: S.hcfold 2→0→2 sem Apply | | conforme valor final; `hcamp` só em W se fold final =0 | | | |
| 12 | OFF: surface.headsup.enabled=0 | | W inalterado (compositor não depende da flag) | | | Desligado |
| 13 | NEEDS_RESTART comum: S.hclift=.4, O=.3 | | {hclift:.4} | | | Pendente |
| 14 | Concorrência: diálogo aberto, Terminal muda `sf.hcsat` | fingerprint muda | não reinicia; reabre lista | | | |
| 15 | Rollback: restart falha | `clearAttempt` (existente) | W recalculado igual | | | Pendente mantém |
| 16 | Contradição artificial (teste com regra fake que alterna) | | `PlanContradiction` | | | Apply desabilitado |
| 17 | Import de preset com `hubloom=1` em Clear K1 | BULK_IMPORT | salvo; não em W | | | Inativo, latente 1,00 |
| 18 | Undo após seleção de material | `repo.undo()` | volta W anterior | | | |

Casos fora do HU obrigatórios (mesma classe de teste): `sf.kcamp` com `kcfold>0`; `sf.kclevel` com `kcveil=0`; `sf.shadecap` com `shadetone=3`; `kgblurradius` em lente com `kgblurpath=0` (LIVE; guard, sem publicação); `qs.tilelightoff` com `qs.hideoffdiscs=1` (LIVE); Live/NowBar/toasts não têm chaves `sf.` próprias (perfil HU/KG), verificados pelo caso 1 com tag da Live card (`useLensRadius`).

## 9. Migração

Nenhuma chave renomeada. Nenhum valor reescrito ao abrir o app. `undo` continua um snapshot por transação. Export/import continuam carregando latentes. Ver `MIGRATION_AND_ROLLBACK.md`.
