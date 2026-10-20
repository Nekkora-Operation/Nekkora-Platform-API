# Consolidação no Git do trabalho da 017 (Entrega A da missão PLATFORM-PR-001)

**Data:** 2026-10-18. **Natureza:** levantamento, conferência de origem e verificação em checkout limpo. Duas correções pequenas de versionamento. Nenhuma alteração de código, contrato ou migration.

## 1. Estado encontrado

| Item | Situação |
|---|---|
| Repositório | `git@github.com:Nekkora-Operation/Nekkora-Platform-API.git`, remoto `origin`, branch protegida `main`, fluxo por PR (PRs #1 a #4) |
| Trabalho da 017 (R5, blocos 1 a 3, P1 R1, P2 R1) | **já está no Git e na `main`**: commit `6c00a88` (17/10, 18:25 -03:00), PR #4, mesclado pela responsável em `4a528b3` (17/10, 18:54 -03:00) |
| `AGENTS.md` | não existe no repositório. A referência operacional é o `README.md`, o `api/README.md` e os relatórios em `docs/relatorios` |
| Pacotes ZIP das etapas | fora do repositório (`../pacote-017-*`), não versionados, preservados sem alteração |

A consolidação pedida, portanto, já existe. Esta entrega confere que o que está na `main` é o que foi revisado, verifica que a `main` se constrói e se testa sozinha, e corrige o que essa verificação encontrou.

## 2. Inventário dos arquivos locais fora do Git

| Grupo | Classificação | Destino |
|---|---|---|
| `db/tests/identidade-017/saidas/` — 9 rodadas de 11 a 13/10 (R1 a R4; ~38 MB) | superadas: a rodada final da R5 (`2026-10-14T034716Z_nekkora-id017`) é a versionada | **não versionadas** (logs redundantes); mantidas no disco, sem apagar |
| `.DS_Store`, `docs/.DS_Store` | metadados do Finder (macOS), fora do tema | **não versionados**; `.DS_Store` entra no `.gitignore` |
| `../pacote-017-*` e `.zip` | entregas antigas por pacote | não versionados (a missão dispensa ZIP); usados só para a conferência da §3 |

Nenhum arquivo modificado e não commitado foi encontrado no repositório.

## 3. Conferência de origem (último pacote revisado contra a `main`)

`pacote-017-p2-criacao-organizacao-r1-2026-10-17` (P2 R1, fechado em revisão local) contra `origin/main` (`4a528b3`), byte a byte:

| Arquivo do pacote | Resultado |
|---|---|
| `IdentityOrganizationsService.java`, `Limits.java` (main), `OrganizationCreationIT.java`, `ConfirmacaoPerdida.java` (test) | idênticos |
| `api/openapi/nekkora-api-v1.yaml` | idêntico |
| `2026-10-14-p2-criacao-organizacao.md`, `2026-10-13-contratos-step-up-criacao-vinculacao.md` | idênticos |
| `db/migrations/017_identidade_cadastro.sql` | sha256 `90b8ddbcceac1319e9d4f71932e4af287ab1cdef67feacb6d71cfdc77db1c160`, o da R5 registrado no pacote |

## 4. Verificação em checkout limpo do commit da `main`

Clone novo de `origin/main` em `4a528b3`, sem nenhum arquivo local, fora da pasta de trabalho.

| Ambiente | Versão |
|---|---|
| Sistema | macOS (Darwin 25.6.0, arm64), 8 CPUs, 8 GB |
| Java | 21.0.5 LTS |
| Maven | 3.9.16, pelo wrapper |
| Docker | 27.4.0 (Testcontainers: PostgreSQL 16 e Redis 7 descartáveis) |
| Bash do sistema | 3.2.57 |
| Bash para o `FixtureRunnerIT` | **5.3.20** (Homebrew), por `-Dnekkora.bash=/opt/homebrew/bin/bash` |

Comando: `sh ./mvnw -B test -Dmaven.test.failure.ignore=true -Dnekkora.bash=/opt/homebrew/bin/bash` (o `failure.ignore` só garante que os quatro módulos rodem; as contagens abaixo foram lidas, não inferidas do `BUILD SUCCESS`).

| Módulo | Testes | Falhas | Erros | Pulados |
|---|---|---|---|---|
| nekkora-core | 18 | 0 | 0 | 0 |
| nekkora-ops | 155 | 0 | 0 | 0 |
| nekkora-platform | 260 | 0 | 0 | 0 |
| nekkora-portal | 12 | 0 | 0 | 0 |
| **total (73 classes)** | **445** | **0** | **0** | **0** |

**As cinco ocorrências conhecidas do `FixtureRunnerIT`** (4 falhas e 1 erro, registradas desde a r7 com o Bash 3.2 do macOS): com Bash compatível, no mesmo commit, o `FixtureRunnerIT` passou **5/5**. Elas vinham do ambiente (o runner real de homologação exige Bash 4+), não do código. Com o Bash 3.2 do sistema elas continuam acontecendo; quem roda a suíte no macOS precisa do `-Dnekkora.bash` (ou de um Bash 4+ no `PATH`).

**Reutilizado, com o motivo:**
- suíte de banco da 017 (`db/tests/identidade-017`): a composição, as dependências, o bootstrap e os fontes não mudaram desde a rodada final da R5, que está versionada (`saidas/2026-10-14T034716Z_nekkora-id017`);
- E2E entre processos: os fontes são os mesmos que produziram o 6/6 do P2 R1. O PR da missão P3/P4 executa o E2E de novo sobre o próprio commit candidato.

## 5. O que a verificação encontrou e esta entrega corrige

| Achado | Efeito | Correção |
|---|---|---|
| `api/mvnw` versionado sem bit de execução (`100644`) | em checkout limpo, o `./mvnw test` do `api/README.md` falha com `permission denied`. Os scripts internos já usam `sh ./mvnw` por isso | bit de execução no `api/mvnw` e nos `run.sh` que os READMEs mandam executar diretamente (`api/e2e`, `api/bench-argon2`, `db/tests/{seguranca-012,portal-013,mfa-014,restauracao-015,atualizacao-populada,mfa-emergencia-016}`). Conteúdo inalterado. Os demais scripts continuam como estão, chamados por `bash`/`sh` |
| `.DS_Store` aparecendo como não versionado | ruído no `git status` e risco de `git add` acidental | `.DS_Store` no `.gitignore` |
| Nenhum check automático nos PRs | a revisão dependia só de evidência local | workflow proposto (§6) |
| Primeira execução do check (PR #5 e #6, 2026-10-20): `FixtureRunnerIT.falhaDepoisDoCommitNuncaEDescritaComoRollback` com erro (`tempo esgotado esperando: ACCESS EXCLUSIVE na fila`); `nekkora-platform` e `nekkora-portal` ficaram sem executar | no runner do GitHub (4 vCPUs), o pool comum do Java tem 3 threads. O runner rodado no `CompletableFuture` do teste e as duas leituras de saída do `Processos` ocupavam as três, e o `LOCK TABLE` do teste ficava na fila sem ser enviado. Na máquina local (8 CPUs, 7 threads) não acontecia. Reproduzido localmente com `-XX:ActiveProcessorCount=4` | só código de teste: `Processos` lê a saída em threads próprias. `FixtureRunnerIT` 5/5 com 4 e com 2 CPUs |

## 6. Pendências reais

- A missão P3/P4 segue em PR próprio, a partir da `main` (independente deste).
- Não havia CI no repositório. Esta entrega **propõe** `.github/workflows/api-testes.yml`: `./mvnw -B test` (os quatro módulos, Testcontainers, sem credenciais reais e **sem** `failure.ignore`) a cada PR e a cada push na `main`, com os relatórios do surefire como artefato por 14 dias. **O que o check garante:** reprova com falha de compilação ou teste com falha ou erro. **O que não garante:** não detecta sozinho testes pulados ou que deixaram de rodar (`@Disabled`, suposição não atendida, classe fora do padrão do surefire). As contagens (`Tests run`, `Skipped`) continuam sendo lidas no log e nos relatórios publicados. As suítes de banco (`db/tests`) e o E2E (`api/e2e`) continuam manuais. As verificações da §4 são **locais**; o que o check executar no PR é registrado no próprio PR, separado delas.
- A publicação não autoriza implantação.
