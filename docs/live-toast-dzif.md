# Live updates não-mídia, Now Bar e toasts — implementação (DZIF, 02–03/11/2026)

Fonte: laudo técnico fechado de 02/11/2026 (pílula L1, cartão L2, Now Bar L3,
toast T1) e o diagnóstico físico pós-PR #116 (02/11, 22:07–22:45, SystemUI PID
20335), que mostrou as quatro superfícies sem vidro no aparelho. Este documento
descreve **o que está implementado depois da correção** e como validar no
Galaxy.

## Correção pós-PR #116 (causas comprovadas)

| ID | Evidência física | Causa no código | Correção |
|---|---|---|---|
| L1, T1 | `LIVE_GLASS_APPLY` com wrapper instalado; `LIVE_BLUR_CAPABILITY enabled=false`; visual nativo | A região só era criada com `isCrossWindowBlurEnabled()`. No DZIF esse sinal é `false` (`ro.surface_flinger.supports_background_blur` vazio), mas os cartões **aprovados** do pop-up/central registram `crossBlur=false` e mesmo assim desenham vidro com o mesmo `BackgroundBlurDrawable` (`HeadsUpHooks.drawablePath()` no modo APK-only); a própria Samsung usa essa classe no cartão expandido | A capacidade passa a ser a do caminho aprovado; o vidro só fica nativo (opaco) com Battery Saver ou `disable_window_blurs=1`, com receptor de Battery Saver para voltar sem polling |
| L2 | Árvore real: `FrameLayout(BackgroundBlurDrawable)` → `CardItemView(bg=null)` → `FrameLayout(GradientDrawable)`, todos 1340×288 | O hook oferecia só o root sem fundo (`noBackground`) | O preenchimento `GradientDrawable` que cobre o root (ou o alvo de `changeCardBg`) é envolvido; com o blur Samsung atrás, **nenhuma região nova**: o preenchimento é desenhado translúcido sobre o blur nativo (`regionless`) |
| L3 | `NOWBAR_NATIVE reason=cardviewWithoutLayer` dezenas de vezes/s; DEX: `getBlurContainer()` lê o campo `a.J` (CardView) | O código recusava CardView sem uma segunda camada simples | Camada `GlassLayer` dentro do CardView, atrás do conteúdo; log deduplicado por estado e setters do CardView só quando o valor difere | Estado: 🔧 implementado e testado localmente (JVM/Robolectric); ☐ sem
prova física no S25 Ultra DZIF. Nada aqui é aprovação visual.

## Escopo e exclusões

| ID | Owner (DZIF) | Hooks instalados | Host do vidro |
|---|---|---|---|
| L1 | `OngoingChipAdapter` | `onBindViewHolder(ViewHolder,int)` before/after, `setChipBg(ViewGroup,ColorStateList)` after | o `ViewGroup` passado a `setChipBg` |
| L2 | `OngoingCardAdapter` + `CardStackView` | `getView`, `bindView`, `initNormalNotificationView`, `changeCardBg`, `setBlurBG`, `updateItemBg` (todos after) | raiz do cartão ou alvo dos writers, nunca o conteúdo `RemoteViews` |
| L3 | plugin AODService (`PluginAODImpl`) | bootstrap `PluginInstance.loadPlugin()` after; no classloader do plugin: `NowBarOngoingCardView.d0/e0/setViewMode/setExpandedViewColor`, `a.N/a.U` (filtrados por classe exata), `NowBarCardStackView.q/setExpandedCardState`, `NowBarCardGradientView.setGradientBackgroundVisible` (before) | container do cartão (view simples) ou camada de blur do plugin quando o container é CardView |
| T1 | `ToastFactory` | `createToast(Context,Context,CharSequence,String,int,int)` after | raiz do toast (ou primeiro filho com o shape e o texto) |

Exclusão estrutural de mídia (negação na dúvida):

- Dado da posição: no L1, `OngoingActivityDataHelper.getDataByIndex(int)`, a mesma
  fonte que o `OngoingChipAdapter` do DZIF usa (ele não tem `getItem(int)` nem
  lista de dados). Método resolvido estritamente (estático, ou no helper que o
  adapter guarda, ou no singleton do helper). O índice tem de estar dentro de
  `getItemCount()`, e o dado é lido de novo no fim do bind: se mudou, fica nativo.
  No L2, uma lista/`getItem` inequívoca e, na falta dela, o mesmo helper.
- L1/L2: `OngoingActivityData.mIsMediaOngoingData` do **dado ligado àquela
  posição** (lido por campo `boolean` exato), `mIsAutomation` também nega
  (sem suporte comprovado), pacote obrigatório. Campo ausente/ilegível = nativo.
- L2: `OngoingCardAdapter.isMediaView(View)` é **chamado** (não hookado); sem o
  método no firmware a superfície inteira fica nativa.
- L3: somente `getClass() == NowBarOngoingCardView` (nunca a base `a`, nunca
  subclasse; `NowBarMediaCardView` nega), `NowBarItem.getFaceWidgetMediaData()`
  precisa existir e ser `null`, pacote obrigatório, pacotes de briefing negados.
- T1: só raiz `ViewGroup` com `TextView` e sem descendente clicável.

Não há hook em `View.setBackground`, `WindowManagerGlobal.addView`,
`LinearLayout`, `WindowSurvey`, SurfaceFlinger, QS, sliders, heads-up, shade ou
notificações comuns. `WindowSurvey` continua log-only.

## Correção pós-teste do PR #117 (03/11/2026)

Teste físico (vídeo de 15 s + log, SystemUI PID 13036): as quatro superfícies
aparecem e os hooks rodam, mas o material não convence. Causas no código e
correções:

| ID | Evidência | Causa | Correção |
|---|---|---|---|
| L1, T1, L3 | Região criada (nenhum `LIVE_GLASS_NO_REGION`), aparência cinza/apagada | As regiões novas eram enviadas sem curva de cor. **Correção da revisão do #119:** no DZIF o `BackgroundBlurDrawable` não tem `setColorCurve(float x6)`; tem `setBlurColorCurve/1` e `setBGColorAdjustment/1` (`BLUR_DRAWABLE_API`). O caminho aprovado (`applyCurve`, `lensMode`) procura o setter de 6 floats e, portanto, **também não envia curva no DZIF**: a curva não é uma diferença comprovada entre as notificações aprovadas e as superfícies novas | `glass/BlurCurve` envia a curva neutra pelo setter real (`setColorCurve` x6, `setBlurColorCurve(float[])`, tipo com construtor de 6 floats ou o objeto de `SemBlurInfo.getColorCurve()`); `curveApplied()` só fica verdadeiro quando um setter rodou; o log `BLUR_CURVE api=… applied=…` mostra qual foi usado. O caminho aprovado não foi alterado |
| L2 | Um `LIVE_GLASS_APPLY` seguido de dezenas de `LIVE_GLASS_RESTORE reason=notFill` em pares (~20 ms) | `covers()` comparava tamanhos vivos antes de `MATCH_PARENT`; durante expandir/recolher o fill diverge do root por alguns quadros e era descartado e reenvolvido | O fill escolhido fica fixo por root enquanto válido (prioridade: alvo de `changeCardBg` → fill já escolhido → busca); `MATCH_PARENT` decide antes dos tamanhos |
| L2 | Cartão azul-marinho quase opaco sobre wallpaper colorido | A região Samsung do ancestral tem cor sobreposta escura e quase opaca; o fill translúcido por cima não mudava o resultado | Enquanto o vidro aparece, só a cor sobreposta dessa região fica transparente (RGB mantido); blur, raio, forma e alpha da região intactos; cor original restaurada exatamente quando o vidro some ou a superfície é desligada |
| L1 | `RESTORE reason=verdict=MEDIA_FLAG` numa pílula de timer | O índice do helper pode incluir itens que a pílula não mostra (mídia), deslocando posições | Se o ViewHolder guarda o próprio `OngoingActivityData` (campo único) ou a tag do `itemView`, esse dado prevalece |

L3 roda no **processo SystemUI**: `NOWBAR_PLUGIN` e `LIVE_GLASS_APPLY surface=NOWBAR`
saem do mesmo PID 13036; o plugin AODService é carregado pelo SystemUI no
classloader dele. No AOD/doze a Now Bar continua nativa por política.

## Material

`glass/LiveGlassDrawable` envolve o drawable que a Samsung acabou de escrever:

- **vidro**: `BackgroundBlurDrawable` do `ViewRootImpl` (via `BackgroundBlurBridge`,
  sem lente/tag SF), a mesma região do vidro aprovado no modo APK-only, com a
  cor nativa em RGB intacto e alpha reduzido (fração por superfície em
  `LiveSurface`), mais um contorno fino iluminado do canto superior esquerdo.
  Sem captura, sem RenderNode em loop, sem véu adicional.
- **vidro sobre blur nativo** (`regionless`, L2): quando a Samsung já tem um
  `*BlurDrawable`/`SemBlurInfo` num ancestral (até o `CardStackView`), nenhuma
  região é criada; o preenchimento nativo é desenhado com o alpha do vidro
  (alterado só durante o desenho, sem callback, portanto sem loop de
  invalidação) e o contorno. Reavaliado em cada writer e sync: se o blur
  Samsung aparecer depois, a região própria é descartada (sem blur duplo).
- **nativo**: desenha exatamente o drawable original. É o que aparece sem
  `ViewRootImpl` (antes do attach), com Battery Saver ou `disable_window_blurs`,
  se a API faltar, e na Now Bar em doze/AOD/tela apagada.

Geometria preservada: padding, tamanhos mínimo/intrínseco e estado delegados
ao nativo; raios lidos do `GradientDrawable`/CardView/outline e limitados aos
bounds; o outline do vidro tem alpha 0 (sombra de elevação vista através do
painel seria halo cinza). O `setBackgroundTintList` nativo continua na View e
recolore o vidro (recoloração do `setChipBg` sem hook extra). Fundos com
`RippleDrawable` e Views CardView nunca têm o fundo substituído.

## Ciclo de vida (`hooks/ongoing/LiveGlass`)

1. **Primeiro frame**: o fundo é envolvido no próprio bind/factory/writer; a
   região é criada em `onViewAttachedToWindow`, antes do primeiro desenho. Sem
   `postDelayed`, sem polling.
2. **Rebind**: cada writer nativo substitui o fundo; o owner oferece a View de
   novo, o drawable novo vira o nativo envolvido e o mesmo wrapper volta
   (`INTACT`/`REASSERTED`, nunca dois wrappers).
   **Blur Samsung** (`SemBlurInfo` de `setBlurBG`/`a.N`): só é removido quando a
   região do compositor já existe e o vidro vai ser desenhado (anexado, blur
   entre janelas ligado, fora de doze). Antes do attach, sem blur, em doze, se a
   criação da região falha, ou se ela falha durante o desenho, o host fica (ou
   volta) com o fundo nativo **e** o blur Samsung, e é reenvolvido quando a
   região puder existir. Nunca fica sem os dois.
3. **Reciclagem**: no fim de cada bind, toda View do holder é reclassificada
   contra o dado **deste** bind; holder reaproveitado para mídia volta ao
   último fundo nativo exato.
4. **Restauração**: gate desligado, dado inelegível, falha persistente ou
   detach do toast devolvem o drawable nativo, o tint e o `SemBlurInfo`
   original; listeners removidos. CardView da Now Bar recebe de volta cor e
   elevação pelos próprios setters; gradiente volta ao último valor pedido.
5. **Fade**: `OnPreDraw` só enquanto anexado; a região segue o alpha acumulado
   dos ancestrais (fade nativo do toast/cartão) sem `invalidate()`.
6. **Falhas**: cada superfície tem orçamento próprio (3 exceções); ao estourar,
   só ela volta ao nativo (`LIVE_SURFACE_DISABLED`).
7. **Estado**: `WeakHashMap<View, State>` na main thread; dono e chave nunca
   referenciados fortemente pelo valor.

L3, CardView (DZIF): `getCardViewContainer()` e `getBlurContainer()` devolvem o
mesmo CardView. O vidro vai numa `GlassLayer` adicionada dentro dele só para
cartão elegível com a superfície ligada: cobre o frame inteiro do CardView
(ignora o padding de conteúdo), fica atrás do conteúdo (Z negativo, de modo
que `getChildAt(0)` continua sendo a View da Samsung), não recebe toque nem
acessibilidade e só desenha o fundo. Enquanto o vidro aparece, cor e elevação
do CardView ficam transparentes pelos setters dele e o gradiente fica oculto;
sem vidro (doze, Battery Saver, falha), voltam no mesmo passo. Desligar ou
cartão inelegível remove a camada.

L3: o plugin é aceito só com `getPackageName()==com.samsung.android.app.aodservice`,
componente `PluginAODImpl` e classloader próprio; instalação **uma vez por
classloader** (plugin reiniciado = novo loader = nova instalação). Plugin já
carregado antes do módulo: não há caminho seguro de inspeção (o módulo entra
no início do processo SystemUI, antes do carregamento de plugins); fica nativo
até o próximo `loadPlugin`.

## Configuração

Três interruptores aditivos em `ProductFlags.OPT_IN`, **desligados por padrão**
e subordinados ao mestre `enabled`, na página Início:

- `surface.liveactivity.enabled` — L1 + L2 (“Atividade ao vivo”)
- `surface.nowbar.enabled` — L3 (“Now Bar”)
- `surface.toast.enabled` — T1 (“Avisos rápidos”)

Nenhum parâmetro existente de pop-up, central, QS ou SF foi alterado.

## Logs (`adb logcat -s OULG`)

`LIVE_HOOK`, `LIVE_READY available=[…]`, `LIVE_CHIP_DATA source=…#static|#instance|list-fallback`,
`LIVE_GLASS_SAMSUNG_BLUR reason=…`, `LIVE_UNAVAILABLE surface=… fallback=native`,
`LIVE_CARD_WRITERS`, `NOWBAR_PLUGIN`, `NOWBAR_UNAVAILABLE missing=…`,
`LIVE_GLASS_APPLY`, `LIVE_GLASS_NATIVE reason=…` (`noBackground`, `ripple`,
`cardview`, `noRadius`), `NOWBAR_NATIVE reason=…` (`verdict=…`, `gate`,
`containerNotGroup`, `noContainer`), `LIVE_GLASS_RESTORE`,
`LIVE_GLASS_NO_REGION`, `LIVE_BLUR_CAPABILITY`, `LIVE_CALLBACK_FAILED`,
`LIVE_SURFACE_DISABLED`. Os eventos `LIVE_GLASS_*` e `NOWBAR_NATIVE` são
registrados quando o estado muda (não há mais teto de 12 por processo nem
repetição a cada callback). Nenhum texto de notificação/toast é registrado.

## Incompatibilidades/limitações registradas

- Assinaturas aplicadas exatamente como no laudo; qualquer diferença de
  nome/parâmetro/retorno mantém a superfície nativa e aparece em
  `LIVE_UNAVAILABLE`/`NOWBAR_UNAVAILABLE`.
- O pacote do `OngoingActivityDataHelper` não consta no laudo: procura-se em
  `…ongoingactivity` e `…ongoingactivity.ongoingchip`. Sem o helper, `LIVE_CHIP_DATA
  source=list-fallback` aparece no log e a pílula fica nativa.
- L2: o `OngoingCardAdapter` não foi coberto pela revisão; usa lista/`getItem`
  inequívoca e, se não houver, o helper. A mídia continua vetada pelo
  `isMediaView(View)` nativo, que não depende do dado.
- Não está provado no aparelho que a região `BackgroundBlurDrawable` renderiza
  numa janela `TYPE_TOAST` e na barra de status como renderiza nos cartões
  aprovados; se não renderizar, o resultado é o fundo translúcido sem blur, e
  a correção seria trocar para o caminho Samsung (`SemBlurInfo`) nesses hosts.
- L1/T1/L3: o tipo do argumento de `setBlurColorCurve/1` no DZIF não está no
  log; se não for um dos formatos aceitos, `BLUR_CURVE … applied=false` e
  nada é enviado. Mesmo aplicada, a curva neutra é uma hipótese de melhora,
  não uma causa comprovada.
- L2: `BackgroundBlurDrawable.setColor` não invalida (AOSP); a camada é
  redesenhada a cada mudança real da cor sobreposta (limpar e restaurar).
- L2: a cor sobreposta da região Samsung é lida de `getColor()` ou do `Paint`
  interno (`mPaint`); se o firmware não expuser nenhum dos dois, ela fica intacta
  e o efeito continua limitado por ela.
- L3: a `GlassLayer` dentro do CardView é uma proposta de implementação; a
  compatibilidade com a animação/clip da Now Bar só o aparelho confirma.
- Exclusão de Now Brief por classe exata e por pacote (heurística conservadora,
  só nega). Theme Park e wallpaper claro/escuro não foram testados.
- O APK de `assembleDebug` local não contém o bundle SF (exige NDK); usar o
  artefato do CI do PR para o teste físico.

## Teste físico final (Galaxy S25 Ultra DZIF)

Pré-requisito: APK do PR instalado, LSPosed ativo, reinício do SystemUI.
Ligar um interruptor por vez na página Início.

1. **L1/L2 (Atividade ao vivo)**: cronômetro/timer do Relógio e descanso de
   treino do Samsung Health. Verificar pílula desde a primeira aparição,
   atualização do contador, toque → cartão expandido, recolher, reabrir, trocar
   de app, remover a atividade (sem cápsula fantasma), girar a tela.
2. **Mídia como testemunha**: tocar música (ex.: Bose/Spotify) ao mesmo tempo;
   pílula, cartão e player de mídia **idênticos ao nativo**.
3. **L3 (Now Bar)**: timer ativo com tela bloqueada acesa, compacta/expandida,
   reordenação com mídia e Now Brief simultâneos; apagar/acender tela e AOD
   (nativo no AOD, sem wakeups). Conferir `NOWBAR_PLUGIN`/`NOWBAR_NATIVE` no log.
4. **T1 (Avisos rápidos)**: copiar texto 4× seguidas, toast de outro app,
   cancelar/esperar o fim; vidro desde o primeiro quadro, duração/animação
   nativas, sem fundo cinza estável nem fantasma.
5. **Desligar cada interruptor** com a superfície visível: volta ao fundo
   nativo exato. Economia de energia ligada: fundo nativo.
6. **Regressão**: QS, sliders, mini-tiles, heads-up, shade e notificações
   comuns sem diferença.
7. Theme Park ON/OFF, modo claro/escuro e wallpaper claro/escuro.
