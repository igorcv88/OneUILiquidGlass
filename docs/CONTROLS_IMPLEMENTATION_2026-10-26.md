# Painel de controles — implementação e validação

Integração sobre `main` após #57 e #56 (`c7e9fb7`). O bundle e o contrato de `docs/sf-live-app.md` são a fonte do fluxo SF. Não há download/ZIP/módulo KernelSU no novo botão.

## Controles e ponte

Registry tipado gerado do catálogo atualizado: **98 props ativas**. A base original tinha 97; os PRs paralelos removeram lensregiondiff/region e acrescentaram popuplock/orphandraw. Teste confronta exatamente os leitores Java/C e defaults. Não altera valores físicos do renderer.

ConfigRepository grava apenas overrides desejados. Default, observado (snapshot getprop sem root), desejado e efetivo desconhecido são distintos. Abrir o APK não instala padrões. Retornar do Terminal recarrega observações; baseline por chave detecta conflito. Falha de leitura não comprova ausência de override. Snapshots, presets JSON, reset, undo, diff e chaves ignoradas preservam os parâmetros SF como desejados/manuais.

Provider read-only: raiz legada para enabled, `/v1/snapshot` para key/value e schemaVersion/generation. UID deve ser do APK ou o UID esperado de SystemUI contendo seu pacote. URI/projeção/parâmetros restritos; nenhuma CRUD/shell. Cliente usa worker, observer e polling de 10 s, troca de snapshot atômico e fallback para props. Nenhum Binder no draw. Ack protegido por STATUS_BAR confirma só leitura; não prova desenho ou shader.

**sfrefract pertence ao helper/injetor.** Não é gravado pela UI, presets, provider ou ConfigClient. Assim, um override persistido em UI não sobrepõe a prop que o injetor só liga depois do carregamento, nem força modo SF após reboot. A linha do catálogo encaminha ao controle de ciclo de vida.

Flags independentes por superfície/camada APK usam release/fallback existentes. Watchdog, motion gate, detach e proteções não recebem opção de desligar. Flags de borda/reflexo/contraste APK não desligam iluminação embutida no C; os parâmetros SF têm identificação própria.

Início / Superfícies / Diagnóstico; Pop-up / Bloqueio / Central; busca global, favoritos, avançados, editor numérico, reset por item/grupo e temas escuro/claro. Preview sintética com quatro fundos, sem captura. Parâmetros suportados têm aproximação visual; demais mostram diagrama de domínio. Sem números inventados de custo ou prova de fidelidade física.

Notificação real BigText compartilha modelo de texto com preview, usa a nova marca/ícone dedicado, timestamp e ações Abrir ajustes/Dispensar explícitas/imutáveis. Fluxo de permissão retoma postagem; negativa cancela. Testes agora/5 s/várias, cancelamento restrito aos IDs do app e timeout. HUN depende do Android.

## Botão SurfaceFlinger integrado ao #57

`SfLiveController` extrai o bundle existente, verifica SHA-256 de injetor, lib **e helper antes de executá-lo como root**, usa somente `SfLive.command`, captura saída/exit e roda fora da UI. Não solicita root em startup/resume, slider ou preset. KernelSU presente não autoriza o UID; managerless depende da concessão pela CLI instalada.

`SfLiveFlow` impõe sequência testável:

1. status e validação de firmware/bundle;
2. UPDATE: install, nova checagem de hashes, selftest;
3. SELFTEST: selftest e nova checagem do stamp;
4. falha: interromper, ler/mostrar log, nenhum restart;
5. RESTART: confirmação digitada, novo status imediatamente antes e restart rewrite;
6. LOADED: não reinstalar/reiniciar binários iguais. Modo dump/lente desligada pode pedir confirmação para ativação.

Ação avançada separada permite recompilar valores **já observados nas props**; não escreve valores SF desejados por shell arbitrário. Retorno restart=started não vira “aplicado”. O app pode morrer com o zygote e pede nova checagem ao reabrir. `sf_loaded`, `sf_rewrite`, `sfrefract`, `sf_optics` são exibidos separadamente; nenhum substitui REWRITE_OK/resultado óptico. Remover arquivos usa UNINSTALL após confirmação, com aviso de reboot e biblioteca ainda mapeada.

## Correções encontradas na revisão do helper

- Manifest incompleto poderia comparar dois hashes vazios como iguais. O helper agora exige SHA hexadecimal de 64 caracteres antes de comparar.
- `bundle_ok` conferia só injetor/lib, embora o manifesto tenha hash do helper. Confere os três; Java também verifica antes da execução privilegiada.
- Módulo legado `oulg_sf` ativo é recusado antes de instalar/reiniciar a rota live; a UI não desativa outros módulos automaticamente.
- `sf_loaded` usava grep parcial do caminho. Agora confere caminho exato, inode atual e ausência de sufixo deleted, evitando confundir a lib substituída com a atual.

Host tests incluem manifest incompleto, corrupção, hash do helper, instalação por rename e mapas com inode atual/deleted/diferente. Não muda o código de injeção ou a física do shader. O injetor e seu mecanismo de recuperação são os já preparados na main.

## Validação e pendências físicas

Contratos host: `python3 tools/test-distribution.py` e `sh sfhook/tools/live_check.sh`. JVM cobre catálogo, validação, presets e ordem do fluxo SF com runner falso; nenhum teste host executa su ou reinicia Android. `controls.yml` roda Gradle test/lint/assemble e checks nativos.

A versão anterior à integração já compilou APK debug e passou 128 testes JVM e checks do shader. Após integrar #57, recursos/manifest foram compilados com AAPT2, as fontes Java foram recompiladas e os 133 testes JVM passaram novamente em execução direta. A rodada completa Gradle/lint/assemble da base atual fica registrada no CI; não confundir compilação direta com essa rodada. Não há aparelho One UI conectado nesta sessão: sem screenshots/vídeo executados, concessão SU, helper real, notificações reais ou matriz óptica 3 superfícies × 2 temas × fundos × APK/SF confirmados. Não declarar paridade física ou aplicação efetiva a partir de testes JVM/host.
