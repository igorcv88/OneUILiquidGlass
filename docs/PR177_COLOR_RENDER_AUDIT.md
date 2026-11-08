# PR #177 — revisão de cor e renderização, 08/11/2026

Estado: implementação revisada; validação visual no S25 Ultra DZIF pendente. Não fazer merge antes dela.

## Evidência e limites

Base examinada: `main` em `10d478d4`, head inicial `493e1208` e histórico de 42 commits do PR. O PR estava mergeável. CI do head inicial: run `42527771816`, sucesso. Os dois comentários de review de `748bd54` foram conferidos: a identidade de SBN/notificação já era reavaliada e a lease normal já salvava/limpava o filtro de ImageView. Não são causas novas pendentes daquele código inicial.

Leitura de `OULG_SHELF_SWITCH.log`, inclusive geração 17: abertura às 18:34:46.036; captura compartilhada válida às 18:34:48.204, `tFromOpen=2167`; filtro preto registrado para android/smartcapture; após fechamento às 18:34:51.882, branco reaplicado por `sharedPreview` às 18:34:51.889. O log é anterior à refatoração e não identifica o filtro efetivo do drawable, nem prova qual view produziu o pixel visto no screenshot. `earlyDraws` soma callbacks de cartões, não frames apresentados.

O arquivo não contém `CAPTURE_SECURE`, `CAPTURE_DISABLED` nem `PANEL_PREVIEW_MISS`. A indisponibilidade imediata no código pode vir de backoff de conteúdo seguro (3.000 ms), API ausente ou captura desabilitada após falhas. O atraso de 2.167 ms é compatível com parte de um backoff, mas essa causa não está comprovada no arquivo. Foi acrescentado `SHADE_CAPTURE_UNAVAILABLE`, sob trace, com motivo e tempo restante quando aplicável. Nenhum prazo de captura foi reduzido.

## Problemas comprovados no código e correções

1. **Hold sem invalidação de foreground/configuração.** Uma paleta de até 30 s podia ser reutilizada após trocar de app. Agora exige amostra posterior ao último evento real de foreground, mesma revisão de configuração, geração de Tuning e tema nativo. Eventos de foreground invalidam a referência retida. Pre-open e captura tardia também rejeitam pixels anteriores ao foreground. Isso é invalidação por evento, não cache de cor por aplicativo.
2. **Fallback individual de cada cartão.** Sem paleta válida, cada cartão podia conservar sua própria medição recente, inclusive de pop-up, e divergir do shelf. Foi removido esse fallback da central. Uma paleta provisória válida ainda é compartilhada; sem captura nem hold válido, usa-se a aparência nativa até chegarem pixels válidos.
3. **Propriedade de filtro incompleta.** O shelf verificava `ImageView.getColorFilter()` e cor, mas não o drawable efetivo. Um filtro direto no drawable ou uma troca de drawable podia manter a cache da ImageView aparentemente correta. `ShelfIconTint` verifica os dois, usa um único filtro por tinta e salva/restaura os filtros por lease, inclusive após rebind. Testes simulam a divergência e a restauração sem sobrescrever uma escrita OEM mais recente. Isso comprova a falha da verificação antiga; não comprova que ela seja a única causa do defeito físico.
4. **Possível segundo escritor do módulo.** `Foreground.walk` podia alcançar a mesma ImageView pelo caminho genérico de tint. A lease compacta agora libera o registro antigo de glyph/tint antes de assumir e exclui a view da varredura genérica enquanto a possui. A elegibilidade real depende do layout; o log antigo não prova uma disputa específica desse caminho.
5. **Momento de atualização OEM.** Além de bind/attach, os métodos disponíveis de `StatusBarIconView` para cor/drawable recebem reconciliação ao retornar (`updateIconColor`, `setStaticDrawableColor`, `setIconColor`, `updateDrawable`, `drawableStateChanged`). O hook é restrito a métodos do SystemUI, não intercepta toda ImageView. Um guard impede entrada recursiva quando a instalação de drawable provoca outro callback. O log de instalação informa os métodos encontrados no firmware; método ausente não é considerado sucesso. A telemetria de aplicação inclui filtros anteriores da view/drawable, identidade do drawable e campos OEM de glyph/dot/estado visível. Uma revisão legítima de tinta não é contada como sobrescrita OEM pelo circuito de disputa.
6. **Configuração ainda decidida em dois consumidores.** A paleta passa a publicar a tinta final, incluindo `iconinkmode`, cor fixa e cores de fundo claro/escuro. Cartões no modo global e shelf usam esse mesmo inteiro; o shelf não classifica novamente a tinta. `textswap=0` usa a tinta nativa no automático; cores personalizadas continuam explícitas. A configuração recompõe a paleta ativa e reconcilia o shelf. Um fechamento retira a paleta antes de restaurar filtros.
7. **Frame compartilhado invalidando backdrop oculto.** `CaptureHub.deliver` difunde o frame a todos os clientes. `CaptureBackdrop.onFrame` não exigia view visível/anexada nem taxa positiva. Agora rejeita frames nessas condições. A captura antecipada de `ShadeTone.Preview`/`Prefetch` continua independente e autorizada enquanto o painel está fechado.

## Autoridades de cor: respostas à revisão

| Questão | Estado após as correções |
| --- | --- |
| Uma decisão para cartões e minimizados? | Sim para a paleta global da central adaptativa, com `shadelocal=0`. Material/swap/tinta são publicados juntos. O texto mantém o tratamento de tons nativos e acentos de Foreground; cor personalizada de ícone não obriga texto a usar essa cor. |
| Algum consumidor ainda classifica? | `shadelocal=1` mantém a opção explícita de medir cada cartão. Pop-ups, lockscreen e superfícies live têm contextos próprios. Header/rodapé podem medir suas bandas. QS compartilha pixels/PanelScene, mas mantém fase de animação e consumidores próprios. Não existe motor universal da aplicação. |
| Filtros após fechar? | A autoridade do shelf retorna null e sua lease restaura nativo. Sem fallback para sharedPreview no fechamento. Hooks de status bar continuam legítimos e separados. |
| O que invalida? | Fechamento, nova geração, saída de BAR_SHADE e desativação/adaptive off retiram a autoridade ativa. Foreground, revisão, Tuning, tema e idade invalidam hold. Detach restaura lease. Configuração durante abertura recompõe decisão. |
| Provisório pode impedir captura válida? | A paleta retida não preenche shadeGrid. Assim a primeira grade válida ainda é adotada, inclusive com mesma classificação; referências por cartão reconhecem a nova revisão. Capturas antigas de outra geração são descartadas. |
| Segunda autoridade no shelf? | A seleção de tinta foi removida do consumidor. A lease só recebe a tinta final. Não há procura de label nem ToneGrid específico. Os antigos helpers/testes de label foram removidos. |
| Quem ainda pode sobrescrever? | OEM pode alterar drawable/tint durante animação; métodos encontrados são reconciliados no evento. Há fallback pre-draw com circuit breaker existente, não garantia de que todos os escritores DZIF sejam conhecidos. Telemetria de drawable/view/alpha/ancestrais decide isso no teste. |
| Como ficam as opções? | `shadeAdaptive=0` não usa paleta retida do shelf. `textswap=0` mantém tinta automática nativa. `shadelocal=1` é exceção intencional por cartão. Cor fixa e duas cores são resolvidas na publicação global; packs/importação/política iconmode e caminhos de outras superfícies foram preservados. |

`HeaderToneController` mantém grupos para clock/data e ações `noti_setting`/`clear_all`, com amostragem local. `QsBackdrop` consome `PanelScene.Snapshot`, incluindo decisão separada de header, e distribui a mesma fase fracionária aos consumidores QS. Forçar todos esses elementos à tinta do corpo eliminaria comportamento configurável/local já aprovado. Não foi ampliada a centralização além da central de notificações nesta revisão.

## Auditoria de CPU/GPU, retenção e lifecycle

| Caminho examinado | Trabalho/custo e decisão |
| --- | --- |
| Notificações/HeadsUpHooks | Pré-desenho mantém verificação de identidade e material; reflexão de campos/métodos é cacheada em Reflect, incluindo misses. A localização de tela no modo global deixou de ser consultada por cartão por frame; só permanece para banda local ou diagnóstico de transição. SWAP_WALK_MS=500 continua como backstop para novas views, além de drift/guards. Não foi reduzida intensidade visual. |
| NotificationIconTreatment | Worker único e cache LRU de 48 fontes; lookup/PNG fora do desenho. Identidade da notificação reavaliada até a cada 250 ms. Candidatos são redescobertos por layout/rebind, não árvore inteira a cada frame. Há bitmap nativo 24x24 de amostragem na thread principal ao resolver nova fonte: permanece como custo de preparação, não de todo draw. |
| Compactos/shelf/status | Verificação de SBN continua cacheada em Reflect. Chave string da fonte passa a ser reconstruída por mudança de SBN/notificação/config/modo ou bind, em vez de todo pre-draw. A lease não aloca filtro quando só o drawable foi sobrescrito e a tinta não mudou. Limites/width budget dos quatro ícones foram preservados. |
| NotificationGlyphDrawable | RectF reutilizado, eliminando uma alocação por draw. setAlpha/setColorFilter ignoram valor idêntico, evitando invalidações redundantes. getAlpha agora expõe o alpha efetivamente desenhado. |
| Captura compartilhada | CaptureHub tem uma captura em voo, crop agregado, worker de I/O, frames com retain/release e backoff seguro. Preview de luminosidade usa 0,125 de escala, até 8 Hz, redução única no worker para clientes QS/shade. A prévia fechada é custo deliberado para primeira abertura; não foi desligada. Tela não interativa não captura, mas o tick ainda acorda. |
| Broadcast/captura visual | Corrigida invalidação de CaptureBackdrop oculto/sem taxa. Não se mudou frequência de captura nem da animação visível. Registro de clientes fracos e unregister no release foram preservados. |
| 900/250/100 ms | Timers de reamostragem QS/shade ficam vinculados à sessão visível; 100 ms é burst de 600 ms por eventos, 250 ms segue atividade recente e 900 ms é fallback. Em geral usam ShadeTone.live antes de pedir captura própria. A prévia de 8 Hz entrega decisões por listener mesmo entre esses timers. Portanto esses valores não equivalem à taxa total de captura/composição. |
| QS, sliders, mídia QS | QsBackdrop usa pre-draw para detectar abertura/fechamento; cancela captura/tick e observer no stop/retire. QuickSettingsHooks/QsSliderHooks/QsTextHooks têm consumidores Compose específicos. Permanecem reflection/descoberta e escritas por evento/desenho; não foi feita uma reescrita desses caminhos sem medição física. |
| Pop-up e P3 | Cor é por superfície de pop-up, distinta da central. O watcher de modo de cor consulta em worker, só com pop-up visível, uma consulta em voo; para ao desaparecer e limita cadência diagnóstica. Compatibilidade P3 é armazenada no drawable; sem consulta SF dentro de draw. Fluxo de Instagram/bordas não foi modificado. |
| Vidro/refração/bordas | GlassDrawable reutiliza Paint/Path/RectF/RenderNode e bitmap shader por frame capturado. Shader/uniformes e blur/refração continuam trabalho visual necessário. Há alocações por nova captura/bounds e liberação adiada de frame por 250 ms para display list; mantidas. |
| Now Bar, live updates, lockscreen | LiveGlass mantém estado por view fraca, reassert limitado a três reparos de background e remove listeners/blur no release. Pré-desenho acompanha raio/alpha OEM durante animação; LiveGlassDrawable só atualiza região quando raio/tinta/radii mudam. Não foi alterada a política de admissão/regionless. |
| Toasts | Hooks de factory/attach e WindowManager conservam aceleração por lease. Usa os mesmos limites de reparo de LiveGlass; detach/release fecha região. Não foi inserido timer exclusivo de toast. |
| Mídia lockscreen/arte | LockMediaGlass e MediaArtGlass têm pre-draw e resolvem alvos no attach ou após gap/rehost; removem observers e referências fortes a filhos no detach. Mudança de geometria/transparência durante expansão segue necessária; sem novo traversal global. |
| Telemetria | Perf é opt-in; Probe.trace deve voltar a 0 após reprodução. Surveys de árvore e dumps são diagnósticos. O novo motivo de captura indisponível é trace-only. Não foi ligado perf/trace por padrão. |

Não foi medido tempo de CPU/GPU, energia, mAh nem temperatura no aparelho nesta execução. Custos evitados acima são identificados pelo fluxo de código e testes, sem percentual alegado. Permanecem candidatos a perfil: preview fechado a 8 Hz, tick em tela apagada, drift/walk periódico de texto, preparação do bitmap nativo, listeners de mídia e custo GPU de refração. Não foram tratados como defeitos comprovados nem usados para reduzir blur ou animação.

## Hipóteses não confirmadas ou descartadas como estratégia

- “Filtro preto registrado prova que o glyph visível ficou preto”: descartado; o log antigo não media o filtro do drawable/pixel final.
- “Falta classificar a luminosidade novamente nos minimizados”: descartado como estratégia; esse consumidor deve receber tinta pronta.
- “Label do contador é referência confiável no DZIF”: descartado pelo repetido `label=missing`; código legado removido.
- “Ícone visível pertence a outra view, dot ou camada interna”: permanece possível. O teste deve correlacionar view id/ancestrais/alpha com imagem. A lease nova não prova que outra camada não exista.
- “3 s de secure backoff explica exatamente os 2.167 ms”: possível, mas não confirmado pelo arquivo disponível. Novo motivo de indisponibilidade permitirá verificar.
- “Menos linhas/callbacks comprovam ganho de bateria”: descartado. Não houve medição de energia.

## Validação e teste físico

Verificar o CI do head final: unit tests/Robolectric, lint, APK debug, shaders, build release e assinatura temporária. Localmente, Gradle não inicia: distribuição 8.11.1 ausente e download sem acesso de rede. Geradores, teste de distribuição e live_check.sh rodaram; git diff --check passou. Testes novos cobrem lease view/drawable, propriedade/restauração, hold por foreground/config/tema/idade e broadcast de frame para superfície oculta. A matriz de leitores foi sincronizada com o scanner do teste; o próprio CI deve conferir a igualdade. `tools/collect-glass-perf.sh` inclui os novos eventos de paleta/captura e contagens no resumo, mantendo restauração automática das propriedades de diagnóstico.

Instalar somente o APK assinado do **head final** e reiniciar SystemUI uma vez para carregar os hooks novos. Depois executar as transições sem reiniciar SystemUI entre elas:

1. App branco → central diretamente; filmar primeira decisão, cartões/textos/ícones/shelf.
2. Branco → app escuro/colorido → central, sem bloquear; repetir inverso pelo menos cinco vezes.
3. Bloquear/desbloquear nos dois fundos; abrir logo após desbloqueio e repetir.
4. Expandir/recolher minimizados; conferir que a cor muda na mesma decisão global.
5. Conferir quatro ícones na status bar; Timer na Now Bar e Live Update desbloqueado; dez expansões da Now Bar do Gradus sem ícone sumir.
6. Pop-ups sobre Instagram e app branco, vidro e bordas P3; mídia/sliders/QS/lockscreen/toast preservados.
7. Testar iconmode 0/1/2/3, PNG importado e pack preservado; cor automática, fixa, duas cores; textswap e shadeAdaptive desligados; desligar tratamento e confirmar restauração.

Captura curta em Termux (há probes mais caros sob trace, portanto desligar ao terminar):

```sh
su -c 'setprop debug.oulg.trace 1; setprop debug.oulg.perf 1'
# Reproduzir por 30–60 segundos e anotar a hora do screenshot/vídeo.
su -c 'logcat -d -b main,system -v threadtime -s OULG:V' > /sdcard/Download/OULG_PR177_COLOR_AUDIT.log
su -c 'setprop debug.oulg.trace 0; setprop debug.oulg.perf 0'
```

Guardar `SHADE_OPEN_BEGIN`, `SHADE_PALETTE_COMMIT`, `SHADE_PREFETCH`, `SHADE_CAPTURE_UNAVAILABLE`, `ICON_TREAT_COMPACT_HOOK`, `ICON_TREAT_SHELF_INK`, `ICON_TREAT_SHELF_CONTESTED`, `PERF` e `PERF_FRAME`. A latência válida é tFromOpen da primeira captura aceita; HOLD é provisório. Contar writes por view e revisão; não interpretar o total de callbacks como frames. PERF com trace ativo serve para atribuição, não para estimar consumo normal. Para comparação de performance, repetir com trace=0 e perf=1, mesmas telas, brilho, Hz e duração; usar Perfetto para CPU/GPU e medição de energia separada para bateria.

Riscos principais: métodos OEM ausentes, camadas/dot distintos do drawable tratado, circuito de disputa ativado, eventos de foreground ausentes no firmware, diferença intencional com shadelocal e interação de filtros durante animações. Sem teste físico não é possível declarar o defeito visual resolvido nem a tarefa concluída.
