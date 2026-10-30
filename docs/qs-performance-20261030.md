# QS / notificações — checklist consolidado e medição A/B (30/10/2026)

**Base de referência:** `main` após o merge do PR #111 (Galaxy S25 Ultra DZIF,
One UI 9 / Android 17, modo APK-only sem SurfaceFlinger). Esta é uma consolidação
do [backlog histórico](qs-backlog-20261028.md); entradas antigas que descrevem
falhas já corrigidas não são bugs reabertos.

## Estado confirmado pelo usuário no build pós-#111

- [x] 15 tiles pequenos: cores corretas em fundos claros e coloridos.
- [x] Cápsulas grandes: discos e glifos de Wi-Fi, Bluetooth e Quick Share
  mudam juntos, inclusive com o painel aberto e troca de aplicativo.
- [x] Sliders de brilho e volume: geometria, ícones e tinta adaptativos.
- [x] Abertura direta e passagem lateral shade/QS: sem regressões de estado,
  ícones invisíveis, piscadas indevidas ou cor preso no fundo anterior.
- [x] Notificações na shade e pop-ups: seleção de cor estável, sem a regressão
  de fundo/cor reportada antes do #111.
- [x] Compilação que falhava por `final discBackground`: resolvida no PR #111;
  o usuário confirmou build instalado e funcional. Não há relato atual de crash.
- [x] Controles novos nas páginas específicas e obsoletos retirados dessas
  páginas: alterados nos PRs anteriores. Verificação de cada preferência no
  dispositivo continua separada de uma inspeção estática.

## Pendências desta etapa

- [ ] **P0: crossfade visual efetivo** em pop-up, notificações e QS. O estado
  final está correto, porém a passagem parece um salto de cor. A QS possui
  `INK_RAMP_MS=120` e recomposição geral a cada 34 ms: são cerca de quatro
  oportunidades teóricas de recomposição durante a rampa a escala 1. Isso não
  comprova quantos quadros realmente aparecem no Galaxy.
- [ ] **P0: latência desde a mudança dos pixels até a paleta publicada,**
  sobretudo ao chegar a fundo branco. Separar: evento do app, início da captura,
  conclusão da captura, classificação/estabilização, primeira atualização do
  animador e primeiro desenho visível. Não reduzir o `ToneGate` antes de
  comprovar a origem, para preservar a estabilidade conquistada.
- [ ] **P1: player de mídia vazio** (sem reprodução) ainda usa arte/ícones
  claros sobre fundo claro. A rota de `PainterNode` dos tiles rejeita mídia
  deliberadamente para preservar capa de álbum e controles do player ativo.
  Identificar ownership da árvore *EmptyMediaPlayer* e aplicar cor somente aos
  glifos monocromáticos do estado vazio.
- [ ] **P1: medição de custo** do módulo ligado/desligado: tempo de abertura,
  jank, CPU, PSS/memória nativa, cadência/custo de captura, temperatura e
  consumo em sessões longas.
- [ ] **P1: A/B de possíveis otimizações**, depois de medir o build atual,
  mantendo iguais paleta, animações do Android, taxa de atualização da tela e
  sequência de ações. A rampa de animação deve ser testada separadamente do
  algoritmo de detecção da cena.
- [ ] **Validação de regressão física**: modo Theme Park ON/OFF, lockscreen,
  slider 0/1/5/50/99/100%, player ativo, orientação e janelas seguras.

## Itens do backlog histórico fora desta otimização

As linhas antigas sobre Live Updates não-mídia na lockscreen, toasts Samsung,
perfil SF opcional e idiomas do aplicativo não têm evidência suficiente nesta
sessão para serem dadas como concluídas. Permanecem **não verificadas ou em
backlog separado**, sem bloquear a etapa atual de performance.

## Evidência já presente no código

1. `QsBackdrop.publish` só inicia `ValueAnimator` se a troca for conhecida,
   o painel estiver aberto e `ValueAnimator.areAnimatorsEnabled()` for true.
   Se a escala de duração do sistema for zero, a transição não é produzida.
2. Durante a rampa, `QuickSettingsHooks` invalida os nós de desenho e limita
   a recomposição geral a uma vez por 34 ms. A eliminação de uma invalidação
   duplicada de `QsContentHooks` preserva a mesma frequência de desenho.
3. O `CaptureHub` conserva um cliente de preview a 8 Hz, escala 1/8,
   com `evenHidden=true`, mesmo com o QS recolhido. O custo é recorrente,
   mas sua contribuição real a CPU/bateria ainda não foi medida.
4. `ToneGate` pode confirmar uma mudança forte após 100 ms de permanência;
   leituras próximas do limiar exigem segunda captura e permanência maior.
   A latência total precisa distinguir esse gate do custo de captura.
5. A medição do pop-up tem rampa de véu de 180 ms e tinta View animável.
   `POPUP_TRANSITION` e `TEXT_REAPPLY` precisam confirmar se a rota animada
   é escolhida e realmente redesenhada no aparelho.

## Protocolo A/B reprodutível (aparelho)

Capturar dois ensaios com `tools/qs-ab-termux.sh`, sem resetar `logcat` ou
`batterystats`. Executar como shell/root de Termux, após copiar o script:

```sh
sh qs-ab-termux.sh A 90
# Alterar SOMENTE a condição de teste e repetir a mesma sequência:
sh qs-ab-termux.sh B 90
```

Para **custo incremental do efeito ativo**, A=OULG desativado pelo controle
mestre do app, B=OULG ativado, no mesmo APK. O controle mestre não descarrega
os hooks nem necessariamente interrompe toda a checagem ociosa do CaptureHub;
**não equivale ao SystemUI sem o módulo instalado**. Para medir sobrecarga
total instalada, repetir A com o módulo LSPosed desativado e SystemUI reiniciado,
igualando o aquecimento e assumindo a mudança de ambiente. Para **ganho de uma otimização**, A=build atual
com OULG ativado, B=build candidato com OULG ativado. São comparações distintas.

Antes de cada sessão: mesmo brilho fixo, taxa 120 Hz/adaptativa igual, carga/temperatura
semelhantes, mesmas notificações, sem carregamento e 3 aquecimentos. Em 90 s,
repetir ao menos 15 vezes a abertura direta, a passagem shade/QS e a troca entre
dois aplicativos com fundo branco/colorido; usar a mesma ordem e ritmo nos dois
ensaios. Se usar gravação de tela para medir cor, gravar nos dois ensaios
com configuração idêntica; a gravação também custa recursos.

O script recolhe `dumpsys gfxinfo ... framestats` antes/depois, `meminfo`,
`cpuinfo`, amostras `top` a cada cinco segundos, propriedades de animação,
`battery` e eventos `OULG`. Comparar **deltas**, não apenas contadores
acumulados. Para chegar ao atraso visual real, comparar timestamps de captura,
`PANEL_SCENE_COMMIT`, `QS_BACKDROP`, `QS_INK_TRANSITION` e a mudança de
cor medida nos frames da gravação.

Registrar `settings get global animator_duration_scale`; se for zero,
a ausência de crossfade é esperada pelo contrato atual e não indica falha
da classificação. A instrumentação nova informa `animatorsEnabled`,
`durationScale`, `sampleAgeMs`, número de callbacks de animação,
latência até o primeiro callback e duração real. **Callback de animação não
prova apresentação na tela**: só a gravação/FrameTimeline confirma isso.

Consumo de bateria: um intervalo de 90 s é insuficiente para comparar drenagem.
Se CPU e captura sugerirem custo relevante, fazer ensaios pareados de 20–30 min
em cada condição, repetidos e com a tela/temperatura controladas, sem zerar
as estatísticas de bateria do usuário.

## Critérios de aceite

- Nenhuma regressão de cor/disco, estado do toggle ou slider.
- Troca de cor com mais de um quadro intermediário realmente visível e sem
  flash de ícone sobre disco incorreto; medir o atraso inicial e o P95.
- P95 da abertura e jank por 100 aberturas não pioram em relação ao A.
- CPU/PSS do SystemUI e capturas/s não aumentam sem ganho visual demonstrado.
- O player vazio usa tinta legível em fundo branco, sem alterar a arte de
  músicas reproduzidas.
