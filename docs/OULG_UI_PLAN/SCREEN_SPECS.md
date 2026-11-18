# Especificação de telas (R6)

Navegação mantida: 4 abas (`MainActivity`: Início, Superfícies, Ícones, Configurações). Nenhuma aba nova. Copy: `COPY_PT_EN.csv`.

## 1. Início (`HomePage`) — mudanças mínimas

- Mantém: vidro mestre, superfícies on/off (pop-up, bloqueio, central, Central de controle, Live Updates, Now Bar, toasts), estado do sistema, ação de lente.
- Novo: sob a lista de superfícies, linha `live_inherits` (CAPTION, `inkMuted`).
- Novo: quando `engine_status energy.reason != NONE`, `StatusLine` `energy_home_notice` em `warn`, toque abre Configurações › Energia.

## 2. Superfícies (`SurfacesPage`) — reconstrução da lista

Ordem por superfície selecionada (seletor existente):

1. Toggle da superfície (existente).
2. Material (existente; não na central). Mostra Fosco/Transparente; "Por cartão" só aparece se o valor salvo já for 2 (rótulo `material_per_card`) — a escolha 2 é feita no Técnico.
3. **Principais** (`ProductLayout.main(surface, route)`, 3–5 linhas `PRIMARY_ROW_MIN`). Sempre visíveis.
4. Favoritos (existente; filtrados por destino permitido; inativos mostram "Inativo").
5. **Detalhes da aparência**: até 5 `GroupHeader` recolhidos por padrão (Desfoque; Refração e forma; Luz e contorno; Cor e realces; Transparência e contraste). Cada grupo: linhas na ordem do mapeamento, contador de ativos, `restore_group`. Grupo sem linhas visíveis na rota não aparece.
6. Mídia (central, existente) dentro de Transparência e contraste via `mediaart`.
7. **Central de controle** (só shade): principais `quick_settings.main`, depois grupo recolhido "Detalhes" com o resto (dependências via Applicability existente).
8. **Comportamento** (recolhido): chaves `behavior` da superfície.
9. Camadas (`Controls.layers`, existente) permanecem onde estão hoje.

Removido de Superfícies: o bloco "Técnico" por superfície e as disclosures por `Section` STD/ADV. Tudo técnico vai para Configurações › Técnico.

Linha inativa: valor "Inativo", motivo na segunda linha, toque abre `ControlSheet` somente leitura com `state_latent` e motivo. Linha pendente: ponto `warn` + "Aplicar para valer". Rota LENS_MIXED: principais de F seguidos de C sem duplicatas.

Exemplo HU, Transparente (rota C): Principais = Desfoque 100 %, Refração 100 %, Clareamento 0,40, Saturação 1,05, Menisco 2,0. Desfoque (detalhe) = Desfoque do sistema, Desfoque da borda (24 px, "Efetivo nominal 24 px × 100 % = 24 px"), Desfoque do centro, Transição do desfoque, Desfoque adaptativo. Cor e realces = Escurecimento nas áreas escuras, nas áreas claras, Neutralidade dos realces, Contenção de brilho (Inativo em kernel 1–3), Neutralidade clássica dos brancos, Cor do vidro claro.

## 3. Busca (`SearchSheet`)

Indexa títulos de produto (`pc_*`) e títulos atuais. Resultado mostra caminho ("Pop-up › Detalhes › Desfoque"). Técnico só aparece com "Mostrar técnico" (toggle já existente). Legado nunca. Abrir resultado respeita `EditGuard.OPEN_EDITOR`.

## 4. Editor (`ControlSheet`)

Mantém slider/entrada exata/reset. Novo: cabeçalho de estado (Ativo/Aplicar para valer/Inativo+motivo/Externo); para pais multiplicadores, linha "Afeta: Desfoque da borda, Desfoque do centro"; para filhos, linha `blur_effective_nominal`. Somente leitura quando o guard recusa. Exibição `MULTIPLIER_PERCENT`: 0,65 → "65 %", entrada exata aceita "65" ou "65 %" e grava 0.65 (6 casas canônicas `SettingSpec.validate`).

## 5. Configurações (`SettingsPage`)

Seções em ordem: Aparência do app; Vidro (lente dinâmica, notificação de teste, estilos/presets); **Energia (nova)**; Backup; Diagnóstico; **Técnico e diagnóstico (nova entrada)**; Recuperação.

### 5.1 Energia

- `StatusLine` com `energy_now_*` + `energy_reported_at`.
- `energy_mode` (Segmented: Nunca / Abaixo do limite).
- Se modo 1: `energy_threshold` (slider 5–50 %, passo 1), `energy_hysteresis` (2–15 pontos), `energy_charging` (toggle), `energy_surfaces` (lista de 7 toggles; a máscara nunca vai a 0: o último toggle não desliga e mostra motivo).
- `energy_saver` (toggle, default desligado) + `energy_saver_help`.
- `energy_help` no rodapé. Sem seção de diagnóstico de custo (D-TEL).

### 5.2 Técnico e diagnóstico

Abre página de sheet cheia: `technical_warning` no topo; grupos `CONTROL_MAPPING.technical.order` recolhidos; grupo "Estado do sistema (somente leitura)"; grupo "Legado sem efeito" (somente leitura); seletor de material com "Por cartão (teste)"; atalho "Todos os parâmetros" (existente) e "Copiar relatório" (existente). Itens daqui não aceitam favorito.

## 6. Aplicar (`LensSheet`)

Lista = `plan.writes ∪ plan.activeDrift`, cada item com título de produto, valor e origem ("Seu ajuste" / "Alterado fora do app"). Contradição desabilita Reiniciar com `apply_contradiction`. Revalidação no toque (contrato §4.2). Relatório técnico recolhido "Sem efeito agora" lista `inactiveDrift` e `deferred`, sem botão.

## 7. Ícones

Sem mudança (13 chaves, aplicação em lote existente).
