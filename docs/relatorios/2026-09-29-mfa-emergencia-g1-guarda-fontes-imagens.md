# Mini missão G1/G1b — guarda e identificação das imagens da homologação

**ESTADO: APROVADA TECNICAMENTE (G1r, 2026-09-29).** Pendência G1/G1b encerrada no escopo da guarda e da identificação
das imagens. A aprovação técnica não concede autorização operacional do P4. Data: 2026-09-29. Pré-requisito A4 da proposta D-ELEV/P4
(`2026-09-29-mfa-emergencia-d-elev-p4-missao-proposta.md`, §3.1).

Esta entrega **não autoriza** D-ELEV, P4, criação de ambiente, concessão de privilégio nem operação Git. Nesta
mini missão **não** houve commit, stage, push, Docker, ambiente de homologação, banco nem janela da 016. A API não foi
alterada: nenhum arquivo de `api/` difere do pacote P3-R015.

## 1. Base

| Item | Valor |
|---|---|
| HEAD / branch | `dd74a8f6e9a7b487e5698c11ca026e2c4666177e` / `feat/mfa-emergencia`, nada em stage |
| Referência incremental | P3-R015 `homologacao-016-p3r015-20260929T162806Z.zip`, SHA-256 `07654115867bb6055014d924968af544a03eb2b31645943684a7b428c6e4e47e`. `homologacao.sh`, `validar_config.sh` e `README.md` estavam idênticos ao `repo/` desse pacote antes da mudança |
| `elevacao.txt` | inalterado (cópia de trabalho `eb98911a…0e6d`, só 011–013) |
| Evidências | `ops/homologacao/saidas/g1-20260929T181321Z/` (fora do Git) |

## 2. Achados tratados

- **G1:** a guarda anterior (`git diff --quiet HEAD -- …`) não via arquivo novo não rastreado.
- **G1b:** a guarda (linha 168) e a identificação (`git log -1`, linha 167) usavam **listas diferentes**. Nenhuma das
  duas cobria a imagem `operacao` (`api/e2e/Dockerfile.operacao`, `api/nekkora-ops/src/test`). A identificação
  também omitia `api/nekkora-ops/src/main`, `api/nekkora-ops/pom.xml` e o wrapper Maven. Além disso, os globs
  `api/*/…` eram expandidos pelo bash no diretório corrente, não no repositório.

Encontrados durante a implementação, e tratados:
- arquivo **ignorado** pelo `.gitignore` dentro de `src/main` seria empacotado pelo Maven, mas escaparia de
  `--exclude-standard`;
- alteração **só no stage** (árvore igual ao HEAD) não aparece em `git diff HEAD`;
- marcas **assume-unchanged/skip-worktree** escondem alteração do `git diff`.

## 3. Mudança

| Arquivo | Situação | SHA-256 (cópia de trabalho) |
|---|---|---|
| `ops/homologacao/lib/fontes_imagens.sh` | novo | `30500d075c65a7ca2a7a7de618fc3275f856c32db059a30f2e65ad06142841a4` |
| `ops/homologacao/testes/guarda_fontes_imagens.sh` | novo | `d392167ce298580539866a5aef0d756460f8881eb045c47411ee0a9352e2088a` |
| `ops/homologacao/homologacao.sh` | alterado (`criar`, 12 linhas) | `df1c8af9e61c1adcb30e6d84f750d7e60416b6b8426afa7e87a3ad8d52048d50` |
| `ops/homologacao/testes/validar_config.sh` | alterado (seção 10) | `0e43824566afbd766b757ca30cf0797e2c0e611b5cf20b1668b75ee18c3e7751` |
| `ops/homologacao/README.md` | alterado (passo 3) | `7f7ccf0d52c6fad6f72bc35fdfd57e56487e4e25a7e2690b94a07d40ca65fbcf` |
| `docs/relatorios/2026-09-29-mfa-emergencia-d-elev-p4-missao-proposta.md` | redação (§3.1, §3.4, §4.0) | no manifesto do pacote |

**`lib/fontes_imagens.sh`:**
- `FONTES_IMAGENS` é a **única** lista das entradas dos três builds. Os caminhos são explícitos, sem glob:
  `api/pom.xml`, `api/mvnw`, `api/.mvn`, `api/e2e/Dockerfile`, `api/e2e/Dockerfile.operacao`,
  `api/nekkora-ops/src/test` e, para cada módulo (`nekkora-core`, `nekkora-ops`, `nekkora-platform`,
  `nekkora-portal`), `pom.xml` e `src/main`. A lista vale para qualquer `HOMOLOG_ATE`.
- `guarda_fontes_imagens <repo>` recusa, com o motivo impresso:
  - alteração na árvore, inclusive exclusão;
  - alteração em stage, inclusive quando só o stage difere do HEAD;
  - arquivo não rastreado, inclusive ignorado;
  - marca assume-unchanged/skip-worktree;
  - `<module>` de `api/pom.xml` diferente da lista.
- `commit_fontes_imagens <repo>` = `git log -1 --format=%h HEAD -- $FONTES_IMAGENS` (a mesma lista).
- `registro_fontes_imagens` grava `fontes_imagens.txt` com o commit, `HEAD`, a árvore `<commit>:api` e a lista.

**`homologacao.sh criar`:** as linhas 165–169 anteriores foram trocadas pela chamada à lib, **antes** do primeiro
recurso (`mkdir -p "$EVID"`). `fontes_imagens.txt` é gravado na evidência do ambiente. O resto do `criar` não mudou.

**Fora de propósito da lista:** `api/nekkora-platform/src/test`, compilado para as ferramentas do ensaio, mas que
não entra em imagem (continua coberto pelo manifesto do snapshot, §3.4 da proposta), e o repositório Maven local
(`~/.m2`), que não está no Git.

**Não incluído (limite declarado):** o registro do SHA-256 dos jars e classes copiados e do id de cada imagem depende
de Docker. Fica para o pré-voo e o encerramento manuais do P4, salvo decisão de incluí-lo no runner.

## 4. Testes (repositório Git temporário, sem Docker)

`bash ops/homologacao/testes/guarda_fontes_imagens.sh` → **86 casos, 0 falhas** (`guarda_fontes_imagens.txt`).

| Seção | O que comprova |
|---|---|
| 0 | um arquivo representativo para **cada** uma das 14 entradas da lista (conferido pelo próprio teste) |
| 1 | árvore commitada passa; `HOMOLOG_COMMIT` = commit; a guarda independe do diretório corrente |
| 2 | recusa para cada entrada **alterada** (14) e **excluída** (14); exclusão em stage (`git rm`); alteração em stage; alteração **só** no stage; arquivo novo em stage; **novo não rastreado** em cada diretório da lista (11); novo **ignorado** em `src/main`; `Dockerfile.operacao` não rastreado; assume-unchanged; skip-worktree; módulo novo em `api/pom.xml` |
| 3 | alteração só fora da lista (`platform/src/test`, `docs`) passa e não muda `HOMOLOG_COMMIT` |
| 4 | commit em **cada** entrada (14), arquivo novo commitado e exclusão commitada: passa e `HOMOLOG_COMMIT` = novo commit |
| 5 | *(substituída na G1r, §6: a versão original aceitava extração vazia)* cobertura contra os arquivos **reais**, somente leitura: módulos de `api/pom.xml`, Dockerfiles e contexto dos `compose*.yml`, cada `COPY` dos Dockerfiles, `ARG JAR`; o `criar` chama a guarda antes do primeiro recurso e não restou a guarda antiga |
| 6 | controle negativo: a guarda e a identificação **anteriores**, reproduzidas no mesmo repositório temporário, deixam passar G1 e G1b onde a nova recusa |

**Mutações** (`mutacoes.txt`, numa cópia temporária): quatro mutações, cada uma removendo uma verificação da lib. O
teste pegou as quatro.
- stage → 1 falha;
- `--exclude-standard` → 1 falha;
- sem marcas do índice → 2 falhas;
- lista sem as entradas da `operacao` → 19 falhas.

**Outras evidências:**
- `sintaxe.txt`: `bash -n` OK nos 4 scripts.
- `guarda_repositorio_real.txt`: a guarda no repositório real, somente leitura. Como esperado, ela **recusa**,
  porque as 5 alterações e os 13 arquivos novos do P1c ainda não têm commit (entre eles `Dockerfile.operacao`,
  `FerramentaHomologacao.java` e `CaixaHomologacaoMailSender.java`). A guarda anterior deixaria passar os 13 novos.
- `baseline.txt`: HEAD, stage vazio e `elevacao.txt`.

**Não executado nesta mini missão:** o `validar-config` completo, porque a seção 4 usa `docker compose config`, e a
entrega foi testada sem Docker. A seção 10 nova só chama o script acima. No pré-voo do P4 espera-se 108/0: os 107
anteriores mais esta seção.

## 5. Pacote

`../relatorios/homologacao-016-g1-<instante>.zip`. O pacote contém:
- `repo/` com as versões entregues;
- o diff incremental contra o `repo/` do P3-R015 e a lista `M/A` correspondente;
- o diff completo contra `dd74a8f`;
- as evidências e este relatório;
- a varredura com controle positivo;
- o manifesto SHA-256, conferido depois de extrair o ZIP.

Os pacotes anteriores foram preservados sem alteração.

## 6. Rodada de correção G1r — conferência de cobertura

**Achado da revisão:** a seção 5 do teste lia os Dockerfiles e os compose sem exigir que a leitura desse resultado.
Se um arquivo faltava, a extração ficava vazia, o laço não executava e o teste imprimia
`OK todo COPY dos Dockerfiles reais vem de modulo coberto`. Numa reexecução fora do checkout (pacote parcial, sem
`api/pom.xml` nem `api/e2e/Dockerfile`, e com arquivos CRLF), isso deu 86 casos e 3 falhas. O `\r` preservado nos
valores do compose virou falha falsa, e a ausência do Dockerfile virou sucesso falso. **A guarda em si não foi
afetada:** `lib/fontes_imagens.sh` e `homologacao.sh` não mudaram (mesmos SHA-256 da §3).

**Causa de não ter aparecido aqui:** no Git Bash deste Windows, `grep` e `awk` descartam o `\r` ao ler arquivo. Por
isso a execução local passava mesmo com compose CRLF, e só um ambiente sem essa conversão (Linux) expôs o problema.

**Correção (só `ops/homologacao/testes/guarda_fontes_imagens.sh`, SHA-256
`4f935f6b90b4cee9a822550f4501ef9f1703e05a41b5c373ef88c954e6f4b9d6`):**
1. **Leitura estrita:** `le` falha explicitamente (`LEITURA_FALHA`) quando o arquivo está ausente ou não é
   regular, quando a leitura dá erro ou quando o conteúdo fica vazio. `cobertura` falha quando alguma extração
   fica vazia: nenhum `<module>`, nenhum `dockerfile:`, nenhum `JAR:`, Dockerfile sem `COPY`, `COPY` sem origem, ou
   zero `COPY`/origens no total. Também exige os dois compose que constroem as três imagens e os dois Dockerfiles
   esperados na extração. O resumo informa quantos `COPY` e origens foram conferidos: no real, 5 e 6.
2. **LF/CRLF:** `le` normaliza removendo o `\r` (`tr -d '\r'`) **numa cópia temporária**. Os arquivos conferidos
   não são alterados (SHA-256 antes = depois, comprovado). A normalização é comprovada por **bytes** (`od`), o que
   não depende do `grep` do ambiente. A mutação que remove o `tr` é detectada neste Windows.
3. **Contraprovas** (seção 5a, fixtures sintéticas, sem o repositório real):
   - fixture LF passa (controle positivo da fixture);
   - fixture CRLF tem `\r` nos bytes, passa, não é alterada, e a cópia normalizada não tem nenhum `\r`;
   - falham com o motivo esperado: Dockerfile ausente; Dockerfile ilegível (diretório no lugar do arquivo);
     Dockerfile vazio; Dockerfile sem `COPY`; `COPY` de módulo fora da lista; `COPY` de fonte fora de `target/`;
     `api/pom.xml` ausente ou sem `<module>`; `compose.016.yml` ausente; compose sem `dockerfile:`; contexto de
     build diferente de `api/`.
4. **Reprodução:** a seção 5b confere primeiro se a árvore real está completa. Se não estiver, falha com
   `arvore real INCOMPLETA` e aponta para `REPRODUCAO.txt`. O pacote traz `baseline-teste/` com os dois arquivos da
   baseline que faltam em `repo/`, cada um com o blob Git (conferível com `git hash-object`, sem o repositório):
   - `api/pom.xml`, blob `a443573d6c688513593fd1baf57dd7c6f406ac5a`;
   - `api/e2e/Dockerfile`, blob `6715ad5e331c9fed2192454588ed0da386d38d9b`.

   O `REPRODUCAO.txt` descreve duas formas: (A) `repo/` + `baseline-teste/`, só com o pacote; (B) `git archive
   dd74a8f` + `repo/`, no checkout exato. O gerador executa A, B e a contraprova C (só `repo/`, falha explícita e
   nenhum "OK cobertura real") sobre o próprio pacote, antes do manifesto (`reproducao-resultado.txt`).

**Resultados** (`ops/homologacao/saidas/g1r-20260929T184957Z/`, fora do Git; a evidência G1 anterior foi preservada):
- teste isolado: **98 casos, 0 falhas** (86 anteriores − 4 da seção 5 antiga + 16 da seção 5a + 1 de árvore
  completa + 1 de cobertura real, com os 3 do `criar` mantidos);
- mutações (`mutacoes.txt`): todas detectadas.
  - Na lib: stage, `--exclude-standard`, marcas do índice e lista sem as entradas da `operacao`.
  - No teste: sem `tr`, vazio aceito, ausente aceito, `COPY` não exigido.
- sem Docker, sem ambiente de homologação e sem regressão da API. O registro dos hashes dos artefatos e dos ids das
  imagens continua no P4, como previsto.

**Pacote:** `../relatorios/homologacao-016-g1r-<instante>.zip`, com `incremental-g1.txt` e
`diff-incremental-g1.patch` contra o pacote G1 (`homologacao-016-g1-20260929T181609Z.zip`, SHA-256
`6889556634d45c874e3ec277dfa1c8a9f4aaae9b0d53d3bbf07f685eaed99207`, preservado), além do incremental contra o
P3-R015.

## 7. Aprovação técnica da G1r

Pacote revisado: `homologacao-016-g1r-20260929T185152Z.zip`, SHA-256
`b34454baf520f5613d12264f7ba3e3019dcbc3d53afb2e559086477f2a4c2406`. Conferências independentes da revisão:
- SHA-256 do ZIP igual ao informado;
- manifesto 105/105, sem divergências;
- reprodução em **Linux** com `repo/` + `baseline-teste/`: 98 casos, 0 falhas, código 0 (confirma o tratamento de
  CRLF fora do Git Bash);
- reprodução sem a baseline: código 1, arquivos ausentes identificados e cobertura recusada, sem falso sucesso;
- contra a G1, só mudaram o teste e o relatório; biblioteca e runner idênticos.

Regressão da API não exigida nesta etapa. O registro dos hashes dos artefatos e dos ids das imagens fica no P4.
