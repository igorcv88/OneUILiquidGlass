# Política de energia (R6)

Status: **planejado**. Baseline `4f33114`.

## 1. Auditoria das rotas (todas as condicionais de energia encontradas)

Busca em `app/`, `sfhook/`, `tools/` por `isPowerSaveMode`, `ACTION_POWER_SAVE_MODE_CHANGED`, `disable_window_blurs`, `isCrossWindowBlurEnabled`, `BatteryManager`, `ACTION_BATTERY_*`:

| Local | O que faz | Classificação | Decisão |
|---|---|---|---|
| `hooks/ongoing/LiveSurfaceHooks.java:315-329` `CompositorBlur.enabled` → `allowed(powerSave, disableWindowBlurs)` | Battery Saver desliga vidro de Live Updates, chips, Now Bar e toasts (volta ao nativo opaco) | **Política voluntária** (não capacidade): no S25/DZIF o blur Samsung funciona com Saver (relato físico) | Remover `powerSave` do gate. Comportamento antigo vira opção opt-in "Seguir Economia de bateria" (default desligado). |
| `LiveSurfaceHooks.java:263-271` receiver `ACTION_POWER_SAVE_MODE_CHANGED` → `glass.syncAll()` | Reavalia o gate acima | Infra da política | Remover (o novo `EnergyGovernor` assume o evento). |
| `LiveSurfaceHooks.java:321` `Settings.Global disable_window_blurs == "1"` | Usuário/desenvolvedor desligou blur de janelas | **Configuração explícita do sistema** | Manter. |
| `LiveSurfaceHooks.java:255-261` `addCrossWindowBlurEnabledListener` (`used=false`) | Só log + `syncAll` | Diagnóstico; DZIF reporta false com blur funcional | Manter sem uso como gate. |
| `HeadsUpHooks.java:2545-2556` `blurEnabled = isCrossWindowBlurEnabled()` → `Backdrop.choose(blurEnabled, samsungBlur, …)` | Escolhe backend; em DZIF `false` sempre e a rota Samsung é usada | Capacidade/escolha de backend (AOSP inclui Saver nesse flag) | Não alterar: em DZIF o flag já é falso com Saver desligado, logo o Saver não muda a rota; mexer aqui arrisca regressão em outros aparelhos. |
| `LiveSurfaceHooks.glassAllowed` (Now Bar: display ON/VR) | Doze/AOD/tela desligada mantêm nativo | Segurança/lifecycle | Manter. |
| `LiveGlass.canShow` (`regionBroken`, attach) | Falha real de região | Fallback seguro | Manter. |
| `diagnostics/Probe.java:53-60` `BLUR_ENV` | Só log | Diagnóstico | Manter. |
| `tools/collect-device.sh:15` | Coleta | Diagnóstico | Manter. |
| `LiveSurfaceHooksTest.java:174-177` | Testa `allowed(true,null) == false` | Teste da política antiga | Atualizar para a nova assinatura (T15). |

Nenhum script do módulo (`sfhook/module/*.sh`, `oulg-live.sh`) consulta energia. O compositor não tem gate de energia: a lente deixa de agir quando o módulo para de produzir tags/regiões (caminho já usado ao desligar uma superfície).

## 2. Princípio

Padrão: **nenhuma redução automática**. O vidro continua igual com Economia de bateria. Redução só por opção explícita do usuário. "Reduzir" não é oferecido (não há implementação definida que reduza custo de forma verificável); a única ação oferecida é **suspender o vidro nas superfícies escolhidas e restaurar o visual nativo da Samsung**, exatamente o caminho já testado de desligar uma superfície.

## 3. Preferências (novas chaves LIVE do registro, grupo "Energia")

| Chave | Tipo | Domínio | Default | Significado |
|---|---|---|---|---|
| `energymode` | INT (escolha) | 0,1 | 0 | 0 = Nunca suspender; 1 = Suspender abaixo do limite de bateria |
| `energythreshold` | INT | 5–50 | 20 | Limite em %; suspende quando nível **≤** limite |
| `energyhysteresis` | INT | 2–15 | 5 | Retoma quando nível **≥** limite + histerese |
| `energycharging` | BOOLEAN | 0/1 | 1 | 1 = manter o vidro enquanto carrega |
| `energysurfaces` | INT (máscara) | 1–127 | 127 | bits: 1 pop-up, 2 bloqueio, 4 central, 8 Central de controle, 16 Live Updates, 32 Now Bar, 64 toasts |
| `energysaver` | BOOLEAN | 0/1 | 0 | 1 = também suspender enquanto a Economia de bateria estiver ligada (comportamento antigo, opt-in) |

São chaves do registro (`docs/PROPERTY_CATALOG_2026-10-26.md` + `tools/generate-settings.py`, assert 208→214), entregues pelo `ConfigProvider` como qualquer chave LIVE, exportadas/importadas e com undo. Não são `sf.*`; não reiniciam nada.

## 4. Máquina de estados (`glass/EnergyPolicy`, puro Java, testável)

```java
public final class EnergyPolicy {
  public enum Reason { NONE, SAVER, LOW_BATTERY }
  public static final class Input { int level; /* 0..100, -1 desconhecido */ boolean present, charging, saver; }
  public static final class Config { int mode, threshold, hysteresis, surfaces; boolean keepWhileCharging, followSaver; }
  public static final class Decision { final Reason reason; final int suspendedMask; }
  /** previous: decisão anterior (memória da histerese). Pura; sem relógio. */
  public static Decision decide(Config c, Input in, Decision previous)
}
```

Regras, em ordem de precedência:

1. Vidro mestre desligado ou `c.surfaces == 0` → `NONE`.
2. `c.followSaver && in.saver` → `SAVER`, máscara `c.surfaces` (carregando não anula: é pedido explícito do usuário).
3. `c.mode == 0` → `NONE`.
4. Bateria desconhecida (`!in.present || in.level < 0`) → `NONE` (nunca suspender por dado ausente).
5. `in.charging && c.keepWhileCharging` → `NONE`.
6. Se `previous.reason == LOW_BATTERY`: continua `LOW_BATTERY` enquanto `level < threshold + hysteresis`; retoma em `level ≥ threshold + hysteresis`.
7. Senão: `LOW_BATTERY` se `level ≤ threshold`, senão `NONE`.

Process death/reboot do SystemUI: `previous = NONE`; avalia com o intent sticky. Na faixa `(threshold, threshold+hysteresis)` após reinício o vidro fica ativo (documentado; sem persistência extra). Mudança de preferência: reavalia imediatamente com a nova config; desligar a política retoma na hora.

Tabela de transições (testes `EnergyPolicyTest`, T=20, H=5):

| Anterior | Nível | Carregando | Saver | follow | Resultado |
|---|---|---|---|---|---|
| NONE | 21 | não | não | 0 | NONE |
| NONE | 20 | não | não | 0 | LOW_BATTERY (igualdade suspende) |
| LOW | 24 | não | não | 0 | LOW (histerese) |
| LOW | 25 | não | não | 0 | NONE |
| LOW | 10 | sim (keep=1) | não | 0 | NONE |
| LOW | 10 | sim (keep=0) | não | 0 | LOW |
| NONE | 80 | não | sim | 0 | NONE (Saver não muda o vidro por padrão) |
| NONE | 80 | sim | sim | 1 | SAVER |
| NONE | -1 | não | não | 0 | NONE |
| NONE | 10 | não | não | 0 com mode=0 | NONE |

## 5. Aplicação no SystemUI (`config/EnergyGovernor`)

- Criado uma vez em `ConfigClient.start(context, onEnabled)` (já único por processo). Registra `Intent.ACTION_BATTERY_CHANGED` (sticky; a resposta do registro é o estado inicial) e `PowerManager.ACTION_POWER_SAVE_MODE_CHANGED`, `RECEIVER_NOT_EXPORTED`, handler do worker `oulg-config`. Sem polling, sem alarmes, sem wakelock.
- No evento: extrai `level*100/scale`, `present`, `plugged != 0`, `pm.isPowerSaveMode()`. Se a tupla não mudou, retorna. Calcula `decide`; se `suspendedMask` mudou: publica `ConfigClient.energyMask` (volatile), `revision++`, despacha `onEnabled(current enabled)` no main (mesmo caminho de uma mudança de config: `HeadsUpHooks.resolveConfig` libera estados, `quickSettings.refresh()`, `ongoing.refresh()` → `LiveGlass.syncAll()`), e envia o relatório (§6). Log `ENERGY_STATE reason=… mask=… level=… charging=…` só na transição.
- `ConfigClient.flag(key)`: se `key` é a flag de uma superfície cujo bit está em `energyMask`, retorna `false`. Mapa bit→flag: 1 `surface.headsup.enabled`, 2 `surface.keyguard.enabled`, 4 `surface.shade.enabled`, 8 `ProductFlags.QUICK_SETTINGS`, 16 `ProductFlags.LIVE_ACTIVITY`, 32 `ProductFlags.NOW_BAR`, 64 `ProductFlags.TOAST`. Nada é escrito nas preferências; a flag salva do usuário fica intacta.
- A configuração da política é lida de `current` (as chaves `energy*` chegam pelo snapshot). Mudança dessas chaves no `refresh` chama `governor.reevaluate()`.
- Restauração: é a mesma de desligar a superfície (região liberada, fundo nativo restaurado, tags não produzidas). Não reinjeta lente, não reinicia, não toca CPU/brilho/propriedades do sistema. #205 preservado: a saída usa os caminhos existentes de release.
- Em `LiveSurfaceHooks.CompositorBlur.enabled`: `return allowed(disabled)`; `static boolean allowed(String disableWindowBlurs) { return !"1".equals(disableWindowBlurs); }`; remover o receiver de Saver.

## 6. Estado visível (sem stale)

SystemUI → app: broadcast explícito `io.github.igorcv88.oneuiliquidglass.ENERGY_STATE` para `ConfigReadReceiver` (já protegido por `STATUS_BAR`), extras `reason` (NONE|SAVER|LOW_BATTERY), `mask` (int), `level` (int), `charging` (bool), `at` (ms). Enviado na transição e uma vez no início do governor. App grava em `engine_status`: `energy.reason`, `energy.mask`, `energy.level`, `energy.charging`, `energy.at`.

Settings › Energia mostra: "Agora: vidro ativo" / "Agora: vidro suspenso em Pop-up, Bloqueio… — bateria 19 % (limite 20 %)" / "Agora: suspenso pela Economia de bateria", com "Relatado às HH:MM". Sem relato (`energy.at == 0`) ou SystemUI sem resposta (`AppModel.sync() == NOT_DETECTED`): "Estado do sistema desconhecido". A Home mostra um aviso compacto apenas quando `reason != NONE`.

## 7. Rollout e reversão

Default (`energymode=0`, `energysaver=0`) = vidro nunca suspenso, inclusive com Saver: única mudança visível para quem não mexe é Live/Now Bar/toasts continuarem com vidro sob Saver. Reversão para o comportamento antigo: ligar "Seguir Economia de bateria". Rollback de código: reverter o commit da fase D. App e hook vêm do mesmo APK, então registro, provider e `ConfigClient` são sempre da mesma versão; reinstalar o APK anterior simplesmente deixa de entregar as chaves `energy*` (valores salvos ficam inertes, não apagados).

## 8. Testes

Unit: `EnergyPolicyTest` (tabela §4 + mudança de config + mask 0), `EnergyGovernorTest` (Robolectric: intent sticky inicial, evento repetido sem mudança não despacha, transição despacha uma vez, `ConfigClient.flag` mascara só bits selecionados, flag salva intacta), `LiveSurfaceHooksTest.allowedIgnoresSaver`, `ConfigReadReceiverEnergyTest`. Físico (S25): Saver on/off com `energysaver=0` sem mudança de aparência; `energymode=1` com limite acima do nível atual (ex.: 90 %) para forçar suspensão sem descarregar; carregar com keep=1/0; alternância rápida do Saver; bloquear/desbloquear; QS e Lock; desligar a política e ver retorno; nenhum restart.
