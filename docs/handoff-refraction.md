# Handoff: refração ao vivo nas bordas dos pop-ups

Data: 2026-10-26. Estado do repositório: `main` em `igorcv88/OneUILiquidGlass` com o PR #44 mergeado.
Escrito para uma nova instância começar do zero.

## 1. Objetivo da próxima fase

Hoje a refração na borda dos pop-ups vem de uma **captura de tela** (`CaptureHub`). A captura chega um
ou mais quadros atrasada. Para o atraso não aparecer como rastro, a borda é **desligada** sempre que o
fundo pode estar se mexendo:
- gesto horizontal;
- gesto de sistema;
- os 100 ms depois de um toque (`lenssettle`);
- os 350 ms depois de um toque ou gesto de sistema (`lenstransition`).

**Efeito colateral que o usuário quer eliminar:** mexer no meio da tela (carrossel do Instagram,
teclado) desliga a borda do pop-up, mesmo sem nada se mexendo atrás dele.

**Objetivo declarado:** refração ao vivo, sem atraso e sem esse portão. O caminho preferido é atacar o
**compositor** (SurfaceFlinger) atrás da notificação.

O usuário também aceita como passo intermediário capturar só a área da notificação.
- **Ressalva técnica:** isso não remove o atraso, que é estrutural em qualquer captura.
- **O que pode ajudar:** um portão por região, em vez de um portão global por toque. Só o que muda atrás
  do card desliga a borda. Ver §6.

## 2. Usuário e regras de trabalho

- **Idioma:** o usuário escreve em português.
- **Estilo:** clínico, direto, brutalmente honesto. Sem animação, sem hipérbole, sem "incrível".
- **Comandos de teste e log:** vão direto no chat, não em documento de handoff (pedido explícito dele).
  Este arquivo é exceção porque ele pediu um handoff.
- **Merge:** é ele quem faz o merge, depois de revisar e de testar no aparelho. Nunca declarar algo
  "funcionando" só com testes JVM; dizer explicitamente "não testado no aparelho".
- **Rastreabilidade:** cada mudança visual precisa de uma prop `debug.oulg.*` que a reverta, sempre que
  possível.
- **Falha de lente:** uma falha de refração ou de tom nunca pode derrubar o blur ao vivo do card.
- **Fluxo de PR:**
  - branch designada pela sessão, PR pronto (não draft), assinatura de PR;
  - o Codex (`chatgpt-codex-connector`) revisa cada PR aberto;
  - os achados P2 dele foram reais quase sempre: verificar o caminho, corrigir, responder no thread e
    resolver.
- **Prints:** quando o usuário manda prints, meça os pixels (PIL) antes de opinar. Várias rodadas desta
  sessão só se resolveram com números: luminância do card contra o painel, transmissão sobre cor.
  Simulações sobre o print são bem-vindas, mas devem vir rotuladas como aproximação.

## 3. Aparelho e ambiente

- **Aparelho:** Galaxy S25 Ultra SM-S938B, firmware **S938BXXUCDZIF** (One UI 9 Stable, Android 17).
- **Root:** late-load RMG (exploit a cada boot) com `ksud` managerless, administrado pelo CLI no Termux.
- **SELinux:** enforcing.
- **Prioridades do usuário:**
  - Play Integrity e Google Wallet **funcionando** são prioridade. Qualquer rota que coloque código no
    compositor precisa medir integridade e Wallet antes e depois.
  - **Sem** metamódulo, **sem** OverlayFS, `/system` intocado.
- **Hoje o módulo roda em modo "só-LSPosed":** APK com escopo SystemUI, `Tuning.live=true`, `sfrefract`
  vazio.
- **Navegação:** por gestos. O sinal de toque do `BackdropMotion` depende do input monitor de gestos;
  com navegação por botões, não há borda.

**Build no container:**
- O Android SDK foi instalado em `/root/android-sdk` (cmdline-tools, platform 36, build-tools 35.0.0).
- `local.properties` tem `sdk.dir` e está no `.gitignore`. Numa sessão nova, reinstale se faltar.

```
./gradlew --no-daemon -q :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

- **Estado em 26/10:** ~107 testes JUnit passam. O lint tem 27 warnings pré-existentes. `-q` imprime
  "Wrote HTML report" mesmo sem erro.
- **AGSL:** só compila no aparelho. O build passa mesmo com um shader inválido, então mudanças em
  `LiquidGlassShader` precisam de teste no aparelho. Se o shader falhar, o card cai no blur sem borda.

**Log no aparelho**, como o usuário costuma rodar:
1. `su -c 'setprop debug.oulg.trace 1; logcat -c'`
2. Reproduzir.
3. `su -c 'logcat -d -v threadtime -s OULG > /sdcard/Download/oulgNN_log.txt; ls -la /sdcard/Download/oulgNN_log.txt'`
4. `su -c 'setprop debug.oulg.trace 0'`

O `ls -la` confirma o tamanho: um log já veio vazio uma vez.

## 4. Mapa do código (app)

Pacote: `app/src/main/java/io/github/igorcv88/oneuiliquidglass`.

| Arquivo | Papel |
| --- | --- |
| `hooks/HeadsUpHooks.java` (~2000 linhas) | Hooks do SystemUI. Cada card é um `State` (fundo + linha) com material, tom, texto e ciclo de vida (`onPreDraw`, `release`). Também cuida do shade (gerações `shadeOpens`, `beginShadeTone`/`adoptGrid`/`endShadeTone`), do cabeçalho, do `paneldim`, das linhas órfãs e do guard de `semSetBlurInfo`. |
| `glass/GlassDrawable.java` | Desenha a borda AGSL (`edgeUniforms`) e a banda de lente (`drawLens`, `rimOnly`). |
| `glass/LiquidGlassShader.java` | AGSL. `liveEdge()` é o modelo de borda do modo live (sem deslocamento de pixels): contorno, Fresnel, glint, reflexo interno, absorção, sheen, `outline` (borda brilhante do vidro claro). `REFRACT_SOURCE` desloca amostras de uma captura. |
| `glass/BackgroundBlurBridge.java` | `BackgroundBlurDrawable` do compositor, criado via ViewRootImpl e desenhado junto com o vidro. Recebe raio, cor (tint ou veil) e cantos. A classe do firmware também tem `setBlurColorCurve`/`setBGColorAdjustment`/`setBlurGradient` (log `BLUR_DRAWABLE_API`). Não há setter de deslocamento. |
| `glass/SemBlurBridge.java` | Caminho Samsung `View.semSetBlurInfo` (`SemBlurInfo.Builder`). Também guarda a lógica de "tag" de raio usada pelo hook do SurfaceFlinger (`tagProfile`, `cornerTag`). |
| `glass/CaptureHub.java` | Única fonte de capturas: `ScreenCapture`/`captureDisplay` via `CaptureApi`, em worker. Clientes registram `view()`, `hz()`, `scale()` (padrão `SCALE` 0.5; o prefetch do shade pede 0.125), `evenHidden()`. O crop é a união das views mostradas, mais exclusões. |
| `glass/CaptureBackdrop.java` | Cliente de captura da banda de lente. Ignora quadros abaixo de `CaptureHub.SCALE`. |
| `glass/BackdropMotion.java` | O portão: sinal de toque (hook em `InputEventReceiver.dispatchInputEvent`), prova de input monitor de gestos, classificação direcional, zonas de sistema, settle, transição, `wantsFrames` (0 Hz enquanto bloqueado). |
| `glass/ShadeTone.java`, `ToneGrid.java`, `VeilMath.java`, `VeilRamp.java` | Tom e material (estilo claro/escuro, veil). Fora do escopo da próxima fase. |
| `hooks/Foreground.java` | Troca do tom do texto e ícones (com guard de `setTextColor`/`setImageTintList`). Fora do escopo. |
| `glass/Tuning.java` | Todas as props `debug.oulg.*`, lidas a cada `Tuning.get()`. Vazio vale o padrão: `setprop debug.oulg.x ""` limpa. |

**KernelSU/SurfaceFlinger (fora de uso hoje), em `sfhook/`:**
- `oulg_sf.c` interpõe `glShaderSource`/`eglGetProcAddress`;
- `refract.h` reescreve os shaders `FillRRectOp` do Skia/Ganesh;
- `dtneeded.c` aplica o patch `DT_DEBUG` → `DT_NEEDED` (88 bytes);
- `module/` traz os scripts KSU, com watchdog;
- `tools/` tem `refract_check` (replay do dump + `glslangValidator`) e previews.

**Leitura obrigatória antes de propor rota:**
- `docs/architecture.md`, seções "SurfaceFlinger probe, phase 1", "phase 2", "Phase 2 on the device",
  "Compositor lens, second attempt", "LSPosed-only mode" e "Gated lens band on LSPosed-only pop-ups";
- `docs/sf-sem-overlay.md`: as rotas sem OverlayFS (A ptrace, B bind mount, C GLES layers, D cache de
  shaders, E injeção a quente) e os riscos;
- `docs/dzif-port.md`: o port do patch para DZIF, verificado offline. Nenhum shader DZIF foi visto no
  aparelho.

## 5. Estado atual da borda do pop-up (aprovado pelo usuário)

**Props, padrões atuais (`Tuning`):**
- `livelens=1`;
- `lensgate=directional`;
- `lens=0.24` (força da lente);
- `rimhz=80` (taxa da captura);
- `lensmaxage=40` ms (idade máxima no draw);
- `lenssettle=100` ms;
- `lenstransition=350` ms;
- `liveglint=0.10`, `shadeglint=0.10`;
- `liveradius=80`, `liveshaderadius=120`.

**Visual do pop-up aprovado, que não pode regredir:**
- `livedarkalpha 20`, `livedarkfill 10`, `liverim 2`;
- vidro claro `lightglass 0.75` / `lighttone ffffff` / `lightedge 1` / `lightoutline 0.8`.

**Visual da lockscreen aprovado, que também não pode regredir:**
- `kgdarkalpha 20`, `kgdarkfill 14`, `kgrim 2`, `kgglint 0.10`.

**Como a banda funciona hoje:**
- O corpo do pop-up é o blur ao vivo do compositor e nunca atrasa.
- A banda (`drawLens`, só a faixa do bevel) refrata a última captura, com fade de entrada de 180 ms.
- Só é desenhada com a prova de input monitor e sem bloqueio pelo portão.
- A captura usada precisa ter sido pedida depois de o fundo assentar e ter no máximo `lensmaxage` ms.

**Medições conhecidas:**
- a captura em si leva ~5–8 ms;
- uma cópia de software em 0.5 de escala levou 55–100 ms no worker do shade;
- o histórico registra 8–14 ms por captura;
- `LENS_STATS` reporta `requestToDraw` P50/P95/max, e os pixels chegam ao display 1–2 vsyncs depois disso.

**Telemetria existente:** `LENS_STATS`, `LENS_BAND`, `TOUCH_SIGNAL`, `CAPTURE_STATS`, `BLUR_DRAWABLE_API`,
`LIVE_STATE` (o que foi pedido ao compositor por card).

## 6. Rotas para a refração ao vivo

Os fatos estão em `sf-sem-overlay.md` e `architecture.md`. O resumo abaixo é para decidir a ordem.

**Fato central:** um app não recebe os pixels de outra janela no quadro em que desenha.
- `BackgroundBlurDrawable` e `semSetBlurInfo` pedem um **blur** ao compositor, sem textura e sem
  deslocamento;
- `captureDisplay` entrega pixels sempre atrasados.

Portanto **refração sem atraso só existe dentro do SurfaceFlinger**. Nenhuma API pública ou Samsung
conhecida (até One UI 9) desloca amostras do blur. Antes de partir para código nativo, vale uma busca
rápida no `framework.jar` / `SurfaceControl` do DZIF por alguma API nova de efeito de fundo; não foi feita
nesta sessão.

### 6.1 Compositor: o que já se sabe

- **RenderEngine:** em ZZIC era GLES (Skia Ganesh), estático no binário stripped. O binário importa
  `glShaderSource` e `eglGetProcAddress`.
- **Programas certos:** o blur region é `drawRRect` com image shader → `FillRRectOp`. A primeira tentativa
  reescreveu os programas errados (`uinnerRect`/`uradiusPlusHalf`), que nunca desenham um blur region.
- **Tag de raio:** a reescrita só atua em cards marcados pelo raio.
  - O raio é `floor(r) − 0.375` (fração .625), porque o `SkRRect` reduz raios maiores que meio lado.
  - Num pill de 216 px com canto 108, a tag antiga 108.125 virava 108.0 e se perdia.
  - O vertex shader exporta `voulg_tag` (tag, raio px, meia largura) e `voulg_vp`. O fragment desloca a
    amostra dentro do bevel, mapeado por Jacobianos.
- **Raio do blur nos cards com lente:** a 180 px não sobra nada para dobrar. Com lente o card usa
  `semradiuslens` (12 px).
- **Cache de shaders:** a Samsung guarda programas linkados em `/data/misc/surfaceflinger/skia_shaders`
  e `egl_shaders` e os carrega com `glProgramBinary`, sem passar por `glShaderSource`.
  - É preciso apagar os dois e reiniciar o SF para a reescrita pegar.
  - O processo que já está rodando mantém os programas que linkou.
- **Reinício do SF:** reinicia todo o userspace. Em ZZIC chegou a 4–5 min em "Powered by Android", sem
  causa medida.
- **Segurança:** watchdog de reinícios (4 em 90 s → desliga). Fallback de link: se a reescrita do vertex
  falhar, as reescritas de fragment param no processo.
- **DZIF:** o patch `dtneeded` foi reproduzido offline (88 bytes, idempotente). Nenhum shader DZIF foi
  dumpado e não se sabe se o DZIF continua GLES ou usa Vulkan. **Esse é o primeiro dado a obter.**

### 6.2 Rotas sem OverlayFS (detalhes em `sf-sem-overlay.md`)

| Rota | Resumo | Viabilidade |
| --- | --- | --- |
| A. ptrace num SF reiniciado | Apagar o cache → `setprop ctl.restart surfaceflinger` → `PTRACE_SEIZE` no PID novo antes do RenderEngine → `dlopen` remoto de uma lib em `/data` → troca de GOT de `glShaderSource`/`eglGetProcAddress` → detach. Reaproveita `refract.h`. Não persiste no reboot. | Indeterminada. Exige 3 checagens no aparelho (só leitura) antes de escrever código: ptrace a partir do domínio do root (DEFEX pode negar), rótulo SELinux da lib (`system_lib_file` em `/data/misc/surfaceflinger`, ou `ksud sepolicy patch`) e custo do reinício do userspace. |
| B. bind mount de um arquivo | Visível no `mountinfo` | Descartada |
| C. GLES layers | Exige `ro.debuggable=1`, que quebra a integridade | Descartada |
| D. cache de shaders pré-preenchido | Gravar o binário do programa reescrito na chave do original | Pesquisa; depende de A uma vez (para obter o GLSL original do DZIF) e do formato do cache da Samsung |
| E. injeção sem reiniciar | Programas já linkados não passam de novo por `glShaderSource` | Descartada como rota principal |

**Ordem que o próprio documento recomenda, se o usuário decidir tocar no compositor:**
1. Medir Play Integrity e Wallet antes.
2. As 3 checagens da rota A, só leitura.
3. Um injetor que só faz o dump (confirma GLES/Vulkan e os programas do DZIF).
4. Só então a reescrita.

Integridade e Wallet medidas de novo depois de cada passo.

**Decisão a confirmar com o usuário antes de começar:** a sessão anterior trabalhou com a restrição "sem
mexer em SurfaceFlinger/partição/root/Zygisk". O pedido novo é atacar o compositor, mas nenhuma das rotas
foi autorizada de forma específica. Apresente a rota, o risco para integridade e Wallet e as checagens,
e espere o "sim" dele. Não escreva injetor sem isso.

### 6.3 Melhorias possíveis sem compositor (paliativo)

Nada disto remove o atraso; reduz quando a borda é desligada sem necessidade.
- **Portão por região, no lugar do portão por toque.** Hoje qualquer toque fora do card bloqueia (no
  modo `directional`, gesto horizontal, zona de sistema, settle e transição). Alternativa: comparar a
  faixa de captura atrás do card entre capturas consecutivas (diferença média de luminância na banda) e
  só bloquear quando a faixa realmente muda.
  - **Custo:** captura contínua mesmo parado, a `rimhz`.
  - **Risco:** o primeiro quadro de movimento ainda sai velho, porque a mudança só é detectada depois de
    acontecer. Dá para combinar: o toque bloqueia preventivamente só se cair atrás ou perto do card.
- **Capturar só a área do card:** o `CaptureHub` já recorta para a união das views mostradas. O ganho de
  latência é pequeno, porque a captura em si leva 5–8 ms; o atraso dominante é de pipeline (1–2 vsyncs).

## 7. Gotchas aprendidos nesta sessão (não repetir)

- **Props esquecidas no aparelho sobrescrevem padrões.** Uma rodada inteira "não funcionou" porque o
  aparelho tinha `lightglass 0.32 / lighttone b8c2ce / lightedge 2` setados à mão. Peça
  `su -c 'getprop | grep debug.oulg'` e leia a linha `TUNING` do log antes de concluir que o código falhou.
- **Medir o que o blur do card realmente vê.** O blur de um card amostra o **app atrás**, não o blur do
  painel.
  - Calibrar a opacidade comparando o card com o painel escurecido deu 0,75 (errado). O certo, medindo a
    transmissão sobre cor, foi 0,27 no shade.
  - O pop-up ficou em 0,75 porque o usuário aprovou assim.
- **O veil substituía o tint nativo** (`if (veil != 0) tint = veil`). Um veil de 2–7 % apagava o fosco
  nativo e escurecia muito. Na central agora ele é composto por cima (`VeilMath.over`).
- **Decisões por card criam inconsistência.** Medir a faixa de cada card fazia o card sobre uma nuvem
  ficar diferente dos outros. O usuário quer luminosidade e estilo decididos pela tela inteira; a cor de
  cada card fica por conta do blur ao vivo.
- **O `onPreDraw` desfaz trocas feitas de fora.** O `State` chama `setForeground(false)` quando
  `Eligibility.textTone` falha. Isso desfez duas correções do grupo minimizado até a linha órfã entrar
  nessa decisão (`orphans`).
- **Linhas de grupo minimizado não têm card desenhado** (raios 0): o tom delas nunca roda. Para achar
  quem é quem, use `TEXT_ROW`/`TEXT_OUTSIDE` (trace).
- **Borda brilhante não aparece em branco sobre branco.** A luz somada satura em 255. A borda só se lê
  quando o miolo fica abaixo de ~250 e o que está ao redor fica mais escuro (daí o `paneldim`). O usuário
  rejeitou contorno escuro.
- **Lockscreen e pop-up compartilham superfícies "lens".** Uma mudança feita para o pop-up já regrediu a
  lockscreen (reflexo duplo). Separe sempre por superfície (`popup`/`lock`, props `live*` vs `kg*`).
- **O AGSL não é validado pelo build.** Um uniform declarado e não setado, ou um setado e otimizado fora,
  pode lançar exceção no aparelho. Os uniforms são setados em `edgeUniforms` para os dois programas
  (`SOURCE` e `REFRACT_SOURCE` compartilham `COMMON`).
- **Prefetch de baixa resolução.** Quadros de 0.125 não podem chegar à lente (`CaptureBackdrop` filtra).
- **Push.** `git push -f` foi negado pelo classificador. Depois de um merge, recrie a branch a partir de
  `origin/main` (`git checkout -B <branch> origin/main`); o push normal faz fast-forward.
- **Shell.** `cat >> /dev/null` sem stdin trava o comando.

## 8. Primeiros passos sugeridos para a nova instância

1. Ler `sf-sem-overlay.md` inteiro e as seções de `architecture.md` citadas em §4.
2. Confirmar com o usuário:
   - se autoriza tocar no compositor, e por qual rota (A, depois D);
   - se aceita reinícios do userspace;
   - como medir Play Integrity e Wallet antes e depois (a ferramenta que ele usa).
3. Se autorizado, mandar no chat **só as checagens de leitura** da rota A:
   - `su -c 'cat /proc/$(pidof surfaceflinger)/maps | head -1'`;
   - contexto SELinux de `/data/misc/surfaceflinger` (`ls -Zd`);
   - `dmesg | grep avc` depois de uma tentativa de `PTRACE_SEIZE`, num binário de teste mínimo;
   - se o late-load já reinicia o userspace.
4. Com as checagens OK, escrever um injetor que só faz o dump (sem reescrita) e obter:
   - GLES ou Vulkan no DZIF;
   - os programas `FillRRect` com `radii_selector`/`radii_x`.

   Validar os rewrites de `refract.h` contra esse dump com `sfhook/tools/refract_check.c` +
   `glslangValidator`.
5. Só então a reescrita, com:
   - o app em `sfrefract=1` marcando os pop-ups (tag `floor(r) − 0.375`, raio de blur 12 px na lente);
   - o portão de captura desligado nesse modo, porque a lente é do compositor;
   - fallback garantido para o modo só-LSPosed se a lib não estiver carregada.

   **Ponto aberto:** hoje `sfrefract=1` sem a lib desliga o `live`. A seleção de modo deve passar a
   depender de um sinal real da lib (arquivo ou handshake), não só da prop.
6. Em paralelo, se o usuário quiser algo sem compositor: o portão por região de §6.3, atrás de uma prop
   (`lensgate=region`), mantendo `directional` como padrão até o teste.
