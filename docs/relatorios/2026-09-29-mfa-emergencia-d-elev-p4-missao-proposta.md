# Mini missão D-ELEV / P4 — elevação da 016 e ensaio integrado em `ensaio-016a`

**ESTADO: AUTORIZADA (A1–A8 registradas na §10 em 2026-09-29T19:20:48Z); P4 AINDA NÃO INICIADO.**

O texto da proposta, por si, não autoriza nada. As autorizações valem **somente nos termos registrados na §10**,
por manifestação expressa da pessoa responsável. Fora deles (J3, recriação, B11 restauração, descarte, push, qualquer
operação no repositório original) é necessária nova decisão. O início do P4 depende de comando expresso. Data da
redação: 2026-09-29.

**Revisão 2 (2026-09-29):** incorpora os seis ajustes da revisão técnica da redação 1 (§11). A revisão técnica
recomendou a Opção B, mas **não concedeu autorização operacional**: o quadro da §10 continua sem preenchimento.
Redação 1: SHA-256 `7a42ef087fc5a2e7e9cd326d4c357a58ddd739dc9bb107e0fd66c532a68ad3f8`.

## 1. Base

| Item | Valor |
|---|---|
| HEAD / branch | `dd74a8f6e9a7b487e5698c11ca026e2c4666177e` / `feat/mfa-emergencia`; nada em stage; **67** arquivos alterados ou novos fora das saídas (P1b–P3-R015), sem commit |
| Migration 016 | `db/migrations/016_recuperacao_emergencial_mfa.sql`, SHA-256 **`25bef818a7d38c465fffcc5821c1bf9f924ef3cade0f29ec7b79f5b1711fbe61`** |
| Linhas autorizadas atuais | `011 38a509845cb4459311b7edfda135ad1ca46203af7387fa45f1e356b200e87ef9`, `012 d3929cc76261debb47740bb0167d1e3438e00b14e01eca2a793716e5ce8321f4`, `013 c6192bff305f3888710ef9430751452b7a48a5bc7078691a16e16ddebb4c14d9`, preservadas **byte a byte** |
| `elevacao.txt` operacional | conteúdo LF `7fd92faed3a8d66eb297d727806518a2983572d90eae0633bf4ddc5a7778370d` (cópia de trabalho CRLF `eb98911a…0e6d`), só 011–013 |
| Proposta da linha 016 | `docs/relatorios/2026-09-29-p3it-anexos/elevacao-016-proposta.patch`; acrescenta `016 25bef818a7d38c465fffcc5821c1bf9f924ef3cade0f29ec7b79f5b1711fbe61 ensaio-016a` (+2 linhas de comentário). Conteúdo resultante LF **`47bb959662063868351a3f15b4248fb876d8f8cb2e14f9404bbb46f606eef1e3`**, já reconhecido por `validar_config.sh` como aprovado **sem ativá-lo** |
| Pacotes aprovados | P2 `4452fc55…f02cc`; P3 rev. 3 `1c50a3d8…17c6`; P3-IT `409b7bdc…4b92`; P3-ITc `1a8931c2…212b`; P3-ITd `1b1a366b…0642`; P3-R015 `07654115…e47e` |
| Runner (estado atual) | 016 com vínculo obrigatório ao id exato e pré-voo oficial obrigatório (`implantacao/preflight/016.sql`, código 0 **e** `PREFLIGHT_OK`); lista validada antes de qualquer ação; `criar` com a 016 para depois da 015 (parada exata); pausa `antes-do-flyway@<versao>`; `concluir-criacao`; H02 integrado (janela interrompida exige recuperação própria) |

## 2. Decisões de autorização

> **Decisões registradas na §10 em 2026-09-29T19:20:48Z.** Cada uma exige manifestação expressa da pessoa responsável, registrada no
> quadro da §10. A falta de qualquer decisão marcada como necessária impede o início do P4.

| Id | Decisão | Necessária? | Recomendação técnica |
|---|---|---|---|
| **A1 — D-ELEV** | Autorizar janelas temporárias de `SUPERUSER` para `migrador_nekkora` **somente** no cluster de `ensaio-016a`, **somente** para a 016 (`25bef818…be61`), **no máximo duas** (J1 interrompida, J2 concluída), com as condições das §§4–6 | Sim | Autorizar duas janelas da 016, restritas a `ensaio-016a`, nos termos da decisão P3 (rev. 3) |
| **A2 — Ativação temporária da linha 016** | Aplicar `elevacao-016-proposta.patch` à lista **que o `criar`/`implantar` do P4 usarão**, e retirá-la ao término ou na interrupção (§§4.9, 7). Onde ela vale depende de A3 | Sim | Linha temporária **somente no clone** (Opção B, commit S2), retirada por S3 |
| **A3 — Baseline Git da execução** | Escolher **Opção A** (integração local revisada, commits locais sem push) **ou Opção B** (snapshot com manifesto explícito e **três** commits — S1, S2, S3 — **só** num clone local) (§3) | Sim | **Opção B**, com S1/S2/S3 e manifesto explícito de inclusões e exclusões (§3.4) |
| **A4 — Correção da guarda de fontes** | Antes do P4, corrigir o `criar` para que a guarda cubra **todas as entradas das três imagens** (inclusive `Dockerfile.operacao` e os fontes de teste da `nekkora-ops` usados na imagem `operacao`), recuse também arquivos **não rastreados** e use **a mesma lista** para identificar as imagens (§3.1, achados G1/G1b), com testes próprios | Sim | Corrigir e revisar G1 **antes** de iniciar o P4 |
| **A5 — Criação e execução de `ensaio-016a`** | Criar o ambiente com os parâmetros da §4.0 e executar a sequência da §4, **incluindo a fixture declarada de elegibilidade dos contatos de B8a** (§4.7) | Sim | Executar a sequência após cumprir os pré-requisitos (A1–A4) |
| **A6 — B8b** | Executar B8b (fixture temporal **declarada** de solicitação, `H016_B8B=1`), distinta da fixture de contatos de B8a | Opcional | Incluir B8b com a fixture declarada, para concluir o fluxo integrado |
| **A7 — B11, restauração** | Restaurar o dump num **segundo cluster descartável**. Exige superusuário **desse** cluster (`sslinfo`) e papéis de cluster pré-criados; **não** é coberto por A1 | Opcional | Deixar a restauração para mini missão própria: B11 fica **parcial** (só dump) |
| **A8 — Destinação** | (i) retirar a linha 016 no encerramento (S3, §7); (ii) **manter `ensaio-016a` e o clone** até a revisão das evidências; (iii) descartar ambiente e clone (P5 do plano) em etapa própria, com F17, o clone **depois** do `destruir` (§3.5) | Sim (i) e (ii); (iii) em etapa própria | Retirar a linha ao encerrar; manter ambiente e clone até a revisão |

## 3. Baseline da aplicação e guarda de fontes commitados

O ensaio completo **tem de usar os fontes revisados, inclusive as alterações P1c da API**: o transporte de
homologação e a configuração do despachante são pré-requisito de B8a e B10. **O clone com a API de `03bc72c` usado em
P3-IT/P3-R015 não serve e não será reutilizado.**

O `criar` exige fontes principais da API sem alteração não commitada e identifica as imagens por
`HOMOLOG_COMMIT = git log -1` dos caminhos da API. Hoje o último commit da API é `03bc72c`.

### 3.1 Achado G1 — a guarda não vê arquivos não rastreados

A guarda usa `git diff --quiet HEAD -- api/pom.xml api/*/pom.xml api/*/src/main api/e2e/Dockerfile` e **não detecta
arquivos novos não rastreados** nas fontes principais. Hoje existe um:
`api/nekkora-platform/src/main/java/com/nekkora/platform/mail/CaixaHomologacaoMailSender.java`. Sem correção, uma
imagem poderia ser construída com esse arquivo e etiquetada com um commit que não o contém.

**Achado G1b — a guarda e a identificação não cobrem a imagem `operacao`, e usam listas diferentes.** Conferência
das entradas efetivas do build (`homologacao.sh` 259–266, `compose.homolog.yml`, `api/e2e/Dockerfile*`):

| Imagem | Como é construída | Entradas que determinam o conteúdo |
|---|---|---|
| `platform`, `portal` | `./mvnw -DskipTests package` (reator inteiro) + `compose build`, `api/e2e/Dockerfile` com `ARG JAR` | `api/pom.xml`, `api/*/pom.xml`, `api/*/src/main` (core, ops, platform, portal), `api/e2e/Dockerfile`, wrapper Maven (`api/mvnw`, `api/.mvn/`) |
| `operacao` (só com `HOMOLOG_ATE=016`) | `dependency:copy-dependencies` + `test-compile -pl nekkora-ops` + `compose --profile operacao build`, `api/e2e/Dockerfile.operacao` | Além das anteriores: `api/e2e/Dockerfile.operacao` e `api/nekkora-ops/src/test` — a imagem copia `FerramentaHomologacao*.class`, `IdpSimulado*.class` e `Pki*.class` de `target/test-classes`, e o `test-compile` compila todo o diretório (inclusive `OpsBanco.java`, alterado, e os testes P1c) |

Hoje: (i) a lista do `git diff` (linha 168) não contém `Dockerfile.operacao` nem `api/nekkora-ops/src/test`;
(ii) a lista do `git log -1` que define `HOMOLOG_COMMIT` (linha 167) é **outra**: omite também `api/nekkora-ops/src/main`,
`api/nekkora-ops/pom.xml` e o wrapper. Uma alteração só nesses caminhos mudaria a imagem sem mudar a tag.

`api/nekkora-platform/src/test` também é compilado (`test-compile -pl nekkora-platform`), mas **não entra em imagem**:
fornece as ferramentas do ensaio (`ClienteH016IT`, `CaixaLinuxSonda`…). Fica coberto pelo manifesto do snapshot (§3.4),
não pela identificação das imagens. O contexto de build é `api/` inteiro (sem `.dockerignore`), mas as imagens só
copiam os artefatos acima; `api/e2e/email/node_modules` e `api/e2e/saidas` não chegam a elas.

**Proposta A4:**
- **uma única lista** `FONTES_IMAGENS` (`lib/fontes_imagens.sh`), com caminhos explícitos, sem glob:
  `api/pom.xml`, `api/mvnw`, `api/.mvn`, `api/e2e/Dockerfile`, `api/e2e/Dockerfile.operacao`,
  `api/nekkora-ops/src/test` e, para cada módulo de `api/pom.xml`, `api/<módulo>/pom.xml` e `api/<módulo>/src/main`.
  Vale para qualquer `HOMOLOG_ATE` (a mesma lista sempre); os módulos têm de ser exatamente os `<module>` do pom;
- a guarda recusa, antes de criar recursos: alteração na árvore ou em stage (inclusive exclusão), arquivo não
  rastreado **inclusive ignorado** (`git ls-files --others` sem `--exclude-standard`) e marca
  assume-unchanged/skip-worktree;
- `HOMOLOG_COMMIT = git log -1 --format=%h -- $FONTES_IMAGENS` (a mesma lista);
- o `criar` grava `fontes_imagens.txt` com `HOMOLOG_COMMIT`, `HEAD`, a árvore `<commit>:api` e a lista. O registro
  do SHA-256 dos jars/classes copiados e do id de cada imagem depende de Docker e **ficou fora** da mini missão G1/G1b
  (entrega pequena, testada sem Docker); no P4 ele é feito no pré-voo/encerramento manual (§4.0, §8), salvo decisão de
  incluí-lo no runner;
- testes em repositório Git temporário (`testes/guarda_fontes_imagens.sh`, chamado pela seção 10 do
  `validar_config.sh`): recusa por arquivo alterado, excluído, em stage (inclusive só no stage), novo não rastreado,
  novo ignorado, assume-unchanged/skip-worktree e módulo fora da lista; passagem com fontes commitadas; mudança
  commitada em cada entrada atualiza `HOMOLOG_COMMIT`; cobertura conferida contra os Dockerfiles e compose reais.
  Relatório: `2026-09-29-mfa-emergencia-g1-guarda-fontes-imagens.md`.

A correção é entregue e revisada **antes** do P4, como pacote próprio, e seus arquivos entram no snapshot com os
hashes revisados. Nas duas opções abaixo, depois do commit correspondente, a guarda corrigida tem de passar **sem**
exceção. No clone da Opção B, `target/` começa vazio (excluído do snapshot), então nenhuma classe antiga entra pelos
globs do `Dockerfile.operacao`.

### 3.2 Arquivos da API envolvidos (estado de 2026-09-29, reconferidos na execução)

| Situação | Arquivo | SHA-256 |
|---|---|---|
| M (principal) | `api/nekkora-platform/src/main/java/com/nekkora/platform/config/PlatformConfig.java` | `7b225584c9d3f36571d64db75ccb886ba15f90702c56d1184b591093eecafb29` |
| M (principal) | `api/nekkora-platform/src/main/java/com/nekkora/platform/emergency/EmergencyNotices.java` | `736bf3e543f39dc0e9f82b8b90581ee0e998b79d285beb05329db9ee0470284d` |
| M (principal) | `api/nekkora-platform/src/main/java/com/nekkora/platform/mail/MailSender.java` | `df585470fc8a0a6abf15174913a3cc74ce511299738aecc318b5636427904b17` |
| M (principal) | `api/nekkora-platform/src/main/resources/application.yml` | `73ced1ce0093b71b9964e26add3252542126e407f693f4bd60bd52ca0823f080` |
| ?? (principal) | `api/nekkora-platform/src/main/java/com/nekkora/platform/mail/CaixaHomologacaoMailSender.java` | `2d81e52d5a7c1aa9e9f44d4d20b91d87b5eb7928fc2e32121572677d0302aeeb` |
| ?? | `api/e2e/Dockerfile.operacao` | `1e1c0d359f9268afbc7c7aaea3734d9cc52610b3d2e6260e0ef31b8127a8fb32` |
| M (teste) | `api/nekkora-ops/src/test/java/com/nekkora/ops/testsupport/OpsBanco.java` | `08d1c3b1e0deac28c9b774fdc17f3a9f1e36de765ea85ed4cc9c87e3564238a2` |
| ?? (teste) | `api/nekkora-ops/src/test/java/com/nekkora/ops/homologacao/FerramentaHomologacao.java` | `adb3c2ccde181017f3f423b7f36d10c6f50d901a3f98633ebb60afd8a2356315` |
| ?? (teste) | `api/nekkora-ops/src/test/java/com/nekkora/ops/homologacao/p1c/*` (9 arquivos) e `api/nekkora-ops/src/test/resources/homologacao/docker-shim.sh` | manifesto P1c; reconferidos na execução |
| ?? (teste) | `api/nekkora-platform/src/test/java/com/nekkora/platform/mail/{CaixaHomologacaoConfigIT, CaixaHomologacaoMailSenderIT, CaixaLinuxSonda, ClienteH016IT}.java` | `6a99c24c…`, `71179941…`, `b1589da3…`, `d042020b…` |

| ?? (**não** é entrega) | `api/e2e/email/` | só `node_modules` de outra frente; **excluído** do snapshot |

Os demais arquivos (`ops/homologacao/**`, `docs/relatorios/**`) são os entregues em P1b–P3-R015. O manifesto
completo, com SHA-256, é gerado no início da execução e comparado com os `repo/` dos pacotes aprovados (§3.4).

### 3.3 Opção A — integração local revisada antes do P4 (commits locais, sem push)

1. **Commits locais** em `feat/mfa-emergencia`, por grupo revisável:
   - (a) API P1c: os 5 fontes principais, testes e `Dockerfile.operacao`;
   - (b) ferramentas de homologação P1b–P3-R015, com a correção A4;
   - (c) relatórios.

   **Sem** a linha 016 e **sem** saídas/evidências (`ops/homologacao/saidas`, `db/tests/*/saidas`,
   `ops/mfa-emergencia/saidas` continuam fora do Git). Sem push.
2. `criar ensaio-016a` roda na árvore real. `HOMOLOG_COMMIT` = hash curto do commit (a); imagens
   `nekkora-homologacao-{platform,portal,operacao}:<hash (a)>` construídas da árvore real.
3. A linha 016 é aplicada **na cópia de trabalho** (não commitada) só durante o P4 e retirada no encerramento (§7).

**Vantagem:** o conteúdo executado é o próprio commit que depois poderá ser integrado.
**Custo:** altera o histórico local agora (desfazer depois exige operação Git própria). Exige **autorização Git
adicional** para os commits locais e a revisão de cada grupo antes do P4.

### 3.4 Opção B — snapshot explícito com commits só num clone local (recomendada)

**Conteúdo do snapshot = baseline `dd74a8f` + lista explícita de inclusões + nada mais.** Não é "toda a árvore de
trabalho": ela contém arquivos de outras frentes, que não entram.

**Inclusões (lista L, fechada):**
- (L1) os arquivos de `repo/` dos pacotes aprovados P1b, P1c, P2, P3 rev. 3, P3-IT, P3-ITc, P3-ITd e P3-R015.
  Quando um arquivo aparece em vários pacotes, prevalece a **última versão aprovada** (ordem de aprovação acima). O
  manifesto registra, para **cada** arquivo, o pacote que fornece a versão usada (nome e SHA-256 do pacote) e os
  demais pacotes em que ele aparece; divergência entre a cópia de trabalho e essa versão → PARAR. Inclui os fontes
  P1c da API da §3.2;
- (L2) os arquivos da correção G1/G1b no pacote revisado de A4 (§3.1), última versão aprovada deles, que prevalecem
  sobre L1 nos arquivos comuns (registrado como fornecedor no manifesto);
- (L3) os relatórios desta frente em `docs/relatorios/` (propostas, decisões, anexos), cada um com hash registrado.

**Exclusões (explícitas, com motivo no manifesto):**
- saídas/evidências: `ops/homologacao/saidas`, `db/tests/*/saidas`, `ops/mfa-emergencia/saidas`, `api/e2e/saidas`;
- artefatos: `target/`, `node_modules/` (inclusive `api/e2e/email/`, que só contém `node_modules`);
- **todo** arquivo novo ou alterado da árvore de trabalho que não esteja em L (outras frentes).

**Procedimento:**
1. **Classificação prévia, no original (somente leitura):** `git status --porcelain=v1 -uall` inteiro classificado
   em L ou em exclusão. **Qualquer entrada não classificada → PARAR** (sem clone). Todo arquivo de L tem de estar
   presente com o SHA-256 do pacote de origem.
2. `git clone --no-hardlinks` local para um diretório próprio (com `core.longpaths` e o **mesmo** `core.autocrlf`
   do original, `true`, registrado), `checkout --detach dd74a8f` e `git remote remove origin`.
   Sem remoto não há push **pelo remoto configurado**, mas push por URL continua tecnicamente possível: a regra
   **"push proibido nesta missão"** vale explicitamente, para o clone e para o original, e o pacote registra
   `git remote -v` vazio no clone.
3. Cópia **somente** dos arquivos de L para o clone.
4. **Conferência de hashes (CRLF × LF).** A cópia de trabalho usa CRLF (`core.autocrlf=true`), exceto `*.sh` e
   `db/migrations/*.sql` (LF por `.gitattributes`); o Git guarda LF. Por isso cada arquivo de L é conferido de três
   formas, todas registradas:
   - SHA-256 dos bytes de trabalho no clone = no original = no pacote aprovado;
   - blob Git, conferido logo depois de S1: `git -C <clone> rev-parse S1:<caminho>` = `git -C <original> hash-object --path=<caminho> <arquivo>`
     (mesma normalização dos dois lados);
   - SHA-256 do conteúdo LF (`git cat-file blob S1:<caminho>`), que é o hash de referência de migrations e listas.
5. **Commit S1 (só no clone):** "SNAPSHOT DE LABORATORIO P4 — nao integrar", com L **sem** a linha 016. Conferir que
   `git diff --name-only dd74a8f S1` é **exatamente** L menos os arquivos idênticos à baseline (nenhum a mais, nenhum
   a menos) e que `git status` do clone fica limpo. Registrar hash do commit, `S1^{tree}` e `S1:api`.
6. **Commit S2 (só no clone):** aplica `elevacao-016-proposta.patch` (A2). Registrar o hash e conferir o conteúdo LF
   `47bb9596…`, a única diferença de S1 (`git diff --name-only S1 S2` = `elevacao.txt`) e 011–013 idênticas.
7. `criar ensaio-016a` roda no clone: a guarda corrigida passa, e `HOMOLOG_COMMIT` = hash curto de **S1** (último
   commit de `FONTES_IMAGENS`), com tags novas `nekkora-homologacao-{platform,portal,operacao}:<S1>`. Nenhuma tag
   existente (`d608a96`, e as já removidas `03bc72c`) é sobrescrita.
8. **Commit S3 (só no clone), no encerramento ou na interrupção:** patch reverso da linha 016 (§7).
9. **Rastreabilidade:**
   - registrar `git diff dd74a8f S1 --stat`, o patch completo e o manifesto de L e das exclusões na evidência;
   - uma integração futura (A ou outra) prova equivalência se `<commit integrado>:api` = `S1:api` (mesmo hash de
     árvore) e o restante bater com o manifesto.

   O repositório original não muda: nenhum commit, branch, alteração de `elevacao.txt` nem de histórico.

**Por que B:** atende à guarda sem mexer no histórico real, sem push e sem ativar a linha 016 na lista operacional
do repositório. A retirada da linha é comprovável dos dois lados: a lista do repositório continua `7fd92fae…` o
tempo todo, e no clone S3 volta a `7fd92fae…`.
**Autorização Git necessária:** criar um clone local sem remoto e **três commits locais dentro dele (S1, S2, S3)**.
**Push proibido nesta missão**, por qualquer remoto ou URL. Nenhuma operação no repositório original, cujo histórico não é alterado.

### 3.5 Preservação do clone

Na Opção B o clone **é o repositório do ambiente**, não uma cópia de apoio:
- `REPO` do runner e diretório do projeto Compose (`ops/homologacao/compose.homolog.yml` do clone);
- montagens de arquivo e diretório do clone: `postgres/pg_hba.conf`, `gateway/nginx.conf`, `sql/` (`/sql`) e
  `saidas/ensaio-016a/migracoes` (`/flyway/sql`);
- evidências geradas em `ops/homologacao/saidas/` **do clone**;
- `estado`, `recuperar`, `testar`, `testar-016`, `fixture … conferir` e o `destruir` do P5 só valem executados no
  clone.

Portanto o clone **permanece enquanto `ensaio-016a` existir ou seus arquivos forem necessários** para configurações,
montagens ou comandos do ambiente. Salvar as evidências não basta para descartá-lo. Depois de S3, o clone só muda por
saídas do runner (conferido por `git status`, que deve mostrar só `saidas/`). O descarte do clone é o **último**
passo do P5, depois do `destruir`, da conferência F17 e da cópia final das evidências.

## 4. Sequência do P4 (após A1–A5)

**0. Pré-voo manual** (nada criado ainda; tudo registrado):
- baseline Git, classificação da §3.4 (passo 1) e manifesto de L e das exclusões; S1 e S2 conferidos;
- guarda G1 corrigida: `FONTES_IMAGENS` limpa no clone e `HOMOLOG_COMMIT` previsto = S1;
- inventário B12 por id (containers com estado e `StartedAt`, redes/sub-redes, volumes, imagens);
- **portas (F16):** `netsh interface ipv4 show excludedportrange protocol=tcp`; a porta escolhida (proposta:
  **56720**) fora de toda faixa e não publicada. A padrão 55643 está hoje em 55588–55687;
- **sub-redes:** propostas **10.252.80/10.252.81**, conferidas contra todas as redes. As padrão são da `hml03`;
- `validar-config` (108/0 esperado: os 107 do P3-R015 + a seção 10 da G1/G1b, que agrega os casos de A4);
- conteúdo da lista usada = `47bb9596…`;
- `HOMOLOG_ELEVACAO_ARQ` **não** definido;
- imagem PostgreSQL `postgres:16-alpine` (id registrado).

**1. Parada depois da 015.** `HOMOLOG_ATE=016 HOMOLOG_TRANSPORTE=homologacao HOMOLOG_REDE_INTERNA=10.252.80
HOMOLOG_REDE_BORDA=10.252.81 HOMOLOG_PORTA=56720 homologacao.sh criar ensaio-016a` (sem `HOMOLOG_ENSAIO_PAUSA`).
Esperado:
- 001–015;
- janelas 011/012/013 concluídas `0|0|0`;
- `parada_antes_016.txt` = migrador recuperado + **uma** linha `faltam: 016; nao esperadas: nenhuma` + BLOQUEADO.

**2. Recusas B3, na parada, sem abrir janela.** Cada uma com código, **nenhuma janela**, histórico inalterado e
`0|0|0`:
- (a) `implantar` **sem** `HOMOLOG_AUTORIZA_ELEVACAO` → código 2;
- (b) lista temporária com a 016 vinculada a `ensaio-016a` e **sha256 errado** → código 2 (`HOMOLOG_ELEVACAO_ARQ` é
  permitido por ser `ensaio-*`);
- (c) lista temporária com a 016 vinculada a **outro id** → código 2;
- (d) lista temporária com a 016 **sem vínculo** → código 3 (validação).

**"Versão fora da lista" não é executada com a 016**: sem elevação, a 016 falharia e, com BEGIN próprio, poderia
deixar `success=false`. Reutiliza P3-IT R2f (sintética), com esse limite declarado.

**3. J1 — interrompida antes do Flyway.** `HOMOLOG_AUTORIZA_ELEVACAO=1
HOMOLOG_ENSAIO_PAUSA="antes-do-flyway@016:<arq>" homologacao.sh implantar ensaio-016a` em segundo plano.
- Pré-voo automático: código 0 + `PREFLIGHT_OK`. Janela aberta: `inicio versao=016`, migrador `1|0|0`.
- Na pausa: `kill -9` **somente** do PID do runner deste ensaio, conferido como dono da trava. `docker kill` só de
  container Flyway deste projeto, se houver.

**4. Recuperação e reconciliação.**
- `estado` BLOQUEADO; `testar`, `testar-016`, `preparar-016` e `concluir-criacao` recusados.
- `homologacao.sh recuperar ensaio-016a` → `0|0|0`.
- Reconciliação somente leitura: `preflight/016.sql` em transação READ ONLY → código 0 + `PREFLIGHT_OK`; histórico
  sem 016; sem `ops`, sem `sslinfo`, sem `mfa_events.ops_request_id`; impressão digital do catálogo antes/depois.
- **Qualquer resultado diferente de "não aplicada" → PARAR** (sem J2, sem repair) → §4.9 → nova decisão.

**5. J2 — concluída (evidência de B2).** `HOMOLOG_AUTORIZA_ELEVACAO=1 homologacao.sh implantar ensaio-016a`.
- Pré-voo OK → 016 aplicada → `fim versao=016 … (0|0|0)` → `flyway validate` → código 0 e **LIBERADO** com 001–016.
- Conferências de retirada: 5 atributos `false`; nenhuma participação envolvendo o migrador; nenhuma ACL de parâmetro
  do migrador; sessões 0.
- Falha em J2 → PARAR (§4.9); **não há J3** (§5).

**6. `concluir-criacao ensaio-016a`.** Senhas dos serviços (inclusive `nekkora_ops_ca`/`ops_intake`), volumes
`operadores` e `caixa` `nekkora 700`, liberação, serviços. TLS e HTTP 200/401. Falha parcial → F15 (§6) e §4.9.

**7. Verificações integradas.**
- `HOMOLOG_TESTES=h02 homologacao.sh testar ensaio-016a` → H02 com **quatro janelas concluídas** (011, 012, 013, 016)
  e **uma interrompida** (016/J1) com recuperação própria; reexecução sem elevação; `0|0|0`.
- `preparar-016` e `testar-016` (B5–B8a, B10–B12; B8b só com A6).
- **Fixtures declaradas (duas, distintas):**
  - **B8a** usa a **fixture declarada de elegibilidade dos contatos** (`homologacao.sh fixture ensaio-016a contatos
    <c1,c2>`, `sql/fixture_016_contatos.sql`): altera só `created_at` e `channel_set_at` dos 2 contatos sintéticos
    da organização da 016, deslocados de −721 h, em transação própria com classificação APLICADA/NÃO_APLICADA/
    INDETERMINADA. É a **única** fixture de B8a e faz parte do escopo de **A5**;
  - **B8b** usa a **fixture temporal da solicitação** (`fixture … solicitacao <uuid> [horas]`,
    `sql/fixture_016_solicitacao.sql`), só com `H016_B8B=1` e só com **A6**.
  - Resultado INDETERMINADA em qualquer uma → `fixture ensaio-016a conferir <execucao>` (somente leitura) antes de
    prosseguir; nunca reaplicar sem reconciliar.
- `validar-config` final.
- H02 sem declaração não fixa o número de interrompidas. O critério "exatamente 1" é conferido pela classificação do
  próprio H02 (`h02_janelas_classificadas.txt`).

**8. Encerramento.**
- Conferência final de retirada de privilégios (`0|0|0`, 5 atributos `false`, nenhuma participação ou ACL de
  parâmetro do migrador, sessões 0).
- Retirada da linha 016 (S3, §7).
- Inventário B12 depois.
- Pacote de evidências (§8).
- `ensaio-016a` **e o clone mantidos** até a revisão (A8-ii, §3.5). O descarte é etapa própria, com F17, e o clone
  sai por último.

**9. Interrupção da missão (qualquer PARAR das §§4–6).** Interromper não é só parar de executar. Nesta ordem, tudo
registrado:
1. **Conferir a retirada de privilégios:** `0|0|0`, 5 atributos `false`, nenhuma participação envolvendo o
   migrador, nenhuma ACL de parâmetro dele, sessões 0. Se houver resíduo de uma janela interrompida, o único passo
   permitido é `homologacao.sh recuperar ensaio-016a` (só retira), seguido de nova conferência.
2. **Retirar a linha temporária:** commit S3 no clone (§7), conferência `7fd92fae…` e remoção das listas temporárias
   de B3 (`HOMOLOG_ELEVACAO_ARQ`), registradas com hash antes da remoção. A lista do repositório original continua
   `7fd92fae…`.
3. **Preservar o estado para análise:** nada é destruído, parado ou removido para "limpar". Registrar `estado`,
   containers (id, estado, `StartedAt`), `janelas_de_elevacao.txt`, histórico do Flyway (somente leitura), logs dos
   serviços e inventário B12. Ambiente e clone permanecem (§3.5).
4. **Pacote de evidências** da interrupção (§8), com a causa classificada.

A interrupção **não autoriza**: J3, nova execução de `concluir-criacao`, recriação do ambiente, `flyway repair`,
`destruir` nem qualquer intervenção corretiva. Cada uma exige nova decisão expressa.

## 5. Limite de janelas da 016

- **Máximo: duas** — J1 (interrompida antes do Flyway) e J2 (conclusão).
- **Resultado esperado ao fim da J2:** **quatro janelas concluídas** (011, 012, 013 no `criar`; 016 na J2) **e uma
  interrompida** (016/J1), cada uma com retirada e `0|0|0`.
- **J3, ou recriar o ambiente** (que abriria nova janela da 016), **exige nova decisão**. Isso vale em qualquer
  falha: J1 fora do previsto, reconciliação diferente de "não aplicada", J2 com erro, ou falha parcial do
  `concluir-criacao`. Em todas elas, o encerramento segue a §4.9.
- `0|0|0` = soma dos 5 atributos privilegiados | participações envolvendo o migrador | sessões do migrador.

## 6. Procedimentos F15–F17

- **F15 — falha parcial do `concluir-criacao`** (limitação do novo fluxo):
  - não repetir o comando (é recusado) e não remover containers para esconder a falha;
  - registrar containers/estados, `subida_servicos.txt`, `liberacao.txt` e `volumes.txt`;
  - classificar a causa;
  - encerrar pela §4.9.

  Correção só por decisão expressa: intervenção revisada, ou recriação — nova janela da 016, fora de A1.
- **F16 — portas:** conferência da §4.0 **antes** do `criar`, registrada; recusar porta em faixa excluída ou
  publicada.
- **F17 — volumes anônimos:** antes do `destruir`, registrar os volumes montados por cada container do ambiente (id e
  destino; hoje o `/data` do Redis). Depois, remover **somente** esses ids, conferindo que nenhum container os usa.
  Nunca `prune` nem filtro genérico.

## 7. Retirada da linha 016 ao término

- **Opção B:**
  - a lista do repositório nunca muda (`7fd92fae…` antes, durante e depois, conferido);
  - no clone, commit S3 com o patch reverso: conteúdo LF `7fd92fae…`, `git diff --name-only S2 S3` = só
    `elevacao.txt`, e `git diff --quiet S1 S3 -- ops/homologacao/implantacao/elevacao.txt`;
  - registrar os três hashes (S1, S2, S3). O clone **não** é descartado aqui (§3.5).
- **Opção A:** patch reverso na cópia de trabalho; `validar-config` reconhece `operacional`; SHA-256 LF `7fd92fae…` e
  cópia de trabalho `eb98911a…` iguais aos de antes; `git diff --quiet HEAD -- elevacao.txt`.
- **Em ambas:** nenhuma outra linha muda; 011–013 byte a byte; a retirada é registrada **antes** da entrega do pacote,
  no encerramento normal (§4.8) ou na interrupção (§4.9).

## 8. Destinação das evidências

- geradas em `ops/homologacao/saidas/` **do clone** (Opção B), copiadas para `ops/homologacao/saidas/p4-<instante>/`
  do repositório original (fora do Git, conferidas por hash); os originais ficam no clone até o P5 (§3.5);
- pacote de revisão ZIP com:
  - manifesto conferido em extração nova;
  - varredura com controle positivo de 200 tokens e 5 marcadores (gerador real declarado);
  - diffs contra P3-R015 (`anterior/`, `atual/`);
  - manifesto do snapshot (L, exclusões, hashes de trabalho/blob/LF, S1/S2/S3) (Opção B) ou dos commits locais (Opção A);
  - `HOMOLOG_COMMIT`, `S1:api`, SHA-256 dos jars/classes copiados e ids das três imagens (§3.1).
- Segredos do ambiente (`~/.nekkora/homologacao/ensaio-016a`) e o conteúdo bruto da caixa ficam **fora** do pacote.
- Pacotes anteriores preservados.
- Ambiente e clone mantidos até a revisão; descarte (P5) em etapa própria, o clone por último.

## 9. Matriz dos cenários do P4

| Cenário | Situação no P4 | Observação |
|---|---|---|
| B1 — medição | **Reutiliza** P2 (aprovada) | Não repetido |
| B2 — implantação 001–016 | **Executa** (§4.1 + J2) | Quatro janelas concluídas (011, 012, 013, 016) + uma interrompida |
| B3 — recusas | **Executa** (a)–(d) na parada | "Fora da lista" com a 016 **não** é executada; reutiliza P3-IT R2f (sintética), com o limite declarado |
| B4 — interrupção | **Parcial** | Executa só a interrupção **antes do Flyway** (J1) e a recuperação. **Não** prova interrupção **durante o SQL** da 016 nem COMMIT com resultado desconhecido: **pendentes** para cenário próprio (ambiente e decisão próprios) |
| B5 — catálogo e invariantes | **Executa** (`testar-016`) | 37/21/9; invariantes §19 |
| B6 — certificado dos operadores | **Executa** (`testar-016`) | CA operacional descartável do ambiente |
| B7 — identidades de serviço | **Executa** (`testar-016`) | `nekkora_ops_ca`, `nekkora_ops_intake` |
| B8a — fluxo até a espera | **Executa** (`testar-016`, transporte de homologação) | Usa a **fixture declarada de elegibilidade dos contatos** (única de B8a; escopo de A5, §4.7). Sem e-mail real |
| B8b — conclusão do fluxo | **Opcional** (A6; recomendado) | Fixture temporal **declarada de solicitação**, distinta da de B8a |
| B9 — prazos em tempo real | **Pendente** | Mini missão posterior, ambiente próprio mantido pelo período |
| B10 — despachante e varredura | **Parcial** | Ver limites abaixo |
| B11 — backup e restauração | **Parcial** | Ver limites abaixo |
| B12 — preservação | **Executa** | Inventário antes/depois por id; `hml03`, principal, desenvolvimento, `nk016-smoke` intactos |
| H02 integrado / I1–I10 / I8 / H09 | **Reutiliza** P3-IT, P3-R015 | Mais o H02 próprio do P4 (§4.7) |

**Limites de B10:**
- despachante ligado **só** no ambiente;
- desfechos (ACEITO, ENFILEIRADO, RECUSADO, INCERTO, exceção) **simulados** pela caixa de controle do transporte de
  homologação, pelo caminho normal (`recordTransportResult`);
- **a varredura não tem agendamento nem gatilho no ambiente**: fica PENDENTE (`h016.sh` registra B10 PARCIAL);
- resultado assíncrono posterior (aceite depois de ENFILEIRADA) não tem caminho de entrada na aplicação;
- sem e-mail real.

**Limites de B11:**
- o **dump** por `backup_nekkora` (inclui as 21 tabelas de `ops`) é executado;
- a **restauração** num segundo cluster exige superusuário desse cluster (`sslinfo`) e papéis pré-criados. **Não** é
  coberta por A1: fica **PENDENTE** sem A7.

**Fora do P4:** produção, infraestrutura real, B9, B4 completo, e-mail real, frontend, commit/push no repositório
original (os únicos commits são S1–S3, no clone sem remoto) e integração definitiva.

**Pré-requisito A4 (G1/G1b): aprovado tecnicamente em 2026-09-29**, pacote `homologacao-016-g1r-20260929T185152Z.zip`
(SHA-256 `b34454ba…2406`; relatório `2026-09-29-mfa-emergencia-g1-guarda-fontes-imagens.md`, §7). Isso atende ao
pré-requisito técnico da A4; **não** substitui a decisão A4 do quadro abaixo nem autoriza o P4.

## 10. Registro de autorização (preencher só com manifestação expressa)

| Decisão | Resposta | Responsável | Data/hora | Referência |
|---|---|---|---|---|
| A1 — D-ELEV | **Autorizada**: até duas janelas temporárias de `SUPERUSER` do `migrador_nekkora`, só no cluster de `ensaio-016a`, só para a 016 (`25bef818…be61`): J1 interrompida e J2 concluída, nas condições das §§4–6. J3 ou recriação exigem nova decisão | Nekkolly (responsável) | 2026-09-29T19:20:48Z | manifestação expressa na sessão Claude Code de 2026-09-29 ("Autorizo como recomendado") |
| A2 — ativação temporária da linha 016 | **Autorizada** só no clone (commit S2), retirada por S3 no encerramento ou na interrupção. A lista do repositório original não muda (`7fd92fae…`) | Nekkolly (responsável) | 2026-09-29T19:20:48Z | manifestação expressa na sessão Claude Code de 2026-09-29 ("Autorizo como recomendado") |
| A3 — Opção A ou B (Git) | **Opção B**: clone local sem remoto, snapshot com manifesto explícito (§3.4) e três commits locais só no clone (S1, S2, S3). **Push proibido nesta missão**. Nenhuma operação no repositório original | Nekkolly (responsável) | 2026-09-29T19:20:48Z | manifestação expressa na sessão Claude Code de 2026-09-29 ("Autorizo como recomendado") |
| A4 — correção da guarda (G1) | **Autorizada e cumprida**: G1/G1b aprovada tecnicamente (G1r, `b34454ba…2406`) | Nekkolly (responsável) | 2026-09-29T19:20:48Z | manifestação expressa na sessão Claude Code de 2026-09-29 ("Autorizo como recomendado") |
| A5 — criação e execução de `ensaio-016a` | **Autorizada**: parâmetros da §4.0 e sequência da §4, inclusive a fixture declarada de elegibilidade dos contatos (B8a). Qualquer PARAR segue a §4.9 | Nekkolly (responsável) | 2026-09-29T19:20:48Z | manifestação expressa na sessão Claude Code de 2026-09-29 ("Autorizo como recomendado") |
| A6 — B8b | **Autorizada**: B8b com a fixture temporal declarada de solicitação (`H016_B8B=1`) | Nekkolly (responsável) | 2026-09-29T19:20:48Z | manifestação expressa na sessão Claude Code de 2026-09-29 ("Autorizo como recomendado") |
| A7 — B11 restauração | **Não autorizada agora**: B11 fica parcial (só o dump); a restauração fica para mini missão própria | Nekkolly (responsável) | 2026-09-29T19:20:48Z | manifestação expressa na sessão Claude Code de 2026-09-29 ("Autorizo como recomendado") |
| A8 — destinação (retirada da linha; descarte em etapa própria) | **Autorizada**: (i) retirar a linha 016 no encerramento (S3); (ii) manter `ensaio-016a` e o clone até a revisão das evidências; (iii) descarte (P5, com F17, clone por último) só em etapa própria, com nova decisão | Nekkolly (responsável) | 2026-09-29T19:20:48Z | manifestação expressa na sessão Claude Code de 2026-09-29 ("Autorizo como recomendado") |

## 11. Revisão 2 — ajustes da revisão técnica da redação 1

| # | Ajuste pedido | Onde |
|---|---|---|
| 1 | Três commits locais S1, S2, S3 no clone, sem push nem alteração do histórico original | §2 (A3), §3.4 (passos 5, 6, 8 e autorização Git), §7 |
| 2 | Snapshot = `dd74a8f` + entregas revisadas + correção G1 revisada, com inclusões e exclusões explícitas; hashes considerando CRLF × LF | §3.2, §3.4 (lista L, exclusões, passos 1–5) |
| 3 | Clone preservado enquanto seus arquivos servirem ao ambiente; salvar evidência não basta | §2 (A8), §3.5, §4.8, §7, §8 |
| 4 | Guarda G1 sobre as entradas efetivas das três imagens; identificação igual ao conteúdo do build | §2 (A4), §3.1 (G1b, `FONTES_IMAGENS`) |
| 5 | B8a usa a fixture declarada de elegibilidade dos contatos, distinta da de B8b, no escopo de A5 | §2 (A5, A6), §4.7, §9 |
| 6 | Interrupção: conferir retirada de privilégios, remover a linha temporária, preservar o estado; sem J3, sem repetir `concluir-criacao`, sem recriar | §4.9, §§4.4–4.6, §5, §6 |

Achado novo durante a revisão: **G1b** — a lista do `git log -1` que define `HOMOLOG_COMMIT` difere da lista da
guarda e nenhuma das duas cobre a imagem `operacao` (§3.1). As recomendações do quadro da §2 foram alinhadas às da
revisão técnica. **Nenhuma decisão foi tomada; a §10 continua sem preenchimento.**

**Complemento (revisão da revisão 2):** (a) no snapshot, prevalece a última versão aprovada de cada arquivo, com o
pacote fornecedor registrado por arquivo (§3.4, L1/L2); (b) o clone sem remoto não torna push tecnicamente
impossível: a regra "push proibido nesta missão" fica explícita (§3.4, passo 2 e autorização Git).
