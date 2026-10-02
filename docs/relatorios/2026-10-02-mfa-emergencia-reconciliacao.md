# Reconciliação das entregas e pendências: do encerramento da 5a até 2026-10-02T14:15Z

**ESTADO: LEVANTAMENTO, PARA REVISÃO.** Nada foi executado nesta etapa: nenhum teste, migration, janela, descarte ou operação Git de escrita.

**Fontes usadas:**
- relatórios em `docs/relatorios/`;
- pacotes em `../relatorios/`;
- registros do executor nos clones de laboratório e em `ops/homologacao/saidas/`;
- a transcrição da sessão em que as revisões foram recebidas, referida abaixo como **[T]**: `~/.claude/projects/C--Users-nicol-nekkora-nekkora-platform/d31e2723-029b-47ee-a98d-bf0f4f24ed47.jsonl`, com horário UTC de cada mensagem.

**Consultas feitas agora, todas somente leitura:**
- sha256 e manifesto de cada pacote, numa extração temporária nova;
- `git rev-parse/status/diff --cached/branch -r`, sem `fetch`;
- `fontes_maven.py verificar` (só hash da árvore);
- `git rev-parse` nos clones.

O estado dos ambientes **não** foi reconsultado (§2.3).

## 1. Quadro de entregas

**Legenda de "executado":**
- **[I]** teste isolado: unitário, sem Docker, ou base descartável;
- **[F]** função extraída do script real e exercitada fora dele;
- **[R]** execução integrada pelo executor real (`homologacao.sh`) num ambiente.

**"Aprovação"** só quando há manifestação da revisão, com referência. Onde a aprovação está **só** em [T], digo isso.

### R2b: proposta, portões e ensaios

**Proposta R2b** (`2026-10-01-mfa-emergencia-r2b-proposta.md`):
- Implementado: nada; documento de proposta.
- Executado: —.
- Aprovação: [T] 2026-10-01T21:45:30Z: "aprovar a R2b-0 e manter a R2b-1 bloqueada até o portão".
- Pendências: —.

**R2b-0: T1 pré-voo da 014, T2 semeadura, T3 conferência** (`…-r2b0.md`):
- Implementado: `preflight/014.sql`, `fixture_014_pre.sql`, `semente_014_estado.sql`, `semente_014.sh`, `homologacao.sh` (`semear-pre014`, `conferir-semente014`), `validar_config` seção 12.
- Executado:
  - [I] base descartável 28/28: positivo, contraprova sem BYPASSRLS e 7/7 mutações;
  - [R] `ensaio-r2b0`, rodada A (3 recusas da 014) e rodada B (fluxo até a parada antes da 016, T3 20/20);
  - `validar-config` 142/0.
- Aprovação: [T] 2026-10-01T22:26:43Z, "D1 está aprovado. A R2b-0 fecha PASS".
- Pendências:
  - o relatório na árvore continua dizendo "PARA REVISÃO NO PORTÃO" (§3.4);
  - `ensaio-r2b0` removido por ID;
  - o clone `lab-r2b0` está preservado.

**R2b-0b: H02 com expectativa pela lista em vigor** (`…-r2b0b-h02.md`):
- Implementado: `executar.sh` (`h02_esperadas`), `validar_config` seção 13.
- Executado:
  - [F] `h02_esperadas`/`h02_janelas` extraídas: 16/16, com registros reais de 016a, hml03, A6b e R2b-0;
  - `validar-config` 147/0;
  - **sem [R]** nesta entrega.
- Aprovação: [T] 2026-10-01T22:50:28Z, "Aprovo o novo portão… S1′ em 20 caminhos… 147/0".
- Pendências: o comportamento pela lista em vigor foi **substituído** pela entrega H02 histórica (abaixo).

**R2b-1: `ensaio-016b`** (`…-r2b1.md`):
- Implementado: nada de ferramenta; ensaio com S1′ `d056145` e S2′ `36eabb3`.
- Executado: [R] passos 1–8 OK (semeadura, 014 BYPASSRLS, B3 4/4, J1 com recuperação, J2, §19), depois **STOP no passo 9** (`concluir-criacao`, porta 56721 numa faixa reservada do Windows, F-R2b1-1).
- Aprovação: STOP classificado como correto e opção A escolhida em [T] 2026-10-01T23:15:38Z. **Não aprovado como ensaio.**
- Pendências:
  - `ensaio-016b` preservado no estado do STOP;
  - S3′ nunca feito: o clone `lab-r2b1` (HEAD `36eabb3`) ainda tem a lista de lab com 014/016, só no clone e sem remoto;
  - descarte pendente de decisão.

**R2b-1a: elegibilidade de porta** (`…-r2b1a-porta.md`):
- Implementado: `lib/porta.py`, `test_porta.py`, `homologacao.sh` (`criar` e `concluir-criacao`), `validar_config` seção 14.
- Executado:
  - [I] 17/17;
  - host real 8/8 sem containers;
  - `validar-config` 154/0, com execução real do `criar` recusada;
  - [R] depois, no `ensaio-016c`.
- Aprovação: [T] 2026-10-01T23:33:20Z, "Aprovo o gate da R2b-1a".
- Pendências: o relatório continua "PARA REVISÃO NO PORTÃO" (§3.4).

**R2b-1c: `ensaio-016c`, STOP no passo 9 (H03)** (`…-r2b1c.md`):
- Implementado: nada de ferramenta; S1″ `d9b39c1` (22 caminhos) e S2″ `e01884f`.
- Executado: [R] passos 1–9: `concluir-criacao` OK; `testar` com **H02 OK** (pela lista em vigor) e H01, H04 a H09 e R015 OK; **H03 FALHA**, então STOP.
- Aprovação: continuação autorizada em [T] 2026-10-02T00:28:08Z, "sem reclassificar".
- Pendências: a **H03 FALHA histórica fica preservada para sempre**.

**R2b-1c continuação, passos 10–15, e adendo** (`…-r2b1c-continuacao.md`, §6):
- Implementado: nada de ferramenta; S3″ `fbd5cdb`.
- Executado:
  - [R] `preparar-016`, `testar-016` (B5, B6, B7, B8a, B8b e B12 OK; B10 e B11 PARCIAL);
  - `validar-config` 154/0 em S2″ e S3″;
  - encerramento com o migrador `0|0|0`;
  - Maven: a C2 (256/0) reaproveitada por reconstrução de hash (§4.7).
- Aprovação: **registrada no próprio relatório (§6, adendo)** e em [T] 2026-10-02T00:46:03Z: "Aprovo formalmente a R2b-1 e o P4". Adendo aprovado em [T] 00:53:34Z.
- Pendências: as do §6 do adendo (H03, H02, Maven, transporte, 5b, P5, ativação, descarte).

### Demais missões do período

**H03 pós-016** (`…-h03-pos-016.md`):
- Implementado: `executar.sh` (`h03`), `h03_perfis.sh`, `test_h03_perfis.sh`, `validar_config` seção 15.
- Executado:
  - [I] 25/25 sem Docker e 12/12 em bases descartáveis;
  - [R] `ensaio-h03a` (015) e `ensaio-h03b` (016) com `HOMOLOG_TESTES=h03`: H03 OK nos dois perfis, mais os controles N1–N8;
  - `validar-config` 162/0.
- Aprovação: [T] 2026-10-02T01:49:48Z, "Aprovo o gate do H03 pós-016… F-R2b1c-1 passa para CLOSED".
- Pendências:
  - o H03 **não** rodou na suíte completa de um ambiente com todas as ferramentas atuais (§4.4);
  - ambientes de ensaio removidos; o clone `lab-h03p016` está preservado.

**Manifesto de fontes Maven** (`…-manifesto-fontes-maven.md`):
- Implementado: `lib/fontes_maven.py`, `regressao_maven.sh`, `test_fontes_maven.py`, `validar_config` seção 16.
- Executado:
  - [I] 18/18, com repositório sintético e `mvnw` falso;
  - Maven real só `-B validate` (0 testes, PROIBIDO por definição);
  - `validar-config` 170/0.
- Aprovação:
  - [T] 2026-10-02T03:22:54Z: ferramenta PASS, **pacote em HOLD** (o ZIP errado foi anexado);
  - [T] 03:42:53Z: "Gate Maven PASS completo".
- Pendências: primeiro uso com testes reais foi na WIN-TRANSPORT-1 (§4.7).

**H02 × lista histórica** (`…-h02-lista-historica.md`):
- Implementado: `lib/elevacao_historica.sh`, `test_elevacao_historica.sh`, `homologacao.sh` (`criar`, fotografia), `executar.sh` (`h02`), `validar_config` seção 17.
- Executado:
  - [I] 28/28;
  - [F] legado com registros reais de 016a, 016c e hml03;
  - [R] `ensaio-h02a`/`h02b` (`HOMOLOG_ATE=015`, `HOMOLOG_TESTES=h02`) 15/15, com contraste contra o H02 anterior;
  - `validar-config` 177/0.
- Aprovação: [T] 2026-10-02T12:16:45Z, "Aprovo o gate do H02 histórico. PASS completo". Ajuste terminológico: "histórica, ancorada e detectável a adulteração", não "imutável".
- Pendências:
  - o relatório na árvore ainda usa "imutável" (§1); a terminologia aprovada está só em [T];
  - rotação da política histórica: OPEN;
  - [R] só em 001–015 (§4.3).

**WIN-TRANSPORT-0** (`…-win-transport-0.md`):
- Implementado: só instrumentação, `testes/wintransport/` (`Harness.java`, `rodar.sh`, `correlacao.py`).
- Executado: [I] harness com cópias verbatim, execuções 5–7 (P1/P2 reproduzem; H2/H4/H5 com `desfecho:192`), sem Docker.
- Aprovação: [T] 2026-10-02T12:55:26Z, "Aprovo a WIN-TRANSPORT-0. PASS", com o achado ainda OPEN naquele momento.
- Pendências: o impacto em Linux ficou INCONCLUSIVO naquela entrega e foi coberto pela prova curta da WT1.

**WIN-TRANSPORT-1** (`…-win-transport-1.md`):
- Implementado: `CaixaHomologacaoMailSender.adquirirTrava`, escritor do IT, `AquisicaoTravaTest`, harness (C1–C3, H6, balanço), `test_trava_caixa.sh`, `VerificaTrava.java`, `validar_config` seção 18.
- Executado:
  - [I] harness no Windows (0 exceções em 640 mil chamadas sob disputa);
  - prova Linux em container (sem [R]: a caixa foi exercitada pelo harness e pelos ITs, não por um ambiente de homologação);
  - `validar-config` 184/0;
  - Maven verify1 **FALHA** (preservada) e **verify2 PERMITIDO** (266/0/0).
- Aprovação: [T] 2026-10-02T14:00:38Z, "Aprovo a WIN-TRANSPORT-1. PASS completo… CLOSED". Repetição única do verify2 autorizada em [T] 13:33:23Z.
- Pendências:
  - causa do verify1 INCONCLUSIVA;
  - **as imagens dos ambientes preservados contêm o transporte anterior à correção** (§4.6).

## 2. Estado atual

### 2.1 Repositório original (consultas Git só de leitura, sem `fetch`)

| Item | Valor |
|---|---|
| Ramo / HEAD | `feat/mfa-emergencia` / `dd74a8f6e9a7b487e5698c11ca026e2c4666177e` ("docs: README da raiz…") |
| Stage | **0** |
| Alterações sem commit | **22** arquivos rastreados modificados e **103** entradas não rastreadas: todo o trabalho de 2026-09-28 em diante, incluindo a 016 rev5 e todas as ferramentas acima |
| Publicação | Pelas refs locais, `dd74a8f` está contido em `origin/main`. O ramo local não tem ramo remoto correspondente. **Último `fetch`: 2026-09-28T20:54:41Z.** Situação atual do remoto **não reconferida** |
| O que está publicado da 016 | O blob da 016 no HEAD é a **revisão 4** (`25bef818a7d38c465fffcc5821c1bf9f924ef3cade0f29ec7b79f5b1711fbe61`), presente em `origin/main` segundo as refs locais. **Nenhum commit, stage ou push foi feito no período** |

### 2.2 Migrations e lista operacional

**016 na árvore de trabalho:** `f37d984128e8241474319a39c00fa54baed90519169663757c7f799e61b63732`, a revisão 5 aprovada (bytes = LF). **Não commitada.**

**014:** `5e5726b9a8f58d319b6b17dcbf7801c21e258999b003d25a023680f0d271d8fc`, inalterada.

**`ops/homologacao/implantacao/elevacao.txt`:**
- sem diferença no Git;
- cópia de trabalho com CRLF, 1.488 bytes, sha bruto `eb98911a23ecd4bb1f0c7dcb103f50655948c6d5edc9fa378f5cdff59a5b0e6d`;
- sha LF `7fd92faed3a8d66eb297d727806518a2983572d90eae0633bf4ddc5a7778370d` (o valor citado nos relatórios);
- conteúdo: 14 linhas de comentário e só as três autorizações SUPERUSER:

```
011 38a509845cb4459311b7edfda135ad1ca46203af7387fa45f1e356b200e87ef9
012 d3929cc76261debb47740bb0167d1e3438e00b14e01eca2a793716e5ce8321f4
013 c6192bff305f3888710ef9430751452b7a48a5bc7078691a16e16ddebb4c14d9
```

**Sem 014 e sem 016:** a ativação operacional não aconteceu.

### 2.3 Ambientes preservados

Situação pelo último registro: o inventário B12 de fim da WIN-TRANSPORT-1 (`ops/homologacao/saidas/wintransport1-20261002T125616Z/A_base/inventario_fim.jsonl`, coletado por volta de 13:50Z). **Não reconferido depois disso.**

| Ambiente | Propósito | Última situação registrada |
|---|---|---|
| `ensaio-016a` | ensaio do P4 (016 rev4; 014 sem BYPASSRLS, F-R2a-1); levantamento 5a | postgres `running`; platform, portal, gateway e redis `exited` |
| `ensaio-016b` | R2b-1, evidência do STOP de porta (F-R2b1-1) | postgres, redis, platform e portal `running`; gateway `created`. Banco LIBERADO 001–016 |
| `ensaio-016c` | ensaio integrado aprovado (R2b-1 / P4 PASS) | os 5 serviços `running`; LIBERADO 001–016 |
| `hml03` | homologação anterior (runner anterior ao P3), aguardando a 5b | todos os containers `exited` desde 2026-09-27 |
| `principal`, `portal-dev`, `portal-013` | bases de desenvolvimento (5a) | `running` |
| `nk016-smoke` | anterior ao período | `exited` |
| Descartáveis do período (`ensaio-r2b0` A/B, `ensaio-h03a/b`, `ensaio-h02a/b`, bases de prova) | — | **removidos por ID**, com B12 conferido em cada missão |

Segredos em `~/.nekkora/homologacao/`: `ensaio-016a`, `ensaio-016b`, `ensaio-016c` e `hml03`. Nunca empacotados (varreduras de 607 valores).

**Clones de laboratório**, todos sem remoto e com `git status` limpo. HEAD conferido agora:

| Clone | HEAD | autocrlf | Propósito |
|---|---|---|---|
| `lab-p4-20260929T192708Z` | `1c2f26b` (S3) | true | P4 / `ensaio-016a` |
| `lab-p4r2ac-20261001T174850Z` | `9f786fc` | true | A6b da R2a-C |
| `lab-r2b0-20261001T215832Z` | `f0f582c` | true | proveniência do `ensaio-r2b0` |
| `lab-r2b1-20261001T225153Z` | `36eabb3` (S2′) | false | `ensaio-016b`. **Lista de lab com 014/016 ainda presente (sem S3′)** |
| `lab-r2b1c-20261001T233433Z` | `fbd5cdb` (S3″) | false | `ensaio-016c` |
| `lab-h03p016-20261002T010356Z` | `4e5b097` | false | proveniência do H03 |
| `lab-h02hist-20261002T034828Z` | `c2b4396` | false | proveniência do H02 histórico |

### 2.4 Janelas por ambiente

Fonte: `janelas_de_elevacao.txt` do ambiente (011–014) e de cada `implantacao-*` (016), mais `recuperacao-*.txt`.

**`ensaio-016a`** (2026-09-29, clone `lab-p4`):
- 011, 012 e 013 SUPERUSER, cada uma com fim `(0|0|0)`.
- **Nenhuma janela da 014.** A 014 rodou sem BYPASSRLS (F-R2a-1).
- 016 rev4: **J1** às 23:00:55.319Z, sem fim (interrompida); `recuperacao-20260929T230307Z.txt` dá "migrador 0|0|0"; **J2** de 23:03:54.286Z a 23:03:58.890Z `(0|0|0)`.
- Estado final do migrador: 0/0/0.

**`ensaio-016b`** (2026-10-01, `lab-r2b1`):
- 011–013 SUPERUSER;
- **014 BYPASSRLS** de 22:56:57.616Z a 22:57:02.609Z `(0|0|0)`;
- 016 rev5: **J1** às 22:58:52.860Z, interrompida; `recuperacao-20261001T225948Z` dá 0|0|0; **J2** de 23:00:20.656Z a 23:00:26.382Z `(0|0|0)`.
- Privilégios conferidos no passo 8: 5 atributos `false`, 0 participações, 0 sessões.
- **Sem encerramento formal (STOP antes do S3′).**

**`ensaio-016c`** (2026-10-01/02, `lab-r2b1c`):
- 011–013 SUPERUSER;
- **014 BYPASSRLS** de 23:40:42.192Z a 23:40:46.933Z `(0|0|0)`;
- 016 rev5: **J1** às 23:42:27.335Z, interrompida; `recuperacao-20261001T234311Z` dá 0|0|0; **J2** de 23:43:48.278Z a 23:43:54.035Z `(0|0|0)`.
- Encerramento (passo 15, 2026-10-02 ~00:40Z): 5 atributos `false`, 0 participações, 0 ACL, 0 sessões.

**`hml03`** (2026-09-26):
- 011, 012 (sha `fd758edf…`, a variante CRLF da época) e 013 SUPERUSER, todas com `(0|0|0)`;
- nenhuma 014 nem 016 registrada;
- estado final do migrador 0/0/0.
- Modo H02: "LEGADO SEM ÂNCORA".

**Descartáveis:**
- `ensaio-r2b0`: 011–013 e 014 BYPASSRLS na rodada B;
- `ensaio-h03b`: inclui a 016;
- `ensaio-h02b`: inclui a 014.
- Os registros estão nas evidências das missões; os ambientes foram removidos.

## 3. Evidências e substituições

**Todos os pacotes conferidos agora** numa extração nova. "Manifesto" = entradas OK / falhas / arquivos fora do manifesto.

### R2b e P4

| Pacote | SHA-256 | Relatório | Manifesto |
|---|---|---|---|
| `homologacao-016-frenteb5a-20261001T204222Z.zip` | `efc4ccd9b6c520633b47709394c7155829ce357ba379f032b69b5bd2ebf31251` | 5a (sem o §7, posterior) | 32/0/0 |
| `homologacao-016-r2bprop-20261001T212753Z.zip` | `05facb6c4c461ad8ffbd9170f6309c330260f74629d9c4541b90756e7d2beefa` | proposta R2b (com o adendo da 5a) | 12/0/0 |
| `homologacao-016-r2b0-20261001T221750Z.zip` | `7ddd6713a279e30511e19a6f96d9a9e8112c7b380b93c57f435f85810ab2f2c4` | R2b-0 | 180/0/0 |
| `homologacao-016-r2b0b-20261001T223908Z.zip` | `33d4800044a12885d374ca7e3d028bb336e26a761dad40f545d884ea6b51c019` | R2b-0b | 21/0/0 |
| `homologacao-016-r2b1-20261001T230355Z.zip` | `6191948e54dd142530f950cc64d69c81bb473edd4d9f68ce4fc9ba4e5c01528b` | R2b-1 (STOP) | 85/0/0 |
| `homologacao-016-r2b1a-20261001T232619Z.zip` | `571ec38fbb1ebae38cb94bf66b9e73f4036f05af5968c9e0f06652750dac075b` | R2b-1a | 34/0/0 |
| `homologacao-016-r2b1c-20261001T235410Z.zip` | `cc79988d39386158386c7d1856cad2df8f25ddc72d304382a62cb39c656501c2` | R2b-1c (STOP) | 105/0/0 |
| `homologacao-016-r2b1c-final-20261002T003939Z.zip` | `944eca5804726e6a4c313789a99ebf70fe325634a3ba18edac496aa5b286d332` | continuação, passos 10–15 | 141/0/0 |
| `homologacao-016-r2b1c-adendo-20261002T004907Z.zip` | `485147a2918a58d5916e664e54cf7b0082ce27b49657044dc3bd6f609fdad1ba` | adendo §6 | 9/0/0 |

### Ferramentas e transporte

| Pacote | SHA-256 | Relatório | Manifesto |
|---|---|---|---|
| `homologacao-016-h03p016-20261002T013045Z.zip` (canônico) | `31e6dffb40f4be9bcc11be9b47ee2027865a630c003cc5bf7c7ce541ded2ec7c` | H03 pós-016 | 244/0/0 |
| `homologacao-016-maven-manifesto-20261002T023053Z.zip` | `22a394271345744eb7d6af524d82d3b4b96a9f7aa9dc6a266b3b14e395ec24cc` | manifesto Maven | 34/0/0 |
| `homologacao-016-h02hist-20261002T043726Z.zip` | `61cc9eed67b526645a225cfddb25f345dda1cc522ba2009c8f4bf8724775e204` | H02 histórico | 157/0/0 |
| `homologacao-016-wintransport0-20261002T123828Z.zip` | `98d5b7cbba29b70f7f65cdf7b4b1c5844038abcd98a4f5ed83478a8a18e5c7a7` | WIN-TRANSPORT-0 | 109/0/0 |
| `homologacao-016-wintransport1-20261002T135101Z.zip` | `1641cddc7dd90548815492ba4ad4d4d78077aa03581e430222ccde2571f8ae8c` | WIN-TRANSPORT-1 | 285/0/0 |

**Substituições, incidentes e divergências.** Todos os originais estão preservados em `../relatorios/` e nas evidências.

1. **Pacote da R2b-1 (`r2b1`):** é a evidência do STOP do `ensaio-016b`. **Não é substituído.** O ensaio integrado aprovado é o `ensaio-016c` (`r2b1c` + `r2b1c-final` + `r2b1c-adendo`).
2. **`r2b1c`** (STOP do H03) **complementado** por `r2b1c-final` (continuação) e `r2b1c-adendo` (decisão formal). Os três valem juntos; nenhum reclassifica o outro.
3. **Incidente do ZIP H03:**
   - `…h03p016-20261002T013045Z.INCIDENTE-alterado-c54216c9.zip` (`c54216c9b16459a51447628776c536968ddf092f4c41bba0316b7bc7a0969207`, 246 entradas: as 245 originais + o ZIP Maven) foi preservado;
   - o nome canônico foi restaurado para `31e6dffb…`, com autorização em [T] 03:42:53Z;
   - registro em `…INCIDENTE.txt`;
   - conferido agora: o manifesto interno do alterado também fecha 244/0.
4. **Pacote descartado:** `homologacao-016-wintransport0-20261002T123117Z.DESCARTADO-falso-positivo-varredura.zip` (`ba7ce5ae3ca6bb26d307de7c9e5c88f34475e642c3e7628fba1c32b62df4d415`, manifesto 125/0/0).
   - Falso positivo de token nos nomes temporários da v1 do harness. Não foi entregue.
   - Substituído por `…123828Z` (`98d5b7cb…`).
5. **HOLD do pacote Maven:** foi anexado um ZIP errado (o H03 alterado). O HOLD foi removido depois que a revisão extraiu o pacote Maven correto de dentro dele.
6. **Maven da WIN-TRANSPORT-1:**
   - `verify1` FALHA/PROIBIDO, preservado em `wintransport1-…/M_maven/verify1`;
   - substituído **como evidência de portão** pelo `verify2` (repetição única autorizada), sem reclassificar o verify1.
7. **Relatórios alterados depois do pacote** (adendos, com o corpo histórico preservado no pacote):
   - 5a, §7: o pacote `frenteb5a` não o contém; o pacote `r2bprop` contém a versão com adendo;
   - continuação R2b-1c, §6: o pacote `r2b1c-final` não o contém; o `r2b1c-adendo` contém.
8. **Execuções inválidas preservadas** (erro de harness ou do controle, não do produto):
   - R2b-0 `T_base/execucao1`;
   - R2b-1a, 1ª verificação real e um controle refeito;
   - H03 `T_base/execucao1` e C5;
   - Maven, 1ª `validar-config` (âncora "mtime");
   - H02, 1ª `validar-config` e execução 1;
   - WT0, execuções 1–4;
   - WT1, `validar_config_1_FALHA_separador_do_controle.txt`.

## 4. Critérios ainda abertos: resposta objetiva

### 4.1 R2b: implantação integrada concluída?

**Sim, no `ensaio-016c`, com aprovação registrada:**
- relatório da continuação, §6;
- [T] 2026-10-02T00:46:03Z.

**O que foi comprovado pelo executor real:**
- 014 com BYPASSRLS e semeadura;
- 016 com J1 interrompida e J2;
- `concluir-criacao` e `testar-016`.

**Limites:**
- o `ensaio-016b` **não** concluiu (STOP);
- o `ensaio-016c` rodou com as ferramentas do S1″. **Não** incluía as ferramentas posteriores (H03 por perfil, H02 histórico, correção da caixa) (§4.4).

### 4.2 B5: §19 completa aprovada após a retirada dos privilégios?

**Sim.** No `ensaio-016c`:
- passo 8: §19 por `admin_cluster` em `READ ONLY`, depois da retirada, `SECAO19_OK`;
- passo 11: B5 OK (37 funções, 21 tabelas, 9 políticas).
- Aprovação junto com R2b-1/P4 ([T] 00:46:03Z).
- Também OK no `ensaio-016b`, passo 8, que não foi aprovado como ensaio.

### 4.3 H02: execução completa por `testar`, incluindo a lista histórica?

**Parcial.**
- **Com a regra anterior** (lista em vigor): H02 OK pelo `testar` completo do `ensaio-016c`, com 5 concluídas + 1 interrompida recuperada.
- **Com a regra histórica aprovada:** pelo executor real só em `ensaio-h02a/h02b`, com `HOMOLOG_ATE=015` e `HOMOLOG_TESTES=h02`.
- Para ambientes com a 016, só por função extraída sobre registros reais ([F]: 016a e 016c em modo legado com fonte explícita).
- **Não houve** `testar` completo de um ambiente 001–016 criado com a fotografia histórica.

### 4.4 H03 e `testar-016`: resultados e limites

**H03:**
- **FALHA** no `ensaio-016c` (histórico, preservado);
- com a ferramenta atual: OK pelo executor real em 015 (`ensaio-h03a`) e 016 (`ensaio-h03b`), com `HOMOLOG_TESTES=h03`, mais os controles N1–N8.
- **Limite:** nunca rodou numa suíte `testar` completa junto com o H02 histórico e as demais versões atuais.

**`testar-016`:** OK no `ensaio-016c`:
- B5, B6, B7, B8a, B8b e B12 OK;
- B10 e B11 PARCIAL;
- B4 sem caso próprio;
- B9 fora;
- SEGREDOS OK.

**Limite comum:** ainda não houve um ensaio integrado com o **conjunto atual** de ferramentas e produto. As árvores de trabalho de hoje (`homologacao.sh` `f43d6c9b…`, `executar.sh` `fc089034…`, transporte corrigido) não passaram juntas por `criar` → `testar` → `testar-016`.

### 4.5 B12: inventário completo e preservação comprovada?

**Sim, missão a missão:**
- em todas as missões do período com recursos, início × fim idêntico por ID fora do ambiente (R2b-0, R2b-1 início × STOP, R2b-1c início × STOP e início × fim, H03, H02);
- e, sem recursos, WT0 e WT1;
- B12 também OK como critério do `testar-016` no `ensaio-016c`.

**Limite:** a preservação **depois** do último inventário (WT1, ~13:50Z) não foi reconferida.

### 4.6 Transporte Windows: causa, correção e validação?

**Sim**, com aprovação em [T] 12:55:26Z (WT0) e 14:00:38Z (WT1, CLOSED na ferramenta/produto atual):
- **causa** identificada na WT0;
- **correção** implementada;
- **validação** concluída na WT1: harness 0/640 mil, controles C1–C3, prova Linux, `validar-config` 184/0 e Maven verify2 PERMITIDO.

**Limites:**
- a correção está **só na árvore de trabalho**, sem commit;
- as imagens dos ambientes preservados (`d9b39c1`, `d056145`, `327c4d4`) **não** a contêm;
- a causa do verify1 continua INCONCLUSIVA.

### 4.7 Regressão Maven: qual execução e quais fontes ela comprovou?

**`wintransport1-…/M_maven/verify2`** (2026-10-02, de 13:33:48Z a 13:49:19Z):
- 266 testes, 0 falhas, 0 erros, reator completo;
- `REAPROVEITAMENTO=PERMITIDO`;
- `fontes.sha256` `e0b5ff19419d3011400620fb01aa827e1385c4cd8507fb7dd29a2e435a4f29be`: **217 arquivos**, árvore suja, 20 alterados e 61 não rastreados, todos no manifesto.
- Conferido agora: `verificar` dá `ARVORE_ATUAL=IDENTICA` e **`PERMITIDO` na árvore atual**.
- É a **única** regressão com manifesto e testes do período.

**Anteriores:**
- a C2 (256/0, 2026-10-01) sustentou o `ensaio-016c` por **reconstrução de hash** (150 arquivos, `hashes_clone_arvore_S1.txt` `bfa8dfd5…`), sem manifesto no instante;
- o `-B validate` da missão Maven não tem testes.

**O que o verify2 cobre:** a árvore de trabalho, não um commit, nem as imagens dos ambientes.

### 4.8 Demais itens

| Item | Situação |
|---|---|
| **5b** (`hml03`, levantamento de sessões sem marcador, como na 5a) | **HOLD.** Não autorizado. `hml03` `exited` desde 2026-09-27 (último registro) |
| **B4 completo** | **PARCIAL / pendente.** Só a interrupção antes do Flyway (J1) e a recuperação estão provadas (P4, 016b, 016c). **Faltam** a interrupção durante o SQL da 016 e o COMMIT com resultado desconhecido: cenário e decisão próprios |
| **B9** (prazos em tempo real) | **Pendente**, nunca executado. Mini missão com ambiente próprio mantido pelo período |
| **B10** (despachante e varredura) | **PARCIAL.** Desfechos simulados pela caixa. A varredura não tem agendamento nem gatilho no ambiente: PENDENTE |
| **B11** (backup e restauração) | **PARCIAL.** O dump por `backup_nekkora` foi feito. A restauração num segundo cluster está pendente (A7 não autorizada em 2026-09-29) |
| **P5** | **HOLD**, não autorizado |
| **Ativação operacional 014/016** | **HOLD.** `elevacao.txt` sem 014/016. Pré-requisito pendente: rotação da política histórica (OPEN) |
| **Integração Git** | **Não autorizada e não iniciada.** Todo o trabalho desde 2026-09-28 está sem commit. O publicado (`origin/main`, pelas refs locais de 2026-09-28) tem a 016 **rev4** |
| **Descarte** | Nada autorizado: `ensaio-016a/b/c`, clones e evidências preservados |

## 5. Constatações desta reconciliação

1. **Aprovações só na transcrição.** As aprovações de R2b-0, R2b-0b, R2b-1a, H03, Maven, H02, WT0 e WT1 estão só em [T]. Os relatórios na árvore continuam com "PARA REVISÃO". Só a 5a (§7) e a R2b-1c continuação (§6) têm a decisão registrada no documento.
2. **Terminologia do H02** ("imutável") ainda no relatório. Ajuste aprovado só em [T].
3. **Clone `lab-r2b1`** com a lista de lab contendo 014/016 sem S3′. Inócuo (sem remoto, ambiente parado no STOP), mas é a única lista de lab não retirada.
4. **Nenhum ensaio integrado com o conjunto atual de ferramentas e produto** (§4.4). Os portões aprovados provaram cada peça. A combinação não passou junta pelo executor real.
5. **Imagens dos ambientes preservados anteriores à correção da caixa** (§4.6).

## 6. Próxima ação necessária

**Revisão desta reconciliação.** Depois, decisão sobre o item seguinte da fila definida: **5b / `hml03`**, que continua sem GO.

**Antes do portão de ativação 014/016**, e não antes da 5b, recomendo decidir também:
- **(a)** registrar nos relatórios as aprovações que hoje só existem em [T], como adendos datados, sem alterar os corpos;
- **(b)** um ensaio integrado de consolidação com o conjunto atual (§5.4);
- **(c)** o plano de integração Git, porque o publicado tem a 016 rev4 e a rev5 aprovada só existe na árvore de trabalho.

**Nada disso está autorizado por este levantamento.** Este documento não declara P4 aprovado por inferência: a aprovação do P4 é a registrada no §6 da continuação da R2b-1c. Também não declara produção liberada.

## Adendo MFA-CONSOL-1 (2026-10-02): registro da decisão da revisão

*Acrescentado na missão MFA-CONSOL-1 (E0.1). O corpo acima não foi alterado; o pacote citado continua sendo a evidência daquele momento. Origem das citações: mensagens da responsável na sessão Claude Code `d31e2723-029b-47ee-a98d-bf0f4f24ed47`, horário UTC. Só trechos de decisão.*

*(Correção de redação, não de decisão.)*

| Onde | Correção |
|---|---|
| §5, item 3 ("Inócuo (sem remoto, ambiente parado no STOP)…") | Ler: **encerramento administrativo pendente** da lista de lab do `lab-r2b1`. STOP não significa container parado: no último registro, 4 dos 5 containers do `ensaio-016b` estavam em execução (postgres, redis, platform, portal; gateway `created`). A ausência de remoto não impede a execução local do executor a partir do clone, e a lista é lida pelo executor no host (não está montada em nenhum container). Tratado na etapa E1 da MFA-CONSOL-1 |
| §1 (aprovações "só em [T]") | Os adendos MFA-CONSOL-1 passam a registrar essas decisões nos próprios relatórios, com pacote, SHA-256, horário e trecho da decisão |
| §1 e §4.3 (H02 "imutável" no relatório) | Corrigido por adendo em `2026-10-02-mfa-emergencia-h02-lista-historica.md` |
