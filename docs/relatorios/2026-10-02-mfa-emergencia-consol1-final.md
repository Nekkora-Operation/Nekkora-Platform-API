# MFA-CONSOL-1: relatório final (E0–E4)

**ESTADO: PARA REVISÃO.**
- O conjunto atual de ferramentas e produto foi exercitado integrado pelo executor real no `ensaio-consol-016`, de ponta a ponta, sem falha de produto.
- Ficam registrados: um STOP de pré-voo (resolvido por portão próprio), um erro de auxiliar e duas divergências de previsão do executor.
- **Não declara produção liberada nem a API funcionalmente completa.** Não autoriza a integração Git no original, a ativação operacional da 014/016, a 5b nem o P5.

**Data:** 2026-10-02, das 14:34Z às 16:0xZ.

**Evidências:** `ops/homologacao/saidas/consol1-20261002T143413Z/`, fora do Git.

## 1. Resultado por etapa

| Etapa | Resultado | Referência |
|---|---|---|
| **E0:** adendos, matriz, lista fechada, proposta | **PASS.** Aprovada tecnicamente depois da revisão 1 (snapshot com 99 arquivos, prova da imagem por conteúdo, redação) | `…-consol1-preparacao.md`; pacotes `consol1-e0` e `consol1-e0r1` |
| **E1:** encerramento residual do `lab-r2b1` | **PASS.** Leitura `READ ONLY` sem resíduo; commit administrativo `b1b4a1a` | `…-consol1-e1-stop-e2-portao9c.md` §1 |
| **E2:** ensaio integrado | **PASS.** Um STOP de pré-voo (183/1), resolvido pela mini-correção 9c aprovada; depois, fluxo completo | §2 a §4 |
| **E3:** plano Git | **PRONTO PARA DECISÃO** (preliminar aceito pela revisão; sem escrita no original) | `…-consol1-preparacao.md` §5 |
| **E4:** relatório e pacote | este documento e o pacote final | §7 |

**Decisões que sustentam a execução** (todas registradas em `B_decisoes/` e nos relatórios intermediários):

| Decisão | Momento (UTC) |
|---|---|
| D1 | pedido da missão |
| D2–D6 | autorizadas pela responsável às 15:11:08Z, depois do parecer técnico sobre a preparação revisada |
| GO da mini-correção 9c | depois do STOP |
| Aprovação técnica do E1 e da correção 9c | antes de seguir o E2 |

## 2. E2: snapshot e pré-voo

**Clone:** `…/lab-consol016-20261002T152744Z/nekkora-platform`.
- `autocrlf=false` antes do checkout;
- sem remoto;
- hook `pre-push` como proteção adicional; publicação proibida.

| Commit | Conteúdo | Conferência |
|---|---|---|
| **S1** `f2352305e6f022853f72d3f45cefa036b1420e01` | `dd74a8f` + 99 arquivos, manifesto r2 `98061f32…` | 99/99 blobs; `diff dd74a8f..S1` = lista; **S1 × S1″ do 016c: 5 alterados + 13 novos (os 18 deltas aprovados), 0 ausentes**; teste 9c no blob do portão `65eaf683…` |
| **S2** `47936157cfd2aec05f82af33a1fd6fce17d57a8b` | só `elevacao_lab_ensaio-consol-016.txt` (`af800301…`) | `elevacao.txt` operacional sem diff desde `dd74a8f` |
| **S3** `e3a04050abca4af4f32d9eace636a6466a15ed14` | retira 014/016 da lista de lab (fica `d66cfea8…`, 011–013) | o commit altera só esse arquivo |

**`validar-config`, por nome contra a referência de 184:**

| Momento | Resultado |
|---|---|
| S1 do 1º clone (`f9477405`, preservado) | **183/1 → STOP** (caso 9c; ver relatório intermediário) |
| S1 r2 | **184/0, nomes idênticos** |
| S3 | **184/0, nomes idênticos** |

**Maven:**
- `verificar verify2 <clone>` = **PROIBIDO**: 37 caminhos diferentes por bytes. Pelo manifesto LF, só o teste corrigido no portão.
- **Divergência de previsão:** eu havia previsto 9. A previsão contou só os CRLF da lista, não os arquivos do HEAD em CRLF na cópia de trabalho do original.
- Pela regra, rodei uma **regressão nova no clone** (`regressao_maven.sh`, 15:31–15:44Z): **266 testes, 0 falhas, 0 erros**, reator completo e **PERMITIDO** no `resultado.txt` e no `verificar` (árvore idêntica, limpa, 217 arquivos, fontes `5c522396…`).
- `AquisicaoTravaTest` 10/10 e `CaixaHomologacaoMailSenderIT` 11/11.
- 0 ocorrências de `Address already in use`.

**Pré-condições, imediatamente antes do `criar`:**
- `ensaio-consol-016` inexistente;
- porta 55731 `PORTA_APTA` pela ferramenta do clone;
- 10.252.90/91 livres;
- `netsh` registrado;
- B12 inicial coletado.

## 3. E2: sequência integrada

| Passo | Resultado | Evidência (`E2_ensaio/`) |
|---|---|---|
| `criar` (pausa antes de conceder a 014) | Código 0. T1 `PREFLIGHT_OK` (7/7). Fotografia histórica com **3 âncoras iguais** (`af800301…`). Na pausa: 001–013, `0|0|0`, 0 sessões, sem janela da 014, trava ativa (pid 1946) | `10_criar/` |
| Semeadura | `SEMENTE_APLICADA`, 18 sessões; T3 `antes` OK (manifesto de 21 linhas, só contagens e digests) | `11_semente/` |
| 014 | **Uma** janela BYPASSRLS, 15:47:50.418Z → 15:47:56.100Z, `0|0|0`; 015; parada antes da 016 (BLOQUEADO, faltam: 016). **T3 `depois` 20/20** | `12_014/` |
| B3 (recusas da 016) | **4/4**: códigos 2, 2, 2 e 3; estado antes = depois em todas (0 janelas da 016, 001–015, `0|0|0`, `ops` ausente) | `09_B3/` |
| J1 | Pausa antes do Flyway com a janela aberta (`1|0|0`, só SUPERUSER; sem container Flyway). `kill -9` **só no pid 1541** (`implantar.sh` dono da trava, conferido); o pai terminou com 137; 0 `implantar.sh` restantes. BLOQUEADO, e `testar`, `testar-016`, `preparar-016` e `concluir-criacao` recusados (código 1). `recuperar` → `0|0|0`. Reconciliação `READ ONLY`: pré-voo da 016 `PREFLIGHT_OK`, 016 fora do histórico, sem `ops` e sem `sslinfo`, **catálogo (`e04b759e… 807`) e papéis idênticos aos de antes da J1** | `13_J1/` |
| J2 | **LIBERADO**, 001–016, `flyway validate` OK. **Janelas da 016: exatamente 2** (J1 15:50:13.801Z sem fim; J2 15:51:37.376Z → 15:51:42.876Z). **§19 completa depois da retirada**, `admin_cluster`, `READ ONLY`, `SECAO19_OK`, sha da §19 = o do 016c (`3fd6317c…`). Migrador `false×5`, 0/0/0 | `14_J2/` |
| `concluir-criacao` (uma vez) | Código 0. Porta conferida de novo antes dos serviços (`PORTA_APTA`); os 5 serviços no ar | `15_concluir/` |
| **Prova da imagem** | Container `platform` em execução → imagem `sha256:e1808e76b7ece6e2df32e73889304c94bb810a3eade5e4a72c26dc68c02046ae` (tag `f235230`). Temporário criado desse ID e **removido por ID**. **`/app/app.jar` = JAR do clone** (`ec9fdb7c…4a79`). **As 3 classes `CaixaHomologacaoMailSender*` idênticas por sha256** às de `target/classes`. Fonte `6fbdc540…` = a aprovada na WT1. JDK 21.0.12 (Microsoft), Maven 3.9.16 | `16_imagem/` |
| `testar` completo (sem `HOMOLOG_TESTES` nem `HOMOLOG_ELEVACAO_ARQ`) | Código 0. **H01–H09 e R015 OK.** **H02 em MODO HISTÓRICO**: 5 concluídas (011, 012, 013, 014, 016) e 1 interrompida com recuperação própria. **H03 com perfil H03-016**: catálogo = referência pelo modelo suportado (3.672 linhas; só difere o Flyway) e 5 blocos de invariantes | `17_testar/` |
| `preparar-016` / `testar-016` (`H016_B8B=1`) | `[PREP] OK`, SEGREDOS OK (36). **B5, B6, B7, B8a, B8b, B12 OK; SEGREDOS OK (59)**; B10 e B11 PARCIAL (limites declarados) | `18_h016/` |
| S3 e H02 depois do S3 | S3 commitado. Listas transitórias do B3 marcadas `.inativa` (preservadas como evidência, nunca dentro do clone). **Fotografia histórica inalterada** (`af800301…`, só leitura). **`HOMOLOG_TESTES=h02 testar` sem lista ativa: OK, modo histórico, `011 012 013 014 016`** | `19_encerramento/` |
| Conferência final | Migrador: 5 atributos `false`, 0 participações, 0 ACL, 0 sessões; LIBERADO; 31 sessões, 0 não revogadas sem marcador. **B12 início × fim OK**, idêntico por ID fora do ambiente (o ambiente tem 5 containers, 2 redes, 4 volumes e 3 imagens). `validar-config` 184/0 por nome | `19_encerramento/` |

**Janelas do ambiente:**
- 011, 012 e 013 SUPERUSER, uma por versão;
- 014 BYPASSRLS, uma;
- 016: J1 interrompida mais J2 concluída;
- **sem J3, sem recriação e sem nova tentativa não prevista.**
- Registro completo em `19_encerramento/janelas_todas.txt`.

## 4. Critérios (§E2.4 da missão)

| Critério | Resultado |
|---|---|
| Snapshot e Maven | **OK.** Proveniência completa (99 blobs, S1 × S1″); regressão nova no clone, PERMITIDO nos dois critérios |
| 014 | **OK.** Fixture conferida, T3 20/20, uma janela BYPASSRLS, retirada `0|0|0` |
| 016 / B5 | **OK.** Hash rev5, J1+J2, §19 depois da retirada, B5 OK |
| H02 / H03 | **OK juntos** no `testar` completo; **H02 histórico OK depois do S3** |
| Transporte | **OK.** A imagem em execução contém o JAR e as classes do snapshot; fluxo integrado sem falha da caixa (B8a, B8b, B10 com desfechos simulados) |
| `testar-016` | **OK:** B5, B6, B7, B8a, B8b, B12, SEGREDOS |
| Limites | B4 parcial (só J1 antes do Flyway); B9 fora; B10 e B11 PARCIAL |
| Preservação | **OK.** B12 completo; nenhuma diferença inexplicada em recursos protegidos |
| Encerramento | **OK.** Autorizações temporárias retiradas (S3); privilégios conferidos; ambiente e clone preservados |

## 5. Falhas, erros e divergências preservados (não reclassificados)

1. **STOP E2.1 (183/1).** Caso 9c com caminho relativo a `saidas/hml03`. O clone `lab-consol016-20261002T151225Z` em `f9477405` está preservado. A correção passou por portão próprio (`364d7b69…`; P1–P5).
2. **Erro de auxiliar na §19 (execução 1).** A extração incluiu o `COMMIT;` final da migration, que encerrou a transação `READ ONLY` depois do bloco DO (aviso "no transaction in progress"). O bloco rodou dentro do `READ ONLY` e passou, mas a evidência foi refeita (só leitura) com a extração de 250 linhas, sha igual ao do 016c. A 1ª está em `14_J2/execucao1_erro_auxiliar_commit_incluido/`.
3. **Previsão do Maven:** 9 previstos, 37 reais (§2). Sem efeito no procedimento, que já previa regressão nova se desse PROIBIDO.
4. **Referência do JAR:** o roteiro previa registrar o sha do JAR do clone logo depois do build do `criar`, e eu registrei depois do `concluir-criacao`.
   - O JAR tem mtime 15:43:49Z, dentro da regressão Maven do clone; o `package -DskipTests` do `criar` não o reescreveu.
   - Nenhum Maven rodou no clone depois do `criar`.
   - A prova vale porque compara o JAR **da imagem em execução** com o do clone: idênticos.
5. **Primeira chamada do E1:** erro de aspas no shell (`$'\r'` dentro de `$()`). O comando não rodou e nada foi alterado; foi refeito como script.

## 6. Matriz final

| Item | Estado |
|---|---|
| Aprovações históricas | Preservadas e registradas nos relatórios (adendos MFA-CONSOL-1, 11 documentos). P4 PASS = a decisão histórica do `ensaio-016c`, não reaberta |
| **Conjunto atual validado** | **Sim, no `ensaio-consol-016`:** H03 por perfil, H02 histórico, elegibilidade de porta, manifesto Maven, transporte corrigido e 016 rev5, juntos pelo executor real. Lacunas: B4 completo, B9, gatilho da varredura B10, restauração B11 (missões próprias) |
| Integração Git | **Pronta para decisão:** plano C1–C4 (121 arquivos, 7/8/71/35), com `fetch` necessário antes. O teste corrigido `test_elevacao_historica.sh` (`364d7b69…`) entra no C3 |
| Lista residual do `lab-r2b1` | **Encerrada** (`b1b4a1a`); histórico em `36eabb3` |
| Validação estática | 184/0 (nomes idênticos) no original, no S1 r2 e no S3 |

**Ambientes preservados:**

| Ambiente | Finalidade | Estado |
|---|---|---|
| `ensaio-consol-016` | conjunto atual | **novo**, LIBERADO, 5 serviços no ar, imagem da platform `sha256:e1808e76…` |
| `ensaio-016c` | P4 PASS | intocado (B12) |
| `ensaio-016b` | evidência do STOP de porta | intocado, exceto a leitura do E1 |
| `ensaio-016a` | P4 original | intocado |
| `hml03` | parado, aguarda a 5b | intocado |
| principal, portal-dev, portal-013, `nk016-smoke` | — | intocados |

**Clones:**

| Clone | HEAD | Papel |
|---|---|---|
| `lab-consol016-20261002T152744Z` | S3 `e3a04050` | o ensaio integrado |
| `lab-consol016-20261002T151225Z` | `f9477405` | STOP |
| `lab-r2b1` | `b1b4a1a` | encerramento administrativo |
| `lab-p4`, `lab-p4r2ac`, `lab-r2b0`, `lab-r2b1c`, `lab-h03p016`, `lab-h02hist` | inalterados | — |

Todos sem remoto.

**Original:**
- HEAD `dd74a8f`, stage 0, sem commit ou push;
- 016 `f37d9841…`; `elevacao.txt` LF `7fd92fae…` (011–013);
- na árvore de trabalho entraram nesta missão os adendos, os relatórios e o `test_elevacao_historica.sh` corrigido.

**Próxima missão necessária:** a decisão sobre a integração Git, com o plano C1–C4 e um `fetch` autorizado. Depois do aceite desta consolidação, o próximo trabalho funcional, conforme a missão, é:
1. inventário das rotas da v1;
2. lacunas;
3. contrato HTTP/OpenAPI;
4. regressão HTTP;
5. ambiente para o frontend.

5b, P5, B4 completo, B9, B10, B11 e a rotação da política histórica continuam em missões próprias.

## 7. O que foi verificado por quem

**Pelo executor, nesta missão:**
- todos os resultados do §2 ao §4;
- o pacote final: manifesto em extração nova e varreduras de tokens e de segredos com controles positivos.

**Dependem de revisão independente:**
- a leitura dos relatórios e das evidências;
- a conferência de que os adendos citam fielmente as decisões;
- a decisão sobre o plano Git.

**Pela revisão, já registrado:**
- pacotes `consol1-e0` (46/46), `consol1-e0r1` (17/17) e `consol1-e1e2stop` (45/45);
- os testes do 9c **não** foram reexecutados pela revisão, porque dependem de registros locais.

## Adendo (2026-10-02): ressalvas da revisão final

*Acrescentado depois da aprovação técnica da MFA-CONSOL-1 no escopo executado. O corpo acima não foi alterado.*

1. **Linha "reutilizada" do resumo do `testar` (§3, `17_testar/`) desatualizada.**
   - O `executar.sh` imprime, como texto fixo, "58 testes Maven e E2E 6/6 do commit f235230 (docs/relatorios/2026-09-26-validacao-api-v1.md)".
   - Essa linha **não** sustenta a regressão atual: o número e o documento são de uma validação antiga, e só o hash do commit é preenchido na hora.
   - **A referência válida de regressão desta missão é a execução no clone:** `E2_ensaio/05_maven/clone_verify1`, com 266 testes, 0 falhas, 0 erros, 0 ignorados, PERMITIDO.
   - Registrado como dívida de texto do `executar.sh`, para missão própria. Não altera nenhum resultado.
2. **Auxiliar da prova da imagem** (`E2_ensaio/16_imagem/prova_imagem.sh`).
   - O `ok=0` está dentro de uma substituição de comando (`$( … )`), que roda num subshell e **não altera a variável externa**.
   - Por isso, a linha "CLASSES IDENTICAS: SIM" **isolada não é confiável**: ela diria SIM mesmo com uma classe diferente.
   - **Nesta execução** a conclusão se sustenta pelos dados individuais registrados: as 3 linhas "IGUAL", com os dois hashes impressos lado a lado, e o JAR completo idêntico (`ec9fdb7c…`).
   - **O auxiliar não deve ser reutilizado como critério automático de aprovação antes de corrigido.** A correção (comparação fora de subshell, falha com código ≠ 0) fica pendente e não foi feita nesta missão.

**Aceitos pela revisão:**
- o registro tardio do hash do JAR, como desvio documentado nesta execução (§5, item 4);
- a segunda verificação da §19, que resolve o erro de extração, com a primeira evidência preservada (§5, item 2).
