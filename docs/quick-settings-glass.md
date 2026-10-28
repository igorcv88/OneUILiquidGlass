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
   (`QuickSettingsGlass.kind`): círculo ativo (fundo com alpha ≥ 0,5), círculo inativo, contêiner
   visível (fundo ou contorno não transparente) ou nada (área transparente de tile de uma célula);
2. registra para a mesma thread os dois elementos que a função cria em seguida;
3. no construtor de `BorderModifierNodeElement`, troca o `SolidColor` do contorno por um
   `LinearGradient` branco (borda superior-esquerda clara, meio tênue, inferior-direita suave);
4. no construtor de `BackgroundElement`, troca a cor de fundo pelo véu: branco translúcido em
   contêineres e círculos inativos, a própria cor da Samsung com alpha ≤ 0,90 nos ativos.

Não há mudança de layout, `Modifier`, `Composer`, semântica, ordem de desenho ou gestos. Não há
desenho por frame nem região de blur do compositor: o blur é o do próprio painel Samsung. Por isso
não existe o "fantasma" de região de blur que ignora o alpha dos ancestrais (ver
`docs/architecture.md`), nem atraso de geometria na abertura, rolagem ou edição.

Um círculo inativo tem fundo transparente e a Samsung não cria `BackgroundElement` para ele; o hook
passa um marcador de alpha 0 (`QuickSettingsGlass.IDLE_MARKER`) para que o elemento exista e seja
recolorido. Se o marcador escapasse, não desenharia nada. Ele é removido quando a lambda de
reinício da Samsung o devolve ao hook.

Ligar ou desligar a opção (ou o master) invalida os escopos de recomposição dos chamadores
capturados (`RecomposeScopeImpl.invalidateForResult(null)`, pois o `invalidate()` foi removido pelo
R8): a Central muda ao vivo. Tema claro/escuro é relido sempre que a Samsung recompõe as cores.

## Falha fechada

- Qualquer membro ausente (`QS_COMPOSE_MISSING`, `QS_CLASS_MISSING`) deixa o painel nativo.
- Argumentos que a Samsung substitui pelos padrões (máscara `$default`) ou cores fora de sRGB são
  ignorados (`QS_BOX defaults` / `QS_BOX non-srgb`).
- Uma exceção no hook (`QS_COMPOSE_FAILED`) desliga o material até o próximo reinício do SystemUI e
  invalida os escopos para restaurar as cores nativas.
- `surface.quicksettings.enabled` é **false por padrão** e fica subordinado ao master.

## Limites conhecidos

- O material é véu + contorno óptico sobre o blur que a Samsung já aplica ao painel. Não há blur
  próprio por elemento nem refração do conteúdo atrás dos controles.
- O player com mídia tocando (`MediaPlayerKt`) não passa por `BoxWithOutline`; só o estado vazio
  recebe o material.
- O preenchimento branco dos sliders e os ícones permanecem os da Samsung.
- Os valores do material são constantes em `QuickSettingsGlass`. Expor como ajustes no app
  (catálogo, `Controls`, superfície própria) fica para depois da validação no aparelho.
- O hook altera chamadas de `BoxWithOutline` também no modo de edição e em pop-ups da Central que
  usem o mesmo componente; conferir na validação.

## Validação no aparelho

1. Instalar, ativar **Início → Ambientes → Controles rápidos** e reiniciar o SystemUI uma vez para
   carregar os hooks.
2. `logcat -d -s OULG | grep 'QS_'` deve conter `QS_COMPOSE_READY`, `QS_COMPOSE_ACTIVE` e linhas
   `QS_BOX` com `ACTIVE`, `IDLE` e `CONTAINER`. Sem `QS_BOX`, o caminho não foi atingido.
3. Abrir/fechar a Central 20 vezes, devagar e rápido; alternar Wi-Fi/Bluetooth e tiles pequenos;
   arrastar brilho e volume; mídia vazia e tocando; modo de edição; tema claro/escuro; rotação.
4. Desligar a opção com a Central aberta: tudo deve voltar às cores Samsung
   (`QS_COMPOSE_REFRESH reason=off scopes=N`, N > 0).
5. Coletar screenshots claro/escuro e o log acima.

## Rollback

Desligar **Controles rápidos** (ou o master). Se o provider não responder, desativar o módulo no
LSPosed e reiniciar o SystemUI. Nada é gravado em partições do sistema.
