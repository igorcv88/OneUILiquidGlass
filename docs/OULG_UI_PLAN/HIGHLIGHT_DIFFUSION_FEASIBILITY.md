# Dispersão dos realces (Highlight diffusion) — viabilidade (R6)

Status: **NO-GO para este PR**. Pendência preservada. Nenhum slider, nenhuma chave, nenhum código nativo.

## 1. Objetivo (do baseline §4)

Reduzir o pico e ampliar a área de influência de brancos/realces vistos através do vidro Clear do pop-up, com cromaticidade vizinha reduzida onde a luz espalha; mais intensa em vizinhanças claras/coloridas, menor em escuras; mistura linear; 0 = máxima contenção possível sem perder a supressão de detalhes. É distinto de `disp` (dispersão cromática RGB da captura), de `hchlneutral` (neutralidade cromática por desvio de luma) e de `hubloom` (pesos de amostras claras no disco).

## 2. Por que não há GO

1. A tentativa anterior aumentou o resíduo textual 5× com D=0 e foi rejeitada; a raiz era reintroduzir informação de alta frequência (amostra central nítida/raw). Qualquer espalhamento adicional que use amostras fora do kernel aprovado de 96 taps exige nova passagem ou nova alocação, o que o baseline proíbe sem prova.
2. Dentro do orçamento de 96 taps, o espalhamento só pode redistribuir pesos das amostras já lidas pelo kernel adaptativo (como `hubloom` faz no disco 0). Isso **reduz** o pico mas não **aumenta** a área de influência além do raio já amostrado: o requisito "ampliar a influência" não é atendível sem aumentar raio (proibido como solução bruta) ou nova passagem.
3. Não houve nesta sessão execução de GPU/render para medir PSF/ESF/MTF; afirmar viabilidade seria inventar resultado.

## 3. Protocolo para uma investigação futura (issue separada, Opus/oulg-optics)

- Harness: `sfhook/tools/hu_kernel_render.mjs` + `hu_kernel_report.py` + `hl_neutral_check.py` (já existentes) com o dump sintético; adicionar alvo de texto (resíduo de contraste), degrau (ESF), ponto branco (PSF), fundo plano e fundo colorido.
- Métricas e aceitação: resíduo de texto ≤ baseline aprovado (#207) para D=0 e D=1; pico do ponto branco reduzido ≥ 20 % em D=1; largura a meia altura +X % (X a definir com o usuário); energia (soma de luma) conservada ±1 %; chroma vizinho reduzido monotônico em D; fundo plano idêntico (|Δ| ≤ 1/255); sem instabilidade temporal em 3 posições sub-pixel; custo ≤ +0 taps, ≤ +5 % ALU estimada.
- Gate: identidade byte a byte do GLSL com D=0 vs. build sem a função (opt-in, default equivalente); teste highp; uma única aplicação de lente agrupada com outra mudança nativa autorizada.
- Saída: GO com algoritmo e tarefa detalhada, ou NO-GO definitivo registrado.

## 4. O que este PR faz

Nada no nativo. A copy `pc_highlight_neutrality_help` explica que neutralidade não espalha a luz. O item fica em `DECISIONS_AND_OPEN_QUESTIONS.md` como pendência P-DIFF, para issue nova após o merge, com este protocolo.
