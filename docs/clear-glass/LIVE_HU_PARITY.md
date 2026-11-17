# Paridade óptica: pop-up convencional × Live Update (CARD), 17/11

Estado: **duas divergências de código corrigidas, as demais documentadas; dono do pop-up do Gradus a confirmar por log.** O baseline óptico é o pop-up convencional depois do #207 (aprovado visualmente).

Legenda: (D) código, (U) relato/captura, (H) hipótese, (V) pendente no aparelho.

## Confirmar o dono do pop-up do Gradus (V)

Não presumo que o pop-up do Gradus seja o CARD (L2). O módulo já registra o dono, sem trace e sem instrumentação nova:
- **CARD:** `LIVE_RENDER_DRAW surface=CARD mode=OWN_REGION|REGIONLESS|NATIVE ...` (`LiveGlass`/`LiveGlassDrawable`, emitido a cada mudança de modo);
- **pop-up convencional:** `GLASS_APPLIED ... source=samsung|compositor` (`HeadsUpHooks`).

Com o pop-up do Gradus aberto: `su -c 'logcat -d -s OULG | grep -E "LIVE_RENDER_DRAW|GLASS_APPLIED" | tail -20'`. Se aparecer `surface=CARD`, as correções abaixo se aplicam a ele. Se aparecer `GLASS_APPLIED`, ele é uma linha de heads-up e a diferença está em outro ponto (estilo de template ou cor), não neste PR.

## Comparação das duas rotas (D)

| Item | Pop-up convencional (`HeadsUpHooks`) | CARD (`LiveGlass`/`LiveGlassDrawable`) |
|---|---|---|
| Região de blur | Samsung (`semSetBlurInfo`) ou drawable (`hublurpath`) | `BackgroundBlurDrawable` em modo lente (OWN_REGION), ou blur Samsung de um ancestral (REGIONLESS) |
| Perfil no SurfaceFlinger | tag 0 | tag 0 (`setOptics(false, true)`): **o mesmo** material Clear, menisco, corpo e luz |
| Raio nativo | `semradiuslens` (14) | era `kgblurradius` (12) → **agora `semradiuslens`** |
| Preenchimento | `semalpha` (0) substitui o alpha: nenhum | idem: os 42 % da `LiveSurface.CARD` não chegam à região com `semalpha=0` |
| Borda AGSL (Java) | `GlassDrawable` com o spec do pop-up e `notificationLineGain = popuplinegain` (1,3) | `GlassDrawable(new GlassSpec())` com ganho **1,0** → **agora `popuplinegain`** |
| Borda P3 | compatibilidade escolhida na criação e vigiada durante a entrada (modos 7/9) | não definida: no modo 7 (trace de 15:06) é igual; no 9 difere (não alterado) |
| Neutralização do #198 | — | `SRC_ATOP` sobre a cor da região: com `semalpha=0` o destino tem alpha 0 e **não desenha nada** |
| Borda do SF × Java | as duas rotas desenham a luz do perfil 0 no SF **e** a borda AGSL em Java (`compositorOwnsEdge` só cede a borda ao SF no perfil 1) | idem |

Leitura:
- Com a mesma tag, o corpo, o menisco, a refração e a luz do SF já são os mesmos.
- O que separava o delineado era a linha de contorno Java, 23 % mais fraca no CARD (1,0 contra 1,3).
- O raio nativo era outro (12 contra 14 px). É a mesma assimetria que o #203 corrigiu para a rota drawable do pop-up.
- A altura do cartão entra no campo pelo raio do canto e por `min(hs)`, iguais aos do pop-up de mesma geometria. Não há parâmetro de campo específico das Live Updates.

## Mudanças

1. **CARD:** a região usa `semradiuslens` (`BackgroundBlurBridge.useLensRadius`, o mesmo código do #203). Chip, Now Bar e toast continuam como estão.
2. **CARD:** a linha de contorno usa `popuplinegain`, que é ao vivo; o pré-desenho a reaplica sem custo quando não muda. Chip, Now Bar e toast continuam em 1,0.

Nada muda em blur, kernel, lift, cores semânticas, barra de progresso, ações ou atualização. Não há controle novo. `LiveCardParityTest` cobre as duas regras.

## Não alterado, com motivo

- **Compatibilidade P3 da borda.** O pop-up depende de um watcher de entrada (modos 7 ↔ 9). Replicá-lo no CARD é outra mudança, a fazer só se o log do aparelho mostrar o modo 9 com o CARD aberto.
- **Contorno duplo SF + Java.** É comum às duas rotas e foi aprovado no pop-up. Um segundo contorno **exterior** às superfícies é outra questão: geometria do #202, região × cartão e rota Samsung no `HU_RESIDUAL.md`. Para atribuí-lo é preciso o trace geométrico do #202 (`QS_EDGE_GEOMETRY`, `BLUR_REGION_GEOMETRY`, `SEM_REGION_GEOMETRY`).
- **42 % do CARD e o #198.** Não chegam à tela com `semalpha=0`. Alterá-los não muda nada nesta configuração.

## Teste e reversão

1. Instale o APK e reinicie o SystemUI; nenhuma mudança nativa.
2. Abra o pop-up do Gradus e confirme o dono pelo log acima.
3. Compare o delineado com o pop-up de teste sobre o mesmo fundo.

Reversão: o APK anterior.
