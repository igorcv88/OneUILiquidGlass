# Sonda de composição do pop-up Clear (R3, 17/11)

Estado: **instrumentação de diagnóstico implementada e validada no host; leitura no aparelho pendente.** Padrão desligado. Nenhuma mudança óptica, de raio, de kernel ou de cor.

Legenda: (D) código, dump ou teste; (N) simulação ou render no host; (U) relato; (H) hipótese; (V) pendente no aparelho.

## Dados da rodada R3 (18:01–18:10)

- (D) SurfaceFlinger no PID 27460, com `liboulg_sf.so` mapeada e saúde coerente.
  - Dump de 18:10 com 143 fontes e 38 fragmentos reescritos.
  - Os quatro rewrites de 18:06 compilaram (`compiled=1`).
  - Nenhum `REWRITE_EXTRA_IMAGE` aparece no trecho do log entregue.
- (D) `TUNING` (SystemUI 15472): `hublurpath=1`, `semradiuslens=14`, `semalpha=0`, `popuptone=true`. Os pop-ups saíram com `GLASS_APPLIED source=compositor`, ou seja, a rota drawable do #203 estava ativa.
- (U) O defeito persistiu com `hublurpath=1`.

## Validação independente da análise recebida

| Afirmação | Verificação | Resultado |
|---|---|---|
| Os parâmetros compilados são os da UI | Literais do GLSL reescrito no aparelho (programa 41): `oulg_br = mix(32.00, 36.00, …)`, `oulg_br *= … 1.150`, `s … * 1.090`, B aninhado (`accb`, limiares 0,035/0,14 = kernel 1), `n = 0.85 …`, saturação `mix(1.020, 1.0, w)`, lift `pow(…, vec3(1.200))`, scrim 0/0, menisco `min(2.0000 * band, 0.9174 * …)`, `hcbody`/`hcbody2` 0/0, expoente da rampa do HU 1,000 | **Confirmado (D).** O erro de aplicação dos controles fica descartado como causa primária. |
| 290 e 299 são um crossfade nativo | Os dois são passes do Kawase Dual Filter: oito leituras (centro e sete direções `STEP_k`), `in_blurOffset`, saída `vec4(Σ · weightedCrossFade, crossFade)`, sem tag nem reescrita | **Confirmado; não é causa por si (D).** Ver o argumento estrutural abaixo. |
| A multiplicação por `outputColor_S0.w` depois do blur pode deixar ~2 % do fundo | Nos 38 programas há variantes **com e sem** `* outputColor_S0.w` (o Skia gera uma ou outra conforme a opacidade conhecida da pintura), e variantes que **descartam** o alpha da textura (`vec4(tex.xyz, 1.0)`) ou o **preservam** | **Mecanismo possível (D); valor desconhecido.** Qual variante desenha o pop-up só se sabe em tempo de execução. |
| As 12 segundas texturas são dither | Todas as leituras de `uTextureSampler_1_S1` nos 12 reescritos são `mat3x2(umatrix…) * vec3(sk_FragCoord.xy, 1.0)` | **Confirmado (D).** Não há segunda imagem do fundo. |

### Argumento estrutural (D)

A textura que a reescrita amostra (`uTextureSampler_0_S1`) é a saída dos passes Kawase. Todo o seu RGB, inclusive os crossfades entre níveis, passa pelo corpo do OULG (σ ≈ R/2 ≈ 20 px, MTF < 10⁻³ abaixo de 32 px) antes de chegar à tela. Uma cópia **plana** de 1,6–1,9 % entre 1,5 e 6 px (medida em `HU_RESIDUAL.md`) não pode atravessar esse corpo. Ela só chega à tela por um termo que **não passa** pelo corpo:

1. alpha final < 1 no programa que desenha a região. Com source-over, a parcela `1 − a` do framebuffer de destino (o app, nítido) fica visível. Esse alpha pode vir:
   - da pintura (`outputColor_S0.w`, que inclui o alpha da região e o da camada);
   - da textura (o alpha que os passes Kawase deixam);
   - da matriz de cor do programa;
2. outro desenho sobre a região, de outro proprietário.

Os uniforms de crossfade só importam se reduzirem o **alpha** da textura (caso 1). Sem esse efeito, misturam níveis já borrados que o corpo borra de novo. Com `hublurpath=1`, o alpha do drawable é 255 (`HeadsUpBlurPathTest`) e o defeito persistiu (U). Restam o alpha da camada no compositor, o alpha da textura e o caso 2.

## A sonda (`debug.oulg.sf.debug = 5`)

Nos fragmentos com tag, a saída final do programa (`output_S1`, logo antes de `sk_FragColor`) é substituída por um código **opaco**, em ladrilhos de 8 px:

| Ladrilho | R | G | B |
|---|---|---|---|
| dados (par) | (1 − alpha final) × 12,75 | (1 − `outputColor_S0.w`) × 12,75 | (1 − alpha da textura borrada na amostra) × 12,75 |
| id (ímpar) | id >> 8 | id & 255 | 255 |

- 0 significa opaco, 1/255 equivale a 0,031 % de transmissão e 255 a 7,8 % ou mais.
- O id identifica o programa; `oulg_sf.c` registra `PROBE shader=… id=… paint=… texalpha=…` ao compilar.
- **A óptica compilada é a de produção.** A biblioteca é gerada como no modo 0, e `probe_check.c` verifica, em todos os programas aceitos do dump DZIF e do dump R3, que o texto de modo 5 é idêntico ao de produção fora do bloco da sonda.
- Fragmentos sem tag e os demais modos não mudam. O custo é zero fora do modo 5.

Controle positivo (N, `probe_render_check.mjs`): o próprio GLSL gerado, renderizado em WebGL2 com alpha de pintura e de textura conhecidos.

| Pintura | Alpha da textura | Decodificado (final / pintura / textura) | Esperado |
|---|---|---|---|
| 1,00 | 255 | 0,000 / 0,000 / 0,000 % | 0 / 0 / 0 % |
| 0,98 | 255 | 1,999 / 1,999 / 0,000 % | 2,000 / 2,000 / 0 % |
| 1,00 | 250 | 1,968 / 0,000 / 1,968 % | 1,961 / 0 / 1,961 % |
| 0,97 | 250 | 4,890 / 3,014 / 1,968 % | 4,902 / 3,000 / 1,961 % |
| 0,90 | 255 | 7,843 (saturado) / 7,843 / 0 % | teto de 7,843 % |

`check.sh` também compila os programas de modo 5 com glslang e os linka contra os vertex rewrites.

## Uso no aparelho (uma aplicação para ligar, uma para desligar)

1. Instale o APK. Em Avançado › Tinta de diagnóstico, escolha **Sonda de composição** (5) e aplique: o userspace reinicia uma vez.
2. Envie a notificação de teste sobre a mesma tela do app (o preview) e capture a tela parada, em resolução original. Não use uma cópia redimensionada pelo app de mensagens.
3. Leia os ids de programa no log: `su -c 'logcat -d -s OULG_SF | grep -E "PROBE|OPTICS version" | tail -40'`.
4. No PC: `python3 -I sfhook/tools/hu_probe_decode.py tela.png X0 Y0 X1 Y1`, com a região no corpo do pop-up, fora do texto.
5. Volte a sonda para **Desligada** (0) e aplique.

| Leitura | Conclusão | Próximo passo |
|---|---|---|
| final ≈ 1,7 %, pintura ≈ 1,7 % | alpha da pintura ou da camada (região × camada no SF) | identificar o proprietário pelo id/programa e pelo alpha da janela; corrigir na origem, não forçando alpha 1 no shader |
| final ≈ 1,7 %, textura ≈ 1,7 % | o Kawase entrega uma imagem com alpha < 1 | compor o resultado como opaco só na rota comprovada (a textura é o fundo inteiro) |
| final ≈ 1,7 %, pintura e textura 0 | matriz de cor ou outro termo do programa | ler o programa pelo id |
| todos 0, mas a cópia nítida continua (com a sonda desligada) | outro desenho sobre a região | sonda 1 (tinta) e trace de camadas; o vidro em si não transmite |
| ladrilhos borrados ou misturados com o fundo | algo desenha depois, sobre a região | idem |

Não forçar alpha 1 globalmente: isso quebraria fades, antialiasing e máscaras de cobertura. Uma correção só entra depois da leitura e só no termo comprovado.

## Limites

- Uma transformação de cor da tela pode deslocar os códigos em 1–2 níveis. Compare zero contra não zero; as magnitudes valem com cerca de ±0,1 %.
- O alpha de blending do framebuffer e os valores dos uniforms Kawase não são lidos diretamente. Os uniforms só são observáveis pelo alpha da textura. O estado de blend do RenderEngine para a região é source-over por padrão (alpha < 1 transmite o destino); um modo src-copy apareceria como ladrilhos opacos sem transmissão física, e a comparação com a sonda desligada desfaz a dúvida.
- A sonda mostra só o último programa com tag em cada pixel.

## Leitura no aparelho (R4, 19:08) e causa

Configuração da leitura: `sf.debug=5`, kernel 1, raios 30/32 px, `hublur` 1,15, `hurefract` 1,09, neutral 1,00, lift 0,42, saturação 1,03. SF PID 13825, saúde 1/1. O log `PROBE` veio vazio. As capturas originais estão em 1440 × 3120.

- (D) Os ladrilhos de id foram lidos como (156, 36, 245) em vez de (·, ·, 255): há uma transformação de cor de ganho ≈ 0,961 depois do código. O decodificador agora separa os ladrilhos pela geometria e corrige pelo ganho.
- (D) ROI 450–950 × 400–432:

  | Cena | Final | Pintura | Textura central |
  |---|---:|---:|---:|
  | preview | 1,907 % (corrigido 1,985 %) | 0 | 0 |
  | Home | 1,907 % (corrigido 1,985 %) | 0 | 0 |
  | Gmail | 0 | 0 | 0 |

- (D) Em toda a área do pop-up, o déficit final varia **continuamente** de 0 a um teto (R ≈ 62, ou seja 1,98 %). É o produto do peso adaptativo do disco B por um erro constante. O Gmail tem mais pixels em 0: com menos estrutura de fundo, a mistura fica mais no disco A.

**Causa (D/N).** Em `oulg_body_hu`, a soma do disco B era `mediump` (fp16 no aparelho) e o seu normalizador era `highp`:
- kernels 1–3: `mediump vec4 accb` ÷ `highp float wb`;
- kernel 0: `mediump vec4 acc` ÷ `highp float wsum`.

Com amostras opacas, `b.a = Σv(fp16) / Σv(fp32)`. Somando os pesos que o shader emite com fp16 truncado em direção a zero, `b.a` = 0,98035 nos kernels 1–3 (pesos somam 83,0; déficit de **1,965 %**, contra 1,985 % medido) e 0,98793 no kernel 0 (pesos somam 20,75; 1,21 %). A região é desenhada em source-over, então essa parcela do backdrop nítido aparece na tela. Isso explica o piso plano de ≈1,7–1,9 % medido em `HU_RESIDUAL.md`. Também explica por que o defeito independe de `hublur` e da rota (Samsung ou drawable), e por que o render WebGL (mediump = fp32 no SwiftShader) não o reproduzia. O disco A e os discos simples dividem mediump por mediump: para amostras opacas, as duas somas são a mesma sequência de operações e o alpha sai exatamente 1.

**Correção.** As somas do disco B passaram a ser `highp`, na mesma precisão do peso:
- kernels 1–3: `highp vec4 accb`;
- kernel 0: `highp vec4 acc`.

Mesmas 96 leituras de textura, mesmos pesos e mesma mistura; o custo é ALU highp em 48 acumulações. O texto gerado muda só nessa declaração (diff verificado). Frosted, KG, os perfis 2/3 e o Clear desligado continuam idênticos byte a byte (I1, I2, I4); a golden I3 foi atualizada só por essa linha. `alpha_precision_check.py` (no `check.sh`) passa a exigir, para todo kernel, a mesma precisão entre soma e normalizador e alpha 1 ± 1e-3 com fp16 ao mais próximo e truncado, para qualquer peso A/B. As declarações antigas servem de controle positivo e falham com 1,21 % e 1,96 %.

**Validação no aparelho.** Este build mantém a sonda.
1. Aplique-o com `sf.debug=5` e capture as mesmas três cenas. Esperado: final 0 em todo o pop-up, exceto o antialiasing do contorno.
2. Volte a sonda para 0 e compare o preview com e sem pop-up usando `hu_composite_report.py --pair`. Esperado: piso de 1,5–6 px ≈ 0 em vez de 1,6–1,9 %.
3. Revise o Gmail (cor aprovada), o relógio e o menisco.

Reversão: o APK anterior (bundle v0.33.0).
