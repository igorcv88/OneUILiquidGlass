# Sistema de design (R6)

Decisão D1: **sem migração de framework**. Java Views + kit existente (`ui/kit`: `Theme`, `Ui`, `Sheet`, `Slider`, `Toggle`, `Segmented`, `GlassNav`, `Icon`, `ColorPicker`, `Lens`). O R6 reorganiza a informação, não a pele. Nenhuma prévia visual foi produzida (preferência do usuário); este documento usa valores concretos.

## 1. Direção

"Instrumento de precisão sereno": superfícies calmas, hierarquia por escala e agrupamento, uma cor de destaque (`accent` ciano) para ação e foco, `warn` âmbar só para estado que exige ação (pendente de aplicar, suspenso), `error` só para falha. O vidro real está no sistema, não no app: o app não imita vidro com blur decorativo.

## 2. Tokens existentes (fonte: `ui/kit/Theme.java`) — manter

| Token | Valor |
|---|---|
| Tipos (sp) | DISPLAY 26, TITLE 20, HEADLINE 17, BODY 16, LABEL 14, CAPTION 13, SMALL 12 |
| Ritmo (dp) | GUTTER 20, CARD_RADIUS 24, ROW_MIN 56, BUTTON 52, BUTTON_SMALL 48, ICON 24, TOUCH 48 |
| Movimento (ms) | FAST 140, STANDARD_MS 220, EMPHASIZED 320; `reduceMotion` respeita `areAnimatorsEnabled()` |
| Cores escuras | bg `#0d1219`, surface `#161e2a`, surfaceHigh `#1c2534`, ink `#edf2f8`, inkMuted `#a6b3c4`, inkFaint `#76839a`, accent `#80dfe9`, warn `#f2bd72`, error `#f2928a`, ok `#86d9a9` |

## 3. Tokens novos (adicionar em `Theme`, sem mudar os existentes)

| Token | Valor | Uso |
|---|---|---|
| `GROUP_GAP` | 28 dp | Espaço entre grupos de detalhe (ritmo diferente do GUTTER) |
| `ROW_GAP_TIGHT` | 4 dp | Entre linhas do mesmo grupo |
| `PRIMARY_ROW_MIN` | 72 dp | Linha principal (título HEADLINE + valor + slider) |
| `stateInactive` | `inkFaint` + texto "Inativo" | Valor de controle inativo |
| `statePending` | `warn` em ponto 8 dp + texto | Pendente de aplicar |
| `stateExternal` | `lilac` em ponto 8 dp + texto | Alterado fora do app |

Contraste: `inkFaint` sobre `surface` escuro = ~4,6:1 (acima de 4,5:1). Verificar o par claro equivalente em teste (`ThemeContrastTest`: calcula contraste WCAG de ink/inkMuted/inkFaint/warn/error sobre surface/surfaceHigh, nos dois temas, mínimo 4,5:1 para texto; falha lista o par).

## 4. Componentes

| Componente | Base | Estados obrigatórios |
|---|---|---|
| `ControlRow` principal | existente | normal, arrastando (valor em `accent`), pendente (ponto `warn` + "Aplicar para valer"), inativo (valor "Inativo", slider desabilitado alpha 0,38, motivo em CAPTION `inkMuted`), externo, somente leitura, foco (anel `accent` 2 dp) |
| `GroupHeader` (novo, `ui/kit/GroupHeader`) | `Ui.section` + ícone + contador "3 de 7 ativos" + chevron | recolhido/expandido (estado salvo por superfície/rota em ui prefs `group.<surface>.<route>.<id>`), `contentDescription` com estado |
| `RestoreGroupAction` | botão texto BUTTON_SMALL | confirma em `Sheet` (`restore_group_confirm`), desfazer via `repo.undo()` |
| `ApplyBar` (LensSheet existente) | | contador `apply_count`, lista expansível, botão "Reiniciar" desabilitado com `apply_contradiction` |
| `StatusLine` (nova) | `Ui.row` CAPTION | estado de energia/sincronização, com hora relatada |

## 5. Acessibilidade

Alvos ≥ 48 dp (`TOUCH`); `contentDescription` de linha = título + valor exibido + estado ("Contenção de brilho, inativo, requer kernel simples"); live region `POLITE` na `ApplyBar` e no status de energia; ordem de foco topo→baixo; nenhum estado transmitido só por cor (sempre texto); reduzir movimento remove animações de expandir grupo (troca instantânea); fonte grande: linhas quebram em duas linhas sem truncar valores.

## 6. Referências usadas e descartadas

Skills locais lidas: `oulg-android-integration` (tabela de estado honesto), `apple-design` (hierarquia, materiais, movimento interrompível). UI/UX Pro Max (repo `e59ff37`, 2026-11-16): **a execução de `search.py` foi negada pelo classificador de modo automático**; não foi contornada; foram lidos apenas os CSVs `ux-guidelines.csv` e `app-interface.csv`. Adotado: alvos de 48 dp, grade de 8 dp, estados desabilitados explícitos, rótulos acessíveis com estado, anúncio por live region, confirmação de ação destrutiva, feedback de sucesso/erro, movimento reduzido, contraste 4,5:1, preservar estado da tela, ≤ 5 abas. Descartado: recomendações de bottom sheet para toda edição (o app já usa sheet só para editor/confirmação), estilos visuais de marketing (gradientes, glassmorphism decorativo), onboarding em carrossel.
