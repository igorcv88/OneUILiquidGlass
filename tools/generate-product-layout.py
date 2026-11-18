#!/usr/bin/env python3
"""Generate config/product/ProductLayout.java from docs/OULG_UI_PLAN/CONTROL_MAPPING.json.

The mapping is the single source of the product layout (which control is main, detail,
behavior, technical...). Stored keys, domains and defaults are never touched here. The output
is written with LF endings so the committed file does not depend on the platform.
"""
import csv
import json
import re
from pathlib import Path

root = Path(__file__).resolve().parents[1]
mapping = json.loads((root / 'docs/OULG_UI_PLAN/CONTROL_MAPPING.json').read_text(encoding='utf-8'))
copy_ids = {r['id'] for r in csv.DictReader(open(root / 'docs/OULG_UI_PLAN/COPY_PT_EN.csv', encoding='utf-8', newline=''))}
registry = re.findall(
    r'new SettingSpec\("([^"]+)"',
    (root / 'app/src/main/java/io/github/igorcv88/oneuiliquidglass/config/SettingsRegistry.java').read_text(encoding='utf-8'))

ROUTES = {'S': 'STANDARD', 'F': 'LENS_FROSTED', 'C': 'LENS_CLEAR', 'X': 'CAPTURE', 'P': 'PLAIN_BLUR'}
ALL_ROUTES = ['STANDARD', 'LENS_FROSTED', 'LENS_CLEAR', 'LENS_MIXED', 'CAPTURE', 'PLAIN_BLUR']
SURFACES = {'headsup': 'HEADSUP', 'keyguard': 'KEYGUARD', 'shade': 'SHADE'}


def fail(message):
    raise SystemExit('product layout: ' + message)


def split(entry):
    """'key@F,C' -> (key, {route tokens}); no suffix means every route."""
    key, _, tokens = entry.partition('@')
    toks = set(tokens.split(',')) if tokens else set(ROUTES)
    bad = toks - set(ROUTES)
    if bad:
        fail(f'unknown route token {sorted(bad)} in {entry}')
    return key, toks


def lens_route(surface, route):
    # The shade has no material: under the lens it uses the frosted list.
    return 'LENS_FROSTED' if surface == 'shade' and route == 'LENS_CLEAR' else route


def resolve(route, surface, entries):
    """The keys of one details list for a route (LENS_MIXED: frosted first, then clear-only)."""
    if route == 'LENS_MIXED':
        out = resolve('LENS_FROSTED', surface, entries)
        return out + [k for k in resolve('LENS_CLEAR', surface, entries) if k not in out]
    token = next(t for t, r in ROUTES.items() if r == lens_route(surface, route))
    return [k for k, toks in map(split, entries) if token in toks]


def resolve_main(route, surface, main):
    if route == 'LENS_MIXED':
        out = resolve_main('LENS_FROSTED', surface, main)
        if surface == 'shade':
            return out
        return out + [k for k in resolve_main('LENS_CLEAR', surface, main) if k not in out]
    route = lens_route(surface, route)
    token = next(t for t, r in ROUTES.items() if r == route)
    return list(main.get(token, main.get('F' if route.startswith('LENS') else 'S', [])))


groups = sorted(mapping['groups'], key=lambda g: g['order'])
group_ids = [g['id'] for g in groups]
for gid in group_ids:
    if 'group_' + gid not in copy_ids:
        fail(f'missing copy for group_{gid}')

# ---- validation: every registry key lives in exactly one category (surfaces / technical / icons / energy)
categories = {}


def place(category, key):
    categories.setdefault(key, set()).add(category)


for sid, spec in mapping['surfaces'].items():
    if sid not in SURFACES:
        fail(f'unknown surface {sid}')
    in_details = {}
    for route_token, keys in spec['main'].items():
        if route_token not in ROUTES:
            fail(f'unknown main route {route_token} on {sid}')
        for key in keys:
            place('surfaces', key)
    for gid, entries in spec['details'].items():
        if gid not in group_ids:
            fail(f'unknown group {gid} on {sid}')
        for entry in entries:
            key, _ = split(entry)
            if key in in_details:
                fail(f'{key} appears twice in the details of {sid} ({in_details[key]}, {gid})')
            in_details[key] = gid
            place('surfaces', key)
    for key in spec['behavior']:
        place('surfaces', key)
    quick = spec.get('quick_settings', {})
    for key in quick.get('main', []) + quick.get('details', []):
        place('surfaces', key)
tech = mapping['technical']
for gid, keys in tech['groups'].items():
    if 'technical_group_' + gid.replace('-', '_') not in copy_ids:
        fail(f'missing copy for technical group {gid}')
    for key in keys:
        place('technical', key)
for key in tech['read_only'] + tech['legacy_hidden'] + tech['material_selectors']:
    place('technical', key)
for key in mapping['icons_page']:
    place('icons', key)
for key in mapping['energy_keys']:
    place('energy', key)

registry_set = set(registry)
unknown = set(categories) - registry_set - set(mapping['energy_keys'])
missing = registry_set - set(categories)
if unknown or missing:
    fail(f'unknown={sorted(unknown)} missing={sorted(missing)}')
clash = {k: sorted(c) for k, c in categories.items() if len(c) > 1}
if clash:
    fail(f'a key is in more than one category: {clash}')
for key, title in mapping['product_titles'].items():
    if title not in copy_ids:
        fail(f'product title {title} of {key} has no copy row')
    if key not in registry_set:
        fail(f'product title for unknown key {key}')
for key in mapping['display']:
    if key not in registry_set:
        fail(f'display for unknown key {key}')


def destination_table():
    dest = {}
    for spec in mapping['surfaces'].values():
        quick = spec.get('quick_settings', {})
        for key in spec['behavior']:
            dest.setdefault(key, 'BEHAVIOR')
        for key in quick.get('details', []):
            dest.setdefault(key, 'QS_DETAIL')
        for key in quick.get('main', []):
            dest[key] = 'QS_MAIN'
        for entries in spec['details'].values():
            for entry in entries:
                dest[split(entry)[0]] = 'DETAIL'
    # Main wins when a shared key is main on one surface and a detail on another.
    for spec in mapping['surfaces'].values():
        for keys in spec['main'].values():
            for key in keys:
                dest[key] = 'MAIN'
    for gid, keys in tech['groups'].items():
        for key in keys:
            dest[key] = 'DIAGNOSTIC' if gid == 'diagnostic' else 'TECHNICAL'
    for key in tech['read_only']:
        dest[key] = 'DIAGNOSTIC'
    for key in tech['legacy_hidden']:
        dest[key] = 'LEGACY'
    for key in tech['material_selectors']:
        dest[key] = 'MATERIAL'
    for key in mapping['icons_page']:
        dest[key] = 'ICONS'
    for key in mapping['energy_keys']:
        dest[key] = 'ENERGY'
    return dest


def q(text):
    return json.dumps(text)


def strs(keys):
    return ', '.join(q(k) for k in keys)


def listing(name, keys):
    return f'    private static final List<String> {name} = Collections.unmodifiableList(Arrays.asList({strs(keys)}));'


out = []
w = out.append
w('// GENERATED by tools/generate-product-layout.py from docs/OULG_UI_PLAN/CONTROL_MAPPING.json. Do not edit.')
w('package io.github.igorcv88.oneuiliquidglass.config.product;')
w('')
w('import io.github.igorcv88.oneuiliquidglass.R;')
w('import io.github.igorcv88.oneuiliquidglass.config.Controls;')
w('')
w('import java.util.ArrayList;')
w('import java.util.Arrays;')
w('import java.util.Collections;')
w('import java.util.EnumMap;')
w('import java.util.HashMap;')
w('import java.util.List;')
w('import java.util.Map;')
w('')
w('/** Where each control lives in the product: main, detail group, behavior, technical... (presentation only). */')
w('public final class ProductLayout {')
w('    public enum Route { ' + ', '.join(ALL_ROUTES) + ' }')
w('')
w('    public enum Destination { MAIN, DETAIL, BEHAVIOR, QS_MAIN, QS_DETAIL, TECHNICAL, DIAGNOSTIC, LEGACY, ICONS, MATERIAL, ENERGY }')
w('')
w('    public enum Display { DEFAULT, MULTIPLIER_PERCENT }')
w('')
w('    /** A titled group of keys: a detail group or a technical group. */')
w('    public static final class Group {')
w('        public final String id;')
w('        public final int titleRes;')
w('        public final List<String> keys;')
w('')
w('        Group(String id, int titleRes, String... keys) {')
w('            this.id = id;')
w('            this.titleRes = titleRes;')
w('            this.keys = Collections.unmodifiableList(Arrays.asList(keys));')
w('        }')
w('    }')
w('')
w('    private static final Map<Controls.Surface, Map<Route, List<String>>> MAIN = new EnumMap<>(Controls.Surface.class);')
w('    private static final Map<Controls.Surface, Map<Route, List<Group>>> DETAILS = new EnumMap<>(Controls.Surface.class);')
w('    private static final Map<Controls.Surface, List<String>> BEHAVIOR = new EnumMap<>(Controls.Surface.class);')
w('    private static final Map<String, Destination> DESTINATIONS = new HashMap<>();')
w('    private static final Map<String, Display> DISPLAYS = new HashMap<>();')
w('    private static final Map<String, Integer> TITLES = new HashMap<>();')
w('')
w('    private static void main(Controls.Surface s, Route r, String... keys) {')
w('        MAIN.computeIfAbsent(s, k -> new EnumMap<>(Route.class)).put(r, Collections.unmodifiableList(Arrays.asList(keys)));')
w('    }')
w('')
w('    private static void details(Controls.Surface s, Route r, Group... groups) {')
w('        DETAILS.computeIfAbsent(s, k -> new EnumMap<>(Route.class)).put(r, Collections.unmodifiableList(Arrays.asList(groups)));')
w('    }')
w('')
w('    private static void dest(Destination d, String... keys) {')
w('        for (String k : keys) DESTINATIONS.put(k, d);')
w('    }')
w('')
w('    static {')
dest = destination_table()
for sid, spec in mapping['surfaces'].items():
    s = 'Controls.Surface.' + SURFACES[sid]
    for route in ALL_ROUTES:
        main = resolve_main(route, sid, spec['main'])
        w(f'        main({s}, Route.{route}, {strs(main)});')
        gl = []
        for gid in group_ids:
            keys = [k for k in resolve(route, sid, spec['details'].get(gid, [])) if k not in main]
            if keys:
                gl.append(f'new Group({q(gid)}, R.string.group_{gid}, {strs(keys)})')
        if gl:
            w(f'        details({s}, Route.{route},')
            w('                ' + ',\n                '.join(gl) + ');')
        else:
            w(f'        details({s}, Route.{route});')
    w(f'        BEHAVIOR.put({s}, Collections.unmodifiableList(Arrays.asList({strs(spec["behavior"])})));')
by_dest = {}
for key in sorted(dest):
    by_dest.setdefault(dest[key], []).append(key)
for d in sorted(by_dest):
    keys = by_dest[d]
    w(f'        dest(Destination.{d},')
    for i in range(0, len(keys), 6):
        chunk = keys[i:i + 6]
        w('                ' + strs(chunk) + (',' if i + 6 < len(keys) else ');'))
for key, mode in mapping['display'].items():
    w(f'        DISPLAYS.put({q(key)}, Display.{mode});')
for key, title in mapping['product_titles'].items():
    w(f'        TITLES.put({q(key)}, R.string.{title});')
w('    }')
w('')
quick = mapping['surfaces']['shade']['quick_settings']
w(listing('QUICK_MAIN', quick['main']))
w(listing('QUICK_DETAILS', quick['details']))
w(listing('READ_ONLY', tech['read_only']))
w(listing('LEGACY', tech['legacy_hidden']))
w(listing('MATERIAL_SELECTORS', tech['material_selectors']))
w(listing('ICONS', mapping['icons_page']))
w(listing('ENERGY', mapping['energy_keys']))
w('    private static final List<Group> TECHNICAL = Collections.unmodifiableList(Arrays.asList(')
tg = []
for gid in tech['order']:
    tg.append(f'            new Group({q(gid)}, R.string.technical_group_{gid.replace("-", "_")}, {strs(tech["groups"][gid])})')
w(',\n'.join(tg) + '));')
w('')
w('    private ProductLayout() {}')
w('')
w('    /** The always-visible controls of a surface on a route (a few rows). */')
w('    public static List<String> main(Controls.Surface s, Route r) {')
w('        return MAIN.get(s).get(r);')
w('    }')
w('')
w('    /** The appearance detail groups of a surface on a route, without what is already main. */')
w('    public static List<Group> details(Controls.Surface s, Route r) {')
w('        return DETAILS.get(s).get(r);')
w('    }')
w('')
w('    public static List<String> behavior(Controls.Surface s) {')
w('        return BEHAVIOR.get(s);')
w('    }')
w('')
w('    public static List<String> quickMain() {')
w('        return QUICK_MAIN;')
w('    }')
w('')
w('    public static List<String> quickDetails() {')
w('        return QUICK_DETAILS;')
w('    }')
w('')
w('    /** Technical groups in display order (Configurações › Técnico). */')
w('    public static List<Group> technical() {')
w('        return TECHNICAL;')
w('    }')
w('')
w('    public static List<String> readOnly() {')
w('        return READ_ONLY;')
w('    }')
w('')
w('    public static List<String> legacy() {')
w('        return LEGACY;')
w('    }')
w('')
w('    public static List<String> materialSelectors() {')
w('        return MATERIAL_SELECTORS;')
w('    }')
w('')
w('    public static List<String> icons() {')
w('        return ICONS;')
w('    }')
w('')
w('    public static List<String> energy() {')
w('        return ENERGY;')
w('    }')
w('')
w('    /** Where a key lives; null for a key the layout does not know. */')
w('    public static Destination destination(String key) {')
w('        return DESTINATIONS.get(key);')
w('    }')
w('')
w('    public static Display display(String key) {')
w('        Display d = DISPLAYS.get(key);')
w('        return d == null ? Display.DEFAULT : d;')
w('    }')
w('')
w('    /** The product title resource of a key; 0 means the existing control title. */')
w('    public static int productTitle(String key) {')
w('        Integer id = TITLES.get(key);')
w('        return id == null ? 0 : id;')
w('    }')
w('')
w('    /** Every key the layout knows, in no particular order (coverage checks). */')
w('    public static List<String> keys() {')
w('        return Collections.unmodifiableList(new ArrayList<>(DESTINATIONS.keySet()));')
w('    }')
w('}')
w('')
target = root / 'app/src/main/java/io/github/igorcv88/oneuiliquidglass/config/product/ProductLayout.java'
target.parent.mkdir(parents=True, exist_ok=True)
target.write_bytes('\n'.join(out).encode('utf-8'))
