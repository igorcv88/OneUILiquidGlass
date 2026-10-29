# Pendências OneUILiquidGlass, a partir do main pós-PR #83

## Diagnóstico complementar pós-PR #91 (29/10, PR seguinte)

- [x] **Rastreio de camadas da shade implementado em código:** `SHADE_LAYER_UPDATE`
  registra o papel (normal/dimmed/other) e drawable de cada fundo após atualização;
  `SHADE_LAYER_DRAW` registra qual `NotificationBackgroundView` realmente entrou no
  `onDraw`, a ordem, alpha, visibilidade, estado do vidro e se o frame foi renderizado
  pelo vidro ou pela Samsung. Amostra inicial e mudanças são capturadas com limite
  temporal; somente `trace=1`. Dois testes unitários do classificador e limitador passam.
- [x] **Origem da paleta dos discos instrumentada:** `QS_PALETTE_EFFECTIVE` informa
  revisão do provider, revisão do estilo, override bruto de `qs.tilelighton`, fonte
  (default/provider) e RGBA efetivo das tintas de discos, ícones e sliders. Isso permite
  confrontar `QS_TILE_COLOR applied=ff000000` com a configuração realmente recebida.
- [ ] **Shade Theme Park OFF e notificação ao vivo:** no APK pós-PR #90, o usuário
  reproduziu cartões que piscam, perdem o vidro ou ficam roxos quando chegam/atualizam
  notificações, tanto na shade padrão como com notificação ao vivo. O padrão visual
  sugere um mecanismo comum, **ainda não comprovado**. Capturar com `trace=1` e correlacionar
  `SHADE_LAYER_UPDATE`/`SHADE_LAYER_DRAW` com `LIFECYCLE`, `GLASS_APPLIED`,
  `SHADE_MATERIAL_DECISION` e `BACKGROUND_UPDATE`, comparando com Theme Park ON.
- [ ] **Discos QS ativos pretos intermitentes:** foram vistos Wi-Fi/Bluetooth pretos
  após abrir o QS, recuperando a tinta durante o fechamento parcial. O diagnóstico
  anterior tinha `QS_TILE_COLOR ... applied=ff000000` para cena clara, sem origem
  do override efetivo. Comparar agora `QS_PALETTE_EFFECTIVE` com `QS_TILE_COLOR`,
  `QS_TONE_ENTRY` e `TONE_COMMIT` em abertura direta e shade→QS. Não trocar paleta
  aprovada nem forçar recoloração de modelos não vinculados antes dessa correlação.
- [ ] **Validação real:** compilar APK, confirmar a cobertura dos novos eventos na
  SystemUI do S25 Ultra DZIF; repetir Theme Park OFF/ON e notificação ao vivo,
  depois reportar se a causa é camada nativa sobreposta, perda do GlassDrawable,
  configuração de paleta ou outro caminho de desenho.

O protocolo de reprodução e os comandos para coletar o diagnóstico serão entregues
diretamente na conversa; este arquivo acompanha somente o estado das pendências.

## Depois do PR #89: instrumentação e ajustes preparados em 29/10

Estado de cada linha: **código/teste local** não equivale à validação visual no S25 Ultra DZIF. O PR permanece em revisão até a coleta física.

- [x] **Painel nativo, controle independente em código:** `panelblurcap` limita os 250 px observados sem Theme Park a 52 px por padrão (ou `-1` para seguir a Samsung); `panelnativegray=0` remove `mBGColorAdjustment`; `panelcolor` adiciona tinta RGBA sem mexer nas notificações; `PANEL_BLUR_POLICY` mede valores nativos/aplicados. Teste puro do limite passou. **Pendente:** verificar via Theme Park ON/OFF, abrindo/fechando painel e edição ao vivo. Evitar afirmar remoção visual definitiva se a Samsung desenhar uma camada cinza fora de `SemBlurInfo`.
- [x] **Color picker em código:** HSV com quadrado saturação/luminosidade, matiz, barra de alpha e campo `RRGGBB/RRGGBBAA`; cada `Controls.Format.COLOR` usa o mesmo seletor. Alpha propagado a discos, ícones, cor do vidro claro, cor do painel e cores separadas do preenchimento dos sliders `qs.sliderlightcolor/qs.sliderdarkcolor`. Testes puros de parser e paleta passaram. **Pendente:** compilação/uso táctil real e validar salvamento, preview e transparência.
- [x] **Sliders em código:** corpo abaixo de um diâmetro encolhe nos dois eixos, sem oval achatada; sem disco residual em 0. `QS_SLIDER_GEOMETRY` registra tamanho final/offset nativo em 0–12% e 95–100%, `QS_SLIDER_COLOR` confirma tinta RGBA, e `QS_SLIDER_ICON_ROUTE` sonda os nós Painter de brilho/volume sem alterar ícones desconhecidos. Testes Java geométricos passaram. **Pendente:** verificar 0, 1, 5, 50, 99 e 100%, duas orientações e ícones visíveis.
- [x] **Reflexo secundário em código:** `qs.glint=0` desliga apenas o brilho AGSL extra no canto superior esquerdo; `QS_EDGE_LAYER_STACK` registra luz AGSL, contorno Compose e participação do compositor para distinguir sobreposição real. **Pendente:** confirmar que a linha duplicada desaparece e preservar a borda principal.
- [x] **Transições em código:** rampa QS de 180 ms em cena/cabeçalho quando a troca entre dois tons é confirmada; duração do `InkAnimator` View igualada; `QS_INK_TRANSITION`, `INK_ANIMATOR`, `POPUP_TRANSITION` e `TEXT_REAPPLY` diferenciam pedido de animação, início, cancelamento, conclusão e rebind. **Pendente:** ver pop-up/QS no aparelho e verificar supressão em gestos cancelados.
- [x] **Rota de ícones pequenos instrumentada:** `QS_TILE_ICON_ROUTE` registra para cada Painter modelo, estado, cena, revisão e motivo de não elegibilidade; `QS_TILE_ICON_INK` passa a registrar também a mudança de ACTIVE para IDLE e o desenho Canvas, sem texto ou SSIDs. **Pendente:** associar ícones pequenos não vinculados ao modelo real sem heurística; confirmar se algum usa shader/Canvas alternativo.
- [x] **Notificações customizadas instrumentadas:** `CUSTOM_TEXT_ROUTE` relata elegibilidade, cor atual e fundo próprio para textos RemoteViews desconhecidos, preservando conteúdo textual e sem alterar a decisão de cor. **Pendente:** texto da Drogasil com fundo branco, coleta real.
- [ ] **Cobertura não comprovada pelo telefone:** emissor de cinza fora de `SemBlurInfo`, APIs de ícones que não passam por Painter/Canvas, cabeçalho do relógio não vinculado, toast refeito pela Samsung, atualização ao vivo da lockscreen e notificação customizada desenhada em Canvas próprio. A instrumentação marca os caminhos conhecidos e seus pontos de saída; ausência de evento de um caminho alternativo ainda exige captura estrutural do DZIF antes de criar um hook.
- [ ] **Revisão física/CI:** PR novo e build Android, Theme Park ON/OFF, shade→QS e abertura direta, fundo branco/colorido/preto, sliders extremos, abertura/fechamento de HUN e custom notification, review comments pendentes. A correção do fantasma na shade foi relatada como resolvida; monitorar regressão sem refazê-la.
- [ ] **UI futura:** página própria Central de controle com previews reais, favoritos acessíveis na inicial, inglês padrão + português alternável, controles de ícones do cabeçalho e dos sliders quando seus donos forem comprovados; nomes de superfície claros, sem o rótulo “Controles rápidos”.

## Após merge #88: coleta com Theme Park ON/OFF e build falho (29/10, 09:48–09:49)

- [x] **Falha de compilação #88, corrigida em código:** `QuickSettingsHooks` (pacote
  `hooks`) acessava `QuickSettingsGlass.CLEAR` (pacote `glass`), que tinha
  visibilidade de pacote. Constante tornada `public`; teste do pacote consumidor
  impede voltar a compilar apenas os testes locais e falhar no Android. O erro
  concreto foi enviado pelo usuário; validar compilação Android no CI do novo PR.
- [x] **Regressão da shade sem Theme Park, causa encontrada e correção proposta:**
  `GLASS_APPLIED` ocorre tanto com Theme Park desligado quanto ligado, com
  `Backdrop=SHARED`. Contudo, o drawable `SeslRecoilDrawable` nativo tem alpha
  estável **77/255** sem Theme Park e **255/255** ligado. `GlassDrawable.configure`
  copiava esse alpha para o próprio vidro, deixando-o 70% mais fraco no primeiro
  caso; a shade compartilhada agora usa alpha 255 em repouso e o alpha do
  conteúdo apenas durante saída comprovada. Teste unitário cobre 77, 56, 255 e
  0 de alpha nativo; validação visual ainda pendente.
- [x] **Overlay realmente muda:** `cmd overlay list` contém
  `android:ThemePark_NotificationPanel` e `android:ThemePark_dummy` só após religar
  Theme Park. Não há evidência de mudança da classe da notificação; os mesmos
  IDs recebiam `GLASS_APPLIED` em ambos os cenários.
- [x] **Fantasma no gesto cancelado não reproduzido pelo usuário no APK #87:**
  na etapa de 09:49:41–09:49:54, `QS_EDGE_EXIT` registra
  `regionsBefore=8 regionsAfter=0 alphaPositiveAfter=0`. Não atribuir essa
  resolução à PR #87 sem um teste A/B; manter observação como regressão.
- [ ] **Aparelho:** verificar shade com Theme Park OFF/ON em fundo claro e
  colorido após o novo APK; comparar `SHADE_SHARED_ALPHA native=77 glass=255`
  versus `native=255 glass=255`. Confirmar remoção/fade dos cartões para evitar
  que o vidro sobreviva ao conteúdo.
- [ ] **Discos pequenos e sliders:** coleta ainda mostra `tileModel=unbound`,
  `state=unverified` e várias ópticas `fallback` para nós Compose. Não aplicar
  paleta a círculos sem identificação de classe/estado, nem afirmar correção dos
  ícones de brilho/volume apenas porque a barra foi desenhada.


## PR #88: Theme Park desligado e paleta de controles, relatos 29/10 09:23–09:26

- [x] **Correção do review P2 do #88, testes locais:** a rampa de `SharedBackdrop`
  passa a usar `shadeDarkFill`/`shadeLightFill`, que são as tintas efetivamente
  desenhadas pelo corpo nativo, nos endpoints; `nativeTint` continua para o blur
  quando ele existe. Teste cobre a continuidade de ida e volta. Validar no DZIF.
- [x] **Discos desligados sem fundo (contrato e código):** `qs.hideoffdiscs=1`
  remove somente fundo e contorno do círculo `IDLE` com `QuickTile` identificado,
  em cenas claras e escuras. Um `NONE` verdadeiro, as cápsulas médias e os estados
  `UNKNOWN` permanecem nativos. Desligar esse toggle libera a cor/opacidade de
  discos desligados. Testes unitários cobrindo estados e preservação de conteúdos.
  **Ainda não comprova** os discos pequenos que não vinculam modelo.
- [x] **Acesso aos controles existentes na UI (código):** grupos de discos,
  ícones e opacidade por cena aparecem no bloco da Central de controle; termo
  `Controles rápidos` removido dos pontos principais. Sem preview QS dedicado.
- [ ] **Tema Samsung sem Theme Park (regressão alta):** prints mostram cartões
  cinza/azul opacos, tanto em fundo branco quanto colorido, enquanto lentes QS
  seguem ativas. Comparar com Theme Park ligado os caminhos `GLASS_APPLIED`,
  `NATIVE_FALLBACK`, `SHADE_MATERIAL_DECISION`, `DRAW_HOOK_CALLED`, `LIFECYCLE`
  e as sobreposições (`cmd overlay list`). Não presumir que a classe mudou.
- [ ] **Classificação de discos com Theme Park desligado:** hoje a distinção
  ACTIVE/IDLE ainda usa alpha do preenchimento. Um disco cinza opaco que
  representa controle desligado pode ser classificado ACTIVE. O novo toggle
  só remove IDLE; precisamos do estado real do modelo para estender a mudança
  aos pequenos sem alterar por engano os ligados.
- [ ] **Sliders sem Theme Park:** os ícones de brilho e volume ficam quase
  invisíveis sobre preenchimento branco. Precisam de tinta estável por estado/cena,
  independente da composição da barra, sem modificar geometria ou porcentagem.
- [ ] **Controle de cor RGBA real:** seletor HSV (barra de matiz + quadrado
  saturação/luminosidade + barra alpha) no editor, inclusive opacidade dos
  discos ligados e preenchimento dos sliders; cor e alpha por cena e estado.
  Atualmente somente discos desligados possuem alpha independente, via slider.
- [ ] **Página exclusiva Central de controle:** preview realista dos discos,
  cápsulas pequenas/médias, sliders e painel nos fundos claro/colorido/preto,
  pois a prévia atual de Central mostra notificações. Organizar parâmetros e
  dependências sem expor nomes internos na tela principal.
- [ ] **Inglês como idioma padrão + português selecionável:** extrair todas
  as strings para recursos e implementar configuração de idioma, preservando
  o português como opção completa. Não traduzir apenas nomes de menus.
- [ ] **Reflexão dupla no canto superior esquerdo de todas as cápsulas QS:**
  observada também em capturas antigas; investigar sobreposição da borda Compose,
  `lightoutline`, luz AGSL e/ou amostragem do compositor. Não reduzir globalmente
  a luz sem identificar a camada redundante, para preservar colorido/preto.


## Revisões dos PRs #85, #86 e #87 (29/10)

- [x] **Review P2 do #85** (`SharedGlassFill`): durante a interpolação, a tinta
  compartilhada segue todos os frames, independentemente da troca de `lightStyle` no
  ponto médio; fora da rampa mantém a distinção entre material nativo e claro.
  Regressão coberta por teste unitário; requer verificação visual no DZIF.
- [x] **Review P2 do #86** (`QsTileProbe`): a deduplicação inclui identidade da instância
  e estado, de modo que tiles distintos com os mesmos booleanos não percam snapshots.
  Teste unitário confirma privacidade e chaves diferentes.
- [ ] **Review do #87**: ainda em execução quando consultado; não havia comentários
  técnicos publicados. Reavaliar se aparecerem novos apontamentos.

## Evidência do APK #87, coleta 29/10 08:51–08:53

- [x] **Falso arraste do brilho durante volume, causa comprovada:** os dois sliders usam
  o mesmo `DrawScope` (`id=d2f618a`). Às 08:52:14, o volume mudou de ~49% a 100%,
  enquanto o brilho permaneceu em ~61,97%; porém o mapa de comprimentos indexado
  somente por scope comparou o último valor do volume com o do brilho e disparou
  `QS_EDGE_SUPPRESS ... released=8` no teste de volume. Histórico separado por
  **scope + role** e coberto por testes; confirmar no próximo APK. Ainda pode haver
  mudança real automática de brilho enquanto outro controle recebe toque.
- [ ] **Discos pequenos:** somente três identidades distintas aparecem em
  `QS_TILE_COLOR`, todas `ACTIVE`, enquanto `QS_TILE_BIND` identificou quatro modelos
  de `QuickTile` no trecho coletado; o quarto pode não ter disco. A instrumentação
  existente é limitada por amostragem, não prova a identidade de cada um dos 15
  pequenos. Coletar `QS_TILE_COLOR_DECISION` do #88 para identificar os não vinculados.
- [ ] **Shade → QS:** esta sessão contém etapas colorido, branco, brilho, volume e
  ajuste ao vivo, mas não marca separadamente a transição horizontal shade → QS.
  Testá-la com `QS_TONE_ENTRY`, `TONE_CAPTURE_DONE source=...` e
  `QS_TILE_COLOR_DECISION` no #88.
- [ ] **Luz no fundo branco:** vídeo do #87 confirma contornos e interiores claros de
  contraste desigual; `lightoutline` entra no shader apenas em LIGHT_SCENE. Separar
  visualmente ganho do shader e opacidade do interior antes de alterar a óptica aprovada.

## Próxima mudança após a coleta QS #86 (29/10, 07:37–07:39)

- [x] Ligado/desligado do slider distinguido pelos callbacks tipados `BrightBarKt` e
  `VolumeBarKt` em `BidirectionalBar` (argumento 12); identidade propagada para a lambda
  diferida de desenho, em vez de associada ao `DrawScope` compartilhado. Falta confirmar em
  aparelho `QS_SLIDER_STATUS role=brightness/volume` e volume sem `QS_EDGE_SUPPRESS`.
- [x] Discos dos QuickTiles: no DZIF, o estado desligado sem alpha usa a sentinela
  `bg=outline=00234b60` e o item *sem disco* usa `bg=outline=0`. Paleta independente no
  fundo claro para ligados/desligados, com separação de tinta dos ícones; modo escuro/colorido
  opt-in desligado para preservar a estética aprovada. Só vinculamos o disco e o ícone ao
  mesmo modelo identificado por `QsTileProbe`; os ícones não vinculados não mudam.
  **Ainda não validado no aparelho**: `QS_TILE_COLOR` e `QS_TILE_ICON_INK` devem mostrar
  aplicação e contraste real em cada estado.
- [x] `trace` agora aparece na página inicial como toggle de diagnóstico; o estado solicitado
  vem do app e a legenda diferencia espera de confirmação do SystemUI. A propriedade bruta
  `getprop debug.oulg.trace` pode continuar `0` enquanto o snapshot efetivo é `1`.
- [x] Catálogo e matriz de controles regenerados: 135 chaves no total. Preservados os valores
  de `qs.fill`, `qs.lightfill` e todos os parâmetros ópticos existentes.

### Pendências seguintes (não corrigidas por essa mudança)

- [ ] **PR #87 no aparelho, paleta parcial**: relato de 29/10 indica adaptação em alguns
  tiles grandes (Wi-Fi e controles inferiores), mas nenhum dos pequenos. Correlacionar
  `QS_TILE_SURFACE_COMPOSE tileModel=unbound`, `QS_TILE_MODEL`, `QS_TILE_COLOR` e
  `QS_TILE_ICON_NODE` para distinguir círculo sem disco, modelo perdido em lambda Compose
  e classificação incorreta; não recolorir círculos não verificados por heurística.
- [ ] **Entrada shade → QS não adapta as cores**: abertura direta adapta ao menos alguns
  controles, mas a transição horizontal a partir da central de notificações não. Comparar
  `TONE_CAPTURE_REQUEST/DONE`, `TONE_COMMIT`, `QS_RESAMPLE` e `QS_TILE_SURFACE_COMPOSE`
  nos dois caminhos para separar captura da cena, atualização e vínculo do tile.
- [ ] **Sliders sem adaptação de cor à cena**: verificar papel de brilho/volume, cor efetiva
  da primitiva e desenho no `LIGHT_SCENE`, sem alterar geometria/zero aprovado.
- [ ] **Fantasma de contornos no gesto horizontal abortado**: no build anterior ao PR #87,
  ao arrastar parcialmente a shade em direção ao QS e soltar para voltar à shade, aparecem
  contornos ópticos de cápsulas QS que não ficaram visíveis. Captura em vídeo de 29/10;
  distinguir resíduo durante a animação de região persistente após o cancelamento. Verificar
  `expansionFraction`, liberação efetiva de regiões em `QS_EDGE_EXIT`, sessão e alpha do
  compositor em cada sentido do gesto, inclusive sem cruzar o limiar de abertura completa.
- [ ] **Excesso aparente de luz nas bordas apenas sobre fundo branco**: comparar a mesma
  cápsula em cena clara e colorida. A captura clara mostra interior cinzento e contorno
  mais evidente; hipótese prioritária é contraste entre tinta escura da cápsula e aro
  branco, não aumento comprovado da energia do AGSL. Avaliar primeiro interior claro com
  tinta legível dos discos/ícones/labels (dependente da validação do PR #87), preservando
  cenas coloridas/escuras. Se persistir, medir separadamente aro Compose, óptica AGSL
  e região do compositor e avaliar ganho por cena. Não mexer no `sf.qslight` global às cegas.
- [ ] Relógio real da shade (`QS_CLOCK_CANDIDATE` sem nó): localizar hook antes de mudar tinta.
- [ ] Ícones do lápis e engrenagem: toggle independente dos ícones dos tiles.
- [ ] Transição gradual do pop-up e confirmação da rampa de 180 ms.
- [ ] Baixíssimo preenchimento dos sliders e possível base retificada na curva.
- [ ] Contraste branco das notificações com controle exclusivo ao fundo claro, sem afetar o colorido.
- [ ] Live updates não-mídia no estado parado e na lockscreen; depois mídia.
- [ ] Toast Samsung que substitui o vidro após a criação.
- [ ] Confirmar tinta dos labels e feedback visual dos controles em todas as cenas.
- [ ] Validar que o refresh funciona com alterações no app, sem reiniciar SystemUI.

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
