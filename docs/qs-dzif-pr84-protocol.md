# DZIF: coleta curta de Quick Settings e notificações (PR #84)

Este protocolo foi preparado para o **APK produzido por este PR**, não para o build anterior.
O diagnóstico distingue instalação, composição, desenho efetivo e entrega de configurações.
Não altera as cores nem as configurações permanentes. Ativa temporariamente
`debug.oulg.trace=1` e restaura seu valor ao finalizar.

## Coleta no Termux

Cole o bloco completo no Termux. **A cada etapa, volte ao Termux e pressione Enter**; digite
`p` e Enter para pular. **Ctrl+C finaliza e compacta imediatamente**, sem esperar 180 segundos.
O `read` lê `/dev/tty`, pois a entrada padrão de um heredoc acaba antes das perguntas (causa da
coleta anterior ter pulado todas as etapas). O único arquivo criado em Downloads é um `.tar.gz`.

```bash
bash <<'OULG_DIAG'
set -u
DOWNLOADS=/storage/emulated/0/Download
mkdir -p "$DOWNLOADS"
STAMP=$(date +%Y%m%d_%H%M%S)
START=$(date '+%m-%d %H:%M:%S')
WORK=$(mktemp -d)
ARCHIVE="$DOWNLOADS/OULG_DZIF_PR84_$STAMP.tar.gz"
OLD_TRACE=$(su -c 'getprop debug.oulg.trace' 2>/dev/null)
[ -n "$OLD_TRACE" ] || OLD_TRACE=0
CLOSED=0

mark() { printf '%s | %s\n' "$(date '+%m-%d %H:%M:%S')" "$1" >> "$WORK/steps.txt"; }
finish() {
  [ "$CLOSED" -eq 0 ] || return 0
  CLOSED=1
  trap - EXIT INT TERM
  mark DIAGNOSTICO_ENCERRADO
  {
    printf 'Inicio: %s\nFim: %s\n' "$START" "$(date '+%m-%d %H:%M:%S')"
    su -c 'getprop ro.build.fingerprint'
    su -c 'pm path com.android.systemui'
    su -c 'settings get global animator_duration_scale'
  } > "$WORK/device.txt" 2>&1
  su -c 'getprop' | grep '^\[debug\.oulg\.' > "$WORK/properties.txt" || true
  su -c "logcat -d -v threadtime -b main,system,crash -s OULG:V OULG_SF:V AndroidRuntime:E DEBUG:F libc:F '*:S'" \
    > "$WORK/all.log" 2> "$WORK/logcat_errors.txt" || true
  awk -v st="$START" 'substr($0,1,14) >= st' "$WORK/all.log" > "$WORK/session.log"
  grep -E 'event=(CONFIG_|QS_|SHADE_|HEADER_|INK_|POPUP_|LIVE_|TOAST_|TUNING)|FATAL EXCEPTION|NoSuchMethodException|ClassNotFoundException' \
    "$WORK/session.log" > "$WORK/events.log" || true
  su -c "setprop debug.oulg.trace $OLD_TRACE" 2>/dev/null || true
  tar -czf "$ARCHIVE" -C "$WORK" \
    steps.txt device.txt properties.txt session.log events.log logcat_errors.txt
  printf '\nColeta encerrada. Arquivo único:\n%s\n' "$ARCHIVE"
  ls -lh "$ARCHIVE"
}
trap 'finish' EXIT
trap 'finish; exit 130' INT
trap 'finish; exit 143' TERM

su -c 'setprop debug.oulg.trace 1'
mark DIAGNOSTICO_INICIADO
etapa() {
  mark "START $1"
  printf '\n%s\nVolte ao Termux: Enter = concluído; p = pular; Ctrl+C = finalizar: ' "$2"
  resposta=
  IFS= read -r resposta < /dev/tty || true
  if [ "$resposta" = p ]; then mark "SKIP $1"; else mark "END $1"; fi
}

etapa QS_COLORIDO '1. Central de controle sobre fundo colorido: aguarde 2 segundos; veja discos ligados/desligados e labels.'
etapa QS_BRANCO '2. Central de controle sobre fundo branco: aguarde 2 segundos; veja discos, ícones, labels e cabeçalho.'
etapa SHADE_BRANCO '3. Central de notificações sobre fundo branco: mantenha os cartões visíveis por 2 segundos.'
etapa BRILHO '4. Mova apenas o brilho de 0 a 50 e 100%, observe outras lentes e a ponta.'
etapa VOLUME '5. Mova apenas o volume de 0 a 50 e 100%; observe se outras cápsulas desaparecem.'
etapa CONFIG '6. Opcional: mude uma opção AO VIVO de QS no app, volte à central e observe quando a mudança aparece.'
etapa POPUP '7. Opcional: com heads-up aberto, troque fundo colorido por branco e volte.'
etapa LIVE '8. Opcional: expanda uma live update de aplicativo não-mídia; compare parada e durante o gesto.'
etapa TOAST '9. Opcional: provoque um toast (por exemplo, Copiado) e observe se o vidro permanece.'
finish
OULG_DIAG
```

## Linhas necessárias e interpretação

| Evento | O que confirma | O que **não** confirma |
|---|---|---|
| `QS_TILE_PROBE_READY`, `QS_TILE_ABI` | Funções QuickTile/IconTextTile encontradas e hookadas | Cor visível do disco |
| `QS_TILE_BIND` | Argumentos tipados reais; máscaras e booleanos no DZIF (`trace=1`) | Qual booleano significa ligado sem comparação entre cenários |
| `QS_TILE_SURFACE_COMPOSE` | Cores, máscara e classe da forma recebidas por `BoxWithOutline`, incluindo discos se o tipo for reconhecido | Cor final pós-Theme Park e composição |
| `QS_SLIDER_ORIGIN`, `QS_SLIDER_SOURCE`, `QS_SLIDER_INTERACTION`, `QS_SLIDER_STATUS` | Origem/candidata da barra, eventos de pressão e estágios de desenho | Que `role=unknown` seja brilho ou volume |
| `QS_EDGE_SUPPRESS`, `QS_EDGE_RESTORE` | Supressão/reconstrução do compositor | Ausência de AGSL sem evento óptico correspondente |
| `CONFIG_RECEIVED` → `CONFIG_DISPATCH` → `QS_CONFIG_RECEIVED` → `TUNING_DISPATCH` → `QS_TUNING_APPLY` → `QS_COMPOSE_REFRESH` → `QS_CONFIG_RENDER` | Caminho da configuração, inclusive publicação assíncrona da nova `Tuning`, até a pintura de texto | Mudança no pixel final de cápsulas que não são texto |
| `QS_OPTICS_COVERAGE`, `QS_OPTICS_DRAWN` | Tentativa e cobertura de óptica | Correspondência visual com inspiração |
| `QS_HEADER_TEXT_DRAWN` | Nó de cabeçalho com tinta renderizada, `clockVerified` quando for hora | Que o relógio visível seja esse nó se `clockVerified=false` |
| `POPUP_TRANSITION` | Momento da troca do estilo e rampa nominal | Latência percebida ou continuidade dos pixels sem vídeo |

O `role` só pode mudar para `brightness` ou `volume` quando o stack do Samsung realmente
contiver um nome inequívoco. Caso contrário fica `unknown`, sem inventar a identidade.
De forma análoga, o diagnóstico dos 15 tiles nunca grava nomes, Wi‑Fi, textos de notificações
ou conteúdo digitado: apenas tipos, booleanos e inteiros/cores.

Não instalar APK de um PR diferente no meio da coleta. Enviar somente o `.tar.gz` gerado
e, se necessário, um único print mostrando brilho e volume em valores diferentes.
