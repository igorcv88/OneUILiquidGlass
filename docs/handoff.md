# Handoff — OneUILiquidGlass (2026-10-16)

Documento para continuar o trabalho em outra conversa. Leia inteiro antes de mexer em qualquer coisa. O histórico técnico detalhado está em `docs/architecture.md`; as seções de 2026-10-16 cobrem a central de notificações.

**Continuidade prioritária (28/10/2026, pós-PR #83):** o checklist atualizado e os critérios
de regressão estão em [`qs-backlog-20261028.md`](qs-backlog-20261028.md); a coleta
interativa para validar a instrumentação e as atualizações ao vivo do branch seguinte está
em [`qs-dzif-pr84-protocol.md`](qs-dzif-pr84-protocol.md). Para Quick Settings e
notificações, use esses dois documentos antes dos relatos antigos abaixo.

## 0w. Atualização (2026-10-25): rodada 40 — gestos do sistema e abertura de app cortam a faixa, lockscreen restaurada

Base: vídeo do PR #39 (18:45:08) e print da lockscreen (18:46). O log enviado estava vazio. **Não testado no aparelho.**

**O vídeo mostrou:**
- Pop-up, app branco → home: o gesto de home começa embaixo e é vertical, então o `directional` mantinha a faixa com a captura velha do app fechando, um anel branco em volta do corpo já azul.
- Launcher → Chrome: depois dos 100 ms do toque, a faixa capturou a animação de abertura, um anel escuro; o texto ficou branco sobre branco ~270 ms porque o pop-up parado só era medido a cada 1 s.
- Lockscreen: o `liverim 2` e o tom escuro do #39 foram aplicados também nela, e voltou o segundo arco.
- Central em branco: "2 more notifications" do grupo recolhido com texto branco.

**Correções:**
- Toque que começa na faixa inferior (4,5 %) ou nas bordas laterais (4 %) é gesto do sistema e corta a faixa desde o início. Depois de um toque simples ou de um gesto do sistema, a faixa fica cortada `lenstransition` (350 ms) além do `lenssettle`. Swipe horizontal normal continua com 100 ms.
- Pop-up é medido a cada 100 ms por 1,5 s depois de qualquer toque.
- `livedarkalpha`, `livedarkfill` e `liverim` valem só para o pop-up; a lockscreen volta ao visual anterior ao #39.
- Texto sem id inflado pelo próprio SystemUI (o "+N" do grupo) agora troca de tom; layouts `RemoteViews` de apps continuam intocados.

**Prop nova:** `lenstransition` (ms). `setprop debug.oulg.lenstransition 0` volta ao comportamento do #39.

**Em aberto (precisa do log):** a piscada ao abrir a central em qualquer fundo, e o título "Minimizados" se não for o texto "+N".

```sh
su -c 'setprop debug.oulg.trace 1; logcat -c'
# reproduzir
su -c 'logcat -d -v threadtime -s OULG > /sdcard/Download/oulg40_log.txt; ls -la /sdcard/Download/oulg40_log.txt'
```

**Validado:** Gradle `testDebugUnitTest` (106), `lintDebug`, `assembleDebug`.

## 0v. Atualização (2026-10-25): rodada 39 — cores interceptadas, uma cena por pilha, rampas a partir do tint nativo, visual de 24/10 no pop-up

Base: rodada física do PR #38 (`OULG38_20261025_174738`, vídeo 17:51:47, prints de 24/10 e 25/10, E0–E4). **Não testado no aparelho.**

**O que os logs mostram:**
- Linha `c9ee378` (um grupo: dois fundos) com `TEXT_REAPPLY reason=rebind views=1` a cada 16–19 ms: o SystemUI escreve uma cor de texto todo quadro e o módulo corrigia no pré-desenho seguinte. Ganha quem escreve por último. É compatível com o título "Minimizados", mas o log não identifica a view.
- `tint=1b8c2ce`: a rampa partia de uma região quase transparente em vez do tint nativo, um quadro de "card sumindo".
- `luma=0.52` dava nativo numa abertura e claro na outra: a histerese por card atravessava aberturas, e cards da mesma pilha discordavam.
- O print de 24/10 23:40 é anterior à faixa refratada (PR #35) e, pelo horário, ao clareamento do tema escuro (PR #34): região `0x14ffffff` + 4 % de preto, contra `0x1cffffff` sem preto depois. `liveoptics` era 1; 0,5 reduz à metade também a linha externa.

**Correções:**
- **Cor interceptada:** `setTextColor`/`setImageTintList` são interceptados no SystemUI só para as views que o módulo trocou. Uma cor do tom do tema vira o novo original e o tom espelhado é aplicado na mesma chamada; cor de destaque libera a view. Acaba a disputa quadro a quadro. `SHADE_OPEN_END intercepted=` conta.
- **Reserva com guarda:** mais de 6 reparos em 500 ms param as varreduras (`TEXT_CONTESTED`); `TEXT_TONE_DRIFT` identifica classe e id da view (sem texto). Com `trace=1`, numa cena clara, `TEXT_OUTSIDE` lista textos fora das linhas: é onde aparecerá o "Minimizados" se não estiver dentro de uma linha.
- **Uma cena por pilha:** luma ≥ 0,62 é claro e ≤ 0,48 é escuro para qualquer card; entre os dois, o card segue a cena (a central inteira decide uma vez por captura; o pop-up segue sua leitura anterior). Nada passa de uma abertura para outra.
- **Rampa contínua:** parte do tint nativo e também anima entre cores diferentes (vidro claro ↔ nativo). O texto troca na metade da rampa.
- **Pop-up e lockscreen (tema escuro):** região `livedarkalpha` 20 (`0x14ffffff`) + preenchimento preto `livedarkfill` 10 (4 %, nenhum no vidro claro), e linha externa com ganho próprio `liverim` 2, independente de `liveoptics`.

**Props novas:** `livedarkalpha`, `livedarkfill`, `liverim`. Estado do PR #38: `setprop debug.oulg.livedarkalpha 28; setprop debug.oulg.livedarkfill 0; setprop debug.oulg.liverim 1`.

**Teste (um vídeo, como no handoff):** pop-up em branco → wallpaper azul → escuro → branco sem fechar; abrir a central com o grupo "Minimizados" recolhido; alternar branco ↔ escuro e reabrir; expandir o grupo. Para a óptica, mesmo wallpaper de 24/10, comparar `livelens 0` e `1`.

```sh
su -c 'grep -E "TEXT_GUARD|TEXT_TONE_DRIFT|TEXT_CONTESTED|TEXT_OUTSIDE|SHADE_PREFETCH|SHADE_OPEN_END|MATERIAL_STYLE|POPUP_CONTRAST" /sdcard/Download/oulg39_log.txt | tail -150'
```

**Validado:** Gradle `testDebugUnitTest` (103), `lintDebug`, `assembleDebug`.

## 0u. Atualização (2026-10-25): rodada 38 — fim do piscar do texto, vidro claro menos branco

Base: rodada física do PR #37 (`OULG37_20261025_165840`, vídeo 17:08). **Não testado no aparelho.**

**Causa do piscar, no log (card `f959dce`, o pop-up que virou card da central):**
- `08.946 swap=false` → `08.996 swap=true`: na passagem pop-up → central o card ficava sem superfície e o texto era restaurado (branco) por ~50 ms.
- `09.189 BACKGROUND_UPDATE` (o SystemUI refaz o bind e põe o branco de volta) → `09.693 swap=true`: 504 ms depois, a varredura de 500 ms. É o branco de ~450 ms do vídeo.

**Correções:**
- O módulo guarda as views que trocou e confere a cor delas em todo pré-desenho (leitura simples, sem alocação). Se o SystemUI repôs a cor ou trocou as views, reaplica no mesmo quadro. A varredura de 500 ms fica só como reserva. Log `TEXT_REAPPLY reason=rebind|detached`.
- Entre superfícies (pop-up saindo, central ainda não aberta) o material é mantido, sem restaurar. Restauração continua na lockscreen e em todo retorno ao fundo nativo.
- Card medido há menos de 1,5 s (o pop-up que ele era) mantém essa leitura no início da abertura até a captura chegar (`source=held`), em vez de ir para nativo e voltar.
- Pop-up entrando é forçado a redesenhar a cada 100 ms (1 s parado): a medição esperava 1,2 s por um desenho qualquer.
- **Vidro claro:** cinza claro frio `b8c2ce` a 0,32 (≈ `#E8ECEF` sobre branco, luma ~0,92) no lugar de branco 0,25, e sombra de espessura da borda 2× (`lightedge`). Ainda longe do preto 25 % (`#BFBFBF`).

**Props novas:** `lighttone` (hex), `lightedge`; `lightglass` agora é o alpha dessa cor. Rodada 37: `setprop debug.oulg.lighttone ffffff; setprop debug.oulg.lightglass 0.25; setprop debug.oulg.lightedge 1`.

**Teste:** 10× pop-up em fundo colorido → app branco → abrir a central (com o pop-up ainda fixado e depois que ele sair), com `trace=1` e gravação. Esperado: zero `TEXT_REAPPLY` com `sinceLastWalk` alto, texto do card de cima escuro do início ao fim. Comparar o vidro claro no mesmo fundo branco com `lightglass` 0.24 / 0.32 / 0.40 e `lightedge` 1 / 2.

**Limite:** abertura sem captura pré-abertura e sem leitura recente ainda pode desenhar os primeiros quadros no visual nativo (gens 52/54).

**Validado:** Gradle `testDebugUnitTest` (99), `lintDebug`, `assembleDebug`.

## 0t. Atualização (2026-10-25): rodada 37 — estilo de material no lugar do véu cinza, sem halo por letra

Base: handoff pós-PR #36 (log conservative, captura `82192.png`, vídeos). **Não testado no aparelho.**

**Por que mudou:** o OULG aplicava preto sobre branco (`veil=59000000`), e preto 25 % sobre branco é `#BFBFBF`: cinza, com contraste de ~1,8:1 para texto branco. Nenhum `veilmax` resolve. O halo por letra virou contorno sujo. A estimativa pela barra de status errou (toolbar escura do Chrome sobre página branca).

**Agora:**
- **Fundo do lado do tema** (escuro no tema escuro): visual nativo, véu pequeno limitado a `veilmax` (padrão 0,25).
- **Fundo do lado oposto** (claro no tema escuro): **vidro claro** (branco 25 %, o véu do próprio tema claro Samsung) **com texto escuro**. No tema claro sobre fundo escuro, o inverso.
- **Histerese por card:** claro a partir de `stylehi` 0,62, volta a escuro em `stylelo` 0,48.
- **Troca do texto:** só views dos templates padrão (ids do framework `android` ou do SystemUI; ícone só o de expandir). Layouts `RemoteViews` personalizados têm ids do app e nunca são tocados. Dentro disso, só cinzas do tom do tema são espelhados (branco → preto, cinza claro → cinza escuro, alpha mantido). Texto colorido, spans, ícones de app e ações não mudam. Restaurado em todo caminho para o fundo nativo, como o halo era.
- **Halo removido. Estimativa pela barra de status removida.**
- **Primeiro quadro da central:** sem dados, visual nativo (sobre branco: card claro cujo texto escurece quando a captura chega, sem passar por cinza). Um toque nos 6 % superiores da tela com a central fechada já pede a captura, vinculada a esse gesto: só a abertura feita pelo mesmo gesto (dedo ainda na tela ou solto há no máximo 250 ms) e com captura de até 1,5 s a usa; qualquer outro toque no meio invalida. O visual nativo é aplicado na hora, sem rampa do véu anterior. Os 616 ms vinham do `CaptureHub` dormindo 500 ms; agora um cliente novo é atendido na hora.
- **Pop-up:** nativo no primeiro quadro; captura a cada 100 ms enquanto entra (alpha < 0,99 ou ainda se movendo), depois a cada 1 s. A faixa refratada usa a mesma cor da região do corpo: acaba o anel claro em volta do miolo cinza.
- **Glint:** `liveglint` 0,10 (aprovado) e novo `shadeglint` 0,10 na central; lockscreen continua 1.
- **Gate:** `lensgate` padrão agora é `directional`.

**Props novas:** `stylelo`, `stylehi`, `lightglass`, `textswap` (0 volta ao véu sem troca de estilo), `shadeglint`. Removida: `texthalo`.

**Logs:** `MATERIAL_STYLE`, `FIRST_VISIBLE_MATERIAL`, `SHADE_PREFETCH state=preopen|sample age=`, `SHADE_OPEN_BEGIN preAge=`, `POPUP_CONTRAST source=native|sample style=`.

```sh
su -c 'grep -E "MATERIAL_STYLE|FIRST_VISIBLE|SHADE_OPEN|SHADE_PREFETCH|SHADE_TONE_APPLY|POPUP_CONTRAST|LENS_STATS" /sdcard/Download/oulg37_log.txt | tail -150'
```

**Teste:** (1) central sobre página branca, puxando pelo topo e pelo gesto do launcher, três vezes cada; (2) o mesmo sobre fundo escuro e colorido, conferindo texto primário, secundário, ações e ícones; (3) pop-up sobre branco por mais de 2 s; (4) quina superior esquerda na central (`shadeglint` 0 / 0.10 / 0.25); (5) desligar o módulo com a central aberta: nenhum texto deve ficar escuro.

**Reverter por prop:**

```sh
su -c 'setprop debug.oulg.textswap 0; setprop debug.oulg.shadeglint 1; setprop debug.oulg.lensgate conservative'
```

(`textswap 0` volta ao véu preto limitado a `veilmax`, sem halo.)

**Limite do log:** `FIRST_VISIBLE_MATERIAL` é registrado no pré-desenho (`at=preDraw`): diz com que material o próximo desenho é feito, não prova qual quadro chegou ao display.

**Validado:** Gradle `testDebugUnitTest` (96), `lintDebug`, `assembleDebug`.

## 0s. Atualização (2026-10-25): rodada 36 — tom desde o primeiro quadro, contraste do pop-up, véu limitado, glint, gate direcional

Base: handoff do aparelho de 25/10 (DZIF). Cada mudança tem prop de reversão. **Não testado no aparelho.**

**Central (P0-A).** Antes, cada card só media o véu depois de visível; o véu da abertura anterior aparecia por 100–491 ms. Agora:
- no primeiro quadro da abertura (`expandedHeight > 0.5`) começa uma geração e é pedida **uma** captura do fundo inteiro atrás da janela da central, antes de ela ficar visível;
- a captura vira 32 faixas de luminância (`ToneGrid`); cada card lê as faixas sob sua posição atual;
- resposta de geração antiga ou que chegue com a central fechada é descartada;
- até a captura chegar: estimativa pela aparência da barra de status do app (`STATUSBAR_TONE`); sem ela, o véu anterior, como antes;
- card ainda aparecendo (alpha < 0,5) recebe o véu novo sem rampa.

**Pop-up (P0-B).** O pop-up sobre app ganha véu próprio: estimativa no primeiro quadro, depois uma captura sob o card a cada 1 s enquanto visível. Slot separado: o véu da central nunca chega ao pop-up e vice-versa. Desligar: `debug.oulg.popuptone 0`.

**Véu limitado + halo (P1-C).** O véu preto sobre app branco chegava a 0,42 (cinza-chumbo). Agora o limite é `debug.oulg.veilmax` (0,35); o que falta de contraste vira uma sombra suave (2,5 dp) atrás do texto branco/cinza do tema — texto colorido não recebe. Desligar o halo: `debug.oulg.texthalo 0`. O halo só existe enquanto o card desenha vidro: ao voltar ao fundo nativo (oculto, inelegível, falha de desenho/composição, detach) `release()` devolve a sombra original do texto no mesmo quadro. Voltar ao véu antigo: `debug.oulg.veilmax 0.75`.

**Glint (P1-D).** O arco curto dentro da quina superior esquerda tem ganho próprio no pop-up: `debug.oulg.liveglint` (0). Central e lockscreen continuam com 1. Contorno, Fresnel e faixa refratada inalterados. Comparar `0`, `0.10`, `0.25` no mesmo fundo.

**Gate direcional (P1-E, experimental).** `debug.oulg.lensgate directional`: toque parado, pressão longa e rolagem vertical mantêm a faixa; movimento horizontal (classificado após 16 px, antes do slop do launcher) ou segundo dedo corta no mesmo quadro; após soltar um toque ou gesto horizontal, `lenssettle`. Padrão continua `conservative`. Ao soltar um toque simples ainda há `lenssettle` (o app pode reagir ao toque); rever depois do teste físico. Limite: direção do dedo não prova movimento do conteúdo sob o pop-up (carrossel sob cabeçalho fixo ainda corta).

**Padrões:** `lenssettle` 100 ms e `rimhz` 80 Hz (os valores escolhidos no aparelho), para sobreviver ao reboot.

**Logs novos:** `STATUSBAR_TONE`, `SHADE_OPEN_BEGIN`, `SHADE_PREFETCH` (`tFromOpen`, `earlyDraws`), `SHADE_TONE_APPLY` (`source=estimate|cached|grid`, `luma`, `lumaP90`, `veil`, `halo`), `SHADE_OPEN_END`, `POPUP_CONTRAST`; `LENS_STATS` agora traz `gate=` e `gestures=`.

**Teste no aparelho (um cenário por vez):**
1. central: app preto → app branco → app colorido, incluindo puxão parcial e cancelamento; conferir `SHADE_PREFETCH state=sample` com `tFromOpen` baixo e `earlyDraws` próximo de 0;
2. pop-up sobre página branca e sobre fundo escuro/colorido (`POPUP_CONTRAST`);
3. quina superior esquerda com `liveglint` 0 / 0.10 / 0.25;
4. home lateral, toque/seleção de texto, carrossel e rolagem vertical com `lensgate` conservative e directional (`LENS_STATS gestures=`).

```sh
su -c 'grep -E "STATUSBAR_TONE|SHADE_OPEN|SHADE_PREFETCH|SHADE_TONE_APPLY|POPUP_CONTRAST|LENS_STATS" /sdcard/Download/oulg36_log.txt | tail -120'
```

**Reverter só esta rodada por prop:**

```sh
su -c 'setprop debug.oulg.veilmax 0.75; setprop debug.oulg.texthalo 0; setprop debug.oulg.popuptone 0; setprop debug.oulg.liveglint 1; setprop debug.oulg.lensgate conservative'
```

(A medição antecipada da central não tem prop própria; `debug.oulg.shadetone` diferente de 3 desliga todo o tom adaptativo.)

**Validado:** Gradle `testDebugUnitTest` (87 testes), `lintDebug` (sem categorias novas de aviso) e `assembleDebug`. AGSL só compila no aparelho.

## 0r. Atualização (2026-10-25): lente com controle de latência no pop-up

**Teste do usuário:**
- com `backdrop=hybrid`, a refração na borda ficou próxima do módulo SF;
- mas voltou o atraso atrás do pop-up ao arrastar as páginas do launcher.

A captura sempre chega atrasada; o blur do corpo, não.

**Mudança, no modo só-LSPosed** (o padrão, sem `hybrid`):
- o pop-up mantém o corpo ao vivo e a borda de luz;
- ganha a faixa refratada do `hybrid`, mas só quando o fundo está parado.

**Sinal de movimento:** os toques que o SystemUI enxerga.
- Ele só vale depois de um toque no meio da tela com a central fechada, o que prova o monitor de gestos da navegação por gestos.
- Com navegação por botões, a faixa nunca aparece.

**Regras da faixa:**
- toque fora do pop-up: a faixa some no mesmo quadro, sem fade, e as capturas param;
- volta 600 ms depois de soltar o dedo (`lenssettle`), em 180 ms;
- só volta com uma captura pedida depois disso e com no máximo 50 ms de idade (`lensmaxage`);
- tocar no próprio pop-up não esconde a faixa.

**Sinal:**
- só valem receptores que não são janela do SystemUI;
- a prova é um toque no meio da tela com a central fechada;
- ela some se esse receptor for descartado.

**Falha da faixa:** `LENS_BAND_FAILED`; só a faixa sai, o blur e a borda continuam.

**Limite:** movimento sem toque (vídeo, animação do app) só é barrado pela idade, então ali o atraso pode aparecer.

**Diagnóstico:**
- `LENS_STATS`: desenhos com pixels capturados, bloqueios por motivo e `requestToDraw` P50/P95/máx. Essa é a idade até o desenho, não até a tela.
- `TOUCH_SIGNAL`, `LENS_BAND`.

**Desligar:** `setprop debug.oulg.livelens 0`.

**Validado:** `javac` e 73 testes JUnit. **Não testado no aparelho.**

## 0q. Atualização (2026-10-25): material do modo só-LSPosed, segunda passada

**Relato (vídeo e log de 24/10):**
- estável, e Wallet PASS no VD Google às 19:51:57;
- mas tudo parecia uma placa fosca uniforme, e na central os cards se misturavam com o fundo.

**Causas lidas no código:**
- raio padrão de 180 px em todas as superfícies;
- no tema escuro, véu de 8 % branco mais fill de 4 % preto, o que deixava o card no mesmo tom do blur da central;
- a borda era a do caminho com módulo: uma linha fina em volta do contorno inteiro.

**Mudanças (só no modo só-LSPosed):**
- **Raio:**
  - pop-up e tela de bloqueio 80 px (`debug.oulg.liveradius`);
  - central 120 px (`debug.oulg.liveshaderadius`);
  - ao mudar de superfície, o raio passa de um valor ao outro em 200 ms.
- **Tema escuro:** véu de 11 % branco no pop-up e 14 % na central, sem fill escuro.
- **Borda nova, só luz** (`liveEdge`):
  - contorno forte do lado da luz e quase ausente do outro;
  - Fresnel crescendo para a borda;
  - brilho localizado no canto voltado para a luz e reflexo interno fraco no canto oposto;
  - anel de absorção suave;
  - brilho de 2 % no topo.
- **Isto não é refração:** os pixels de trás não se deslocam. Prévia no host: `docs/img/live-edge-preview.jpg`.
- **Diagnóstico:** `LIVE_STATE` por card, com superfície, raio pedido, véu e fill. `BLUR_CAPABILITY_CHANGED` só quando muda.

**Ajustes ao vivo** (sem reiniciar nada):
- `liveradius` e `liveshaderadius`: valores em px;
- `liveoptics`: 0–2, ganho da borda;
- `livesheen`: 0–0,15.

**Validado:** `javac` e 62 testes JUnit. Gradle bloqueado de novo. **Não testado no aparelho.**

## 0p. Atualização (2026-10-24): modo só-LSPosed, sem módulo KernelSU

**Ambiente novo do usuário:**
- aparelho restaurado de fábrica, DZIF, root RMG por late-load com `ksud` managerless pelo Termux;
- sem metamódulo, sem OverlayFS, sem DFReroot;
- integridade e Wallet funcionando.

**Pedido:** estabilidade (sem pisca, sem troca de cor, sem atraso atrás do pop-up) só com o APK; depois, se der, a estética do SF sem OverlayFS.

**Diagnóstico pelo código** (não medido no aparelho nesta configuração). Sem o módulo, `sfrefract` fica 0, e então:
- o pop-up usava o `HybridBackdrop`: a borda era uma captura de tela a 60 Hz, sempre atrasada. Esse é o relógio que fica para trás e depois acompanha;
- a tela de bloqueio usava o papel de parede ou uma captura a 5 Hz (papel de parede animado), porque o caminho drawable (`kgblurpath`) só valia com `sfrefract`;
- um heads-up na central trocava do caminho Samsung para o drawable quando o estado heads-up acabava: a cor mudava de repente.

**Mudança (app; espera-se reduzir o pisca e o atraso, pendente de teste no aparelho):** com `sfrefract=0`, `liveblur=1` e `backdrop=auto`, que são os padrões:
- todo card, inclusive o pop-up, usa o caminho drawable (`BackgroundBlurDrawable` desenhado com o vidro);
- nenhuma captura por quadro (a central mantém a única captura por abertura do `ShadeTone`);
- um raio só para todas as superfícies (`debug.oulg.liveradius`, padrão = `semradius`, 180);
- a iluminação de borda é a do app. **Não há lente.** Refração ao vivo exige o compositor.

**Voltar ao comportamento anterior:** `setprop debug.oulg.backdrop hybrid` ou `setprop debug.oulg.liveblur 0`. Com o módulo KernelSU (`sfrefract=1`) nada mudou.

**Também nesta rodada (após a revisão do PR):**
- se o `BackgroundBlurDrawable` não puder ser criado para um card, ele fica no blur Samsung pela vida inteira (`BLUR_DRAWABLE_FALLBACK`), em vez de cair no fundo nativo;
- o véu da central passa para a medição nova em 180 ms, em vez de num quadro só.

**Validado:** compilação com `javac` e 62 testes JUnit (inclui `LiveModeTest` e `VeilRampTest`). Lint e APK pelo Gradle não rodaram nesta sessão (Maven Central recusou as requisições); o workflow de release roda os dois. **Não testado no aparelho.**

**Teste no aparelho:**
1. Instale o APK e reinicie o SystemUI. Não defina nenhuma `debug.oulg.*`.
2. Confirme `live=true` na **última** linha `TUNING`: `su -c 'logcat -d -s OULG | grep TUNING | tail -1'`.
3. Pop-up sobre a tela inicial, arrastando as páginas: o que está atrás deve acompanhar sem atraso.
4. Tela de bloqueio, central e um heads-up que vira card da central: sem pisca e sem troca de cor.
5. Se o blur ficar forte demais no pop-up: `setprop debug.oulg.liveradius 90` (vale ao vivo).
6. Se algo piscar: `setprop debug.oulg.trace 1` e mande `logcat -d -s OULG` com `ROW_FRAME` e `DECISION`.

**SF sem OverlayFS:** nota de pesquisa em `docs/sf-sem-overlay.md`, classificada como risco alto para a Wallet e viabilidade indeterminada. Não faz parte do plano atual: a próxima etapa estética é lente procedural no AGSL do SystemUI sobre o blur ao vivo.

## 0o. Atualização (2026-10-16, sfhook v0.20 + app): contorno duplo, lente do pop-up, bloom

**Contorno duplo na tela de bloqueio (borda inferior, fundo azul).**
- No print, pixel a pixel: 0,43 a ~5 px para dentro, vale de 0,41, depois 0,60 no contorno.
- O pico interno é o hairline do app (`LiquidGlassShader`, meio ganho, centrado ~6 px para dentro).
- **Correção:** nos cards que o compositor ilumina (perfil 1, `sf.optics ≥ 9`), o app não desenha o shader de borda. O contorno do compositor fica mais largo (σ 1,13 → 1,6 px) e mais forte (`kgline` 0,55 → 0,62).
- **Host:** um contorno único (0,53 → 0,58 → 0,64 → 0,66).

**Lente do pop-up.**
- Padrão `sflens` 0,7 → 0,45 (`Tuning`). Acima de 0,5 o campo do perfil 0 rebatia o conteúdo na faixa externa do bisel.
- Em 0,45 não há rebatimento, mas a ampliação na borda chega a ~10× nos pixels externos (`field_check`).

**Bloom do pop-up.**
- Novo disco do perfil 0 com controle de bloom (`debug.oulg.sf.hubloom`): amostras mais de 0,10 de luma acima do pixel central perdem peso.
- **Desligado por padrão.** No host, com força 1,5, o vazamento do balão verde cai ~40%, mas a estrutura do fundo no miolo dobra (`render_check` 2,61 → 5,2): o texto atrás do pop-up passa a aparecer. Limiares maiores mantêm quase todo o custo e perdem o ganho.
- Com 0 o perfil 0 é bit a bit igual.

**Mantido da v0.19:** halo mais fino na tela de bloqueio e miolo no nível da borda interna.

**Teste:**
1. Instale o APK v20 (o app mudou).
2. Lib `SurfaceFlingerProp.v20.so`; confirme com `grep -c hubloom`.
3. Reinicie o SurfaceFlinger com o cache limpo.
4. Para testar o bloom: `setprop debug.oulg.sf.hubloom 1.5` e reinicie o SurfaceFlinger com o cache limpo.

## 0n. Atualização (2026-10-16, sfhook v0.19): halo da borda mais fino na tela de bloqueio; pop-up analisado, sem mudança

**Prints do usuário (v0.18), comparados com o iOS 26.**
- **Tela de bloqueio, card sobre a jaqueta:** a borda vai de 0,59 no contorno a 0,17 a ~28 px para dentro. É uma faixa iluminada larga, não um reflexo na superfície.
- **Pop-up sobre o WhatsApp:** duas manchas brancas descem da borda e um verde se espalha embaixo.

**Pop-up: sem mudança, por evidência.**
- O padrão `sflens` 0,7 dobra o perfil 0 no terço externo do bisel (`field_check`).
- No host, com ícones claros e um balão verde atrás (`bg_popup`), k = 0,7, 0,5, 0,45 e 0,4 dão quase o mesmo resultado. As manchas são o próprio conteúdo (ícones do cabeçalho, balão verde) espalhado pelo blur de miolo de 48 px perto da borda, não especular borrado.
- Opções, se o usuário quiser: reduzir o blur do miolo do pop-up ou atenuar cores muito saturadas nele. As duas mudam o pop-up, que foi declarado bom.

**Tela de bloqueio (só perfil 1):**
- o Fresnel e a faixa especular ficam 30% mais estreitos (`kgglow` 0,70: a coordenada da faixa é comprimida para a borda);
- a intensidade cai: `kgrim` 1,0 → 0,72, `kgspec` 0,30 → 0,19;
- a linha fina do contorno (`kgline`) não muda.

**Host,** brilho acima do papel de parede por faixa:

| Faixa | v0.18 | v0.19 | Redução |
|---|---|---|---|
| 6–15 px | +0,216 / +0,173 / +0,162 | +0,124 / +0,116 / +0,105 | 33–43% |
| 15–30 px | | | ~50% |
| contorno 0–6 px | | | 12–20% (pelo Fresnel que se sobrepõe) |

- Borda interna (30 px em diante) e miolo: iguais à v0.18.
- **Rollback:** `kgglow=1 kgrim=1 kgspec=0.30` dá a v0.18 bit a bit.
- Pop-up bit a bit igual.

**Teste:** lib `SurfaceFlingerProp.v19.so`; confirme com `grep -c kgglow`. O APK v16 continua.

## 0m. Atualização (2026-10-16, sfhook v0.18): miolo no nível da borda interna

**Regra do usuário:** o miolo tem a mesma luminosidade da borda interna (no máximo ~1% acima) e mais blur. Um miolo mais claro parece elevado e destrói a profundidade.

**Medido no host:** quanto cada região clareia acima do papel de parede borrado.

| | borda interna 30–45 px | miolo > 68 px |
|---|---|---|
| v0.11, pele | +0,107 | +0,202 |
| v0.11, escuro | +0,024 | +0,102 |
| v0.17, pele | +0,103 | +0,132 |
| v0.17, escuro | +0,025 | +0,072 |
| **v0.18, pele** | +0,079 | **+0,090** |
| **v0.18, escuro** | +0,028 | **+0,047** |
| **v0.18, claro** | +0,024 | **+0,014** |

Na v0.18 a diferença média entre miolo e borda interna é de cerca de +0,007.

**Mudança, só no perfil 1:**
- teto do peso do achatamento 0,5 → 0,22 (`kgflatcap`);
- ganho de 4% só no miolo profundo (`kgcoredim`, a partir de body 0,5, depois da faixa da borda). É um ganho, então o miolo continua acompanhando o fundo.

**Borda:**
- 0–30 px igual (0,290 contra 0,291);
- a faixa de 30–45 px escureceu um pouco sobre fundos escuros (−0,028 sobre pele), porque o teto agora é menor que o peso dela.

**Contraste do texto branco no pior caso:** pele 9,1, escuro 2,9, claro 4,3.

**Rollback:** `kgcorecap=80`, `kgflatcap=1` e `kgcoredim=0` dão saída bit a bit igual à v0.11.

**Teste:** lib `SurfaceFlingerProp.v18.so` (seção 4; APK v16); confirme com `grep -c kgcoredim`.

## 0l. Atualização (2026-10-16, sfhook v0.17): miolo dinâmico, sem branco fixo

**Relato do usuário (v0.16):** contorno claro, borda escurecendo (bonita) e o miolo claro de novo, branco e sem acompanhar o papel de parede. Pedido: só o contorno deve ser fixo e iluminado; o resto, dinâmico. Reduzir o clareamento em uns 50%.

**Medido no host** (luma do card menos a do papel de parede borrado; inclinação = quanto acompanha o fundo):

| | Borda | Miolo |
|---|---|---|
| v0.11 / v0.16 | +0,14 / 81% | +0,20 / 40–46% |

**Causa:** o achatamento de transmissão da v0.11 (`kgflat` 0,5 em direção a 0,40) tem peso pela rampa do miolo. A borda recebe pouco, o miolo recebe tudo, e por isso o miolo fica claro e quase fixo.

**Correção:** o peso do achatamento é limitado a 0,5 (`OULG_KG_FCAP_DEFAULT`, propriedade `debug.oulg.sf.kgflatcap`).
- Na faixa da borda o peso já fica abaixo de 0,5 até ~38 px, então ela não muda (média 0,04–0,28/255, máx 8 na ponta interna).
- O miolo recebe metade do achatamento: +0,13 e 68% (sobre pele/jaqueta), no mesmo nível da borda.

**Mantido:** o limite de 62 px do raio do miolo (v0.16) e todo o resto da v0.11.

**Rollback exato:** `kgflatcap=1` e `kgcorecap=80` dão saída bit a bit igual à v0.11.

**Contraste:** o do texto branco no pior caso, sobre o fundo escuro, cai de 3,5 para 2,9 no host. É a consequência de o miolo acompanhar o fundo claro.

Prévia: `docs/img/lockscreen-v17-preview.jpg` (v0.11 e v0.17 alternados).

**Teste:**
1. O APK da v0.16 continua.
2. Lib `SurfaceFlingerProp.v17.so` (seção 4); confirme com `grep -c kgflatcap`.
3. Reinicie o SurfaceFlinger com o cache limpo.

## 0k. Atualização (2026-10-16, sfhook v0.16): volta à estética da v0.11, miolo menos borrado

**Decisão do usuário:** a referência visual é a estética da **lib v0.11** com a correção de primeiro quadro e flicker do **APK v0.12**.
- A lib v0.12 nunca rodou no aparelho: o `oulg_live` não tem `v12.so`, e o print das 13:22 era lib v0.11 + APK v12.
- As v0.13–v0.15 mudaram a estética e foram descartadas.

**Restaurado:**
- `sfhook/refract.h`, `oulg_sf.c`, `tools/field_check.c` e `tools/refract_check.c` saem de `aa6fd27` (v0.11): bisel em curva S, Fresnel de 1,42 rad, contorno, especular, transmissão, tom frio e vibração 1,25;
- `GlassDrawable` e `BackgroundBlurBridge` saem de `aa6fd27`: hairline a meio ganho e véu da região. O app inteiro ficou idêntico ao de `431cf07` (diff vazio).

**Preservado da v0.12:**
- perfil 1 para toda linha no estado 1, inclusive em heads-up (correção do primeiro quadro);
- logs `OPTICS` e `OPTICS_FINAL`;
- pílula de excedentes;
- ferramentas de host (`kg_preview.mjs`).

**A única mudança visual:**
- o raio do blur extra do miolo continua `80·smoothstep(0,25·bisel; 1,5·bisel; depth)`, mas agora limitado a **62 px** (`OULG_KG_CCAP_DEFAULT`, propriedade `debug.oulg.sf.kgcorecap`);
- abaixo do limite, cada pixel amostra exatamente como na v0.11. Num canto de 108 px, o raio só passa de 62 px depois da faixa do bisel;
- as cores do material são ponderadas pela rampa (`oulg_bw`), não por raio/núcleo, então limitar o raio não muda a cor do miolo;
- as leituras ficam em 48, o que deixa o disco mais denso.

**Validação no host**, com máscaras por região (borda 0–45 px, transição 45–68 px, miolo > 68 px), contra a v0.11 nos fundos pele/jaqueta, escuro e claro:

| Região | Diferença contra v0.11 |
|---|---|
| borda | máx **0** em todos os fundos |
| transição | máx 3–9/255 |
| miolo | média 0,5–1,0/255, máx 3–4 |

- Com `kgcorecap=80` a saída é bit a bit igual à v0.11 em todas as regiões (rollback).
- `render_check`, perfil 1: estrutura do fundo visível no miolo 0,87 → 1,36 (mais fundo aparece), grão 0,51 → 0,40, custo igual.
- Perfil 0 bit a bit igual à v0.11.
- **Limite do host:** a entrada simulada do compositor já vem muito borrada, então 80, 62 e 48 px ficam quase iguais no render. A intensidade real só se vê no aparelho.
- Prévia: `docs/img/lockscreen-v16-preview.jpg`. De cima para baixo: v0.11, v0.16 (62), 48 px, v0.15.

**Teste:**
1. Instale o APK v0.16, que é igual ao v12.
2. Lib `SurfaceFlingerProp.v16.so` (seção 4; nome novo); confirme com `grep -c kgcorecap`.
3. Reinicie o SurfaceFlinger com o cache limpo.

Comparar o raio no aparelho (lido na compilação; reinicie o SurfaceFlinger com o cache limpo a cada troca):
- `setprop debug.oulg.sf.kgcorecap 80`: v0.11 exata;
- `setprop debug.oulg.sf.kgcorecap 48`: miolo ainda mais claro;
- vazio: 62.

## 0j. Atualização (2026-10-16, sfhook v0.15): miolo quente, "blur em vez de vidro"

**Print da v0.14 no aparelho:**
- gota dupla resolvida: um contorno único (0,52) e miolo liso (0,29–0,32);
- miolo quente: na região do rosto, R−B fica em 0,10 na mediana e 0,27 no p95 (borrão âmbar);
- a faixa de cima puxa o azul da camisa (croma 0,18).

**Origem do tom quente:**
- o disco de 80 px espalha pele, couro e mãos em manchas largas;
- a vibração (1,12) e o ganho de transmissão da v0.14 (até 1,8×) aumentavam esse croma. No host, o croma do miolo subiu 58% da v0.13 para a v0.14.

**"Blur em vez de vidro":** o miolo era só fundo filtrado; faltava o reflexo da própria face do vidro.

**v0.15, só perfil 1 (a borda não mudou):**
- vibração 1,0;
- ganho de transmissão limitado a 1,35×;
- neutralização do croma quente (`sf.kgwarm` 0,6): onde R > B, o croma cai 60%; tons frios mantêm o croma;
- tom frio 0,5 → 0,7 (neutro em luma);
- reflexo de superfície (`sf.kgsheen` 0,05): ambiente frio refletido pela face do vidro, 5% em cima caindo a 0,5% embaixo, mais fraco sobre fundo claro.

**Host** (fundo recortado do print do aparelho, logo abaixo do card):

| | v0.14 | v0.15 |
|---|---|---|
| R−B p99 | 0,12 | 0,02 |
| croma p95 | 0,19 | 0,16 |

- Pop-up bit a bit igual; central e app intocados.
- Prévia: `docs/img/lockscreen-v15-preview.jpg` (em cima v0.14, embaixo v0.15).

**Teste:** lib `SurfaceFlingerProp.v15.so` (seção 4; o APK da v0.14 continua); confirme com `grep -c kgsheen`; reinicie o SurfaceFlinger com o cache limpo; mande um print na mesma posição.

## 0i. Atualização (2026-10-16, sfhook v0.14 + app): "duas gotas" e miolo leitoso

**Medido no print da v0.13**, na vertical do card: contorno 0,59, vale 0,33, **segundo anel 0,49 a ~19 px para dentro**, miolo 0,32–0,34. Embaixo, anel interno 0,42 e contorno 0,41.

**Contribuições de borda que competiam** num card da tela de bloqueio:
1. Fresnel do compositor (~8 px externos).
2. Contorno do compositor (gaussiana de 1,6 px).
3. **Crescente especular do compositor a ~20 px para dentro**, mais um oposto embaixo: era o segundo anel.
4. **Hairline do app** (`LiquidGlassShader`, meio ganho): três linhas com franja cromática centradas a ~6 px para dentro, ou seja, um segundo contorno.
5. Faixa de refração mostrando o conteúdo escuro comprimido entre os anéis (o vale).

**Miolo leitoso:**
- o véu branco de 8% da região (`semalpha` sobre o tom Samsung);
- o clareamento por luz aditiva, que acinzenta.

**Correção:**
- **Compositor (só perfil 1):** um único termo de borda (Fresnel e contorno somados num só peso, um pouco mais forte do lado da luz) e um brilho que decai da borda (5 px) no lado iluminado. Sem anel interno.
- **Transmissão por ganho** (0,6× a 1,8×, preserva o croma) em direção a 0,48. Só o que o ganho não alcança (quase preto) recebe luz espalhada.
- **App, só nos cards em que o compositor ilumina o perfil 1:**
  - não desenha o shader de borda (`GlassDrawable`);
  - a região vai sem cor (`BackgroundBlurBridge`, quando o perfil 1 é enviado e `sf.optics ≥ 9`).

**Host:** o perfil vertical, que na v0.13 tinha contorno, vale e anel, agora é um contorno único decaindo de forma monotônica (0,81 → 0,44 → 0,37 → miolo 0,32–0,44). Contraste do texto branco no pior caso 2,9 → 3,1. Prévia: `docs/img/lockscreen-v14-preview.jpg` (v0.13 e v0.14 alternados).

**Pop-up:** perfil 0 bit a bit igual; o shader de borda e o véu continuam (`compositorLit` só vale para o perfil 1). **Central:** intocada.

**Teste:**
1. Instale o APK novo.
2. Troque a lib com o nome `SurfaceFlingerProp.v14.so` (seção 4). **Nunca reuse um nome montado.**
3. Confirme com `grep -c "exp(-px / 5.0)"`.
4. Reinicie o SurfaceFlinger com o cache limpo.
5. Mande um print na mesma posição.

## 0h. Atualização (2026-10-16, sfhook v0.13): "fumê" e "almofada" na tela de bloqueio

**Confirmado no aparelho (v0.12):**
- a notificação nasce no estado final (`OPTICS_FINAL ... frames=1 firstFrame=true`, com `headsUp=true` no estado 1);
- no estado 2 o card fica sem marca (`tag=-1`), como previsto.

**Diagnóstico pelo print da v0.12.** Perfil de luma na vertical do card, medido no print:

| Posição | Luma |
|---|---|
| contorno | 0,51 |
| vale a ~28 px para dentro | 0,17 |
| platô uniforme | 0,29 |
| vale | 0,24 |
| contorno | 0,40 |

- O olho lê esse sombreado como relevo: borda clara, sulco escuro e miolo plano elevado é a almofada.
- **Causas no código:**
  - o blur do miolo só chegava ao raio total a 0,85·r. A ~28 px ele era 14 px, e o fundo escuro passava quase cru;
  - o achatamento de tom tinha peso pela profundidade (0,35 na borda) e escurecia as altas luzes tanto quanto clareava as sombras. Resultado: miolo uniforme e escurecido (fumê) ao lado de uma borda com o fundo escuro original;
  - o domo da v0.12 espalhava a lente pelo raio inteiro, e o card todo virava uma lupa (inflado).

**Correção (só perfil 1):**
- lente de borda numa faixa de `r/2` (54 px), deslocamento `A·b·t³`, `A = min(0,5·k; 0,25)`. Ampliação 2,5× na borda (igual à do pop-up), caindo de forma monotônica, sem pico interno e C2 no fim da faixa;
- blur do miolo `80·smoothstep(0,02; 0,38; depth/r)`: raio total a 41 px, logo atrás da lente;
- transmissão uniforme no card inteiro: sombras sobem 55% do caminho até 0,44 (luz espalhada); altas luzes descem metade disso (ganho);
- vibração 1,25 → 1,12;
- Fresnel `tilt·t²`, restrito aos pixels externos; especular estreito em `t = 0,62`.

**Host** (`kg_preview`, mesmo recorte):

| | v0.12 | v0.13 |
|---|---|---|
| miolo médio | 0,43 | 0,46 |
| p05 | 0,32 | 0,35 |
| p95 | 0,54 | 0,59 |
| contraste do texto branco no pior caso | 3,5 | 2,9 |

- O contraste caiu no host. No aparelho os cards saem ~0,14 de luma mais escuros que no modelo, então o contraste real deve ficar acima disso. Confirmar com print.
- Prévia: `docs/img/lockscreen-v13-preview.jpg`, v0.12 e v0.13 alternados, sobre a jaqueta e sobre a grade.

**Pop-up:** bit a bit igual (`render_check` max 0). **Central:** intocada. **App:** sem mudança nesta rodada.

**Teste:** lib `SurfaceFlingerProp.v13.so` (seção 4); confirme com `grep -c 0.0128`; reinicie o SurfaceFlinger com o cache limpo. Mande um print na mesma posição do anterior.

## 0g. Atualização (2026-10-16, sfhook v0.12): "lombada" e chegada tardia na tela de bloqueio

Estado de partida confirmado pelo usuário: `VS_OK=24 FS_OK=36 FAIL=0`, `sf.optics=9`. Nada abaixo foi validado no aparelho.

### Lombada: causa e correção (só perfil 1)

- **Causa, no código:** o perfil 1 deslocava a amostra por uma curva em S, `G(t) = t²(3−2t)`, só numa faixa de `0,42·r` (45 px num card de meia altura 108). O Jacobiano dessa curva é mínimo no meio da faixa, então a ampliação subia de 1,05× na borda a **2,46× a 20–25 px para dentro** e voltava a 1,00× a 45 px. Daí para dentro o miolo era plano, com o blur subindo de 11 a 68 px e o achatamento de tom aplicado só lá.
  - Resultado: anel de ampliação máxima (o "ombro"), platô plano e um degrau de luminância entre a borda intocada e o miolo achatado.
- **Correção: o perfil 1 virou um domo.**
  - A lente cobre o raio inteiro do canto (num card-pílula, até a linha central): `t = 1 − depth/r`, deslocamento `A·r·t²`, `A = min(0,6·k; 0,35)`.
  - A ampliação cai de forma monotônica, de 1,91× na borda a 1,00× no centro, sem pico interno e sem dobra (`field_check`).
  - Blur do miolo `80·smoothstep(0,04; 0,85; depth/r)`: mesma coordenada da refração.
  - Achatamento de tom com peso de 0,35 na borda a 1 no miolo. Antes ia de 0 a 1, e esse era o degrau de luminância.
  - Inclinação de Fresnel `kgtilt·t` (a inclinação de um perfil `t²`), especular como gaussiana em `t = 0,74`.
- **Pop-up (perfil 0):** bit a bit igual (`render_check` max 0). **Central (perfil 2):** código intocado.
- Prévia: `docs/img/lockscreen-v12-preview.jpg`, v0.11 em cima e v0.12 embaixo, sobre a jaqueta e sobre uma grade.

### Chegada tardia: causa provável e correção

- **Causa provável (código, não trace):** `Eligibility.keyguardOptics` negava o perfil 1 a qualquer linha em estado heads-up.
  - Uma notificação que chega na tela de bloqueio fica em heads-up enquanto entra na pilha. Nesse tempo ela recebia o perfil 0 (material do pop-up) e só trocava para o perfil 1 quando o heads-up terminava.
  - O caminho (drawable) não muda; só o perfil da marca. Isso bate com "chega de um jeito e assenta depois".
- **Correção:** no estado 1, toda linha recebe o perfil 1, inclusive em heads-up. No estado 2 e sobre apps (estado 0), o heads-up mantém o perfil 0. Pop-up sobre apps intocado.
- **Instrumentação** (sempre ligada, só nos estados 1 e 2, no máximo 60 linhas por card, reinicia a cada troca de estado):
  - `OPTICS`: uma linha por desenho em que o estado muda, com `dt` (ms desde a criação do card), `sinceBar`, `frame`, caminho, `tag`, `headsUp`, `pinned`, `alpha` (1/partial/0), `scaled`, `fallback`, `stacked` e `final`;
  - `OPTICS_FINAL`: primeiro desenho no estado final (perfil 1, caminho drawable, opaco, sem escala), com `firstFrame=true|false`.
- Se o atraso continuar, o trace diz qual campo muda em ~2 s: `tag` (perfil), `alpha=partial` (fade de entrada mistura o fundo nítido na região), `scaled` (marca perdida no shader) ou `fallback`.

### Teste no aparelho

1. Instalar o APK; trocar a lib ao vivo (seção 4) com `SurfaceFlingerProp.v12.so`; confirmar com `grep -c "smoothstep(0.04"`; reiniciar o SurfaceFlinger com o cache limpo.
2. Na tela de bloqueio, com a tela ligada, mandar uma notificação para si mesmo e depois:
   `su -c 'logcat -d | grep -E "OPTICS|BAR_STATE|HEADSUP" > /sdcard/Download/optics.txt'`
3. Prints: a tela de bloqueio parada, e um vídeo da chegada de uma notificação.

## 0f. Atualização (2026-10-16, sfhook v0.11): material da tela de bloqueio e pílula de excedentes

Nada disto foi validado no aparelho. A validação foi no host (render WebGL do shader real sobre recortes das capturas do usuário).

### Tarefa 1: material do perfil 1 (só `sfhook/refract.h`; pop-up e central intocados)

- **Causa do contorno cinza, achada no código:** o reflexo de Fresnel usava inclinação máxima de 1,2 rad, o que limita `F` a 0,10 na borda. Por isso `kgoptics` 0 contra 1 mudava só 1 a 6 de RGB.
- **Mudanças:**
  - inclinação na borda 1,42 rad (`sf.kgtilt`), ambiente frio e mais claro em cima;
  - contorno fino (gaussiana de 1,6 px a partir da borda, `sf.kgline` 0,55);
  - faixa especular separada do contorno, no meio do bisel (`sf.kgspec` 0,30);
  - transmissão adaptativa no miolo: a luma vai metade do caminho até 0,40 (`sf.kgflat` 0,50, `sf.kglevel` 0,40). Escurecer é ganho; clarear soma luz fria espalhada, não ganho;
  - tom frio neutro em luma (`sf.kgcool` 0,5);
  - miolo de 80 px com 48 leituras (era 56/40).
- **Medido no host** (`sfhook/tools/kg_preview.mjs`, recorte da jaqueta escura de `lockscreen-target.jpg`): desvio da luma do miolo 0,151 → 0,072; p95 0,64 → 0,54; contorno superior +0,02 → +0,18 sobre o miolo; contraste de texto branco no pior caso 2,5 → 3,5. `render_check`: perfil 0 bit a bit igual (max 0); estrutura do miolo 1,61 → 0,90; grão 0,45 → 0,54.
- Comparação visual (em cima v0.10, embaixo v0.11): `docs/img/lockscreen-v11-preview.jpg`.
- **Limite do host:** a entrada real do compositor (fator de redução e filtro da Samsung) não é conhecida, e o aparelho escurece os cards mais do que o modelo. Os números são relativos.
- Troca ao vivo: `SurfaceFlingerProp.v11.so`; confirme com `grep -c kgflat`. `sf.optics` continua 9.

### Tarefa 2: pílula de excedentes

- **Hipótese:** a pílula é o `NotificationBackgroundView` da `NotificationShelf` (no AOSP a shelf é uma `ActivatableNotificationView`). O hook de desenho ignorava fundos fora de uma linha.
- **Mudança:** na tela de bloqueio (estado 1, caminho drawable), o fundo da shelf recebe vidro leve: blur simples sem marca (`debug.oulg.pillblur`, 40 px), o véu e a borda do módulo. Fora disso fica nativo. `debug.oulg.pillglass=0` desliga.
- **Logs de identificação:**
  - `PILL_GLASS`: o vidro engatou (classe, tamanho, raios, drawable nativo);
  - `BACKGROUND_HOSTLESS`: um fundo de notificação fora de linha e de shelf;
  - `OVERFLOW` (com `debug.oulg.trace=1`, 1,2 s após entrar na tela de bloqueio): todas as views com shelf, icon container, overflow ou footer no nome, com fundo e campos `Drawable`.
- Se a pílula continuar cinza e não houver `PILL_GLASS`, ela é outra view; o `OVERFLOW` diz qual.

## 0e. Atualização (2026-10-16): central resolvida; faltam a tela de bloqueio e a pílula de excedentes

### Estado do repositório

- `main` em `82423d9`, com os PRs #27 e #28 mergeados.
- **Nenhuma release foi gerada por esses merges.**
  - `release.yml` só roda por `workflow_dispatch`.
  - `trigger-release.yml` só roda quando `.github/release-trigger` é enviado ao `main`.
  - Para publicar, dispare o workflow ou envie esse arquivo.
- Propriedades setadas à mão no aparelho devem ser apagadas: os padrões do código já são os valores bons. `debug.oulg.sf.optics` fica em 9.

### Central de notificações (desbloqueada): resolvida e confirmada pelo usuário

O usuário aprovou o resultado. Ela fica estável, sem pisca, sem fantasma, sem clarão e sem travada na abertura. Resta só um "buf" leve ao abrir ou fechar muito devagar, que o usuário classificou como detalhe.

**Não mexer sem trace.** As tentativas de suavizar o "buf" pioraram tudo e foram revertidas (commit `b643c60`).

Como a central funciona agora, com as causas provadas por trace:

- **Sem a marca do sfhook.** Com `shadetone=3`, que é o padrão, os cards da central nunca levam a marca de perfil 2.
  - A marca só era reconhecida pelo compositor em alguns quadros. Isso acontecia em escala fracionária, ao trocar de programa e até parado no caminho drawable. Esse era o pisca.
  - O tom vem de `ShadeTone`: uma captura por card quando a central abre, que mede a luma média atrás do card e define um véu fixo até a central fechar.
  - O véu é preto até `shadecap` (0,55) para texto claro e branco até `shadelift` (0,45) para texto escuro.
  - A leitura da GPU roda numa thread separada. Na thread principal, ela travava a animação de abertura.
  - Enquanto a medição nova não chega, o card usa o véu medido na abertura anterior.
- **Caminho drawable na central.** Com `shadeblurpath=1`, os cards usam `BackgroundBlurBridge`.
  - O alpha da região `semSetBlurInfo` da Samsung não acompanha o esmaecimento do card. Era o fantasma.
- **Região inteira ou nenhuma.** Com `shadefadecut=0.5`, a região fica opaca e some quando o alpha acumulado do card cai abaixo de 0,5.
  - Uma região parcialmente transparente neste firmware mistura o fundo nítido no blur (a pílula branca). Isso é dedução, não medição no compositor.
- **Linhas encolhidas.** Na central desbloqueada, o One UI escala todas as linhas ao fechar.
  - A regra que suspendia a região de qualquer linha encolhida (0fd9e01) agora só vale para filhos de grupo nessa superfície.
  - Fora dela, por exemplo na tela de bloqueio, a regra antiga continua.
- **Região de blur sempre opaca.** O alpha nativo do card (204 na tela de bloqueio) não é mais repassado à região de blur.
  - Esse era o "borrão sujo" da tela de bloqueio.
- **Traces disponíveis** com `debug.oulg.trace=1`:
  - `ROW_FRAME`: por card, com alpha, escala, clip, caminho, marca enviada e alpha da região;
  - `SHELF_FRAME`;
  - `PANEL_BLUR`: o blur da própria central, que é uma `SemBlurInfo` só com raio, de 1 a ~50, sem cor e sem curva;
  - `SHADE_TONE`.

### Tarefa 1: estética da tela de bloqueio

Estado atual:
- Caminho drawable (`kgblurpath=1`) e perfil 1.
- `kgblurpath=0` está **proibido**: o usuário confirmou que volta o pisca na tela de bloqueio.
- Desde `9c17579`, o miolo está bem desfocado e a borda tem efeito de vidro. O usuário diz que melhorou, mas ainda não está como deveria.

Expectativa do usuário, ilustrada em `docs/img/lockscreen-target.jpg`, que é uma imagem gerada como referência e não uma captura. Compare com `docs/img/lockscreen-current.jpg`.
- Miolo com blur forte e homogêneo, sem deixar áreas escuras do fundo dominarem o card.
- Transição gradual do miolo para uma faixa de lente suave na borda.
- Contorno fino e brilhante.
- Reflexo especular entre o contorno e o miolo, que dá espessura e elevação.
- Leve tom frio e azulado.

Laudo técnico do usuário, resumido:
- O problema está na composição do material, não em valores de `kgoptics`, `sflens` ou raio.
- `kgoptics` 0 contra 1 deu diferença média RGB de apenas 1 a 6 entre os prints.
- Hoje três camadas montam o material sem um modelo óptico comum:
  - `BackgroundBlurDrawable`: região e marca;
  - `sfhook/refract.h`: reamostragem, blur do miolo, lente, saturação e reflexos do perfil 1;
  - `GlassDrawable`/`LiquidGlassShader`: preenchimento, contorno, realces e sombra.
- Proposta do laudo:
  1. Transmissão adaptativa no miolo. Recuperar regiões escuras de forma suave, como hoje se comprimem as altas luzes.
  2. Refração e filtragem coordenadas: detalhe na borda, reconstrução uniforme no miolo, transição contínua. Reavaliar o disco Vogel com rotação por pixel.
  3. Iluminação da borda calculada a partir do mesmo modelo de espessura, transmissão e luminância do miolo.
- **Não pedir ao usuário mais testes de propriedade às cegas.** Ele explicitou isso. Investigar no código e validar com prints ou traces.
- **Preservar** o pop-up (perfil 0) e a central.

Pontos de partida no código:
- `sfhook/refract.h`, perfil 1: `OULG_FS_LENS_FMT`, tom, reflexos e parâmetros por perfil em `oulg_profile`.
- `GlassDrawable` e `LiquidGlassShader`: com `setCompositorLit(true)`, o app reduz o próprio contorno quando o compositor ilumina a borda.
  - No teste, `sf.optics` 0 contra 9 não mudou nada visível.
- Mudanças no sfhook exigem trocar a biblioteca ao vivo e reiniciar o SurfaceFlinger com o cache limpo (seção 4). O usuário **não pode** fazer reboot completo.
- Teste decisivo ainda não feito: `debug.oulg.sf.debug=1`. Ele mostra qual programa desenha o card da tela de bloqueio: magenta é FillRRect, ciano é o programa de recorte.

### Tarefa 2: pílula de excedentes na tela de bloqueio

- Com várias notificações na tela de bloqueio, aparece embaixo uma pílula com ícones das notificações que não cabem, para expandir a lista. Exemplo: dois ícones do GitHub em `docs/img/lockscreen-current.jpg`.
- Ela está cinza, opaca e sem vidro, destoando dos cards.
- **Pedido:** vidro leve, com transparência e blur suave. Não precisa do material completo dos cards.
- **Primeiro passo:** identificar a view. Provavelmente é a shelf da tela de bloqueio ou um contêiner de ícones. Um `BLUR_CENSUS` ou um `Probe.hierarchy` com trace ligado deve achar.
- Com `shadetone=3`, que é o padrão, nada recebe a marca de perfil 2: `tagForeign` devolve `off`, e a shelf da central fica sem marca.
- A pílula pode ser essa mesma shelf. Confirme no trace antes de supor.



## 0d. Atualização (port offline para One UI 9 Stable, S938BXXUCDZIF)

Registro completo em `docs/dzif-port.md`.

- **`customize.sh` virou allowlist.** Era um `EXPECTED` único, que não consegue
  aceitar dois firmwares. Agora é `ALLOWED_FINGERPRINTS`, uma linha por
  `ro.build.fingerprint`, com ZZIC preservado. A iteração usa `IFS` só em nova
  linha e `set -f` (sem glob), e um `ro.build.fingerprint` vazio aborta.
- **DZIF está na allowlist**, com a linha
  `samsung/pa3qxxx/pa3q:17/CP2A.260605.016/S938BXXUCDZIF_OXMCDZIF:user/release-keys`
  lida por `getprop ro.build.fingerprint` no aparelho. Ela ficou de fora
  enquanto só havia fingerprints de partição: a de `/system` traz `qssi_64` no
  lugar do device e nenhum sufixo de CSC, e a de `/vendor` traz um release e um
  build id de outra linha (`15/AP3A...`). As três formas erradas estão
  exercitadas como casos negativos, porque cada uma é um palpite plausível.
- **O patch do SurfaceFlinger foi reproduzido contra o binário DZIF exato**, não
  assumido: exit 0, biblioteca `SurfaceFlingerProp.so`, **88 bytes** alterados,
  tamanho inalterado, `DT_DEBUG` 1→0, `DT_NEEDED` 55→56, segunda execução dá 2.
  O binário DZIF continua importando `glShaderSource`, `eglGetProcAddress`,
  `glCreateShader`, `glCompileShader` e `glGetUniformLocation`.
- **`framework.jar` e `app_process64` são byte-identical a ZZIC**, então as APIs
  de blur da Samsung e a cadeia LSPosed não mudaram.
- **Os shaders continuam não validados.** Os programas GLSL são gerados em tempo
  de execução; só o dump em `/data/misc/surfaceflinger/oulg_shaders.txt` no
  aparelho responde isso.

## 0c. Atualização (sfhook v0.10: legibilidade da central e correções da revisão)

Detalhes técnicos em `docs/architecture.md`, seção "Shade legibility, review fixes".

- **Central sobre app branco.**
  - Os cards da central recebem o perfil 2: sem lente e sem blur extra, só um mapeamento de tom contra a cor do texto. Com texto claro, o brilho máximo do fundo vai a 0,55; com texto escuro, o mínimo sobe a 0,45. Abaixo de 0,43 nada muda.
  - Liga e desliga ao vivo com `debug.oulg.shadetone`. Os limites, lidos na compilação, são `debug.oulg.sf.shadecap` e `.shadelift`.
- **Iluminação da borda na tela de bloqueio.** O app só deixa de desenhar a própria iluminação quando o SurfaceFlinger confirma a lib (`debug.oulg.sf.optics=9`) e a marca chega intacta (raio entre 42 e 127,5).
  - A lib define a propriedade sozinha; o SELinux pode recusar.
  - Na troca ao vivo, confirme com `getprop debug.oulg.sf.optics`. Se vier vazio, rode `setprop debug.oulg.sf.optics 9`.
- **Força da lente.** O teto do perfil 1 caiu de 0,55 para 0,5: ampliação máxima de 4x.
- **Troca ao vivo da lib:** `SurfaceFlingerProp.v10.so`. Confirme com `grep -c shadecap`.

## 0b. Atualização (sfhook v0.9, material da tela de bloqueio)

Detalhes em `docs/architecture.md`, seção "Lockscreen material: one thick-slab model".
- **Pop-up intocado:** perfil 0, bit a bit igual à v0.7. A central continua sem marca.
- **Tela de bloqueio (perfil 1):** um único modelo de placa espessa.
  - Refração em curva S, que nunca dobra.
  - Reflexão de Fresnel e brilho especular calculados no compositor a partir do fundo real.
  - Miolo de 56 px com 40 leituras, vibração e joelho de brilho.
  - Nesses cards, o shader de borda do app deixa de desenhar as faixas de luz e a sombra.
- **Ao vivo:**
  - `debug.oulg.kgoptics` (0 = material do pop-up);
  - `debug.oulg.kglens` (força da tela de bloqueio, padrão 0,40);
  - `debug.oulg.huoptics=1` (prévia do material novo no pop-up).
- **Na compilação:** `debug.oulg.sf.kgcore`, `.kgtaps`, `.kgramp`, `.kgsat`, `.kgtone`, `.kgrim`, `.kgspec`.
- **Troca ao vivo da lib:** `SurfaceFlingerProp.v9.so`. Confirme com `grep -c kgspec`.
- **Protocolo:** o mesmo da seção 0a. No passo 4, compare também `kglens` 0,30, 0,40 e 0,50.

## 0a. Atualização (sfhook v0.8, perfis ópticos por superfície)

Detalhes técnicos em `docs/architecture.md`, seção "One optical library for both programs; per-surface profiles".

- **Hipótese do miolo ruim com `kgblurpath=1`.** O programa de recorte (`oulg_rewrite_clip`) só deslocava a amostra: não aplicava a B-spline nem o blur do miolo. Se o caminho do drawable for desenhado por ele, o miolo fica exatamente como o relatado. **Falta a prova no aparelho**, que é o teste de cor do passo 3 abaixo.
- **Correção.** Os dois programas usam a mesma biblioteca GLSL: o campo, a B-spline e o disco.
- **Perfis por superfície.** A marca carrega o perfil:
  - Pop-up: perfil 0. A saída dele é bit a bit igual à da v0.7.
  - Tela de bloqueio: perfil 1, com miolo de 56 px, 32 leituras e saturação de 1,25.
  - A/B ao vivo, sem reiniciar o SurfaceFlinger: `debug.oulg.kgoptics` (1 = material novo, 0 = material do pop-up).
  - Parâmetros do perfil 1, lidos na compilação: `debug.oulg.sf.kgcore`, `.kgtaps`, `.kgramp`, `.kgsat`.
- **Testes no host:** `sfhook/tools/check.sh` (Jacobiano, marcas, compilação e link GLSL) e `sfhook/tools/render_check.mjs` (render WebGL2 dos dois programas e dos dois perfis).

### Protocolo de validação no aparelho (v0.8)

1. Troca ao vivo da lib, com o procedimento da seção 4 e o nome `SurfaceFlingerProp.v8.so`. Confirme com `nsenter -t 1 -m -- grep -c kgsat /system/lib64/SurfaceFlingerProp.so`, que deve dar 1 ou mais.
2. Reinicie o SurfaceFlinger com o cache limpo (seção 4). Critério: `FAIL=0` no diagnóstico de shaders.
3. **Teste decisivo do programa** (antes de avaliar a estética):
   - `setprop debug.oulg.sf.debug 1`, reinicie o SurfaceFlinger com o cache limpo e use `kgblurpath=1`.
   - Card da tela de bloqueio **ciano**: é o programa de recorte, a hipótese se confirma e a v0.8 corrige.
   - Card **magenta**: é o FillRRect, e a diferença vem da textura de entrada. Mande a linha `BLUR_DRAWABLE_API` do log.
   - Card **sem cor**: é uma terceira família de programa. Mande o `oulg_shaders.txt`.
   - Volte com `sf.debug=0` e reinicie de novo.
4. A/B estético na tela de bloqueio, ao vivo: `setprop debug.oulg.kgoptics 1` e depois `0`.
5. Regressão do pop-up: o perfil 0 tem que sair idêntico ao da v0.7. Compare uma notificação real do WhatsApp com botões, sobre fundo claro e sobre fundo escuro, e texto grande de alto contraste cruzando a borda.
6. Custo de GPU:
   - `dumpsys SurfaceFlinger --timestats -clear -enable`;
   - 30 s de arrasto na tela de bloqueio;
   - `--timestats -dump > /sdcard/Download/ts_kgN.txt`, com N = 0 ou 1 conforme o `kgoptics`, e depois `--timestats -disable`.
   - Critério: mediana e p90 de `renderEngineTiming` com `kgoptics=1` no máximo ~1 ms acima de `kgoptics=0`, e sem aumento de quadros perdidos.
7. Estabilidade: ciclos de bloqueio, meio-arrasto, toque, subida ao bouncer e economia de energia. Os defeitos já resolvidos (fantasma, cinza, piscadas) não podem voltar.

## 0. Atualização (sessão seguinte, PR #21)

Documento compartilhado com a investigação completa: <https://claude.ai/code/artifact/06632b0e-8a50-473c-af62-3373690088d1>. Estado final:

- **sfhook v0.7 (blur em camadas)** confirmado no aparelho: `VS_OK=24 FS_OK=36 FAIL=0`. No dump real de 138 shaders, os 47 programas reescritos compilam.
  - Chaves, lidas na compilação: `debug.oulg.sf.core` (48 px), `debug.oulg.sf.taps` (24), `debug.oulg.sf.ramp` (1,5).
- **Tela de bloqueio resolvida e confirmada pelo usuário em vários ciclos:** sem cinza, sem piscadas, sem fantasma, mesma aparência de antes.
  - **Curva neutra:** `semcurve=auto` aplica `0,0,0,255,0,255` nos cards com lente, que deixam de herdar a curva escura do painel.
  - **Blur desenhado junto com o vidro:** na tela de bloqueio, o blur vem de um `BackgroundBlurDrawable` desenhado dentro do vidro, com os mesmos limites, raio e marca da lente (`debug.oulg.kgblurpath=1`, padrão). O blur Samsung instalado na View seguia os limites da View e atrasava um quadro.
  - **Véu e raio:** véu igual ao do caminho Samsung; raio em px por `debug.oulg.kgblurradius` (-1 = `semradiuslens`).
  - **Fantasma:** a região de blur recebe o alpha acumulado das Views pai.
- **Economia de energia:** no modo padrão o efeito se mantém. No máximo, a Samsung desativa todos os blurs; é o comportamento esperado.
- **Correções a este documento:**
  - O item 3 da seção 7 (clamp do `scrim_notifications`) estava errado: aquela camada tem tamanho 0x0. O clamp é inofensivo.
  - O handoff anterior chegou a dizer que os defeitos estavam fora do alcance do app. Uma revisão externa mostrou que não estavam.
- **Limpeza:** os diagnósticos e as tentativas sem efeito saíram do código no commit de limpeza do PR #21. A tabela de commits do documento compartilhado registra o que cada um fez.
- **Build do app nesta nuvem:** o Maven Central respondeu 429. Use um init script do Gradle que troque `repo.maven.apache.org` por `maven-central.storage-download.googleapis.com/maven2/`.

## 1. Objetivo e preferências do usuário

- Liquid Glass no estilo iOS 26 nas notificações do One UI 9. Aparelho: Samsung S25 Ultra (SM-S938B), firmware S938BXXUCZZIC, Android 17.
- Superfícies e o que o usuário quer em cada uma:
  - **Pop-up (heads-up) e tela de bloqueio:** vidro com refração real e ao vivo na borda (opção 3: SurfaceFlinger) e blur no miolo.
  - **Central de notificações:** blur difuso, sem refração ("não tem o que refratar").
- Estilo de resposta: clínico, direto, brutalmente honesto, sem animação de torcida. Responder em português.
- Restrições operacionais, obrigatórias:
  - Os comandos de aparelho são para o Termux com root, sempre na forma `su -c '...'`. **Não mande comandos `adb`.**
  - **O usuário não pode fazer reboot completo**: o root morre. Toda troca de biblioteca é feita ao vivo (seção 4).
  - O buffer do logcat tem no máximo 5 MB. Para logs grandes, mande gravar em arquivo em `/sdcard/Download/` e peça o arquivo.
  - Com `su -c`, use sempre aspas simples em volta do comando inteiro.
  - Nunca use `pkill -f com.android.systemui`, porque ele mata o próprio `su`. Use `kill $(pidof com.android.systemui)`.
  - Bootloop não preocupa o usuário: se acontecer, o root some e tudo volta ao normal.

## 2. Estado do repositório

- Repo `igorcv88/OneUILiquidGlass`, branch de trabalho `ccr-fa46d54c-s1s4b6`.
- **PR #20 aberto**, sem conflitos e sem threads de revisão: <https://github.com/igorcv88/OneUILiquidGlass/pull/20>.
  - O `main` está em `1c017f1` (release v0.1.16).
  - O PR carrega, em ordem: 568d86f, 3be0082, 1c0f47b, fe14371, d658650, 2a16f6d, e5f45c3, 603db97, 4e9de52, e758611, 135f4bb, mais o commit deste handoff.
  - Um merge no `main` dispara o workflow `release.yml`, que gera a release v0.1.N assinada.
- O app é um módulo LSPosed com escopo na SystemUI. O "sfhook" é um módulo KernelSU que injeta uma biblioteca no SurfaceFlinger.
- Builds, dentro da sessão de nuvem:
  - App: `export ANDROID_HOME=$(ls -d /tmp/claude-0/*/<sessão>/scratchpad/sdk | head -1); ./gradlew --build-cache -q testDebugUnitTest lintDebug assembleDebug`. O SDK estava no scratchpad desta sessão; em outra sessão talvez seja preciso reinstalar.
  - sfhook: `ANDROID_NDK=<ndk 29.0.14206865> sfhook/build.sh`, que gera `sfhook/build/oulg-sf.zip`.
  - Validação dos shaders: `gcc -I sfhook -o rc sfhook/tools/refract_check.c && ./rc <oulg_shaders.txt> <dir>` e depois `glslangValidator -S frag|vert` em cada `mod_*`.
- Até agora o usuário recebeu APKs de debug pelo chat. Os merges geram APKs de release.

## 3. Arquitetura atual

### App (LSPosed, SystemUI)

- **`hooks/HeadsUpHooks.java`**:
  - Hook de `NotificationBackgroundView.onDraw`. O desenho nativo vai para um RenderNode descartável, e o `GlassDrawable` é desenhado no canvas real.
  - `State` por linha de notificação. `material()` escolhe o tipo de fundo (`Backdrop.Kind`): SAMSUNG, SHARED, SAMPLED ou COMPOSITOR.
  - Com `debug.oulg.sfrefract=1`, o pop-up e a tela de bloqueio usam SAMSUNG: o blur da Samsung via `SemBlurInfo` com raio marcado. Com `shadeblur=1`, a central também usa SAMSUNG, mas sem marca, logo sem lente.
  - **Guarda do blur:** hook de `View.semSetBlurInfo` que bloqueia chamadas de terceiros em views gerenciadas e o clear aninhado.
  - **Estado da barra:** hook de `StatusBarStateControllerImpl.setState`, em `barState`: 0 é a central desbloqueada, 1 a tela de bloqueio, 2 a central sobre o bloqueio. A lente é decidida por `Eligibility.lens(barState, shadeExpanded, keyguard)` e reavaliada a cada mudança de estado ou da central (`refreshLens`).
  - **Scrim de notificações:** hook de `ScrimView.setViewAlpha`. A view com id `scrim_notifications` fica em 0 enquanto o estado é 1 e é zerada quando o estado volta a 1. Commit 135f4bb.
  - **Toque:** uma notificação pressionada mantém o vidro, com um véu branco `0x24ffffff`. Antes ela caía no card nativo preto.
- **`glass/SemBlurBridge.java`**:
  - Aplica o `SemBlurInfo`: modo window, raio, cor, forma `single`.
  - `setLens(bool)` liga e desliga a marca.
  - `sfTag(r, k) = floor(r) - 1 + 0.55 + 0.15*s`, com `s = (k-0.1)/1.1`. A marca nunca passa do raio original, porque o SkRRect corta raios maiores que metade da altura, e a pílula do pop-up tem altura 216 e canto 108.
  - O raio do blur dos cards com lente é `semradiuslens`; o dos outros é `semradius`.
- **`glass/Tuning.java`**: propriedades `debug.oulg.*`, relidas no máximo a cada 1 s. Qualquer mudança incrementa `generation`, e o material é reconstruído.
- **`hooks/Reflect.java`**: cache de campos e métodos por classe e nome, incluindo as buscas sem resultado. Resolveu o lag da central.
- **`diagnostics/Perf.java`**: com `debug.oulg.perf=1`, uma linha `PERF` a cada 2 s com o tempo de cada hook.
- **`diagnostics/Probe.java`**: com `debug.oulg.trace=1`, despejos de scrims, hierarquia e afins. Desligado por padrão, porque custa.

### sfhook (KernelSU + biblioteca no SurfaceFlinger)

- **`dtneeded.c`**: troca o `DT_DEBUG` do binário `/system/bin/surfaceflinger` por `DT_NEEDED "SurfaceFlingerProp.so"`. A cópia modificada do binário fica no módulo.
- **`oulg_sf.c`**:
  - Intercepta `glShaderSource` e `eglGetProcAddress`.
  - Despeja cada shader distinto em `/data/misc/surfaceflinger/oulg_shaders.txt`.
  - Reescreve os shaders: vertex shaders do FillRRect, com fallback que só zera as saídas; fragment shaders do FillRRect; e programas de recorte arredondado.
  - Compila cada reescrita na hora e volta ao original se falhar.
  - `debug.oulg.sf.norewrite=1` desliga tudo. `debug.oulg.sf.debug=1` pinta os cards marcados: magenta no caminho FillRRect, ciano no de recorte. As duas propriedades só valem com o cache limpo e o SurfaceFlinger reiniciado.
- **`refract.h`**, o núcleo:
  - **Comprovado no aparelho:** o card é desenhado pelo **FillRRectOp**. Prova: o card ficou magenta no modo debug.
  - **Vertex shader:** calcula `voulg_tag = (k ou 0, raio px, meio-tamanho px)` e `voulg_vp`, a posição normalizada. A marca vale se a fração do raio estiver em [0,55, 0,70], o raio for maior que 40 e os lados forem inteiros. O critério dos lados inteiros elimina o falso positivo da animação de abertura de apps.
  - **Fragment shader:** dentro da faixa da borda, `bevel = clamp(0,42·r, 16, 56)`. O deslocamento para dentro é `k·bevel·t²`, levado ao espaço da textura pelos Jacobianos. Não há brilho do lado do SurfaceFlinger; o módulo desenha a borda dele.
  - **B-spline (v0.6):** a textura do blur é amostrada com B-spline cúbica (4 leituras bilineares) só nos cards marcados. Isso tirou o aspecto de "144p", em que o blur de raio pequeno ampliado mostrava blocos.
- **Versão da lib em uso no aparelho:** v0.6. O arquivo está em `/data/adb/oulg_live/SurfaceFlingerProp.v6.so`, montado por bind sobre `/system/lib64/SurfaceFlingerProp.so`.

## 4. Procedimentos no aparelho (testados)

**Trocar a lib ao vivo.** Sempre use um nome novo de arquivo e **nunca sobrescreva o arquivo montado**, porque o SurfaceFlinger o mantém mapeado:
```
su -c 'cd /data/adb/oulg_live && unzip -o -j "$(ls -t /sdcard/Download/oulg-sf*.zip | head -1)" lib/liboulg_sf.so && mv -f liboulg_sf.so SurfaceFlingerProp.vN.so && chmod 644 SurfaceFlingerProp.vN.so && chcon u:object_r:system_lib_file:s0 SurfaceFlingerProp.vN.so && L=/data/adb/oulg_live/SurfaceFlingerProp.vN.so && nsenter -t 1 -m -- sh -c "while umount /system/lib64/SurfaceFlingerProp.so 2>/dev/null; do :; done; mount --bind $L /system/lib64/SurfaceFlingerProp.so"'
```
Confirme com `nsenter -t 1 -m -- grep -c <string única da versão> /system/lib64/SurfaceFlingerProp.so`.

**Reiniciar o SurfaceFlinger** com o cache limpo e o zygote junto. Leva uns 20 s. Sem o zygote, demorava de 4 a 5 minutos na tela "Powered by Android":
```
su -c 'nohup sh -c "stop surfaceflinger; rm -f /data/misc/surfaceflinger/skia_shaders /data/misc/surfaceflinger/egl_shaders /data/misc/surfaceflinger/oulg_shaders.txt; start surfaceflinger; sleep 3; setprop ctl.restart zygote" >/dev/null 2>&1 &'
```

**Diagnóstico dos shaders:**
```
su -c 'F=/data/misc/surfaceflinger/oulg_shaders.txt; echo "VS_OK=$(grep -c "===== REWRITE_OK vertex" $F) FS_OK=$(grep -c "===== REWRITE_OK fragment" $F) FAIL=$(grep -c "===== REWRITE_FAILED" $F)"'
```

**Problemas conhecidos:**
- Reinstalar o zip pelo KernelSU dá "Device or resource busy" (erro 16). Binds antigos de `/data/adb/modules_update/oulg_sf/...` ficaram presos em cerca de 990 namespaces de apps. Não há como limpar sem reboot, por isso o caminho é o `oulg_live`.
- Reiniciar só a SystemUI: `su -c 'kill $(pidof com.android.systemui)'`. As propriedades `debug.*` sobrevivem ao reinício da SystemUI e do zygote, mas não a um reboot.

## 5. Propriedades em uso no aparelho e padrões no código

O usuário achou esta combinação "mais ou menos melhor":
- `debug.oulg.sfrefract=1`: liga o modo lente. **No código o padrão é 0.**
- `debug.oulg.shadeblur=1`: blur próprio em cada card da central. **No código o padrão é 0.**
- `debug.oulg.semradiuslens=16`: raio do blur Samsung nos cards com lente. Padrão no código: 16.
- `debug.oulg.sflens=0.7`: força da lente. Padrão no código: 0,7, alterado neste commit.
- `debug.oulg.semalpha=20`: opacidade do véu do blur Samsung. No código o padrão é -1, que usa o tom do spec, 20 no modo escuro.
- `debug.oulg.semradius`: vazio, ou seja 180, usado nos cards da central.
- `debug.oulg.sf.debug=0` e `debug.oulg.trace=0`.

**Pendência:** decidir se `sfrefract` e `shadeblur` passam a ser 1 por padrão. O usuário usa os dois o tempo todo. Hoje um reboot zeraria as duas propriedades, mas o usuário não faz reboot.

## 6. O que está resolvido (confirmado no aparelho)

- **Lag da central:** era reflexão sem cache, mais varreduras de diagnóstico. A mediana caiu de 13 ms para 5 ms por quadro, igual ao módulo desligado.
- **Card preto ao tocar uma notificação na central:** era o fallback para o fundo nativo durante o toque.
- **Lente do SurfaceFlinger:**
  - funciona ao vivo e sem atraso, inclusive com movimento horizontal atrás do card;
  - aparece só no pop-up e na tela de bloqueio;
  - não dispara mais na animação de abertura de apps.
- **Raio 180 apagava a refração:** os cards com lente agora têm raio próprio (`semradiuslens`).
- **"144p":** resolvido com a B-spline na v0.6. O usuário chamou de "da água pro vinho".
- **Reinício lento do SurfaceFlinger:** resolvido reiniciando o zygote junto.
- **Avaliação do usuário na v0.6:** cerca de 25% de satisfação. A borda e a refração estão "quase impecáveis, talvez mais bonitas que no iOS". O problema principal agora é o blur do miolo.

## 7. Pendências, em ordem de prioridade

1. **Blur em camadas: miolo forte e borda leve, com transição gradual.** É o pedido principal do usuário.
   - Hoje existe um único blur (a região Samsung com `semradiuslens`) aplicado ao card inteiro.
   - O usuário quer o miolo com blur bem mais forte, pela legibilidade, e a borda com blur menor e a refração, em transição gradual. Ele chamou de "escada", mas não brusca.
   - Ele lembra que isso existia nas versões 0.1.13 e 0.1.14. Naquela época, o corpo era o blur da Samsung (raio 180) e a borda vinha da captura refratada (`HybridBackdrop`).
   - **Proposta técnica:** manter a região Samsung com raio pequeno (borda nítida, para a lente) e, dentro do fragment shader do FillRRect, nos cards marcados, sintetizar um blur maior no miolo. A ideia é amostrar a mesma textura num disco de N leituras (12 a 16, em ângulo dourado) com raio crescente conforme a profundidade a partir da borda: `raio_blur(depth) = mix(raio_borda, raio_miolo, smoothstep(0, ~1,5·bevel, depth))`. Isso dá a transição suave.
   - O custo fica restrito aos pixels dos cards marcados. Um card tem cerca de 300 mil pixels; a 16 leituras, isso é viável no Adreno 830.
   - Os parâmetros (raio do miolo e largura da transição) podem viajar na marca, para ajuste ao vivo. A marca já carrega `k`; seria preciso outro canal, por exemplo a fração do raio Y ou o tamanho. Alternativa: propriedade lida na compilação, que exige reiniciar o SurfaceFlinger.
   - **Alternativa descartada:** duas regiões de blur aninhadas (Samsung). O SurfaceFlinger desenha regiões de bordas duras, então isso daria degrau, não gradiente.
2. **Legibilidade e contraste:** véu adaptativo à luminância do fundo, calculado no próprio shader a partir da textura desfocada, e brilho de borda dependente do ambiente, como propôs o laudo do usuário. Hoje o brilho de borda é do módulo (`LiquidGlassShader`), fixo.
3. **Card escuro após meio-arrasto na tela de bloqueio:**
   - Causa provada: o `scrim_notifications` fica com `viewAlpha=1.0` (tint `#1c3433`) depois do meio-arrasto e só volta com um toque.
   - Correção: commit 135f4bb, que mantém essa camada em 0 no estado 1. O log já confirmou que ela foi identificada (`SCRIM_NOTIFICATIONS id=scrim_notifications`).
   - **Falta o usuário confirmar visualmente** que o card não escurece mais.
4. **Flicker nas bordas ao arrastar na tela de bloqueio:**
   - A região de blur do SurfaceFlinger chega um quadro atrasada em relação ao card.
   - Na v0.4 o brilho do lado do SurfaceFlinger foi removido para reduzir o efeito. O status depois disso é desconhecido.
5. **Pisca na central com raio baixo:** com raio menor que cerca de 10, a região chega atrasada durante a rolagem. Por isso a central usa `semradius` (180). Não mexer.
6. **Futuro:**
   - blur separado para a central de notificações e para o control center (hoje o Theme Park e o HomeUp aplicam o mesmo blur nos dois);
   - medir o custo de bateria;
   - avaliar se a captura do `HybridBackdrop` ainda é necessária quando `sfrefract=0`.

## 8. Erros já cometidos (não repetir)

- Atribuir o visual do aparelho à versão errada. Antes de concluir, confirme qual APK, lib e propriedades estão ativos.
- Reescrever programas de shader sem prova de que são eles que desenham o card. Use o modo `sf.debug`.
- Esquecer que o SkRRect corta raios maiores que metade do lado, o que destrói a marca.
- Fazer bind a partir de `modules_update` e depois instalar pelo KernelSU (erro 16).
- `pkill -f` matando o próprio comando.
- Filtro "dim" no Probe casando com qualquer `ImageView`.
- Usar `tail` em logs de trace grandes, que cortava as linhas importantes.
- Decidir a lente pelo `isOnKeyguard` da linha. Ele fica falso depois do meio-arrasto; use o `barState`.
- Estado de blur é propriedade da view: uma mudança de estado precisa reaplicar o blur na hora, não esperar o próximo desenho.

## 9. Arquivos de diagnóstico úteis

- Dump de shaders do aparelho, com 147 shaders, enviado pelo usuário como `oulg_shaders_v3.txt`. Ele mostra o FillRRect com 26 programas de fragmento e os programas de recorte. Para regenerar, peça `cp /data/misc/surfaceflinger/oulg_shaders.txt /sdcard/Download/` depois de um reinício com o cache limpo.
- `framework.jar` (Samsung) e `services.jar`: já analisados. `View.semSetBlurInfo` chama `invalidateBlurBackground` e `setBackground(BackgroundBlurDrawable)`.
