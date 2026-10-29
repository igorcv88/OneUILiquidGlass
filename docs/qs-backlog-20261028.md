# Pendências OneUILiquidGlass, a partir do main pós-PR #83

## Evidência DZIF do PR #84 (28/10, 23:26–23:30)

- Diagnóstico complementar do usuário: desligar `debug.oulg.shadeblur` tornava inoperante
  `shadelightglass`. Confirmado no código: `SharedBackdrop` descartava `measuredVeil` porque
  `pushToneVeil` somente notificava `compBridge` ou `semBridge`, ausentes no blur compartilhado.
  A correção pinta a cor clara diretamente na cápsula, sem blur por cartão, substituindo o
  preenchimento fixo para não somar dois véus brancos. Novo log: `SHADE_SHARED_DRAW`.

- O arquivo `OULG_PR84_DIAG_20261028_232634.tar.gz` registrou todas as nove etapas. A propriedade
  bruta `debug.oulg.shadelightglass=0.34` ficou estável, mas o **Tuning efetivo** passou por
  `shadelightglass=0.4` (23:28:45), `0.35` (23:29:15) e `0.4` (23:29:34), confirmando
  entrega via `ConfigClient` e prioridade do valor salvo no app. A propriedade bruta não é
  o valor efetivo; a UI anterior dava a entender que era.
- O PR #85, mesclado, passou a invalidar as notificações após a publicação do `Tuning` e a
  desenhar o véu claro em `SharedBackdrop` com `shadeblur=0`. O usuário confirmou visualmente a
  resolução, sem necessidade de ativar blur por cartão.
- O QS desenhou labels com `finalTone=ff1d1d1d` e manteve óptica (`QS_OPTICS_DRAWN`). A origem
  de ambos os sliders permaneceu `role=unknown` (pilha R8), e **o arraste do volume também
  disparou `QS_EDGE_SUPPRESS`**. O volume continua pendente: não inferir sua identidade pelo
  `DrawScope`, que é compartilhado. A próxima sondagem registra também a assinatura real e os
  argumentos tipados de `BidirectionalBar` (`QS_SLIDER_BAR_ABI` / `QS_SLIDER_BAR_ARGS`) para
  distinguir as barras sem depender da pilha R8.
- Além dos `CONTAINER`, o log de 23:29:40 já contém círculos `ACTIVE` com `bg=ffffffff`
  e `NONE` com `bg=0` ou `234b60`. A ausência de `IDLE` não prova sua inexistência; faltava
  associar os círculos aos modelos e estados reais dos tiles. A próxima sondagem registra
  o `tileModel` ativo, o owner da composição, os campos tipados dos modelos e os nós de pintura
  dos ícones. Todos os valores de texto/SSID continuam excluídos. Não mudar cor por heurística.
- `QS_CLOCK_CANDIDATE role=unbound` ainda aparece: a hora visível não está vinculada ao nó de
  cabeçalho. A próxima coleta inclui a origem de pilha sem texto para localizar o desenho.
  A transição do pop-up foi requisitada com `rampMs=180`, sem confirmação visual.

Revisão 28/10/2026 (horário de Fortaleza). Este documento é o checklist acumulado do relato do usuário;
não confundir implementação de diagnóstico com correção visual validada no Galaxy S25 Ultra
DZIF (Android 17). Base aprovada: efeito óptico atual das cápsulas QS sobre fundo colorido/preto,
player, notificações transparentes sobre colorido/preto e ausência de fantasmas no arraste do
brilho. Preservar `qs.fill=0.85` e `qs.lightfill=0.05`; não escurecer cartões da shade branca.

## Implementado neste branch, ainda pendente de validação no DZIF

- [x] Sondagem **somente leitura** de funções de composição QuickTile/IconTextTile: assinaturas,
  tipos dos argumentos, booleanos e cores, sem nomes de apps/SSID nem texto.
- [x] Sondagem de cores e formas de entrada do `BoxWithOutline`, inclusive candidatos a discos,
  sem alterá-las.
- [x] Classificação conservadora por origem do slider (`brightness`, `volume`, `unknown`), com
  logs limitados; quando comprovadamente volume, não suprime outras lentes. Origem desconhecida
  mantém a lógica existente para não regredir o brilho. A distinção depende do stack real.
- [x] Invalidar desenho/recomposição ao receber **qualquer revisão** nova de configuração,
  mesmo sem alteração nos números de `QsStyle`.
- [x] Redesenho de QS após a **publicação assíncrona efetiva de `Tuning`** na thread de
  configuração; evita redesenhar apenas com valores antigos e esperar trocar de cena depois.
- [x] Trilha `CONFIG_RECEIVED` → `CONFIG_DISPATCH` → `QS_CONFIG_RECEIVED` →
  `TUNING_DISPATCH` → `QS_TUNING_APPLY` → `QS_COMPOSE_REFRESH` → `QS_CONFIG_RENDER`
  para medir recepção versus pintura efetiva do texto.
- [x] Protocolo interativo com leitura do `/dev/tty`, Enter para avançar, `p` para pular,
  Ctrl+C para empacotar, `trace` temporário restaurado e um único `.tar.gz` em Downloads.

## Prioridade alta, requer capturas reais do APK desta branch

- [ ] **15 discos e ícones de Quick Settings**: correlacionar `QS_TILE_BIND` com controles
  ligados/desligados no fundo claro e escuro. Implementar adaptação de *fundo do disco* e
  *cor do ícone* para cada estado e cena, respeitando a cor de destaque desejada. Não alterar
  ícones por tentativa nem herdar automaticamente as cores do Theme Park.
- [ ] **Volume não deve esconder lentes alheias**: confirmar se a origem é identificada em
  ambos os sliders; se `unknown`, descobrir o owner Compose real e associar interação à barra,
  sem suprimir a do brilho.
- [ ] **Labels e cabeçalho**: confirmar cor final em `QS_CONFIG_RENDER` e visual; manter alto
  contraste no fundo branco, texto claro em fundo escuro, sem cinza de baixo contraste.
- [ ] **Relógio da central de notificações**: identificar a instância efetivamente desenhada
  e seu caminho de cor; não confundir com o relógio da central de controle ou View legado.
  Decidir entre tinta adaptativa e relógio/cabeçalho fixos brancos com toggle independente.
- [ ] **Lápis, energia e engrenagem do QS**: identificar os ícones próprios do cabeçalho e
  dar toggle separado da adaptação dos ícones dos 15 tiles; opção para ficarem brancos sempre.
- [ ] **Propagação ao vivo dos ajustes**: medir tempo desde geração/observador do provider
  até render do nó e pixel visível; investigar `Tuning` assíncrono, draw cache e refresh que
  atualiza só depois de trocar a cena ou reiniciar SystemUI. Corrigir rótulos de “imediato”
  enquanto o comportamento não for imediato.
- [ ] **Notificações sobre fundo branco**: aumentar levemente a luminosidade/branco dos cartões
  (comparar print de ~20h com atual) sem aumentar efeito “radioativo”, sem escurecer e sem alterar
  a shade sobre fundo colorido/preto. Isolar controle por cena.
- [ ] **Pop-up heads-up**: restaurar transição gradual e curta de tinta/vidro que existia
  antes da regressão, sem clique abrupto e com baixo atraso quando fundo muda de branco para
  colorido e vice-versa. Capturar `POPUP_TRANSITION` e vídeo com eventos temporais.

## Prioridade média, implementação depois da identificação de classes

- [ ] **Slider de brilho e volume em baixíssimo percentual**: evitar a base retificada / ponta
  semipiramidal, preservar semicírculo suave e zero verdadeiramente vazio, testar 0/1/5/50/100%.
- [ ] **Controles granulares na interface**: separar modo claro vs escuro/colorido, ligado vs
  desligado, disco vs ícone vs label; distinguir blur do painel, blur da cápsula, shade/tinta,
  borda e luz sem expor jargão interno aos usuários. Tornar organização e nomes compreensíveis.
- [ ] **Tema e override Theme Park**: localizar origem nativa e prioridade do Theme Park;
  determinar quais propriedades devem ser reaplicadas em recomposição vs apenas no evento.
- [ ] **Live updates não-mídia**: manter vidro quando expandidas e imóveis na área superior
  esquerda; hoje só aparecem transparentes durante o gesto e depois volta a superfície nativa.
  Identificar criação, rebind, overlay/SurfaceControl e transições. Preservar mídia atual.
- [ ] **Toasts**: vidro aparece na criação e a Samsung repõe toast cinza; identificar View
  e compositor definitivos e manter o estilo durante todo o ciclo de vida.

## Depois da estabilização

- [ ] **Live updates de aplicativos não-mídia na lockscreen**: cápsula inferior com vidro
  permanente, sem comprometer toque, desbloqueio e segurança.
- [ ] Avaliar mídia nas live updates/lockscreen somente depois dos aplicativos não-mídia.
- [ ] Validar consumo de reamostragem/performance, ausência de crashes SystemUI, rebinds
  após troca de app/tema e custo dos hooks com `debug.oulg.trace=0`.

## Critérios de aceite

1. Testes unitários, lint e APK, mas **sem declarar sucesso visual com base neles**.
2. Diagnóstico DZIF: hook encontrado, argumento, estágio de composição e draw real.
3. Fotos comparáveis claro/colorido/preto e gravação curta para latência/lifecycle.
4. Confirmar ausência de alterações indevidas em player, vidro do QS escuro/colorido,
   shade escura/colorida e fantasma do brilho antes de merge.
