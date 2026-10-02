# MFA-CONSOL-1: preparação (E0 e E3) e propostas operacionais (E1 e E2)

**ESTADO: PREPARAÇÃO CONCLUÍDA, REVISÃO 1, PARA APROVAÇÃO OPERACIONAL.**

**Revisão 1** (depois do parecer da revisão sobre o pacote `homologacao-016-consol1-e0-20261002T144440Z.zip`, que fica preservado):
- **(1)** snapshot com as dependências de execução e o contrato da rev5: **99 arquivos** em vez de 84 (§2.4, §3.2);
- **(2)** prova da imagem por **conteúdo**: ID da imagem em execução, sha256 do JAR e das classes comparados com o artefato do snapshot (§3.5, passo 7);
- **(3)** redação: o hook `pre-push` é proteção adicional, e a proibição de publicação é explícita (§3.2); o E1 **não** executa recuperação e para para decisão se houver resíduo (§4).
- Sem novos testes de ferramentas.
- Foi executado só o que D1 cobre: consolidação documental, levantamento e preparação do snapshot e do plano Git.
- **Nada operacional foi feito:**
  - nenhum ambiente, clone ou container criado;
  - nenhuma consulta a banco;
  - nenhuma edição ou commit no `lab-r2b1`;
  - nenhuma escrita Git no original.
- E1 e E2 aguardam as decisões D2–D6 (§7).

**Data:** 2026-10-02, a partir de 14:34Z.

**Evidências:** `ops/homologacao/saidas/consol1-20261002T143413Z/`, fora do Git:
- `A_base`: `git status -uall`;
- `B_decisoes`: cópias dos relatórios antes dos adendos e a integridade dos corpos;
- `C_lista`: linhagem, classificação e manifesto;
- `D_proposta`: disponibilidade, porta e redes, lista de lab, leituras do E1;
- `G_git`: distribuição por commit.

**Base de partida, reconferida agora:**

| Item | Referência de partida | Reconferido em 14:34–14:39Z |
|---|---|---|
| Original | `feat/mfa-emergencia`, HEAD `dd74a8f6e9a7b487e5698c11ca026e2c4666177e`, stage vazio | **igual**: HEAD idêntico, stage 0 |
| 016 rev5 | `f37d9841…3732` | **igual** (árvore, LF = bytes) |
| 014 | `5e5726b9…d8fc` | **igual** |
| Lista operacional (LF) | `7fd92fae…370d`, só 011–013 | **igual**, sem diferença no Git |
| Maven reutilizável | verify2, 266/0/0, manifesto de 217 arquivos `e0b5ff19…` | `verificar` na árvore original: IDENTICA / PERMITIDO (na reconciliação). **No clone novo, o resultado esperado é outro: ver §3.4** |
| `validar-config` | 184/0 | referência **por nome** gravada: `D_proposta/validar_config_referencia_184_nomes.txt` (184 OK, 18 seções, da saída `4b847c87…` da WT1) |
| Refs remotas | — | último `fetch` em 2026-09-28T20:54:41Z; **não** provam o estado atual do servidor |

## 1. E0.1: registro das aprovações (feito)

**O que foi feito:**
- 11 adendos datados, "Adendo MFA-CONSOL-1", anexados **sem alterar o corpo**. Prova: `B_decisoes/adendos_integridade.tsv`, com o prefixo de cada arquivo depois do adendo igual, byte a byte, ao arquivo anterior (11/11 SIM). As cópias anteriores estão em `B_decisoes/antes/`.
- Cada adendo traz o pacote com o SHA-256 completo, a decisão com horário UTC e o trecho exato, o escopo, as limitações e a substituição de implementação, sem reclassificar resultados.
- **Origem:** mensagens da responsável na sessão `d31e2723-029b-47ee-a98d-bf0f4f24ed47`. Só trechos de decisão; a transcrição não foi publicada.
- **Correções feitas nos adendos:**
  - "imutável" → "histórica, ancorada e detectável a adulteração", no relatório do H02;
  - a lista residual do `lab-r2b1` → "encerramento administrativo pendente", no relatório da R2b-1 e na reconciliação. STOP ≠ container parado; sem remoto ≠ sem execução local.

### Matriz consolidada das decisões

| Entrega | Pacote (SHA-256) | Decisão (UTC) | Estado | Limitações / pendências que continuaram abertas | Substituída por |
|---|---|---|---|---|---|
| R2b-0 (T1–T3) | `r2b0-…221750Z` `7ddd6713a279e30511e19a6f96d9a9e8112c7b380b93c57f435f85810ab2f2c4` | 10-01 22:26:43 "D1 está aprovado. A R2b-0 fecha PASS" | PASS | `autocrlf=false` obrigatório nos clones | — |
| R2b-0b (H02 pela lista em vigor) | `r2b0b-…223908Z` `33d4800044a12885d374ca7e3d028bb336e26a761dad40f545d884ea6b51c019` | 10-01 22:33:08 "Corrigir H02 antes"; 22:50:28 "Aprovo o novo portão" | PASS | dependência da lista vigente (não bloqueante) | regra substituída pelo H02 histórico (10-02 12:16:45), sem reclassificar |
| R2b-1 (`ensaio-016b`) | `r2b1-…230355Z` `6191948e54dd142530f950cc64d69c81bb473edd4d9f68ce4fc9ba4e5c01528b` | 10-01 23:15:38 "STOP — não aprovado"; passos 1–8 "Aceita e preservada" | **STOP, não aprovado** | **encerramento administrativo da lista do `lab-r2b1` pendente (E1)** | o ensaio integrado aprovado é o `ensaio-016c` |
| R2b-1a (porta) | `r2b1a-…232619Z` `571ec38fbb1ebae38cb94bf66b9e73f4036f05af5968c9e0f06652750dac075b` | 10-01 23:33:20 "Aprovo o gate da R2b-1a" | PASS | — | — |
| R2b-1c (`ensaio-016c` até o STOP) | `r2b1c-…235410Z` `cc79988d39386158386c7d1856cad2df8f25ddc72d304382a62cb39c656501c2` | 10-02 00:28:08 "continuação formalmente autorizada após o STOP" | STOP preservado; **H03 = FALHA para sempre** | — | complementado pela continuação (não reclassifica) |
| R2b-1 / P4 (continuação e adendo) | `r2b1c-final` `944eca5804726e6a4c313789a99ebf70fe325634a3ba18edac496aa5b286d332`; `r2b1c-adendo` `485147a2918a58d5916e664e54cf7b0082ce27b49657044dc3bd6f609fdad1ba` | 10-02 00:46:03 "Aprovo formalmente a R2b-1 e o P4"; 00:53:34 adendo aceito | **PASS** (registrado no §6 da continuação) | B4 parcial, B9 fora, B10/B11 parciais; não autoriza 5b, P5, ativação, commit nem descarte | — |
| H03 pós-016 | `h03p016-…013045Z` `31e6dffb40f4be9bcc11be9b47ee2027865a630c003cc5bf7c7ce541ded2ec7c` | 10-02 01:49:48 "Aprovo o gate do H03 pós-016" | PASS; F-R2b1c-1 CLOSED | incidente do ZIP (alterado `c54216c9…` preservado; canônico restaurado, autorização 03:42:53) | — |
| Manifesto Maven | `maven-manifesto-…023053Z` `22a394271345744eb7d6af524d82d3b4b96a9f7aa9dc6a266b3b14e395ec24cc` | 10-02 03:22:54 (pacote em HOLD); 03:42:53 "Gate Maven PASS completo" | PASS | mutação desfeita durante a execução não detectada (aceito) | — |
| H02 × lista histórica | `h02hist-…043726Z` `61cc9eed67b526645a225cfddb25f345dda1cc522ba2009c8f4bf8724775e204` | 10-02 12:16:45 "Aprovo o gate do H02 histórico. PASS completo" | PASS | terminologia corrigida; rotação da política OPEN; hml03 LEGADO SEM ÂNCORA | — |
| WIN-TRANSPORT-0 | `wintransport0-…123828Z` `98d5b7cbba29b70f7f65cdf7b4b1c5844038abcd98a4f5ed83478a8a18e5c7a7` | 10-02 12:55:26 "Aprovo a WIN-TRANSPORT-0. PASS" | PASS (finding OPEN naquele momento) | pacote descartado `ba7ce5ae…` aceito como falso positivo | finding fechado pela WT1 |
| WIN-TRANSPORT-1 | `wintransport1-…135101Z` `1641cddc7dd90548815492ba4ad4d4d78077aa03581e430222ccde2571f8ae8c` | 10-02 13:33:23 (verify2 autorizado); 14:00:38 "Aprovo a WIN-TRANSPORT-1. PASS completo… CLOSED" | PASS; finding CLOSED | verify1 FALHA preservada, causa INCONCLUSIVA; correção só na árvore | — |

**Aprovação não localizada:** nenhuma. Todas as decisões pedidas foram encontradas e conferidas contra os pacotes, inclusive o SHA-256 citado pela revisão em cada mensagem.

## 2. E0.2: lista fechada do snapshot (feito)

### 2.1 Método

**Linhagem aprovada:** a base é o **S1″** do `ensaio-016c` (clone `lab-r2b1c`, commit `d9b39c16a81d71b44f72a1eb3978b7c2939ff925`, base do P4/R2b-1 PASS). Por cima dele, as mudanças das missões aprovadas depois: H03, Maven, H02, WT0 e WT1, cada uma com o hash "depois" do seu pacote.

**Comparação** (`C_lista/linhagem.tsv`), arquivo a arquivo:
- conteúdo LF da árvore original;
- blob do S1″, lido com `git cat-file` no clone, sem alterá-lo;
- hash aprovado posterior.

| Classe | Arquivos | Significado |
|---|---|---|
| IGUAL_S1 | 428 | conteúdo idêntico ao S1″ (inclui todo o HEAD não alterado) |
| APROVADO_POS | **18** | alterado depois do S1″, **idêntico ao hash aprovado** da última missão que o tocou |
| **DIVERGENTE** | **0** | nenhuma diferença de código inexplicada: **a montagem do snapshot não está bloqueada** |
| FORA_S1 | 31 | 22 relatórios posteriores ao S1″ e 9 de `db/tests/portal-8-2/` (outra missão) |
| SO_NO_S1 | 0 | nada do S1″ desapareceu da árvore; **não há exclusão a fazer** |

**Os 18 aprovados depois do S1″, por missão:**
- **H03:** `h03_perfis.sh`, `test_h03_perfis.sh`.
- **Maven:** `fontes_maven.py`, `regressao_maven.sh`, `test_fontes_maven.py`.
- **H02:** `homologacao.sh` `f43d6c9b…`, `elevacao_historica.sh`, `test_elevacao_historica.sh`, `executar.sh` `fc089034…` (sobre o do H03).
- **WT0:** `correlacao.py`.
- **WT1:**
  - `CaixaHomologacaoMailSender.java` `6fbdc540…` e o IT `11cbeeb1…`;
  - `AquisicaoTravaTest.java`, `VerificaTrava.java`, `test_trava_caixa.sh`;
  - `Harness.java` e `rodar.sh` (sobre os da WT0);
  - `validar_config.sh` `92367faa…` (sobre H03, Maven e H02).

**`db/bootstrap/`:** o único arquivo novo é `aplicar_como_migrador.sh` (`245f667a…`), já no S1″. O `001_organizacao_interna_nekkora.sql` do HEAD continua igual. Nenhuma exclusão.

### 2.2 Classificação de todas as entradas do `git status`

`C_lista/classificacao.tsv`: 4.304 entradas (22 modificadas + 4.282 não rastreadas com `-uall`).

| Classe | Entradas | Destino |
|---|---|---|
| entrega aprovada desta frente | 84 (21 M + 63 ??) | snapshot e integração (o snapshot de execução também leva 15 documentos: §2.4) |
| documento atualizado | 37 (1 M + 36 ??) | integração (commit de documentação), não o snapshot de execução |
| outra missão | 9 (`db/tests/portal-8-2/`) | fora |
| saída de teste | 1.933 (`db/tests/*/saidas/`, `ops/mfa-emergencia/saidas/`) | fora |
| dependência gerada | 2.241 (`api/e2e/email/node_modules/`, 2.240; `ops/homologacao/testes/__pycache__/`, 1) | fora |
| **sem classificação** | **0** | — |

**Não aparecem no `git status`** porque o `.gitignore` os ignora, e ficam fora:
- `.env`;
- `api/*/target/`;
- `api/e2e/saidas/`;
- `ops/homologacao/saidas/`, onde estão as evidências e os segredos de execução.

Os segredos ficam em `~/.nekkora/homologacao/`, fora do repositório.

### 2.3 Manifesto por arquivo

`C_lista/manifesto_lista_fechada.tsv`: 121 arquivos (84 de entrega + 37 documentos). Para cada um:
- caminho, classe e origem (S1 ou missão);
- **sha256 dos bytes**;
- **sha256 LF**;
- fim de linha;
- **blob Git** (`git hash-object --path`, **sem `-w`**: nada foi gravado no repositório).

**Divergência de representação, não de conteúdo:** 10 arquivos estão em CRLF na cópia de trabalho do original (`core.autocrlf=true`), com conteúdo LF idêntico ao S1″:
- `application.yml`;
- `IMPLANTACAO.md`, `README.md`, `compose.homolog.yml`, `exemplo.env`, `lib/segredos.py`, `testes/cliente.py`, `testes/inventario_b12.py`, `testes/test_inventario_b12.py`;
- um relatório da P3.

O blob Git normalizado é o que vai para o snapshot. Consequência para o Maven no §3.4.

**Divergências deliberadas de relatórios** (separadas das mudanças de código): os 11 adendos do §1. São só acréscimos; os corpos estão intactos.

### 2.4 Revisão 1: dependências do snapshot de execução (apontamento da revisão)

**Defeito da versão anterior:** o S1 proposto (HEAD + 84 arquivos de entrega) excluía tudo o que estava classificado como documento. Com isso:
- ficava de fora uma **dependência de execução**: `docs/relatorios/2026-09-29-p3it-anexos/elevacao-016-proposta.patch`, lida pelo `validar_config.sh` (seção 5, linhas 103 e 116);
- e ia junto a **matriz contratual antiga**: no HEAD, `docs/relatorios/2026-09-27-mfa-emergencia-matriz-016.md` é `4930a717…`; a versão da rev5, a mesma do S1″, é `4a6c3ad6f235…`.

**Conferência focalizada** (`H_revisao1/`), sem repetir provas de ferramentas:
1. **Conjunto de caminhos do S1″ aprovado × snapshot proposto:** o S1″ tinha 14 caminhos ausentes do proposto, todos em `docs/relatorios/` (`no_s1duas_fora_do_proposto.txt`).
2. **Conteúdo** de todos os caminhos comuns, proposto × S1″ (`conteudo_proposto_x_s1duas.txt`): 6 diferenças.
   - 5 são deltas aprovados depois do S1″: transporte e IT (WT1), `homologacao.sh` (H02), `executar.sh` (H02/H03), `validar_config.sh` (WT1).
   - **1 é a matriz da 016**, com a versão do HEAD.
3. **Referências de caminho** feitas pelos arquivos que o roteiro executa (`referencias_externas.txt`; grep em `ops/homologacao`, `api/*/src`, `db/`, `docker-compose.modelo.yml`, `api/e2e/Dockerfile.operacao`):
   - **única leitura em execução de arquivo fora dos 84:** `validar_config.sh` → `elevacao-016-proposta.patch`;
   - as demais menções são comentários de migrations, links de README ou texto de mensagem (`executar.sh:528`), e os alvos já estão no HEAD;
   - `db/tests/portal-8-2/` não é referenciado.

**Lista de execução revisada: 99 arquivos** (`C_lista/manifesto_snapshot_execucao_r1.tsv`, com motivo de inclusão por arquivo):

| Grupo | Arquivos | Motivo |
|---|---|---|
| Entregas aprovadas | 84 | como antes |
| `p3it-anexos/elevacao-016-proposta.patch` | 1 | **dependência de execução** do `validar-config` |
| `2026-09-27-mfa-emergencia-matriz-016.md` (`4a6c3ad6…`) | 1 | **contrato da 016 correspondente à rev5** (a versão do S1″) |
| demais documentos do S1″ (13: relatórios P1a, P1c, plano, P2, P3, P3-IT, D-ELEV, G1, P4-proposta e anexos da P3) | 13 | já estavam no S1″ aprovado. Não são consumidos em execução, mas incluí-los faz o snapshot ser **S1″ + deltas aprovados, sem remoção** |

**Propriedade verificada:**
- snapshot r1 = HEAD `dd74a8f` + 99 arquivos;
- **nenhum caminho do S1″ ausente**;
- **13 caminhos novos**, todos aprovados depois do S1″ (H03, Maven, H02, WT0, WT1);
- conteúdo de todos os 99 = S1″ ou o hash aprovado posterior: **0 divergências**.
- O plano de commits (§5) não muda: a matriz já estava no C1, o anexo no C3 e os 13 documentos no C4.

## 3. E0.3: proposta operacional do E2 (para aprovação de D3–D6)

### 3.1 Identificação e disponibilidade (`D_proposta/disponibilidade.txt`, `porta_redes.txt`)

| Item | Proposta | Conferência (14:38Z, só leitura) |
|---|---|---|
| ID | `ensaio-consol-016` | 0 containers, redes ou volumes com o rótulo ou o nome; sem `saidas/ensaio-consol-016`, sem segredos, sem clone `lab-consol*` |
| Porta | **55731** (reserva: 55732) | `porta.py elegivel` (ferramenta aprovada): `PORTA_APTA` (listener, bind e fora das 8 faixas reservadas); sem publicação Docker. **Reconferida pelo próprio `criar` e de novo antes dos serviços** |
| Sub-redes | interna **10.252.90**, borda **10.252.91** | ausentes das 14 redes Docker (em uso: .10/.11 hml03, .80/.81 016a, .82/.83 016b, .86/.87 016c, 172.17–20) e das interfaces e rotas do host. O `criar` confere sobreposição de novo |
| Docker | em execução, servidor 29.7.2 | se estiver parado no início do E2, ligá-lo precisa estar coberto pela decisão. Containers preservados têm política de reinício `no` (`ensaio-016b`, conferido) |

### 3.2 Snapshot (clone novo, sem remoto)

- **Clone:** `../lab-consol016-<instante>/nekkora-platform`, a partir do original **local** (sem rede):
  - `core.autocrlf=false` **antes** do checkout;
  - remoto removido;
  - `remote.pushDefault` e `url.<x>.pushInsteadOf` sem efeito;
  - um hook `pre-push` que sempre falha, como **proteção adicional**. Ele não torna o push impossível (por exemplo, com `--no-verify`).
  - **A proibição de publicação é explícita e independe do hook:** nenhum push, nem por URL, a partir do clone.
- **S1 (revisão 1):** HEAD `dd74a8f` + **exatamente os 99 arquivos** de `C_lista/manifesto_snapshot_execucao_r1.tsv`, nas versões aprovadas (§2.4).
  - Conferências:
    - `diff dd74a8f..S1` = a lista, e cada blob = o `blob_git` do manifesto;
    - **S1 × S1″ do 016c** (`d9b39c1`): nenhum caminho ausente, e só os 18 deltas aprovados (5 alterados e 13 novos).
  - Guarda de fontes limpa.
- **S2:** só `ops/homologacao/implantacao/elevacao_lab_ensaio-consol-016.txt` (`af800301d80e3c90aa7bfae447c9ffb7709851999807ecab9727c542c8a3ada6`). Derivada da lista aprovada do S2″ do 016c (`3d3121c6…d940`); o diff muda **só o vínculo** (`D_proposta/elevacao_lab.diff`):

  ```
  011 38a50984…  012 d3929cc7…  013 c6192bff…   (inalteradas)
  014 5e5726b9a8f58d319b6b17dcbf7801c21e258999b003d25a023680f0d271d8fc tipo=bypassrls ambiente=ensaio-consol-016
  016 f37d984128e8241474319a39c00fa54baed90519169663757c7f799e61b63732 tipo=superuser ambiente=ensaio-consol-016
  ```

- **S3:** retira as linhas 014 e 016 da lista de lab, ao encerrar ou em caso de falha.
- **`elevacao.txt` operacional:** fica intocado no clone, sem diferença desde o HEAD.

### 3.3 Ferramentas que serão usadas (sha256 LF, todas na lista fechada)

| Papel | Arquivo | sha256 |
|---|---|---|
| Executor | `homologacao.sh` | `f43d6c9b620b0e92b0ea972e457b88f0b812c2e2f02455f0ea6efdd6aa8e648b` |
| Implantação, janelas, recuperação | `implantacao/implantar.sh` | `60f81c85a2530b08d78b097d13c64e732a8850eb922960bfd0cd0106be15ff1e` |
| Parser da lista | `lib/lista_elevacao.sh` | `367a6f51bf069489a16641f0e078cfd8435dc7c906af877d9dbdf046aafe1451` |
| Pré-voos oficiais | `preflight/014.sql` / `preflight/016.sql` | `9eebb93b…f4ee54` / `560767ab…3772d` |
| Semeadura e conferência da 014 | `sql/fixture_014_pre.sql` / `testes/semente_014.sh` | `6684af85…31271c` / `28dabb85…edb34` |
| Porta | `lib/porta.py` | `a64c641f71ee5dad3f8adc47dbcb29135a43c79b617479840714d7dfb0751425` |
| Fotografia histórica / H02 | `lib/elevacao_historica.sh` | `79baae18dac25d4c48264205780c3897891ddd2fc4e6cbaea05703899e4bca4d` |
| Suíte `testar` (H02 histórico, H03 por perfil) | `testes/executar.sh` + `testes/h03_perfis.sh` | `fc089034…dba` + `b552099a…71b68` |
| `testar-016` | `testes/h016.sh` | `47954b73359eb1fa1e04a3c03220fae5cd5165f27e19f025e640a86b3d949050` |
| Validação estática | `testes/validar_config.sh` | `92367faac67d72a79612239674df4465721bc0d8128a78e1fc4270551adc2b9d` |
| Inventário B12 | `testes/inventario_b12.py` | `ef6b1a7198a5b51e8263db57f001a2997beb967f29d30cbba2cb5623e0f8dcf1` |
| Regressão Maven | `regressao_maven.sh` + `lib/fontes_maven.py` | `5f137a25…26a6a` + `49b3e2d5…861bc` |
| Modelo de banco | `db/bootstrap/aplicar_como_migrador.sh` | `245f667ac2e2e25a9fb13d1c6ca6f7aa9d81567c734e4f0bf5ab68e024bc70d1` |
| Migrations | 014 / 016 rev5 | `5e5726b9…d8fc` / `f37d9841…3732` |
| Transporte | `CaixaHomologacaoMailSender.java` | `6fbdc5400e01964334243b87b71ce8ed1f80c3d6b9ab5cbd3f310c70ab6427ba` |

### 3.4 Maven no snapshot: previsão e proposta

**Regra aprovada:** reaproveitar o verify2 só se `fontes_maven.py verificar <verify2> <clone>` der PERMITIDO. Essa ferramenta compara **bytes** (`fontes.sha256`).

**Previsão, a confirmar no E2.1:**
- **9 arquivos** do manifesto Maven do verify2 estão em CRLF na árvore original (lista no §2.3, exceto o relatório da P3, que não entra no conjunto).
- No clone com `autocrlf=false` eles terão os bytes LF do blob.
- Portanto o resultado esperado é **`ARVORE_ATUAL=DIFERENTE (9)` → PROIBIDO**.
- A diferença é **só de fim de linha**: `fontes_lf.sha256` deve coincidir. Mas a regra é por bytes, e não presumo equivalência.

**Proposta:**
- registrar esse `verificar`;
- se der PROIBIDO, rodar **uma regressão nova no próprio clone S1**, `regressao_maven.sh <saida> -B verify` (~16 min), **antes** do `criar`.
- Aceite: `REAPROVEITAMENTO=PERMITIDO` e `verificar` PERMITIDO no clone.
- Se falhar, vale a regra da WT1: registrar e parar, sem repetição automática.

### 3.5 Roteiro de comandos (o fluxo aprovado do `ensaio-016c`, com o id novo)

Todos rodam no clone. `L` = lista de lab S2. Cada passo grava o comando, o código, os horários e a saída na evidência.

**0. Pré-voo do snapshot:**
- `validar-config` no S1, com a contagem **por nome** comparada à referência de 184;
- `verificar` do Maven e, se necessário, a regressão (§3.4);
- B12 inicial;
- `netsh` excludedportrange e `porta.py elegivel 127.0.0.1 55731` registrados.

**1. `criar`:**

```
HOMOLOG_ATE=016 HOMOLOG_TRANSPORTE=homologacao HOMOLOG_REDE_INTERNA=10.252.90 HOMOLOG_REDE_BORDA=10.252.91 HOMOLOG_PORTA=55731 HOMOLOG_ELEVACAO_ARQ=L HOMOLOG_ENSAIO_PAUSA=antes-de-elevar@014:<ev>/pausa014 homologacao.sh criar ensaio-consol-016
```

- Em segundo plano, até a pausa.
- Conferir a fotografia histórica: lista = `L`, proveniência, `ambiente.env` e log, as 3 âncoras.

**2. Semeadura e conferência:**
- `homologacao.sh semear-pre014 ensaio-consol-016`, depois `conferir-semente014 antes`;
- identidade do cluster, banco e id conferida pelas guardas do T2;
- só contagens e digests nas evidências.

**3. Liberar a pausa:**
- o mesmo executor faz a 014 BYPASSRLS → `0|0|0` → 015 → parada antes da 016;
- depois, `conferir-semente014 depois` (20/20 esperado, por `admin_cluster`).

**4. Recusas B3 da 016**, antes de qualquer concessão:
- (a) sem `HOMOLOG_AUTORIZA_ELEVACAO`;
- (b) sha errado;
- (c) `ambiente=ensaio-outro`;
- (d) sem vínculo.
- As listas transitórias de (b), (c) e (d) ficam **fora do clone**, na evidência, e são removidas no encerramento.
- Esperado: código 2, 2, 2 e 3, e antes = depois em todas (0 janelas da 016, histórico 001–015, `0|0|0`).

**5. J1:**

```
HOMOLOG_AUTORIZA_ELEVACAO=1 HOMOLOG_ELEVACAO_ARQ=L HOMOLOG_ENSAIO_PAUSA=antes-do-flyway@016:<ev>/pausa homologacao.sh implantar ensaio-consol-016
```

- Em segundo plano. `kill -9` **só no PID dono da trava** (`implantar.sh`).
- Esperado BLOQUEADO; `testar`, `testar-016`, `preparar-016` e `concluir-criacao` recusados.
- `homologacao.sh recuperar ensaio-consol-016` → `0|0|0`.
- Reconciliação `READ ONLY` (pré-voo 016, 016 fora do histórico, catálogo idêntico ao de antes da J1).
- Só continua se a 016 estiver comprovadamente não aplicada.

**6. J2:**

```
HOMOLOG_AUTORIZA_ELEVACAO=1 HOMOLOG_ELEVACAO_ARQ=L homologacao.sh implantar ensaio-consol-016
```

- Esperado LIBERADO 001–016.
- §19 completa por `admin_cluster` em `READ ONLY` **depois** da retirada, com donos e ACLs (B5).
- Janelas da 016 = exatamente 2.

**7. `concluir-criacao` (uma vez):**
- porta conferida antes dos serviços;
- registrar ID e digest de cada imagem e `fontes_imagens.txt`.
- **Prova do transporte corrigido** (revisão 1: por conteúdo, na imagem efetivamente executada):
  1. **Imagem em execução:** `docker inspect` do container `platform` do `ensaio-consol-016` em execução, para registrar o **ID da imagem** (`.Image`, `sha256:…`) que ele usa. É esse ID que é inspecionado, não a tag.
  2. **Extração:**
     - `docker create` (sem `start`) de um container temporário **a partir desse ID de imagem**;
     - `docker cp` do JAR da aplicação;
     - remoção do temporário **por ID**.
     - Registrar o **sha256 do JAR** e o caminho dele na imagem.
  3. **Classe da imagem:** extrair do JAR (incluindo um eventual JAR aninhado do Spring Boot) o `com/nekkora/platform/mail/CaixaHomologacaoMailSender.class`, e as classes internas `CaixaHomologacaoMailSender$*.class`, e registrar o sha256 de cada uma.
  4. **Referência do snapshot.** A imagem não compila nada: `api/e2e/Dockerfile` copia o JAR que o Maven do host gerou **no próprio clone** (`COPY ${JAR} /app/app.jar`, com `JAR=nekkora-platform/target/nekkora-platform-0.1.0-SNAPSHOT.jar`). Por isso:
     - logo depois do build do `criar`, e antes de qualquer outro Maven no clone, registrar o sha256 de `api/nekkora-platform/target/nekkora-platform-0.1.0-SNAPSHOT.jar` e de `target/classes/com/nekkora/platform/mail/CaixaHomologacaoMailSender*.class` do clone;
     - registrar o JDK do build (`mvnw -v` no clone): o `criar` gera o JAR em `homologacao.sh:286` (`mvnw -q -DskipTests package`) e só depois constrói as imagens (`:287`);
     - a regressão Maven do clone (§3.4) roda **antes** do `criar`. Se o `target/` for reconstruído depois do `criar`, a referência é a registrada no momento do build.
  5. **Aceite:**
     - **sha256 do `/app/app.jar` da imagem em execução = sha256 do JAR do clone registrado no build**;
     - **sha256 de cada `CaixaHomologacaoMailSender*.class` extraída da imagem = sha256 da correspondente em `target/classes` do clone**;
     - mais as características da correção: o método `adquirirTrava` e a constante "nao adquirida no prazo (acesso negado)".
     - **Se os hashes diferirem** (por exemplo, por JDK ou flags de compilação distintos), registrar como divergência e **parar o E2 neste ponto** para decisão, sem substituir a prova pela busca de texto.

**8. `testar`:** `homologacao.sh testar ensaio-consol-016`, **sem `HOMOLOG_TESTES`**.
- H02 pelo modo histórico: 5 concluídas (011, 012, 013, 014 BYPASSRLS, 016/J2) + 1 interrompida (016/J1), recuperada.
- H03 pelo perfil 016.
- H01, H04–H09 e R015.

**9. `testar-016`:** `homologacao.sh preparar-016 ensaio-consol-016`, depois `H016_B8B=1 homologacao.sh testar-016 ensaio-consol-016`.
- B5, B6, B7, B8a, B8b, B12 e SEGREDOS;
- B10 e B11 parciais;
- transporte de homologação, sem e-mail real.

**10. Encerramento:**
- **S3:** retirar 014 e 016 da lista de lab e remover as listas transitórias de B3;
- conferir que a fotografia histórica continua com os bytes da criação;
- `HOMOLOG_TESTES=h02 homologacao.sh testar ensaio-consol-016` **sem** `HOMOLOG_ELEVACAO_ARQ`: o H02 tem de passar de novo pela fotografia. Complementa o passo 8, não o substitui.

**11. Conferência final:**
- migrador: 5 atributos `false`, 0 participações, 0 ACL de parâmetro, 0 sessões;
- B12 início × fim;
- `validar-config` no S3, por nome;
- preservar o ambiente e o clone.

### 3.6 Limites de privilégio (§6.2 da missão) e parada

| Versão | Tipo | Limite |
|---|---|---|
| 011, 012, 013 | SUPERUSER | 1 janela concluída por versão |
| 014 | BYPASSRLS | 1 janela; hash `5e5726b9…`; `ambiente=ensaio-consol-016` |
| 016 | SUPERUSER | até 2: J1 interrompida antes do Flyway + J2 concluída; hash rev5; vínculo exato |

**Parada:** em falha, timeout ou resultado desconhecido:
- registrar comando, código, horário, PID e estado, e parar a implantação;
- `recuperar` só para a retirada de privilégios;
- S3 da lista;
- comunicar imediatamente se a retirada não puder ser confirmada.

**Proibido nesse caso:**
- repetir `concluir-criacao`;
- J3;
- recriar o ambiente;
- `repair`;
- SQL por fora.

## 4. Proposta E1: encerramento da autorização residual do `lab-r2b1` (para aprovação de D2)

### Levantamento já feito, só leitura (`D_proposta/e1_*.txt`)

**O clone:**
- caminho `…/lab-r2b1-20261001T225153Z/nekkora-platform`;
- HEAD `36eabb31d0ab4a804768cbbeedf5c0e06c62c591` (S2′), ramo `r2b1`;
- `git status` limpo; 0 remotos e 0 URLs configuradas; `autocrlf=false`.

**Lista ativa** `elevacao_lab_ensaio-016b.txt`:
- 658 bytes, `b3323116de8e78087d2a4b6f713398182f6a8b4094dba7f9f58b25df46387759`;
- 011–013, mais `014 … tipo=bypassrls ambiente=ensaio-016b` e `016 … tipo=superuser ambiente=ensaio-016b`.

**Vínculo histórico:**
- o `ensaio-016b` é **anterior** ao mecanismo de fotografia: não existe `elevacao_criacao/`;
- a fonte histórica é a 1ª linha do log de criação (`… ALTERNATIVA DE ENSAIO sha256 b3323116…`) mais o commit `36eabb3`;
- `ambiente.env`: `HOMOLOG_ATE=016`, porta 56721, redes 10.252.82/83.

**Montagens** (`docker inspect`, só leitura):
- a lista **não** está montada em nenhum container;
- os containers montam só `config/` (nginx, pg_hba, pg_ident) do clone, mais segredos e volumes;
- a lista é lida apenas pelo executor no host;
- estado: postgres, redis, platform e portal `running` desde 2026-10-01, gateway `created`, 0 reinícios, política `no`.

### Proposta

1. **(D2) Consulta administrativa só de leitura** no `ensaio-016b`, por `admin_cluster`, em `BEGIN TRANSACTION READ ONLY … ROLLBACK`: os 5 atributos do `migrador_nekkora`, as participações, a ACL de parâmetro e as sessões do migrador.
   - É feita pelo mesmo auxiliar de leitura usado no encerramento do 016c.
   - A retirada de privilégios **não** é inferida da lista.
2. **Cópia da lista** e do log de criação para a evidência, com os sha256.
3. **Commit administrativo local "S3′-pendente"** no `lab-r2b1`:
   - remove só as linhas 014 e 016 da lista ativa, mantendo 011–013;
   - antes e depois registrados;
   - sem remoto e sem push.
   - O commit `36eabb3` e o log de criação continuam como fonte histórica. **Nada é reescrito.**
4. **Sem** nova janela, sem `implantar`, **sem `recuperar`**, sem recriar serviços e sem trocar ferramentas ou imagens do clone. Nenhuma exceção dentro do E1.
5. **Se** o passo 1 mostrar privilégio residual ou estado inesperado: registrar e **parar o E1** antes do passo 3, para **decisão da responsável** sobre a recuperação. O E1 não executa a recuperação; ela fica como proposta, sem migrations.

**Aceite:** retirada administrativa rastreável e o estado do migrador informado com a evidência. **Não** torna o `ensaio-016b` aprovado.

## 5. E3: plano preliminar de integração Git (sem escrita no original)

### 5.1 Estado pré-integração

- Ramo `feat/mfa-emergencia`, HEAD `dd74a8f`, stage 0.
- `dd74a8f` está contido em `origin/main` segundo as refs locais. **Último fetch em 2026-09-28T20:54:41Z.**
- O publicado (pelas refs antigas) tem a 016 **rev4** (`25bef818…`). A rev5 aprovada e todo o trabalho desde 2026-09-28 estão só na árvore de trabalho.
- **Antes de propor merge ou publicação é preciso atualizar as refs remotas** (`git fetch`). Isso deve constar do escopo da etapa de integração; não foi feito aqui. O baseline do servidor **não** pode ser afirmado com as refs atuais.

### 5.2 Commits propostos

Distribuição completa dos 121 arquivos em `G_git/plano_commits.tsv`, com blob Git por arquivo. Hashes de commit não existem e não são inventados.

| # | Commit | Arquivos | Conteúdo | Depende de | Validação aplicável |
|---|---|---|---|---|---|
| C1 | base de banco da 016 e suporte de teste compartilhado | 7 | 016 rev5; `db/bootstrap/aplicar_como_migrador.sh`; `docker-compose.modelo.yml`; `db/tests/mfa-emergencia-016/run.sh`; `RealDatabase.java`; `OpsBanco.java`; `matriz-016.md` (contrato) | — | `db/tests/mfa-emergencia-016/run.sh`; compilação de core/ops |
| C2 | produto: transporte de homologação e configuração | 8 | `PlatformConfig`, `EmergencyNotices`, `MailSender`, `application.yml`, `CaixaHomologacaoMailSender`; testes sem dependência do runner: `AquisicaoTravaTest`, `CaixaHomologacaoConfigIT`, `CaixaLinuxSonda` | C1 | testes do módulo platform que não usam o runner |
| C3 | runner de homologação, ferramentas e ITs que dependem deles | 71 | `ops/homologacao/**` (executor, implantar, preflights, lib, sql, compose, modelos, testes, wintransport, `regressao_maven.sh`); `api/e2e/Dockerfile.operacao`; ITs do `nekkora-ops` (p1c, docker-shim) e da platform (`CaixaHomologacaoMailSenderIT`, `ClienteH016IT`, que usam `lib/indice_caixa.py` e `testes/cliente.py`); `docs/relatorios/2026-09-29-p3it-anexos/elevacao-016-proposta.patch` (o `validar-config` depende dele) | C1, C2 | `validar-config` (por nome) e `regressao_maven.sh -B verify` completo nesse commit |
| C4 | documentação da frente | 35 | relatórios de 2026-09-28 a 2026-10-02, com adendos, e os anexos da P3 | — | revisão |

**Ordem C1 → C2 → C3 → C4.** C2 não tem teste que dependa do runner. O runner (C3) configura a platform com o transporte de C2. Os ITs que usam o runner entram com ele.

**Ativação separada:** o `elevacao.txt` operacional **não muda** em nenhum commit (011–013). Linhas 014/016 só numa decisão própria de ativação.

**Fora da integração:**
- `db/tests/portal-8-2/` (outra missão);
- saídas de `db/tests/*/saidas` e `ops/mfa-emergencia/saidas`;
- `node_modules`, `__pycache__`, `target/`, `.env`;
- `ops/homologacao/saidas/` (evidências e segredos);
- os pacotes de `../relatorios/`.

**Artefatos e evidências:** os pacotes ZIP ficam fora do Git. Os relatórios os referenciam por SHA-256 e propõe-se guardá-los em armazenamento de evidências próprio, a decidir.

**Riscos a tratar na integração:**
- (a) `api/e2e/email/node_modules/` **não** está no `.gitignore`: proibir `git add -A`/`.` e adicionar só pela lista fechada. Um ajuste do `.gitignore` pertence à frente de e-mail E2E.
- (b) Com `core.autocrlf=true` no original, os 10 arquivos CRLF entram normalizados para LF pelo Git. O blob esperado é o do manifesto. Conferir `git diff --cached --stat` e os blobs antes de cada commit.
- (c) A validação do C3 deve ser a regressão por `regressao_maven.sh` na árvore do commit, não o verify2.

### 5.3 Próximos passos da integração (decisão posterior, fora deste quadro)

1. `fetch` autorizado e reconferência do baseline remoto.
2. Commits C1–C4 num ramo novo, a partir de `dd74a8f`, pela lista fechada.
3. Validação por commit.
4. PR para revisão.
5. Merge e publicação só com decisão sobre o plano concreto.

## 6. Ressalvas desta preparação

- A conferência das decisões usa a transcrição da sessão e os pacotes. A revisão neste chat examinou a reconciliação; **revisão independente** dos adendos e da lista fechada é recomendada (o pacote desta entrega traz os arquivos para isso).
- A disponibilidade de porta e redes é do momento da consulta. O `criar` reconfere.
- Não houve consulta a banco algum. O estado do migrador do `ensaio-016b` vem do registro do passo 8 da R2b-1 e precisa da leitura do E1 para ser afirmado hoje.

## 7. Quadro de decisões (não preenchido pelo executor)

| Código | Decisão proposta | Situação |
|---|---|---|
| D1 | E0/E3: consolidar documentos e preparar o snapshot e o plano Git | **Executado** com base no pedido de preparação desta missão (2026-10-02, mensagem que abre a MFA-CONSOL-1) |
| D2 | E1: leitura administrativa no `ensaio-016b`, retirada da lista residual e commit S3′-pendente só no clone antigo | **a registrar pela responsável** |
| D3 | Criar o clone novo sem remoto; commits locais S1/S2/S3 só nele; regressão Maven no clone se o `verificar` der PROIBIDO (§3.4) | **a registrar** |
| D4 | Criar o `ensaio-consol-016` (porta 55731, redes 10.252.90/91) com a fixture sintética pré-014 e os limites do §3.6 | **a registrar** |
| D5 | Executar o roteiro do §3.5, as fixtures B8a/B8b, os testes e a recuperação só para retirada de privilégios; container temporário de inspeção da imagem removido por ID | **a registrar** |
| D6 | Retirar as autorizações temporárias e preservar o ambiente e o clone ao fim, inclusive em falha | **a registrar** |

**Fora deste quadro:** integração Git no original, ativação operacional, descarte, produção, 5b (que pode ser decidida à parte) e P5.
