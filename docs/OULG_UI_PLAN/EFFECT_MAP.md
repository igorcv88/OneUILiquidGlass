# Mapa de efeitos por intenção, superfície e material (R6)

Baseline `4f33114`. Fonte de layout: `CONTROL_MAPPING.json`. Inventário por chave: `CONTROL_INVENTORY.csv`. Nenhuma fórmula óptica muda; as transformações abaixo são **somente de apresentação** (o valor salvo é o mesmo).

Classificação de pares: (a) alias verdadeiro, (b) exclusividade de backend, (c) escala composta legítima, (d) ortogonal, (e) conflito/supressão, (f) morto.

## 1. Rotas

| Rota | Derivação (confirmada, sem drafts) | Quem desenha |
|---|---|---|
| STANDARD | `Mode.STANDARD` (`sfrefract=0, liveblur=1, backdrop=auto`) | Módulo (BackgroundBlurDrawable + GlassDrawable) |
| LENS_FROSTED | `DYNAMIC_LENS` e seletor da superfície = 0 | Compositor óptica 11 + blur nativo Samsung por região |
| LENS_CLEAR | `DYNAMIC_LENS` e seletor = 1 | Compositor Clear (óptica 12) + blur nativo |
| LENS_MIXED | `DYNAMIC_LENS` e seletor = 2 ou HU≠KG em GlassMaterial.of | Ambos compilados; união das listas |
| CAPTURE | `backdrop ∈ {capture,hybrid,grid}` ou `liveblur=0` com backdrop≠off | Captura experimental |
| PLAIN_BLUR | `liveblur=0` e `backdrop=off` | Somente blur Samsung |

Central (shade) não tem material: sob a lente usa a lista F. Live Updates, Now Bar e toasts **não têm escopo independente**: Live card usa o perfil/raio do pop-up (`useLensRadius`), Now Bar o perfil 1; são ligados/desligados na Home (flags OPT_IN) e herdam os ajustes de pop-up/bloqueio. A UI deve dizer isso (copy `live_inherits`), não criar sliders próprios.

## 2. Desfoque

| Intenção | Rota | Chave(s) | Relação | Fórmula efetiva (cadeia real) |
|---|---|---|---|---|
| **Desfoque** (principal) | S HU/KG | `liveradius`; KG `kgblurradius` (-1 → liveradius) | (b) com `sf.hublur` | raio px da região BackgroundBlurDrawable |
| | S Central | `liveshaderadius` | (b) | idem por cartão |
| | F/C HU | `sf.hublur` (×) | (c) pai de borda/centro | `r(x) = curve(edge, core, blurexp; x) × hublur` (refract.h `oulg_br *= p.blur`, Clear e Fosco); o blur nativo da região (`semradiuslens`) atua antes e não é escalado |
| | F/C KG | `sf.kgblur` (×) | (c) | idem perfil 1 |
| | F Central | `semradius` | (b) | blur Samsung dos cartões sem lente |
| Desfoque do sistema (detalhe) | F/C | `semradiuslens` (-1 → semradius) | (d) em série | região nativa sob o vidro |
| Desfoque da borda | C / F | `sf.hcedge` / `sf.edgeblur` (KG `kcedge`/`kgedgeblur`) | (b) entre si; (c) filho de hublur | nominal: `edge × hublur` |
| Desfoque do centro | C / F | `sf.hccore` / `sf.core` (KG `kccore`/`kgcore`) | (b); (c) | nominal: `core × hublur` |
| Transição do desfoque | C | `hcblurexp`, `kcblurexp` | (d) | expoente da curva borda→centro |
| Desfoque adaptativo | C HU | `hcadapt` (pai de `hchlneutral` e da regra de `hubloom`) | (d) | kernel adaptativo; método `hckernel` no Técnico |
| Desfoque do fundo da central | todas | `panelblurcap` | (d) | limite do raio do painel; -1 nativo |

Decisão D-BLUR: o principal "Desfoque" com lente é `hublur`/`kgblur`, exibido em % (1 → 100 %). Ele preserva a proporção borda/centro exatamente (é o multiplicador que o shader já aplica). Os detalhes mostram, além do valor salvo, a linha "Efetivo nominal: 24 px × 115 % = 27,6 px" (copy `blur_effective_nominal`). Não se afirma variância total porque o blur nativo e o kernel adaptativo também participam (texto `blur_chain_note`). Editar borda/centro não altera o pai; resetar o pai volta a 100 % e não toca filhos; "Restaurar grupo" reseta pai e filhos numa transação (`AppModel.resetGroup`, um undo).

Exemplo: borda 30 px, centro 32 px, hublur 1,15 → nominais 34,5 px e 36,8 px (como no handoff), acrescidos do blur nativo 14 px da região e do kernel adaptativo quando ligado; a UI mostra os dois nominais e a nota da cadeia.

## 3. Refração e forma

| Intenção | Rota | Chave | Relação | Fórmula |
|---|---|---|---|---|
| **Refração** (principal) | F/C | `sf.hurefract` / `sf.kgrefract` (×) | (c) pai | `shift = field × refract` (Clear: `oulg_clearN() × refract`; também limita `acap`/menisco cap) |
| Refração da borda | F/C HU | `sflens` | (d) | força enviada na tag (Java, `SemBlurBridge.strength`), não o shader; principal em F, detalhe em C |
| | F/C KG | `kglens` (-1 → sflens; ativo só com `kgoptics=1`) | (d) | idem perfil 1 |
| Menisco (principal C) | C | `hcfold`, `kcfold` | (b) com `hcamp` | campo menisco; >0 desativa `hcamp` (prova §5 do contrato) |
| Largura da borda curva | C / F | `hcband`,`kcband` / `kgband` | (b) | |
| Curvatura sem menisco | C | `hcamp`,`kcamp` | (e) suprimida por fold>0 | `min(amp, amax)` |
| Ampliação / abaulamento | C | `hcbody`, `hcbody2` | (d) com limite conjunto | `amax = ((1-M)/rho - body - 3·body2)/1.5`; o shader limita a amplitude, não rejeita valores: não há região infactível global a impor no slider |
| Refração difusa do miolo | F | `hubodylens`, `kgbodylens` | (b) com hcbody | |
| Faixa de lente capturada | CAPTURE/S+livelens | `lens, refract, ior, profile, disp` | (b) | rota experimental → Técnico |

D-REFR: "Refração" principal = multiplicador global; "Refração da borda" = força da tag. Nomes diferentes, ajuda diferente (copy `pc_refraction_help`, `pc_edge_refraction_help`). Não são duplicatas: tag e shader são estágios distintos.

## 4. Luz e contorno

| Intenção | Rota | Chave | Relação |
|---|---|---|---|
| Contorno (principal S) | S | `liverim`, `kgrim` | (b) com `sf.kgline` |
| Luz da linha externa | todas | `popuplinegain`, `kglinegain`, `shadelinegain` | (d) |
| Reflexo (principal S) | S | `liveglint`, `kgglint`, `shadeglint` | (b) |
| Luz pela borda (compositor) | F/C | `sf.hulight`, `sf.kglight`, `sf.shadelight` | (b) com `liveoptics` |
| Intensidade/brilho no topo | S | `liveoptics`, `livesheen` | (b) |
| Contorno/reflexo/especular/direção/alcance KG | F/C | `sf.kgline, kgrim, kgspec, kgglow, kgtilt, kgdepth` | (d) |
| Contorno/sombra no vidro claro | todas | `lightoutline`, `lightedge` | (d) |

## 5. Cor e realces

| Intenção | Chave | Relação | Observação |
|---|---|---|---|
| **Clareamento** (principal C) | `sf.hclift`, `sf.kclift` | (d) | Nome, fórmula e comportamento preservados; acima dos realces; aviso de contraste ≥1 mantido |
| **Saturação** (principal C) | `sf.hcsat`, `sf.kcsat`; F KG `sf.kgsat` | (b) | |
| Escurecimento claro/escuro | `hcscrim`, `hcscrimmax` | (d) | |
| Neutralidade dos realces | `sf.hchlneutral` | (d) | Nova; precisa de `hcadapt>0`; 0 = identidade |
| Contenção de brilho | `sf.hubloom` | (d) | Aumentar = mais contenção (supressão de amostras claras). **Não** é dispersão nem quantidade de realce: a relação com "quantidade de realce" não foi provada monotônica, então não há inversão. Inativo em C K1–3 com adapt>0. |
| Neutralidade clássica dos brancos | `sf.hcneutral` | (d) legado ativo | Default 0,75; age somente quando o bloco de material está ativo (`hcsat≠1` ou `scrimmax>0` ou `hclift>0`). Como Clareamento>0 ativa o bloco, a chave tem efeito real hoje: fica nos detalhes, abaixo de H, com rótulo "clássica" e ajuda que a diferencia; não é convertida em H. Ativar H **não** acorda o legado (refract.h:1207 usa `legacy_material_on`, independente de `hlneutral`). |
| Compressão de realces (KG C) | `sf.kcknee` | (d) | |
| Dispersão dos realces | — | — | **Não existe**; nenhum slider (ver `HIGHLIGHT_DIFFUSION_FEASIBILITY.md`). `disp` é dispersão cromática RGB da captura, no Técnico. |
| Véu/nível/escurecimento/tom frio KG C | `kcveil→kclevel`, `kcdim`, `kccool` | pai/filho | |
| Fosco KG | `kgsat, kgcool, kgtone, kgflat→kglevel, kgcoredim` | (b) com C | |
| Cor do vidro claro | `lighttone` | (d) | depende de `textswap` |

## 6. Transparência e contraste

`lightglass`/`shadelightglass`/`shadedarkglass` (dependem de `textswap`), `livedarkfill/kgdarkfill`, `livedarkalpha/kgdarkalpha`, `veilmax`, `popuptone→popuplock`, `stylelo/stylehi` (par com histerese; manter `stylelo < stylehi` validado no editor: se o usuário arrastar `stylelo ≥ stylehi`, o editor recusa com `error_style_order` — limite dependente, validado na UI e no `AppModel.saveControl`), `shadecap/shadelift`, `shadetone` (pai de paneldim, orphandraw, shadelocal, headertone, shadescaleveil, sf.shadecap/lift), `paneldim`, `panelcolor`, `panelnativegray`, `mediaart`.

## 7. Comportamento (por superfície, recolhido)

`popuplock`, `hufadecut` (#205 aprovado; -1 herda), `headergap`, `kgwinblur`, `shadeblur`, `shadelocal`, `shadescaleveil`, `orphandraw`, `shadefadecut`, `headertone`. São opções de funcionamento, não intensidade estética.

## 8. Técnico e diagnóstico

42 chaves (`CONTROL_MAPPING.technical.groups`) + 5 somente leitura + 6 legadas sem UI + 2 seletores de material. Inclui `sf.hckernel` (algoritmo), `taps/ramp`, caminhos de blur, curva/forma Samsung, captura, perfis ópticos, comparações A/B, `perf/trace/edgepremul/sf.debug`. Nenhuma pode ser favorita.

## 9. Mortos e legados

`qs.darkfill, qs.darkcolor, qs.idle, qs.active, qs.track, qs.lightstyle`: lidos e descartados (`QsStyle.java:59-61`). Permanecem no registro para round-trip de perfis; saem de toda lista, inclusive Todos os parâmetros comuns; aparecem só em Técnico › "Legado sem efeito" (somente leitura).

## 10. Contagens

208 chaves: MAIN 24, DETAIL 77, BEHAVIOR 10, ICONS 13, QS_MAIN 7, QS_DETAIL 22, TECHNICAL 42, DIAGNOSTIC 5, LEGACY 6, MATERIAL 2 (gerado por `tools/build_inventory.py`; MAIN conta a chave uma vez mesmo que apareça em várias rotas).
