# Deslocamento aparente na borda do heads-up (laudo 17/11, seção 4)

Estado: **análise do campo concluída no host; nenhuma alteração de comportamento.** A decisão final depende do teste no aparelho descrito abaixo.

## Pergunta

Na captura `HU_30`, o balão azul atrás do pop-up parece começar mais abaixo do que deveria, perto da borda inferior. A questão é se isso é a óptica intencional do menisco ou um defeito de recorte, amostragem, mapeamento ou limites.

## O que o campo faz (refract.h, `OULG_FS_MENISCUS_FIELD_FMT`)

Na faixa de borda (`band = 0,42 r`), a amostra é deslocada para dentro em `d(x) = a (1 − √(1 − x²))`, com `x = 1 − depth/band` e `a = min(fold · band, cap · min(hs))`. A profundidade de origem `depth + d(depth)` tem um único mínimo, na dobra:

- **faixa externa nunca exibida:** `band (1 + f − √(1 + f²))`, com `f = a / band`. Fundo mais raso que isso não aparece em nenhum ponto da faixa;
- **borda espelhada:** de 0 a `band (1 − 1/√(1 + f²))`, o interior aparece invertido e comprimido;
- **interior repetido:** as profundidades de `band` a `a` aparecem duas vezes, na borda espelhada e na posição real;
- **contorno:** amostra o fundo a `a` px para dentro.

Verificado em `sfhook/tools/clear_check.c`:
- M3: posição da dobra e mínimo único.
- M5: largura da faixa oculta igual à fórmula, dentro de 0,02 px.
- M1/M4: nenhuma amostra sai do cartão, e o contorno fica a `a` px para dentro.

## Números (cartão de pop-up do aparelho, r = 108 px, banda 0,42)

| `hcfold` | Faixa externa oculta | Borda espelhada | Interior repetido | O contorno amostra |
|---:|---:|---:|---:|---:|
| 2,00 (aprovado) | 34,7 px | 25,1 px | 45,4–90,7 px | 90,7 px para dentro |
| 1,75 | 33,3 px | 22,9 px | 45,4–79,4 px | 79,4 px |
| 1,50 | 31,6 px | 20,2 px | 45,4–68,0 px | 68,0 px |
| 1,00 | 26,6 px | 13,3 px | — | 45,4 px |

## Conclusão

- O efeito da captura é compatível com a óptica: um objeto cuja borda está até ~35 px para dentro do limite inferior do cartão não aparece na faixa da borda, que mostra o interior espelhado (mais escuro, na captura). Ele surge só onde a origem volta a coincidir com a posição real.
- Reduzir `hcfold` quase não muda a faixa oculta (−3 px de 2,0 para 1,5): ela escala com a largura da banda, já no mínimo permitido (0,42). Reduzir a dobra só diminui o espelho e a profundidade amostrada no contorno, ou seja, enfraquece o menisco sem resolver a percepção.
- Não há evidência, no código, de recorte, de amostra fora do cartão nem de limite UV errado. Sem prova no aparelho, nenhuma mudança é feita.

## Teste no aparelho (para confirmar ou refutar)

1. Use um fundo com uma barra horizontal colorida e nítida (por exemplo, um balão de chat) e role devagar até ela cruzar a borda inferior do pop-up parado.
2. Com a captura parada, meça a distância entre a borda real da barra (sem vidro, mesmo quadro) e onde ela reaparece dentro do vidro. O esperado pela óptica é ~35 px de faixa oculta e a cópia espelhada a 45–91 px.
3. Repita com `hcfold` 1,5 (Pop-up → Bordas e reflexos → Menisco) só como ablação, sem mudar o blur. O esperado é uma faixa oculta quase igual (~32 px).
4. Opcional: o mapa de deslocamento do compositor (`debug 3`) mostra o deslocamento por pixel. Ele deve coincidir com a tabela.

Se a barra desaparecer numa faixa maior que a prevista, aparecer cortada por uma linha reta, ou se o comportamento mudar com a posição do cartão na tela, aí há defeito de recorte ou limites, e a correção fica restrita a essa rota.
