# Diagnóstico da cápsula tardia — Live Updates DZIF

**Escopo:** instrumentação experimental do carrossel expandido (`CardStackView`), sem alterações de desenho. A bolha aparece **após** o primeiro desenho do cartão, somente quando existem dois ou mais itens (live + live ou live + mídia). O cinza transitório já é uma questão distinta e não é alvo deste diagnóstico.

## Ativação no aparelho (sem ativar o trace geral)

```sh
su -c 'setprop debug.oulg.stackprobe 1'
su -c 'getprop debug.oulg.stackprobe'
```

O valor deve ser `1`. O diagnóstico só funciona depois de instalar o APK compilado com esta alteração. `debug.oulg.trace` pode ficar em `0` (recomendado), pois a instrumentação não usa os snapshots gerais que podem alterar o timing de SystemUI.

Com **duas live updates** ou **uma live update + mídia**, execute no Termux:

```sh
su -c 'sh -s' <<'SH'
set -eu
STAMP=$(date +%Y%m%d_%H%M%S)
BASE="/sdcard/Download/OULG_BUBBLE_$STAMP"
logcat -v epoch -T 1 -b main,system -s OULG:V OULG_TEST:V AndroidRuntime:E > "$BASE.log" &
PID=$!
sleep 1
log -t OULG_TEST BEGIN_BUBBLE_CAPTURE
screenrecord --time-limit 15 --bit-rate 6000000 "$BASE.mp4"
log -t OULG_TEST END_BUBBLE_CAPTURE
sleep 1
kill "$PID" 2>/dev/null || true
echo "Arquivos: $BASE.mp4 e $BASE.log"
SH
```

Durante a gravação: abra a primeira live update expandida, observe o aparecimento posterior da cápsula e faça pelo menos cinco swipes. O vídeo e o log devem ser da **mesma coleta**. Se possível, capture uma única live update como controle negativo em uma segunda coleta.

**Desativação:** `su -c 'setprop debug.oulg.stackprobe 0'` (o estado é consultado com cache de até 750 ms).

## Eventos

- `LIVE_STACK_TIMELINE_BEGIN`: início de uma janela de observação após a primeira exibição do vidro, `changeCardBg`, outro writer ou `updateItemBg`, com identificador numérico da janela.
- `LIVE_STACK_TIMELINE_TICK`: marca temporal planejada e real em milissegundos, número de filhos da pilha e `getTopViewIndex()`.
- `LIVE_STACK_TIMELINE_NODE`: somente mudanças desde a amostra anterior do mesmo objeto: identidade, caminho estrutural, id do recurso, posição, dimensões, alpha, `translationY/Z`, elevação, clipping, cores de sombra ambiental/spot e fundo.
- Os `RippleDrawable` incluem IDs de cada layer, flag `mask`, cor dos `GradientDrawable`, filtro, opacidade, alpha e outline. Os drawables dos botões/contêineres também aparecem se estiverem na árvore.
- `LIVE_STACK_TIMELINE_END_TICK`: contagem de objetos percorridos e de alterações publicadas.
- `LIVE_STACK_TIMELINE_DONE`: fim do intervalo de aproximadamente 520 ms.
- `LIVE_RENDER_DRAW` existente: mudanças de modo de desenho do material (`OWN_REGION`, `NATIVE` etc.) para correlacionar com a nova telemetria.

As amostras são em **0, 16, 32, 48, 64, 96, 128, 176, 256, 352 e 480 ms**, com duração real informada em cada linha. Há limites de 4 cartões, 110 views, 48 linhas alteradas por amostra e cooldown de 620 ms para não instalar uma coleta por frame.

## Como interpretar

A comparação principal é o último frame **sem** cápsula e o primeiro frame **com** cápsula no vídeo:

1. Surgiu uma view/drawable novo ou mudou `RippleDrawable`/filtro/cor/alpha/máscara? Investigar seu writer/owner.
2. Permaneceram drawables e cores, mas mudou elevação, `translationZ` ou shadow ambient/spot? A alteração visual pode ser o RenderNode/sombra.
3. Nenhum atributo Java observado mudou, mas a cápsula apareceu? Considerar display list reaproveitada, animação do RenderThread ou camada fora da árvore inspeccionada; nesse caso a telemetria atual **não prova a origem**. O próximo diagnóstico teria de observar o desenho com outra técnica, sem instalar wrappers que afetem contorno ou toque.

O log não lê texto das notificações, mensagens, metadados de mídia, nomes de contato nem bitmaps. Mesmo assim, identidades/posição de views são diagnósticos do dispositivo; não deixe a propriedade ativada após a coleta.
