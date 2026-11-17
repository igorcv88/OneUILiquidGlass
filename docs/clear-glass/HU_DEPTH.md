# Profundidade do pop-up Clear na entrada e no corpo (adendo R2, 17/11)

Estado: **um defeito de associação da região na entrada, com correção opt-in; tuning do corpo documentado; nenhum padrão muda.**

Legenda: (D) código/log/teste, (N) simulação no host, (U) relato, (H) hipótese, (V) validação física pendente.

## Sintoma

(U) Desde o kernel gaussiano, o pop-up perdeu parte da profundidade, sobretudo **ao entrar**. A refração da borda externa continua boa. O pedido é recuperar com discrição a curvatura, o movimento e a profundidade do corpo, sem voltar a expor o fundo.

## 1. Entrada: a lente chega com o cartão já parado (D)

Trace de 15:06:34–35 (`ROW_FRAME`/`SEM_BLUR_APPLY`, mesmo cartão, rota Samsung):

| t (s) | translationY | alpha dos ancestrais | região |
|---|---:|---:|---|
| 34,990 | −86 | 0,00 | suspensa (`suspended=cut`) |
| 35,015 | −5 | 0,00 | suspensa |
| 35,048 | 106 | 0,05 | suspensa |
| 35,072 | 138 | 0,24 | suspensa |
| 35,098 | 150 | 0,53 | aplicada em 35,099 |
| 35,131 | 152 | 1,00 | inteira |

- O pop-up desliza 248 px em ~120 ms e, nesse tempo, sobe de alpha 0 a ~0,5. A região de blur, e com ela a lente, a refração e o menisco do compositor, só é aplicada quando o cartão está a 2 px do repouso.
- Causa no código: `HeadsUpHooks` aplica o corte de fade `shadefadecut` (0,5) a qualquer cartão com `barState == BAR_SHADE`. Esse estado inclui o pop-up sobre apps, e não só a central desbloqueada para a qual o corte foi criado (`architecture.md`, "partly transparent region turns whiter"). Na rota Samsung, a região é inteira ou nada (`CardExit.samsungCut`), então ela fica ausente durante quase todo o movimento.
- A tag não é perdida na entrada. Com a lente, o perfil 0 continua sendo enviado durante a escala (`scaled=true tag=0`); quem falta é a região.

**Correção (opt-in): `debug.oulg.hufadecut`** (Pop-up › Comportamento).
- −1 (padrão): segue `shadefadecut`; idêntico ao atual.
- 0,25 ou 0,1: a região, sempre inteira (nunca parcial; ver `HU_RESIDUAL.md`), aparece com o pop-up a 25 % ou 10 % de alpha. Com 0,1, no trace acima, isso seria em 35,056 (translationY 120): a lente acompanha os últimos ~30 px do deslize e a desaceleração.
- 0: região desde o primeiro quadro, possível "fantasma" de vidro enquanto o conteúdo ainda está invisível.

O mesmo corte vale para a saída. Os cartões da central continuam com `shadefadecut`. O pop-up só é consultado quando o opt-in está definido, então o caminho padrão não lê nada a mais por quadro. Aplica-se ao vivo.

## 2. Corpo: o tuning existente basta (D/N)

O campo do corpo Clear (`OULG_FS_CLEAR_FIELD_FMT`/`MENISCUS`) é `s(q) = −min(hs) · u · (λ0 + λ2 u²) · hurefract`, com `u = q/hs`. `hcbody` = λ0 (ampliação uniforme) e `hcbody2` = λ2 (domo cúbico), **ambos 0 por padrão**. Com 0/0, o corpo é uma janela plana: toda a profundidade vem do menisco e da luz da borda.

Jacobiano no cartão do aparelho (1340 × 382, `hurefract` 1,09; J < 1 = ampliação):

| `hcbody` / `hcbody2` | J mínimo x / y | Deslocamento no extremo | Ampliação no centro |
|---|---:|---:|---:|
| 0 / 0,02 | 0,981 / 0,935 | 4,2 px | 1,000 |
| 0 / 0,04 | 0,963 / 0,869 | 8,3 px | 1,000 |
| 0,02 / 0 | 0,994 / 0,978 | 4,2 px | 1,022 |
| 0,02 / 0,02 | 0,975 / 0,913 | 8,3 px | 1,022 |
| 0 / 0,05 (máx. da UI) | 0,953 / 0,837 | 10,4 px | 1,000 |

- J é positivo e limitado em todo o domínio da UI, sem singularidade.
- O campo do corpo não altera a dobra do menisco: ele se soma a ela, e a dobra só existe na faixa.

Visibilidade da refração do corpo sob o blur (N, `sfhook/tools/hu_depth_report.py`): RMS da diferença com e sem campo, relativa à estrutura de 6–120 px do fundo, mais a pista de movimento numa rolagem de 6 px.

| Kernel, R | `hcbody` / `hcbody2` | Foto | Texto | Pista de movimento (foto) |
|---|---|---:|---:|---:|
| K0 36 | 0 / 0,02 | 1,74 % | 0,84 % | 0,22 % |
| K0 36 | 0 / 0,04 | 3,48 % | 1,63 % | 0,41 % |
| K0 36 | 0,02 / 0 | 7,40 % | 2,96 % | 0,60 % |
| K0 36 | 0,02 / 0,02 | 8,99 % | 3,64 % | 0,74 % |
| K1 36 | 0 / 0,02 | 1,74 % | 0,56 % | 0,22 % |
| K1 36 | 0 / 0,04 | 3,48 % | 1,03 % | 0,41 % |
| K1 36 | 0,02 / 0 | 7,09 % | 1,44 % | 0,58 % |
| K1 36 | 0,02 / 0,02 | 8,70 % | 1,79 % | 0,71 % |
| K1 41,4 (×1,15) | 0 / 0,02 | 1,71 % | 0,55 % | 0,21 % |
| K1 41,4 (×1,15) | 0,02 / 0,02 | 8,36 % | 1,77 % | 0,69 % |

Leitura:
- **Visibilidade e kernel:** o kernel gaussiano preserva a refração do corpo sobre estrutura ampla (foto: 1,74 contra 1,74 %; 7,09 contra 7,40 %). Sobre texto, a visibilidade cai para cerca de metade (0,56 contra 0,84 %; 1,44 contra 2,96 %), porque o kernel apaga justamente a textura média que denunciava o deslocamento. Parte da "perda de profundidade" é, portanto, consequência direta do blur aprovado; recuperá-la devolvendo essa textura está vetado. O ×1,15 muda pouco (−2 a −5 %). Em repouso, `hcbody` 0,02 rende ~4× a visibilidade de `hcbody2` 0,02 (ampliação uniforme de 2,2 % contra domo de 4 px nas extremidades); a pista de movimento é ~0,6 % contra 0,2 %.
- **Sem patch de campo:** o campo não tem defeito. Os controles existentes (`hcbody2`, depois `hcbody`) cobrem o pedido dentro do domínio validado.
- **O que não fazer:** aumentar `hurefract` desloca também o menisco aprovado; `hubodylens` só atua no Frosted.

## Teste no aparelho

1. `hufadecut` 0,1 (ao vivo). Envie a notificação de teste sobre um fundo com estrutura ampla (wallpaper, vídeo parado) e grave a entrada. Esperado: a refração e o menisco acompanham o fim do deslize, sem um vidro vazio antes do conteúdo.
2. Se o corpo ainda parecer plano em repouso: `hcbody` 0,02 (reinício do userspace), o termo mais visível; se quiser só o domo nas extremidades, `hcbody2` 0,02–0,04. Não mude `hurefract`, os raios nem as cores.
3. Reversão: `hufadecut` −1 (padrão); `hcbody2` 0.

## Limites

- A sequência de entrada vem de um trace (15:06), e a duração depende da escala de animação.
- A aparência de uma região inteira sob um pop-up a 10 % de alpha ainda não foi vista no aparelho (V).
- A visibilidade é medida no gêmeo, não na GPU.
