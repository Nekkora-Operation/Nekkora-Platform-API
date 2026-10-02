# WIN-TRANSPORT-1: correção mínima da aquisição da trava da caixa (Windows)

**ESTADO: PARA REVISÃO. Todos os portões cumpridos.**
- **Maven verify1: FALHA, preservada, sem reclassificação** (§6). Houve STOP. Foram 2 erros ambientais (`Address already in use`) no `nekkora-ops`, e o `nekkora-platform` ficou SKIPPED.
- **Maven verify2: PERMITIDO** nos dois critérios (§6a). É a repetição **única** autorizada, sem alteração, com fontes byte a byte iguais às do verify1. O reator foi inteiro e `AquisicaoTravaTest` e `CaixaHomologacaoMailSenderIT` executaram e passaram.
- O encerramento do achado de transporte Windows fica para a revisão.

**Data:** 2026-10-02.

**Evidências:** `ops/homologacao/saidas/wintransport1-20261002T125616Z/`, fora do Git.

## 1. Correção (produto)

`CaixaHomologacaoMailSender.java` (`2d81e52d…aeeb` → `6fbdc540…27ba`):
- A aquisição continua usando o **diretório** `controle.trava`. Ela saiu do `desfecho()` para `adquirirTrava(trava, limite, criador, windows)`. O `desfecho()` chama com o prazo **existente** (`System.currentTimeMillis() + ESPERA_TRAVA_MS`), `Files::createDirectory` e `WINDOWS`.
- `FileAlreadyExistsException`: ocupada, como antes.
- **`AccessDeniedException` é contenção transitória só se** o SO for Windows **e** `e.getFile()` não for nulo **e** `Path.of(e.getFile())` for **igual ao caminho exato da trava**. Nesse caso entra no **mesmo** ciclo de espera.
- Qualquer outro caso é falha imediata (`UncheckedIOException`):
  - `AccessDenied` fora do Windows;
  - `AccessDenied` em outro caminho ou sem caminho;
  - qualquer outra `IOException`.
- **Prazo único:** `limite` é calculado uma vez e nunca reatribuído. Ocupada e acesso negado compartilham o mesmo prazo.
- **Causa preservada:** se o prazo acaba depois de ter recebido acesso negado, a exceção é `UncheckedIOException("… nao adquirida no prazo (acesso negado)", <AccessDeniedException>)`. Ela não vira timeout genérico nem sucesso. Sem acesso negado, a mensagem de "ocupada" continua a mesma de antes.
- **Sem** texto de mensagem, código de erro localizado ou heurística de `Files.exists`.
- Leitura, escrita, *rename* e liberação **não mudaram**: um `AccessDenied` nelas continua sendo erro imediato (controle C1).

**Escritor externo do IT** (`CaixaHomologacaoMailSenderIT.java`, `71179941…d1b5` → `11cbeeb1…111b`):
- mesma regra: `AccessDenied` só no Windows, com caminho não nulo e igual ao da trava, é ocupada;
- senão, `IllegalStateException`.

**Runner Linux:** o `mkdir`/`rmdir` de `homologacao.sh` **não mudou** (conferido no `validar-config`, §5).

**Teste unitário novo:** `AquisicaoTravaTest.java` (`027c9000…402a`), com 10 casos e criador simulado:
- adquire;
- ocupada → adquire;
- acesso negado na trava no Windows → adquire;
- acesso negado fora do Windows → imediata (< 500 ms);
- outro caminho → imediata;
- sem caminho → imediata;
- outra `IOException` → imediata;
- persistente → falha **com a causa** depois do prazo;
- alternado → **prazo único**: 500 a 1.500 ms com prazo de 500, mais de 4 tentativas, causa `AccessDenied`;
- ocupada persistente → "trava do controle ocupada".

> No verify1 ele **não rodou** (platform SKIPPED). No **verify2** rodou: **10/10 casos passaram** (§6a). Também roda o equivalente no `VerificaTrava` do `validar-config` (§5), contra a fonte real e contra cópias adulteradas.

## 2. Contraprova Windows: o MESMO harness da WIN-TRANSPORT-0 (`C_harness/`)

O `Harness.java` (`c81e5065…` → `43b8d1e5…`) é o da WIN-TRANSPORT-0, estendido sem facilitar nada:
- o escritor do H4 segue a regra do IT corrigido e registra `OCUPADA_REMOCAO_PENDENTE` (transitório) ou `ESCRITOR_FATAL`;
- H4, H5 e H6 registram o **balanço** de cada rodada: gravadas = consumidas (`ENFILEIRADO`) + restantes no controle;
- casos novos: H6 e C1 a C3 (§3, §4).

**Fontes compiladas**, cópias verbatim conferidas no `contexto.txt` de cada execução:
- produto `6fbdc540…27ba` e harness `43b8d1e5…3e79f` em **todas** as execuções que contam (1 a 3 e Linux).
- A execução 0 (fumaça, antes do rótulo do tipo da exceção nos controles) fica preservada e não conta.

| Execução | Escala | Resultado |
|---|---|---|
| 1 | 5 × 400, todos os casos | **P1: 31 `AccessDenied`**, **P2: 5** (Java) + 87 `PermissionError` (Python): a semântica do Windows continua presente. **H1 e H3: 0.** **H2, H4, H5: 0 exceções.** Balanços OK |
| 2 | **40 × 1.000**, H4/H5 (= execução 6 da WIN-TRANSPORT-0) | **H4: 0** (80.000 chamadas; antes **41**). **H5: 0** (80.000; antes **17**). 40/40 balanços OK por caso. Escritor do H4: 5 `AccessDenied` **absorvidos** como transitórios, **0 fatais** |
| 3 | **80 × 1.000**, H2/H4/H5 (o dobro da execução 7) | **H2: 0** (160.000; antes 16 em 80.000). **H4: 0** (160.000). **H5: 0** (160.000). 80/80 balanços OK em H4 e H5. Escritor do H4: 15 absorvidos, **0 fatais**; Python: 15 `PermissionError` |

- Antes da correção: **1 a 2,5 exceções por 10 mil** chamadas sob disputa.
- Depois: **0 em 640.000** chamadas sob disputa, nas execuções 2 e 3.
- A condição continua ocorrendo: o escritor e o Python ainda veem acesso negado transitório. Agora é absorvida, sem perda nem duplicação de regras.

## 3. Controles da correção (no harness, sistema de arquivos real)

| Controle | Windows (execução 1, 5 rodadas) | Linux (§4) |
|---|---|---|
| **C1:** `AccessDenied`/E/S **fora** da aquisição (`controle.conf` é um diretório: falha na leitura) | 5/5 `FALHA_IMEDIATA[UncheckedIOException]`, causa `AccessDeniedException@desfecho:249` (< 1 s) | 5/5 imediata, causa `IOException@desfecho:249` |
| **C2:** acesso negado **real e persistente** na aquisição (`icacls /deny <usuário>:(AD)` na caixa; no Linux `chmod 555` com usuário sem privilégio) | 5/5 **`FALHA_EM_5000MS[UncheckedIOException]`**, causa **`AccessDeniedException`**: nunca sucesso, espera o prazo existente e preserva a causa. Negação removida ao fim | 5/5 **`FALHA_EM_0MS`**, causa `AccessDeniedException`: imediata fora do Windows |
| **C3:** prazo único com ocupada e acesso negado **alternados** (criador simulado, prazo de 1 s, modo Windows) | 5/5 `PRAZO_UNICO_OK_1000MS[UncheckedIOException]`, causa `AccessDeniedException` | 5/5 idem |

## 4. Prova curta no Linux (`L_linux/`): não regressão, não reprodução

**Como rodou:**
- `linux.sh`: o mesmo harness e as mesmas fontes, compilados no host (`--release 21`);
- executado em `eclipse-temurin:21-jre-alpine` (kernel WSL2), como **uid 1000** sem privilégio, com as caixas no `/tmp` do próprio container e `--network none`;
- um container por caso, criado e removido **pelo ID** (7 IDs no `execucao.txt`; 0 restantes).

**Resultados** (5 × 400):
- **H1, H2: 0 exceções.**
- **H4: 0**, balanços OK.
- **H6** (escritor `sh` com o protocolo **exato** do runner: `mkdir` da trava, anexa, `rmdir`): **0 exceções**, 5/5 balanços OK.
- C1, C2 e C3 conforme §3.
- Sem Python na imagem, por isso H5 não foi incluído (o H6 cobre o escritor em outro processo).

## 5. Controle leve contra regressão silenciosa (`validar-config` seção 18)

**`testes/test_trava_caixa.sh`** (`d35e6fda…`) + **`testes/wintransport/VerificaTrava.java`** (`2f9e819e…`):
- compila cópias verbatim do transporte e roda **8 cenários** do contrato contra o `adquirirTrava` real, em poucos segundos, sem estresse;
- depois, **4 cópias adulteradas** têm de ser **detectadas**:
  - sem a condição Windows: 1 cenário falha;
  - sem o caminho exato: 1;
  - prazo reiniciado a cada acesso negado: 2;
  - causa trocada por timeout genérico: 2.
- Âncora ausente = FALHA: se a correção mudar de forma, o controle tem de ser revisto junto.

**Verificações estáticas:**
- o `desfecho` usa **só** `adquirirTrava` com o prazo existente;
- uma única captura de `AccessDeniedException` no produto, dentro da aquisição, com Windows + caminho exato;
- `limite` nunca reatribuído; causa preservada; sem `getMessage` nem `Files.exists`;
- escritor do IT coerente; os 4 testes-chave do `AquisicaoTravaTest` presentes;
- protocolo `mkdir`/`rmdir` do runner inalterado.

**`validar-config`:**
- **1ª execução: 183/1.** A falha era **do meu controle novo**: o separador `|` das linhas de adulteração cortava o `||` do código Java, então 2 cópias não compilavam, e o controle se marcou corretamente como inválido.
- Corrigi o separador para `#` e preservei a saída como `V_validar/validar_config_1_FALHA_separador_do_controle.txt`.
- **2ª execução: 184/0.** Novo total = 177 + 7: 1 de sintaxe do `test_trava_caixa.sh` (seção 1) + 6 da seção 18.

O estresse (H2/H4/H5 em escala) fica **fora** do `validar-config`: é prova de portão.

## 6. Maven verify1: FALHA, STOP (preservado; `M_maven/verify1`)

**Execução:** `ops/homologacao/regressao_maven.sh …/M_maven/verify1 -B verify`, de 13:20:14Z a 13:27:43Z.

**Resultado:**
- `RESULTADO_TESTES=FALHA`, código 1;
- `TESTES=160 FALHAS=0 ERROS=2`;
- `FONTES_ESTAVEIS=OK`;
- **`REAPROVEITAMENTO=PROIBIDO`**.
- `fontes_maven.py verificar`: `INTEGRIDADE_EXECUCAO=OK`, `ARVORE_ATUAL=IDENTICA`, **`REAPROVEITAMENTO_NA_ARVORE_ATUAL=PROIBIDO`**.
- Reator: core SUCCESS, **ops FAILURE**, **platform e portal SKIPPED**.

**Erros:** os dois em `com.nekkora.ops.Revisao4TentativasIT`, módulo e código **não alterados** nesta missão.
- `tentativaAntigaNaoEmiteDepoisDoAceiteDaNova`: `resultado de entrega falhou (sqlstate 08001)` ← `PSQLException: A tentativa de conexão falhou` ← **`java.net.BindException: Address already in use: getsockopt`**, ao **abrir** uma conexão JDBC (`OpsBanco.comoServico`).
- `aceiteForaDeOrdemPreservaATentativaPosterior`: `emissao negada: REGISTRO_INDISPONIVEL` (`OpsBanco.credencial`), no teste seguinte.

**Diagnóstico** (`diagnostico_portas.txt`, só leitura, logo depois):
- 1.893 conexões em TIME_WAIT no host, **1.856 para um único destino, `127.0.0.1:57259`** (o banco dos testes);
- faixa dinâmica do Windows: 49152 + 16384;
- é a **primeira** ocorrência de `Address already in use` em todos os `maven.txt` de `ops/homologacao/saidas/`.

**Hipótese, não provada:** colisão ou esgotamento de porta efêmera de **cliente** no host Windows (muitas conexões curtas para a mesma porta). Ela não envolve o código da caixa: o `nekkora-ops` não usa a caixa, e o harness não abre sockets. **Classificação: INCONCLUSIVO**, não atribuído à correção.

**Consequência:** verify1 não é aceito e permanece **FALHA** com `REAPROVEITAMENTO=PROIBIDO`. Nele, `AquisicaoTravaTest` e `CaixaHomologacaoMailSenderIT` não rodaram.

## 6a. Maven verify2: a repetição única autorizada (`M_maven/`)

**Condições da autorização e como foram cumpridas:**

1. **Nenhuma alteração** entre verify1 e verify2: fonte, teste, POM, configuração, TCP, registro, faixa efêmera, Docker e banco ficaram iguais. Só aguardei o escoamento.
2. **Fotografia read-only antes** (`fotografia_pre_verify2.txt`, 13:33:34Z):
   - TIME_WAIT total: **46**, contra 1.893 depois do verify1;
   - para `127.0.0.1:57259`: **0** (eram 1.856); nenhum *listener* nessa porta;
   - faixa dinâmica inalterada: 49152 + 16384;
   - nenhum processo `java.exe` ou `mvn.exe`;
   - HEAD `dd74a8f`, stage 0, `git status` registrado;
   - **os 8 hashes da missão conferem** com `P_patches/depois.sha256`.
   - Além disso, `fontes_maven.py verificar verify1` deu `ARVORE_ATUAL=IDENTICA` antes de rodar.
3. **Mesmo comando e mesma ferramenta:** `regressao_maven.sh …/M_maven/verify2 -B verify`, sem `-pl`, `-rf`, *retry* nem mudança de paralelismo. Rodou de 13:33:48Z a 13:49:19Z, código 0.
4. **Fontes idênticas:**
   - `fontes.sha256` do verify1 e do verify2 **idênticos byte a byte** (`cmp`), sha do manifesto `e0b5ff19…29be`;
   - `fontes_lf.sha256` também idênticos;
   - o `fontes_fim.sha256` do verify2 é igual ao início do verify1.
5. **Os dois critérios:**
   - `resultado.txt`: `RESULTADO_TESTES=OK`, `TESTES=266 FALHAS=0 ERROS=0 IGNORADOS=0`, `FONTES_ESTAVEIS=OK`, **`REAPROVEITAMENTO=PERMITIDO`**;
   - `fontes_maven.py verificar`: `INTEGRIDADE_EXECUCAO=OK`, `ARVORE_ATUAL=IDENTICA`, **`REAPROVEITAMENTO_NA_ARVORE_ATUAL=PERMITIDO`**.
6. **Reator inteiro:** api, core, ops (08:15), **platform (06:29)** e portal com **SUCCESS**, **0 SKIPPED**, `BUILD SUCCESS`.
   - `AquisicaoTravaTest`: **10 testes, 0 falhas, 0 erros**.
   - `CaixaHomologacaoMailSenderIT`: **11 testes, 0 falhas, 0 erros**, incluindo **`escritorExternoETransportesConcorrentesNaoPerdemNemDuplicamRegras`**, o teste da falha histórica.
   - Os XML do surefire (13:45Z, dentro do verify2) estão copiados em `verify2_relatorios_testes/`, com os nomes de cada caso em `portao_verify2.txt`.
7. **`Address already in use`: 0 ocorrências.** Nenhuma falha ambiental. Não houve verify3.

**Classificação da falha do verify1:** continua **INCONCLUSIVA**. O verify2 limpo, depois do escoamento, torna mais plausível um *flake* ambiental de portas de cliente no host Windows, mas **não prova** a causa.

## 7. Escopo e integridade

**Diff fechado:** `P_patches/wintransport1.patch`, aplicado sobre as cópias "antes", reproduz os **8 arquivos** (`verificacao_patch.txt`: 8/8 OK). Antes e depois em `antes.sha256` e `depois.sha256`:
- alterados: `CaixaHomologacaoMailSender.java`, `CaixaHomologacaoMailSenderIT.java`, `Harness.java`, `rodar.sh` (resumo com H6 e C1 a C3), `validar_config.sh` (`f8a04b78…` → `92367faa…`);
- novos: `AquisicaoTravaTest.java`, `VerificaTrava.java`, `test_trava_caixa.sh`.

**Ambiente e repositório:**
- **Inventário B12** (id `wintransport1-missao`), início × fim **depois do verify2**: **idêntico por ID** (redes 14, volumes 89, imagens 25; containers idem). A comparação depois do verify1, também idêntica, fica preservada como `*_pos_verify1.*`.
- **Não tocados:** `ensaio-016a/016b/016c`, `hml03`, 014/016 (016 `f37d9841…`, igual à referência anterior), `elevacao.txt`, 5b, P5, ativação.
- Sem stage, commit ou push (stage 0, HEAD `dd74a8f`).
- **Pacote:** montado só depois do PASS do verify2. O gerador é `empacotar_wintransport1.py`, adaptado do da WIN-TRANSPORT-0. Os `.class` e o jar do slf4j da prova Linux ficam fora; as fontes compiladas entram como cópias com hash.

## 8. Quadro

| Item | Estado proposto |
|---|---|
| Correção, harness, Linux, `validar-config` 184/0 | PASS |
| Maven verify1 | **FALHA preservada** (ambiental, INCONCLUSIVA) |
| Maven verify2 (repetição única, sem alteração, fontes idênticas) | PASS: PERMITIDO nos dois critérios, reator completo |
| Achado do transporte Windows | encerramento **para decisão da revisão** |
| Rotação da política histórica, 5b/hml03, P5, ativação 014/016 | inalterados (OPEN/HOLD); fora desta missão |

## Adendo MFA-CONSOL-1 (2026-10-02): registro da decisão da revisão

*Acrescentado na missão MFA-CONSOL-1 (E0.1). O corpo acima não foi alterado; o pacote citado continua sendo a evidência daquele momento. Origem das citações: mensagens da responsável na sessão Claude Code `d31e2723-029b-47ee-a98d-bf0f4f24ed47`, horário UTC. Só trechos de decisão.*

| Item | Registro |
|---|---|
| Entrega | WIN-TRANSPORT-1 (correção mínima da aquisição da trava) |
| Pacote | `homologacao-016-wintransport1-20261002T135101Z.zip`, `1641cddc7dd90548815492ba4ad4d4d78077aa03581e430222ccde2571f8ae8c` (285/285) |
| Autorização intermediária | 2026-10-02T13:33:23Z: "Autorizo uma única repetição controlada da regressão Maven, sem qualquer alteração de código/configuração, em M_maven/verify2" |
| Decisão | 2026-10-02T14:00:38Z: "Aprovo a WIN-TRANSPORT-1. PASS completo. O achado de transporte Windows pode ser encerrado como CLOSED na ferramenta/produto atual." `validar-config` 184/0 |
| Preservado | verify1 FALHA / PROIBIDO, sem reclassificação; causa INCONCLUSIVA |
| Limitação | a correção existe só na árvore de trabalho; as imagens dos ambientes preservados são anteriores a ela |
