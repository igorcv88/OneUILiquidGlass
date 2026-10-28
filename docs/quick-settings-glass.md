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
   um contorno óptico uniforme (`QuickSettingsGlass.rim`): branco sobre cenas escuras/coloridas,
   uma linha escura fina sobre cenas claras (onde o branco desapareceria). Círculos internos mantêm
   o contorno da Samsung: o grupo de controles se lê como uma peça de vidro;
4. no construtor de `BackgroundElement`, troca a cor por **transparente** em contêineres e discos
   inativos, em qualquer cena (inclusive UNKNOWN, antes da classificação), ou pela própria cor
   Samsung com alpha ≤ 0,90 nos discos ativos. Não existe película branca, cinza ou colorida dentro
   de cápsula alguma; o desfoque visível é o do painel (backdrop), como nas notificações.

O grande contêiner dos tiles (`QuickTileBox`) chama `BoxWithOutline` com o bit 2 da máscara
`$default` (parâmetro `contorno`, padrão `true`). Esse bit é aceito e lido como `true`; os demais
bits continuam fazendo o hook desistir. Antes, o contêiner era ignorado e ficava com a cor nativa
do `coloredBackgroundInteractor` (tinta derivada do papel de parede, o azul da captura) e com o
contorno nativo `#0dffffff`. Se o usuário definir uma imagem própria para o grupo
(`customImageDrawable`), a Samsung desenha a imagem com alpha 0,5 e o hook só aplica o contorno.

### Sliders

`IconProgressBar → BidirectionalBar` mantém seu layout Samsung vertical ou horizontal. Exatamente
duas superfícies: a cápsula de vidro (interior transparente, igual às demais) e uma forma branca de
progresso. O rect do trilho não é executado no DrawScope identificado. O progresso tem espessura
`qs.fill` (padrão 0,78) da cápsula, centrado, e a **mesma margem** nas duas pontas do eixo longo
(a transação de `DrawContext` encurta a área pela margem, então 100% termina dentro do vidro). O
corpo é um retângulo arredondado de raio = metade da espessura; abaixo de um diâmetro (0%, 1%, 2%…)
ele é um círculo completo ancorado na ponta de onde o progresso nativo parte. O thumb é desenhado
como a tampa da ponta móvel desse mesmo corpo, então as duas primitivas brancas formam uma forma
só. Canvas/context size são restaurados mesmo em erro. Layout, valor, hitbox e arraste permanecem
nativos.

### Luz de borda dependente do fundo (compositor, perfil 3)

Os cards de notificação respondem ao fundo porque o shader reescrito no SurfaceFlinger
(`sfhook/refract.h`) lê os pixels reais atrás de cada região de blur marcada. A Central passa a usar o
mesmo motor, com um perfil próprio:

- **Marca.** Cada contêiner de vidro (`Kind.CONTAINER`) ganha uma região `BackgroundBlurDrawable`
  com raio de canto `floor(r) − 1 + 0,875` (`QuickSettingsGlass.edgeTag`): a faixa 0,80–0,95 no
  programa FillRRect e, como `r + 0,5`, 0,30–0,45 no programa de clip, livres nos dois. 0,875 e
  0,375 são exatos em fp16 até 256 px (`field_check`: 852 raios de 43 a 256 px, os dois programas e
  o uniform fp16; nenhuma marca dos perfis 0–2 nem raio inteiro decodifica como 3).
- **Luz que entra pela borda (v0.27, `oulg_glow`, compartilhada por todos os perfis).** O campo dá,
  para cada pixel, a profundidade `d` a partir do contorno e o ponto mais próximo do contorno; o
  shader lê o fundo ali (2 px para dentro) e calcula `u = (luma − piso) / (1 − piso)`. A luz que entra
  é `min(ganho × u, teto)` e decai para dentro como `exp(−d / λ)`, com
  `λ = 1,5 + (qsdepth − 1,5) × u²` px: trecho escuro não acende nada (só o contorno basal do Compose),
  cinza médio penetra pouco, branco penetra fundo. É um campo contínuo: sem faixa de largura fixa e
  sem linha interna (o decaimento é monótono a partir do contorno). `λ ≤ r/4` e um corte suave entre
  2,5 λ e 4 λ zeram a luz antes da profundidade `r`, onde o ponto mais próximo do contorno salta
  (eixo medial do retângulo interno). Cor: branco tingido em 35 % pela cor do fundo naquele trecho.
  Conteúdo em movimento move a luz. Medido no host (pílula 720 × 300, r 145, padrões): a luz cai a
  10 % do valor no contorno em 6,5 / 12,5 / 24,5 / 42,5 px para luma 0,25 / 0,5 / 0,75 / 1,0.
- **Refração (v0.27, perfil 3).** O mesmo deslocamento de laje do perfil 1
  (`−n · A · bisel · t²(3 − 2t)`, `t = 1 − d/bisel`, bisel = `clamp(0,42 r, 16, 56)`), com força própria
  `sf.qslens` (padrão 0,30; o clamp do C limita a 0,5, a cota sem dobra do `field_check`): o fundo
  junto ao contorno é visto pela borda do vidro, deslocado para dentro em até `A × bisel` (16,8 px na
  pílula) e ampliado no máximo `1/(1 − 1,5 A)` ≈ 1,8×. Esse fundo refratado é desenhado com opacidade
  `sf.qsbody × (1 − S(d/bisel))` (padrão 0,35 no contorno, S a smootherstep quíntica) e some no fim do
  bisel; dali para dentro a região é transparente e o painel da Samsung aparece como antes.
  `sf.qslens=0` desliga a refração e o fundo refratado (sobra só a luz).
- A luz basal continua sendo o contorno do Compose, então sem o compositor nada muda.
- **Onde é desenhada.** No `BackgroundNode.draw` do próprio fundo do contêiner (o nó é ligado ao
  elemento recolorido em `BackgroundElement.create/update`), dentro da display list do Compose: a
  região acompanha layout, rolagem e transformações.
- **Geometria.** O fundo fica na caixa interna, recuada pela largura do contorno (no dump do S938B,
  4 px: caixa externa `[48,404][708,694]`, interna `[52,408][704,690]`). A região é a caixa interna
  estendida por `QuickSettingsGlass.edgeOutset` (a largura em dp × densidade, arredondada como o
  `padding` do Compose), com o raio da forma da Samsung calculado no tamanho externo
  (`CornerSize.toPx-TmRCtEA`). A profundidade da luz e da refração é medida a partir da borda
  externa, sobre o anel do contorno, não 4 px para dentro.
- **Ligação por identidade.** `BackgroundElement.equals` compara cor, brush, alpha e forma, e todo
  fundo de contêiner é o mesmo véu sobre `RectangleShape`: um mapa por igualdade juntaria todos
  numa chave (e pegaria qualquer fundo igual em outro ponto do SystemUI). Os elementos ficam numa
  lista de referências fracas comparada por `==`, e saem dela ao serem ligados ao nó.
- **Ciclo de vida.** Um `BackgroundBlurDrawable` fica no conjunto forte do agregador do
  `ViewRootImpl` enquanto estiver visível com alpha e raio > 0, mesmo quando o `RenderNode` deixa de
  ser desenhado (vira um retângulo vazio, reenviado a cada frame). Por isso a região é liberada
  (`BackgroundBlurBridge.release`: raio 0, invisível, alpha 0) quando o nó sai da árvore
  (`Modifier.Node.markAsDetached$ui`), é religado a outro elemento ou substituído, quando o efeito é
  desligado (opção, master ou compositor) e quando um hook falha. Só o fade transitório mantém
  alpha ≥ 1/255, para a região não sumir quando o painel volta sem regravar a display list.
- **Fade.** Uma região de blur ignora o alpha de quem a desenha. Antes de cada frame da janela, o
  alpha das regiões é o alpha acumulado dos ancestrais do painel × `edgeFade(expansão)`
  (`SecQSPanelComposeAdapter.expansionFraction`: 0 abaixo de 60 %, inteira a partir de 95 %). Se a
  expansão não puder ser lida, a luz dinâmica fica em 0, não em "aberta". Um ancestral com escala
  ≠ 1 apaga a luz: a marca exige pixels inteiros, como nos cards da central.
- **Condição.** Opção ligada, `debug.oulg.sfrefract=1` e `debug.oulg.sf.optics ≥ 10`. A refração e
  a luz contínua exigem a biblioteca v0.27 (anuncia 11); com a v0.26 a região continua sendo só a
  faixa de luz antiga. Sem isso, nenhuma região é criada.

### Outras superfícies

A mesma `oulg_glow` entra nos perfis 0 (pop-up), 1 (bloqueio) e 2 (cartões da central), por cima do
material de cada um (`oulg_light`), com ganho próprio: `sf.hulight` 0,40, `sf.kglight` 0,35,
`sf.shadelight` 0,30; profundidade 12 px, piso 0,10 e teto 0,28 em comum. São mais fracos que na
Central porque esses materiais já têm aro (contorno e Fresnel do perfil 1, ótica de borda do módulo
no perfil 0) e o perfil 2 carrega texto. Ganho 0 não emite código para o perfil: o desenho é o da
v0.26 bit a bit (`edge_check` compara com o `refract.h` de referência).

Verificação no host: `sfhook/tools/edge_check.mjs <dir> [referência]` monta os programas com vários
conjuntos de parâmetros e renderiza FillRRect e clip em WebGL2 (Chromium headless). Confere: luz zero
sobre preto; oito trechos iguais sobre cinza; profundidade a 10 % crescente com a luma e o branco
≥ 2,5× o cinza; nenhuma subida para dentro (nem linha interna nem faixa) nas colunas e na diagonal
do canto; barra branca acende só o trecho que cruza e a luz acompanha a barra deslocada; nada além
de 4 λ; refração: a linha de origem do fundo segue o deslocamento da laje dentro da precisão de 8
bits, em ordem estrita (sem dobra nem duplicação), opacidade conforme a fórmula e nada além do
bisel; perfis 0–2: a luz muda só a faixa externa (nada além de 48 px), nada sobre preto, e com ganho
0 o resultado é idêntico ao da referência; FillRRect = clip.

### Círculos transparentes e alinhamento do DeviceControl

Para tiles grandes `DeviceControl` (SmartThings, Google Home), `NearByDevice` e `MyDeviceTileService`
(`isNoBgTile`), a Samsung passa cor transparente ao círculo do ícone (`QuickTileKt`: `z15` →
`Color.Transparent`) e o ícone do app preenche a mesma caixa quadrada que o disco ocupa nos outros
tiles. O módulo não cria disco ali, nem aro.

O desalinhamento vinha do layout da própria Samsung. `QuickTileKt` (lambda do conteúdo do tile)
chama `IconTextTile-v3dQN_U(…, start, gap, iconSize, …)` com:

| Tile | `start` | `gap` (ícone → texto) | posição x do ícone (multi-célula) |
| --- | --- | --- | --- |
| comum (Quick Share, Wi-Fi…) | `Dp.Unspecified` | 5 dp | `(altura − ícone) / 2`: centrado na altura, mesmo recuo inicial |
| `isNoBgTile` | 6 dp | 0 | 6 dp fixos |

A caixa do ícone tem o mesmo tamanho nos dois casos (calculada antes, sem depender de `isNoBgTile`).
O hook em `IconTextTile-v3dQN_U` troca só o par `(6 dp, 0)`, que é o único rastro de `isNoBgTile` ali,
pelo par dos tiles comuns `(Unspecified, 5 dp)` (`QuickSettingsGlass.iconLayout`): ícone e rótulo
ficam onde ficariam num tile com disco. Não depende de spec, provedor (SmartThings ou Google Home),
nome de app ou posição; vale após reordenar ou trocar o provedor. O escopo do chamador é capturado
para que ligar/desligar a opção reposicione ao vivo; desligado, os argumentos são os da Samsung.

O alinhamento não altera `Modifier`, `Composer`, semântica, ordem de desenho ou gestos. O blur do
miolo continua sendo o do painel Samsung. Com o perfil 3 ativo existe uma região de compositor por
contêiner elegível, desenhada pelo nó Compose; seu alpha e sua liberação seguem o ciclo descrito
acima para evitar resíduos na abertura, no fechamento e na troca de composição.

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

- O material é interior transparente + contorno óptico sobre o blur que a Samsung já aplica ao painel. A
  refração junto ao contorno e a luz que depende do fundo só existem com o compositor (perfil 3);
  só com o APK, a borda é apenas a luz basal uniforme.
- O fundo refratado é o fundo real com o blur leve da região (8 px), sem o escurecimento/tinta do
  painel da Samsung: sobre fundo claro a borda refratada fica mais clara que o painel, sobre fundo
  escuro, mais escura. `sf.qsbody` controla quanto disso aparece.
- Ganhos fixos no código: teto e piso da luz (`OULG_QS_LCAP/LFLOOR`, `OULG_EDGE_*`); só ganho,
  profundidade, força da refração e opacidade são propriedades.
- O SmartThings/Google Home (e outros `DeviceControl`/`NearByDevice`/`MyDevice`) fica sem disco,
  como na Samsung; só a posição do ícone e do rótulo segue a dos tiles com disco.
- O player ativo não passa por `BoxWithOutline`; possui hooks próprios de background e arte,
  descritos na seção do material adaptativo abaixo. Ausência dessas APIs é uma lacuna a validar.
- A cor do preenchimento dos sliders e os ícones permanecem os da Samsung.
- A paleta legada permanece como fallback. Os ajustes do material adaptativo estão em `QsStyle`
  e na seção Controles rápidos, descrita abaixo; os valores iniciais ainda exigem A/B no aparelho.
- O hook altera chamadas de `BoxWithOutline` também no modo de edição e em pop-ups da Central que
  usem o mesmo componente; conferir na validação.

## Validação no aparelho

1. Instalar, ativar **Início → Ambientes → Controles rápidos** e reiniciar o SystemUI uma vez para
   carregar os hooks.
2. `logcat -d -s OULG | grep 'QS_'` deve conter `QS_COMPOSE_READY`, `QS_COMPOSE_ACTIVE` e linhas
   `QS_BOX` com `ACTIVE`, `IDLE` e `CONTAINER` (uma delas com `mask=2`, o grupo de tiles) e
   `QS_SLIDER_READY`, `QS_ICON_TILE_READY` e, com um tile DeviceControl grande na tela,
   `QS_ICON_TILE noDisc …`. Sem `QS_BOX`, o caminho não foi atingido. Com o compositor v0.27
   (`getprop debug.oulg.sf.optics` = 11): `QS_EDGE_READY`, `QS_EDGE_PANEL host=true expansion=true`
   e `QS_EDGE_REGION ... expansion=1.0` com a Central aberta. `setprop debug.oulg.sf.debug 1` antes
   de reiniciar o compositor pinta as regiões marcadas de magenta.
3. Abrir/fechar a Central 20 vezes, devagar e rápido; alternar Wi-Fi/Bluetooth e tiles pequenos;
   arrastar brilho e volume; mídia vazia e tocando; modo de edição; tema claro/escuro; rotação.
4. Desligar a opção com a Central aberta: tudo deve voltar às cores Samsung
   (`QS_COMPOSE_REFRESH reason=off scopes=N`, N > 0).
5. Coletar screenshots claro/escuro e o log acima.

## O que só o aparelho decide (S25 Ultra)

Nada abaixo é verificável no host; os testes sintéticos só provam a matemática do shader.

- Se o blur do painel da Samsung é desenhado antes das regiões da Central (senão cobre luz e
  refração).
- Se a refração parece vidro ou um anel de fundo sem tinta em volta dos controles (`sf.qsbody`,
  `sf.qslens`), e se fica sutil sobre papéis de parede claros e escuros.
- Se a profundidade da luz (`sf.qsdepth`) lê como luz entrando e não como halo; se o conteúdo em
  movimento (rolagem de app atrás, vídeo) move a luz sem cintilar.
- Perfis 0–2 com a luz nova: pop-up, cartões do bloqueio e da central sem segunda linha junto ao
  contorno já ajustado, e sem perda de legibilidade do texto junto à borda. `sf.hulight`,
  `sf.kglight`, `sf.shadelight` = 0 devolvem o desenho da v0.26.
- Alinhamento do Google Home/SmartThings igual ao do Quick Share, nas duas posições do grid e após
  trocar o provedor; tamanho visual do ícone do app em relação ao glifo dos tiles com disco.
- Que a reescrita v0.27 compila no driver do aparelho (`REWRITE_OK`, sem `REWRITE_FAILED`).

## Rollback

Desligar **Controles rápidos** (ou o master). Se o provider não responder, desativar o módulo no
LSPosed e reiniciar o SystemUI. Nada é gravado em partições do sistema.


## Diagnóstico do perfil 3 (após o PR #73)

Este incremento instrumenta o caminho existente sem alterar shader, paleta, geometria, parâmetros
ópticos ou reinício. `QS_COMPOSE_REFRESH` comprova recomposição; `QS_BOX` comprova recoloração;
`REWRITE` comprova a reescrita de um programa no compositor. Nenhum desses eventos, isoladamente,
comprova uma região do perfil 3 desenhada nos controles rápidos.

`QS_EDGE_STATUS` é independente de `debug.oulg.trace`. Não precisa ativar o survey. Os eventos não
registram rótulos, SSIDs, conteúdo de notificações ou árvores Compose. Identidades são
`System.identityHashCode`, válidas apenas no processo atual.

| Etapa | Evidência |
| --- | --- |
| Instalação | `phase=install ready=true`; na falha, `member` e tipo da exceção |
| Hook do host | `phase=panel-hook installed=true acquisition=attachComposeView` |
| Host adquirido depois da instalação | `phase=panel afterInstall=true host=true adapter=true expansionReadable=true`, com identidade da View |
| Condições efetivas | `phase=gate ready=… option=… master=… enabled=… sfRefract=… sfOptics=… host=… blocked=…` |
| Fundo e nó associados | `phase=path reason=element` e `reason=bind`, com identidades do elemento e do nó |
| Região enviada ao drawable | `reason=region-created`, `region-updated` (geometria/tag) e `region-drawn` |
| Central aberta | `phase=settled` ao atingir expansão ≥ 0,95, mesmo sem regiões; `phase=stabilized` 350 ms depois, após recalcular e aplicar o fade |
| Desenho com alpha positivo | `phase=region-visible` requer nó já desenhado, expansão ≥ 0,95, `edgeFade>0` e alpha enviado > 0 |
| Saída | `phase=exit` e, se a View sair da janela, `phase=detach` com contagens de liberação |

`phase=snapshot` sai também ao alternar a opção/master, inclusive ao desligar e com zero regiões.
Callbacks de configuração sem alternância checam os gates sem rearmar os resumos. Os motivos de caminho saem uma vez por sessão; as contagens continuam acumulando.
Uma sessão de diagnóstico é rearmada na abertura, na aquisição do host e em callbacks de
alternância, para repetir o diagnóstico após um toggle sem reiniciar o processo.
Os contadores dos resumos cobrem o intervalo desde o último `exit`, incluindo a composição que
antecede a abertura. `specsCreated` = specs criadas, `specsBound` = specs associadas,
`nodesReleased` = associações removidas por unbind/rebind; `region_created`,
`region_updated`, `region_drawn` e `region_released` contam operações de ciclo de vida. `region_drawn`
conta o primeiro desenho de cada região criada, não frames. `nodesActive` são nós associados,
`pending` specs aguardando associação, `regionsActive` drawables ainda não liberados.

`element-unmatched` e `unbound-node` podem incluir fundos Compose comuns enquanto há specs QS
pendentes; são indícios de associação a investigar, não prova isolada de falha. Elementos seguem
comparados por identidade (`==`); não se usa `equals()`. `invalid-radius-tag eligible=false`
distingue geometria fora de `43 ≤ r < 256` px de falha do hook.

O listener de pré-desenho fica na janela da Central a partir de `panel()`, também com a opção
desligada (o padrão). Com o efeito inativo, cada frame só confere o gate e libera regiões
remanescentes; a caminhada pelos ancestrais e a leitura reflexiva de `expansionFraction` rodam apenas
em snapshots (alternância, callback de configuração, falha) e com o efeito ativo.

`phase=fade` registra somente transições entre host oculto, ancestral escalado/transparente,
expansão ilegível/abaixo do fade e fade positivo. Variações numéricas dentro da mesma categoria
não geram logs por frame.

Os resumos incluem `shown`, `ancestorAlpha`, `scaled`, `expansion`, `edgeFade`, `alphaCalculated`,
`alphaSentMin/Max`, `alphaPositive` e `visibleCandidates`. O guard existente de display list conserva
alpha enviado **1/255** quando o fade calculado é zero. Por isso `alphaPositive>0` sozinho não
comprova visibilidade: exigir `edgeFade>0`, expansão aberta e `visibleCandidates>0` na observação
estabilizada. Expansão ilegível continua resultando em fade zero, e escala diferente de 1 mantém o
guard. Nenhuma instrumentação força alpha 255. `region-visible` significa que o Java desenhou uma
região candidata; somente inspeção no aparelho pode confirmar que o SurfaceFlinger a consumiu, que
a janela Samsung não a cobriu depois e que o efeito é visualmente perceptível.

Se houver `panel-hook` e não houver `phase=panel` no **log completo desde o início do processo**,
a aquisição por `attachComposeView` não foi observada depois da instalação. Não concluir isso de
um buffer truncado. Não foi introduzida busca de um singleton/host existente sem evidência de qual
instância seria correta. O teste no aparelho decide se essa recuperação é necessária.

### Coleta controlada no S25 Ultra

1. Instalar o build diagnóstico e usar o fluxo seguro de reinício já existente no app. Não reiniciar
   o SurfaceFlinger isoladamente. Manter survey/trace detalhado desligado.
2. Com Controles rápidos ativado, abrir sobre fundo branco por 2 s, fechar, abrir sobre fundo azul
   por 2 s e fechar. Repetir uma vez; alternar a opção desligada/ligada para repetir os snapshots.
3. Coletar somente os eventos relevantes (o comando não altera configuração):

   ```sh
   su -c 'logcat -d -v threadtime -s OULG OULG_SF' | grep -E 'event=(QS_|TUNING|OPTICS)|OULG_SF.*(OPTICS|REWRITE)'
   ```

4. Verificar a cadeia da tabela e os valores **efetivos** em `phase=gate`, mesmo quando `getprop`
   estiver vazio ou divergente. Não mudar `qslens`, `qsbody`, cores ou shader para compensar um gate.
5. Confirmar liberação com master/ambiente desligado, `sfrefract=0`, versão óptica < 10, detach e
   troca de composição. Repetir sliders, edição, rotação e mídia ativa para checar interação.

Os testes de host cobrem gates e motivos, associação por identidade, rebind/unbind, release,
substituição/detach do host, fade sem expansão legível, escala e atualização do alpha sem novo draw.
Não substituem as capturas e os logs do dispositivo.

## Material adaptativo e controles (correção após #76/#77)

A nova captura mede o conteúdo abaixo da janela QS, excluindo a própria janela por SurfaceControl.
`QsBackdrop` mantém uma requisição cancelável de `ShadeTone.prefetch` por abertura, em escala 0,125;
a cópia de pixels e a redução para `ToneGrid` continuam no worker. A classificação é **global e
deliberada**: uma decisão para a superfície inteira (contornos, textos e ícones; o interior é
transparente em qualquer decisão), mais a banda do cabeçalho. Durante a sessão ela só muda por uma
mudança real e confirmada do fundo (ver "Adaptação contínua e transições").

Abertura e fechamento seguem a expansão do painel (`SecQSPanelComposeAdapter.expansionFraction`,
lida por `QuickSettingsEdgeLight.expansion()`), não `View.isShown()`: o root Compose pode continuar
VISIBLE com o painel recolhido. Só com a expansão ilegível a visibilidade decide. Uma captura
decide a abertura (depois, a reamostragem de 900 ms descrita abaixo): o ACTION_DOWN nos 64 dp do topo (o gesto que costuma abrir o painel) antecipa a
captura antes de a Samsung mostrar o painel; ela é ignorada com o painel aberto ou havendo amostra com
menos de 600 ms, que é reaproveitada pela abertura seguinte. O fechamento cancela a captura em voo e
encerra a geração, então um resultado atrasado nunca cai na sessão seguinte. Não há captura,
consulta de configuração ou leitura da expansão por frame com o recurso desligado. Os timestamps são fixados quando as janelas de exclusão são reunidas, antes de
enfileirar o worker; uma medição não aceita uma captura anterior à sua inscrição.

`QsScene` separa UNKNOWN / DARK_SCENE / LIGHT_SCENE do tema Android. Entrada em claro: luma sRGB
≥ 0,75; saída: < 0,65, com cooldown de 500 ms. Uma amostra com mais de 600 ms não decide uma nova
abertura; dados seguros indisponíveis mantêm a paleta legada por tema. O estado fica estável durante
a abertura. Mudanças de configuração/tema invalidam a amostra por ComponentCallbacks e pedem
uma nova captura; detach cancela a requisição e remove callbacks/listeners. O header QS (relógio, data e ações, sobre o
painel e não sobre uma cápsula) usa as quatro bandas superiores **só para a tinta do texto e dos
ícones**: um ajuste local de contraste, nunca um segundo material. A classificação das cápsulas é global: fotos mistas
ainda precisam de teste local de contraste no aparelho; não há promessa de contraste mínimo para
todos os pixels de um vídeo ou wallpaper arbitrário.

Interior das cápsulas e discos inativos: transparente em todas as cenas (após o PR #78). Contorno:
linha escura #101318 com alpha `qs.lightrim` (0,18) sobre claro; branco com `qs.darkrim` (0,26)
sobre escuro/colorido; o branco basal do tema enquanto a cena é UNKNOWN. `qs.darkfill`,
`qs.lightfill`, `qs.darkcolor`, `qs.lightcolor`, `qs.idle`, `qs.track` e `qs.lightstyle` são
legado: `SettingSpec.validate` ainda aceita valores antigos (importação de perfis), mas `QsStyle`
os lê e descarta, então nenhum perfil antigo traz a película de volta. O blur continua pertencendo
ao painel, sem blur por cápsula. Sem amostra válida
o foreground e o desenho nativos são preservados. Texto neutro preserva o alpha nativo no contexto
adaptado; cores de destaque, logos sem tint e ícones pretos dos discos ativos são preservados. A espécie ACTIVE/IDLE observada em
BoxWithOutline é associada ao Painter por identidade, com referências fracas e teto de 64 entradas;
a associação mantém a tinta correta em recomposições independentes de QSIcon. Glyphs sem estado
conhecido mantêm conservadoramente a tinta escura nativa, para não inverter um disco claro.

`QsContentHooks` não intercepta `Material3.TextKt` nem `Material3.IconKt`. Nenhum hook de
foreground escreve `$changed`, `$default`, `Composer` ou `TextStyle`. Os argumentos padrão de
`Text` são resolvidos exclusivamente pelo Compose, eliminando a origem suspeita do receptor nulo.

`QsTextHooks` associa `TextStringSimpleElement` / `TextAnnotatedStringElement` criados dentro dos
owners Samsung permitidos aos seus nós de desenho. O registro usa identidade fraca, não `equals`
estrutural do Compose, e é limitado a 256 entradas. O desenho de texto desses nós pode adaptar
apenas o `Paint` neutro, sem shader/filtro. O callback posterior restaura a cor mesmo se o desenho
lançar exceção; não captura nem suprime a exceção da aplicação. Tanto BaseCanvas quanto as
sobrescritas de BaseRecordingCanvas são contempladas. Outros nós não herdam o override, nem quando
aninhados. O estado UNKNOWN/OFF produz desenho nativo, e OFF invalida também a display list.

As APIs `create/update/draw(ContentDrawScope)` e os tipos/retornos são checados antes da ativação.
API ausente produz `QS_TEXT_INCOMPATIBLE` e deixa esse caminho inativo. Essa compatibilidade de
renderização ainda precisa ser confirmada no DEX em execução no aparelho; testes de host não
executam recomposição real de SystemUI. O fallback não altera o fornecimento de TextStyle.

`QSIcon-fWhpE4E` só altera o long de cor na ABI explícita
`(Modifier, Painter, String, long, float, boolean, Composer, int) -> void`, com conteúdo tintável.
Nenhuma máscara é alterada. ABI diferente gera `QS_ICON_INCOMPATIBLE` e ícone nativo.
Ações do cabeçalho usam os nós Painter criados no escopo dos wrappers Samsung do cabeçalho.
O filtro neutro é substituído só durante seu desenho e restaurado logo após; display lists também
são invalidadas. Assim o override não depende de uma recomposição interna de Icon com parâmetros
inalterados. Painters sem tint e cores de marca permanecem nativos. API de Painter ausente gera
`QS_HEADER_ICONS_INCOMPATIBLE`, isoladamente do texto.
Scopes e nós de texto são invalidados nas mudanças de cena/configuração e ao desligar QS;
nenhum desses caminhos depende do interruptor de vidro das notificações.

### Mídia ativa

`OneUI90MediaPlayerPageKt` cria um background fora de `BoxWithOutline`; o hook captura seu clip
arredondado e o associa à região óptica, com o mesmo interior transparente. O véu que era desenhado
sobre a arte na cena clara (e os hooks globais de `DrawWithContentElement` que o sustentavam) foi
removido: era a mesma película leitosa. Play/pause, metadata e arte continuam pertencendo à Samsung. Logos multicoloridos e logos brancos sem tint são mantidos;
a variante clara precisa de inspeção desses logos no dispositivo.

### Duas superfícies, sem alterar a célula

- Cápsula externa: 50–100% da dimensão curta da célula nativa. Fundo e contorno compartilham
  o envelope. O clip do host e a área de toque permanecem nativos.
- Preenchimento branco: 10–100% da espessura externa (padrão 78%), com a mesma margem nas pontas.
  O corpo mantém o intervalo nativo no eixo longo; abaixo de um diâmetro vira um círculo completo
  ancorado na ponta de origem (vertical: base; LTR: esquerda; RTL: direita), estável entre 0% e o
  primeiro diâmetro. O thumb é a tampa circular da ponta móvel do corpo (sem nativo do corpo no
  quadro: o centro nativo do thumb, limitado à barra). Raio = metade da espessura em qualquer valor.
  Valor, hitbox e coordenadas lógicas não são alterados; o único desvio visual é o diâmetro mínimo.

O rect do trilho é suprimido somente no DrawScope do slider identificado. `qs.track` continua
importável/exportável, mas não desenha nenhuma faixa nem aparece como ajuste ativo. As três
assinaturas das primitivas são verificadas por tipos e quantidade de parâmetros; incompatibilidade
mantém o desenho nativo do slider. Nenhuma View nem interceptador de toque é adicionado.

A referência é o DEX DZIF, não o nome decompilado normalizado:

| Membro | Escopo |
| --- | --- |
| `BidirectionalBarKt.PillShape` | Identifica o `BoxWithOutline` do slider por identidade do shape (vale também quando a caixa recompõe sozinha) |
| `BidirectionalBar-Ld4roP0` | Captura o scope do chamador, para a espessura mudar ao vivo |
| `drawBidirectionalBarTrackProgressThumbContent-Uzs9YN0` | Transação de Canvas/context size |
| `DrawScope.drawRect-n-J9OG0$default` | Trilho |
| `DrawScope.drawRoundRect-ZuiqVtQ$default` | Gradiente de progresso |
| `DrawScope.drawRoundRect-u-Aw5IA$default` | Thumb |
| `Shape.createOutline-Pq9zytI` / `Outline.Rounded(RoundRect)` | Envelope externo inset |

Primitives só são alteradas quando o DrawScope é a mesma identidade do slider sendo desenhado.
O rect do trilho não é executado no escopo identificado. As máscaras alteradas são apenas
as de geometria das primitivas DrawScope, nunca máscaras de recomposição. `QsDrawTransaction` restaura o Canvas mesmo quando o setter de tamanho
falha; a restauração é idempotente. A geometria/tag da região acompanha o envelope externo. Raio
< 43 px é inelegível para perfil 3 e libera a região, preservando o material Compose. Retornos de
invalid-size, nonhardware-canvas e missing-viewroot também liberam bridges antigos.

### Aplicativo e aplicação pendente

Central → Controles rápidos: um cartão próprio, depois de "Camadas do efeito", com o opt-in (o
subtítulo avisa que muda na hora e que a prévia acima é das notificações) e quatro grupos recolhidos,
no padrão de "Mais ajustes": Sliders, Círculos (só os ligados), Contorno e Luz e refração. Não há
mais grupo "Vidro": o interior não tem opacidade ajustável. Cada controle tem
ajuda própria. O opt-in é independente do vidro das notificações e permanece false por padrão. Na
busca, os controles aparecem na seção "Controles rápidos". `qs.edgeblur` só se aplica com a lente
dinâmica (é o blur das regiões do perfil 3) e aparece inativo fora dela.

`qs.active`, `qs.darkrim`, `qs.lightrim`, `qs.edgeblur`, `qs.outer` e `qs.fill` são chaves do app,
lidas do snapshot do provider. `qs.darkfill`, `qs.lightfill`, `qs.darkcolor`, `qs.lightcolor`,
`qs.idle`, `qs.track` e `qs.lightstyle` são preservadas somente para compatibilidade (somente
leitura na UI, lidas e descartadas por `QsStyle`). Não são lidas de `setprop`. `qs.edgeblur` atualiza drawables existentes
sem reiniciar o compositor. Os demais valores invalidam somente scopes QS. O máximo da espessura
externa está explicado na UI.

`sf.qslens`, `sf.qsbody`, `sf.qsdepth` e o novo `sf.qslight` usam o mecanismo existente de pending
até a aplicação real. `sf.qslight` tem default 0,90, domínio 0–2; é lido em `p[3].light`, permitido
por `SfLive` e pelo helper e registrado no catálogo gerado. O parser C rejeita valores não finitos
e sufixos inválidos. O ganho não altera os perfis 0–2; a biblioteca bundled passa a v0.28.0/29.
Não foi alterado `sf.shadelight`, `paneldim`, `shadelightglass` ou o material de popup/bloqueio.

### Cabeçalho e chevrons das notificações

`HeaderToneController` mantém as Views já identificadas: header_clock, header_date,
shade_carrier_text, noti_setting e clear_all. Relógio, data e operadora formam um grupo com uma
decisão; cada ação do rodapé tem a sua. A região medida vem da posição de **layout** (getTop na
árvore, sem translações), então o cabeçalho deslizando na animação de abertura não muda o que é
medido. A decisão só acontece quando chega uma amostra (abertura ou reamostragem), com histerese e
`ToneGate`; o pre-draw só reaplica a cor decidida quando a Samsung escreveu a sua (rebind) e nunca
decide. Antes do PR, o pre-draw recalculava a tonalidade pela posição animada contra a captura antiga
e alternava branco/preto (log 14:57:19–20). O log `HEADER_INK phase=apply|repair` é emitido depois
da escrita, com `before=` e `color=`. OFF/detach restaura a ColorStateList original.
O cabeçalho Compose QS conserva a classificação separada das bandas superiores do painel; sua
geometria e ícones de editar/energia/configurações precisam de inspeção no DZIF.

`Foreground` reconhece android:expand_button_icon e variantes cujo objeto seja exatamente o
mIconView de NotificationExpandButton do framework (inclusive subclasses Samsung). Não se usam
nomes aproximados de recursos. Variantes fora dessa estrutura ficam nativas, sem inventar IDs.
O filtro explícito da ImageView tem precedência, seguido do filtro do Drawable e do tint ativo
para o estado da View. Filtro efetivo de tipo desconhecido retorna ao estado nativo, sem inferir
cor a partir de um tint sobreposto. A troca de drawable restaura também o filtro do objeto antigo destacado.
Rebind/drift reaplica o override antes do desenho. OFF restitui tint/mode e filtros originais;
imageAlpha e estado não são modificados. Logos e imagens arbitrárias não se tornam elegíveis.

### Adaptação contínua e transições (após o PR #78)

Três estados separados, em todas as superfícies:

| Estado | Onde vive | O que muda |
| --- | --- | --- |
| Observado | `ToneGrid` da captura (`ShadeTone.prefetch`, escala 0,125, abaixo da janela) | Luma por banda, geração, `takenAt` |
| Decidido | `QsScene`/`ToneGate` (QS), `shadeScene` + `sceneGate` (central), grupo do `HeaderToneController`, `popupGate` (pop-up) | Só com amostra nova: histerese + confirmação |
| Mostrado | Cor na View / nível de tinta lido no desenho Compose | Transição curta até a cor decidida |

**Reamostragem.** Central de notificações (`HeadsUpHooks.resampleShade`) e Controles rápidos
(`QsBackdrop.resample`) capturam de novo a cada 900 ms enquanto abertos e visíveis; nunca por quadro,
nunca com a superfície escondida ou com o recurso desligado. Fechar cancela a captura em voo e
incrementa a geração/ticket, então resultado atrasado é descartado. O pop-up continua medindo sob o
cartão no ritmo existente (100 ms entrando, 1 s parado).

**Confirmação (`ToneGate`).** A primeira decisão de uma sessão vale na hora. Uma leitura do outro
lado do classificador vira candidata; só muda a decisão se a próxima leitura, ≥150 ms depois
(200 ms no pop-up), concordar, e no mínimo 600 ms (800 ms no pop-up; 400 ms no cabeçalho) após a
mudança anterior. A próxima leitura é agendada cedo quando há candidata, então uma mudança real chega
em ~1 s no pior caso. Ruído, um quadro transitório ou o movimento da animação não mudam nada.
`popuplock=1` (padrão) usa esse portão; `popuplock=0` volta a aplicar cada leitura.

**Transição.** `InkAnimator` (Views) e o nível animado de `QsBackdrop.ink` (Compose) movem o primeiro
plano em 200 ms × escala de animação do sistema, com easing simétrico (passa rápido pelo cinza do
meio). Escala 0 ou animações desligadas: cor final imediata. Uma nova decisão recomeça da cor que
está na tela; um rebind da Samsung no meio da transição vence (a transição é descartada e o reparo
reaplica a cor decidida no próximo pre-draw). Só um relógio de quadro compartilhado, ativo apenas
enquanto há transição. Cobertura:

- cabeçalho da central (relógio, data, operadora) e ações do rodapé;
- texto e ícones com `imageTintList` das notificações e pop-ups, e setas de expansão (tint e filtros);
- texto Compose dos Controles rápidos e ícones do cabeçalho QS (editar, energia, configurações), por
  nível de tinta lido no desenho (só invalida o desenho, sem recompor a cada quadro);
- contornos e ícones dos tiles QS (argumentos de composição): trocam uma vez, no meio da transição;
- ícones de app filtrados/monocromáticos das notificações: trocam de uma vez (sem transição).

Logs (limitados a mudanças de estado): `SHADE_RESAMPLE`, `QS_RESAMPLE`, `QS_BACKDROP` (com
`previous=` e `transition=`), `QS_INK_TRANSITION`, `HEADER_INK phase=apply|repair`,
`POPUP_CONTRAST_GATE`, `TEXT_REAPPLY animated=`, `CHEVRON_TONE target= animated=`.

### Validação no SM-S938B / S938BXXUCDZIF (pendente)

1. Abrir Chrome numa página inteiramente branca. Abrir/fechar notificações e QS 20 vezes.
   Esperado: cápsulas sem película (o fundo desfocado aparece igual dentro e fora), contorno escuro
   fino visível; relógio/data escuros desde a primeira abertura, sem alternar durante a animação.
2. Repetir sobre preto, azul e imagens mistas: cabeçalho claro/restante escuro e o inverso.
   Conferir relógio, data, operadora, ações e editar/energia/configurações já na primeira abertura.
3. Com a central aberta (ou um pop-up visível), mudar o fundo de verdade: vídeo/página alternando
   branco↔preto, troca de app por baixo. Esperado: em ~1 s os textos, ícones e setas mudam
   suavemente (~200 ms), sem piscar; um único quadro diferente não muda nada.
4. Testar separadamente QS ON/notificações ON, OFF/ON, ON/OFF e OFF/OFF. Alternar durante a sessão;
   OFF deve devolver desenho, cores e shaders nativos somente do recurso desligado.
5. Arrastar brilho/volume por 0, 1, 2, 5, 25, 50, 75, 99 e 100%. Esperado: só a cápsula de vidro e
   a forma branca (mais fina, com margem visível dos lados e nas pontas), círculo completo no mínimo,
   sem faixa interna leitosa, toque/arraste iguais ao nativo.
6. Alterar Wi-Fi, Bluetooth e configuração/tema. Expandir/recolher notificações, atualizar seu
   conteúdo e conferir setas, tint e logos. Observar se há flash e se os alvos de toque continuam iguais.
7. Configurações → Opções do desenvolvedor → escala de animação 0: as cores devem mudar sem transição.
8. Registrar PID da SystemUI antes/depois e verificar que não reiniciou. Coletar eventos e crashes:

   ```sh
   adb shell pidof com.android.systemui
   adb logcat -v threadtime -b main -b system -b crash OULG:I OULG_SF:I AndroidRuntime:E ActivityManager:I '*:S' \
     | grep --line-buffered -E 'OULG|com\.android\.systemui|FATAL EXCEPTION|TextStyle|TextKt|QuickTile|compose|NullPointerException'
   ```

   Não fixe `--pid`: isso perderia o novo processo justamente após um crash. Para acompanhar só a
   adaptação: `| grep -E 'SHADE_PREFETCH|SHADE_RESAMPLE|QS_BACKDROP|QS_RESAMPLE|QS_INK|HEADER_INK|POPUP_CONTRAST|CHEVRON_TONE|TEXT_REAPPLY|_FAILED'`.

Para medir o custo no dispositivo, comparar QS OFF/ON sobre o mesmo fundo e os mesmos 20 gestos:
`adb shell dumpsys gfxinfo com.android.systemui reset` antes e
`adb shell dumpsys gfxinfo com.android.systemui framestats` depois de cada sequência. A reamostragem
acrescenta ~1 captura de 1/8 de resolução por segundo com uma central aberta; isso precisa de
confirmação no aparelho (gfxinfo/Perfetto), não foi medido no host.

Os testes automatizados cobrem material transparente em todas as cenas e com perfis antigos,
geometria do slider (0–100%, vertical/horizontal, LTR/RTL, espessuras), portão de confirmação,
interpolação, transições de View (troca, restauração, reversão, rebind no meio), decisão do cabeçalho
por posição de layout e nível de tinta Compose. Não provam recomposição real, ABI do DEX em execução,
o aspecto final no aparelho nem a estabilidade da SystemUI no S25 Ultra.

### Resultado automatizado desta correção

Local: `./gradlew testDebugUnitTest` (Robolectric 4.14.1, SDK 36) — ver a descrição do PR para o
número exato. CI (`Controls panel checks`) roda testes, lint, NDK/sfhook e gera o APK de teste.
Validação física no S25 Ultra: pendente.
