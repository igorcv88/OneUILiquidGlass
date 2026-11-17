# Neutralidade dos realces (`sf.hchlneutral`) e auditoria do Neutralize (R4, 17/11)

Estado: **implementado e medido no host; opt-in (padrão 0); validação física pendente.**

Legenda: (D) código ou teste; (N) simulação no host; (U) relato; (V) pendente no aparelho.

## Auditoria do controle antigo (`sf.hcneutral`)

(U) No aparelho, Neutralize a 15 % e a 100 %, com lift 0,42, ficou visualmente quase igual. O valor estava aplicado: o bloco de material existia porque `hcsat` = 1,03. A diferença entre configuração não aplicada e curva restritiva, com os achados:

1. **Dependência artificial (D).** `oulg_hu_material_on()` só emite o material com `hcsat ≠ 1`, `hcscrimmax > 0` ou `hclift > 0`; sozinho, `hcneutral` não é compilado. A Applicability já declarava isso, mas a dependência não tem razão óptica.
2. **Curva restritiva no ponto errado (N, `hu_neutrality_report.py`).** `n = N · smoothstep(0,55, 0,90, y) · (1 − smoothstep(0,15, 0,45, max − min))` age em pixels quase brancos de croma absoluta baixa:
   - Em volta de um relógio branco desfocado sobre azul, verde ou laranja, os quase-brancos contaminados mantêm **97,8–99,9 %** da croma de N = 0 mesmo com N = 1. É o que foi percebido como sem efeito.
   - Ao mesmo tempo, áreas pastel **uniformes** (sem contaminação) perdem croma: verde-menta para **9,8 %**, pêssego para 67 % e azul-claro para 83 % com N = 1.
3. **Interação com o lift (D).** O lift vem depois e comprime a croma dos canais altos (derivada de 1 − (1 − c)^1,42 em c = 0,9 é 0,54), neutralizando sozinho a faixa onde a curva antiga atua.

Por pixel, uma cor é indistinguível de outra: um branco tingido de verde e um verde-menta legítimo podem ser a mesma cor. O que os separa é espacial: a contaminação é a **mistura** de um branco com uma cor dentro do kernel, e o disco B do corpo já mede isso pelo desvio de luma `sd`. No halo do relógio, `sd` ≈ 0,33–0,36; no interior de um pastel, a mediana é 0,038 e o p95 0,108.

## O controle novo

`rgb ← mix(rgb, vec3(y), H · L · S · C)`, depois do material legado e antes do lift:
- **L**, realce: `smoothstep(0,25, 0,70, luma)`;
- **S**, saturação relativa baixa: `1 − smoothstep(0,35, 0,95, (max − min) / max)`;
- **C**, mistura: `smoothstep(0,10, 0,22, sd)`, com `sd` do disco B (`oulg_sd`, atribuído em `oulg_body_hu`).

Propriedades:
- A luma não muda antes do lift; o mix é para o cinza de mesma luma.
- **H é linear**; 0 não emite nada (texto idêntico byte a byte).
- Independe de saturação, lift e escurecimento: o material é emitido só por H > 0.
- Exige o desfoque adaptativo, porque `sd` vem dele. Sem o adaptativo nada é emitido, e a Applicability mostra o controle como inativo, com a razão.
- O lift não mudou: fórmula, nome e descrição.

Resultado (N; corpo K1 a 41,4 px, sat 1,03, lift 0,42). Croma relativa a H = 0; "brancos" são os pixels com luma > 0,5 em volta do relógio; "pastel" é o interior da área uniforme:

| Fundo | H | 0,15 | 0,50 | 0,85 | 1,00 |
|---|---|---:|---:|---:|---:|
| azul / azul-claro | brancos / pastel | 93,8 / 100,0 % | 79,0 / 99,8 % | 63,8 / 99,7 % | 57,1 / 99,7 % |
| verde / menta | brancos / pastel | 92,9 / 100,0 % | 76,7 / 100,0 % | 60,9 / 100,0 % | 54,3 / 100,0 % |
| laranja / pêssego | brancos / pastel | 95,4 / 100,0 % | 84,6 / 100,0 % | 73,7 / 100,0 % | 69,0 / 100,0 % |
| legado N, verde / menta | brancos / pastel | 99,7 / 85,7 % | 98,9 / 53,3 % | 98,1 / 22,5 % | 97,8 / 9,8 % |

- Variação de luma ≤ 0,009, causada só pelo lift aplicado depois.
- A resposta nos brancos é monótona e quase linear (−5 a −8 pontos por 0,15 de H), e o pastel uniforme fica intacto.

## Compatibilidade e interface

- **Chave nova.** `sf.hchlneutral` ("Neutralidade dos realces" / "Highlight neutrality"), padrão 0, faixa 0–1, na seção principal do pop-up Clear. Exige o desfoque adaptativo e é aplicada ao reiniciar o userspace.
- **Legado.** `sf.hcneutral` não muda de comportamento nem de valor salvo: o Gmail aprovado com 0,80/0,85 continua idêntico. Ele passa para a seção avançada como "Neutralização antiga (legado)" / "Legacy neutralization", com a descrição do que faz. Não há migração automática: o controle novo começa em 0 e só muda a aparência quando movido. Na interface principal fica um único controle.
- **Ordem no shader.** Legado (se > 0), depois o novo, depois o lift. Para não somar os dois, a recomendação é baixar o legado a 0 ao usar o novo.

## Testes

- `hl_neutral_check.py` (no `check.sh`):
  - 0, ausente, −1, 1,5 e NaN geram texto idêntico ao anterior.
  - Sem o adaptativo, nada é emitido.
  - Acima de 0 aparecem exatamente a global `oulg_sd`, o bloco antes do lift e `oulg_sd = sd` no corpo, nos kernels 0–3, com compilação e link no glslang.
- `hu_neutrality_report.py`: a tabela acima.
- Java: registro, faixas do validador, aplicabilidade, descrições EN/pt-BR e matrizes.

## Teste no aparelho

Base: o build com a correção de alpha (#207), para que o resíduo não contamine a leitura.
1. Com o Neutralize antigo a 0 e mantendo lift e saturação, use **Neutralidade dos realces** em 0 e depois em 0,6, aplicando cada vez, sobre o relógio da Home e o Gmail.
2. Esperado: os brancos do relógio menos tingidos e o azul e as áreas uniformes iguais.
3. Reversão: 0.

## Limites

- Cenas sintéticas. A tela não é linear e o P3/HDR não foi modelado.
- O efeito máximo (H = 1) é limitado pelos gates: a croma dos brancos cai a 54–69 %, não a 0. Isso é intencional para não acinzentar o halo de cor legítimo.
