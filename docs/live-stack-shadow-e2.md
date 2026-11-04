# E2 / opção A — atribuição e recorte do indicador inferior da pilha (DZIF)

Experimento **reversível e atrás de flag**. Com o padrão `0`, nada é alterado. **Não é uma correção definitiva** e não deve ser integrado como tal antes do resultado no aparelho.

## Base

O E1 (`docs/live-stack-external-e1.md`, log `OULG_E1_20261104_133743`) encontrou três views fora do `CardStackView`:

```
ongoing_card_stack_and_shadow_layout (LinearLayout)
 ├ CardStackView
 └ ongoing_card_bottom_shadow_frame (FrameLayout, topo = base do stack: y 440 / 590 / 602)
    ├ ongoing_card_bottom_shadow1_view  escala 0,92  ty −195  alpha 0,5  1340×216
    └ ongoing_card_bottom_shadow2_view  escala 0,84  ty −171  alpha 0,3  1340×216
```

O fundo das duas views indicadoras é um `RippleDrawable` com uma camada de conteúdo `GradientDrawable #FA3A3A3D` e uma máscara branca. É tinta comum: não há `BackgroundBlurDrawable` nem região de compositor nesse ramo.

O `CardStackView` declara `showBottomCardShadowIfNeeded(1)` e `removeBottomCardShadow()`.

A tela coincide com a cápsula medida no MP4 do PR #147:

| Cartão na frente | Posição de `shadow1` | Cápsula medida |
|---|---|---|
| D (base 590) | y 404–603, x 104–1336 | y 402–604, x 103–1337 |
| H (base 440) | y 254–453 | y 252–454 |

A faixa mais fraca, até y ≈ 617, corresponde ao `shadow2`.

O frame desenha os filhos fora dos próprios limites, já que eles estão deslocados para cima. Por isso, eles aparecem atrás do cartão da frente. No visual nativo, o cartão opaco esconde essa área e só ficam visíveis ~12 px (`shadow1`) e ~27 px (`shadow2`) abaixo dele.

**A atribuição dos pixels ainda é uma hipótese.** Este experimento serve para testá-la.

## Controle

```sh
su -c 'setprop debug.oulg.stackshadow N'
```

A propriedade é relida no máximo a cada 750 ms, sempre que o Samsung reescreve o stack ou um cartão. Um swipe aplica o novo modo.

| N | Modo | O que muda |
|---|---|---|
| 0 | nativo | nada; tudo o que o experimento alterou é restaurado |
| 1 | atribuição | camada de conteúdo de `shadow1` **e** `shadow2` em magenta (`SRC_IN`) |
| 2 | suprimir `shadow1` | camada de conteúdo de `shadow1` transparente |
| 3 | suprimir `shadow2` | idem, só `shadow2` |
| 4 | suprimir ambos | prévia da opção B: sem indicador |
| 5 | **A2**: recortar o frame | `frame.setClipBounds(0, 0, w, h)`: só o que fica abaixo da base do cartão |
| 6 | **A**: recortar cada view | `setClipBounds` local calculado a partir de top, `translationY`, pivô e escala (hoje 203/216 e 183/216) |
| 7 | alpha da view | alpha final 0 em shadow1/shadow2 via `View.setTransitionAlpha(0f)` (o `getAlpha()` da Samsung fica intacto); não suspende a região do compositor |
| 8 | supressão completa | modo 7 + região `BackgroundBlurDrawable` de cada indicador com alpha 0 (`NativeRegionLease`) |
| 9 | só a região | região de blur dos indicadores com alpha 0; alpha da view nativo |

> Os indicadores trocam o `RippleDrawable` por um `BackgroundBlurDrawable` quando ficam visíveis (E1: `@c7ded7f`/`@d7956aa`, alpha 8→127). Por isso os modos 1–4 perdem a elegibilidade (`reject=notRipple`), e recortes ou alpha da view não limitam a região que o SurfaceFlinger desfoca.

Escopo e garantias:

- Só são alteradas as três views com esses **nomes exatos de recurso**, localizadas a partir do pai direto do stack, e só enquanto um cartão com vidro desse stack está visível.
- Filtros e clips nativos são salvos e restaurados exatamente quando a flag volta a 0, quando não há vidro, quando a view é desacoplada ou em caso de erro.
- Máscara, outline, alpha, visibilidade, layout, toque, `stack_pip_layout`, botões, CHIP, Now Bar e notificações comuns **não** são tocados.
- O estado é registrado em `LIVE_STACK_SHADOW_E2` apenas quando muda.

## Ciclo de vida e log (revisão após o primeiro teste no DZIF)

No primeiro teste do modo 5, o log mostrou `state=applied frameClip=-` às 15:13:53.069 e `restored:detached` às 15:13:53.085. O recorte nunca foi aplicado, porque o frame ainda estava 0×0. Além disso, o detach do frame desarmou o experimento por completo. **Esse teste não refuta o A2.**

Correções desta revisão:

- Os listeners de pre-draw e de attach ficam no `CardStackView`, não no frame transitório. A cada quadro, as três views são resolvidas de novo a partir do pai do stack, com verificação de identidade primeiro.
- Quando o frame, um indicador ou o drawable é substituído, os leases antigos são restaurados antes de a nova instância ser alterada.
- O modo 5 só é aplicado quando o frame tem tamanho de layout válido. Até lá, o log registra `state=requested pending=frameNotLaidOut`.
- O detach do stack restaura os leases e mantém o pedido (`state=suspended`). O attach seguinte retoma.
- A propriedade também é relida no pre-draw, com cache de 750 ms. `stackshadow=0` restaura e desarma sem esperar swipe, desde que a janela continue desenhando. O caminho inverso, de 0 para N, ainda precisa de um writer do stack, como um swipe ou a atualização do timer.
- Um quadro estável não aloca, não invalida e não registra log.

Formato do evento, emitido só quando algo muda:

```
LIVE_STACK_SHADOW_E2 mode=5 state=effective pending=- frame=<id> frameWh=1340x216 frameVis=0 frameClip=[0,0][1340,216] ... attached=true
```

`state` assume `requested` (pedido feito, ainda não aplicado; `pending` traz o motivo: `noFrame`, `frameNotLaidOut`, `noIndicator`, `noGeometry`, `detached`), `effective`, `suspended` ou `restored:<motivo>`.

**Critério mínimo para avaliar o A2:** `mode=5 state=effective frameClip=[0,0][1340,216]` (ou as dimensões reais do frame) mantido depois que o cartão assenta.

## Captura

Use o mesmo script do E1, trocando a propriedade por `debug.oulg.stackshadow` e mantendo `debug.oulg.stackprobe 2`. Faça uma captura por modo, de 15 s, com **pausas de mais de 1 s em cada cartão**.

Sequência mínima: modos 0, 1, 5 e 6 com duas live updates; modo 5 com live + mídia; modos 0 e 5 com uma live update sozinha.

## Previsões e critérios

| Modo | Se o indicador for o produtor | Refuta |
|---|---|---|
| 1 | A cápsula fica magenta em D e em H, assim como as faixas abaixo do cartão | A cápsula continua cinza com `filter1/filter2=true` no log |
| 2 | Some a cápsula principal; resta só a faixa fraca até ~617 | A cápsula não muda |
| 3 | A cápsula principal permanece; some só a faixa fraca | — |
| 4 | Sem cápsula e sem faixas inferiores | A cápsula permanece |
| 5 | Sem cápsula; faixas de ~12/27 px abaixo do cartão preservadas | A cápsula permanece com `frameClip` ativo no log |
| 6 | Mesmo resultado visual do 5 | Diferença entre 5 e 6 indica geometria diferente da medida |

Controle negativo: com uma única live update, nenhum modo deve produzir diferença visível.

Verificar também: botões, ripple de toque, clip do cartão, blur dos cartões, swipe e expansão/recolhimento.

Se o modo 5 eliminar a cápsula e preservar as faixas, ele é o candidato à correção. A implementação final deve sair num PR separado, sem a flag de experimento.

O blur tardio (M2) está **fora** deste experimento.
