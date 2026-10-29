# Refração e desfoque por região — PR #97

Perfis do SurfaceFlinger: **0 = pop-up**, **1 = notificações do bloqueio**. Os perfis **2 = notificações da central** e **3 = Quick Settings** não são alterados. Os controles `sf.*` são incorporados quando o shader é recompilado; alterar só a propriedade não prova que a lente carregada já a usa. Reinicie o userspace pela ação confirmada do aplicativo e confira o diagnóstico.

| Região | Pop-up | Bloqueio |
| --- | --- | --- |
| Desfoque base da notificação inteira (Android, ao vivo) | `semradiuslens` | `kgblurradius` (`-1` segue `semradiuslens`) |
| Ganho do desfoque adicional do shader inteiro (`0–2`, padrão `1`) | `sf.hublur` | `sf.kgblur` |
| Desfoque adicional do miolo (px) | `sf.core` | `sf.kgcorecap` limita `sf.kgcore` |
| Desfoque adicional da borda (px) | `sf.edgeblur` | `sf.kgedgeblur` (novo, padrão `0`) |
| Ganho da refração da superfície inteira (`0–1.2`, padrão `1`) | `sf.hurefract` | `sf.kgrefract` |
| Refração difusa do miolo (`0–0.15`) | `sf.hubodylens` (novo, padrão `0`) | `sf.kgbodylens` (existente) |
| Refração da borda | `sflens` | `kglens` |
| Largura do campo óptico | `sf.ramp` | `sf.kgband` (deve ser **maior que zero** para ativar a lente difusa do miolo) |

O ganho de desfoque `sf.hublur`/`sf.kgblur` é multiplicado depois que o shader determina o raio local. Ele não reduz o blur base do Android. `sf.kgedgeblur=0` preserva o contorno anterior: no campo contínuo o raio sobe de zero ao teto do miolo; quando `sf.kgband=0` continua usando a curva legada. Se `sf.kgedgeblur` for positivo com `sf.kgband=0`, ele introduz o raio somente perto da borda, sem mudar o centro.

O deslocamento difuso do pop-up usa uma curvatura elíptica suave, independente do contorno, limitada a 15% do semieixo menor. Os ganhos globais de refração são aplicados depois dos componentes locais e só atingem os perfis 0 e 1. A validação numérica dos perfis originais permanece no `field_check`; `material_controls_check.sh` confirma a geração de todos os campos novos e os defaults neutros.

## Proposta inicial com o arquivo de ajustes (8)

Na captura `observed`, `sf.kgband=0`, `sf.kgcorecap=6.9`, `kgblurradius=16`, `sf.core=90`, `semradiuslens=22` e `sflens=1.2`. A refração difusa do bloqueio está desligada pelo `sf.kgband=0`; o pop-up usa o valor máximo de refração da borda, mas não curva o miolo.

- **Bloqueio:** teste primeiro `sf.kgband=0.85`, `sf.kgbodylens=0.07`, `sf.kgedgeblur=5`, `sf.kgblur=1`, `sf.kgrefract=1` e `kgblurradius=12` (o teto `sf.kgcorecap=6.9` já é baixo). Se faltar refração, suba `sf.kgrefract` gradualmente para `1.05–1.10`; para mais difusão aumente `sf.kgbodylens` em passos de `0.01`.
- **Pop-up:** teste `semradiuslens=14`, `sf.core=56`, `sf.edgeblur=8`, `sf.hubodylens=0.06`, `sf.hublur=1`, `sf.hurefract=1`. Se ainda faltar refração difusa, experimente `sf.hubodylens=0.08`, sem subir `sflens=1.2` que já está no limite.

Os valores são pontos de partida, **não presets ativados automaticamente**. Depois de cada bloco, compare fundo preto, branco e colorido, notificação em repouso e durante animação. Se surgir deformação ou regressão, restaure `sf.hubodylens=0`, `sf.kgedgeblur=0`, `sf.hublur=1`, `sf.kgblur=1`, `sf.hurefract=1`, `sf.kgrefract=1`. Para retornar à geometria de bloqueio da configuração enviada, restaure `sf.kgband=0`.

O helper de diagnóstico admite `setprops` somente para nomes conhecidos. Esses valores também podem ser selecionados no aplicativo e aplicados com reinício confirmado; um `setprop` isolado não reescreve os shaders já carregados.
