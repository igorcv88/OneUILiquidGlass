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

# Campo óptico contínuo — biblioteca live v0.23

Na captura do aparelho (v0.22, várias notificações sobre o rosto) a refração só aparecia nos ~12 px externos. Causa no shader: o deslocamento refrativo terminava em `bevel = 0.42 r` (~45 px num pill de 108 px), enquanto o blur seguia subindo até `1.5 bevel`. Onde a distorção é máxima (meio do bisel, ~22 px) o blur já tinha 10–17 px de raio, e no fim do bisel ~38 px: a refração existia, mas ficava escondida sob o desfoque.

**Ajuste padrão após validação no S25 Ultra:** `kgcorecap=36` (antes 52), com `kgcore=80`, `kgband=0.85`, `kgbodylens=0.05` e `kgblurexp=2` preservados. A mudança afeta apenas o limite do raio do disco adicional e sua progressão proporcional; os parâmetros de iluminação e o campo de refração não mudam. Capturas com o mesmo papel de parede mostraram menor mistura de cores no miolo sobre cabelo, mantendo a intensidade do delineado.

O perfil 1 agora usa uma única coordenada `u = depth / band`, `band = clamp(kgband · r, bevel, r)`, para refração e blur:

- **Transição (borda interna):** o mesmo perfil de laje `A · band · G(1 − u)`, agora ao longo da faixa inteira (padrão `0.85 r`, ~91 px num pill de 108 px). O pico de ampliação radial continua `1 − 1.5 A`; a faixa ficou mais larga, não mais forte.
- **Miolo:** domo parabólico elíptico fraco, na escala do cartão: `−kgbodylens · q · R / hs` (`q` = posição a partir do centro, `R = min(hs)`). O campo é linear, então a ampliação é constante, `1 / (1 − kgbodylens · R / hs)` em cada eixo. Num pill isso amplia quase só no eixo curto (vertical), sem emendas em nenhum ponto, inclusive no centro, onde a normal da borda inverte. Fisicamente, numa placa de faces paralelas o centro não distorce: a "refração no miolo" só existe como curvatura, e curvatura parabólica é ampliação uniforme. Uma primeira versão, com clamp no eixo medial do estádio, quebrava a continuidade do Jacobiano nas extremidades do eixo; o `field_check` agora testa essa continuidade.
- **Blur:** `min(kgcore, kgcorecap) · S(u)^kgblurexp` (`S` = smootherstep quíntica): ~0 no delineado, raio total no fim da transição, C2 ali. O peso do material (saturação, achatamento, escurecimento do miolo) segue o mesmo raio normalizado, então a borda fica mais transparente.
- **Iluminação:** Fresnel, contorno e banda especular mantêm a coordenada, a normal e o peso de atenuação da v0.22. A borda ajustada não muda com a lente; o que muda é o fundo refratado sob ela.
- **Limite de segurança:** a força da borda é limitada a `min(0.5, (0.75 − kgbodylens) / 1.5)`, de modo que a soma dos dois termos nunca amplia mais que 4x (o mesmo limite da v0.22).

Valores com os padrões (`kglens 0.40`, pill `r ≈ 107`), medidos a partir do delineado para dentro:

| Profundidade (px) | 0⁺ | 15 | 29 | 43 | 57 | 71 | 85 | ≥92 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Deslocamento (px) | 41.9 | 38.5 | 31.7 | 23.1 | 14.1 | 6.4 | 1.6 | ≤0.8 |
| Raio do blur (px) | 0 | 0.1 | 1.2 | 7.2 | 18.9 | 30.7 | 35.8 | 36 |

**Degrau no delineado.** `0⁺` é o limite logo dentro do cartão. O deslocamento é contínuo até ali, e o `field_check` verifica isso de 1e-6 a 1 px de profundidade, tanto num lado reto quanto na diagonal de um canto. O próprio delineado (`depth = 0`) fica fora do cartão (cobertura zero) e devolve só a lente do miolo (5.4 px). O degrau que importa é o de fora para dentro: o fundo fora do cartão não tem deslocamento, e logo dentro ele chega a 41.9 px (v0.22: 18 px). Esse degrau é a própria refração da borda; ele fica sob o contorno e a borda de cobertura. Numa renderização com texto de alto contraste cruzando o delineado (`render_check`), não aparece rasgo nem serrilhado. Mas o texto que cruza a borda não continua do lado de dentro, porque ali o vidro mostra o fundo de ~42 px para dentro. Esse degrau escala com `kglens` e com a largura da faixa.

Na v0.22 o raio já era 8 px aos 12 px de profundidade e 38 px aos 45 px; o deslocamento terminava em 45 px.

## Propriedades (lidas ao compilar o shader)

| Propriedade | Padrão | Faixa | Efeito |
| --- | ---: | --- | --- |
| `sf.kgband` | 0.85 | 0–1 | largura da transição / raio do canto; **0 restaura a v0.22 bit a bit**. A faixa nunca fica mais estreita que o bisel (`clamp(0.42 r, 16, 56)`): valores entre 0 e ~0.42 (menos para raios acima de 133 px) dão todos a mesma largura mínima; só ~0.42–1 ajusta de fato |
| `sf.kgbodylens` | 0.05 | 0–0.15 | ampliação uniforme do miolo (0 desliga) |
| `sf.kgblurexp` | 2 | 1–4 | quanto o blur demora a subir na transição. `S(u)` fica entre 0 e 1, então um expoente **maior dá menos blur** na transição (borda mais nítida) e um **menor dá mais blur** |

**Se o texto perder legibilidade** porque o fundo ficou nítido demais na transição: **diminua** `kgblurexp` (até 1), ou diminua `kgband` (até ~0.42) para o blur chegar ao máximo mais cedo. Aumentar `kgblurexp` piora a legibilidade.

**Cor do material.** O peso do material (saturação, achatamento da luminância, escurecimento do miolo) segue o novo raio de blur. Por isso a transição muda de cor e de luminância mesmo com `kgsat`, `kgcool`, `kgflat` e a iluminação inalterados. Na comparação no aparelho, mude só `kgband` entre as duas capturas.

Com `kgband > 0`, `kggradient` e `kgramp` deixam de afetar o perfil 1 (o blur passa a ser definido pela faixa). `kglens` continua controlando a força da refração da transição.

```sh
# v0.23 (padrões)
su -c 'setprop debug.oulg.sf.kgband 0.85; setprop debug.oulg.sf.kgbodylens 0.05; setprop debug.oulg.sf.kgblurexp 2'
# referência v0.22
su -c 'setprop debug.oulg.sf.kgband 0'
```

Como antes, instale o bundle novo pelo fluxo SurfaceFlinger do app e recompile/reinicie para cada comparação.

## Verificação (host)

- `field_check`: 65,9 milhões de pontos no campo novo (7 formatos, incluindo pill com raio abaixo da meia altura e retângulos com miolo plano; `kglens` 0.1–1.2; `kgband` 0.42–1; `kgbodylens` 0–0.15; expoente 1–4). Zero falhas: deslocamento sempre para dentro e limitado, Jacobiano por diferenças finitas sem pular nenhuma linha de emenda, Jacobiano do miolo contínuo (salto máximo 1e-11; o clamp anterior dava 0,15) (det mínimo 0.120, ampliação máxima 4.00x, nunca minifica), coordenada de iluminação idêntica à v0.22, blur monotônico e igual ao miolo após a faixa. Campo v0.22 inalterado.
- `check.sh`: 1 vertex e 5 fragment rewrites compilam e linkam (glslangValidator); dump do aparelho (`dzif_dump.txt`): 11 vertex e 36 fragment, todos compilam.
- `render_check` (WebGL2/SwiftShader): `kgband=0` contra a v0.22 — diferença máxima 0 no perfil 1 e no pop-up; FillRRect vs clip diferem no máximo 1 nível.
- `live_check.sh`: passou com as três chaves novas na allowlist.

Não verificado: aparência e custo de GPU no S25 Ultra. Pela contagem, menos pixels executam o disco de 48 amostras (o raio só passa de 0.5 px a ~22 px da borda, antes ~6 px), mas isso não prova custo menor.
