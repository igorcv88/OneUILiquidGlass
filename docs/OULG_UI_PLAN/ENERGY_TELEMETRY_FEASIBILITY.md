# Diagnóstico de custo por superfície — viabilidade (R6)

Status: **estudo; implementação fora deste PR** (decisão D-TEL em `DECISIONS_AND_OPEN_QUESTIONS.md`).

## 1. O que existe

`tools/collect-glass-perf.sh`: sessão curta via adb (4 estágios × 15 s, trace de shelf), PERF/CAPTURE_STATS, `dumpsys gfxinfo`, CPUINFO, restaura props. Não roda 24 h, não atribui energia. `diagnostics/Perf`/`PerfStats` (opt-in `perf`) mede tempo de hooks; `FrameAccount`, `LiveRenderProbe`, `LiveMaterialTrace` são sondas pontuais. `docs/architecture.md` registra custo de GPU/bateria como não medido.

## 2. Matriz de capacidades (S25 Ultra, One UI 9) — o que se sabe e o que falta provar

| Fonte | Acesso de app sem root | Atribuição por superfície | Situação |
|---|---|---|---|
| `BatteryManager.BATTERY_PROPERTY_CURRENT_NOW/ENERGY_COUNTER` | Sim | Não (aparelho inteiro) | Resolução/frequência no S25 não atestadas |
| Power Stats HAL / ODPM rails | Não para app comum; Perfetto com shell/root | Por rail (GPU, CPU…), nunca por janela | Suporte no S25 **não atestado**; documentação do Power Profiler cita Pixel 6+ |
| Perfetto (`android.power`, FrameTimeline, gpu counters) | adb/shell | Frames por layer; energia não | Custo de trace alto para 24 h |
| `gfxinfo`/FrameTimeline | adb | Por processo SystemUI | Jank, não energia |
| Instrumentação no próprio módulo (SystemUI) | Sim (já roda lá) | Tempo visível/ativações por superfície; CPU do módulo por `SystemClock.currentThreadTimeMillis` amostrado | Viável; overhead a medir |

Conclusão: **energia por superfície (mWh, % de bateria) é indisponível** sem método calibrado. Não será exibida.

## 3. MVP factual (proposto para fase posterior, issue separada)

Métricas atribuíveis, rotuladas como custo/uso, nunca energia:

- Tempo visível por superfície (HU, KG, central, QS, Live card, chip, Now Bar, toast) a partir de eventos já existentes de ownership (attach/release de `LiveGlass.State`, `HeadsUpHooks.State.release`, `QuickSettingsHooks` refresh). Coexistência: tempo contado por superfície; um contador "tempo com ≥1 superfície" evita somar sobreposição como total.
- Ativações (contagem de bind com vidro).
- CPU do módulo amostrada: 1 em cada 32 desenhos, `currentThreadTimeMillis` antes/depois do trecho do módulo; extrapolação proibida, mostra ms por minuto ativo e total amostrado.
- Jank só quando atribuível ao processo e período da sessão (FrameTimeline não acessível sem shell: fica fora do MVP).

Sessão: iniciada explicitamente em Configurações › Energia › Diagnóstico de custo; default desligada; duração 1 h/6 h/24 h, parada manual, para sozinha no limite, em reboot/morte do SystemUI (não retoma) e se overhead estimado > 1 % do tempo de CPU do módulo. Agregação em memória no SystemUI, flush a cada 15 min para `engine_status` via broadcast agregado (sem conteúdo de notificação, sem pacote, sem texto). TTL 7 dias; botão Limpar; exportar texto redigido. Sem wakelock, sem alarmes, sem trace verbose.

Ranking: por "tempo ativo" e por "CPU do módulo por minuto ativo", separadamente; sem ranking quando < 30 min de dados ou < 20 ativações por superfície; cada linha com cobertura e versão.

## 4. Energia calibrada (go/no-go futuro)

Só se: (1) ODPM/Power Stats do S25 provar rails acessíveis; (2) protocolo A/B pareado (mesmo conteúdo, brilho fixo, 120 Hz fixo, rede, temperatura), ordem balanceada, ≥10 repetições por condição, controle negativo (superfície desligada vs ligada sem uso), estabilização 2 min, intervalos de confiança; (3) sonda com overhead medido e subtraído. Sem isso: "energia por superfície indisponível".

## 5. Decisão

GO para documentação e issue; **NO-GO** para implementação neste PR (não é necessária para UI/energia, exige instrumentação em 7 superfícies e validação física de overhead). A tela Energia reserva a seção "Diagnóstico de custo" como texto informativo "Em estudo" **sem botão** (não anunciar função inexistente) — ou omitida; decisão D-TEL: **omitida**.
