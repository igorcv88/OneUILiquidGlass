# Fundo nítido residual sob o pop-up Clear (adendo R2, 17/11)

Estado: **causa localizada na composição, não no kernel; mecanismo exato ainda não atestado no aparelho.** Este PR entrega a medida, os controles positivos, a atestação de compilação e uma rota de comparação opt-in. Nenhum padrão muda.

Legenda: (D) código, log ou teste reproduzível; (U) relato do usuário; (N) simulação no host; (H) hipótese; (V) validação física pendente.

## Sintoma

(U) Sobre o preview do app, o relógio da Home e fundos com texto, o pop-up mostra uma imagem bem difusa e, ao mesmo tempo, vestígios relativamente definidos da estrutura original: parece névoa sobre o fundo, não o próprio fundo difuso. Aumentar `hublur` (Full lens blur) acentuou essa impressão.

## Medida física (captura pareada, sem dados pessoais no repositório)

As capturas de 16:57 do mesmo preview do app mostram o título "Test notification" na mesma posição: desobstruído em uma e atrás do pop-up na outra (registro por correlação 0,64, deslocamento 0/0). Com `hu_composite_report.py --pair` na região do título sem texto do pop-up por cima (84 × 40 px da captura; 2,03 px do aparelho por px da captura):

| Banda (σ, px do aparelho) | 0,7–1,5 | 1,5–3 | 3–6 | 6–12 | 12–24 |
|---|---:|---:|---:|---:|---:|
| Transferência medida (inclinação da regressão) | 1,0 % | 1,6 % | 1,9 % | 3,4 % | 9,2 % |
| Correlação com a referência | 0,50 | 0,85 | 0,92 | 0,75 | 0,82 |

(D) O resíduo é uma cópia **coerente** do texto de referência, não grão: correlação 0,85–0,92 nas bandas finas. A transferência é praticamente plana entre 1,5 e 6 px, cerca de 1,6–1,9 %. Nessa escala, o contraste do título (luma 0,08 → 1,0) deixa cerca de 1,7 % de luma, 4 a 5 níveis de 8 bits sobre um campo liso. Isso é visível em letras grandes. Limites: uma região de 40 px de altura não sustenta as bandas acima de 24 px, a tela não é linear e o redimensionamento da captura atenua a banda de 0,7–1,5 px.

## O que cada hipótese prevê (N, `hu_composite_report.py`)

Fundos sintéticos: texto de 18 px e de 40 px, texto branco sobre azul, relógio de 300 px, preview de notificação, rosto esquemático e página escura com realces de 2–16 px. Corpo = gêmeo do GLSL gerado. Entrada do compositor = ¼ da resolução + 1 texel. Piso = média das bandas de 1,5–6 px; a tabela traz a média dos sete fundos.

| Modelo | Piso (1,5–6 px) | Forma da transferência | Depende de `hublur`? |
|---|---:|---|---|
| Corpo K1 36/36 sozinho (referência aprovada) | 0,07 % | ≈0 abaixo da própria escala; cresce acima de 12 px | sim |
| Corpo K1 ×1,15 (R2, R = 41,4 px) | 0,05 % | idem, mais baixo | sim |
| H1: região com α = 0,98 | 2,07 % | **plana** nas bandas finas, mais o corpo nas grossas | **não** |
| H1: α = 0,95 / 0,90 / 0,80 | 5,1 / 10,1 / 20,1 % | plana | não |
| Crossfade do RenderEngine, r = 9,8 / 9 / 7 | 2,1 / 10,1 / 30,1 % | plana (mesma álgebra de H1, m = r/10) | não; depende do raio **nativo** |
| H3: região sem lente (só o blur nativo) | 27,6 % (17–37 %) | sobe fortemente com a escala (0,7 → 8 → 48 → 64 %) | não |
| H2: frequências do próprio kernel (A, B, mistura) | ≈0 | nenhuma cópia coerente fina; o grão não correlaciona | sim |

Leitura:
- **H2 descartada como origem dos vestígios finos (N).** O corpo, em qualquer kernel e raio do adendo, transmite menos de 0,2 % entre 1,5 e 6 px. A medida física é 10 a 25 vezes maior.
- **H3 descartada como explicação principal (N/D).** Uma rota sem lente teria transferência crescente com a escala, 1–4 % em 1,5 px e mais de 30 % em 6 px. A medida é plana.
- **H1 ou crossfade explicam a forma e a amplitude (N).** Ambas somam uma parcela aditiva do fundo nítido, constante em frequência e independente de `hublur`. O perfil medido (1,6–1,9 % plano, depois a subida do corpo) corresponde a α_efetivo ≈ 0,98.
- **Discrepância do Full Lens Blur (N/H).** O corpo e a cópia nítida são independentes. Com R maior, o corpo apaga mais a estrutura média que mascarava a cópia. A cópia nítida não muda e passa a ser a única estrutura legível: a névoa fica mais lisa e os vestígios ficam relativamente mais evidentes. Não é inversão do controle: `hublur` multiplica o raio como documentado. Com H1 α = 0,98:

| Fundo | Banda 12–24 px, ×1,00 → ×1,15 | Piso fino, ×1,00 → ×1,15 | Razão cópia nítida / estrutura média |
|---|---:|---:|---:|
| preview | 7,2 % → 3,5 % | 2,06 % → 2,02 % | 0,28 → 0,57 |
| relógio | 10,2 % → 6,4 % | 2,18 % → 2,09 % | 0,21 → 0,33 |

MTF do corpo em 96 / 128 px: 0,069 / 0,285 (×1,00) → 0,005 / 0,173 (×1,15). Com α = 0,98, o MTF não cai abaixo de 0,020 em 8 px, em qualquer raio.

Outras métricas (N, K1 36/36): ESF 10–90 % de 80 px (92 px em ×1,15); a PSF concentra 2,7 % da energia a até 4 px do ponto, ou 4,5 % com α = 0,98; a cintilação de rolagem a 1 px/quadro é 1,6 % do contraste, igual com e sem H1 (a cópia nítida acompanha o conteúdo rigidamente). O ajuste de dois componentes da ESF não detecta 2 % (limite do método); o MTF e a transferência por banda detectam.

## Onde a parcela nítida pode entrar (D)

1. **Alpha da região Samsung (H1).** No trace de 15:06, em repouso, o HU usa `path=samsung tag=0`, a=1,00 e um `BackgroundBlurDrawable@255`. Porém, durante a entrada, o mesmo drawable reportou @255 com o alpha dos ancestrais em 0,53–0,89. O código já registrava que "a região Samsung não segue o alpha do drawable" (`CardExit.samsungCut`, `HeadsUpHooks.drawablePath`). Ou seja: o alpha efetivo da região Samsung no compositor não é controlado nem atestado pelo módulo. Esse defeito exato de transmissão foi o motivo de mover o lockscreen (`kgblurpath`) e a central (`shadeblurpath`) para o drawable; ver o comentário de `BackgroundBlurBridge.baseAlpha` (204 → backdrop nítido no corpo). O pop-up é a única superfície com lente que ficou na rota Samsung.
2. **Crossfade abaixo de 10 px (H).** O RenderEngine desenha uma região com raio menor que 10 px como `mix(original, borrado, raio/10)`, com duas imagens. `image_check.c` (D) mostra que a reescrita FillRRect **aceita** um programa com esse formato: ela roteia só a leitura borrada (`uTextureSampler_0_S1`) por `oulg_sample` e deixa a original intacta. O resultado é o corpo do OULG mais (1 − r/10) do fundo nítido, independente de `hublur`. No dump DZIF disponível, nenhum dos 36 programas reescritos tem leitura extra de imagem; o dump é anterior e não cobre essa rodada. Não se sabe como a Samsung converte `semradiuslens = 14` no raio do RenderEngine. O piso medido corresponderia a um raio efetivo de ≈9,8.
3. Descartados por código: `oulg_lens` não cai no B-spline com R ≥ 6 (`oulg_br` ≥ 36,8); `oulg_glow` lê o fundo só no ponto do contorno (projeção ao longo da normal, não letras); o material e o lift são pontuais.

## O que este PR muda

- **Atestação de compilação (sfhook v0.32.0 / 33).** `oulg_extra_images()` conta as leituras de imagem que a reescrita deixa intactas, exceto tabelas de dither lidas em `sk_FragCoord`. `oulg_sf.c` emite `REWRITE_EXTRA_IMAGE shader=… images=…` (WARN, no máximo 8 por processo) quando um programa reescrito tem alguma. O custo é zero por frame. O texto GLSL gerado não muda: `clear_shader_check` I1–I4 continuam byte a byte.
- **Rota de comparação opt-in `debug.oulg.hublurpath`** (Pop-up › Técnico; 0 = padrão atual). Com 1, um pop-up desbloqueado com a lente do compositor desenha seu blur por um `BackgroundBlurDrawable` dentro do vidro, como `kgblurpath`. O alpha da região passa a ser o do módulo: 255 em repouso, corte inteiro/zero no fade. Mesmo perfil de tag (0) e mesmo raio nativo (`semradiuslens`, não `kgblurradius`), para que a comparação isole só a rota. Vale para pop-ups criados depois da mudança, sem reinício. Se o drawable não puder ser criado, o fallback existente volta à rota Samsung.
- **Ferramentas:** `hu_composite_report.py` (modelos, controles positivos, MTF/ESF/PSF, cintilação temporal, `--pair` para capturas) e `image_check.c` (no `check.sh`).

Não foram usados scrim, opacidade artificial nem escurecimento. O kernel, os raios, o material e o tom não mudam.

## Teste no aparelho (uma sessão, um reinício de userspace)

Pré-requisitos: APK deste PR e bundle v0.32.0 aplicado (o helper reinicia o userspace uma vez). Valores de cor e blur como estão (Gmail aprovado).

1. Atestação: `su -c 'logcat -d -s OULG_SF | grep -E "OPTICS version|CLEAR_OPTICS|REWRITE_EXTRA_IMAGE" | tail -5'`.
   - `REWRITE_EXTRA_IMAGE` presente: o crossfade é real e a próxima etapa é capturar o dump desse programa para uma reescrita correta (não às cegas).
   - Ausente com o pop-up aberto: o crossfade fica excluído para esta sessão.
2. Cena fixa: a tela do app com o preview "Test notification" visível. Capture **sem** pop-up (referência) e depois com a notificação de teste, em repouso.
3. `hublurpath` 1 (o controle aplica ao vivo), nova notificação de teste, mesma captura. Volte para 0.
4. No PC: `python3 -I sfhook/tools/hu_composite_report.py --pair ref.png hu.png X0 Y0 X1 Y1 2.03`, numa região só com fundo atrás (sem texto do pop-up por cima). Compare o piso de 1,5–6 px nas duas rotas.

| Resultado | Leitura |
|---|---|
| Rota 1 com piso ≈0 e rota 0 ≈1,7 % | composição da região Samsung (H1). Próximo passo: avaliar a rota drawable como padrão (entrada, saída, transição para a central) |
| Ambas ≈1,7 % e `REWRITE_EXTRA_IMAGE` presente | crossfade; corrigir a reescrita com o dump real |
| Ambas ≈1,7 %, sem crossfade | outra camada/proprietário; trace da janela do pop-up (não coletar sem antes reler este documento) |

**Reversão:** `hublurpath` 0 (padrão). O bundle anterior (v0.31.0) só difere pelo log de compilação.

## Limites

- O piso físico vem de uma única região de uma única captura. A comparação na mesma cena, nas duas rotas, é o que decide (V).
- O gêmeo modela a entrada do compositor como ¼ + 1 texel, não o RenderEngine da Samsung, e a tela como linear.
- A rota drawable no pop-up existia apenas no modo LSPosed-only. Entrada, saída, P3/HDR e a transição para a central precisam de teste no aparelho antes de qualquer proposta de padrão.
