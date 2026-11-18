#!/usr/bin/env python3
"""R6 planning tool: rebuild CONTROL_INVENTORY.csv from the live checkout.

Reads the generated SettingsRegistry (208 keys at 4f33114), the Controls catalogue, the
Applicability switch, GlassMaterial scopes, SfLive.WRITABLE, ProductFlags and scans readers in
Java (glass/, hooks/, live/, diagnostics/, config/ non-catalogue files) and native sources
(sfhook/*.c|*.h|*.sh). Decisions come from DECISIONS below (one row per key; the script fails if
a registry key has no decision or a decision names an unknown key), so coverage is verifiable:

    python docs/OULG_UI_PLAN/tools/build_inventory.py            # writes CONTROL_INVENTORY.csv
    python docs/OULG_UI_PLAN/tools/build_inventory.py --check    # exit 1 if the CSV is stale

Pure standard library; no network; read-only on the source tree.
"""
import csv, io, json, os, re, sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..', '..'))
PKG = os.path.join(ROOT, 'app/src/main/java/io/github/igorcv88/oneuiliquidglass')
OUT = os.path.join(ROOT, 'docs/OULG_UI_PLAN/CONTROL_INVENTORY.csv')


def read(p):
    with open(p, encoding='utf-8', errors='ignore') as f:
        return f.read()


# ---------------------------------------------------------------- registry
STR = r'"((?:[^"\\]|\\.)*)"'
REG = re.compile(r'add\(new SettingSpec\(' + ','.join([STR] * 5) + r',SettingSpec\.Type\.(\w+),' + STR +
                 r',([-0-9.]+),([-0-9.]+),([-0-9.]+),' + ','.join([STR] * 3) + r',(true|false),"(\w+)"\)\);')
registry = []
for m in REG.finditer(read(os.path.join(PKG, 'config/SettingsRegistry.java'))):
    k, g, sc, ti, ex, ty, de, mn, mx, st, un, ch, dep, adv, pv = m.groups()
    registry.append(dict(key=k, group=g, scope=sc, title=ti, explanation=ex, type=ty, default=de, min=mn, max=mx,
                         step=st, unit=un, choices=ch, deps=dep, advanced=adv, preview=pv))

# ---------------------------------------------------------------- Controls catalogue
ctl_src = read(os.path.join(PKG, 'config/Controls.java'))
controls = {}
for chunk in ctl_src[ctl_src.index('static {'):].split('put(c(')[1:]:
    m = re.match(r'\s*"([^"]+)",\s*"([^"]*)"', chunk)
    if not m:
        continue
    body = chunk[:chunk.find(');\n')]
    sec = re.search(r'\.in\((\w+),\s*(\w+)\)', body)
    on = 'ALL' if '.all()' in body else ','.join(x.strip() for x in re.findall(r'\.on\(([^)]*)\)', body))
    fmt = re.search(r'\.fmt\(Format\.(\w+)\)', body)
    auto = re.search(r'\.auto\("([^"]*)"\)', body)
    feat = re.search(r'\.feature\("([^"]*)"\)', body)
    controls[m.group(1)] = dict(title=m.group(2), section=sec.group(1) if sec else 'TECH',
                                tier=sec.group(2) if sec else 'TEC', surfaces=on,
                                format=fmt.group(1) if fmt else 'PLAIN', auto=auto.group(1) if auto else '',
                                feature=feat.group(1) if feat else '')
primary = set(re.findall(r'"((?:sf\.)?[a-z][a-z0-9.]*)"', ctl_src[ctl_src.index('CLEAR_PRIMARY_HU'):ctl_src.index('public static Map<String, String> layers')]))
titles_en = json.loads(read(os.path.join(ROOT, 'tools/control-title-translations.json')))
writable = set('sf.' + x for x in re.findall(r'"([a-z0-9]+)"', read(os.path.join(PKG, 'live/SfLive.java')).split('WRITABLE =')[1].split(');')[0]))
gm = read(os.path.join(PKG, 'config/GlassMaterial.java'))
def lst(name):
    return set('sf.' + x for x in re.findall(r'"([a-z0-9]+)"', gm.split(name + ' = Arrays.asList(')[1].split(');')[0]))
clear_only = lst('HU_CLEAR') | lst('KG_CLEAR')
frosted_only = lst('FROSTED_ONLY')
app_src = read(os.path.join(PKG, 'config/Applicability.java'))
icon_keys = set(re.findall(r'"([a-z]+)"', read(os.path.join(PKG, 'config/IconSettings.java'))))

# ---------------------------------------------------------------- readers
EXCL = ('config/Controls.java', 'config/SettingsRegistry.java', 'ui/', 'config/Applicability.java',
        'config/GlassMaterial.java', 'config/ValueFormat', 'config/Scale', 'config/PresetCodec', 'config/IconSettings')
java = {}
for d, _, fs in os.walk(PKG):
    for f in fs:
        if f.endswith('.java'):
            p = os.path.join(d, f).replace(os.sep, '/')
            java[p[len(PKG.replace(os.sep, '/')) + 1:]] = read(p).splitlines()
native = {}
for d, _, fs in os.walk(os.path.join(ROOT, 'sfhook')):
    for f in fs:
        p = os.path.join(d, f).replace(os.sep, '/')
        if f.endswith(('.c', '.h', '.sh', '.prop')) or '.' not in f:
            native[p[len(ROOT.replace(os.sep, '/')) + 1:]] = read(p).splitlines()


def readers(key):
    jr, ui = [], []
    q = '"' + key + '"'
    for rel, ls in java.items():
        for i, l in enumerate(ls):
            if q in l:
                (ui if any(e in rel for e in EXCL) else jr).append(f'{rel}:{i + 1}')
    rx = re.compile(r'debug\.oulg\.' + re.escape(key) + r'(?![a-z0-9])')
    nr = [f'{rel}:{i + 1}' for rel, ls in native.items() for i, l in enumerate(ls) if rx.search(l)]
    return jr, ui, nr


# ---------------------------------------------------------------- decisions
# key | destination | group | product_control_id | decision | note
# destination: MAIN (route-dependent principal), DETAIL (Detalhes da aparência), BEHAVIOR (comportamento da
# superfície), ICONS (página Ícones), QS_MAIN/QS_DETAIL (bloco Central de controle), TECHNICAL (Configurações ›
# Técnico), DIAGNOSTIC (read-only state), LEGACY (sem efeito; só import/export), MATERIAL (seletor de material).
D = """
liveradius|MAIN|blur|blur|map|Desfoque principal no vidro padrão (pop-up; bloqueio em Automático).
liveshaderadius|MAIN|blur|blur|map|Desfoque principal da central no vidro padrão.
kgblurradius|MAIN|blur|blur|map|Desfoque principal do bloqueio (padrão e lente com caminho desenhado); Automático preservado.
semradiuslens|DETAIL|blur|system_blur|relocate|Desfoque do sistema sob a lente; série com o desfoque do compositor; não é duplicata.
semradius|DETAIL|blur|system_blur|relocate|Desfoque do sistema nos cartões sem lente (central com lente).
livedarkfill|MAIN|tone|dark_fill|keep|Escurecimento principal no vidro padrão (pop-up).
kgdarkfill|MAIN|tone|dark_fill|keep|Escurecimento principal no vidro padrão (bloqueio).
livedarkalpha|DETAIL|tone|dark_glow|relocate|Brilho do vidro escuro.
kgdarkalpha|DETAIL|tone|dark_glow|relocate|Brilho do vidro escuro (bloqueio).
lightglass|MAIN|tone|light_opacity|keep|Opacidade do vidro claro; depende de Texto adaptativo.
shadelightglass|MAIN|tone|light_opacity|keep|Opacidade do vidro claro da central.
shadedarkglass|MAIN|tone|dark_opacity|keep|Opacidade do vidro escuro da central.
mediaart|DETAIL|tone|media_art|relocate|Capa de mídia; aparência própria da central.
shadeblur|BEHAVIOR|blur|per_card_blur|relocate|Liga/desliga desfoque por cartão (comportamento, não intensidade).
pillglass|DETAIL|blur|overflow_glass|relocate|Vidro no resumo de ícones do bloqueio.
pillblur|DETAIL|blur|overflow_blur|relocate|Desfoque do resumo de ícones.
semalpha|TECHNICAL|samsung|-|relocate|Véu nativo da Samsung; parâmetro de backend.
sflens|DETAIL|refraction|edge_refraction|relocate|Refração da borda (tag da lente); difere de hurefract (escala de toda a refração no shader).
kglens|DETAIL|refraction|edge_refraction|relocate|Refração da borda no bloqueio; Igual ao pop-up preservado.
liverim|MAIN|light|outline|keep|Contorno no vidro padrão.
kgrim|MAIN|light|outline|keep|Contorno no vidro padrão (bloqueio).
popuplinegain|DETAIL|light|line_light|relocate|Luz da linha externa Java; independente do contorno.
kglinegain|DETAIL|light|line_light|relocate|Luz da linha externa Java (bloqueio).
shadelinegain|DETAIL|light|line_light|relocate|Luz da linha externa Java (central).
liveglint|MAIN|light|glint|keep|Reflexo (vidro padrão).
kgglint|MAIN|light|glint|keep|Reflexo (vidro padrão, bloqueio).
shadeglint|MAIN|light|glint|keep|Reflexo da central.
liveoptics|DETAIL|light|edge_intensity|relocate|Intensidade das bordas (vidro padrão).
livesheen|DETAIL|light|top_sheen|relocate|Brilho no topo.
lightoutline|DETAIL|light|light_outline|relocate|Contorno no vidro claro.
lightedge|DETAIL|light|light_edge_shadow|relocate|Sombra da borda no vidro claro.
kgoptics|TECHNICAL|lens-profile|-|relocate|Escolha de perfil óptico (backend).
kgsharedoptics|TECHNICAL|comparison|-|relocate|Comparação aprovada; não é intenção estética.
huoptics|TECHNICAL|lens-profile|-|relocate|Prévia de perfil; backend.
veilmax|DETAIL|contrast|contrast_veil|relocate|Proteção de contraste.
popuptone|DETAIL|contrast|measured_contrast|relocate|Contraste medido no pop-up.
textswap|DETAIL|contrast|adaptive_text|relocate|Texto adaptativo (pai de lightglass/lighttone).
shadetone|DETAIL|contrast|legibility|relocate|Proteção de legibilidade da central.
paneldim|DETAIL|contrast|panel_dim|relocate|Escurecer o painel.
panelblurcap|MAIN|blur|panel_blur|keep|Desfoque do fundo da central (painel).
panelnativegray|DETAIL|tone|panel_gray|relocate|Véu cinza nativo.
panelcolor|DETAIL|tone|panel_color|relocate|Cor do fundo da central.
lighttone|DETAIL|tone|light_tone|relocate|Cor do vidro claro.
stylelo|DETAIL|contrast|style_threshold|relocate|Limiar claro/escuro (par com stylehi).
stylehi|DETAIL|contrast|style_threshold|relocate|Limiar claro/escuro (par com stylelo).
shadecap|DETAIL|contrast|text_limits|relocate|Limite para texto claro.
shadelift|DETAIL|contrast|text_limits|relocate|Limite para texto escuro.
headertone|BEHAVIOR|contrast|header_tone|relocate|Ajustar relógio e data.
icontone|ICONS|icons|-|keep|Página Ícones.
iconmode|ICONS|icons|-|keep|Página Ícones.
iconcardsize|ICONS|icons|-|keep|Página Ícones.
statusiconsize|ICONS|icons|-|keep|Página Ícones.
statusmaxicons|ICONS|icons|-|keep|Página Ícones; #196 fora de escopo.
iconinkmode|ICONS|icons|-|keep|Página Ícones.
iconfixedink|ICONS|icons|-|keep|Página Ícones.
iconlightink|ICONS|icons|-|keep|Página Ícones.
icondarkink|ICONS|icons|-|keep|Página Ícones.
statusinkmode|ICONS|icons|-|keep|Página Ícones.
statusfixedink|ICONS|icons|-|keep|Página Ícones.
statuslightink|ICONS|icons|-|keep|Página Ícones.
statusdarkink|ICONS|icons|-|keep|Página Ícones.
shadescaleveil|BEHAVIOR|contrast|scale_veil|relocate|Véu durante animações.
shadelocal|BEHAVIOR|contrast|local_measure|relocate|Medir cada cartão.
popuplock|BEHAVIOR|contrast|stable_contrast|relocate|Contraste estável.
orphandraw|BEHAVIOR|contrast|first_frame_tone|relocate|Tom desde o primeiro quadro.
headergap|BEHAVIOR|layout|header_gap|relocate|Espaço acima do título.
shadefadecut|BEHAVIOR|timing|fade_cut|relocate|Corte do desfoque ao sumir.
hufadecut|BEHAVIOR|timing|fade_cut|relocate|Lente do pop-up na entrada (#205 aprovado; -1 herda).
kgwinblur|BEHAVIOR|blur|window_blur|relocate|Desfoque da janela no bloqueio parado.
livelens|TECHNICAL|captured-band|-|relocate|Faixa de lente capturada: rota experimental.
lens|TECHNICAL|captured-band|-|relocate|Curvatura da faixa capturada.
rimblur|TECHNICAL|captured-band|-|relocate|Desfoque da faixa capturada.
lensmaxage|TECHNICAL|captured-band|-|relocate|Temporização de captura.
lenssettle|TECHNICAL|captured-band|-|relocate|Temporização de captura.
lenstransition|TECHNICAL|captured-band|-|relocate|Temporização de captura.
lensgate|TECHNICAL|captured-band|-|relocate|Proteção contra movimento.
sf.core|DETAIL|blur|center_blur|relocate|Desfoque do centro (Fosco).
sf.hublur|MAIN|blur|blur|map|Desfoque principal com lente: multiplica o desfoque do compositor (borda e centro) nos dois materiais.
sf.hubodylens|DETAIL|refraction|body_refraction|relocate|Refração difusa do miolo (Fosco).
sf.hurefract|MAIN|refraction|refraction|map|Refração principal com lente: multiplica toda a refração do compositor nos dois materiais.
sf.hubloom|DETAIL|highlights|bloom_containment|relocate|Contenção de brilho (pesos de amostras claras); não é dispersão.
sf.edgeblur|DETAIL|blur|edge_blur|relocate|Desfoque da borda (Fosco).
sf.taps|TECHNICAL|kernel|-|relocate|Amostras.
sf.ramp|TECHNICAL|kernel|-|relocate|Rampa.
sf.kgcore|DETAIL|blur|center_blur|relocate|Desfoque do centro (Fosco, bloqueio).
sf.kgblur|MAIN|blur|blur|map|Desfoque principal com lente (bloqueio).
sf.kgrefract|MAIN|refraction|refraction|map|Refração principal com lente (bloqueio).
sf.kgedgeblur|DETAIL|blur|edge_blur|relocate|Desfoque da borda (Fosco, bloqueio).
sf.kgsat|DETAIL|color|saturation|relocate|Saturação (Fosco, bloqueio).
sf.kgcool|DETAIL|color|cool_tone|relocate|Tom frio (Fosco).
sf.kgline|DETAIL|light|outline|relocate|Contorno do compositor (bloqueio, lente).
sf.kgfixedrim|TECHNICAL|comparison|-|relocate|Comparação A/B; default 1 aprovado.
sf.kgrim|DETAIL|light|rim_reflection|relocate|Reflexo do aro.
sf.kgspec|DETAIL|light|specular|relocate|Brilho especular.
sf.kgglow|DETAIL|light|rim_glow_width|relocate|Largura do brilho do aro.
sf.kgtilt|DETAIL|light|light_direction|relocate|Direção da luz.
sf.kgflat|DETAIL|color|uniformity|relocate|Uniformidade (Fosco).
sf.kglevel|DETAIL|color|uniformity_level|relocate|Nível da uniformidade.
sf.kgflatcap|TECHNICAL|kernel|-|relocate|Limite interno.
sf.kgtone|DETAIL|color|highlight_curve|relocate|Curva de destaque.
sf.kgcoredim|DETAIL|color|center_dim|relocate|Escurecer o miolo.
sf.kgcorecap|TECHNICAL|kernel|-|relocate|Teto interno do desfoque.
sf.kggradient|TECHNICAL|kernel|-|relocate|Transição contínua (referência v0.21 em 0).
sf.kgband|DETAIL|refraction|edge_band|relocate|Largura da transição óptica (Fosco).
sf.kgbodylens|DETAIL|refraction|body_refraction|relocate|Refração difusa do miolo (Fosco).
sf.kgblurexp|TECHNICAL|kernel|-|relocate|Curva do desfoque.
sf.kgtaps|TECHNICAL|kernel|-|relocate|Amostras.
sf.kgramp|TECHNICAL|kernel|-|relocate|Rampa.
sf.shadecap|DETAIL|contrast|lens_text_limits|relocate|Limite para texto claro (lente).
sf.shadelift|DETAIL|contrast|lens_text_limits|relocate|Limite para texto escuro (lente).
sf.hulight|DETAIL|light|edge_light|relocate|Luz pela borda (compositor).
sf.kglight|DETAIL|light|edge_light|relocate|Luz pela borda (compositor, bloqueio).
sf.kgdepth|DETAIL|light|light_reach|relocate|Alcance da luz.
sf.shadelight|DETAIL|light|edge_light|relocate|Luz pela borda (central, lente).
qs.lightstyle|LEGACY|qs|-|retire-ui|Lido e descartado (QsStyle.prop); só import/export.
qs.lightfill|QS_MAIN|qs|qs_light_fill|keep|Central de controle.
qs.lightcolor|QS_DETAIL|qs|qs_light_color|keep|Central de controle.
qs.lightglassopacity|QS_MAIN|qs|qs_glass_opacity|keep|Central de controle.
qs.lightglasscolor|QS_DETAIL|qs|qs_glass_color|keep|Central de controle.
qs.lightoptics|QS_MAIN|qs|qs_lens_light|keep|Central de controle.
qs.tilelight|QS_MAIN|qs|qs_adaptive_discs|keep|Pai de qs.tilelight*.
qs.hideoffdiscs|QS_MAIN|qs|qs_hide_off_discs|keep|Pai negativo de *off*.
qs.tilelighton|QS_DETAIL|qs|qs_disc_colors|keep|Cor; depende de qs.tilelight.
qs.tilelightoff|QS_DETAIL|qs|qs_disc_colors|keep|Cor; depende de qs.tilelight e !qs.hideoffdiscs.
qs.tilelightoffalpha|QS_DETAIL|qs|qs_disc_colors|keep|Opacidade; idem.
qs.tilelightonink|QS_DETAIL|qs|qs_disc_colors|keep|Cor.
qs.tilelightoffink|QS_DETAIL|qs|qs_disc_colors|keep|Cor.
qs.tiledark|QS_DETAIL|qs|qs_dark_discs|keep|Pai de qs.tiledark*.
qs.tiledarkon|QS_DETAIL|qs|qs_disc_colors|keep|Cor.
qs.tiledarkoff|QS_DETAIL|qs|qs_disc_colors|keep|Cor.
qs.tiledarkoffalpha|QS_DETAIL|qs|qs_disc_colors|keep|Opacidade.
qs.tiledarkonink|QS_DETAIL|qs|qs_disc_colors|keep|Cor.
qs.tiledarkoffink|QS_DETAIL|qs|qs_disc_colors|keep|Cor.
qs.darkrim|QS_MAIN|qs|qs_outline|keep|Contorno sobre fundo colorido.
qs.lightrim|QS_MAIN|qs|qs_outline|keep|Contorno sobre fundo claro.
qs.glint|QS_DETAIL|qs|qs_glint|keep|Reflexo extra.
qs.sliderlightcolor|QS_DETAIL|qs|qs_slider_color|keep|Cor do preenchimento.
qs.sliderdarkcolor|QS_DETAIL|qs|qs_slider_color|keep|Cor do preenchimento.
qs.outer|QS_DETAIL|qs|qs_slider_size|keep|Espessura do slider (geometria R5 aprovada).
qs.track|LEGACY|qs|-|retire-ui|Lido e descartado; só import/export.
qs.fill|QS_DETAIL|qs|qs_slider_size|keep|Espessura do preenchimento.
qs.darkfill|LEGACY|qs|-|retire-ui|Lido e descartado; só import/export.
qs.darkcolor|LEGACY|qs|-|retire-ui|Lido e descartado; só import/export.
qs.idle|LEGACY|qs|-|retire-ui|Lido e descartado; só import/export.
qs.active|LEGACY|qs|-|retire-ui|Lido e descartado; só import/export.
sf.qslight|DIAGNOSTIC|qs|-|keep|Somente leitura (sem escrita pelo app; não está em WRITABLE).
sf.qslens|QS_DETAIL|qs|qs_edge_refraction|keep|Refração da borda (compositor, perfil 3).
sf.qsbody|QS_DETAIL|qs|qs_refracted_backdrop|keep|Fundo refratado.
sf.qsdepth|QS_DETAIL|qs|qs_light_reach|keep|Alcance da luz.
qs.edgeblur|QS_DETAIL|qs|qs_edge_blur|keep|Desfoque da borda (lente).
sf.debug|TECHNICAL|diagnostic|-|relocate|Tinta de diagnóstico; nunca favorito.
backdrop|TECHNICAL|route|-|relocate|Origem do fundo (seleciona rota CAPTURE/PLAIN).
liveblur|TECHNICAL|route|-|relocate|Vidro padrão on/off (rota).
profile|TECHNICAL|captured-band|-|relocate|Modelo de refração da captura.
refract|TECHNICAL|captured-band|-|relocate|Multiplicador Snell.
ior|TECHNICAL|captured-band|-|relocate|Índice de refração.
disp|TECHNICAL|captured-band|-|relocate|Dispersão RGB (cromática), não dispersão de realces.
rimhz|TECHNICAL|captured-band|-|relocate|Frequência.
blur|TECHNICAL|capture|-|relocate|Desfoque da captura.
sat|TECHNICAL|capture|-|relocate|Saturação da captura.
hz|TECHNICAL|capture|-|relocate|Frequência.
hzkg|TECHNICAL|capture|-|relocate|Frequência.
tint|TECHNICAL|capture|-|relocate|Véu da captura.
semcurve|TECHNICAL|samsung|-|relocate|Curva Samsung.
semshape|TECHNICAL|samsung|-|relocate|Região Samsung.
kgblurpath|TECHNICAL|route|-|relocate|Caminho de blur (pai de kgblurradius/pill com lente).
shadeblurpath|TECHNICAL|route|-|relocate|Caminho de blur.
hublurpath|TECHNICAL|route|-|relocate|Caminho de blur.
perf|TECHNICAL|diagnostic|-|relocate|Métricas; custo.
trace|TECHNICAL|diagnostic|-|relocate|Registro detalhado; custo.
edgepremul|TECHNICAL|diagnostic|-|relocate|Experimento P3.
sfrefract|DIAGNOSTIC|route|-|keep|Helper-owned; read-only.
sf.optics|DIAGNOSTIC|diagnostic|-|keep|Versão anunciada; não prova compilação.
sf.norewrite|DIAGNOSTIC|diagnostic|-|keep|Read-only perigoso.
sf.clear|DIAGNOSTIC|diagnostic|-|keep|Modos compilados.
sf.huclear|MATERIAL|material|material|keep|Seletor via MaterialSheet; valor 2 (por cartão) somente Técnico.
sf.kgclear|MATERIAL|material|material|keep|Seletor via MaterialSheet; valor 2 somente Técnico.
sf.hcfold|MAIN|shape|meniscus|keep|Menisco: principal do Transparente.
sf.hcband|DETAIL|refraction|edge_band|relocate|Largura da borda curva.
sf.hcamp|DETAIL|refraction|edge_curvature|relocate|Curvatura sem menisco (inativo com menisco>0).
sf.hcbody|DETAIL|refraction|body_magnification|relocate|Ampliação do corpo (soma limitada com hcbody2).
sf.hcbody2|DETAIL|refraction|body_dome|relocate|Abaulamento do centro.
sf.hcedge|DETAIL|blur|edge_blur|relocate|Desfoque da borda (Transparente); multiplicado por sf.hublur.
sf.hccore|DETAIL|blur|center_blur|relocate|Desfoque do centro (Transparente); multiplicado por sf.hublur.
sf.hcblurexp|DETAIL|blur|blur_transition|relocate|Transição do desfoque.
sf.hcadapt|DETAIL|blur|adaptive_blur|relocate|Desfoque adaptativo (pai de hchlneutral e da regra de hubloom).
sf.hckernel|TECHNICAL|kernel|-|relocate|Algoritmo do desfoque adaptativo; não é intenção visual.
sf.hcsat|MAIN|color|saturation|keep|Saturação (Transparente).
sf.hcscrim|DETAIL|color|scrim|relocate|Escurecimento nas áreas escuras.
sf.hcscrimmax|DETAIL|color|scrim|relocate|Escurecimento nas áreas claras.
sf.hclift|MAIN|color|brightening|keep|Clareamento: nome, fórmula e comportamento preservados; acima dos realces.
sf.hchlneutral|DETAIL|highlights|highlight_neutrality|relocate|Neutralidade dos realces (nova).
sf.hcneutral|DETAIL|highlights|classic_white_neutrality|relocate|Neutralidade clássica dos brancos (legado ativo com bloco de material); abaixo de H.
sf.kcfold|MAIN|shape|meniscus|keep|Menisco (bloqueio).
sf.kcband|DETAIL|refraction|edge_band|relocate|Largura da borda curva.
sf.kcamp|DETAIL|refraction|edge_curvature|relocate|Curvatura sem menisco.
sf.kcbody|DETAIL|refraction|body_magnification|relocate|Ampliação do corpo.
sf.kcbody2|DETAIL|refraction|body_dome|relocate|Abaulamento do centro.
sf.kcedge|DETAIL|blur|edge_blur|relocate|Desfoque da borda (Transparente, bloqueio).
sf.kccore|DETAIL|blur|center_blur|relocate|Desfoque do centro (Transparente, bloqueio).
sf.kcblurexp|DETAIL|blur|blur_transition|relocate|Transição do desfoque.
sf.kcsat|MAIN|color|saturation|keep|Saturação (Transparente, bloqueio).
sf.kclift|MAIN|color|brightening|keep|Clareamento (bloqueio).
sf.kcveil|DETAIL|color|level_veil|relocate|Véu de nivelamento (pai de kclevel).
sf.kclevel|DETAIL|color|level_veil|relocate|Nível do véu.
sf.kcdim|DETAIL|color|glass_dim|relocate|Escurecimento do vidro.
sf.kcknee|DETAIL|highlights|highlight_compression|relocate|Compressão de realces.
sf.kccool|DETAIL|color|cool_tone|relocate|Tom frio.
energymode|ENERGY|energy|energy_mode|keep|Nova (T15): política de bateria; 0 = nunca suspender.
energythreshold|ENERGY|energy|energy_threshold|keep|Nova (T15).
energyhysteresis|ENERGY|energy|energy_hysteresis|keep|Nova (T15).
energycharging|ENERGY|energy|energy_charging|keep|Nova (T15).
energysurfaces|ENERGY|energy|energy_surfaces|keep|Nova (T15); máscara de superfícies.
energysaver|ENERGY|energy|energy_follow_saver|keep|Nova (T15); comportamento antigo opt-in.
"""
DECISIONS = {}
for line in D.strip().splitlines():
    k, dest, grp, pid, dec, note = line.split('|', 5)
    if k in DECISIONS:
        raise SystemExit('duplicate decision ' + k)
    DECISIONS[k] = (dest, grp, pid, dec, note)

DEST_LABEL = {'MAIN': 'Principais da superfície (por rota)', 'DETAIL': 'Detalhes da aparência',
              'BEHAVIOR': 'Comportamento da superfície', 'ICONS': 'Página Ícones', 'QS_MAIN': 'Central de controle › principais',
              'QS_DETAIL': 'Central de controle › detalhes', 'TECHNICAL': 'Configurações › Técnico',
              'DIAGNOSTIC': 'Configurações › Diagnóstico (somente leitura)', 'LEGACY': 'Sem UI (preservado em import/export)',
              'MATERIAL': 'Seletor de material', 'ENERGY': 'Configurações › Energia'}
SENTINEL = {'kgblurradius': '-1=Automático', 'semradiuslens': '-1=igual a semradius', 'semradius': '-1=spec (180 px)',
            'kglens': '-1=igual ao pop-up', 'hufadecut': '-1=herda shadefadecut', 'tint': '-1=GlassSpec',
            'semalpha': '-1=spec', 'panelblurcap': '-1=nativo', 'statusmaxicons': '0=sistema', 'mediaart': 'ver Controls.off',
            'sf.hublur': '1=identidade (multiplicador)', 'sf.hurefract': '1=identidade', 'sf.kgblur': '1=identidade',
            'sf.kgrefract': '1=identidade', 'sf.hcsat': '1=identidade', 'sf.kcsat': '1=identidade',
            'sf.hclift': '0=identidade', 'sf.kclift': '0=identidade', 'sf.hchlneutral': '0=identidade',
            'sf.hubloom': '0=disco simples', 'sf.hcfold': '0=sem menisco', 'sf.kcfold': '0=sem menisco',
            'sf.huclear': '0 Fosco,1 Transparente,2 por cartão', 'sf.kgclear': '0 Fosco,1 Transparente,2 por cartão'}


def route(key):
    if key in clear_only:
        return 'LENS_CLEAR'
    if key in frosted_only:
        return 'LENS_FROSTED'
    if key.startswith('sf.'):
        return 'LENS(any material)'
    m = re.search(r'case "' + re.escape(key) + r'":(?:\s*case "[^"]+":)*\s*(?:\{[^}]*?)?return ([^;]+);', app_src)
    if m:
        expr = m.group(1)
        if 'standard ? ACTIVE' in expr or 'NEEDS_STANDARD)' in expr and 'standard' in expr:
            return 'STANDARD'
        if 'lens ? ACTIVE' in expr:
            return 'LENS'
        if 'capture ? ACTIVE' in expr:
            return 'CAPTURE'
        if 'band ?' in expr:
            return 'STANDARD+livelens|CAPTURE'
    return 'ALL'


def predicate(key):
    m = re.search(r'(case "' + re.escape(key) + r'":[^\n]*\n(?:\s*case "[^"]+":\s*\n)*)([^\n]+\n(?:[^\n]+\n){0,4})', app_src)
    if not m:
        if key in clear_only or key in frosted_only:
            return 'materialRule: scope + DYNAMIC_LENS'
        return 'sf.* -> DYNAMIC_LENS' if key.startswith('sf.') else 'ACTIVE (default branch)'
    body = ' '.join(m.group(2).split())
    return body[:220]


def status(key, jr, nr, dest):
    if dest == 'LEGACY':
        return 'dead'
    if dest == 'DIAGNOSTIC':
        return 'diagnostic'
    if dest == 'TECHNICAL' and key in ('perf', 'trace', 'sf.debug', 'edgepremul'):
        return 'diagnostic'
    if not jr and not nr:
        return 'dead'
    if key == 'sf.hcneutral':
        return 'legacy'
    return 'attested'


FIELDS = ['key', 'legacy_aliases', 'label_pt', 'label_en', 'scope', 'current_tier', 'current_entrypoints',
          'registry_type', 'units', 'default', 'min', 'max', 'step', 'sentinel', 'route_material', 'readers',
          'activation_predicate', 'dependencies', 'affects_what', 'effective_formula', 'apply_channel',
          'saved_vs_compiled', 'status', 'overlap_group', 'proposed_product_control_id', 'proposed_destination',
          'decision', 'migration', 'rollback', 'rationale', 'evidence', 'tests']

FORMULA = {
    'sf.hublur': 'r_compositor = curve(edge,core,blurexp) * hublur; native region blur (semradiuslens) separate, before the shader',
    'sf.kgblur': 'r_compositor = curve(edge,core,blurexp) * kgblur',
    'sf.hurefract': 'shift = field(...) * hurefract (Clear: oulg_clear0()*refract; Frosted: base field)',
    'sf.kgrefract': 'shift = field(...) * kgrefract',
    'sf.hcedge': 'r(edge) = hcedge * hublur (+adaptive kernel when hcadapt>0)',
    'sf.hccore': 'r(core) = hccore * hublur',
    'sf.edgeblur': 'r(edge) = edgeblur * hublur', 'sf.core': 'r(core) = core * hublur',
    'sf.hclift': 'material: c * (1 + lift) (refract.h oulg_hu material block)',
    'sf.hchlneutral': 'mix toward luma by hlneutral*L*S*C; emitted only if hcadapt>0 and hlneutral>0',
    'sf.hcneutral': 'legacy near-white pull, emitted only if hcsat!=1 or scrimmax>0 or lift>0',
    'sf.hubloom': 'w = exp(-2r^2)*exp(-6*bloom*max(luma-lc-0.10,0)); compiled out on Clear kernel 1-3 with hcadapt>0',
    'sf.hcamp': 'clear amplitude min(amp, amax(rho,body,body2)); ignored when hcfold>0 (meniscus field)',
    'sf.hcbody': 'amax = ((1-M)/rho - body - 3*body2)/1.5', 'sf.hcbody2': 'see sf.hcbody',
    'liveradius': 'BackgroundBlurDrawable radius px (Tuning.liveRadius, clamp 0..400)',
    'kgblurradius': '-1 -> liveradius (standard) or semradiuslens (lens); else px',
    'semradiuslens': '-1 -> semradius; native Samsung region radius under tagged cards',
}


def main(check):
    keys = [r['key'] for r in registry]
    if len(keys) not in (208, 214):
        raise SystemExit(f'registry has {len(keys)} keys; plan expects 208 (baseline) or 214 (after T15)')
    energy = {k for k, d in DECISIONS.items() if d[0] == 'ENERGY'}
    missing = [k for k in keys if k not in DECISIONS]
    extra = [k for k in DECISIONS if k not in keys and not (len(keys) == 208 and k in energy)]
    if missing or extra:
        raise SystemExit(f'decision coverage: missing={missing} extra={extra}')
    buf = io.StringIO()
    w = csv.DictWriter(buf, FIELDS, lineterminator='\n')
    w.writeheader()
    for r in registry:
        k = r['key']
        c = controls.get(k, {})
        jr, ui, nr = readers(k)
        dest, grp, pid, dec, note = DECISIONS[k]
        entry = []
        if c:
            entry.append('SurfacesPage' if c.get('tier') in ('STD', 'ADV') and c.get('section') != 'TECH' else 'SurfacesPage(Técnico)')
            entry.append('SearchSheet(technical)')
            if c.get('tier') != 'INTERNAL':
                entry.append('SearchSheet')
            entry.append('ControlSheet')
            if k in primary:
                entry.append('Controls.primary')
        if k in icon_keys:
            entry.append('IconsPage')
        if k.startswith('sf.hc') or k.startswith('sf.kc') or k in ('sf.huclear', 'sf.kgclear'):
            entry.append('MaterialSheet/recommendedClear' if k in GlassMaterial_recommended else 'MaterialSheet')
        entry.append('PresetCodec(import/export)')
        apply = 'READONLY' if r['type'] == 'READONLY' else ('SF_RECOMPILE (helper setprops + userspace restart)' if k.startswith('sf.') else
                ('helper-owned' if k == 'sfrefract' else ('LIVE staged until Apply ícones' if k in icon_keys else 'LIVE (ConfigProvider -> ConfigClient)')))
        svc = ('saved vs observed vs compiled baseline (CompositorValues)' if k in writable else
               ('observed only' if r['type'] == 'READONLY' or k == 'sfrefract' else 'saved -> provider snapshot -> SystemUI read ack'))
        tests = ('CompositorValuesTest;EffectiveStateTest;PublicationPlanTest' if k in writable else
                 'ProductControlsTest;EditGuardTest') + (';ControlInventoryCoverageTest')
        w.writerow({
            'key': k, 'legacy_aliases': '', 'label_pt': c.get('title', r['title']),
            'label_en': titles_en.get(c.get('title', ''), ''), 'scope': c.get('surfaces', r['scope']),
            'current_tier': c.get('tier', 'none') + '/' + c.get('section', 'none'), 'current_entrypoints': ';'.join(entry),
            'registry_type': r['type'], 'units': r['unit'] or c.get('format', ''), 'default': r['default'], 'min': r['min'],
            'max': r['max'], 'step': r['step'], 'sentinel': SENTINEL.get(k, ''), 'route_material': route(k),
            'readers': ';'.join(jr + nr) or 'none', 'activation_predicate': predicate(k), 'dependencies': r['deps'],
            'affects_what': r['explanation'][:200], 'effective_formula': FORMULA.get(k, ''), 'apply_channel': apply,
            'saved_vs_compiled': svc, 'status': status(k, jr, nr, dest), 'overlap_group': grp,
            'proposed_product_control_id': pid, 'proposed_destination': DEST_LABEL[dest] + ' [' + dest + ']',
            'decision': dec, 'migration': 'none: key, domain and stored value unchanged' if dec != 'retire-ui' else 'kept in registry; hidden from UI; import/export unchanged',
            'rollback': 'revert ProductControls/CONTROL_MAPPING entry; Controls tier unchanged', 'rationale': note,
            'evidence': 'SettingsRegistry.java; Controls.java; Applicability.java' + ('; GlassMaterial.java' if k in clear_only | frosted_only else ''),
            'tests': tests})
    text = buf.getvalue()
    if check:
        cur = read(OUT) if os.path.exists(OUT) else ''
        if cur != text:
            raise SystemExit('CONTROL_INVENTORY.csv is stale; rerun build_inventory.py')
        print('inventory up to date:', len(keys), 'keys')
        return
    with open(OUT, 'w', encoding='utf-8', newline='') as f:
        f.write(text)
    by = {}
    for k in keys:
        by[DECISIONS[k][0]] = by.get(DECISIONS[k][0], 0) + 1
    print('wrote', OUT, len(keys), 'keys', json.dumps(by, sort_keys=True))


GlassMaterial_recommended = set(re.findall(r'm\.put\("([^"]+)"', gm))
if __name__ == '__main__':
    main('--check' in sys.argv)
