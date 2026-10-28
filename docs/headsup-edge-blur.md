# Desfoque contínuo do pop-up — biblioteca live v0.24

Escopo: somente o perfil 0 (heads-up/pop-up). Lockscreen (perfil 1), central (perfil 2), refração, iluminação, contorno e layout não mudam. Novo parâmetro: `debug.oulg.sf.edgeblur` (px, 0–48, padrão **0**). Com 0 o shader é o v0.7 bit a bit; o padrão só deve mudar depois da validação no aparelho.

## Causa

Na curva v0.7 o raio do disco adicional é `core * smoothstep(0.25 * bevel, ramp * bevel, depth)`:

- até ~13 px do contorno o raio é **zero**. Ali o pixel é a textura reduzida do Samsung, ampliada até 2,5x pela lente (texels bilineares, com B-spline só abaixo de 6 px de raio): o "fundo reconhecível, de baixa resolução";
- logo depois o raio sobe até `core` em `0.75 * bevel` (~34 px num canto de 108 px). Com `core=90, ramp=1` a subida chega a 3,98 px de raio por px de profundidade: a faixa nítida termina num degrau, seguido do miolo borrado.

`core` muda só o patamar e `ramp` só a posição do fim da subida; nenhum dos dois remove a faixa de raio zero. Por isso a troca de valores não resolvia.

## Mudança

Com `edgeblur > 0`, o raio é uma única curva quintica a partir do contorno:

```
raio(depth) = mix(edgeblur, core, smootherstep(0, ramp * bevel, depth))
```

- `edgeblur` é o raio mínimo, já no contorno; `ramp` (em biséis) é a largura da transição; `core` é o raio alvo do miolo. Os três são independentes.
- Valor, primeira e segunda derivadas contínuas nas duas pontas (C2): sem faixa marcada nem platô súbito. Verificado em `field_check.c` (monotonia, C2, `edgeblur=0` ≡ v0.7).
- Com `edgeblur >= 6` todo pixel do cartão usa só o disco Vogel/Gaussiano: o filtro não troca mais (B-spline → disco) dentro do cartão. Entre 0 e 6 px vale a mistura contínua anterior.
- O deslocamento refrativo é o mesmo; a borda continua com vidro, agora com um raio mínimo de desfoque sobre a amostra refratada.

Raio do disco adicional, canto de 108 px (bisel 45,2 px):

| Profundidade | v0.7 core 90 ramp 1 | edge 12, core 90, ramp 1 | edge 12, core 64, ramp 2 |
| --- | ---: | ---: | ---: |
| 0 px | 0 | 12.0 | 12.0 |
| 8 px | 0 | 15.3 | 12.3 |
| 12 px | 0.1 | 21.4 | 13.0 |
| 16 px | 4.7 | 30.8 | 14.2 |
| 20 px | 14.8 | 42.7 | 15.9 |
| 30 px | 52.0 | 73.3 | 22.8 |
| 45 px | 90 | 90 | 37.8 |
| 60 px | 90 | 90 | 52.9 |
| 90 px | 90 | 90 | 64 |

## Medição no host (SwiftShader, `render_check.mjs`)

`fillDepth` mede o detalhe do fundo de 3–13 px (RMS(box1 − box6)) por faixas de 4 px a partir de 8 px do contorno, só nos trechos retos. `maxStep` é o maior salto entre faixas vizinhas relativo à primeira faixa: um degrau borda/miolo aparece como um salto grande.

| Variante | detalhe 8–24 px | maxStep | estrutura miolo | grão | ms |
| --- | --- | ---: | ---: | ---: | ---: |
| aparelho (core 90, ramp 1, taps 48, edge 0) | 7.83, 8.56, 3.85, 2.89 | 0.602 | 0.995 | 1.017 | 61.6 |
| + edge 8 | 2.86, 1.97, 1.50, 1.10 | 0.311 | 0.995 | 1.017 | 65.1 |
| + edge 12 | 2.01, 1.53, 1.24, 1.01 | 0.239 | 0.995 | 1.017 | 73.3 |
| edge 12, core 56, ramp 2 | 2.78, 2.39, 1.77, 1.20 | 0.223 | 2.126 | 0.544 | 76.3 |
| edge 16, core 64, ramp 2.5 | 1.82, 1.64, 1.33, 1.00 | 0.181 | 1.684 | 0.638 | 78.8 |

- O degrau reproduz o defeito do aparelho (8,56 → 3,85 entre faixas vizinhas) e desaparece com `edgeblur`; a queda passa a ser monotônica.
- Perfis 1 e 2 e o cartão sem tag: pixel a pixel idênticos à v0.23, com e sem `edgeblur`. Perfil 0 com `edgeblur=0`: idêntico (diferença máxima 0).
- `ms` é tempo de rasterizador em CPU, só relativo. O custo extra é a faixa de borda passar a usar o disco inteiro (`taps` amostras) em vez de 4 leituras B-spline; o miolo não muda. `taps` continua limitado a 4–48.
- Achado à parte: com `core=90` e 48 taps o espaçamento entre amostras é ~23 px (≈6 texels da textura reduzida) e o miolo mostra um padrão regular de amostragem (grão 1,02 contra 0,64 em core 64). Isso contribui para o aspecto de "mancha" do miolo e não é corrigido por `edgeblur`. Raio do miolo moderado (≤ 64 com 48 taps) evita o problema; não há como avaliar no host o quanto o `semradiuslens=22` do aparelho já o suaviza.
- Limite do host: a textura de entrada é simulada (¼ da resolução + blur de 1 px), não o RenderEngine Samsung. A aprovação depende do aparelho.

## Teste no aparelho

Reproduzir o caso reprovado e, em seguida, ligar só a borda (mesmo fundo do WhatsApp e do `83910.png`):

```sh
su -c 'setprop debug.oulg.sf.core 90; setprop debug.oulg.sf.ramp 1; setprop debug.oulg.sf.taps 48; setprop debug.oulg.sf.edgeblur 12'
```

Depois, a combinação com miolo moderado:

```sh
su -c 'setprop debug.oulg.sf.core 64; setprop debug.oulg.sf.ramp 2; setprop debug.oulg.sf.taps 48; setprop debug.oulg.sf.edgeblur 12'
```

Os valores só entram ao recompilar o shader (reinício do compositor pelo app). Confirmar no dump do shader a linha `if (oulg_pf < 0.5 && 12.00 > 0.0) oulg_br = mix(12.00, ...)`. Rollback: `setprop debug.oulg.sf.edgeblur 0`.

Critérios: sem separação visível entre a faixa da borda e o miolo; contorno e refração ainda legíveis como vidro; texto do pop-up legível. Se a borda ficar borrada demais, reduzir `edgeblur` (8); se o degrau ainda aparecer, aumentar `ramp` antes de aumentar `edgeblur`.
