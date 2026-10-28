# OneUILiquidGlass — catálogo de controles em 26/10/2026

**Fonte:** `app/src/main/java/io/github/igorcv88/oneuiliquidglass/glass/Tuning.java`, `sfhook/oulg_sf.c`, `sfhook/refract.h`, `sfhook/module/service.sh` no commit-base `11e1f3e6f2220c1eb6c7831cdf61279a981dcc03`. Inventário para a refatoração da interface, não captura do estado efetivo do aparelho.

**Atualização de integração (main após #57):** `lensregiondiff`/gate `region` foram removidos; `popuplock` e `orphandraw` foram adicionados. O inventário ativo agora tem 103 propriedades. O interruptor `sfrefract` pertence ao helper live e não pode ser persistido pela ponte do APK.

**Abrangência:** 87 chaves em Java (`Tuning` e `QsStyle`, incluindo `sf.optics`), 37 no leitor da biblioteca C (incluindo a mesma `sf.optics`), **123 chaves distintas**. O módulo também tem um controle global `glass/enabled` nas SharedPreferences, fora de `debug.oulg.*`. As configurações independentes de perfil, as condições de ativação, os comandos de teste e as ações de recuperação também devem aparecer no APK.

**Exceção para `qs.*`:** são chaves do snapshot do aplicativo, sem leitura de `setprop`.

Prefixo omitido nas tabelas (demais chaves): cada entrada abaixo significa `debug.oulg.<chave>`. **L:** `Tuning` atualiza aproximadamente a cada 1 s dentro de SystemUI, mas algumas mudanças exigem recriação de material/invalidação de View. **C:** SurfaceFlinger lê ao compilar shader; mudar `setprop` não recompila o shader existente. **R:** leitura de estado; não oferecer escrita normal. Padrões são do código, não do telefone, do serviço de boot nem dos overrides do usuário.

## Modos, captura e refração amostrada — Java (L)

| Chave | Padrão | Tipo/domínio do parser | Controle / consequência |
| --- | --- | --- | --- |
| `backdrop` | `auto` | enum `auto/hybrid/capture/grid/off` | Origem do fundo; `grid` diagnóstico, `capture` pode atrasar atrás do movimento |
| `profile` | `lens` | enum `lens/snell` | Modelo de refração AGSL no caminho amostrado |
| `lens` | 0.24 | 0–0.45 | Deslocamento de borda do modelo `lens`, fração do bisel |
| `refract` | 1 | 0–6 | Multiplicador do deslocamento Snell |
| `rimhz` | 80 | inteiro 1–120 Hz | Frequência da captura da faixa de borda no pop-up |
| `rimblur` | 24 | 0–32 px | Gaussian blur da faixa capturada |
| `ior` | 1.5 | 1–2.4 | Índice óptico do Snell |
| `blur` | 8 | 0–32 px | Blur RenderEffect sobre captura; zero mantém amostra nítida |
| `sat` | 1.2 | 0–2.5 | Saturação do backdrop capturado |
| `disp` | 0.10 | 0–0.5 | Diferença relativa de deslocamento RGB |
| `hz` | 15 | inteiro 1–120 Hz | Captura geral de pop-up, quando caminho capturado ativo |
| `hzkg` | 5 | inteiro 1–120 Hz | Captura na lockscreen, quando necessária |
| `tint` | -1 | inteiro -1–255 | Alpha do véu; -1 usa GlassSpec |
| `livelens` | 1 | booleano | Faixa de refração capturada em pop-up APK-only, quando imóvel |
| `lensmaxage` | 40 | inteiro 8–500 ms | Idade máxima do quadro usado na lente capturada |
| `lenssettle` | 100 | inteiro 0–3000 ms | Tempo de espera após toque/gesto |
| `lenstransition` | 350 | inteiro 0–1500 ms | Janela extra após gesto que pode iniciar uma transição |
| `lensgate` | `directional` | enum `directional/conservative` | Corte por gesto; antigo `region` foi removido e cai no direcional |

Dependências: `liveblur=1` só ativa `live` se `sfrefract=0` e `backdrop=auto`. `livelens` só é relevante nesse caminho. `profile/lens/refract/ior/disp` podem ficar sem efeito no modo compositor; mostrar estado **inativo por dependência**, jamais fingir aplicação.

## Backdrop, blur e óptica APK — Java (L)

| Chave | Padrão | Tipo/domínio do parser | Controle / consequência |
| --- | --- | --- | --- |
| `sfrefract` | 0 | booleano | Solicita lente do compositor; serviço KernelSU pode aplicar 1 no boot |
| `liveblur` | 1 | booleano | Material live APK-only quando compositor desligado |
| `liveradius` | 80 | inteiro 0–400 px | Blur APK de pop-up e lockscreen |
| `liveshaderadius` | 120 | inteiro 0–400 px | Blur APK de cartões do shade |
| `liveoptics` | 1 | 0–2 | Ganho geral da iluminação/óptica de borda APK-only |
| `livesheen` | 0.02 | 0–0.15 | Brilho superior suave |
| `liveglint` | 0.10 | 0–1 | Reflexo curto na quina do pop-up |
| `shadeglint` | 0.10 | 0–1 | Reflexo curto da central |
| `semradius` | -1 | inteiro -1–400 px | Blur Samsung geral; -1 delega ao spec (atualmente 180 px) |
| `semradiuslens` | 16 | inteiro -1–400 px | Blur de cards marcados para lente; -1 segue `semradius` |
| `sflens` | 0.45 | 0.1–1.2 | Intensidade da lente compositor, perfil de pop-up |
| `semcurve` | `auto` | texto controlado | Curva de cor Samsung (`auto`, `spatial`, `dim`, `ultra`, `none` ou curva explícita validada); caminhos podem falhar por firmware |
| `semalpha` | -1 | inteiro -1–255 | Véu do blur Samsung; -1 segue o spec |
| `semshape` | `auto` | enum `auto/single/path/four/none` | Geometria da região Samsung |
| `shadeblur` | 1 | booleano | Blur próprio em cada card expandido |
| `kgblurpath` | 1 | inteiro 0/1 | Lockscreen: blur desenhado no drawable vs instalado na View |
| `shadeblurpath` | 1 | inteiro 0/1 | Shade: blur no drawable vs instalado na View |
| `shadefadecut` | 0.5 | 0–1 | Limite para remover a região de blur ao desaparecer |
| `kgwinblur` | 0 | booleano | Permite rampas do blur da janela no keyguard ocioso |
| `kgblurradius` | -1 | inteiro -1–400 px | Override do blur da lockscreen; -1 segue `semradiuslens` |
| `pillglass` | 1 | booleano | Vidro na pill de overflow da lockscreen |
| `pillblur` | 40 | inteiro 0–400 px | Blur do overflow, sem marca de lente |
| `kgoptics` | 1 | inteiro 0/1 | Perfil compositor 1 na lockscreen vs perfil 0 |
| `huoptics` | 0 | inteiro 0/1 | Prévia do perfil compositor de lockscreen no pop-up |
| `kglens` | 0.40 | -1 ou 0.1–1.2 | Lente do perfil 1; -1 acompanha `sflens` |

O interruptor `sfrefract` **não comprova** que o binário nativo foi carregado: cruzar com `sf.optics`, log, módulo instalado e shader observado. `kgoptics/huoptics` funcionam por marca de raio nos cards elegíveis, não alteram automaticamente a shade.

## Cor, legibilidade, superfície e layout — Java (L)

| Chave | Padrão | Tipo/domínio do parser | Controle / consequência |
| --- | --- | --- | --- |
| `shadetone` | 3 | enum inteiro 0/1/2/3 | 0 sem proteção; 1 perfil SF tone-map; 2 véu fixo; 3 análise adaptativa via captura |
| `shadecap` | 0.55 | 0.2–1 | Teto de luminância média para texto claro no shade adaptativo |
| `shadelift` | 0.45 | 0–0.8 | Piso de luminância para texto escuro no shade adaptativo |
| `veilmax` | 0.25 | 0–0.75 | Alpha máximo do véu de contraste |
| `stylelo` | 0.48 | 0.2–1, limitado por `stylehi` | Threshold inferior de estilo claro/escuro |
| `stylehi` | 0.62 | 0.3–1 | Threshold superior com histerese |
| `lightglass` | 0.75 | 0–0.95 | Alpha do vidro claro em pop-up/lockscreen |
| `lighttone` | `ffffff` | RGB hexadecimal de seis dígitos | Cor-base do vidro claro |
| `shadelightglass` | 0.27 | 0–0.95 | Alpha separado dos cards claros no shade |
| `lightoutline` | 0.8 | 0–2 | Força do contorno claro |
| `lightedge` | 1 | 0–4 | Sombra de espessura da borda clara |
| `livedarkalpha` | 20 | inteiro 0–255 | Alpha branco da região do pop-up escuro |
| `livedarkfill` | 10 | inteiro 0–255 | Alpha preto do preenchimento do pop-up escuro |
| `liverim` | 2 | 0–4 | Ganho isolado do contorno externo do pop-up |
| `kgdarkalpha` | 20 | inteiro 0–255 | Alpha branco da região escura da lockscreen |
| `kgdarkfill` | 14 | inteiro 0–255 | Alpha preto interno da lockscreen |
| `kgrim` | 2 | 0–4 | Contorno externo da lockscreen (APK) |
| `kgglint` | 0.10 | 0–1 | Reflexo interno da lockscreen |
| `textswap` | 1 | booleano | Troca cor do texto em material claro/escuro oposto ao tema |
| `headertone` | 1 | booleano | Ajusta relógio/data/operadora da central ao fundo |
| `icontone` | 1 | booleano | Ajusta ícone monocromático da notificação ao texto |
| `popuplock` | 1 | booleano | Estabiliza o contraste do pop-up: só muda com mudança real do fundo, confirmada por uma segunda leitura |
| `orphandraw` | 1 | booleano | Aplica o tom das linhas minimizadas já no primeiro desenho da central |
| `popuptone` | 1 | booleano | Véu medido do backdrop do pop-up APK-only |
| `shadescaleveil` | 115 | inteiro 0–255 | Véu de fallback quando shade card é escalado |
| `shadelocal` | 0 | booleano | Mede a faixa de cada card no lugar do fundo inteiro |
| `paneldim` | 0.12 | 0–0.4 | Escurecimento do painel sobre app claro, no tema escuro |
| `headergap` | 4 | 0–8 dp | Espaço extra entre cabeçalho e título expandido; 0 nativo |

Observar que as propriedades `lightglass` e `shadelightglass` se aplicam a superfícies distintas. Não unificar os sliders. Os nomes `kgrim` (Java) e `sf.kgrim` (compositor) são **parâmetros diferentes**.

## Estado e diagnóstico — Java

| Chave | Padrão | Fase | Controle / consequência |
| --- | --- | --- | --- |
| `sf.optics` | 0 no parser; biblioteca anuncia 9 | R | Handshake da versão de óptica carregada; **indicador, não slider** |
| `perf` | 0 | L | Instrumentação de tempos de hooks, deve ter aviso de custo |
| `trace` | 0 | L | Diagnóstico detalhado de árvores/janelas; evitar coleta permanente |

## Perfis do SurfaceFlinger — C

**Fase C para todas as propriedades abaixo**, inclusive `sf.debug` e `sf.norewrite`: valores congelados no código do programa compilado. Não reiniciar o compositor automaticamente ao arrastar o slider. O parser C usa `strtof` sem clamping geral; **os limites seguros abaixo devem ser estabelecidos por testes e pelo validador de entrada** antes de exposição irrestrita. O campo “padrão” foi extraído de `sfhook/refract.h`.

| Chave (`sf.*`) | Padrão | Perfil / efeito |
| --- | --- | --- |
| `sf.core` | 48 | Heads-up: raio do blur do miolo (px; 0 desliga) |
| `sf.taps` | 24 | Heads-up: amostras por pixel |
| `sf.ramp` | 1.5 | Heads-up: profundidade em biséis até raio pleno |
| `sf.hubloom` | 0 | Heads-up: supressão de amostras claras/bloom; 0 mantém disco simples |
| `sf.edgeblur` | 0 | Heads-up: raio do blur no contorno em px, uma curva contínua até o miolo (0 restaura v0.7) |
| `sf.kgcore` | 80 | Lockscreen: raio nominal do miolo em px |
| `sf.kgtaps` | 48 | Lockscreen: amostras por pixel |
| `sf.kgramp` | 1.5 | Lockscreen: rampa do blur |
| `sf.kgsat` | 1.25 | Lockscreen: saturação |
| `sf.kgtone` | 0.15 | Lockscreen: curva de destaque |
| `sf.kgrim` | 0.72 | Lockscreen: reflexão do aro **no shader nativo** |
| `sf.kgspec` | 0.19 | Lockscreen: specular |
| `sf.kgflat` | 0.50 | Lockscreen: achatamento da transmissão |
| `sf.kglevel` | 0.40 | Lockscreen: luminância-alvo do achatamento |
| `sf.kgcool` | 0.50 | Lockscreen: matiz fria |
| `sf.kgtilt` | 1.42 | Lockscreen: inclinação da luz (rad) |
| `sf.kgline` | 0.62 | Lockscreen: contorno |
| `sf.kgcorecap` | 52 | Lockscreen: teto do raio efetivo do miolo (px) |
| `sf.kggradient` | 1 | Lockscreen: transição contínua vidro-miolo (0–1; 0 usa curva anterior, junto de kgcorecap=62 restaura referência) |
| `sf.kgband` | 0.85 | Lockscreen: largura da transição óptica (fração do raio do canto; 0 restaura v0.22; abaixo de ~0.42 fica no bisel mínimo) |
| `sf.kgbodylens` | 0.05 | Lockscreen: curvatura da lente no miolo (ampliação uniforme; 0 desliga) |
| `sf.kgblurexp` | 2 | Lockscreen: expoente da subida do blur na transição (1–4; maior = menos blur na transição) |
| `sf.kgflatcap` | 0.22 | Lockscreen: teto do peso de achatamento |
| `sf.kgcoredim` | 0.04 | Lockscreen: redução de brilho do miolo |
| `sf.kgglow` | 0.70 | Lockscreen: largura do glow do aro |
| `sf.shadecap` | 0.55 | Perfil 2 shade: teto da luminância para texto claro |
| `sf.shadelift` | 0.45 | Perfil 2 shade: piso da luminância para texto escuro |
| `sf.hulight` | 0.40 | Heads-up: luz que entra pelo contorno conforme o fundo ali (0 restaura v0.26) |
| `sf.kglight` | 0.35 | Lockscreen: luz que entra pelo contorno conforme o fundo ali (0 restaura v0.26) |
| `sf.shadelight` | 0.30 | Perfil 2 shade: luz que entra pelo contorno conforme o fundo ali (0 restaura v0.26) |
| `sf.qslens` | 0.30 | Quick Settings: refração do fundo junto ao contorno (0 desliga a refração e o fundo refratado) |
| `sf.qsbody` | 0.35 | Quick Settings: opacidade do fundo refratado no contorno |
| `sf.qsdepth` | 18 | Quick Settings: profundidade (px) da luz vinda de um contorno branco |
| `sf.debug` | 0 | Tinta diagnóstica magenta/ciano sobre programas elegíveis |
| `sf.norewrite` | 0 | 1 desativa a reescrita dos shaders ao compilar |
| `sf.optics` | 9 anunciado pela lib | R, handshake; já listado em Java |

## Recursos sem prop correspondente

| Recurso | Localização hoje | Na futura interface |
| --- | --- | --- |
| Ligar/desligar o efeito global | `Config.KEY_ENABLED` em SharedPreferences | Interruptor principal com estado real e aviso de restart |
| Criar notificação teste | `MainActivity`, channel high, BigText em 5 s, sem ações | Botão fixo em Testes; BigText real, ícone do app, botões funcionais e opcional HUN |
| Ativar/desativar módulo KernelSU | KernelSU e service.sh | Estado detectado, instruções e ação de recuperação; não forçar instalação pelo APK |
| Checar shader carregado | `OULG_SF`, `sf.optics`, dump de shaders | Diagnóstico legível: carregado / reescrita observada / falhou / incerto |
| Reiniciar SystemUI | CLI | Ação explícita, confirmação e instrução de recuperação; sem automação no arranque |
| Recompilar shaders/reiniciar SF | CLI/boot + cache | Ação avançada separada, confirmada, com pré-requisitos e watchdog; nunca pelo movimento do slider |
| Exportar/importar preset | Inexistente | JSON versionado com diff, validação, backup e rollback |
| Exibir preview aproximada | Inexistente | Mock não privilegiado, reativo ao controle; rótulo de simulação |

## Auditoria obrigatória no Work

1. Gerar o registry diretamente dos pontos de leitura **e confrontar** com esta lista após qualquer mudança no código. Falha de cobertura no CI se nova prop não tiver especificação.
2. Preservar distinção entre padrão, override em `getprop`, valor desejado no APK, valor aplicado em SystemUI e valor ainda pendente de recompilação em SF.
3. Não transformar `sf.optics` em opção de usuário; não confundir `kgoptics` (seleção de perfil) com confirmação do compositor.
4. `debug.*` expira no reboot: persistência deve ser uma política explícita, com caminho de rollback, e não presumida.
5. Validar o comportamento de cada toggle nos três estados (heads-up, keyguard, shade) e nos dois temas; dependências devem desabilitar controles contextualmente, sem perder valor salvo.


## Controles rápidos — pós-PR #75

Controles `qs.*` são lidos pelo snapshot `QsStyle` em callbacks de configuração e aplicam ao vivo. Cápsulas são vidro transparente sobre fundos escuros/coloridos e recebem um tom escuro discreto (`qs.lightfill`/`qs.lightcolor`, teto 18%) sobre apps claros; o contorno é sempre a luz branca; círculos e ícones mantêm as cores da Samsung. `qs.darkfill`, `qs.darkcolor`, `qs.idle`, `qs.active`, `qs.track` e `qs.lightstyle` são legado, preservados apenas para importar/exportar perfis, e não têm efeito.

| Chave | Padrão | Tipo/domínio do parser | Controle / consequência |
| --- | --- | --- | --- |
| `qs.darkfill` | 0 | 0–0.18 | Legado sem efeito visual; o interior das cápsulas é sempre transparente |
| `qs.lightfill` | .10 | 0–0.18 | Tom escuro das cápsulas sobre apps claros (transparentes sobre fundos escuros/coloridos) |
| `qs.darkcolor` | ffffff | RGB hexadecimal | Legado sem efeito visual; o interior das cápsulas é sempre transparente |
| `qs.lightcolor` | 141823 | RGB hexadecimal | Cor do tom das cápsulas sobre apps claros |
| `qs.idle` | 0 | 0–0.12 | Legado sem efeito visual; discos desligados são sempre transparentes |
| `qs.active` | .90 | 0–1 | Legado sem efeito visual; os círculos mantêm as cores da Samsung |
| `qs.darkrim` | .26 | 0–1 | Intensidade do contorno sobre fundos escuros ou coloridos |
| `qs.lightrim` | .18 | 0–1 | Intensidade do contorno de luz sobre apps claros |
| `qs.edgeblur` | 8 | inteiro 1–32 px | Desfoque do fundo lido pela lente dinâmica no contorno das cápsulas |
| `qs.outer` | 1 | 0.50–1 | Espessura da pílula inteira dos sliders (o toque continua na área da Samsung) |
| `qs.track` | .88 | 0.10–1 | Legado sem efeito visual; preservado para importar e exportar perfis |
| `qs.fill` | .85 | 0.10–1 | Espessura do preenchimento branco dentro da cápsula; a mesma margem vale nas pontas |
| `qs.lightstyle` | light | enum obsidian/light | Legado sem efeito visual; ambos usam vidro translúcido e tinta adaptativa |
| `sf.qslight` | .90 | 0–2 | Quick Settings: luz que entra pelo contorno conforme o fundo ali (0 desliga) |
