# Handoff — OneUILiquidGlass (2026-10-09)

Documento para continuar o trabalho em outra conversa. Leia inteiro antes de mexer em qualquer coisa. O histórico técnico detalhado está em `docs/architecture.md`, nas seções de 2026-10-07 a 2026-10-09.

## 0. Atualização (sessão seguinte, PR #21)

O handoff completo desta sessão, com a linha do tempo da investigação, o estado de cada chave e a tabela de commits, está no documento compartilhado: <https://claude.ai/code/artifact/06632b0e-8a50-473c-af62-3373690088d1>. Resumo:

- **PR #21** (`claude/new-session-myjwlg` → `ccr-fa46d54c-s1s4b6`, empilhado sobre o #20).
- **sfhook v0.7 (blur em camadas)** confirmado no aparelho: `VS_OK=24 FS_OK=36 FAIL=0`. No dump real de 138 shaders, os 47 programas reescritos compilam no `glslangValidator`.
  - Chaves, lidas na compilação: `debug.oulg.sf.core` (48 px), `debug.oulg.sf.taps` (24), `debug.oulg.sf.ramp` (1,5).
- **Cinza permanente depois do meio-arrasto: causa e correção confirmadas.**
  - Causa: o blur Samsung sem curva de cor própria herda a curva escura do painel.
  - Correção: `debug.oulg.semcurve=auto` (padrão), que aplica a curva neutra `0,0,0,255,0,255` nos cards com lente.
  - O item 3 da seção 7 deste documento (clamp do `scrim_notifications`) estava errado: aquela camada tem tamanho 0x0.
- **Restam dois defeitos na tela de bloqueio.** Os dois são do pipeline de blur do SurfaceFlinger da Samsung e estão fora do alcance do app.
  - **A:** textura de blur errada por 2 quadros no início e no fim de arrastos lentos. Com `sf.debug=1` o card continua magenta nesses quadros, então o shader desenha e a marca chega; quem erra é a textura de entrada.
  - **B:** região de blur atrasada um quadro durante o arrasto.
  - **Testes sem efeito:** scrims, blur da janela (`kgwinblur` 0/2/3), troca de lente, `CapturedBlurContainer`, `setBackgroundBlurRadius`, `debug.sf.enable_layer_caching=0` (piorou).
  - **Inconsistente:** `debug.renderengine.restore_blur_step=0` resolveu por um ciclo de bloqueio e depois voltou.
- **Decisão pendente**, com outros desenvolvedores:
  1. engenharia reversa do blur no binário do SurfaceFlinger;
  2. tela de bloqueio no caminho `SAMPLED`, com a lente do SurfaceFlinger só no pop-up (recomendada);
  3. aceitar os defeitos.
- **Limpeza do PR #21 antes do merge.** Manter `435bc60`, `c6888cf` e a curva neutra de `e1188bc`. Retirar os diagnósticos e as tentativas sem efeito: `6c19b48`, `d05b0f1`, `c88b8b9`, o descarte de raio ≤ 4 em `e1188bc`, `0cd2961`, `e6eb9f5`, `8953c9a`, `70c8da3` e `d2d48e8`. Decidir sobre `840e5e9`.
- **No aparelho:** APK de debug `d2d48e8`; lib do SurfaceFlinger `/data/adb/oulg_live/SurfaceFlingerProp.v7.so`.
- **Build do app nesta nuvem:** o Maven Central respondeu 429; use um init script do Gradle que troque `repo.maven.apache.org` por `maven-central.storage-download.googleapis.com/maven2/`.

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
