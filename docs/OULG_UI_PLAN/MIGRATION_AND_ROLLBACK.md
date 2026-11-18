# Migração e reversão (R6)

## 1. Dados do usuário

| Item | Mudança | Migração |
|---|---|---|
| 208 chaves do registro | Nenhuma (nome, domínio, default, unidade) | Nenhuma; valores lidos como estão |
| Valores salvos inativos | Passam a ser exibidos como latentes | Nenhuma escrita |
| `favorite.<key>` (ui prefs) | Filtrados por destino | Não apagados; aviso único `favorites_migrated` (flag `favorites.migrated.r6`) |
| Grupos abertos | Nova pref `group.<surface>.<route>.<id>` | Ausente = recolhido |
| 6 chaves `energy*` | Novas, defaults inertes (`energymode=0`, `energysaver=0`) | Ausentes = default |
| Perfis/presets exportados | Formato `PresetCodec` inalterado | Perfis antigos importam igual; perfis novos com `energy*` em app antigo: chaves desconhecidas são rejeitadas pelo validador antigo (comportamento existente de chave desconhecida — registrar em release notes) |
| Baseline compilado (`baseline.*`, `baselineBoot.*`) | Inalterado | — |
| Propriedades do sistema | Nenhuma escrita na atualização | — |

Ao abrir o app atualizado nada é publicado nem reiniciado. A primeira publicação só acontece por Apply explícito, e a lista mostrada é exatamente `writes ∪ activeDrift`.

## 2. Comportamento que muda para quem não mexe em nada

1. Lista de "aguardando aplicação" pode encolher: deriva observada≠compilada de chave inativa deixa de pedir restart (vai para relatório).
2. Live Updates, Now Bar e toasts mantêm o vidro com Economia de bateria ligada (antes: nativo). Reversível em Configurações › Energia › Seguir a Economia de bateria.
3. Controles técnicos saem de Superfícies (continuam em Configurações › Técnico, com o mesmo valor).

## 3. Rollback

- Por fase: `git revert <commit da fase>` (fases independentes na ordem B→C→D; C depende de B; D independe de C exceto `SettingsPage`).
- Reinstalar APK anterior: dados compatíveis (nenhuma chave renomeada; `energy*` ignoradas por `ConfigClient` antigo porque o registro antigo não as contém — o provider do app antigo também não as entrega).
- Nenhuma ação nativa: nenhum bundle, lente ou versão nativa mudou; nenhum restart de SurfaceFlinger é necessário para voltar.
