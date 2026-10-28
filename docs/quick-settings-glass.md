# Controles rápidos — Liquid Glass (fase inicial)

Referência visual: cápsulas Wi-Fi/Bluetooth, círculos compactos, sliders de brilho/volume e
bloco de mídia sobre um backdrop borrado, com borda óptica fina e controles ativos mais claros.
**Não** é uma substituição do layout One UI, nem uma cópia de ícones, agrupamentos ou gestos.

## Escopo do patch

- `QuickSettingsHooks` procura `SecQSTileView`/`SecQSTileBaseView`, escuta o estado nativo dos
  tiles e coloca o material em `mBg` (círculos) ou no próprio tile (cápsulas grandes).
- Em barras reconhecidas (`BrightnessBar`, `VolumeBar`, `QSMediaPlayerBar`, `MediaDevicesBar`),
  só a view raiz já inflada recebe o material. Uma classe/método ausente é ignorado.
- `QuickSettingsMaterial` reutiliza `BackgroundBlurBridge` e `GlassDrawable`: blur compositor
  ao vivo, corpo translúcido, highlights e contorno. Não captura a tela, não usa hooks de
  `SurfaceFlinger`, não altera layout, filhos, ícones, acessibilidade ou ouvintes de toque.
- O `RippleDrawable` nativo das cápsulas permanece por cima com alpha reduzido; no círculo
  compacto, o preenchimento Samsung ativo/inativo continua, com alpha moderado. O material
  restaura background e alpha ao sair da árvore ou desligar a opção.
- A preferência `surface.quicksettings.enabled` é **false por padrão**, diferente das três
  superfícies de notificações. A chave é transmitida pelo provider já existente; o switch
  aparece em **Início → Ambientes → Controles rápidos**.

## Limites conhecidos

- Os nomes e campos foram cruzados com fontes Samsung SystemUI anteriores. A implementação
  **não foi validada** contra o dump específico do SystemUI One UI 9 do Galaxy S25 Ultra.
  `QS_READY`, `QS_HOOK`, `QS_CLASS_MISSING`, `QS_GLASS` e `QS_GLASS_FALLBACK` permitem verificar
  quais caminhos existem e quais foram usados sem mudar mais componentes.
- O material não rearranja o painel para replicar a imagem. Um fundo opaco desenhado **dentro**
  de uma barra ou tile pelo firmware pode cobrir parte do vidro. Os seletores de barra são
  intencionalmente conservadores; não se intercepta `View.draw()` ou `View.setBackground()`
  globalmente.
- Compositor blur real e bordas iluminadas **não equivalem** a refração do conteúdo atrás dos
  tiles. APK-only não deve prometer deslocamento óptico cross-window.

## Validação no aparelho

1. Antes de ativar, registrar a Central com a mesma imagem de fundo, em tema claro e escuro.
   Confirmar pop-up, bloqueio e central de notificações como controle.
2. Ativar apenas **Controles rápidos**, fechar e reabrir o painel. Conferir Wi-Fi/Bluetooth,
   tiles pequenos, brilho, mídia, estados ligado/desligado, toque longo, deslize e TalkBack.
3. Repetir alternando tema, rotação, tela bloqueada/desbloqueada, modo edição de tiles,
   pop-up de brilho, painel dividido e economia de energia/blur desativado.
4. Confirmar que cada controle volta à aparência nativa em tempo real ao desligar o switch;
   abrir/fechar o painel 20 vezes e observar blur residual, travamento e queda de FPS.
5. Coletar `logcat -d -s OULG | grep 'QS_'` e screenshots. Se `QS_CLASS_MISSING` ou nenhum
   `QS_GLASS` aparecer, inspecionar classes/árvore no firmware **antes** de ampliar hooks.

## Rollback

Desligar **Controles rápidos** (ou o master). Se o provider não responder, desativar o APK no
LSPosed e reiniciar o processo SystemUI. Nenhuma modificação persiste em partições do sistema.
