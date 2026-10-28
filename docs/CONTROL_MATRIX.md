# Matriz de controles

Gerada por `ControlMatrixTest` a partir de `SettingsRegistry`, `Controls`, `Applicability`, `glass/Tuning.java` e `sfhook/oulg_sf.c`. Não edite à mão: rode os testes com `OULG_UPDATE_MATRIX=1`.

- **Lido por**: arquivos que usam o campo de `Tuning` (Java, ao vivo via `ConfigClient` ou `getprop`) ou a biblioteca do compositor (lido ao compilar os shaders).
- **Aplicação**: `ao vivo` = SystemUI relê em até ~1 s ou na notificação do provider; `reinício` = só depois de gravar a propriedade e reiniciar o userspace pela lente dinâmica; `leitura` = estado.
- **Padrão / Lente / Captura**: `Applicability.rule` com os valores padrão em cada modo; dependências entre chaves e camadas aparecem nas linhas dos switches.
- **Prévia**: estratégia e canal de `Controls`; `FAITHFUL` e `DIDACTIC` são cobertos por `ControlModelTest.everyRepresentedControlVisiblyChangesThePreview` (parâmetros) e por `tools/screenshots` `PerceptibilityTest` (pixels).
- **Teste**: `CM` = ControlModelTest, `SC` = ScaleTest, `CV` = CompositorValuesTest, `LH` = sfhook/tools/live_check.sh, `PT` = PerceptibilityTest.

| Chave | Nome na UI | Ambientes | Grupo · nível | Tipo · faixa · padrão | Lido por | Aplicação | Padrão | Lente | Captura | Camada | Prévia | Teste |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `backdrop` | Origem do fundo | Pop-up, Bloqueio, Central | Técnico · technical | enum · auto/hybrid/capture/grid/off · auto | glass/BackgroundBlurBridge, glass/GlassDrawable, hooks/HeadsUpHooks | ao vivo | ativo | inativo: Usado no vidro padrão ou na captura | ativo |  | none | CM |
| `profile` | Modelo de refração | Pop-up | Técnico · technical | enum · lens/snell · lens | glass/GlassDrawable | ao vivo | ativo | inativo: Usado no vidro padrão com a faixa de lente | ativo |  | none | CM |
| `lens` | Curvatura da faixa | Pop-up | Lente e movimento · advanced | float · 0–0.45 · 0.24 | glass/GlassDrawable | ao vivo | ativo | inativo: Usado no vidro padrão com a faixa de lente | ativo | lens | faithful · lens | CM, SC, PT |
| `refract` | Multiplicador físico | Pop-up | Técnico · technical | float · 0–6 · 1 | glass/GlassDrawable | ao vivo | inativo: Usado com o modelo Física | inativo: Usado no vidro padrão com a faixa de lente | inativo: Usado com o modelo Física |  | none | CM, SC |
| `rimhz` | Frequência da faixa | Pop-up | Técnico · technical | int · 1–120 · 80 | glass/HybridBackdrop, hooks/HeadsUpHooks | ao vivo | ativo | inativo: Usado no vidro padrão com a faixa de lente | ativo |  | none | CM, SC |
| `rimblur` | Desfoque da faixa | Pop-up | Lente e movimento · advanced | float · 0–32 · 24 | glass/GlassDrawable | ao vivo | ativo | inativo: Usado no vidro padrão com a faixa de lente | ativo |  | didactic | CM, SC, PT |
| `ior` | Índice de refração | Pop-up | Técnico · technical | float · 1–2.4 · 1.5 | glass/GlassDrawable | ao vivo | inativo: Usado com o modelo Física | inativo: Usado no vidro padrão com a faixa de lente | inativo: Usado com o modelo Física |  | none | CM, SC |
| `blur` | Desfoque da captura | Pop-up, Bloqueio, Central | Técnico · technical | float · 0–32 · 8 | glass/GlassDrawable | ao vivo | inativo: Usado no modo de captura | inativo: Usado no modo de captura | ativo |  | none | CM, SC |
| `sat` | Saturação da captura | Pop-up, Bloqueio, Central | Técnico · technical | float · 0–2.5 · 1.2 | glass/GlassDrawable | ao vivo | inativo: Usado no modo de captura | inativo: Usado no modo de captura | ativo |  | faithful · saturation | CM, SC, PT |
| `disp` | Dispersão de cor | Pop-up | Técnico · technical | float · 0–0.5 · 0.10 | glass/GlassDrawable | ao vivo | ativo | inativo: Usado no vidro padrão com a faixa de lente | ativo |  | none | CM, SC |
| `hz` | Frequência de captura | Pop-up | Técnico · technical | int · 1–120 · 15 | glass/CaptureBackdrop | ao vivo | inativo: Usado no modo de captura | inativo: Usado no modo de captura | ativo |  | none | CM, SC |
| `hzkg` | Frequência no bloqueio | Bloqueio | Técnico · technical | int · 1–120 · 5 | glass/CaptureBackdrop | ao vivo | inativo: Usado no modo de captura | inativo: Usado no modo de captura | ativo |  | none | CM, SC |
| `tint` | Véu da captura | Pop-up, Bloqueio, Central | Técnico · technical | int · -1–255 · -1 | glass/GlassDrawable | ao vivo | inativo: Usado no modo de captura | inativo: Usado no modo de captura | ativo |  | none | CM, SC |
| `livelens` | Faixa de lente no pop-up | Pop-up | Lente e movimento · advanced | boolean · 1 | hooks/HeadsUpHooks | ao vivo | ativo | inativo: Usado no vidro padrão | inativo: Usado no vidro padrão | lens | didactic · lens | CM, PT |
| `lensmaxage` | Idade máxima da captura | Pop-up | Lente e movimento · advanced | int · 8–500 · 40 | glass/GlassDrawable | ao vivo | ativo | inativo: Usado no vidro padrão com a faixa de lente | ativo |  | behavior | CM, SC |
| `lenssettle` | Espera após gesto | Pop-up | Lente e movimento · advanced | int · 0–3000 · 100 | glass/GlassDrawable, hooks/HeadsUpHooks | ao vivo | ativo | inativo: Usado no vidro padrão com a faixa de lente | ativo |  | behavior | CM, SC |
| `lenstransition` | Espera após transição | Pop-up | Lente e movimento · advanced | int · 0–1500 · 350 | hooks/HeadsUpHooks | ao vivo | ativo | inativo: Usado no vidro padrão com a faixa de lente | ativo |  | behavior | CM, SC |
| `lensgate` | Ocultar a faixa em | Pop-up | Lente e movimento · advanced | enum · directional/conservative · directional | glass/LensStats | ao vivo | ativo | inativo: Usado no vidro padrão com a faixa de lente | ativo |  | behavior | CM |
| `sfrefract` | Lente dinâmica | Pop-up, Bloqueio, Central | Técnico · internal | boolean · 0 | glass/BackgroundBlurBridge, glass/GlassDrawable, glass/SemBlurBridge, hooks/HeadsUpHooks, hooks/QuickSettingsEdgeLight | leitura | leitura | leitura | leitura |  | none | CM |
| `liveblur` | Vidro padrão | Pop-up, Bloqueio, Central | Técnico · technical | boolean · 1 | glass/BackgroundBlurBridge, glass/GlassDrawable, hooks/HeadsUpHooks | ao vivo | ativo | inativo: Usado no vidro padrão ou na captura | ativo |  | none | CM |
| `liveradius` | Desfoque | Pop-up, Bloqueio | Vidro e desfoque · standard | int · 0–400 · 80 | hooks/HeadsUpHooks | ao vivo | ativo | inativo: Usado no vidro padrão | inativo: Usado no vidro padrão |  | faithful · blur | CM, SC, PT |
| `liveshaderadius` | Desfoque | Central | Vidro e desfoque · standard | int · 0–400 · 120 | hooks/HeadsUpHooks | ao vivo | ativo | inativo: Usado no vidro padrão | inativo: Usado no vidro padrão |  | faithful · blur | CM, SC, PT |
| `liveoptics` | Intensidade das bordas | Pop-up, Bloqueio, Central | Bordas e reflexos · advanced | float · 0–2 · 1 | glass/GlassDrawable, hooks/HeadsUpHooks | ao vivo | ativo | inativo: Usado no vidro padrão | inativo: Usado no vidro padrão | edge | faithful · optics | CM, SC, PT |
| `livesheen` | Brilho no topo | Pop-up, Bloqueio, Central | Bordas e reflexos · advanced | float · 0–0.15 · 0.02 | glass/GlassDrawable | ao vivo | ativo | inativo: Usado no vidro padrão | inativo: Usado no vidro padrão | edge | faithful · sheen | CM, SC, PT |
| `liveglint` | Reflexo | Pop-up | Bordas e reflexos · standard | float · 0–1 · 0.10 | hooks/HeadsUpHooks | ao vivo | ativo | inativo: Usado no vidro padrão | inativo: Usado no vidro padrão | glint | faithful · glint | CM, SC, PT |
| `shadeglint` | Reflexo | Central | Bordas e reflexos · standard | float · 0–1 · 0.10 | hooks/HeadsUpHooks | ao vivo | ativo | inativo: Usado no vidro padrão | inativo: Usado no vidro padrão | glint | faithful · glint | CM, SC, PT |
| `semradius` | Desfoque geral | Central | Vidro e desfoque · advanced | int · -1–400 · -1 | glass/BackgroundBlurBridge, glass/SemBlurBridge | ao vivo | inativo: Sem efeito no vidro padrão | ativo | ativo |  | faithful · blur | CM, SC, PT |
| `semradiuslens` | Desfoque sob a lente | Pop-up, Bloqueio | Vidro e desfoque · standard | int · -1–400 · 16 | glass/BackgroundBlurBridge, glass/SemBlurBridge | ao vivo | inativo: Usado com a lente dinâmica | ativo | inativo: Usado com a lente dinâmica |  | faithful · blur | CM, SC, PT |
| `sflens` | Refração da borda | Pop-up | Bordas e reflexos · standard | float · 0.1–1.2 · 0.45 | glass/SemBlurBridge | ao vivo | inativo: Usado com a lente dinâmica | ativo | inativo: Usado com a lente dinâmica | lens | faithful · lens | CM, SC, PT |
| `semcurve` | Curva de cor do sistema | Pop-up, Bloqueio, Central | Técnico · technical | enum · auto/spatial/dim/ultra/none · auto | glass/BackgroundBlurBridge, glass/SemBlurBridge | ao vivo | ativo | ativo | ativo |  | none | CM |
| `semalpha` | Véu do desfoque do sistema | Pop-up, Bloqueio, Central | Cor e contraste · advanced | int · -1–255 · -1 | glass/BackgroundBlurBridge, glass/GlassDrawable, glass/SemBlurBridge, hooks/HeadsUpHooks | ao vivo | ativo | ativo | ativo |  | didactic · tint | CM, SC, PT |
| `semshape` | Forma da região | Pop-up, Bloqueio, Central | Técnico · technical | enum · auto/single/path/four/none · auto | glass/SemBlurBridge | ao vivo | inativo: Sem efeito no vidro padrão | ativo | ativo |  | none | CM |
| `shadeblur` | Desfoque próprio por cartão | Central | Vidro e desfoque · advanced | boolean · 1 | hooks/HeadsUpHooks | ao vivo | ativo | ativo | ativo | per_card_blur | didactic · blur | CM, PT |
| `kgblurpath` | Desfoque do bloqueio com a lente | Bloqueio | Técnico · technical | int · 0–1 · 1 | hooks/HeadsUpHooks | ao vivo | inativo: Usado com a lente dinâmica | ativo | inativo: Usado com a lente dinâmica |  | none | CM, SC |
| `shadeblurpath` | Desfoque da central | Central | Técnico · technical | int · 0–1 · 1 | hooks/HeadsUpHooks | ao vivo | inativo: Sem efeito no vidro padrão | ativo | ativo |  | none | CM, SC |
| `shadefadecut` | Corte do desfoque ao sumir | Central | Comportamento · advanced | float · 0–1 · 0.5 | hooks/HeadsUpHooks | ao vivo | ativo | ativo | ativo |  | behavior | CM, SC |
| `kgwinblur` | Desfoque da janela no bloqueio parado | Bloqueio | Comportamento · advanced | boolean · 0 | hooks/HeadsUpHooks | ao vivo | ativo | ativo | inativo: Usado no vidro padrão ou com a lente dinâmica |  | behavior | CM |
| `kgblurradius` | Desfoque | Bloqueio | Vidro e desfoque · standard | int · -1–400 · -1 | glass/BackgroundBlurBridge | ao vivo | ativo | ativo | inativo: Usado no vidro padrão ou com a lente dinâmica |  | faithful · blur | CM, SC, PT |
| `pillglass` | Vidro no resumo de ícones | Bloqueio | Vidro e desfoque · standard | boolean · 1 | hooks/HeadsUpHooks | ao vivo | ativo | ativo | inativo: Usado no vidro padrão ou com a lente dinâmica |  | faithful · pill | CM, PT |
| `pillblur` | Desfoque do resumo | Bloqueio | Vidro e desfoque · advanced | int · 0–400 · 40 | glass/BackgroundBlurBridge, hooks/HeadsUpHooks | ao vivo | ativo | ativo | inativo: Usado no vidro padrão ou com a lente dinâmica |  | faithful · pill_blur | CM, SC, PT |
| `kgoptics` | Material da lente no bloqueio | Bloqueio | Bordas e reflexos · advanced | int · 0–1 · 1 | glass/SemBlurBridge | ao vivo | inativo: Usado com a lente dinâmica | ativo | inativo: Usado com a lente dinâmica |  | behavior | CM, SC |
| `huoptics` | Material da lente no pop-up | Pop-up | Bordas e reflexos · advanced | int · 0–1 · 0 | glass/SemBlurBridge | ao vivo | inativo: Usado com a lente dinâmica | ativo | inativo: Usado com a lente dinâmica |  | behavior | CM, SC |
| `kglens` | Refração da borda | Bloqueio | Bordas e reflexos · standard | float · -1–1.2 · 0.40 | glass/SemBlurBridge | ao vivo | inativo: Usado com a lente dinâmica | ativo | inativo: Usado com a lente dinâmica | lens | faithful · lens | CM, SC, PT |
| `shadetone` | Proteção de legibilidade | Central | Cor e contraste · standard | enum · 0/1/2/3 · 3 | glass/SemBlurBridge, hooks/HeadsUpHooks | ao vivo | ativo | ativo | ativo | tonemap | didactic · veil | CM, PT |
| `shadecap` | Limite para texto claro | Pop-up, Central | Cor e contraste · advanced | float · 0.2–1 · 0.55 | glass/ShadeTone | ao vivo | ativo | ativo | ativo |  | didactic · veil | CM, SC, PT |
| `shadelift` | Limite para texto escuro | Pop-up, Central | Cor e contraste · advanced | float · 0–0.8 · 0.45 | glass/ShadeTone | ao vivo | ativo | ativo | ativo |  | didactic · veil | CM, SC, PT |
| `veilmax` | Proteção de contraste | Pop-up, Central | Cor e contraste · standard | float · 0–0.75 · 0.25 | glass/ShadeTone | ao vivo | ativo | ativo | ativo | tonemap | faithful · veil | CM, SC, PT |
| `stylelo` | Voltar ao vidro escuro abaixo de | Pop-up, Bloqueio, Central | Cor e contraste · advanced | float · 0.2–1 · 0.48 | glass/ShadeTone, hooks/HeadsUpHooks | ao vivo | ativo | ativo | ativo |  | didactic · text_swap | CM, SC, PT |
| `stylehi` | Passar ao vidro claro acima de | Pop-up, Bloqueio, Central | Cor e contraste · advanced | float · 0.3–1 · 0.62 | glass/ShadeTone, hooks/HeadsUpHooks | ao vivo | ativo | ativo | ativo |  | didactic · text_swap | CM, SC, PT |
| `lightglass` | Opacidade do vidro claro | Pop-up, Bloqueio | Vidro e desfoque · standard | float · 0–0.95 · 0.75 | glass/ShadeTone | ao vivo | ativo | ativo | ativo |  | faithful · light_alpha | CM, SC, PT |
| `lighttone` | Cor do vidro claro | Pop-up, Bloqueio, Central | Cor e contraste · advanced | rgb · ffffff | glass/ShadeTone | ao vivo | ativo | ativo | ativo |  | faithful · light_tone | CM, PT |
| `shadelightglass` | Opacidade do vidro claro | Central | Vidro e desfoque · standard | float · 0–0.95 · 0.27 | glass/ShadeTone | ao vivo | ativo | ativo | ativo |  | faithful · light_alpha | CM, SC, PT |
| `lightoutline` | Contorno no vidro claro | Pop-up, Central | Bordas e reflexos · advanced | float · 0–2 · 0.8 | hooks/HeadsUpHooks | ao vivo | ativo | ativo | ativo | edge | faithful · outline | CM, SC, PT |
| `lightedge` | Sombra da borda no vidro claro | Pop-up, Central | Bordas e reflexos · advanced | float · 0–4 · 1 | hooks/HeadsUpHooks | ao vivo | ativo | inativo: Usado no vidro padrão | inativo: Usado no vidro padrão | edge | didactic · edge_shadow | CM, SC, PT |
| `livedarkalpha` | Brilho do vidro escuro | Pop-up | Vidro e desfoque · advanced | int · 0–255 · 20 | hooks/HeadsUpHooks | ao vivo | ativo | inativo: Usado no vidro padrão | inativo: Usado no vidro padrão |  | faithful · region | CM, SC, PT |
| `livedarkfill` | Escurecimento | Pop-up | Vidro e desfoque · standard | int · 0–255 · 10 | hooks/HeadsUpHooks | ao vivo | ativo | inativo: Usado no vidro padrão | inativo: Usado no vidro padrão |  | faithful · dark_fill | CM, SC, PT |
| `liverim` | Contorno | Pop-up | Bordas e reflexos · standard | float · 0–4 · 2 | hooks/HeadsUpHooks | ao vivo | ativo | inativo: Usado no vidro padrão | inativo: Usado no vidro padrão | edge | faithful · rim | CM, SC, PT |
| `kgdarkalpha` | Brilho do vidro escuro | Bloqueio | Vidro e desfoque · advanced | int · 0–255 · 20 | hooks/HeadsUpHooks | ao vivo | ativo | inativo: Usado no vidro padrão | inativo: Usado no vidro padrão |  | faithful · region | CM, SC, PT |
| `kgdarkfill` | Escurecimento | Bloqueio | Vidro e desfoque · standard | int · 0–255 · 14 | hooks/HeadsUpHooks | ao vivo | ativo | inativo: Usado no vidro padrão | inativo: Usado no vidro padrão |  | faithful · dark_fill | CM, SC, PT |
| `kgrim` | Contorno | Bloqueio | Bordas e reflexos · standard | float · 0–4 · 2 | hooks/HeadsUpHooks | ao vivo | ativo | inativo: Usado no vidro padrão | inativo: Usado no vidro padrão | edge | faithful · rim | CM, SC, PT |
| `kgglint` | Reflexo | Bloqueio | Bordas e reflexos · standard | float · 0–1 · 0.10 | hooks/HeadsUpHooks | ao vivo | ativo | inativo: Usado no vidro padrão | inativo: Usado no vidro padrão | glint | faithful · glint | CM, SC, PT |
| `textswap` | Texto adaptativo | Pop-up, Bloqueio, Central | Cor e contraste · standard | boolean · 1 | glass/ShadeTone, hooks/HeadsUpHooks | ao vivo | ativo | ativo | ativo |  | faithful · text_swap | CM, PT |
| `headertone` | Ajustar relógio e data | Central | Cor e contraste · advanced | boolean · 1 | hooks/HeadsUpHooks | ao vivo | ativo | ativo | ativo |  | behavior | CM |
| `icontone` | Ajustar ícone pequeno | Pop-up, Bloqueio, Central | Cor e contraste · advanced | boolean · 1 | hooks/Foreground | ao vivo | ativo | ativo | ativo |  | behavior | CM |
| `popuplock` | Contraste fixo ao chegar | Pop-up | Comportamento · advanced | boolean · 1 | hooks/HeadsUpHooks | ao vivo | ativo | ativo | ativo |  | behavior | CM |
| `orphandraw` | Tom certo desde o primeiro quadro | Central | Comportamento · advanced | boolean · 1 | hooks/HeadsUpHooks | ao vivo | ativo | ativo | ativo |  | behavior | CM |
| `popuptone` | Contraste medido no pop-up | Pop-up | Cor e contraste · standard | boolean · 1 | glass/BackgroundBlurBridge, hooks/HeadsUpHooks | ao vivo | ativo | ativo | inativo: Usado no vidro padrão | tonemap | faithful · veil | CM, PT |
| `shadescaleveil` | Véu durante animações | Central | Cor e contraste · advanced | int · 0–255 · 115 | glass/SemBlurBridge | ao vivo | inativo: Sem efeito na proteção Adaptativa | inativo: Sem efeito na proteção Adaptativa | inativo: Sem efeito na proteção Adaptativa |  | behavior | CM, SC |
| `shadelocal` | Medir cada cartão | Central | Cor e contraste · advanced | boolean · 0 | hooks/HeadsUpHooks | ao vivo | ativo | ativo | ativo |  | behavior | CM |
| `paneldim` | Escurecer o painel | Central | Cor e contraste · standard | float · 0–0.4 · 0.12 | hooks/HeadsUpHooks | ao vivo | ativo | ativo | ativo |  | faithful · panel_dim | CM, SC, PT |
| `headergap` | Espaço acima do título | Pop-up, Bloqueio, Central | Comportamento · advanced | float · 0–8 · 4 | hooks/HeaderGapHooks | ao vivo | ativo | ativo | ativo |  | faithful · header_gap | CM, SC, PT |
| `sf.optics` | Versão óptica anunciada | Pop-up, Bloqueio, Central | Técnico · internal | readonly · 0 | sfhook/oulg_sf.c, hooks/HeadsUpHooks, hooks/QuickSettingsEdgeLight | leitura | leitura | leitura | leitura |  | none | CM, CV |
| `perf` | Medir tempo dos ganchos | Pop-up, Bloqueio, Central | Técnico · technical | boolean · 0 | diagnostics/Perf | ao vivo | ativo | ativo | ativo |  | none | CM |
| `trace` | Registro detalhado de janelas | Pop-up, Bloqueio, Central | Técnico · technical | boolean · 0 | diagnostics/Probe | ao vivo | ativo | ativo | ativo |  | none | CM |
| `sf.core` | Desfoque interno | Pop-up | Lente dinâmica · advanced | float · 0–128 · 48 | sfhook/oulg_sf.c | reinício | inativo: Usado com a lente dinâmica | ao reiniciar | inativo: Usado com a lente dinâmica |  | didactic · core_blur | CM, CV, LH, SC, PT |
| `sf.taps` | Amostras do pop-up | Pop-up | Técnico · technical | int · 1–64 · 24 | sfhook/oulg_sf.c | reinício | inativo: Usado com a lente dinâmica | ao reiniciar | inativo: Usado com a lente dinâmica |  | none | CM, CV, LH, SC |
| `sf.ramp` | Rampa do pop-up | Pop-up | Técnico · technical | float · 0.1–4 · 1.5 | sfhook/oulg_sf.c | reinício | inativo: Usado com a lente dinâmica | ao reiniciar | inativo: Usado com a lente dinâmica |  | none | CM, CV, LH, SC |
| `sf.hubloom` | Contenção de brilho | Pop-up | Lente dinâmica · advanced | float · 0–2 · 0 | sfhook/oulg_sf.c | reinício | inativo: Usado com a lente dinâmica | ao reiniciar | inativo: Usado com a lente dinâmica |  | behavior | CM, CV, LH, SC |
| `sf.edgeblur` | Desfoque no contorno | Pop-up | Técnico · technical | float · 0–48 · 0 | sfhook/oulg_sf.c | reinício | inativo: Usado com a lente dinâmica | ao reiniciar | inativo: Usado com a lente dinâmica |  | none | CM, CV, LH, SC |
| `sf.kgcore` | Desfoque interno | Bloqueio | Lente dinâmica · advanced | float · 0–128 · 80 | sfhook/oulg_sf.c | reinício | inativo: Usado com a lente dinâmica | ao reiniciar | inativo: Usado com a lente dinâmica |  | didactic · core_blur | CM, CV, LH, SC, PT |
| `sf.kgtaps` | Amostras do bloqueio | Bloqueio | Técnico · technical | int · 1–64 · 48 | sfhook/oulg_sf.c | reinício | inativo: Usado com a lente dinâmica | ao reiniciar | inativo: Usado com a lente dinâmica |  | none | CM, CV, LH, SC |
| `sf.kgramp` | Rampa do bloqueio | Bloqueio | Técnico · technical | float · 0.1–4 · 1.5 | sfhook/oulg_sf.c | reinício | inativo: Usado com a lente dinâmica | ao reiniciar | inativo: Usado com a lente dinâmica |  | none | CM, CV, LH, SC |
| `sf.kgsat` | Saturação | Bloqueio | Lente dinâmica · advanced | float · 0–2.5 · 1.25 | sfhook/oulg_sf.c | reinício | inativo: Usado com a lente dinâmica | ao reiniciar | inativo: Usado com a lente dinâmica |  | didactic · saturation | CM, CV, LH, SC, PT |
| `sf.kgtone` | Curva de destaque | Bloqueio | Lente dinâmica · advanced | float · 0–1 · 0.15 | sfhook/oulg_sf.c | reinício | inativo: Usado com a lente dinâmica | ao reiniciar | inativo: Usado com a lente dinâmica |  | didactic · flatten | CM, CV, LH, SC, PT |
| `sf.kgrim` | Reflexo do aro | Bloqueio | Lente dinâmica · advanced | float · 0–2 · 0.72 | sfhook/oulg_sf.c | reinício | inativo: Usado com a lente dinâmica | ao reiniciar | inativo: Usado com a lente dinâmica |  | didactic · glint | CM, CV, LH, SC, PT |
| `sf.kgspec` | Brilho especular | Bloqueio | Lente dinâmica · advanced | float · 0–1 · 0.19 | sfhook/oulg_sf.c | reinício | inativo: Usado com a lente dinâmica | ao reiniciar | inativo: Usado com a lente dinâmica |  | didactic · glint | CM, CV, LH, SC, PT |
| `sf.kgflat` | Uniformidade | Bloqueio | Lente dinâmica · advanced | float · 0–1 · 0.50 | sfhook/oulg_sf.c | reinício | inativo: Usado com a lente dinâmica | ao reiniciar | inativo: Usado com a lente dinâmica |  | didactic · flatten | CM, CV, LH, SC, PT |
| `sf.kglevel` | Nível da uniformidade | Bloqueio | Lente dinâmica · advanced | float · 0–1 · 0.40 | sfhook/oulg_sf.c | reinício | inativo: Usado com a lente dinâmica | ao reiniciar | inativo: Usado com a lente dinâmica |  | didactic · flatten | CM, CV, LH, SC, PT |
| `sf.kgcool` | Tom frio | Bloqueio | Lente dinâmica · advanced | float · 0–1 · 0.50 | sfhook/oulg_sf.c | reinício | inativo: Usado com a lente dinâmica | ao reiniciar | inativo: Usado com a lente dinâmica |  | didactic · cool | CM, CV, LH, SC, PT |
| `sf.kgtilt` | Direção da luz | Bloqueio | Lente dinâmica · advanced | float · 0–3.141593 · 1.42 | sfhook/oulg_sf.c | reinício | inativo: Usado com a lente dinâmica | ao reiniciar | inativo: Usado com a lente dinâmica |  | didactic · light_angle | CM, CV, LH, SC, PT |
| `sf.kgline` | Contorno | Bloqueio | Lente dinâmica · advanced | float · 0–2 · 0.62 | sfhook/oulg_sf.c | reinício | inativo: Usado com a lente dinâmica | ao reiniciar | inativo: Usado com a lente dinâmica |  | didactic · rim | CM, CV, LH, SC, PT |
| `sf.kgcorecap` | Teto do desfoque interno | Bloqueio | Técnico · technical | float · 0–128 · 52 | sfhook/oulg_sf.c | reinício | inativo: Usado com a lente dinâmica | ao reiniciar | inativo: Usado com a lente dinâmica |  | none | CM, CV, LH, SC |
| `sf.kggradient` | Transição vidro-miolo | Bloqueio | Técnico · technical | float · 0–1 · 1 | sfhook/oulg_sf.c | reinício | inativo: Usado com a lente dinâmica | ao reiniciar | inativo: Usado com a lente dinâmica |  | none | CM, CV, LH, SC |
| `sf.kgband` | Largura da transição óptica | Bloqueio | Técnico · technical | float · 0–1 · 0.85 | sfhook/oulg_sf.c | reinício | inativo: Usado com a lente dinâmica | ao reiniciar | inativo: Usado com a lente dinâmica |  | none | CM, CV, LH, SC |
| `sf.kgbodylens` | Lente do miolo | Bloqueio | Técnico · technical | float · 0–0.15 · 0.05 | sfhook/oulg_sf.c | reinício | inativo: Usado com a lente dinâmica | ao reiniciar | inativo: Usado com a lente dinâmica |  | none | CM, CV, LH, SC |
| `sf.kgblurexp` | Curva do desfoque na transição | Bloqueio | Técnico · technical | float · 1–4 · 2 | sfhook/oulg_sf.c | reinício | inativo: Usado com a lente dinâmica | ao reiniciar | inativo: Usado com a lente dinâmica |  | none | CM, CV, LH, SC |
| `sf.kgflatcap` | Limite da uniformidade | Bloqueio | Técnico · technical | float · 0–1 · 0.22 | sfhook/oulg_sf.c | reinício | inativo: Usado com a lente dinâmica | ao reiniciar | inativo: Usado com a lente dinâmica |  | didactic · flatten | CM, CV, LH, SC, PT |
| `sf.kgcoredim` | Escurecer o miolo | Bloqueio | Lente dinâmica · advanced | float · 0–1 · 0.04 | sfhook/oulg_sf.c | reinício | inativo: Usado com a lente dinâmica | ao reiniciar | inativo: Usado com a lente dinâmica |  | didactic · core_dim | CM, CV, LH, SC, PT |
| `sf.kgglow` | Largura do brilho do aro | Bloqueio | Lente dinâmica · advanced | float · 0–2 · 0.70 | sfhook/oulg_sf.c | reinício | inativo: Usado com a lente dinâmica | ao reiniciar | inativo: Usado com a lente dinâmica |  | didactic · rim | CM, CV, LH, SC, PT |
| `sf.shadecap` | Limite para texto claro (lente) | Central | Lente dinâmica · advanced | float · 0.2–1 · 0.55 | sfhook/oulg_sf.c | reinício | inativo: Usado com a lente dinâmica | inativo: Use o mapa de tons da lente na central | inativo: Usado com a lente dinâmica |  | didactic · veil | CM, CV, LH, SC, PT |
| `sf.shadelift` | Limite para texto escuro (lente) | Central | Lente dinâmica · advanced | float · 0–0.8 · 0.45 | sfhook/oulg_sf.c | reinício | inativo: Usado com a lente dinâmica | inativo: Use o mapa de tons da lente na central | inativo: Usado com a lente dinâmica |  | didactic · veil | CM, CV, LH, SC, PT |
| `sf.hulight` | Luz pela borda | Pop-up | Lente dinâmica · advanced | float · 0–1 · 0.40 | sfhook/oulg_sf.c | reinício | inativo: Usado com a lente dinâmica | ao reiniciar | inativo: Usado com a lente dinâmica |  | behavior | CM, CV, LH, SC |
| `sf.kglight` | Luz pela borda | Bloqueio | Lente dinâmica · advanced | float · 0–1 · 0.35 | sfhook/oulg_sf.c | reinício | inativo: Usado com a lente dinâmica | ao reiniciar | inativo: Usado com a lente dinâmica |  | behavior | CM, CV, LH, SC |
| `sf.shadelight` | Luz pela borda (lente) | Central | Lente dinâmica · advanced | float · 0–1 · 0.30 | sfhook/oulg_sf.c | reinício | inativo: Usado com a lente dinâmica | ao reiniciar | inativo: Usado com a lente dinâmica |  | behavior | CM, CV, LH, SC |
| `sf.qslens` | Refração dos atalhos | Central | Lente dinâmica · advanced | float · 0–0.5 · 0.30 | sfhook/oulg_sf.c | reinício | inativo: Usado com a lente dinâmica | ao reiniciar | inativo: Usado com a lente dinâmica |  | behavior | CM, CV, LH, SC |
| `sf.qsbody` | Fundo refratado dos atalhos | Central | Técnico · technical | float · 0–1 · 0.35 | sfhook/oulg_sf.c | reinício | inativo: Usado com a lente dinâmica | ao reiniciar | inativo: Usado com a lente dinâmica |  | none | CM, CV, LH, SC |
| `sf.qsdepth` | Alcance da luz nos atalhos | Central | Técnico · technical | float · 1.5–48 · 18 | sfhook/oulg_sf.c | reinício | inativo: Usado com a lente dinâmica | ao reiniciar | inativo: Usado com a lente dinâmica |  | none | CM, CV, LH, SC |
| `sf.debug` | Tinta de diagnóstico | Pop-up, Bloqueio, Central | Técnico · technical | boolean · 0 | sfhook/oulg_sf.c | reinício | inativo: Usado com a lente dinâmica | ao reiniciar | inativo: Usado com a lente dinâmica |  | none | CM, CV, LH |
| `sf.norewrite` | Modo de reescrita | Pop-up, Bloqueio, Central | Técnico · internal | boolean · 0 | sfhook/oulg_sf.c | leitura | leitura | leitura | leitura |  | none | CM, CV |

## Interruptores de produto (fora de `debug.oulg.*`)

Guardados em `glass` SharedPreferences, entregues ao SystemUI pelo `ConfigProvider` (`/v1/snapshot`) e lidos por `ConfigClient.flag`; ausentes = ligado.

| Chave | Lido por | Efeito ao desligar | Padrão | Lente | Captura |
| --- | --- | --- | --- | --- | --- |
| `enabled` | SystemUIEntry, hooks/HeadsUpHooks (`enabled`) | todos os ambientes voltam ao fundo nativo | ativo | ativo | ativo |
| `surface.headsup.enabled` | hooks/HeadsUpHooks (`surfaceEnabled`, `eligible`) | Pop-up volta ao fundo nativo | ativo | ativo | ativo |
| `surface.headsup.feature.edge.enabled` | hooks/HeadsUpHooks (`feature("edge")`) | Pop-up: camada Bordas desligada | ativo | ativo | ativo |
| `surface.headsup.feature.glint.enabled` | hooks/HeadsUpHooks (`feature("glint")`) | Pop-up: camada Reflexo desligada | ativo | inativo: Usado no vidro padrão | inativo: Usado no vidro padrão |
| `surface.headsup.feature.lens.enabled` | hooks/HeadsUpHooks (`feature("lens")`) | Pop-up: camada Lente desligada | ativo | ativo | ativo |
| `surface.headsup.feature.tonemap.enabled` | hooks/HeadsUpHooks (`feature("tonemap")`) | Pop-up: camada Proteção de contraste desligada | ativo | ativo | ativo |
| `surface.keyguard.enabled` | hooks/HeadsUpHooks (`surfaceEnabled`, `eligible`) | Bloqueio volta ao fundo nativo | ativo | ativo | ativo |
| `surface.keyguard.feature.edge.enabled` | hooks/HeadsUpHooks (`feature("edge")`) | Bloqueio: camada Bordas desligada | ativo | ativo | ativo |
| `surface.keyguard.feature.glint.enabled` | hooks/HeadsUpHooks (`feature("glint")`) | Bloqueio: camada Reflexo desligada | ativo | inativo: Usado no vidro padrão | inativo: Usado no vidro padrão |
| `surface.keyguard.feature.lens.enabled` | hooks/HeadsUpHooks (`feature("lens")`) | Bloqueio: camada Lente desligada | inativo: Usado com a lente dinâmica | ativo | ativo |
| `surface.keyguard.feature.tonemap.enabled` | hooks/HeadsUpHooks (`feature("tonemap")`) | Bloqueio: camada Proteção de contraste desligada | ativo | ativo | ativo |
| `surface.shade.enabled` | hooks/HeadsUpHooks (`surfaceEnabled`, `eligible`) | Central volta ao fundo nativo | ativo | ativo | ativo |
| `surface.shade.feature.edge.enabled` | hooks/HeadsUpHooks (`feature("edge")`) | Central: camada Bordas desligada | ativo | ativo | ativo |
| `surface.shade.feature.glint.enabled` | hooks/HeadsUpHooks (`feature("glint")`) | Central: camada Reflexo desligada | ativo | inativo: Usado no vidro padrão | inativo: Usado no vidro padrão |
| `surface.shade.feature.tonemap.enabled` | hooks/HeadsUpHooks (`feature("tonemap")`) | Central: camada Proteção de contraste desligada | ativo | ativo | ativo |
| `surface.shade.feature.per_card_blur.enabled` | hooks/HeadsUpHooks (`feature("per_card_blur")`) | Central: camada Desfoque por cartão desligada | ativo | ativo | ativo |

## Funções sem propriedade

| Função | Onde | Verificação |
| --- | --- | --- |
| Notificação de teste (agora, 5 s, várias, remover, canal) | `TestNotifications`, `MainActivity.requestTest` | resultado sempre descrito (`TestNotifications.describe`); permissão negada, canal/app bloqueado e Não perturbe tratados |
| Estilos (salvar, usar com diferenças, padrão, desfazer) | `PresetsSheet`, `ConfigRepository.replace/undo` | `PresetCodecTest`; aplicar estilo não grava no compositor |
| Exportar/importar | `MainActivity`, `PresetCodec` | validação completa antes de escrever; diff antes de usar |
| Alterados fora do app | `AppModel.conflicts`, `InfoSheets.conflicts` | nunca sobrescreve sem escolha explícita |
| Comunicação com o sistema | `AppModel.sync`, `ConfigReadReceiver` | confirma leitura, não efeito visual |
| Lente dinâmica (verificar, instalar/testar, ativar, aplicar pendentes, remover, registro) | `LensSheet`, `SfLiveController`, `SfLiveFlow`, `oulg-live.sh` | `SfLiveFlowTest`, `CompositorValuesTest`, `live_check.sh` |
| Reiniciar a interface do sistema | `InfoSheets.restartSystemUi`, `RootAccess` | comando fixo, confirmação explícita |
| Busca e favoritos | `SearchSheet`, `ControlSheet` | todos os controles editáveis encontráveis; favoritos por ambiente |
