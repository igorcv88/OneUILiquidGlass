# Kernel do blur do Clear Glass nos pop-ups (`hckernel`)

Estado: **implementado e medido no host; não validado no aparelho.** O padrão continua `hckernel=0` (kernel aprovado até v0.30, texto GLSL idêntico byte a byte); os kernels 1–3 são opt-in para comparação A/B.

## Problema

Com `hcedge=hccore=30` (referência operacional), a estrutura de texto do fundo continua legível atrás do pop-up; com 36/36 ela diminui, mas os brancos se espalham sobre áreas coloridas e surgem estrias (relato e capturas de 17/11). Aumentar o raio não resolve o compromisso.

## Diagnóstico (medido, não presumido)

Ferramenta: `sfhook/tools/hu_kernel_report.py` (gêmeo numpy do GLSL gerado; `--glsl` renderiza o próprio GLSL em WebGL2). O gêmeo concorda com o GLSL em ~0,7 LSB médio.

1. **Valor esperado do disco A.** Resposta em frequência média (rotações) do disco A em R=30: |MTF| ≤ 0,015 para períodos ≤ 32 px, isto é, letras já não passam em média. Passam linhas e palavras: MTF 0,20 em 48 px e 0,44 em 64 px (pitch de linha da captura ≈ 65 px). O que se vê atrás do HU_30 é estrutura de linha/palavra, não de glifo.
2. **Disco B subamostrado.** B usa as mesmas 48 amostras de A numa área 6,25× maior (2,5 R). Na textura de entrada a ¼ da resolução, isso dá ~23 texels por amostra em R=30 (A: ~3,7). O erro estocástico de B cresce com o contraste local, justamente onde a mistura escolhe B. Medido: B triplica o grão do corpo (0,13 → 0,36) em relação ao disco A sozinho.
3. **Espalhamento dos brancos.** Um quadrado branco de 60 px sobre azul saturado: energia fora do quadrado 28 % só com A, 60 % com A+B em 30/30, 68 % em 36/36; alcance a 3 % do pico 22 → 40 → 46 px. O lift (`hclift 0.30`) acrescenta só 0,4–1 ponto: o espalhamento vem de B, não do lift.
4. **Mistura parcial no texto denso.** Com os limiares 0,06–0,22, o desvio de luma de B em blocos de texto fica parcialmente dentro da rampa, então o texto denso recebe uma mistura parcial de A (que passa linhas) e B.

## Kernel aninhado (1–3)

- **Amostragem aninhada:** B reaproveita as 48 amostras de A como seu disco interno e gasta suas próprias 48 só no anel [R, 2R]. Mesmo número de leituras de textura (96), densidade de B duplicada.
- **Pesos de quadratura:** cada amostra pesa Gaussiana × área representada (interna πR²/48, anel 3πR²/48), então B continua sendo uma Gaussiana (σ = R, corte em 2σ) qualquer que seja a densidade. `clear_check.c` verifica a soma das áreas e o segundo momento.
- **B em 2,0 R** (era 2,5 R) e **limiares menores** (1: 0,035–0,14; 2: 0,04–0,16; 3: 0,03–0,12).
- A estatística de mistura continua sendo o desvio de luma de B (escala larga, suave). Uma alternativa com o desvio de A foi medida e **rejeitada**: ela comuta entre A e B a poucos px do alcance de A e desenha um degrau (0,4 % → 17 % em 6 px a 26–32 px de um traço).

## Resultados (host, gêmeo do GLSL; `hu_kernel_report.py`)

| Configuração | Texto (5–60 px) | Grão | Auréola | Alcance 10 % / 3 % | Saturação perdida | Auréola com lift | ESF | Foto mantida 6–60 / 20–120 px |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| kernel 0, 30/30 | 3,14 | 0,36 | 60,3 % | 26 / 40 px | 7,0 % | 61,0 % | 84 | 70,5 % / 91,7 % |
| kernel 0, 36/36 | 2,18 | 0,34 | 68,2 % | 28 / 46 px | 8,1 % | 68,6 % | 100 | 64,5 % / 88,7 % |
| **kernel 1, 30/30** | **0,48** | **0,24** | **52,4 %** | **24 / 36 px** | **6,2 %** | **53,4 %** | **68** | **65,0 % / 89,3 %** |
| kernel 2, 30/30 | 1,16 | 0,22 | 51,9 % | 24 / 36 px | 6,1 % | 53,0 % | 68 | 67,4 % / 90,5 % |
| kernel 3, 30/30 | 0,27 | 0,23 | 52,7 % | 24 / 38 px | 6,4 % | 53,8 % | 68 | 61,8 % / 87,6 % |

Os mesmos números, calculados sobre o GLSL gerado renderizado em WebGL2 (`--glsl`), coincidem nas métricas do corpo (texto, grão, auréola, ESF) até a segunda casa.

Leitura: o kernel 1 em 30/30 retém a textura fotográfica como o kernel 0 em 36/36 (65 % / 89 % contra 64,5 % / 88,7 %), com 78 % menos estrutura de texto, menos auréola (52 % contra 68 %) e menos grão.

### Custos e limites conhecidos

- **Traço isolado:** onde ambos já escolhem B por inteiro, um traço isolado de 40 px mantém ~1,2× o resíduo do kernel 0 (B alcança 2,0 R, não 2,5 R). É o preço da auréola menor. Limite de regressão em `clear_check.c` (≤ 1,25).
- **Formas grandes de baixo contraste** são suavizadas um pouco mais que no kernel 0 em 30/30 (coluna Foto). O kernel 2 fica mais próximo do kernel 0; o kernel 3 suaviza mais.
- **Custo:** mesmo número de leituras de textura; ALU extra de um `exp` e um `dot` por amostra interna (constantes com o laço desenrolado). No SwiftShader (CPU, só relativo): +5–10 % por quadro. O custo na GPU do aparelho não foi medido.
- **Contornos:** no modelo determinístico (`clear_check.c`), os kernels 1–3 não acrescentam extremos aos do disco simples em traços, pares e degraus (R 24 e 30, com e sem o blur da região). O detector |yA − yB| continua como controle positivo.
- **Limites do host:** a entrada simulada é ¼ da resolução + blur de 1 texel, não o RenderEngine da Samsung. A aprovação depende do aparelho.

## Comparação A/B no aparelho

Pré-requisitos: APK deste PR instalado, lente dinâmica ativa, pop-up em Clear Glass, `Desfoque adaptativo` (`hcadapt`) em 1, `hcedge=hccore=30`.

1. Em **Pop-up → Vidro e desfoque → Método do desfoque adaptativo** (avançado), escolha `Gaussiano aninhado` (1). Aplique; a lente reinicia o userspace.
2. Confirme o que foi compilado: `su -c 'logcat -d -s OULG_SF | grep CLEAR_OPTICS | tail -1'` deve mostrar `hcmat=...,kernel:1` e `hc=...edge:30.00,core:30.00`.
3. Use a mesma tela de teste (texto denso escuro, texto denso claro, papel de parede colorido com relógio) e a notificação de teste do app; capture o pop-up parado.
4. Repita com `Original` (0) e, se quiser, `suave` (2) e `forte` (3). Não mude o raio entre as capturas.

Prefira o controle do app: na próxima aplicação, o app regrava os valores `sf.*` que ele controla, inclusive `hckernel`, então um `setprop` manual seria sobrescrito.

**Reversão:** `Original` (0) no mesmo controle, ou `hckernel=0`. O texto GLSL volta a ser o da v0.30 byte a byte.
