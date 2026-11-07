# Diagnóstico dirigido: pílula, botões da live update e ícone do Now Bar (DZIF)

## Now Bar expandida: camada monocromática (07/11/2026)

O `OULG_ICON_LIVE_FOLLOWUP.log.gz` mostrou **duas imagens distintas** no
`NowBarOngoingCardView` do DZIF:

- Compacta: `ImageView id=nowbar_icon`, dentro de
  `MaterialCardView id=ongoing_app_icon_container`, já exibindo `VectorDrawable`
  com filtro de cor.
- Expandida: `NotificationRowIconView id=icon`, abaixo de
  `expand_content_rootview`/`NotificationHeaderView`, ainda contendo
  `AdaptiveIconDrawable` com `monochrome=true`. Nessa árvore não apareceu
  `ongoing_app_expanded_icon_container`.

Para a Now Bar expandida, o hook tem uma segunda lease, restrita à árvore de
conteúdo expandido da carta ongoing. Ela extrai o drawable monocromático
existente, mantém seu contraste e devolve o `AdaptiveIconDrawable` original
quando o vidro para de ser mostrado. Não altera a notificação normal nem a
Now Bar de mídia. O evento `NOWBAR_EXPANDED_ICON glass=true
before=AdaptiveIconDrawable after=...` confirma a substituição; `glass=false`
confirma restauração. A correção visual ainda requer teste físico no DZIF.

O vidro principal da lock screen e os toasts estão aprovados. Esta instrumentação **não modifica a composição**: acrescenta somente snapshots de views quando `debug.oulg.trace=1`. Não fazer ajustes de fundo/tint por tentativa até identificar a camada exata.

## O que registrar

- `CHIP`: resultado da classificação no `onBindViewHolder`, origem do dado (holder ou índice), reescritas de `setChipBg` fora do bind, views e fundos do chip. Comparar um chip com preenchimento opaco com o mesmo chip mostrando vidro.
- `CARD`: árvore do `CardItemView` após expandir, fundos (`GradientDrawable`, `BackgroundBlurDrawable`, `RippleDrawable`, `LiveGlassDrawable`), dimensões e posições de cada descendente. Identificar a view cujo retângulo ocupa somente a faixa dos botões; preservar o conteúdo, touch/ripple e ações.
- `NOWBAR`: árvore do cartão da lock screen, especialmente `ImageView` com fundo colorido, tipo do ícone, `imageTint` e presença de `AdaptiveIconDrawable.getMonochrome()`. Não alterar a CardView ou o material já aprovado.

São gravados `LIVE_DETAIL_BEGIN`, `LIVE_DETAIL_NODE`, `LIVE_DETAIL_END`, `LIVE_DETAIL_CHIP_BIND` e `LIVE_DETAIL_CHIP_WRITER`, além dos eventos `LIVE_RENDER_*` já existentes. Um cooldown de 1,5 s por view evita dumps durante cada frame; a árvore tem limite de 80 views, só quando o modo de rastreio está ativo. Não são lidos textos, conteúdo da notificação nem contatos.

## Captura no Termux (build com esta instrumentação instalado)

```sh
su -c 'setprop debug.oulg.trace 1'
su -c 'getprop debug.oulg.trace'
```

Se o valor foi definido depois que o SystemUI inicializou, o processo pode precisar ser reiniciado para carregar a configuração; faça isso somente pelo mecanismo habitual do módulo. Depois, faça um vídeo curto e reproduza, na sequência:

1. Chip que apareceu opaco e chip que apareceu com vidro (se não conseguir ambos, repita abertura/recolhimento algumas vezes).
2. Cartão do Relógio expandido com a cápsula duplicada em torno dos botões.
3. Cartão do app de treino expandido com a mesma cápsula.
4. Now Bar na lock screen com o ícone colorido visível.
5. Um toast como controle; a aparência aprovada deve permanecer igual.

Logo após o teste, ainda no Termux:

```sh
su -c 'logcat -d -b main -b system -v threadtime -s OULG:I "*:S"' | grep -E 'LIVE_DETAIL|LIVE_RENDER|LIVE_GLASS|LIVE_CHIP|LIVE_CARD|NOWBAR' > OULG_LIVE_EDGE_DZIF.log
su -c 'setprop debug.oulg.trace 0'
```

Enviar `OULG_LIVE_EDGE_DZIF.log` junto do vídeo. Os timestamps do vídeo e do log ajudam a correlacionar o estado aparente ao modo de desenho. Não ativar tracing de outras superfícies para esta rodada.

## Critérios antes da correção

- Na pílula: determinar se a área opaca é o próprio host (`NATIVE`, rebind ou falha de blur) ou uma view sobreposta enquanto o host continua `OWN_REGION`.
- No cartão: atribuir a cápsula interna a uma classe/ID/drawable e confirmar que é apenas o fundo da faixa, sem suprimir o fundo do cartão inteiro nem o feedback dos botões.
- No Now Bar: localizar o ícone e verificar se existe camada monocromática nativa ou se seria necessário um drawable alternativo. Preservar a identidade visual das aplicações e a restauração exata no fallback.

Só depois disso implementar mudanças reversíveis com testes de rebind, expansão/recolhimento, lockscreen, toast e gate off.
