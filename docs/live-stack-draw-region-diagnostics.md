# Live Updates DZIF — correlação de desenho, cápsula tardia e entrada de blur

Este PR adiciona apenas **telemetria read-only**, sem corrigir ou antecipar a causa dos artefatos. O diagnóstico anterior (`OULG_BUBBLE_20261104_114738.log` + vídeo de mesmo nome) encontrou o contorno secundário aparecendo cerca de 100–120 ms após o primeiro cartão visível. A árvore, porém, não relacionava cada `LiveGlassDrawable` ao host e ao momento do desenho. **Hipóteses continuam abertas:** material de um cartão secundário, conteúdo nativo, máscara/ripple, shadow/RenderNode, transição de compositor ou outra superfície.

O aspecto levemente transparente antes do blur é um defeito distinto e de prioridade inferior. O mesmo registro pode indicar se a região foi atribuída tardiamente, se o alpha/fade demorou a subir ou se `draw()` retornou cedo, mas não mede diretamente a apresentação em SurfaceFlinger.

## Versão necessária

Instale uma versão compilada **com este PR**. A instrumentação do PR #146, isoladamente, não contém os novos eventos de desenho.

## Controle

Uma única propriedade ativa **a timeline estrutural existente e os novos eventos**:

```sh
su -c 'setprop debug.oulg.stackprobe 1'
```

Deixe `debug.oulg.trace=0`: o trace geral percorre árvores e pode alterar o tempo da transição.

## Captura de 10 segundos (vídeo + log sincronizados)

```sh
su -c 'sh -s' <<'SH'
set -eu
STAMP=$(date +%Y%m%d_%H%M%S)
BASE="/sdcard/Download/OULG_REGION_$STAMP"
cleanup() {
  setprop debug.oulg.stackprobe 0
  [ -n "${PID:-}" ] && kill "$PID" 2>/dev/null || true
}
trap cleanup EXIT
setprop debug.oulg.stackprobe 1
logcat -v epoch -T 1 -b main,system \
  -s OULG:V OULG_TEST:V AndroidRuntime:E > "$BASE.log" &
PID=$!
sleep 1
log -t OULG_TEST BEGIN_REGION_CAPTURE
screenrecord --time-limit 10 --bit-rate 8000000 "$BASE.mp4"
log -t OULG_TEST END_REGION_CAPTURE
sleep 1
echo "VIDEO: $BASE.mp4"
echo "LOG:   $BASE.log"
SH
```

Para um controle negativo, repita com uma live update sozinha. Na coleta principal, use uma live update mais um segundo item e expanda o cartão desde o início; depois faça swipes lentos para expor tanto a cápsula quanto a entrada do blur.

## Eventos

`LIVE_STACK_TIMELINE_*` (já existiam): geometria e estado de `stack_pip_layout`, seus filhos, `action0`, `sec_action0`, foregrounds, máscaras, elevation, cores de sombra e filtros. O `burst` identifica uma janela de 0–520 ms.

`LIVE_MATERIAL_FRAME` (novos): usa o **mesmo burst** e mede `ms` desde seu começo. Registra:

- `BLUR_ASSIGNED` e `BLUR_RELEASED`: bridge criada/descartada pelo owner; não equivale a bitmap de blur disponível na tela.
- `GLASS_ENABLED` / `GLASS_DISABLED`: decisão de desenhar vidro ou fallback nativo.
- `REGION_FADE`: variação do alpha efetivo solicitado.
- `DRAW_OWN_REGION`, `DRAW_REGIONLESS`, `DRAW_NATIVE`, outros modos: caminho de `LiveGlassDrawable.draw()`; `DRAW_OWN_REGION` significa somente que a chamada `blur.draw(canvas)` retornou.
- `host`, `material`, `stack`, `stackChild`: identidade do host, drawable, pilha e índice do filho direto.
- `xy`/ `wh`/ `bounds`, `region`, `regionDrawable`, `regionAlpha`, `regionBounds`, `fade`, `nativeHostBg`, `glass`, `attached` e `hwCanvas`.

O evento tem limite de 160 linhas por janela e até 28 submissões de desenho por drawable; visitas de outras pilhas são descartadas. Valores fora da janela de 650 ms não são registrados. Os campos não incluem texto, pacote, contato ou conteúdo de mídia.

## Interpretação sem induzir causa

1. Alinhar o quadro **sem cápsula** e o primeiro **com cápsula** à timeline pelo timestamp do log. Verificar se houve nova região, novo host ou alteração da geometria no mesmo intervalo. Uma correlação não prova que o material desenhou esses pixels.
2. Alinhar a primeira imagem **sem desfoque** e a primeira **com desfoque** às transições `BLUR_ASSIGNED`, `REGION_FADE` e `DRAW_OWN_REGION`. Se ocorrerem antes da mudança visual, o atraso pode estar além da submissão em Java.
3. Verificar a ordem e sobreposição entre dois hosts (com `xy`, `wh`, `stackChild`), sem assumir que o último índice estrutural é sempre o cartão visualmente frontal durante a animação.
4. Se não houver alteração entre os quadros, avaliar display-list reutilizada, `RenderThread`, `SurfaceFlinger` e sombras, sem concluir que o estado Java não importa. Uma futura captura de frame timeline/Perfetto só seria proposta com uma pergunta verificável e sem alterar o compositor.

**Não declarar uma causa confirmada a partir deste PR.** O futuro laudo deve separar observação, hipótese, alternativa, resultado negativo e limites instrumentais, de modo que uma segunda instância possa interpretar o código e o log de forma independente.
