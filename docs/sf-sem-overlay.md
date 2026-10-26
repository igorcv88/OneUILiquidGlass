# Alterar o SurfaceFlinger sem OverlayFS: rotas e riscos

Data: 2026-10-24. **Nota de pesquisa, não recomendação.** Nada aqui foi implementado ou testado no
aparelho, e nada disto faz parte do modo só-LSPosed. Para um aparelho cuja prioridade é Play
Integrity e Wallet, toda rota abaixo que coloca código no compositor é **risco alto, viabilidade
indeterminada** até haver evidência no aparelho e uma decisão própria do dono.

## 0. Ponto de partida

**Ambiente do usuário:**
- S938B em DZIF (One UI 9 Stable);
- root por late-load RMG (exploit a cada boot) com `ksud` managerless, administrado pelo CLI no Termux;
- SELinux enforcing, integridade e Wallet funcionando;
- sem metamódulo, sem OverlayFS, sem DFReroot.

**Restrições pedidas:**
- não depender de metamódulo nem de OverlayFS;
- se possível, não alterar `/system` nem o `system_server`.

**O que só o compositor faz.** Um vidro com refração ao vivo precisa dos pixels de outra janela no mesmo quadro em que o card é desenhado. O SystemUI não tem esses pixels:
- `BackgroundBlurDrawable` e `semSetBlurInfo` pedem ao SurfaceFlinger um blur, mas não entregam textura ao app;
- `captureDisplay` entrega pixels, mas sempre um ou mais quadros atrasados. Esse atraso é estrutural: medido em 8–14 ms por captura no aparelho (`architecture.md`, *Captured backdrop*).

Por isso o modo só-LSPosed (README, *LSPosed-only mode*) tem blur ao vivo e a iluminação de borda do app, mas não tem lente. A lente, o blur em camadas e a iluminação do perfil 1 só existem dentro do SurfaceFlinger.

O módulo atual chega lá por um caminho: copia `/system/bin/surfaceflinger`, troca uma entrada `DT_DEBUG` por `DT_NEEDED` (88 bytes) e depende do metamódulo para montar o binário por cima do original. As rotas abaixo evitam essa montagem.

## 1. Rotas avaliadas

| Rota | Mexe em `/system` | Montagem | Código no SF | Persiste no reboot | Viabilidade |
| --- | --- | --- | --- | --- | --- |
| A. Injeção por ptrace no SF reiniciado | não | não | sim (lib em `/data`) | não; refeito a cada late-load | indeterminada; depende de 3 checagens no aparelho |
| B. Bind mount de um arquivo só | não (sobrepõe) | sim | sim | não | tecnicamente simples; pior em detecção que A |
| C. GLES layers (`debug.gles.layers`) | não | não | sim | – | não funciona em build `user` sem `ro.debuggable=1`, que quebra a integridade |
| D. Cache de shaders do SF pré-preenchido | não | não | não (só o GLSL muda) | **sim** | pesquisa; depende do formato do cache da Samsung |
| E. Injeção no SF já rodando, sem reiniciar | não | não | sim | não | baixa: os programas já compilados não passam de novo por `glShaderSource` |

### A. Injeção por ptrace num SurfaceFlinger reiniciado (a menos invasiva das rotas nativas, não testada)

**Fluxo, depois do late-load, comandado do Termux:**
1. Apagar `skia_shaders` e `egl_shaders` em `/data/misc/surfaceflinger`, como o `post-fs-data.sh` atual faz.
2. `setprop ctl.restart surfaceflinger`. Isso reinicia o userspace inteiro (`system_server`, zygote e apps), mas não o kernel, então o root continua.
3. O injetor lê `/proc` em laço curto, acha o PID novo e faz `PTRACE_SEIZE` + `PTRACE_INTERRUPT`. O SF passa centenas de ms inicializando HWC e displays antes de criar o RenderEngine, então há margem.
4. Chamada remota a `dlopen` (o endereço sai de `/proc/<pid>/maps`, porque o `linker64` é o mesmo binário nos dois processos).
5. O construtor da lib troca, na GOT do `surfaceflinger`, as entradas de `glShaderSource` e `eglGetProcAddress`. Sai de cena o `DT_NEEDED`, que dependia do binário patcheado:
   - a página RELRO vira gravável só durante a troca (`mprotect` sem `PROT_EXEC`, então não exige `execmod`);
   - o Skia busca as funções GL por `eglGetProcAddress`, e o hook atual já intercepta essa chamada.
6. `PTRACE_DETACH`. A partir daí o SF roda o mesmo `refract.h` de hoje.

**O que se aproveita:**
- `refract.h` e quase todo o `oulg_sf.c`. Muda só a forma de entrar: troca de GOT no lugar da interposição por `DT_NEEDED`.
- O app não muda: `sfrefract=1` liga a lente; sem a lib, ele cai no modo só-LSPosed.

**Pegada:**
- Nada é montado e nada em `/system` muda. O `system_server` não é tocado.
- O SF mapeia uma lib de `/data`. Isso só aparece em `/proc/<pid_do_SF>/maps`, que um app não lê.
- O `TracerPid` só fica diferente de 0 durante a injeção (milissegundos).
- **Hipótese, não fato:** o DroidGuard (no processo do GMS) e a Wallet provavelmente não leem a memória do SF. Não foi medido o que esses verificadores observam: podem existir sinais indiretos (estado SELinux, propriedades, reinícios do userspace, comportamento do compositor). Play Integrity, certificação da Play Store e elegibilidade da Wallet são três verificações distintas; nenhuma delas foi medida com esta rota.

**Segurança contra falha:**
- Sem persistência no boot: um reboot sempre volta ao SF original.
- O injetor precisa de uma trava: se o SF reiniciar de novo nos 90 s seguintes, não reinjeta e registra o motivo. É o watchdog atual, sem o `reboot`.

**Checagens obrigatórias no aparelho, antes de escrever o injetor:**
1. **ptrace a partir do domínio do root.** O `ksud` managerless mantém a regra ampla do domínio `ksu`? Teste: `su -c 'cat /proc/$(pidof surfaceflinger)/maps | head -1'` e um `PTRACE_SEIZE` de teste. O DEFEX da Samsung pode recusar; isso tem de aparecer como `EPERM` ou como `avc: denied { ptrace }` no `dmesg`.
2. **SELinux da lib.** O domínio `surfaceflinger` precisa de `search` na pasta e de `read open map execute` no arquivo:
   - **Primeira opção:** rotular a lib como `system_lib_file` (`chcon`) numa pasta que o SF já percorre. `/data/misc/surfaceflinger` é candidata, porque o hook atual escreve lá de dentro do SF.
   - **Se houver negação:** uma regra viva com `ksud sepolicy patch`. Ela só existe na memória do kernel e o SELinux continua enforcing.
   - Conferir com `ls -Zd` e com o `dmesg` depois de uma tentativa.
3. **Custo do reinício do userspace.** No ZZIC cada reinício do SF chegou a 4–5 min em "Powered by Android" (`architecture.md`, *Phase 2 on the device*), sem causa medida. Se o DZIF repetir isso, a rota A custa um boot lento por dia de uso.
   - Se o fluxo do late-load já reinicia o userspace, a injeção pode entrar nesse mesmo reinício, sem custo extra. Não está claro nos documentos do RMGLabs-Payloads; precisa ser lido no código do `ksud`.

**Ainda falta, mesmo com as três checagens OK:** confirmar que o DZIF continua em GLES (Ganesh), e não em Vulkan. Isso só se vê no dump de shaders depois da primeira injeção (`/data/misc/surfaceflinger/oulg_shaders.txt`, com `VS_OK`, `FS_OK` e `FAIL` contados como no README).

### A, implementação (2026-10-26, não testada no aparelho)

`sfhook/inject.c` (estático, `oulg_inject`) e o mesmo `liboulg_sf.so`, no asset `OneUILiquidGlass-SurfaceFlinger-live-*.zip` do release. Diferenças em relação ao fluxo acima:

- **Ponto de injeção:** não um `PTRACE_INTERRUPT` em lugar qualquer, mas o ponto de entrada do executável (`AT_ENTRY`). O injetor para o SF novo enquanto ele tem uma thread só, põe um `BRK` no entry e chama `__loader_dlopen` do `linker64` quando o processo para ali. Nesse ponto a ligação dinâmica acabou, nenhum lock está tomado e o `main()` ainda não criou o RenderEngine. Se o processo já passou do entry (mais de uma thread, ou o `BRK` não é atingido em 3 s), o injetor solta o processo intacto e sai com código 3.
- **Namespace:** o `caller` do `__loader_dlopen` é o entry, então a lib entra no namespace do próprio SF. Se o namespace recusar `/data`, o `dlerror` é impresso.
- **Troca de GOT:** o construtor da lib percorre todos os objetos carregados (`dl_iterate_phdr`) e troca as entradas `JUMP_SLOT`/`GLOB_DAT` de `glShaderSource` e `eglGetProcAddress`. Ficam de fora a própria lib, a `libEGL` e a `libGLES*`. O valor antigo vira a função real. Carregada por `DT_NEEDED`, as entradas já apontam para ela e nada muda. Log: `GOT ... patched`, `GOT_DONE`.
- **Funções GL reais:** com `dlopen`, o `RTLD_NEXT` só vê o grupo local da lib (libc, liblog, libdl), então a busca cai para `RTLD_DEFAULT`, o grupo global do SF.
- **Reinício:** `--restart` segue a sequência do `handoff.md`: para o SF, apaga o cache (`--clear-cache`), inicia o SF, injeta e reinicia o zygote 3 s depois do start. Reiniciar só o SF deixava o aparelho 4–5 min em "Powered by Android", até um watchdog reiniciar o zygote; com o zygote reiniciado logo, eram ~20 s. O zygote é reiniciado mesmo se a injeção falhar. Se esse reinício derrubar o SF de novo, o processo novo também é injetado. Tudo roda de dentro do injetor, que antes sai do cgroup do app (`--daemon`), porque o reinício do userspace mata o Termux.
- **`--probe`:** só leitura. Faz `PTRACE_SEIZE`, para a thread principal só pelo `PTRACE_DETACH` (que exige ptrace-stop) e confere o retorno. Informa o linker, o entry, os símbolos e o rótulo SELinux da lib.

- **Limpeza verificada:** a instrução original do entry é relida depois de restaurada, os registradores são conferidos depois do `SETREGSET`, e todo `PTRACE_DETACH` tem o retorno checado. Um processo que não volta intacto, ou que não pode ser solto, é morto com `SIGKILL` (o init sobe um SF limpo), em vez de ficar parado ou com o `BRK`.
- **Parada que falha:** se o SF não parar em 10 s, nada é apagado nem injetado; o injetor pede `ctl.start` e, se o SF reiniciar mesmo assim, reinicia o zygote.
- **Cgroup:** `--restart` exige `--daemon` e confere em `/proc/self/cgroup` que o injetor saiu do cgroup do app; se não saiu, recusa o reinício.
- **GOT:** a página volta à proteção exata que tinha (lida de `/proc/self/maps`); página executável ou de proteção desconhecida não é tocada. `GOT_DONE ... main=N` diz quantas entradas foram trocadas no próprio executável; `main=0` é falha, mesmo com `DLOPEN_OK`.
- **`DLOPEN_OK` não prova interceptação.** A prova está no `logcat -s OULG_SF` (`GOT_DONE main>0`, linhas `SRC`) e no dump de shaders, que também diz se o DZIF é GLES.

Ainda não foi visto no aparelho: se o DEFEX/SELinux deixa o domínio do root usar ptrace no SF, se o domínio `surfaceflinger` pode mapear a lib, quanto o SF leva até o entry depois do exec (a corrida) e se o DZIF é GLES.

### B. Bind mount de um único arquivo

`mount --bind` do binário patcheado sobre `/system/bin/surfaceflinger`, sem OverlayFS, seguido de `ctl.restart`. Funciona, mas:
- a montagem aparece no `mountinfo` de todo processo do namespace global, inclusive apps, a menos que o KernelSU a desmonte nos namespaces deles;
- a rota A chega ao mesmo resultado sem nenhuma montagem.

Descartada.

### C. GLES layers

O carregador de layers do Android só aceita layers em app debuggable ou com `ro.debuggable=1`. O SF não é app, e mudar `ro.debuggable` derruba a integridade. Descartada.

### D. Cache de shaders pré-preenchido (pesquisa)

**Ideia:** o SF da Samsung carrega programas prontos de `/data/misc/surfaceflinger/skia_shaders` com `glProgramBinary`, e por isso o hook nunca os vê. O mesmo mecanismo pode trabalhar a favor:
1. compilar o GLSL reescrito num contexto GLES próprio, no aparelho, e ler o binário com `glGetProgramBinary`;
2. gravar esse binário no cache, sob a chave que o Skia usa para o programa original.

**Resultado, se funcionar:**
- o SF carrega o shader reescrito sozinho a cada boot, inclusive antes do root;
- não há código estranho no processo, injeção nem montagem;
- uma atualização de firmware invalida o cache e volta ao original.

**Por que é pesquisa e não plano:**
- o formato do arquivo (cabeçalho, checksum, chave do Skia) não foi lido. É preciso puxar o arquivo do aparelho;
- para cada programa é preciso o GLSL original, que só o hook captura. Então a rota D precisa da rota A uma vez, para gerar o dump no DZIF;
- não se sabe se a Samsung valida o conteúdo além do driver e do build;
- a força da lente viaja na marca do raio e continua ao vivo. Os parâmetros lidos na compilação ficariam congelados no binário.

**Próximo passo, se a rota A der certo:** puxar `skia_shaders` e comparar com o dump da mesma sessão.

### E. Injeção sem reiniciar o SF

Os programas que o Skia já compilou ou carregou do cache, incluindo o `FillRRect` dos blurs, não passam de novo por `glShaderSource`. O binário é stripped, então não há como limpar o cache de programas do Skia de dentro. Descartada como rota principal.

## 2. Recomendação

1. **Agora:** só o modo só-LSPosed. Nenhuma rota deste documento: nada de ptrace, reinício do SurfaceFlinger, limpeza do cache de shaders, regra SELinux ou módulo novo.
2. **A estética que falta** (lente, blur em camadas) primeiro como material procedural no AGSL do próprio SystemUI, sobre o blur ao vivo do compositor. Isso não acrescenta nenhuma superfície de detecção.
3. **Só se o dono decidir voltar a tocar no compositor**, e com Play Integrity e Wallet medidos antes e depois: as três checagens de §1.A (leitura, sem instalar nada), um injetor que só faz o dump e, por último, a reescrita. A rota D depende da A.

## 3. Riscos que valem para qualquer rota

- **Propriedades `debug.oulg.*`:** qualquer processo com permissão de leitura de `debug_prop` as vê. Não foi medido se um app comum (e o DroidGuard) lê essa classe neste firmware.
  - O modo só-LSPosed não precisa de nenhuma delas: os padrões valem com todas vazias.
  - A rota A precisa só de `sfrefract=1`. Para não depender dela, a lib e o app podem combinar por arquivo; fica para quando a rota for escrita.
- **O SF patcheado é código nativo no compositor:** um erro derruba o userspace. A trava de reinício de §1.A é obrigatória.
- **DZIF não validado:** nenhum shader do DZIF foi visto ainda (`dzif-port.md`, §5).
