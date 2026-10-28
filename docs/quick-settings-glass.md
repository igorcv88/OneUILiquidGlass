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
- **A luz do contorno não depende do fundo.** Este caminho só troca cores no Compose; o processo do
  SystemUI não lê os pixels atrás da própria janela. As opções para uma borda que responda ao
  fundo trecho a trecho são o shader do compositor (`sfhook`, add-on KernelSU, por pixel e sem
  atraso, mas exige regiões de blur por contêiner) ou a captura de tela (`CaptureHub`, só APK, mas
  com atraso em movimento e custo contínuo enquanto a Central está aberta). Nenhuma foi feita.
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
   `QS_SLIDER_READY`. Sem `QS_BOX`, o caminho não foi atingido.
3. Abrir/fechar a Central 20 vezes, devagar e rápido; alternar Wi-Fi/Bluetooth e tiles pequenos;
   arrastar brilho e volume; mídia vazia e tocando; modo de edição; tema claro/escuro; rotação.
4. Desligar a opção com a Central aberta: tudo deve voltar às cores Samsung
   (`QS_COMPOSE_REFRESH reason=off scopes=N`, N > 0).
5. Coletar screenshots claro/escuro e o log acima.

## Rollback

Desligar **Controles rápidos** (ou o master). Se o provider não responder, desativar o módulo no
LSPosed e reiniciar o SystemUI. Nada é gravado em partições do sistema.
