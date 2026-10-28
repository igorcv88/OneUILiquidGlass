from pathlib import Path
import re,json
root=Path(__file__).resolve().parents[1]; out=root/'app/src/main/java/io/github/igorcv88/oneuiliquidglass/config'
text=(root/'docs/PROPERTY_CATALOG_2026-10-26.md').read_text()
rows={}
for line in text.splitlines():
 m=re.match(r'\| `([a-z][a-z0-9.]*)` \| (.*?) \| (.*?) \|(?: (.*?) \|)?$',line)
 if not m: continue
 key,default,domain,desc=m.groups()
 if key in rows: continue
 if key=='sf.optics': default='0'; domain='readonly'; desc='Indicador de versão; não comprova reescrita ou execução do shader.'
 rows[key]=(default,domain,desc or domain)
assert len(rows)==123,len(rows)
# Literal bounds follow Tuning; native limits are deliberately conservative UI limits, not C clamps.
bools=set('livelens shadeblur sfrefract liveblur kgwinblur pillglass textswap headertone icontone popuptone popuplock orphandraw shadelocal perf trace sf.debug sf.norewrite'.split())
ints=set('qs.edgeblur rimhz hz hzkg tint lensmaxage lenssettle lenstransition liveradius liveshaderadius semradius semradiuslens semalpha kgblurpath shadeblurpath kgblurradius pillblur kgoptics huoptics livedarkalpha livedarkfill kgdarkalpha kgdarkfill shadescaleveil sf.taps sf.kgtaps'.split())
enums={'qs.lightstyle':'obsidian,light','backdrop':'auto,hybrid,capture,grid,off','profile':'lens,snell','lensgate':'directional,conservative','semcurve':'auto,spatial,dim,ultra,none','semshape':'auto,single,path,four,none','shadetone':'0,1,2,3'}
native={'core':(0,128),'taps':(1,64),'ramp':(.1,4),'hubloom':(0,2),'edgeblur':(0,48),'kgcore':(0,128),'kgtaps':(1,64),'kgramp':(.1,4),'kgsat':(0,2.5),'kgtone':(0,1),'kgrim':(0,2),'kgspec':(0,1),'kgflat':(0,1),'kglevel':(0,1),'kgcool':(0,1),'kgtilt':(0,3.141593),'kgline':(0,2),'kgcorecap':(0,128),'kgflatcap':(0,1),'kgcoredim':(0,1),'kgglow':(0,2),'kggradient':(0,1),'kgband':(0,1),'kgbodylens':(0,.15),'kgblurexp':(1,4),'shadecap':(.2,1),'shadelift':(0,.8),'hulight':(0,1),'kglight':(0,1),'shadelight':(0,1),'qslens':(0,.5),'qsbody':(0,1),'qsdepth':(1.5,48),'qslight':(0,2)}
# Clear human titles; descriptions and units remain the audited catalogue's text.
titles={'qs.darkfill':'Véu sobre fundo colorido (descontinuado)','qs.lightfill':'Tom sobre fundo claro','qs.darkcolor':'Cor do véu escuro (descontinuado)','qs.lightcolor':'Cor do tom sobre fundo claro','qs.idle':'Disco desligado (descontinuado)','qs.active':'Círculos ligados (descontinuado)','qs.darkrim':'Contorno sobre fundo colorido','qs.lightrim':'Contorno sobre fundo claro','qs.edgeblur':'Desfoque da borda','qs.outer':'Espessura do slider','qs.track':'Trilho antigo (descontinuado)','qs.fill':'Espessura do preenchimento','qs.lightstyle':'Estilo antigo (descontinuado)','sf.qslight':'Luz pela borda dos controles rápidos','sflens':'Refração da borda','liveradius':'Blur do pop-up','liveshaderadius':'Blur da central','kgblurradius':'Blur do bloqueio','liveglint':'Reflexo do pop-up','shadeglint':'Reflexo da central','kgglint':'Reflexo do bloqueio','liverim':'Contorno do pop-up','kgrim':'Contorno do bloqueio','veilmax':'Véu máximo de contraste','lightglass':'Transparência clara','shadelightglass':'Transparência clara da central','lighttone':'Cor do vidro claro','sfrefract':'Modo do compositor (helper)','liveblur':'Material APK-only','backdrop':'Origem do fundo','profile':'Modelo de refração','sf.optics':'Versão anunciada pelo compositor','shadetone':'Proteção de legibilidade','lensgate':'Proteção contra movimento','textswap':'Cor adaptativa do texto','headertone':'Cor do cabeçalho','icontone':'Cor dos ícones','popuplock':'Estabilizar contraste do pop-up','orphandraw':'Tom no primeiro desenho da central','popuptone':'Contraste do pop-up','paneldim':'Escurecimento do painel','headergap':'Espaço do cabeçalho','pillglass':'Vidro no overflow','pillblur':'Blur do overflow','shadeblur':'Blur por cartão','ior':'Índice de refração','sat':'Saturação','disp':'Dispersão RGB','lens':'Deslocamento da lente','refract':'Refração Snell','blur':'Blur da captura','rimhz':'Frequência da borda','rimblur':'Blur da borda','hz':'Frequência do pop-up','hzkg':'Frequência do bloqueio','tint':'Alpha do véu','lensmaxage':'Idade máxima da captura','lenssettle':'Espera após gesto','lenstransition':'Janela após transição','lensregiondiff':'Limiar de movimento','livelens':'Lente capturada','liveoptics':'Iluminação de borda','livesheen':'Brilho superior','semradius':'Blur Samsung','semradiuslens':'Blur sob a lente','semcurve':'Curva Samsung','semalpha':'Alpha Samsung','semshape':'Região Samsung','kgblurpath':'Caminho de blur do bloqueio','shadeblurpath':'Caminho de blur da central','shadefadecut':'Corte de blur no fade','kgwinblur':'Blur da janela no bloqueio','kgoptics':'Perfil óptico do bloqueio','huoptics':'Perfil do bloqueio no pop-up','kglens':'Força da lente do bloqueio','shadecap':'Teto de luminância','shadelift':'Piso de luminância','stylelo':'Limiar inferior de estilo','stylehi':'Limiar superior de estilo','lightoutline':'Contorno do vidro claro','lightedge':'Espessura da borda clara','livedarkalpha':'Alpha claro no pop-up escuro','livedarkfill':'Preenchimento do pop-up escuro','kgdarkalpha':'Alpha claro no bloqueio escuro','kgdarkfill':'Preenchimento do bloqueio escuro','shadescaleveil':'Véu durante escala','shadelocal':'Medir cada cartão','perf':'Métricas de tempo','trace':'Diagnóstico de árvores'}
q=lambda x:json.dumps(str(x),ensure_ascii=False)
lines=[]
for key,(default,domain,desc) in rows.items():
 sf=key.startswith('sf.') and key!='sf.optics'
 typ='READONLY' if key=='sf.optics' else 'BOOLEAN' if key in bools else 'ENUM' if key in enums else 'RGB' if key in ('lighttone','qs.darkcolor','qs.lightcolor') else 'INT' if key in ints else 'FLOAT'
 default=default.replace('`','').split(' ')[0]
 lo,hi=0,1
 if typ in ('FLOAT','INT'):
  if sf: lo,hi=native[key[3:]]
  else:
   m=re.search(r'(-?\d+(?:\.\d+)?)[–−](\d+(?:\.\d+)?)',domain)
   if m:lo,hi=map(float,m.groups())
   elif key=='kglens':lo,hi=-1,1.2
  if key=='kglens': lo,hi=-1,1.2
  if key=='liveradius': default='80'
  if key=='liveshaderadius': default='120'
 scope='shade' if key.startswith('qs.') else 'headsup' if key=='popuplock' else 'shade' if key=='orphandraw' else 'keyguard' if key.startswith(('kg','pill','sf.kg')) or key=='hzkg' else 'shade' if key.startswith(('shade','sf.shade','sf.qs')) or key in ('headertone','paneldim') else 'headsup' if key.startswith(('hu','sf.hu')) or key in ('liveglint','liverim','livedarkalpha','livedarkfill','sf.core','sf.taps','sf.ramp','sf.edgeblur') else 'all'
 group='Controles rápidos' if key.startswith('qs.') else 'Compositor' if sf else 'Diagnóstico' if key in ('sf.optics','trace','perf') else 'Captura' if key in 'backdrop profile lens refract rimhz rimblur ior blur sat disp hz hzkg tint livelens lensmaxage lenssettle lenstransition lensgate lensregiondiff'.split() else 'Cor e contraste' if key in 'shadetone shadecap shadelift veilmax stylelo stylehi lightglass lighttone shadelightglass livedarkalpha livedarkfill kgdarkalpha kgdarkfill textswap headertone icontone popuptone popuplock orphandraw shadescaleveil shadelocal paneldim semcurve semalpha'.split() else 'Bordas e luz' if key in 'liveoptics livesheen liveglint shadeglint liverim kgrim kgglint lightedge lightoutline sflens kglens kgoptics huoptics'.split() else 'Blur e material'
 unit='ms' if 'ms' in domain else 'Hz' if 'Hz' in domain else 'px' if 'px' in domain or sf and ('core' in key) else 'dp' if key=='headergap' else 'rad' if key=='sf.kgtilt' else ''
 advanced=sf or group in ('Captura','Diagnóstico') or key in ('semcurve','semshape','kgblurpath','shadeblurpath','kgwinblur','huoptics')
 title=titles.get(key, desc.split(': ',1)[-1].split(';')[0])
 dependency='sfrefract=1' if sf or key in ('qs.edgeblur','sflens','kglens','kgoptics','huoptics','semradiuslens') else 'liveblur=1;sfrefract=0;backdrop=auto' if key in ('livelens','liveradius','liveshaderadius','liveoptics','livesheen','liveglint','shadeglint','liverim','kgrim','kgglint','livedarkalpha','livedarkfill','kgdarkalpha','kgdarkfill') else 'backdrop=capture|hybrid|grid' if key in ('profile','lens','refract','ior','disp','blur','sat','hz','hzkg') else ''
 strategy='MATERIAL' if key in 'liveradius liveshaderadius kgblurradius sflens kglens sat lighttone lightglass shadelightglass liveglint shadeglint kgglint liverim kgrim veilmax livedarkalpha livedarkfill kgdarkalpha kgdarkfill sf.core sf.kgcore sf.kgsat'.split() else 'DIAGRAM'
 lines.append('        add(new SettingSpec('+','.join([q(key),q(group),q(scope),q(title),q(desc), 'SettingSpec.Type.'+typ,q(default),str(lo),str(hi),'1' if typ=='INT' else '.01',q(unit),q(enums.get(key,'')),q(dependency),'true' if advanced else 'false',q(strategy)])+'));')
(out/'SettingsRegistry.java').write_text('''package io.github.igorcv88.oneuiliquidglass.config;
import java.util.*;
/** Generated from the audited catalogue by tools/generate-settings.py. */
public final class SettingsRegistry {
    private static final Map<String, SettingSpec> SPECS = new LinkedHashMap<>();
    static {
'''+ '\n'.join(lines)+'''
    }
    private static void add(SettingSpec spec) { if (SPECS.put(spec.key, spec) != null) throw new IllegalStateException(spec.key); }
    public static SettingSpec get(String key) { return SPECS.get(key); }
    public static Collection<SettingSpec> all() { return Collections.unmodifiableCollection(SPECS.values()); }
    private SettingsRegistry() {}
}
''')
