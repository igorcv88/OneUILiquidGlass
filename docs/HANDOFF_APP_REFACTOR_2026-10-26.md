# Handoff para o Work — refatoração do aplicativo OneUILiquidGlass

**Data:** 26/10/2026  
**Repositório-alvo:** [igorcv88/OneUILiquidGlass](https://github.com/igorcv88/OneUILiquidGlass)  
**Base auditada:** `11e1f3e6f2220c1eb6c7831cdf61279a981dcc03` (`main` antes do trabalho de documentação/ícone)  
**Referência de agrupamento:** [igorcv88/WaEnhancerXCommunity](https://github.com/igorcv88/WaEnhancerXCommunity), especialmente `LiquidGlassActivity.java`, `BottomBarCustomizationActivity.java` e as telas de preferências  
**Catálogo obrigatório:** [PROPERTY_CATALOG_2026-10-26.md](PROPERTY_CATALOG_2026-10-26.md) — 97 propriedades distintas, mais o interruptor global fora das props.

## Resultado esperado

Transformar o APK atual, hoje quase inteiramente dependente de comandos Termux, em um **painel completo para controlar o material de cada superfície de notificação**, com:

1. Interface consistente com o estilo OneMate/Kyant0 usado no WaEnhancer Community e com sua organização em seções; controles claros, refinados e agrupados por função.
2. Acesso no APK a **cada controle visual ou funcional que hoje usa `debug.oulg.*`**, com descrição inteligível, unidade, valor atual, padrão e botão de reset; respeitar dependências e necessidade de reinício.
3. **Switches independentes para cada efeito e superfície**, mesmo quando hoje a implementação tem somente um switch global. Preservar as proteções de segurança obrigatórias.
4. **Preview responsivo dentro do APK**, representando aproximadamente o efeito de cada slider e toggle, mais uma amostra completa de heads-up, lockscreen e shade, com fundos/temas alternáveis.
5. **Notificação de teste real do Android**, expandida e com ações funcionais, enviada pelo próprio app usando `NotificationManager`; deve dizer “Notificação de teste”.
6. Persistência de presets, comparação com valores efetivos, backup/export/import, indicação de aplicação pendente, diagnóstico e recuperação.
7. O novo ícone de cartões de vidro deve ser o ícone do APK e aparecer no banner; estes recursos **já foram preparados** no commit/PR que acompanha este handoff.

Não fazer uma reescrita do renderer, nem mudar os valores físicos do material como efeito colateral de construir a UI. O usuário quer controlar o renderer existente e manter seu aspecto aprovado. A interface deve funcionar quando o add-on SurfaceFlinger está ausente e quando está instalado.

## Evidências e estado atual

- `MainActivity.java` monta uma `LinearLayout` com texto, um `Switch` chamado “Experimental glass” e um botão que agenda um `BigTextStyle` em 5 segundos. Não há sliders, preview, abas, ações da notificação, presets nem estado individual de funcionalidades.
- `Config.KEY_ENABLED` é um booleano em `glass` SharedPreferences, com fallback por `ConfigProvider` para SystemUI. O módulo em SystemUI consome esse estado; o app ainda instrui a reiniciar SystemUI.
- `glass/Tuning.java` lê **73 chaves** de `debug.oulg.*`, com cache de aproximadamente 1 segundo, valida/clampa muitos valores e aumenta `generation` quando há mudança.
- `sfhook/oulg_sf.c` lê **25 chaves** `debug.oulg.sf.*`, das quais `sf.optics` também aparece no Java. São **97 nomes distintos** no total; as propriedades C, salvo o marcador de status, entram em vigor na compilação do shader.
- `sfhook/module/service.sh` liga `sfrefract=1` no boot quando não há override e coloca `sf.optics=9` como indicador/fallback; **esse número isolado não prova execução de refração**.
- A implementação já distingue superfície e material em `HeadsUpHooks`, `Eligibility`, `GlassDrawable`, `BackgroundBlurBridge`, `SemBlurBridge`, `BackdropMotion`, `ShadeTone` e `GlassSpec`. Não mesclar tudo em uma Activity monolítica.
- Os padrões na base auditada refletem ajustes até **26/10**: `sflens=0.45`, `liveglint=0.10`, `shadeglint=0.10`, `lenssettle=100`, `lensgate=directional`, `veilmax=0.25`, `lightglass=0.75`, `shadelightglass=0.27`, `livedarkalpha=20`, `livedarkfill=10`, `liverim=2`. Não copiar valores antigos do README anterior (por exemplo, `sflens=0.7` ou `lenssettle=600`).

### Referência visual específica

O usuário lembrava o nome como **OneMate**. A própria base do WaEnhancer Community confirma em `app/src/main/java/com/waenhancer/activities/LiquidGlassActivity.java` a opção “OneMate material (Kyant0 Liquid Glass)”. Ela descreve centro com cores do fundo, lens periférica, neutral wash de 18%, reflexos direcionais, blur de 10 dp na barra flutuante e de 30 dp em superfícies do WhatsApp. **Esses números descrevem aquele componente, não são parâmetros a copiar para cartões do SystemUI.** A referência é a sensação do vidro e a linguagem dos controles. A referência funcional vem de `MaterialSwitch`, `Slider`, captions/sections, disabled-state por dependência e laboratório gráfico na mesma Activity.

Estética do novo aplicativo:

- Dark por padrão, claro suportado, base azul-noturno/cinza-neutro, texto com contraste nativo, acentos ciano e lilás do novo ícone; evitar cards com vidro em cada linha.
- Glass só onde ajuda: cabeçalho, seletor flutuante de superfícies, área do preview e poucos controles ativos. Restante usa materiais sólidos e divisores suaves.
- Pílulas de seleção, controles agrupados em cards discretos, headings curtos, switches tipo Material 3/One UI, sliders com valor numérico editável, unidade e explicação “menos ↔ mais”.
- Animação breve em transição de preview, sem uma bolha excessivamente luminosa ou contorno branco contínuo. Ícone já existe em `.github/assets/oneui-liquid-glass-icon.png`, recurso Android adaptativo em `app/src/main/res/drawable/oulg_launcher.xml` (foreground `drawable-nodpi/oulg_launcher_foreground.png`, monochrome `drawable/oulg_launcher_monochrome.xml`).
- Reaproveitar padrões de organização e explicações do WaEnhancer. Não copiar classes do renderer do WhatsApp, assets de terceiros ou dependências sem necessidade. A UI nova pode usar Material Components com Views/RecyclerView (mínimo risco para o projeto Java) ou solução equivalente após verificação do build.

## Arquitetura de telas

| Navegação | Conteúdo e controles |
| --- | --- |
| **Início** | Switch geral, estado LSPosed/SystemUI, modo APK-only vs SurfaceFlinger, status de aplicação, presets, última alteração e “Notificação de teste” em destaque |
| **Heads-up** | Switch próprio, blur, refração/lente, captura e motion gate, cor do corpo, rim, glint, highlights; preview de notificação detalhada |
| **Tela de bloqueio** | Switch próprio, blur/material, perfil 1, força de lente, dark alpha/fill, rim/glint, overflow pill; preview com relógio, wallpaper e notificação |
| **Central de notificações** | Switch próprio, per-card blur, tone mapping, legibilidade, texto/ícones/cabeçalho, alpha e fallback, transições; preview de cartões múltiplos |
| **Material e óptica** | Controles transversais, método de backdrop, IOR, saturação, dispersão, curvas e parâmetros avançados, com indicação das superfícies afetadas |
| **Diagnóstico** | Estado real, logs, métricas, export, incompatibilidades, resolução de falhas e ações de recuperação |

Preferir navegação inferior com 3 destinos principais (**Início, Superfícies, Diagnóstico**) e, dentro de Superfícies, segmented control **Pop-up / Bloqueio / Central**. Assim a barra não terá 6 tabs. Busca por “nome/efeito/prop”, favoritos e “mostrar avançados” são necessários para navegar por quase cem knobs. Cada row abre um bottom sheet com explicação e preview focada; o slider compacto pode ficar na row.

### Cartão de configuração (contrato visual)

- Título humano, exemplo “Refração da borda”, subtítulo de uma linha (“Desloca visualmente o fundo nas quinas”) e **nome técnico** em texto discreto `debug.oulg.sflens`.
- Controle apropriado: switch; slider contínuo com campo numérico; color picker RGB; select enum com descrições; ou leitura somente.
- Valor atual (inclui unidade), valor padrão, reset por item, status **Ativo / Não se aplica neste modo / Pendente / Falhou / Requer recomposição**.
- Inline pequeno “menos” e “mais”, com mini preview local dos extremos e preview maior sincronizado acima.
- Ícone informativo abre explicação do efeito físico, superfície afetada, conflito/dependência, custo de GPU/captura se relevante e aplicação (live, restart SystemUI, recompilar SF).
- Nunca esconder uma prop só porque o modo atual a torna inativa: mantê-la em Avançado ou mostrar disabled + motivo. Guardar o valor para quando a dependência for ligada.

## Modelo de configuração e ponte entre processos

Criar um **registry tipado, único e testado**, preferencialmente `SettingSpec`/JSON gerado com checagem contra os readers Java e C. Campos mínimos:

```text
key, group, surfaceScope, title, explanation,
type(boolean|float|int|enum|rgb|readonly), default, min, max, step, unit,
dependencies[], applicability(APK|SF|BOTH), applyMode(LIVE|SYSTEMUI_RESTART|SF_RECOMPILE|READONLY),
previewStrategy, risk(ordinary|expert|danger), storageOwner, validator
```

**Quatro estados distintos** por setting:

1. `default`: compilado no código, como no catálogo.
2. `observed`: `getprop` ou estado do processo observado; pode refletir CLI anterior.
3. `desired`: valor salvo/intencionado pelo usuário e exibido na UI.
4. `effective`: confirmado em SystemUI ou na versão de shader ativa; pode diferir de desired.

Ao abrir o novo APK pela primeira vez, não aplicar um preset default silenciosamente. Ler overrides existentes, preservar sua eficácia e oferecer “Adotar valores atuais” e “Restaurar padrões do código”. Definir precedência explícita: valor persistido na UI por chave (quando existe) > override externo detectado e ainda não adotado > padrão do código. Se a UI e um terminal alterarem a mesma prop, mostrar conflito e permitir recarregar, sem sobrescrever silenciosamente o ajuste do Terminal.

### Controles Java (sem depender de root para cada mudança)

Para as props consumidas por `Tuning`, migrar gradualmente para um `ConfigRepository` tipado no APK e um **canal de leitura explícito dentro de SystemUI**. O `ConfigProvider` existente já permite leitura do interruptor global: ampliá-lo com versionamento/generation e URIs estritamente documentadas para leitura de valores, com invalidação/observer ou polling de baixo custo. Em `Tuning`, manter fallback para `SystemProperties` para preservar scripts de Termux existentes.

Segurança da ponte: validar `Binder.getCallingUid()` com `PackageManager.getPackagesForUid`/UID esperado, escopo de URI/projection, tipos e chaves em allowlist. A consulta atual `getCallingPackage()` isoladamente não substitui essa validação. Provider continua **read-only** e não deve oferecer CRUD arbitrário; exportar apenas dados de configuração pública não sensíveis. Sem salvar secrets/tokens. Evitar loop de observers e leituras síncronas pesadas em `onDraw`.

Configurações de switches **novas** precisam de campos próprios, por exemplo:

- `surface.headsup.enabled`, `surface.keyguard.enabled`, `surface.shade.enabled`;
- `feature.edge.enabled`, `feature.tonemap.enabled`, `feature.glint.enabled`, `feature.lens.enabled`, `feature.per_card_blur.enabled`, com exceções por superfície.

São *novas flags de produto*, **não afirmar que já existem como props**. Integrar no ponto de aplicação do efeito mantendo as rotinas de fallback e release. A lógica efetiva deve ser global AND surface AND feature AND eligible AND supported. A flag desativada deve restaurar só o comportamento nativo daquela camada; não substituir Views, conteúdo, listeners nem campos tipados internos da Samsung.

Nem toda rotina é uma “função opcional”: watchdog, checagem de versão, retorno ao fundo nativo, exclusão de captura e limpeza no detach **não podem** ser desligados pelo usuário; podem ter estado e diagnóstico.

### Controles SurfaceFlinger

O processo normal do APK **não tem permissão Android para escrever `debug.oulg.sf.*` indiscriminadamente**. Não assumir que a existência do KernelSU equivale a autorização de `su` para o app.

Propor duas rotas, com degradação segura:

- Se houver **ponte privilegiada explícita e autorizada** pela instalação existente, usar helper mínimo de comandos **allowlist de chaves e tipos**, sem shell arbitrário e sem um root manager genérico. Validar UID chamador, valores, módulo instalado, firmware e disponibilidade. Manter um arquivo root-owned de overrides versionado para reaplicar somente no boot quando o usuário optou por persistência. Documentar o mecanismo real e testá-lo.
- Sem ponte: mostrar valores lidos e **comandos `su -c 'setprop ...'` prontos para copiar**, com status `aplicação manual`. Não simular êxito.

`sf.optics` é um marcador de versão produzido pela biblioteca (9 na base). UI somente leitura. Verificar também `OULG_SF`, evidência de `REWRITE_OK` e estado de módulo para afirmar que a lente real está ativa. `sfrefract=1` sozinho não basta.

Para parâmetros `sf.*` com fase **SF_RECOMPILE**:

1. Slider altera preview instantaneamente, e salvar altera `desired`.
2. Exibir “Aguardando recompilação” e diff das mudanças.
3. Ação separada “Aplicar à próxima reinicialização” por padrão; operação avançada “Reiniciar SurfaceFlinger” apenas com confirmação forte, pré-check de compatibilidade, watchdog e procedimento de recuperação.
4. Nunca fazer restart de SF automaticamente durante drag, salvar preset, iniciar Activity ou receber boot. Não apagar caches/arquivos sem plano de rollback e caminho exato.
5. Se não for possível confirmar recompile, manter `effective=unknown/old`, não mostrar “Aplicado”.

## Preview local (requisito central)

Uma preview **não é screenshot de SystemUI** nem prova fidelidade física. Usar pipeline local com assets sintéticos (wallpaper com detalhes, tela clara de app, conversa/rolagem simulada, padrão quadriculado), sem captura de apps reais ou dados pessoais. Não usar a mesma UI que lê props como único mecanismo de preview; a preview deve reagir imediatamente ao `desired`, sem mexer no shader do telefone.

Componentes:

- `PreviewSurface`: modelo fixo de heads-up expandida com app/icon/title/text/action row; modelo de lockscreen com relógio e overflow; modelo da shade com vários cards e cabeçalho.
- `PreviewBackdrop`: seletor de **fundo claro / escuro / colorido / quadriculado** para tornar deslocamento, blur e contraste perceptíveis; alternar tema claro/escuro e escala de preview.
- `PreviewMaterial`: aproximar blur por RenderEffect/local buffer, saturação/tint/alpha, rim, glint, shadow, difração/refração local (quando viável) usando cópias controladas do backdrop. Reaproveitar fórmulas testáveis de `GlassSpec`/material se possível, mas não importar o hook SF inteiro.
- `PreviewBinding`: mapear cada prop para efeito visível relevante (ex.: `liveradius` afeta nitidez; `sflens` desloca borda sintética; `veilmax` afeta legibilidade; `liveglint` afeta arco interno). Prop sem representação fidedigna tem **diagrama de extremos + explicação**, não uma animação falsa.
- `PreviewBadge`: “Simulação aproximada. Resultado real depende do fundo, Samsung blur e SurfaceFlinger”. Para C: “simulação atualizada; dispositivo pendente de recompilação”.

Uma régua “Menor / Atual / Maior” no bottom sheet deve deixar claro o sentido do parâmetro. Controlar 100 props com um render pass custoso é desnecessário: throttle durante slider, cache de fundo e shader, nenhuma captura privada, nenhuma animação quando tela invisível, cancelamento ao sair da Activity. Controles de desempenho/captura mostram custo estimado **qualitativo**, nunca inventar mW ou FPS específicos.

## Notificação de teste real e representativa

Manter o botão atual, elevar para **“Enviar notificação de teste”**, visível no início e na aba Testes. O botão publica uma **notificação Android verdadeira**, originada pelo APK:

- Channel `IMPORTANCE_HIGH` próprio com nome identificável e permission flow `POST_NOTIFICATIONS` (Android 13+). Se não houver permissão, solicitar e **retomar a postagem** após a concessão, ou mostrar cancelamento claro. Verificar canal e DND.
- `Notification.Builder` + `BigTextStyle` com título **“Notificação de teste”**, texto resumido e corpo expandido com linhas suficientes para reproduzir uma notificação detalhada; timestamp real; ícone pequeno **monocromático** dedicado e `largeIcon` com a marca colorida do APK, se adequado.
- Pelo menos duas `Action` reais com `PendingIntent` explícitos/seguros: **“Abrir ajustes”** abre a Activity correta; **“Dispensar”** cancela somente aquela notificação. Não usar botões decorativos/inertes. Opcional “Responder” com `RemoteInput` + receiver que trate uma resposta de teste localmente; nunca sugerir envio a outra pessoa.
- Testes **agora / em 5 s** e variantes “Expandida”, “Heads-up”, “Várias na central”; a ação precisa sempre dizer o que ocorreu e permitir cancelar. NotificationManager publica o objeto real e SystemUI decide se surge heads-up; `IMPORTANCE_HIGH` não garante HUN por si.
- Notification `setAutoCancel`, IDs/canais controlados, Activity/Receiver com `exported` correto, ícones válidos, sem spam e com cleanup ao sair/selecionar. Ação “Descartar testes” só cancela as notificações deste app.
- A preview offline usa esse **mesmo modelo de texto/ações** (sem chamar a API de notificação) para comparação visual; o botão real é o teste final.

Não falsificar a origem de outros aplicativos e não usar strings genéricas com aparência de captura privada. O texto pode explicar “Teste expandido de vidro — deslize/expanda/abra o painel para comparar”.

## Agrupamento das props (todos os itens do catálogo)

| Grupo da UI | Chaves/conjuntos |
| --- | --- |
| Motor/modo | `sfrefract`, `liveblur`, `backdrop`, `shadeblur`; status `sf.optics` |
| Glass por superfície | `liveradius`, `liveshaderadius`, `kgblurradius`, `semradius`, `semradiuslens`, `kgblurpath`, `shadeblurpath`, `shadefadecut`, `kgwinblur`, `pillglass`, `pillblur` |
| Bordas e luz | `liveoptics`, `livesheen`, `liveglint`, `shadeglint`, `liverim`, `kgrim`, `kgglint`, `lightedge`, `lightoutline`, `sflens`, `kglens`, `kgoptics`, `huoptics` |
| Captura/AGSL | `profile`, `lens`, `refract`, `ior`, `disp`, `blur`, `sat`, `tint`, `rimhz`, `rimblur`, `hz`, `hzkg`, `livelens`, `lensmaxage`, `lenssettle`, `lenstransition`, `lensgate`, `lensregiondiff` |
| Cores/contraste | `semcurve`, `semalpha`, `semshape`, `shadetone`, `shadecap`, `shadelift`, `veilmax`, `stylelo`, `stylehi`, `lightglass`, `lighttone`, `shadelightglass`, `livedarkalpha`, `livedarkfill`, `kgdarkalpha`, `kgdarkfill`, `textswap`, `headertone`, `icontone`, `popuptone`, `shadescaleveil`, `shadelocal`, `paneldim` |
| Layout/diagnóstico | `headergap`, `trace`, `perf` |
| Shader de heads-up (C) | `sf.core`, `sf.taps`, `sf.ramp`, `sf.hubloom` |
| Shader da lockscreen (C) | `sf.kgcore`, `sf.kgtaps`, `sf.kgramp`, `sf.kgsat`, `sf.kgtone`, `sf.kgrim`, `sf.kgspec`, `sf.kgflat`, `sf.kglevel`, `sf.kgcool`, `sf.kgtilt`, `sf.kgline`, `sf.kgcorecap`, `sf.kgflatcap`, `sf.kgcoredim`, `sf.kgglow` |
| Shader da shade e debug (C) | `sf.shadecap`, `sf.shadelift`, `sf.debug`, `sf.norewrite`; `sf.optics` indicador somente leitura |

**O catálogo é a fonte exaustiva para implementadores.** A tabela acima é o mapa de navegação, não substitui intervalos, defaults e dependências descritos lá.

## Presets, persistência e segurança

- Presets iniciais: **Padrão do código**, **Atual do dispositivo** (snapshot que não altera nada), **Personalizado**. “Experimental” só quando opt-in, não default. Perfis por superfície podem herdar `Global`.
- Export/import JSON com `schemaVersion`, modelo/firmware, dados desejados, timestamp, modo necessário e lista de props desconhecidas. Mostrar diff, recusar valores inválidos; importar não significa automaticamente aplicar no compositor.
- Uma alteração por vez pode ser desfeita e restaurada. “Reset de grupo” e “Reset geral” pedem confirmação e salvam snapshot; nunca apagar propriedades desconhecidas ou dados de outros módulos.
- Um “modo seguro” desativa somente efeitos não essenciais e restaura background nativo; caminhos de recuperação via LSPosed e KernelSU documentados. Nunca reiniciar em loop se o estado de bridge/root é incerto.
- Não associar a persistência do app ao módulo de root ou integridade do telefone sem ação explícita. O usuário mantém arquitetura KernelSU/CLI sem manager visível, e estabilidade/Wallet são prioridades. Evitar ampliar escopos LSPosed para `system_server`, `SettingsProvider`, outras apps ou hook de segurança.

## Plano de implementação para o Work

1. **Inventário e testes antes do design:** validar cada leitor do catálogo, gerar registry, verificar Git status e preservar alterações existentes. Criar testes que falhem quando uma nova prop não estiver registrada.
2. **Infra de configuração:** `SettingSpec`, tipos/intervalos, migração de overrides, UI store, provider com acesso restrito, `Tuning` integrando provider e props de compatibilidade. Testes unitários de defaults/clamps, concorrência, fallback e read-only.
3. **Feature gates por superfície:** switches independentes e release correto de cada efeito; não tocar em geometria/conteúdo/press states nativos. Confirmar que `Experimental glass` continua desligando tudo.
4. **UI/preview:** componentização, cards e controles agrupados, estado desejado/efetivo, previews offline com fundos distintos, acessibilidade e responsividade. Importar orientação visual OneMate e organização WaEnhancer sem acoplar renderer.
5. **Notificação real + presets + diagnóstico:** ações funcionais, canal/permissão, presets reversíveis, relatório de estado, entradas para SF avançado e comandos copiáveis se necessário.
6. **Integração SF segura:** só depois de testar bridge/root, versionamento, pending/recompile, rollback e watchdog. Não habilitar auto-restart de compositor.
7. **QA e entrega:** screenshots/vídeo de cada tela e do teste real em One UI 9, builds em CI, revisão de diferenças ópticas antes de qualquer mudança no motor. PR(s) separados por estágio para revisão.

## Critérios de aceite verificáveis

- Todas as **97** props têm definição visível/pesquisável, valores padrão e efetivos, validação, UI apropriada ou status read-only; cada efeito visual controlável na superfície relevante. Nenhuma chave do código fica órfã.
- Alterar slider reflete **imediatamente na preview**; ao aplicar, o estado real da engine é corretamente rotulado. Prop C não recebe status “aplicada” sem evidência de recompilação.
- Heads-up, lockscreen e shade têm switches próprios e o botão global continua removendo o material de todas. Desativar uma não desmonta as outras.
- Preview alterna fundos escuro, claro, colorido e grade; ajustes de blur, refração, glint, rim, véu, transparência e cor produzem respostas visuais perceptíveis e coerentes. A legenda “aproximado” nunca desaparece.
- A notificação teste usa o **ícone novo**, `BigTextStyle`, título “Notificação de teste” e **pelo menos duas ações executáveis**. Testar fluxo de permissão concedida/negada e HUN suprimido pelo sistema.
- Primeiro lançamento **não substitui** overrides Termux. Presets e resets são reversíveis, com restauração após reboot segundo política escolhida.
- Testar 3 superfícies × 2 temas × fundos claros/escuros × modo APK-only/compositor, incluindo abrir/fechar shade, fade, mover pop-up, expandir, desbloquear, mudanças de tema e fallback sem módulo SF.
- Sem captura de conteúdo protegido em preview, sem gravação de texto de notificações em logs, sem ANR, sem leak de listener, sem root arbitrário, sem restarts automáticos de SurfaceFlinger. Watchdog e fallback permanecem intactos.
- Validar `./gradlew testDebugUnitTest lintDebug assembleDebug`, testes C relevantes, manifest/recursos e CI. Fazer smoke test **no aparelho real** antes de afirmar paridade óptica.

## Fora de escopo nesta refatoração

Portar código do OneMate/WhatsApp, implementar Brief/Edge Lighting, mexer no music player/Now Bar, novos hooks em processos privilegiados, alterar defesa do sistema, automatizar instalação root ou reescrever física da lente para encaixar na preview. A arquitetura física do projeto permanece `CaptureHub`/backdrop + `GlassDrawable`/shader + bridges de blur + hook lifecycle + biblioteca SF opcional.

## Handoff de arte/README já executado

- Arte raster original: `.github/assets/oneui-liquid-glass-icon.png`.
- Ícone para o APK: adaptativo, `app/src/main/res/drawable/oulg_launcher.xml` (fundo em gradiente, foreground gerado de `.github/assets/oneui-liquid-glass-icon.png`, camada monochrome); `AndroidManifest.xml` aponta `android:icon` e `android:roundIcon` para ele. Ícone pequeno da notificação de teste: `drawable/oulg_notification.xml`.
- Banner: `.github/assets/oneui-liquid-glass-banner.png`, gerado do `.svg` fonte com o ícone no lado esquerdo.
- README no estilo do WaEnhancer Community: banner centrado, badges automáticas de release/build/downloads/stars/forks, status, instalação, features, troubleshooting, build e links para estes handoffs.

**Nota de escopo:** a criação desta especificação e da identidade visual não significa que a nova interface ou o novo canal de configuração já tenham sido implementados.
