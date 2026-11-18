# R5: correções no #211, diagnóstico e protocolo físico (18/11)

Base: #211 revisado em `ace226c` (matriz em `R5_REVIEW_MATRIX.md`). As correções são commits separados sobre a mesma branch. Somente Java/recursos: **nenhuma mudança no `sfhook`**, bundle nativo continua **v0.36.0 / 37**. Lift/Clareamento, #205, #207 e as cores aprovadas não foram tocados.

Legenda: (D) observado no código ou em teste, (U) relato do usuário, (H) hipótese, (N) simulação no host, (V) pendente no aparelho.

## B1. All Parameters: o editor segue a aplicabilidade (§9)

- **Causa (D):** `ControlSheet` mostrava o motivo de inatividade, mas ainda montava slider, campo exato, reset, escolha e Save. Não havia barreira no modelo.
- **Barreira única:** `AppModel.saveControl(key, valor, superfície)` passa a ser a barreira de toda edição de controle: linha, reset, slider, campo, escolha, toggle e Save. Em `INACTIVE`/`OFF` recusa com o motivo da regra compartilhada (`Applicability`) e mantém o valor salvo. `NEEDS_RESTART` continua editável.
- **Comportamento do sheet:**
  - Inativo: mostra o motivo, o valor salvo e "Em vigor agora: nada (inativo)", sem controles de edição.
  - Condição muda com o sheet aberto (por exemplo, Desfoque adaptativo alterado em outra tela): o sheet se reconstrói e volta a oferecer o valor salvo antes.
- **`sf.hubloom`:**
  - A regra não mudou: inativo só no Clear com K1–3 e Desfoque adaptativo > 0.
  - O motivo agora cita as duas saídas: "use o Original ou desligue o Desfoque adaptativo".
- **Texto "sem prévia":**
  - Nova classe `Preview.ON_DEVICE` para 18 controles ópticos: luz de borda, material da lente, pesos de realce e lente da QS.
  - Esses controles deixam de ser descritos como "tempo ou comportamento".
  - Os demais controles `BEHAVIOR` ficam para a reorganização (#209).
- **Testes:** `ControlSheetApplicabilityTest` (Robolectric, atividade real).

## B2. Slider horizontal da QS com preenchimento baixo (§5.2)

- **Causa confirmada (D):** `cross = vertical ? narrow : min(narrow, hi-lo)`. Com isso, o preenchimento horizontal baixo virava disco.
- **Correção:**
  - Espessura transversal fixa nos dois eixos.
  - `QuickSettingsGlass.lowFill` aplica o contorno vertical aprovado (semicírculo na origem, ombro móvel que achata) a qualquer eixo e a qualquer origem.
  - A origem é lida do intervalo nativo (`anchoredHigh`), sem presumir LTR, o que cobre RTL e offset do OEM.
  - O clip do trilho vale para os dois eixos.
- **Testes (N):**
  - O vertical é numericamente idêntico ao `drawLowVerticalFill` aprovado.
  - Horizontal LTR/RTL em 0/1/5/10/50/95/100 % mantém espessura, comprimento nativo e origem.
  - O contorno horizontal é o vertical rotacionado.
- A descrição de `qs.fill` não diz mais que vira círculo.

## B3. P3: borda do pop-up existente e Live Update (§6)

- **HU, causa no código (D):**
  - `composeGlass` desenha o `GlassDrawable` à mão.
  - O callback dele é a View de fundo, mas o drawable não é um drawable verificado dessa View. Por isso `View.invalidateDrawable` descartava o `invalidateSelf()`.
  - O watcher detectava 7→9 e aplicava a compatibilidade, mas nada redesenhava até a shade abrir ou o cartão ser recriado. Isso é compatível com o relato (U) de borda perdida "durante a vida do pop-up".
- **HU, correção:** `setP3EdgeCompatibility` informa se mudou, e `applyP3Mode` invalida a View dona. É uma atualização no lugar, sem recriar o cartão e sem mudar a frequência.
- **CARD (Live Update), estado anterior (D):** a rota `OWN_REGION` nunca configurava a compatibilidade P3.
- **CARD, correção:**
  - `LiveGlassDrawable.setP3EdgeCompatibility` passa o valor para a óptica.
  - `LiveGlass` lê o modo uma vez na criação e no attach, nunca por quadro.
  - Somente enquanto o cartão é um heads-up visível, segue o watcher existente do HU via `P3ModeBus`. É o mesmo watcher, com os mesmos 30 Hz, a mesma guarda de ciclo de vida (shade fechada, desbloqueado) e para junto com o último pop-up visível. Não há segundo poller.
  - A política é a compartilhada (`P3EdgeSelection.eligible`: só no modo 9, e modo desconhecido falha fechado). Não traz a guarda de shade desbloqueada do HU: a leitura de criação/attach vale também no editor da lock screen.
  - O REGIONLESS desenha o próprio aro e não mudou.
- **Log, só por mudança:**
  - `P3_EDGE_ROUTE surface=HU|CARD`: id do dono, modo, compat requerida/aplicada, `invalidated`, rota.
  - `P3_EDGE_DRAW_STATE path=glass|optics p3Compat=…`: uma vez por mudança, no próximo draw efetivo, nos dois sentidos.
- **Limite (H):** no editor da lock screen, uma troca de modo **depois** do attach, com o cartão parado, só é lida no próximo attach. A captura 08 não registra o modo daquela janela (V).

## B4. QS: bloco coletivo, halo e distribuição (§4, §5.1)

### Contabilidade `rimWithoutOptics` (D)

Em todas as linhas `stabilized` do trace R4, `rimWithoutOptics == rimAttenuated`. Pelo código, isso é um artefato:
1. Cada recomposição cria um token novo.
2. Depois que a caixa acende, a borda é construída atenuada nesse token novo.
3. O desenho continua no token ligado ao nó de fundo, porque o Compose não chama `update` quando o elemento não mudou.
4. Contado por token, todo contorno atenuado aparecia "sem óptica".

O invariante real é **por caixa**: o escopo `BoxWithOutline`, a mesma chave que `QsLitRims` usa para decidir a atenuação. Agora `rimWithoutOptics` conta caixas com contorno atenuado e nenhum token desenhado na sessão. `boxes` e `rimAttenuatedTokens` são informados ao lado. Não houve mudança de desenho.

**Leitura (H):** a decisão visual (`QsLitRims`) já era por caixa e só atenua depois de um draw bem-sucedido da mesma caixa. Por isso o contador antigo não é evidência de contorno atenuado sem AGSL no mesmo box. A confirmação é `rimWithoutOptics=0` em `phase=stabilized` no aparelho (V).

### Regressão angular e halo: não resolvidas pela revisão estática

O trace R4 não mostra raio inválido para o bloco coletivo. O #202 continua necessário no horizontal. Não alterei `qslight`, `qsbody`, `qsdepth`, `qslens`, blur, glow nem outsets.

### Diagnóstico seletivo (desligado por padrão)

`setprop debug.oulg.qslayers <id>:<máscara>`:
- O valor é lido uma vez na abertura do painel.
- `id` é o id que `QS_EDGE_GEOMETRY`/`QS_OPTICS_DRAWN` imprimem para o bloco coletivo (2145 no trace R4; o id muda entre sessões do SystemUI).

| Bit | Camada removida, só nessa caixa |
|---|---|
| 1 | Perfil 3 do SF: mesma região e blur, raio inteiro, sem a tag `.875` |
| 2 | Óptica AGSL |
| 4 | Contorno Compose (pincel transparente) |

Comportamento:
- As outras caixas e superfícies não mudam.
- Termina sozinho 120 s após a abertura, com redesenho e recomposição.
- Registra uma linha `QS_LAYER_ISOLATION` em cada evento: `armed`, cada camada aplicada e `ended`.
- A sombra/elevation nativa não tem dono identificado no código do módulo: não está isolada.

### Distribuição em paisagem (§5.1)

Nenhum hook do módulo altera medida, span, slots ou ordem de tiles da QS. Os hooks mexem só em cor, forma, posição do ícone dentro do tile e desenho do slider no mesmo tamanho medido. **(H)** O arranjo da captura 02 é nativo da Samsung. Não mudei nada. Uma redistribuição consciente exige layout Compose nativo, e eu a trataria como mudança de produto, não como regressão.

## B5. Gate de economia de energia (§15.1): registro, sem alteração

- **Gate (D):** `hooks/ongoing/LiveSurfaceHooks.java`.
  - `CompositorBlur.enabled` → `allowed(powerSave, disable_window_blurs)` = `!powerSave && !"1".equals(disable_window_blurs)`.
  - O receiver de `ACTION_POWER_SAVE_MODE_CHANGED` chama `glass.syncAll()`.
  - Vale para CARD, chip, Now Bar e toast.
- **Equivalentes procurados (D):**
  - `HeadsUpHooks` usa `isCrossWindowBlurEnabled()`, que no DZIF é sempre false. Só libera a rota COMPOSITOR sem bridge; não é gate de energia.
  - `Probe.blurEnvironment` é diagnóstico.
  - Não há outro uso de `isPowerSaveMode`.
- **Pertinência:** não comprovada nas regressões R5. O trace R4 mostra o CARD em `OWN_REGION` (vidro ativo), ou seja, a economia não estava atuando. Pelo mandato, não alterei nada.
- **Encaminhamento para a etapa #209 (Opus/Sonnet):** trocar `powerSave` por política voluntária própria, mantendo `disable_window_blurs`, doze/AOD (`glassAllowed` do Now Bar) e restauração. O teste existente é `LiveSurfaceHooksTest` (linhas 174–177).

## Testes e verificação

- Suíte Java local: **908 testes, 0 falhas** (896 no head revisado + 12 novos). O CI do #211 confirma.
- Matriz de controles regenerada (`OULG_UPDATE_MATRIX=1`).
- Geradores sem diferença.

## Protocolo físico mínimo (um reinício do SystemUI; nenhuma lente nova)

1. Instale o APK do CI e reinicie o SystemUI. **Não** reaplique a lente: o nativo é o mesmo.
2. **All Parameters:**
   - Clear, kernel gaussiano, Desfoque adaptativo ligado → abra "Contenção de brilho". Esperado: sem slider, sem campo, sem Save; motivo citando o adaptativo.
   - Desligue o adaptativo em outra tela e volte: deve ficar editável, com o valor salvo antes.
3. **Slider horizontal (paisagem):**
   - Volume e brilho em ~1 %, 5 % e 10 %: espessura igual à de 50 %, ponta de origem arredondada, ombro achatando.
   - Em 0 %: vazio.
   - Repita em RTL, se possível.
   - O vertical não deve mudar.
4. **P3 HU:**
   - Pop-up visível em SDR → abra Instagram com conteúdo P3 sem fechar o pop-up.
   - A borda deve se manter e acompanhar a troca, e voltar ao sair.
   - Log: `su -c 'logcat -d -s OULG | grep -E "P3_EDGE_ROUTE|P3_EDGE_DRAW_STATE|P3_POPUP_WATCH" | tail -40'` (esperado `surface=HU … invalidated=true` seguido de `P3_EDGE_DRAW_STATE`).
5. **P3 Live Update:** com o Gradus, a mesma sequência. Também no editor da lock screen, registrando o `mode=` do `P3_EDGE_ROUTE surface=CARD`.
6. **QS bloco coletivo:**
   - Abra a QS e anote `rimWithoutOptics` em `QS_OPTICS_COVERAGE phase=stabilized` (esperado 0).
   - Para isolar camadas, pegue o id do bloco em `QS_EDGE_GEOMETRY`. Faça `setprop debug.oulg.qslayers <id>:1`, reabra a QS e capture; repita com `:2` e `:4` sobre o mesmo fundo.
   - Volte com `setprop debug.oulg.qslayers ""`.

## Reversão

Reinstale o APK anterior do #211 (`ace226c`) e reinicie o SystemUI. Nada nativo nem persistente foi alterado. Para o diagnóstico da QS, basta `setprop debug.oulg.qslayers ""`.

## R5.2. Geometria da QS: forma desatualizada por caixa (validação física de 17/11)

Resultado do aparelho (U):
- **Aprovados:** sliders e P3 das Live Updates.
- **Reprovada:** a geometria da QS. Aparecem dois contornos distintos em Wi-Fi, Bluetooth, painel coletivo, player, Quick Share e Home.
- **Diagnóstico de camadas:** nenhum `state=applied`.

### Medida nas capturas (D)

Ampliando os cantos (1440 × 3120), há dois arcos circulares:
- o **externo**, com r ≈ 70 px;
- o **interno**, com r ≈ 140–145 px. Em Wi-Fi/Bluetooth (290 px de altura externa) isso é meia altura, ou seja, a cápsula.

### Trace R5 (D)

- Na abertura, todos os contêineres verticais registram `requested=70`: cápsulas 652×282, player 652×595 e painel 1336×909. AGSL 66, tag 69,875.
- Mais tarde, caixas **de mesmas dimensões** registradas de novo aparecem com `requested=144.807`. Os ids 588 (652×282) e 600/626/649 (652×595) surgem quando a cena muda e o elemento de fundo é reconstruído.
- O painel 1336×909 (id 450) nunca foi registrado de novo e manteve 70 durante toda a expansão.

### Causa no código (D)

1. O `BoxWithOutline` monta `border(largura, cor, forma).clip(forma)` na caixa externa e `padding(largura).clip(forma).background(cor)` na interna.
2. O `BackgroundElement` é o mesmo véu sobre `RectangleShape`: **não contém a forma**.
3. Quando a Samsung troca só a forma (abertura da QS: 70 → 144,807 px), a borda e o clip mudam, mas o Compose mantém o `BackgroundNode` sem chamar `update`.
4. O `Edge.Spec` guardava a forma da primeira composição. A região do SF e a óptica AGSL ficavam em r=70 enquanto o contorno e o clip nativos iam para 144,807.
5. Resultado: dois contornos.

Isso também explica por que as caixas re-registradas (mudança de cena) saem corretas. O `QsCapsuleOptics` replica um único raio; a forma é `RoundedCornerShape`, e os quatro cantos agora aparecem no trace para verificação, sem presumir simetria.

### Correção (Java; nativo inalterado, bundle v0.36.0 / 37)

- A cada composição, o elemento de borda (reconstruído sempre, com a forma atual) publica a forma da caixa: `QuickSettingsEdgeLight.shape(box, forma)`, com a mesma chave `BoxWithOutline` que o `QsLitRims` usa.
- A região do SF e a AGSL leem a forma atual da caixa (`shapeOf`). Quando ela muda, o nó de fundo daquela caixa é redesenhado (`invalidateDraw`), sem recompor nem recriar a região.
- O registro `QS_SHAPE_REFRESH` sai no máximo 32 vezes por sessão.
- **Geometria resultante:** a borda Compose (raio r na caixa externa, traço de 4 px para dentro), a AGSL (r − 4 na caixa interna, concêntrica) e a região do SF (r na caixa externa, tag `.875`) passam a usar o mesmo r.
  - Cápsulas: clamp em meia altura, extremidades semicirculares.
  - Cartões: raio nativo de cada um.
  - A normalização do #202 não mudou, e nenhuma luz, refração ou opacidade foi reduzida.
- O trace de geometria agora inclui `corners=` (os quatro cantos em px), `shape=` e `registered=`. Quando aparece `registered=false`, a forma veio do refresh.

### Seletor do diagnóstico (D)

O primeiro teste armou `710:*`, que era o painel em paisagem de uma sessão anterior; em retrato não havia esse id. O seletor agora aceita:
- dimensões da caixa interna, como `1336x909` (±3 px);
- papel, como `container` ou `slider`;
- papel e dimensões juntos, como `container@652x282`;
- ou um id.

Todas as caixas que casam são afetadas. Cada armação registra `state=matched` uma vez e cada camada aplicada registra `state=applied`.

### Pendente (V)

- Confirmar no aparelho um único contorno em todas as superfícies, em retrato e paisagem, abertura e expansão, e cena clara e escura.
- Aparecer `QS_SHAPE_REFRESH` na abertura, com `registered=false` e `requested≈144.8` no painel 1336×909.
- **(H)** `boxes=16` com 8 nós desenhados no trace: as 8 caixas extras devem ser tiles compostos de outra página do pager. Isso não está provado.

### Protocolo

1. Instale o APK e reinicie o SystemUI.
2. Abra a QS em retrato e capture.
3. Rode `su -c 'logcat -d -s OULG | grep -E "QS_SHAPE_REFRESH|QS_EDGE_GEOMETRY" | tail -40'`.
4. Se ainda houver dois contornos:
   - `setprop debug.oulg.qslayers 1336x909:1`, reabra e capture;
   - repita com `:2` e `:4`;
   - para as cápsulas, use `container@652x282:<máscara>`;
   - volte com `setprop debug.oulg.qslayers ""`.
