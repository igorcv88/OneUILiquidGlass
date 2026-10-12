# Port para One UI 9 Stable (S938BXXUCDZIF) — análise offline

Data: 2026-10-12. Tudo abaixo foi verificado contra os arquivos exatos do
firmware `S938BXXUCDZIF`; nada foi observado no aparelho.

O módulo foi desenvolvido e validado em `S938BXXUCZZIC` (One UI 9 Beta 3). A
Stable exige um port de firmware pequeno, não uma arquitetura nova.

## 1. Procedência do material

Os arquivos vieram do pacote `DZIF_FRAMEWORK_CORE_V3.tar.gz`
(SHA-256 `27da2d22fe66f4167a50845c9854ab553cddf7155c3924dc24cb10ce15b5d05d`,
conferido no download). Cada arquivo extraído foi conferido contra o
`MANIFEST_SHA256.txt` do próprio pacote:

| arquivo | SHA-256 DZIF | mudou vs ZZIC |
| --- | --- | --- |
| `/system/bin/surfaceflinger` | `f13befcbd7777b8d6e26c98d4454a90365f7404c532023b9b891796ad7384bf7` | sim |
| `/system_ext/priv-app/SystemUI/SystemUI.apk` | `801e74ef4e8acbdfd2dea70e2b5ab24f9917b2162f13b611b881f07533d4cea0` | sim |
| `/system/framework/services.jar` | `14e052a2d29af90c13ca609b5eacb1831333d16df706f31b61d58a61c17f13a2` | sim |
| `/system/framework/framework-res.apk` | `8d627476e05a231fb383b5d6e0886ea264ab8bb26734d9a4f57b94752f5904c4` | sim |
| `/system/framework/framework.jar` | `663b0469b19a5434cb3cef106d7d57065bf7bf3e5180efba07142b6f29f69133` | **não** (byte-identical) |
| `/system/bin/app_process64` | `08891284f79ebd9411d7ebd2e267bd2c083e8d5a02f2f192629fb126fabfeec8` | **não** (byte-identical) |

`framework.jar` idêntico é o fato mais útil para este módulo: as APIs de blur da
Samsung que ele usa (`View.semSetBlurInfo`, `SemBlurInfo.Builder` e os
`setRadius`/`setBackgroundColor`/`setColorCurve*`/`setBGColorAdjustment`) estão
no mesmo bytecode. `app_process64` idêntico vale para a cadeia LSPosed.

Os valores DZIF de `services.jar` e `framework-res.apk` acima fecham duas
lacunas do handoff de coleta, que pedia para lê-los do manifesto V3 em vez de
inventá-los.

## 2. Patch do SurfaceFlinger — reproduzido, não assumido

`sfhook/dtneeded.c` foi compilado e executado contra o binário DZIF exato. O
resultado é o esperado e idêntico ao comportamento em ZZIC:

```text
exit code                 0  (patch aplicado)
nome da biblioteca        SurfaceFlingerProp.so
bytes modificados         88
tamanho do arquivo        10286144 -> 10286144  (inalterado)
DT_DEBUG                  1 -> 0
DT_NEEDED                 55 -> 56
primeiro DT_NEEDED        libdisplayconfig.system.qti.so -> SurfaceFlingerProp.so
segunda execução          exit code 2 (já aplicado; idempotente)
```

A âncora `libSurfaceFlingerProp.so` está em `.dynstr` no offset `0x2cf5a`, e o
nome usado é âncora+3, por isso `SurfaceFlingerProp.so`. O binário DZIF também
continua importando os símbolos dinâmicos de que a arquitetura depende:

```text
glShaderSource  eglGetProcAddress  glCreateShader  glCompileShader
glGetUniformLocation
```

Ou seja: a técnica de injeção continua estruturalmente viável na Stable. Isso
**não** valida os shaders: os programas GLSL são gerados em tempo de execução,
e a reescrita de `FillRRect` só pode ser confirmada no aparelho, pelo dump em
`/data/misc/surfaceflinger/oulg_shaders.txt`.

## 3. Allowlist de firmware

`sfhook/module/customize.sh` tinha um único `EXPECTED` e por isso não conseguia
aceitar dois firmwares. Agora é uma lista, `ALLOWED_FINGERPRINTS`, uma linha por
`ro.build.fingerprint`. O suporte a ZZIC foi preservado, não substituído.

A iteração roda com `IFS` só em nova linha e com `set -f`, porque uma expansão
sem aspas faria glob de uma entrada que contivesse curinga contra o diretório
atual e compararia com o que isso casasse. Um `ro.build.fingerprint` vazio
agora também aborta, em vez de comparar string vazia.

Casos exercitados no host, com `getprop` e `abort` stubados:

| `ro.build.fingerprint` | resultado |
| --- | --- |
| ZZIC exato | aceito |
| `...S938BXXUCDZIF_OXMCDZIF...` (forma provável de DZIF) | recusado |
| `samsung/pa3qxxx/qssi_64:17/.../S938BXXUCDZIF:user/release-keys` | recusado |
| ZZI4 exato | recusado |
| vazio | recusado |
| ZZIC com espaço no fim | recusado |
| entrada da lista trocada por `*` | recusado (nada de glob) |

### Por que DZIF ainda não está na lista

**O `ro.build.fingerprint` exato de DZIF não foi lido.** Ele carrega o sufixo do
CSC — o de ZZIC é
`samsung/pa3qxxx/pa3q:17/CP2A.260605.016/S938BXXUCZZIC_OXMCZZIC:user/release-keys` —
e fica em `/vendor/build.prop`. O `vendor_a.img` não foi extraído no material
de coleta: `core_partitions.txt` lista apenas `product_a`, `system_a`,
`system_dlkm_a`, `system_ext_a` e `vendor_dlkm_a`. Os `build.prop` disponíveis
só trazem fingerprints de partição:

```text
ro.system.build.fingerprint=samsung/pa3qxxx/qssi_64:17/CP2A.260605.016/S938BXXUCDZIF:user/release-keys
ro.system_dlkm.build.fingerprint=samsung/pa3qxxx/pa3q:15/AP3A.240905.015.A2/S938BXXUCDZIF:user/release-keys
```

Nenhum dos dois é `Build.FINGERPRINT`. A forma que falta é óbvia a partir da
linha de ZZIC, e é exatamente por isso que não foi escrita: um palpite faria
este gate aceitar uma string que nenhum aparelho foi confirmado reportar, o que
anula a única função que ele tem. O gate é o que impede o módulo de patchear um
`surfaceflinger` que ninguém analisou.

Para fechar: rodar `getprop ro.build.fingerprint` num aparelho em DZIF (ou ler
`/vendor/build.prop` de um `vendor_a.img` extraído) e acrescentar a linha.
Depois disso, nada mais no módulo precisa mudar para instalar em DZIF.

## 4. SystemUI — o que a coleta já verificou

Reaproveitado da análise de coleta, não refeito aqui. O `SystemUI.apk` mudou,
mas os contratos que o código atual usa continuam presentes em DZIF:

- `NotificationBackgroundView.onDraw(Canvas)`, `setCustomBackground(...)`, e os
  campos `mBackground`, `mClipTopAmount`, `mClipBottomAmount`, `mActualHeight`,
  `mExpandAnimationRunning`;
- `NotificationPanelViewController.setExpandedHeightInternal(float)`, `mExpandedHeight`, `mView`;
- `ScrimView.setViewAlpha(float)`;
- `StatusBarStateControllerImpl.setState(int, boolean)`;
- `NotificationContentView`: `mExpandedChild`, `mContractedChild`,
  `mHeadsUpChild`, `mExpandedSmartReplyView`, `mNotificationMaxHeight`.

`ExpandableNotificationRow.setHeadsUp` e `setPinned` não foram encontrados. O
módulo já trata hooks opcionais como fail-soft e mantém
`HeadsUpManagerImpl`/`setEntryPinned` como rota de observação, então isso não é
bloqueio — mas é a primeira coisa a conferir no log de hooks depois do update.

## 5. O que falta, em ordem

1. Ler `ro.build.fingerprint` em DZIF e acrescentar a linha na allowlist.
2. Instalar e reiniciar; confirmar `logcat -s OULG_SF` e
   `getprop debug.oulg.sf.optics`.
3. Conferir o dump de shaders em
   `/data/misc/surfaceflinger/oulg_shaders.txt` — é o único jeito de saber se a
   reescrita de `FillRRect` pegou nos programas que a Stable gera.
4. Smoke test na ordem do handoff: hooks do SystemUI, `SurfaceFlingerProp`,
   descoberta de shader, captura compartilhada, blur Samsung, heads-up, tela de
   bloqueio, central de notificações, modo de economia máxima de energia.
5. Nenhuma mudança visual durante o port. Primeiro paridade funcional.
