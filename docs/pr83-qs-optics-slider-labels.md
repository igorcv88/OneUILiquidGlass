# QS pós-#82 — revisão visual DZIF (28/10/2026)

Dispositivo: SM-S938B, One UI 9 / Android 17 DZIF. Referências: três capturas com
central de controle clara/escura e central de notificações clara, após merge do #82.
Nenhuma mudança deste PR foi validada visualmente no aparelho.

## Correções isoladas

1. **Reflexo duplo no canto inferior direito:** `LiquidGlassShader.SOURCE` tinha o
   termo `inner` que cria um arco oposto ao da luz, a 34% do chanfro. O novo
   uniforme `innerGain` mantém o valor 1 nas notificações (`GlassDrawable`) e 0
   nas cápsulas QS (`QsCapsuleOptics`), preservando Fresnel, absorção, reflexo
   principal e iluminação pelo compositor. Isso remove o arco AGSL oposto; não
   comprova ausência de qualquer segunda borda no SF.
2. **Player:** removida a transformação de 6 dp (escala/translação) do conteúdo;
   toda a arte, controles e hitbox seguem a Samsung. A própria borda AGSL usa
   chanfro de 3 dp e intensidade reduzida, sem glint/sheen. O player não cria
   uma segunda região luminosa do compositor. `QS_MEDIA_EDGE_DRAWN insetDp=0`.
3. **Slider:** em preenchimento mais curto que a espessura, raio X = meia largura
   e raio Y = meia altura (`CornerRadius` elíptico); acima disso volta ao
   semicírculo normal da cápsula. O thumb não é redesenhado sobre o próprio
   corpo, evitando sobreposição de antialias. 0% permanece vazio; `qs.fill=0.85`.
4. **Labels:** somente texto neutro de tiles e demais labels QS recebe tinta
   opaca durante o desenho. O Paint nativo é restaurado logo em seguida;
   acentos, logotipos, círculos, ícones e cabeçalho seguem suas regras próprias.
5. **Relógio da central de notificações:** *não corrigido por falta de identidade
   confirmada do desenho real*. Com diagnóstico `trace` ativo, um Canvas que
   desenhe um texto no formato de hora fora dos nós registrados emite
   `QS_CLOCK_CANDIDATE` sem registrar o conteúdo. `HEADER_INK` agora inclui
   `shown`, `bounds` e `y` para descartar um relógio legado oculto. Esse log
   é diagnóstico, não um novo hook de alteração.

## Validação obrigatória no DZIF

- Abrir QS sobre fundo branco e escuro. Confirmar ausência do segundo arco,
  brilho da borda preservado e labels próximos ao preto/branco.
- Player com mídia e vazio: artwork deve tocar o contorno iluminado, sem recuo
  nem halo largo externo.
- Brilho e volume em 0, 1, 5, 15, 50 e 100%. As pontas precisam ser suaves,
  sem ombro plano/semi-pirâmide, e o arraste continuar fluido.
- Pressionar e arrastar um slider: nenhum contorno de outras cápsulas fica como
  fantasma (caminho de supressão não foi alterado neste PR).
- Abrir **somente** a central de notificações sobre fundo branco, com `trace`
  habilitado nas configurações avançadas do módulo. O relógio precisa ser
  correlacionado por `QS_HEADER_TEXT_DRAWN`, `QS_CLOCK_CANDIDATE` e `HEADER_INK`
  antes de mudar classes ou interceptar texto genérico.

## Coleta — arquivo único em Downloads

No Termux, após reproduzir os cinco cenários:

```sh
su -c 'logcat -d -v threadtime -s OULG AndroidRuntime DEBUG libc' \
  | grep -E 'QS_(OPTICS|MEDIA|SLIDER|TEXT|LABEL|CLOCK|HEADER|EDGE|BACKDROP)|HEADER_INK|SHADE_HEADER_TONE|AndroidRuntime|Fatal signal' \
  > /storage/emulated/0/Download/OULG_PR83_DZIF.txt
```

Se forem produzidos vídeos e várias capturas, criar **um único `.tar.gz`** com
o log e as mídias selecionadas; não enviar vários arquivos de texto soltos.

## Verificação automatizada

- `git diff --check`
- `python3 tools/test-distribution.py`
- `./gradlew testDebugUnitTest lintDebug assembleDebug`: **338 testes, 0 falhas,
  lint e APK debug OK**, com Android SDK 36, JDK 17 e proxy configurados apenas
  no ambiente temporário. Primeira tentativa dos testes falhou por download do
  Robolectric (`UnknownHostException`), não por falha de teste; após ajustar
  o proxy todos passaram.

Testes JVM e APK não substituem o diagnóstico da execução no hardware DZIF.
