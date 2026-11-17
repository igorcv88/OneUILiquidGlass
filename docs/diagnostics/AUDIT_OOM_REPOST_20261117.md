# Auditoria de retenção (laudo §2.2) e notificações que reaparecem (§7) — 17/11/2026

Estado: **revisão de código e plano de medição. Nenhuma correção de comportamento**, porque nenhuma retenção ou intervenção do OULG foi comprovada. Não substitui o heap dump do item 4 do laudo.

## 1. Retenção: o que foi verificado no código (`main` 8a3978e)

Critério: uma referência fraca não protege nada se o valor da entrada alcança a própria chave (ou a árvore dela) por referência forte, ou se uma coleção forte cresce sem limite.

| Estrutura | Situação | Base |
|---|---|---|
| `HeadsUpHooks.states` (`WeakHashMap<View, State>`) | O valor alcança a chave enquanto o cartão está anexado: `semBridge.host` = view de fundo, e `swappedViews` são views da mesma row. No detach, `release()` zera `glass`/`semBridge`/`compBridge`, `setForeground(false)` esvazia `swappedViews`, e `notificationIcons.restore()`, observer e `wm` são liberados. Não há `states.remove`, então a entrada depende da coleta da chave. | `detached()`, `release(boolean)`. O #192 troca o índice por `WeakIdentityMap<View, WeakReference<State>>` com o `State` pertencendo ao listener da view, o que elimina a dependência estrutural. |
| `Foreground.swapped/glyphs/chevrons/shadows/restoring` | Valores sem view forte (`ColorStateList`, filtros; drawables com callback fraco). | Leitura dos tipos. |
| `InkAnimator.runs` (`IdentityHashMap` forte, chave = view) | Limitado pela duração da transição: a entrada sai ao fim ou no `cancel`. | `run/step/cancel`. |
| `UberRemoteViewsTone.active` | `Lease` guarda `Drawable` (callback fraco) e `ColorStateList`. Não retém view. | Tipos. |
| `NotificationProgressTone` (#194) | Só `ColorStateList`. | Tipos. |
| `MediaArtGlass.WATCHED` | O valor guarda `art` (filho da chave) enquanto anexado; o detach zera `art`/`overlayView`. | `onViewDetachedFromWindow`. |
| `LockMediaProbe.sessions` | Leases com `WeakReference<View>`. | Tipos. |
| `NotificationIconTreatment.sourceCache` | LRU de 48 entradas. Cada uma guarda um bitmap de 128 px (`SIZE`), cerca de 64 KiB em ARGB ou menos em máscara: no máximo ~3 MiB, fora do heap Java (bitmaps nativos desde o Android 8). | `CACHE_LIMIT`, `SIZE`. |
| Fila do worker de ícones | Ilimitada em `main`. O #193 remove a tarefa obsoleta no `restore()`; o #199 deixa de criar trabalho duplicado quando a resposta já foi publicada. | `ThreadPoolExecutor`/`worker.remove`. |
| `QsTextHooks.composeFilters`, `QsSliderHooks.fillBrushes`, `QsOpticsCoverage.tokens`, `LiveAdmissionDiagnostics.lastLog` | Limitadas (16 / 12 / 512 / 64). | Código. |
| `LiveMediaSurvey.signatures/lastLogged` | Uma entrada por raiz de mídia, só com diagnóstico ligado, limpa ao desligar. | `tick()`. |
| Listeners estáticos (`Tuning.onChanged`, `BackdropEvents.add`, `ShadeTone.listen`) | Registrados uma vez na instalação dos hooks. | Chamadores. |
| `SemBlurBridge.NATIVE` (`WeakHashMap<View, Object>`) | O valor é o `SemBlurInfo` da Samsung. Uma referência dele de volta à view **não pode ser descartada sem a classe da Samsung**. | Verificar no heap dump. |

**Conclusão:** esta revisão **não comprova** nenhuma retenção do OULG. Os números do meminfo (25.577 views, 324 AppContexts) são compatíveis com rows retidas, mas também com o próprio SystemUI sob carga. O que separa os dois casos é o heap dump.

## 2. Medição que decide (laudo §2.2 itens 3–4)

1. Instale o APK com #192 (e #193/#199, se aprovados). Reinicie o SystemUI uma vez e anote o PID.
2. Rode a série no mesmo PID: baseline, depois de estabilizar, depois de N ciclos (heads-up, descartar, abrir a central, mídia, timer, um app pesado em primeiro plano recebendo notificações próprias) e depois de 5 min ocioso. Use o mesmo `dumpsys meminfo -d $PID` do laudo e anote Views, AppContexts e Activities.
3. Se Views/AppContexts crescerem e não voltarem após o ócio, faça o heap dump (só em janela ociosa; mantenha local e privado):
   ```sh
   su -c 'am dumpheap --help'   # confirmar a sintaxe da build antes
   su -c 'am dumpheap com.android.systemui /data/local/tmp/sysui.hprof'
   ```
   Na análise, procure instâncias de `ExpandableNotificationRow` sem `mParent`, com o caminho até a raiz de GC. Uma cadeia que passe por `io.github.igorcv88.oneuiliquidglass.*` (por exemplo `HeadsUpHooks$State`, `SemBlurBridge`, `GlassDrawable`, `SemBlurInfo`) atribui a retenção ao OULG; uma cadeia só por classes da Samsung/AOSP, não.

## 3. Notificações descartadas que reaparecem (§7)

**Código:** nenhum hook do OULG cancela, reposta, adia ou bloqueia notificações:
- os hooks de `HeadsUpManager` (`showNotification`/`updateNotification`/`removeNotification`/…) só registram `MANAGER_EVENT`;
- `LiveAdmissionDiagnostics`/`LiveRemovalDiagnostics` são somente leitura e dependem do trace;
- os `setResult(null)` existentes atuam apenas em `semSetBlurInfo` e no desenho.

**Hipóteses a separar:**
1. **O app reposta** a notificação (`notify()` com o mesmo id ou um novo, `postTime` novo). É comportamento externo.
2. **O SystemUI morreu** entre o deslize e o cancelamento no `NotificationManagerService`. A notificação continua ativa no serviço e volta quando o SystemUI reinicia. Com 6 mortes em 2 dias, isso é plausível; aqui o OULG atua indiretamente, pelos crashes (#192/OOM), não por interceptar o dismiss.
3. A row reaparece **sem** a notificação estar ativa no serviço (falha de coleção ou de row). Seria defeito de SystemUI/OULG.

**Coleta somente leitura** (não usar `--noredact`; o buffer `events` não traz texto):
```sh
su -c 'logcat -b events -d | grep -E "notification_(enqueue|cancel|panel_hidden|clicked)|am_proc_died|am_crash" | tail -n 400' > /sdcard/Download/OULG_REPOST_$(date +%Y%m%d_%H%M%S).txt
su -c 'pidof com.android.systemui'
```

Leitura do registro:
- `notification_cancel` do deslize (motivo de cancelamento pelo usuário) seguido de um `notification_enqueue` do mesmo pacote: hipótese 1.
- O deslize sem `notification_cancel` correspondente e `am_proc_died`/`am_crash` do `com.android.systemui` logo depois: hipótese 2.
- Nenhum `notification_enqueue` novo, nenhuma troca de PID, mas a row volta: hipótese 3. Só nesse caso se abre correção.

Não altere o comportamento de dismiss antes desse registro.
