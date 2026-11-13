# Matriz de visibilidade por material do vidro

Gerada por `MaterialMatrixTest` a partir de `Controls` e `GlassMaterial`. Não edite à mão: rode os testes com `OULG_UPDATE_MATRIX=1` e revise o diff.

- **Escopo**: `comum` age em qualquer material (inclui botões antigos que continuam valendo no Clear); `fosco` só age no Fosco (óptica 11, substituído pelo campo Clear); `clear` só age no Transparente.
- **Fosco / Transparente**: se a lista comum do ambiente mostra o controle quando o material do ambiente é esse. A pesquisa e os favoritos seguem a mesma regra; **Todos os parâmetros** lista tudo e indica o material.
- O material de cada perfil vem do seu seletor (`sf.huclear` no pop-up, `sf.kgclear` no bloqueio); o seletor 2 (por cartão) mostra os dois grupos. A central e a Central de controle não são filtradas.
- Valores ocultos continuam salvos; trocar de material não os apaga.

## Principais por ambiente

| Ambiente | Material | Controles principais |
| --- | --- | --- |
| Pop-up | frosted (lente) | sflens, semradiuslens, lightglass, popuptone |
| Pop-up | clear (lente) | sf.hcfold, sf.hcedge, sf.hccore, sf.hcadapt, sf.hcsat, sf.hclift |
| Bloqueio | frosted (lente) | kglens, kgblurradius, lightglass, pillglass |
| Bloqueio | clear (lente) | sf.kcfold, sf.kcedge, sf.kccore, sf.kcsat, sf.kclift |

## Controles do pop-up e do bloqueio

| Chave | Nome na UI | Ambientes | Seção · nível | Escopo | Fosco | Transparente |
| --- | --- | --- | --- | --- | --- | --- |
| `sf.core` | Desfoque interno | Pop-up | Lente dinâmica · advanced | fosco | sim | não |
| `sf.hubodylens` | Refração difusa do miolo | Pop-up | Lente dinâmica · advanced | fosco | sim | não |
| `sf.edgeblur` | Desfoque no contorno | Pop-up | Lente dinâmica · advanced | fosco | sim | não |
| `sf.ramp` | Rampa do pop-up | Pop-up | Técnico · technical | fosco | sim | não |
| `sf.kgcore` | Desfoque interno | Bloqueio | Lente dinâmica · advanced | fosco | sim | não |
| `sf.kgedgeblur` | Desfoque no contorno | Bloqueio | Lente dinâmica · advanced | fosco | sim | não |
| `sf.kgsat` | Saturação | Bloqueio | Lente dinâmica · advanced | fosco | sim | não |
| `sf.kgcool` | Tom frio | Bloqueio | Lente dinâmica · advanced | fosco | sim | não |
| `sf.kgflat` | Uniformidade | Bloqueio | Lente dinâmica · advanced | fosco | sim | não |
| `sf.kglevel` | Nível da uniformidade | Bloqueio | Lente dinâmica · advanced | fosco | sim | não |
| `sf.kgflatcap` | Limite da uniformidade | Bloqueio | Técnico · technical | fosco | sim | não |
| `sf.kgtone` | Curva de destaque | Bloqueio | Lente dinâmica · advanced | fosco | sim | não |
| `sf.kgcoredim` | Escurecer o miolo | Bloqueio | Lente dinâmica · advanced | fosco | sim | não |
| `sf.kgcorecap` | Teto do desfoque interno | Bloqueio | Lente dinâmica · advanced | fosco | sim | não |
| `sf.kggradient` | Transição vidro-miolo | Bloqueio | Técnico · technical | fosco | sim | não |
| `sf.kgband` | Largura da transição óptica | Bloqueio | Lente dinâmica · advanced | fosco | sim | não |
| `sf.kgbodylens` | Refração difusa do miolo | Bloqueio | Lente dinâmica · advanced | fosco | sim | não |
| `sf.kgblurexp` | Curva do desfoque na transição | Bloqueio | Técnico · technical | fosco | sim | não |
| `sf.kgramp` | Rampa do bloqueio | Bloqueio | Técnico · technical | fosco | sim | não |
| `sf.hcfold` | Menisco | Pop-up | Bordas e reflexos · standard | clear | não | sim |
| `sf.hcband` | Largura da borda curva | Pop-up | Bordas e reflexos · advanced | clear | não | sim |
| `sf.hcamp` | Curvatura sem menisco | Pop-up | Bordas e reflexos · advanced | clear | não | sim |
| `sf.hcbody` | Ampliação do corpo | Pop-up | Bordas e reflexos · advanced | clear | não | sim |
| `sf.hcbody2` | Abaulamento do centro | Pop-up | Bordas e reflexos · advanced | clear | não | sim |
| `sf.hcedge` | Desfoque da borda externa | Pop-up | Vidro e desfoque · standard | clear | não | sim |
| `sf.hccore` | Desfoque do centro | Pop-up | Vidro e desfoque · standard | clear | não | sim |
| `sf.hcblurexp` | Transição do desfoque | Pop-up | Vidro e desfoque · advanced | clear | não | sim |
| `sf.hcadapt` | Desfoque adaptativo | Pop-up | Vidro e desfoque · standard | clear | não | sim |
| `sf.hcsat` | Saturação do vidro | Pop-up | Cor e contraste · standard | clear | não | sim |
| `sf.hcscrim` | Escurecimento nas áreas escuras | Pop-up | Cor e contraste · advanced | clear | não | sim |
| `sf.hcscrimmax` | Escurecimento nas áreas claras | Pop-up | Cor e contraste · advanced | clear | não | sim |
| `sf.hclift` | Clareamento | Pop-up | Cor e contraste · standard | clear | não | sim |
| `sf.hcneutral` | Neutralizar brancos | Pop-up | Cor e contraste · advanced | clear | não | sim |
| `sf.kcfold` | Menisco | Bloqueio | Bordas e reflexos · standard | clear | não | sim |
| `sf.kcband` | Largura da borda curva | Bloqueio | Bordas e reflexos · advanced | clear | não | sim |
| `sf.kcamp` | Curvatura sem menisco | Bloqueio | Bordas e reflexos · advanced | clear | não | sim |
| `sf.kcbody` | Ampliação do corpo | Bloqueio | Bordas e reflexos · advanced | clear | não | sim |
| `sf.kcbody2` | Abaulamento do centro | Bloqueio | Bordas e reflexos · advanced | clear | não | sim |
| `sf.kcedge` | Desfoque da borda externa | Bloqueio | Vidro e desfoque · standard | clear | não | sim |
| `sf.kccore` | Desfoque do centro | Bloqueio | Vidro e desfoque · standard | clear | não | sim |
| `sf.kcblurexp` | Transição do desfoque | Bloqueio | Vidro e desfoque · advanced | clear | não | sim |
| `sf.kcsat` | Saturação do vidro | Bloqueio | Cor e contraste · standard | clear | não | sim |
| `sf.kclift` | Clareamento | Bloqueio | Cor e contraste · standard | clear | não | sim |
| `sf.kcveil` | Véu de nivelamento | Bloqueio | Cor e contraste · advanced | clear | não | sim |
| `sf.kclevel` | Nível do véu | Bloqueio | Cor e contraste · advanced | clear | não | sim |
| `sf.kcdim` | Escurecimento do vidro | Bloqueio | Cor e contraste · advanced | clear | não | sim |
| `sf.kcknee` | Compressão de realces | Bloqueio | Cor e contraste · advanced | clear | não | sim |
| `sf.kccool` | Tom frio do vidro | Bloqueio | Cor e contraste · advanced | clear | não | sim |

## Comuns do compositor que continuam valendo no Transparente

`sf.hublur`, `sf.hurefract`, `sf.hubloom`, `sf.taps`, `sf.kgblur`, `sf.kgrefract`, `sf.kgline`, `sf.kgfixedrim`, `sf.kgrim`, `sf.kgspec`, `sf.kgglow`, `sf.kgtilt`, `sf.kgtaps`, `sf.hulight`, `sf.kglight`, `sf.kgdepth`, `sf.debug`
