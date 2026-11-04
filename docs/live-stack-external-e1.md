# E1 — identificar possível produtor externo da cápsula das Live Updates

Experimento **somente de leitura**, introduzido após o parecer independente em
`docs/OULG_PARECER_PR147_CAPSULA_BLUR_2026-11-04.md` (PR #148). O parecer propõe H6:
uma view ou desenho nativo de indicador de pilha, por trás do cartão translúcido,
possivelmente irmã do `CardStackView`. **H6 não é uma causa confirmada.**

## Como funciona

O modo antigo `debug.oulg.stackprobe=1` é preservado. O modo **2** estende a timeline
até 1200 ms (ticks novos 640, 800, 960, 1200) e observa, em paralelo:

- Os filhos do pai direto do `CardStackView`, exceto o próprio stack, e os filhos dos
  contêineres ancestrais `ongoing_card_stack_and_shadow_layout` e
  `OngoingCardBackgroundContainer`, até quatro níveis ancestrais.
- Até 56 views externas por amostra, profundidade máxima 6 e 42 linhas de alterações,
  registrando sempre um resumo da visita (`LIVE_STACK_EXTERNAL_TICK`).
- Classe, resource ID, identidade, posição e dimensões em tela, alpha, visibilidade,
  `translationX/Y/Z`, escala, `getZ()`, elevação, `clipToOutline`,
  `clipBounds`, outline provider e geometria, cores de sombra, background,
  foreground, drawables/layers e filtros. **Nenhum conteúdo de texto ou bitmap.**
- Uma única lista por processo de nomes de métodos declarados em `CardStackView`
  contendo `draw`, `shadow`, `peek`, `dummy`, `behind` (sem invocá-los).

Para não interferir desnecessariamente nos tempos, após 480 ms só o stack principal
e os **nós externos** são inspecionados; o percurso profundo dos botões termina.
Não há mudanças nos drawables, blur, cores, contornos, layout, interação, nem SurfaceFlinger.

Os novos eventos são `LIVE_STACK_EXTERNAL_NODE` (somente propriedades alteradas),
`LIVE_STACK_EXTERNAL_TICK`, `LIVE_STACK_NATIVE_METHOD` e
`LIVE_STACK_NATIVE_METHOD_END`. A timeline existente `LIVE_STACK_TIMELINE_*`
segue com os mesmos IDs `burst` e timestamps.

## Instalação e captura

**É necessário instalar um APK compilado com este PR**; só ligar a propriedade em
uma versão anterior não habilita o experimento.

Execute no Termux:

```sh
su -c 'sh -s' <<'SH'
set -eu
STAMP=$(date +%Y%m%d_%H%M%S)
BASE="/sdcard/Download/OULG_E1_$STAMP"
cleanup() {
  setprop debug.oulg.stackprobe 0
  [ -n "${PID:-}" ] && kill "$PID" 2>/dev/null || true
}
trap cleanup EXIT
setprop debug.oulg.trace 0
setprop debug.oulg.stackprobe 2
logcat -v epoch -T 1 -b main,system \
  -s OULG:V OULG_TEST:V AndroidRuntime:E > "$BASE.log" &
PID=$!
sleep 1
log -t OULG_TEST BEGIN_E1_CAPTURE
screenrecord --time-limit 15 --bit-rate 8000000 "$BASE.mp4"
log -t OULG_TEST END_E1_CAPTURE
sleep 1
echo "VIDEO: $BASE.mp4"
echo "LOG:   $BASE.log"
SH
```

Faça preferencialmente duas coletas separadas:

1. **Controle negativo:** somente uma live update, expandir e manter por mais de 1 s.
2. **Comparação:** duas live updates (ou uma live update + mídia), abrir o cartão
   expandido e alternar devagar, mantendo **mais de 1 s de pausa após cada swipe**.
   Deixe a cápsula visível durante algumas dessas pausas.

Envie os MP4/log de ambos os cenários. A propriedade volta a 0 no final, inclusive
se o comando for interrompido. Não é necessário habilitar o trace geral.

## Interpretação e limites

Se surgir um nó externo com cerca de 1232×200 px, base aproximadamente 12 px
abaixo da borda do cartão da frente, e ele mudar ~400–600 ms após a estabilização,
isso **fortalece** H6 e identifica um candidato a confirmação por E2.

Se não surgir, H6 **como view externa visível na árvore percorrida** fica
enfraquecida; desenho direto em `Canvas`, `RenderNode`, objeto fora dos limites
visitados ou outra composição continuam possíveis. A ausência de mudanças de
propriedades em Java não exclui animações assíncronas.

Nenhum A/B de cor magenta ou supressão está implementado. Não tratar uma
correlação espacial/temporal como atribuição definitiva dos pixels.
A demora eventual da entrada do blur (M2) continua independente; E3 deve ser
avaliado em outro experimento após esta coleta.

**Critério de conclusão:** correlacionar o último quadro sem cápsula e o primeiro
com cápsula com os eventos `LIVE_STACK_EXTERNAL_*`, documentar candidaturas e
resultados negativos, e somente então considerar E2 com identificação exata.
