# Decisões fechadas e pendências (R6)

## Decisões

| Id | Decisão | Motivo |
|---|---|---|
| D1 | Sem migração de framework; Java Views + kit; plano em `docs/OULG_UI_PLAN/` | Risco e escopo; o problema é informação, não renderização |
| D2 | Camada de produto gerada de `CONTROL_MAPPING.json` (`ProductLayout`), sem nova matemática; chaves inalteradas | Uma fonte, verificável no CI; zero migração de dados |
| D-BLUR | Desfoque principal com lente = `hublur/kgblur` em %, com linha nominal | É o multiplicador real de borda e centro no shader (Clear e Fosco) |
| D-REFR | Refração principal = `hurefract/kgrefract`; Refração da borda (`sflens/kglens`) separada | Estágios distintos (shader vs. tag) |
| D-LIFT | Clareamento intocado e principal no Transparente, acima dos realces | Mandato |
| D-BLOOM | `hubloom` = "Contenção de brilho", sem inversão | Monotonicidade com "quantidade de realce" não provada |
| D-NEUTRAL | `hcneutral` permanece como "Neutralidade clássica dos brancos" nos detalhes, abaixo de H; não convertido | Tem efeito real quando Lift/Sat/scrimmax ativos; H não o acorda |
| D3 | Resolver de estado efetivo sem drafts; `PublicationPlan` com writes/activeDrift/inactiveDrift; revalidação no Apply | Corrige pendências falsas e lista velha |
| D4 | Nenhuma escrita de neutralidade; prova por identidade GLSL (T04) | Regras INACTIVE correspondem a leitores que ignoram o valor |
| D5 | `EditGuard` único (inclui reset, favoritos, busca, presets) | `reset()` contornava o guard |
| D6 | Inativo mostra "Inativo"; latente só no sheet | Número cinza parecia intensidade atual |
| D7 | Saver não desliga o vidro por padrão; política de bateria opt-in que só suspende/restaura | Gate antigo era política, não capacidade |
| D8 / D-TEL | Telemetria de custo: só estudo e issue futura; nada de mWh; seção omitida da UI | Sem método calibrado; escopo |
| D9 | Dispersão dos realces: NO-GO neste PR, protocolo registrado | Requisito de "ampliar influência" não cabe no orçamento sem prova |
| D10 | Copy nova em `COPY_PT_EN.csv` → gerador | Paridade pt/en garantida |
| D11 | Sem nova aba; Técnico como sheet em Configurações | ≤ 5 abas; general audience |
| D12 | Valor 2 (por cartão) só no Técnico | Modo de teste |

## Pendências (não bloqueiam este PR)

| Id | Item | Encaminhamento |
|---|---|---|
| P-DIFF | Dispersão dos realces | Issue nova após merge, protocolo em `HIGHLIGHT_DIFFUSION_FEASIBILITY.md` |
| P-TEL | Diagnóstico de custo por superfície (MVP de tempo/ativações/CPU amostrada) | Issue nova; `ENERGY_TELEMETRY_FEASIBILITY.md` |
| P-NEUTRAL | Se T04 achar par não idêntico | Bloqueio para Opus (ajustar regra para ACTIVE ou neutro explícito) |
| P-196/181/178 | Backlog existente | Não tocar |
| P-ODPM | Rails de energia do S25 | Investigar só se P-TEL avançar |

## Perguntas ao usuário

Nenhuma bloqueante. Defaults escolhidos: limite 20 %, histerese 5, manter carregando = sim, todas as superfícies, Saver não segue. Ajustáveis na tela Energia.
