# Revisão independente do #211 e matriz de equivalência (R5, 18/11)

Revisor: Claude (sessão de implementação R2–R5). Base `main` `cb0743075da41488d75a301b5ed38524e1ead707` (sem mudança desde a integração). Head revisado do #211: `ace226ca1bb8cced6a9f0164c7bffaa8d4bd5288`; correções R5 vêm depois, em commits separados.

## Método

Para cada PR de origem, calculei o diff da sua base correta até o head (o #207 sobre o #206; os demais sobre a `main`) e comparei com a árvore do #211 em dois sentidos:
- toda linha **adicionada** pela origem deve existir no arquivo integrado;
- nenhuma linha **removida** pela origem pode continuar no arquivo integrado.

Cada divergência foi inspecionada à mão e classificada. Os arquivos gerados foram regenerados pelos geradores oficiais, sem nenhuma diferença. Também executei:
- `check.sh`: I1–I4, `image_check`, `probe_check`, `alpha_precision_check` e `hl_neutral_check`, com compilação e link no glslang;
- `live_check.sh`;
- `test-distribution.py`;
- a suíte Java completa: **896 testes, 0 falhas**.

Os PRs não têm threads de revisão; os comentários são apenas avisos de cota do Codex, sem achados.

## Matriz

| PR | Head (= SHA do laudo) | Base | Divergências no #211 | Decisão | Evidência / risco |
|---|---|---|---|---|---|
| #202 | `3befe5c` | main | nenhuma | equivalente | `QuickSettingsEdgeLightTest`, `QuickSettingsGlassTest` |
| #203 | `4c87caa` | main | a linha `toString` do `Tuning` combina `hublurpath` e `hufadecut` (#205); o gerador conta 208 entradas com os títulos combinados; bundle v0.36 | equivalente (conflito resolvido) | `HeadsUpBlurPathTest`; padrão Samsung inalterado; `image_check` com o parser corrigido |
| #204 | `6964252` | main | `sf.hubloom` só fica inativo com `hcadapt > 0` | substituído com justificativa | Com o adaptativo desligado, `oulg_body_hu` não é emitido e o corpo é `oulg_disc0`, que lê `hubloom`: a regra original bloqueava um controle ativo. Teste `GlassMaterialApplicabilityTest` |
| #205 | `9cba09f` | main | `toString` e gerador combinados (ver #203) | equivalente | `CardExitTest`; padrão −1 |
| #206 | `5a04137` | main | `version.prop` agora v0.36/37 | equivalente (versão consolidada) | `probe_check` sobre os dumps DZIF e R3 |
| #207 | `a1e51c1` | #206 | a linha `mediump vec4 acc` continua no **disco A aninhado** e nos discos simples, como deve (mesma precisão nos dois lados); o K0 adaptativo está em `highp` | equivalente | `alpha_precision_check` rejeita as declarações antigas; golden I3 |
| #208 | `70602f2` | main | `oulg_hu_material_on` = legado **ou** (adaptativo **e** H > 0); o bloco emitido só por H recebe neutral 0 | conflito resolvido, **correção** | No #208 original, ligar só H acordava o legado (0,75), que antes não era emitido. `hl_neutral_check` tem caso dedicado |
| #210 | `a16f714` | main | nenhuma (`useLensRadius`/`lensPx` têm uma única definição, compartilhada com o #203) | equivalente | `LiveCardParityTest` |

Branches antigas com commits fora do #211 (`fix/hu-clear-nested-kernel`, `fix/hu-icon-first-frame`, `docs/*`, `fix/live-card-neutral-rim`, `fix/status-icon-tuning-publication`, `fix/cancel-stale-notification-icon-work`, `fix/systemui-p3-state-registry-20261116`, `ccr-6437b91e-22w7c1`): pertencem aos PRs #191–#201, já mesclados por squash. As diferenças em relação à `main` vêm de PRs posteriores que mexeram nos mesmos arquivos. Fora do escopo R5; nada a integrar.

## Preservação

Nenhuma branch foi apagada nem reescrita. Os heads acima continuam acessíveis pelas branches de origem e pelos PRs fechados. Os PRs #202–#208 e #210 são fechados como substituídos pelo #211, com comentário cruzado. A issue #209 permanece aberta.
