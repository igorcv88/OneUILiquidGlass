# OULG R6 — Plano mestre: UI para o público geral, estado efetivo e energia

Baseline: `main` @ `4f3311478f439c598ad33b3de6fec3bc55855d68` (R5 aprovado). Branch: `feat/r6-ui-state-energy`. Track: #209. Autor do plano: Opus; execução: Sonnet; revisão final: Opus.

## 1. Objetivo

Reorganizar o app para o público geral sem perder granularidade: cada superfície mostra 3–5 controles principais que mudam com a rota (padrão, lente Fosca, lente Transparente, captura, blur simples), e o resto vira "Detalhes da aparência" em cinco grupos recolhidos, "Comportamento" e uma área "Técnico e diagnóstico" fora das superfícies. Ao mesmo tempo, tornar honesto o estado de cada controle (ativo, aguardando aplicação, inativo com valor guardado, desligado, externo, desconhecido) e retirar o desligamento arbitrário do vidro pela Economia de bateria, trocando-o por uma política de energia opcional do usuário.

## 2. O que NÃO muda

Óptica (shader, campo, menisco, luz, blur, alpha highp #207), bundle e versão nativa, `hclift` (Clareamento), defaults e domínios das 208 chaves, R5 (#202, #205, #208, #210, #204), release/signing, issues (#209 só track; #196/#181/#178 backlog).

## 3. Documentos

| Documento | Conteúdo |
|---|---|
| `CONTROL_INVENTORY.csv` | 208 chaves × 32 colunas, gerado por `tools/build_inventory.py` (cobertura obrigatória; `--check`) |
| `CONTROL_MAPPING.json` | Fonte do layout por superfície/rota (entrada do gerador T07) |
| `EFFECT_MAP.md` | Intenções, rotas, relações entre chaves, fórmulas efetivas |
| `ACTIVATION_AND_BACKUP_CONTRACT.md` | Adendo P0: contexto NEXT, evidência compilada por geração, plano de ativação, backup schema 2, ImportPlan, proveniência, investigação de `hclift` |
| `EFFECTIVE_STATE_CONTRACT.md` | Estados, snapshot sem drafts, algoritmo de publicação, prova de neutralidade, guard, 18 casos |
| `HIGHLIGHT_DIFFUSION_FEASIBILITY.md` | NO-GO + protocolo |
| `ENERGY_POLICY.md` | Auditoria das rotas, máquina de estados, governor, UI de estado |
| `ENERGY_TELEMETRY_FEASIBILITY.md` | Sem mWh; MVP futuro |
| `COPY_PT_EN.csv` | Toda copy nova pt/en |
| `DESIGN_SYSTEM.md` | Tokens, componentes, acessibilidade, referências |
| `SCREEN_SPECS.md` | Telas |
| `MIGRATION_AND_ROLLBACK.md` | Dados, mudanças percebidas, reversão |
| `TASKS.md` | T01–T20 em 4 fases com arquivos, API, testes, aceite, rollback |
| `VALIDATION_MATRIX.md` | Testes automatizados, revisão, protocolo físico |
| `DECISIONS_AND_OPEN_QUESTIONS.md` | D1–D12, pendências |
| `HANDOFF_TO_SONNET.md` | Instruções de execução |
| `EXECUTION_TRACKER.md` | Checkpoint vivo |

## 4. Fases

B Estado efetivo (T01–T06) → B2 Ativação/evidência/backup (T21–T28, adendo P0, `ACTIVATION_AND_BACKUP_CONTRACT.md`) → C Layout e telas (T07–T14) → D Energia (T15–T18) → E Docs, CI, PR draft, APK (T19–T20). Um commit por fase. Depois: revisão Opus; correções pelo Sonnet; CI de novo.

## 5. Números

208 chaves: principais 24 (por rota 3–5), detalhes 77, comportamento 10, Central de controle 29, ícones 13, técnico 42, somente leitura 5, legado sem UI 6, seletores 2. +6 chaves de energia (214).

## 6. Riscos

| Risco | Mitigação |
|---|---|
| Regra INACTIVE onde o leitor não ignora | T04 identidade GLSL; para e escala |
| Mudar `applicability()` para confirmados afeta editor durante arrasto | `previewApplicability` só na prévia; testes do editor |
| SurfacesPage > 800 linhas | Extrações previstas em T09 |
| Testes de cobertura do registro (Tuning reader, Controls catálogo) | T15 cobre `Tuning` + `Section.ENERGY` |
| Saver sem gate muda Live/Now Bar | Opt-in de volta; validação física 8 e 10 |
| Contexto/cota | Tracker + plano persistidos no repositório |
