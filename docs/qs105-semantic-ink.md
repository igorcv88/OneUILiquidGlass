# QS105 — rotas semânticas de tinta e decisão compartilhada

Base: `da02ab53500c937cda3aa47fe6841d388a6d56d0` (main, PR #104 mesclado).
Firmware analisado: SystemUI DZIF, SHA-256
`801e74ef4e8acbdfd2dea70e2b5ab24f9917b2162f13b611b881f07533d4cea0`.
A inspeção local decodificou tabelas, assinaturas, campos e instruções dos DEX do APK
fornecido. O código abaixo ainda não foi executado no Galaxy.

## Evidência e intervenção

- `QuickTileBoxItemLayout(Modifier, QuickTile, boolean, Function0, Function0,
  boolean, Composer, int, int)` usa um `derivedStateOf` cujo cálculo é
  `QuickTileBoxItemLayoutKt$QuickTileBoxItemLayout$iconColor$2$1.invoke(): Object`.
  Os campos são `$item`, `$resourceColorPicker`, `$state$delegate`. O resultado é
  `Color.box-impl(long)`, não um ARGB Integer. O estado numérico vem de
  `QSTile.State.state`; `availableItem != null` usa estado 1 no firmware.
- `QsTileBoxHooks` adiciona uma dependência `MutableState` ao cálculo derivado.
  Mudanças de cena, configuração e liga/desliga invalidam o cálculo, incluindo
  recomposições diferidas. A cor de recurso global não é interceptada.
- `rememberQsTileIconDrawablePainter-ZLcQsz0(Drawable, Color, Composer)` retorna
  `QsTileIconPainterState(painter, applyImageLayerColorFilter)`. A associação ao
  modelo usa o painter dessa chamada durante o layout exato da grade. Painter
  compartilhado entre modelos é excluído do hook de `RoundIcon`.
- O DEX confirma que Lottie recebe a tinta pelo efeito nativo e retorna
  `applyImageLayerColorFilter=false`; AnimatedVector/Other retornam true.
  O hook conserva esse booleano e o painter, sem substituir animações ou aplicar
  filtro global. Só cores nativas neutras são adaptadas; avisos coloridos são mantidos.
- `RoundIcon-It-hVyY` recebe o painter no parâmetro 3, a tinta no 4 e duas
  funções de fundo/contorno. Só a função exata com campo `$bgColor` é substituída,
  por uma nova instância. Não são alterados campos de lambdas compartilhadas.
  Disco transparente permanece transparente; discos visíveis usam a paleta
  existente e o estado real. A tinta da grade considera a superfície resultante,
  sem mudar a preferência branco-sobre-preto dos controles grandes.
- Brilho: `BrightBar$lambda$1(State): float` fornece o nível;
  `rememberColorFilterProperty-RPmYEkk(long, Composer)` cria a propriedade de
  filtro Lottie. O override é limitado à execução de `BrightBar`/`BrightBarWithTile`,
  ambos com nível `lambda$1(State): float` confirmado no DEX.
- Volume: `VolumeBarKt$$ExternalSyntheticLambda4.invoke(Object)` cria/atualiza
  `QPVolumeIcon`, um AndroidView. `updateLayout(int)` aplica tintas às ImageViews
  internas. Só instâncias comprovadamente vinculadas ao VolumeBar são alteradas;
  estados de alpha e avisos não neutros permanecem nativos.

## Cena compartilhada

`PanelScene` publica um snapshot imutável com o frame de luminância, decisão do
corpo e decisão da faixa do cabeçalho. Um único par de gates decide cada frame;
QS e shade recebem a publicação na thread principal, sem aguardar seus timers
independentes. Frames antigos/duplicados não revertem a decisão. Após interrupção
superior a 1 s, o classificador não reaproveita a histerese anterior.

Os dois painéis usam os limiares QS (.75/.65). A faixa do cabeçalho continua local;
a grade e o corpo compartilham a decisão global. Pop-ups e lockscreen não usam o
novo listener da shade. A rampa exclusiva do texto QS não é usada no commit de cena:
ícones, superfícies e óptica recebem a mesma decisão antes do próximo desenho.
Isso coordena a solicitação de renderização; não prova atomicidade dos pixels
entre Compose e SurfaceFlinger no display.

Capturas one-shot agora pedem o display inteiro. O preview rejeita frames anteriores
à sua inscrição e recortes parciais. Resultados de redução em voo são descartados
após indisponibilidade da captura. A passagem lateral aceita somente 250 ms de idade,
como a fonte compartilhada. Uma abertura sem captura passa a tentar novamente,
em vez de depender de `sampledAt != 0` para agendar a recuperação.

## Validação e limites

Seis casos novos de regressão passaram em harness Java local: mesma decisão para
os dois consumidores, rejeição de frames antigos/duplicados, ruído/confirmacão,
interrupção, contraste em vidro/disco/preenchimento e preservação de alpha/acentos.
O harness usa doubles mínimos de JUnit e QsStyle; não equivale à suíte Android.
Os três novos hooks e o sinal Compose também passaram por compilação Java com
stubs de API. O resultado da compilação Android, testes completos e lint deve ser
consultado nos checks do PR; esses resultados não validam reflexão no firmware.

Ainda é necessário medir no aparelho:

- cobertura dos 15 modelos por revisão, incluindo ON/OFF, indisponível e Theme Park;
- primeiro quadro em abertura direta/lateral e captura indisponível/protegida;
- atualização de página clara/preta dentro do mesmo aplicativo, com painel aberto;
- 0/1/5/50/100% de ambos os sliders, inclusive subida e descida;
- cores nativas de Lottie/ícones de terceiros e ausência de regressão em mídia;
- notificação, pop-up, lockscreen, custo de captura e jank.

**Limites de implementação que não devem ser marcados como resolvidos:** sem pixels
recentes não há garantia de primeira tinta correta. Nos sliders, a política distingue
zero (vidro) de preenchimento positivo; não mede a interseção geométrica entre o glifo
e a extremidade arredondada em percentuais muito baixos. Essa fronteira continua
pendente; nenhum resultado de host comprova contraste em cada pixel de 1–5%.
Não foram feitas alterações especulativas nos demais itens históricos
(toasts, live updates e interface) sem evidência adicional nesta coleta.

## Coleta

Com `debug.oulg.trace=1`, conferir:

- `QS_TILEBOX_READY` e `QS_TILEBOX_INK` (modelo opaco, estado, cena, entrada/saída,
  superfície; nenhum texto, SSID ou especificação de aplicativo);
- `QS_TILEBOX_COVERAGE revision=... captured=...` (modelos distintos observados
  na revisão de paleta, não contagem garantida de ícones visíveis);
- `QS_SLIDER_ICON_NATIVE_READY` e `QS_SLIDER_ICON_ROUTE owner=native`;
- `PANEL_PREVIEW_MISS` (idade, redução em andamento, anchors e disponibilidade);
- `QS_BACKDROP`, `QS_TONE_LIVE`, `SHADE_PREFETCH state=shared-commit`, `CAPTURE_STATS`.

Os logs detalhados dependem de trace e são deduplicados/limitados. Não remover as
rotas antigas de controles grandes antes de comprovar a cobertura no dispositivo.
