# Gradiente da lockscreen — biblioteca live v0.22

O perfil 1 passa do vidro ao miolo usando duas curvas quinticas `smootherstep`, calculadas com a distância ao contorno arredondado. Não há nova captura, camada ou textura.

- Os primeiros 4 px mantêm o filtro e a iluminação anteriores. O contorno, sua largura e seus ganhos não mudam.
- Depois do contorno, o blur sobe suavemente até 8 px em `max(8 px, 0.25 * bevel)`.
- Desse ponto até `kgramp * bevel`, o raio sobe de 8 px até `min(kgcore, kgcorecap)`. A curva chega ao miolo com derivadas primeira e segunda nulas, sem encontrar o antigo teto por um clamp abrupto. Para raios menores que 8 px, a etapa inicial também respeita o teto; a rampa tem largura mínima de 1 px.
- O mesmo peso da segunda curva controla a tonalização e atenua a reflexão e o reflexo especular internos. A linha externa continua independente e inalterada. A saturação, a cor fria e os demais ganhos mantêm seus valores.
- O deslocamento refrativo e `kglens` continuam iguais. A refração já desaparece suavemente no fim do bisel.
- O teto padrão do miolo cai de 62 para 52 px (16,1%). `kgcore` continua em 80 para permitir a comparação com a curva anterior.

Exemplo aproximado para canto de 108 px, bisel de 45 px, rampa 1.5 e configurações padrão. São raios do disco adicional, não o sigma de um Gaussian blur nem o blur total do compositor:

| Distância do contorno | Referência anterior | Gradiente |
| --- | ---: | ---: |
| 4 px | 0 | 0 |
| 8 px | 0 | 4.8 |
| 12 px | 0.04 | 8.0 |
| 20 px | 5.2 | 9.3 |
| 30 px | 20.6 | 17.2 |
| 45 px | 51.7 | 37.9 |
| 55 px | 62 | 48.6 |
| 68 px | 62 | 52 |

## Comparação no aparelho

Novo perfil (padrões, salvo overrides já existentes):

```sh
su -c 'setprop debug.oulg.sf.kggradient 1; setprop debug.oulg.sf.kgcorecap 52'
```

Referência exata v0.21, mantendo os demais parâmetros nos mesmos valores:

```sh
su -c 'setprop debug.oulg.sf.kggradient 0; setprop debug.oulg.sf.kgcorecap 62'
```

`kggradient` aceita 0–1; valores intermediários misturam as curvas. O controle fica no grupo avançado do compositor da lockscreen. As duas propriedades são lidas ao compilar o shader: um `setprop` sozinho não atualiza um programa já compilado. Instale o bundle novo pelo fluxo SurfaceFlinger do app e aplique cada comparação após recompilar/reiniciar pelo fluxo existente. Overrides de `kgcore`, `kgramp` ou `kgcorecap` continuam prevalecendo. O handshake de óptica continua em 9 porque não houve mudança no contrato com SystemUI.

## Verificação

- Harness C compila com `-Wall -Wextra -Werror`; mantém 1 vertex e 5 fragment rewrites nos shaders sintéticos.
- Verificador de campo: 96.695.474 pontos, zero falhas de monotonicidade, limites, geometria, continuidade da refração e Jacobiano.
- Compilação dos shaders 2D e renderização real em OpenGL ES 3.2/Mesa: pop-up e central idênticos à base; faixa externa de 0–4 px da lockscreen idêntica; `kggradient=0,kgcorecap=62` reproduz a base em todos os perfis. FillRRect e clip diferem no máximo 1 nível de canal de 8 bits na área interna do teste.
- Shaders também compilados com blur desligado, rampa mínima com mistura parcial, e valores não finitos substituídos pelos padrões.
- Helper live: todos os checks passaram. Registro de controles regenerado a partir do catálogo, agora com 99 propriedades.

O disco da lockscreen continua com 48 amostras. Como o blur começa mais perto do contorno, mais pixels podem executar o disco completo: contagem de amostras por pixel não comprova custo total igual. A aparência final e o tempo de GPU no S25 Ultra ainda precisam ser avaliados no aparelho; o teste sintético não reproduz exatamente o filtro Samsung nem o wallpaper da imagem enviada.
