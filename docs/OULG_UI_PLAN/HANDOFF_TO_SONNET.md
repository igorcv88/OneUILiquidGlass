# Handoff para o Sonnet (executor) — R6

Você implementa `TASKS.md` em ordem, sem redesenhar. O plano está fechado; decisões em `DECISIONS_AND_OPEN_QUESTIONS.md`. Leia antes de começar: `PLAN_MASTER.md`, `EFFECTIVE_STATE_CONTRACT.md`, `TASKS.md`, `.claude/skills/oulg-android-integration/SKILL.md`. Para T04 leia também `.claude/skills/oulg-optics/SKILL.md` (só ferramentas host; nada nativo muda).

## Regras

1. Branch `feat/r6-ui-state-energy` (já existe, contém o commit do plano). Não criar outra. Não tocar `ClearGlass_Visual_Handoff/`, `.agents/`, `.claude/skills/apple-design/` (não rastreados do usuário).
2. Um commit por fase (B, C, D, E). Formato `<type>: <description>`; última linha `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Nunca `--no-verify`.
3. TDD: escrever o teste da tarefa, ver falhar, implementar, ver passar.
4. Arquivos < 800 linhas; funções < 50 linhas quando razoável; sem `System.out`/logs novos fora do `Probe` existente; sem mutação de mapas recebidos (copiar).
5. Atualizar `EXECUTION_TRACKER.md` ao concluir cada tarefa com comandos e saídas **reais** (não inventar). Commitar o tracker junto com a fase.
6. Proibido: editar `sfhook/*.c|*.h`, `refract_check.c`, versão nativa, defaults/domínios existentes, `hclift`, signing/release/secrets; merge; release; fechar issues; reiniciar SurfaceFlinger; apagar preferências; instalar pacotes desconhecidos; enviar código a serviços externos.
7. Bloqueio arquitetural (contradição no plano, T04 com GLSL diferente, teste antigo que exige quebrar uma regra): **pare**, registre em "Bloqueios para Opus" no tracker com evidência e retorne o relatório. Não contorne.
8. Dúvida mecânica (nome de método, local de helper): escolha o padrão do código vizinho e registre em "Desvios".

## Ambiente

Windows, Git Bash. Java 17 presente. SDK em `%LOCALAPPDATA%/Android/Sdk`; crie `local.properties` git-ignorado se o Gradle reclamar. `gh` autenticado. Comando de validação `V` no topo de `TASKS.md`. Se `cc`/`sh` de `check.sh` não funcionarem localmente, valide no CI.

## Entrega

PR draft único (T20), CI verde, link do artefato APK assinado (retenção 24 h) e SHA-256, tracker completo. Relatório final ao Opus: lista de commits, testes adicionados, desvios, bloqueios, resultado do CI.
