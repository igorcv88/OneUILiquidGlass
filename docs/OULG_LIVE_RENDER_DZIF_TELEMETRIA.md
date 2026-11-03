# Telemetria temporária — vidro ao vivo no DZIF

Esta instrumentação observa o caminho de renderização, sem alterar blur, tint, raio, alpha,
hook, janela ou composição. Ela compara as notificações já aprovadas (`LIVE_RENDER_CONTROL`)
com a pílula (`CHIP`), o cartão expandido (`CARD`), a Now Bar (`NOWBAR`) e o toast (`TOAST`).

## Eventos e interpretação

| Evento | Evidência |
| --- | --- |
| `LIVE_RENDER_STATE` | Modo escolhido pelo controlador (`OWN_REGION`, `REGIONLESS`, `NATIVE`, `WAITING_SAMSUNG`, `DETACHED`, `DRAW_FAILURE`), presença do blur, host, janela e backgrounds ancestrais. |
| `LIVE_RENDER_DRAW` | Caminho **realmente executado** no `Canvas`: região própria, região Samsung sem região própria, nativo ou falha; registra hardware acceleration e bounds. |
| `LIVE_RENDER_CONTROL` | Controle positivo: material renderizado pelas notificações aprovadas, tipo de backdrop e hardware acceleration. |
| `LIVE_RENDER_CARD` | Quantas regiões da Samsung tiveram a cor tratada e quantos fills ancestrais foram cobertos. |
| `LIVE_RENDER_REGION_TINT` | Cor anterior, cor pedida, cor efetivamente relida e sucesso na supressão da camada escura. |
| `LIVE_RENDER_NOWBAR_CARD` | CardView transparente/restaurado, cor/elevation efetivas depois do setter. |
| `LIVE_RENDER_NOWBAR_GRADIENT` | Pedido da Samsung e estado lido após ocultar/restaurar o gradiente. |
| `LIVE_RENDER_NOWBAR_LAYER` | Criação/reattach da camada dentro do CardView. |
| `LIVE_RENDER_TOAST_WINDOW` | Tipo da janela, elegibilidade, flag de aceleração de hardware antes da apresentação. |

O evento de decisão e o de desenho podem divergir. Por exemplo:

- `REGIONLESS` com `tintedRegions=0` ou `verified=false` indica que a hipótese de uma camada
  nativa opaca ainda precisa ser examinada; **não prova** por si só que há blur atrás.
- `OWN_REGION` no controlador, mas `NATIVE`/`REGION_DRAW_FAILED` no desenho, indica que o
  compositor não recebeu um desenho válido naquele frame.
- `REGIONLESS` com `tintedRegions>0` e `verified=true`, mas cartão ainda opaco, sugere
  outro fill/overlay, ou região Samsung sem blur efetivo. As capturas de SurfaceFlinger em
  `--proto` do DZIF não revelam o blur Samsung nem no controle positivo; não usá-las como
  conclusão de ausência de blur.
- `LIVE_RENDER_CONTROL` permite conferir qual tecnologia realmente desenhou uma notificação
  boa, sem presumir que toda notificação aprovada use `BackgroundBlurDrawable`.

## Uma captura física, em sequência

1. Instale o APK deste PR e reinicie apenas o **SystemUI** pela rotina de uso já habitual;
   não reinicie o SurfaceFlinger nem instale shader/módulo SF.
2. No aparelho, abra uma notificação pop-up **aprovada** como controle.
3. Inicie um temporizador: observe a pílula, expanda o cartão, interaja e recolha.
4. Bloqueie/desbloqueie para observar a Now Bar. Faça surgir um toast de texto.
5. Extraia as linhas abaixo (não é necessário ativar o `debug.oulg.trace`, que habilita
   levantamentos muito mais pesados):

```sh
su -c 'logcat -d -b main -b system -v threadtime -s OULG:I "*:S"' | grep -E 'LIVE_RENDER_(STATE|DRAW|CONTROL|CARD|REGION_TINT|NOWBAR_CARD|NOWBAR_GRADIENT|NOWBAR_LAYER|TOAST_WINDOW)|LIVE_GLASS_(APPLY|RESTORE|NO_REGION|SAMSUNG_BLUR)'
```

As linhas são emitidas quando **um host muda de modo**. Não há um `logcat` por frame; em
uma animação estável com 120 frames idênticos haverá só a primeira linha daquele modo.
Os eventos não coletam texto de notificações, nome do remetente, conteúdo de toast,
chaves de notificação, pacote do aplicativo, RemoteViews, áudio nem pixels.

## Critério para remover a instrumentação

Após identificar se a falha é do blur, da janela ou de uma camada opaca e validar a correção
no Galaxy, remover os eventos `LIVE_RENDER_*` e `LiveRenderProbe`, preservando os caminhos
funcionais. Este PR é **somente de telemetria** e não deve ser confundido com uma correção visual.
