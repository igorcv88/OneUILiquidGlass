# Controles rápidos — Liquid Glass (One UI 9, Compose)

Referência visual: cápsulas Wi-Fi/Bluetooth, círculos ativos claros e inativos translúcidos, grupo
de tiles, sliders de brilho/volume e cartão de mídia sobre o fundo desfocado, com borda óptica fina.
O layout, os ícones, os gestos e a organização são os da Samsung.

## O que a Central do aparelho realmente é

Firmware analisado: SM-S938B, Android 17 (SDK 37), One UI 9, `S938BXXUCDZIF`
(`SystemUI.apk` SHA-256 `801e74ef…cea0`).

- `res/layout/qs_panel.xml` declara o `SecQSPanel` legado e o `SecQSPanelComposeRoot`
  (`sec_quick_panel_compose_root`). `SecQSPanelCompose.attachComposeView()` cria um `ComposeView`,
  torna o root visível e o anexa. O dump `uiautomator` da Central aberta mostra
  `sec_quick_panel_compose_root → ComposeView → …` com os tiles como nós Compose.
- As classes `SecQSTileView`/`SecQSTileBaseView`/`qs.bar.*` ainda existem no APK, mas não desenham
  essa tela; `qs.bar.VolumeBar` e `qs.bar.QSMediaPlayerBar` nem existem. A primeira versão do PR #69
  decorava essas Views e foi substituída.
- O runtime Compose do SystemUI não está ofuscado (nomes de classes e membros preservados).

## Mecanismo

Toda superfície visível passa por
`samsung.quicksetting.ui.common.uielement.BoxWithOutlineKt.BoxWithOutline-8QY48No(Modifier,
boolean contorno, float largura, long corContorno, long corFundo, RoundedCornerShape, conteúdo,
Composer, int, int)`. Chamadores no firmware: `IconTextTileBackground` (cápsulas), `RoundIcon`
(círculos), `QuickTileBox` (grupo), `BidirectionalBar` (sliders), `OneUI90EmptyMediaPlayer`,
`QuickControlBar` e `MultiSIMPreferredBar`.

`BoxWithOutline` monta `border(largura, corContorno, forma).clip(forma)` na caixa externa e
`padding(largura).clip(forma).background(corFundo)` na interna. `QuickSettingsHooks`:

1. no início de `BoxWithOutline`, classifica a chamada pelos argumentos da Samsung
   (`QuickSettingsGlass.kind`): círculo ativo (fundo com alpha ≥ 0,5), círculo inativo (fundo
   tênue), contêiner visível (fundo ou contorno não transparente) ou nada (área transparente de
   tile de uma célula, ou círculo que a Samsung deixa transparente de propósito);
2. registra para a mesma thread os dois elementos que a função cria em seguida;
3. no construtor de `BorderModifierNodeElement`, **só em contêineres**, troca a cor do contorno por
   uma luz basal branca uniforme (`QuickSettingsGlass.rim`). Círculos internos mantêm o contorno da
   Samsung: o grupo de controles se lê como uma peça de vidro, não como várias lentes pequenas;
4. no construtor de `BackgroundElement`, troca a cor de fundo pelo véu: branco translúcido em
   contêineres e círculos inativos, a própria cor da Samsung com alpha ≤ 0,90 nos ativos.

O grande contêiner dos tiles (`QuickTileBox`) chama `BoxWithOutline` com o bit 2 da máscara
`$default` (parâmetro `contorno`, padrão `true`). Esse bit é aceito e lido como `true`; os demais
bits continuam fazendo o hook desistir. Antes, o contêiner era ignorado e ficava com a cor nativa
do `coloredBackgroundInteractor` (tinta derivada do papel de parede, o azul da captura) e com o
contorno nativo `#0dffffff`. Se o usuário definir uma imagem própria para o grupo
(`customImageDrawable`), a Samsung desenha a imagem com alpha 0,5 e o hook só aplica o contorno.

### Sliders

`IconProgressBar → BidirectionalBar` desenha trilha, preenchimento e polegar em
`BidirectionalBarKt.drawBidirectionalBarTrackProgressThumbContent-Uzs9YN0(DrawScope, …)`, sobre
toda a caixa interna. O hook recua essa área em todos os lados por
`QuickSettingsGlass.sliderInset` (6 % do lado estreito, deixando trilha e preenchimento com 88 %
dessa medida, centralizados) e a recorta numa pílula. A trilha da Samsung é um `drawRect` que só era
arredondado pelo `clip` externo. É o mesmo que `DrawScope.inset {}`: transladar o canvas e reduzir o
tamanho do `DrawContext` (o `getTransform()` foi removido pelo R8), desfeito depois da chamada,
mesmo se ela falhar. Só desenho: layout, área de toque e arraste não mudam.

### Luz de borda dependente do fundo (compositor, perfil 3)

Os cards de notificação respondem ao fundo porque o shader reescrito no SurfaceFlinger
(`sfhook/refract.h`) lê os pixels reais atrás de cada região de blur marcada. A Central passa a usar o
mesmo motor, com um perfil próprio:

- **Marca.** Cada contêiner de vidro (`Kind.CONTAINER`) ganha uma região `BackgroundBlurDrawable`
  com raio de canto `floor(r) − 1 + 0,875` (`QuickSettingsGlass.edgeTag`): a faixa 0,80–0,95 no
  programa FillRRect e, como `r + 0,5`, 0,30–0,45 no programa de clip, livres nos dois. 0,875 e
  0,375 são exatos em fp16 até 256 px (`field_check`: 852 raios de 43 a 256 px, os dois programas e
  o uniform fp16; nenhuma marca dos perfis 0–2 nem raio inteiro decodifica como 3).
- **Shader.** Perfil 3: sem refração nem blur extra. Numa faixa de `OULG_QS_LWIDTH_DEFAULT` (4,5 px)
  a partir do contorno, a luz é `ganho × (luma − piso) / (1 − piso)`, limitada a um teto e tingida em
  35 % pela cor do fundo naquele ponto; fora da faixa, transparente. Preto não soma nada, um glifo
  branco cruzando a borda acende só aquele trecho, e não há direção nem canto embutidos. A luz basal
  continua sendo o contorno do Compose, então sem o compositor nada muda.
- **Onde é desenhada.** No `BackgroundNode.draw` do próprio fundo do contêiner (o nó é ligado ao
  elemento recolorido em `BackgroundElement.create/update`), dentro da display list do Compose: a
  região acompanha layout, rolagem e transformações. O raio vem de
  `CornerSize.toPx-TmRCtEA(Density, tamanho)` da forma que a Samsung passou ao `BoxWithOutline`.
- **Fade.** Uma região de blur ignora o alpha de quem a desenha. Antes de cada frame da janela, o
  alpha das regiões é o alpha acumulado dos ancestrais do painel × `edgeFade(expansão)`
  (`SecQSPanelComposeAdapter.expansionFraction`: 0 abaixo de 60 %, inteira a partir de 95 %). Um
  ancestral com escala ≠ 1 apaga a luz: a marca exige pixels inteiros, como nos cards da central.
  O alpha nunca vai a 0 no drawable (mínimo 1/255), para a região não sumir quando o painel volta sem
  regravar a display list.
- **Condição.** Opção ligada, `debug.oulg.sfrefract=1` e `debug.oulg.sf.optics ≥ 10` (o pacote
  v0.26 anuncia 10). Sem isso, nenhuma região é criada.

Verificação no host: `sfhook/tools/edge_check.mjs` renderiza os dois programas reescritos em WebGL2
(Chromium headless) e confere corpo transparente, borda apagada sobre preto, borda uniforme sobre
cinza (oito trechos iguais), resposta local a uma barra branca na borda inferior (acesa nela, zero a
60 px e na borda superior) e FillRRect = clip.

### Círculos transparentes

Para tiles grandes `DeviceControl` (SmartThings), `NearByDevice` e `MyDeviceTileService`, a Samsung
passa cor transparente ao círculo do ícone (`QuickTileKt`: `z15` → `Color.Transparent`) e posiciona o
ícone para um layout sem disco. A primeira versão criava um disco ali (marcador de alpha 0), o que
expunha um recuo pensado para ícone solto: era o "círculo do SmartThings encostado na borda". Agora
um círculo transparente fica transparente. O Quick Share do QuickShort é um tile grande comum, com
disco nativo da Samsung, por isso já estava alinhado.

Não há mudança de layout, `Modifier`, `Composer`, semântica, ordem de desenho ou gestos. Não há
desenho por frame nem região de blur do compositor: o blur é o do próprio painel Samsung. Por isso
não existe o "fantasma" de região de blur que ignora o alpha dos ancestrais (ver
`docs/architecture.md`), nem atraso de geometria na abertura, rolagem ou edição.

Ligar ou desligar a opção (ou o master) invalida o escopo de recomposição de cada
`BoxWithOutline` (`RecomposeScopeImpl.invalidateForResult(null)`, pois o `invalidate()` foi removido
pelo R8): a Central muda ao vivo. O escopo é o da própria caixa, capturado dentro do seu corpo (no
construtor do contorno), também com a opção ou o master desligados; invalidar o chamador não basta,
porque com os mesmos argumentos o Compose pularia a caixa. Tema claro/escuro é relido sempre que a
Samsung recompõe as cores. O recuo dos sliders vale a partir do próximo redesenho da barra.

## Falha fechada

- Qualquer membro ausente (`QS_COMPOSE_MISSING`, `QS_CLASS_MISSING`) deixa o painel nativo.
- Argumentos que a Samsung substitui pelos padrões (máscara `$default`, exceto o bit de
  `contorno`) ou cores fora de sRGB são ignorados (`QS_BOX defaults` / `QS_BOX non-srgb`).
- Sem o método de desenho dos sliders (`QS_SLIDER_MISSING`), as barras ficam com a trilha nativa.
- Uma exceção no hook (`QS_COMPOSE_FAILED`) desliga o material até o próximo reinício do SystemUI e
  invalida os escopos para restaurar as cores nativas.
- `surface.quicksettings.enabled` é **false por padrão** e fica subordinado ao master.

## Limites conhecidos

- O material é véu + contorno de luz basal sobre o blur que a Samsung já aplica ao painel. Não há
  blur próprio por elemento nem refração do conteúdo atrás dos controles.
- A luz que depende do fundo só existe com o compositor (perfil 3, acima). Só com o APK, a borda
  é apenas a luz basal uniforme. Os parâmetros do perfil 3 são constantes (`OULG_QS_*`), sem
  propriedades `debug.oulg.sf.*` até a validação no aparelho.
- Não validado no aparelho: a ordem entre as regiões da Central e o blur do próprio painel
  (se o blur da Samsung for desenhado depois, cobre a luz), o significado de `expansionFraction` e
  fades internos do Compose (modo de edição, detalhes), que o alpha da região não acompanha.
- O SmartThings (e outros tiles `DeviceControl`/`NearByDevice`/`MyDevice`) fica sem disco, como na
  Samsung; um disco com o mesmo recuo do Quick Share exigiria alterar o layout desses tiles.
- O player com mídia tocando (`MediaPlayerKt`) não passa por `BoxWithOutline`; só o estado vazio
  recebe o material.
- A cor do preenchimento dos sliders e os ícones permanecem os da Samsung.
- Os valores do material são constantes em `QuickSettingsGlass`. Expor como ajustes no app
  (catálogo, `Controls`, superfície própria) fica para depois da validação no aparelho.
- O hook altera chamadas de `BoxWithOutline` também no modo de edição e em pop-ups da Central que
  usem o mesmo componente; conferir na validação.

## Validação no aparelho

1. Instalar, ativar **Início → Ambientes → Controles rápidos** e reiniciar o SystemUI uma vez para
   carregar os hooks.
2. `logcat -d -s OULG | grep 'QS_'` deve conter `QS_COMPOSE_READY`, `QS_COMPOSE_ACTIVE` e linhas
   `QS_BOX` com `ACTIVE`, `IDLE` e `CONTAINER` (uma delas com `mask=2`, o grupo de tiles) e
   `QS_SLIDER_READY`. Sem `QS_BOX`, o caminho não foi atingido. Com o compositor v0.26:
   `QS_EDGE_READY`, `QS_EDGE_PANEL host=true expansion=true` e `QS_EDGE_REGION ... expansion=1.0`
   com a Central aberta. `setprop debug.oulg.sf.debug 1` antes de reiniciar o compositor pinta as
   regiões marcadas de magenta: com o perfil 3, só a faixa da borda deve aparecer.
3. Abrir/fechar a Central 20 vezes, devagar e rápido; alternar Wi-Fi/Bluetooth e tiles pequenos;
   arrastar brilho e volume; mídia vazia e tocando; modo de edição; tema claro/escuro; rotação.
4. Desligar a opção com a Central aberta: tudo deve voltar às cores Samsung
   (`QS_COMPOSE_REFRESH reason=off scopes=N`, N > 0).
5. Coletar screenshots claro/escuro e o log acima.

## Rollback

Desligar **Controles rápidos** (ou o master). Se o provider não responder, desativar o módulo no
LSPosed e reiniciar o SystemUI. Nada é gravado em partições do sistema.
