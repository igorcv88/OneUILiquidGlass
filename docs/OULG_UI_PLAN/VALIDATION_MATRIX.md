# Matriz de validação (R6)

## 1. Automatizada (CI `controls.yml` + local)

| Área | Prova | Tarefa |
|---|---|---|
| Cobertura de chaves | `build_inventory.py --check`; `ProductLayoutTest` 208/214 exata; `ControlModelTest`; `SettingsRegistryTest` | T07, T15, T19 |
| Sem drafts nas regras | `EditGuardTest.noDraftsInRules`; `ConfirmedSnapshotTest` | T01, T05 |
| Publicação | `PublicationPlanTest` (18 casos + fora do HU) | T02 |
| Estado exibido | `EffectiveStateTest`, `ControlRowStateTest` | T03, T11 |
| Neutralidade | `sfhook/tools/inactive_identity_check.sh` (identidade GLSL byte a byte) | T04 |
| Guard | `EditGuardTest` (ações × estados), `AppModelResetGroupTest` | T05, T10 |
| Apply | `LensSheetPlanTest`/`confirmPlan` STALE/OK/CONTRADICTION; `SfLiveFlowTest`, `CompositorValuesTest` intactos | T06 |
| Layout | `ProductLayoutTest`, `RoutesTest`, `SurfacesLayoutTest`, `TechnicalLayoutTest`, `SearchIndexTest`, `FavoritesFilterTest` | T07–T14 |
| Copy | `LocalizationParityTest`; diffs dos geradores | T08 |
| Formato | `ValueFormatPercentTest` | T11 |
| Energia | `EnergyPolicyTest`, `EnergyGovernorTest`, `LiveSurfaceHooksTest.allowedIgnoresSaver`, `ConfigReadReceiverEnergyTest`, `EnergySectionTextTest` | T16–T18 |
| Contraste | `ThemeContrastTest` (≥4,5:1) | T11 |
| Óptica intacta | `check.sh` (property_check, material_controls_check, GLSL), `live_check.sh`, `test-distribution.py`; `git diff origin/main -- sfhook/*.c sfhook/*.h` vazio | todas |
| Build | `testDebugUnitTest lintDebug assembleDebug`; APK assinado 24 h | T20 |

## 2. Revisão independente (Opus, após T20)

Diff completo vs. `origin/main`; checagem das regras invioláveis; leitura dos 18 casos de publicação; amostra de telas por código (sem prévia visual); conferir que nenhuma chave mudou default/domínio (`git diff -- docs/PROPERTY_CATALOG_2026-10-26.md` só adiciona 6 linhas).

## 3. Físico (usuário, S25 Ultra, APK do PR)

1. Instalar por cima; abrir app: nenhuma pendência nova inesperada; nenhum restart.
2. Pop-up Transparente: principais = Desfoque, Refração, Clareamento, Saturação, Menisco. Clareamento idêntico ao R5 (mesmo valor, mesma aparência).
3. Desfoque 100 %→115 %: Aplicar; borda e centro crescem proporcionalmente; linha nominal correta.
4. `hckernel` 1 (Técnico): Contenção de brilho mostra "Inativo" com motivo; Aplicar não lista `hubloom`.
5. Voltar `hckernel` 0: aviso "volta a valer ao aplicar"; Aplicar lista `hubloom`.
6. Terminal: `setprop debug.oulg.sf.hcsat 1.2` sem salvar no app → "Alterado fora do app"; Aplicar mostra origem externa.
7. Abrir Aplicar, mudar `hcsat` pelo Terminal, tocar Reiniciar → lista reabre atualizada, sem restart.
8. Economia de bateria ligada (política padrão): Live Updates/Now Bar/toasts/pop-up continuam com vidro.
9. Energia: modo Abaixo do limite, limite 90 % (acima do nível atual) → vidro suspenso nas superfícies marcadas, Home mostra aviso, Energia mostra "suspenso … bateria N %". Conectar carregador com "Manter carregando" → volta. Desligar a política → volta. Nenhum restart.
10. "Seguir a Economia de bateria" ligado + Saver → suspenso; desligar Saver → volta.
11. Central de controle, sliders R5, animação #205 do pop-up: inalterados.
12. Busca "brilho": encontra Contenção de brilho com caminho; técnico só com o toggle.
13. Favorito técnico antigo: aviso único; item continua em Técnico.
