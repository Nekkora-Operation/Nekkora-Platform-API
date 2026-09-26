# Validação da API v1 — Platform e portal externo (Spring Boot)

**Data:** 2026-09-26 — **revisão 3**, depois da revisão do código pelo responsável (rodada 2). **Revisão encerrada pelo responsável** (§13).
**Branch:** `feat/api-v1`, commit-base `9ff34ae` (migration 013). O conjunto revisado (pacote r3) foi commitado nesta branch depois do encerramento da revisão, sem push.
**Contrato:** `docs/relatorios/2026-09-26-decisoes-8-6-8-7-8-10.md`, **revisão 4.1**: a revisão 4 fechada mais a alteração explícita do §5.4 (duas chamadas da 013 por requisição de execução), aprovada em 2026-09-26.

**O que mudou na revisão 3:** os dois pontos da revisão do código foram corrigidos, com testes direcionados e mutações que os testes detectam (§6.9):
- a conclusão do MFA passa a ser atômica também quando o consumo do desafio falha depois do segundo fator;
- logout e revoke-all ficam fora da R7, como o contrato registra.

As execuções oficiais afetadas foram refeitas (§5). As variáveis de ambiente continuam em português nesta entrega, por decisão do responsável.

**O que mudou na revisão 2:**
- decisões do responsável aplicadas: migration 015 para a restauração lógica, Argon2 e concorrência mantidos provisórios, regra do portal no contrato ativo;
- evidências novas sobre a limpeza do `SecurityContext` e a concorrência do MFA (§6.8);
- correção da §6.6 (a proporção é de ~3×, não de 2×);
- identificadores Java renomeados para inglês (§3), por pedido do responsável;
- todas as execuções oficiais refeitas com o código final (§5).

## 1. Conclusão

**API v1 implementada e validada nos cenários listados** neste relatório.

- Não é uma cobertura exaustiva.
- **Não está pronta para produção.** Continuam abertos os gates da §7 do registro de decisões:
  - entrega real de e-mail;
  - recuperação operacional (*break-glass*) de MFA;
  - perfil de produção e calibração do Argon2id e do semáforo;
  - capacidade sob carga;
  - gateway com várias réplicas;
  - credencial de migration dedicada;
  - a restauração lógica: a 015 está validada, mas ainda não aprovada.
- Nada foi aplicado em produção e o expurgo não foi ativado. A base principal (`nekkora-postgres`, volume `nekkora-platform_nekkora_postgres_data`, porta 5432) não foi conectada nem alterada.

## 2. Ambiente

| Item | Valor |
|---|---|
| Host | Windows 11 Pro 10.0.26200, Git Bash, Docker 29.7.2 (Docker Desktop, cgroup v2) |
| Java / Maven | OpenJDK 21.0.12; Maven 3.9.16 pelo wrapper |
| Stack | Spring Boot 4.1.1 (Framework 7.0.9, Security 7.1.1, Tomcat 11, Jackson 3), pgJDBC 42.7.13, HikariCP 7.0.2, Lettuce 7.5.2, Bouncy Castle 1.86, Testcontainers 2.0.5 |
| Imagens | `postgres:16-alpine` (16.15), `redis:7-alpine`, `nginx:1.29-alpine`, `eclipse-temurin:21-jre-alpine`, `flyway/flyway:11-alpine` |

**Migrations (sha256 completo, conteúdo LF):**

| Migration | sha256 | Situação |
|---|---|---|
| 012 | `fd758edfb66af9e6b1c17697ebeb2fd8ddf9ce22fa4673c358f28efa78d1fbd7` | inalterada |
| 013 | `c6192bff305f3888710ef9430751452b7a48a5bc7078691a16e16ddebb4c14d9` | inalterada |
| 014 | `5e5726b9a8f58d319b6b17dcbf7801c21e258999b003d25a023680f0d271d8fc` | nova, candidata |
| 015 | `b4de5d81bb3cad9b2dec5d879decde2c1d8fba46429185e7297572411cb1087d` | nova, candidata (autorizada com escopo restrito) |

## 3. Entregáveis

**Migration 014:** `db/migrations/014_mfa_sessoes.sql`. Traz:
- fator TOTP, códigos de recuperação, desafios e `mfa_events` (append-only);
- a permissão `identity.mfa.reset` para ORG_ADMIN;
- em `user_sessions`: marcador de MFA, step-up e CSRF, com triggers de INSERT e UPDATE;
- a revogação das sessões pré-014 na mesma transação;
- invariantes.

**Migration 015:** `db/migrations/015_restauracao_logica.sql`. Só `ALTER FUNCTION public.is_valid_command_contract(jsonb) SET search_path = pg_catalog, public`, mais invariantes. O corpo da função e as migrations anteriores não mudam.

**Código:** `api/`, com os módulos `nekkora-core`, `nekkora-platform` e `nekkora-portal`, e as instruções em `api/README.md`.
- Identificadores em inglês (classes, métodos, campos, pacotes, testes) e chaves de configuração em inglês.
- Continuam em português: variáveis de ambiente, comentários, mensagens de log e scripts das suítes de banco.
- A renomeação foi feita só em tokens de identificador (strings, SQL, Lua e comentários intactos). Os problemas que ela causou foram corrigidos:
  - duas variáveis de template de rota (`{usuario}`, `{concessao}`) não batiam mais com o parâmetro;
  - dois testes comparavam o nome do enum como string;
  - métodos homônimos receberam o mesmo nome em inglês com sentidos diferentes.
  A suíte completa passou depois das correções.

**Harness:**
- `api/e2e/`: E2E entre processos, agora com 001–015;
- `api/bench-argon2/`: benchmark;
- `db/tests/mfa-014/`: suíte da 014;
- `db/tests/restauracao-015/`: **nova**, backup lógico e restauração;
- `db/tests/atualizacao-populada/`: agora com 001–012 → 015 e restauração lógica depois da atualização.

**Suítes 012 e 013:** lista explícita de migrations (`TESTE_MIGRACOES`); as asserções não mudaram.

**Documentos:**
- este relatório;
- o registro de decisões (revisão 4.1: §1, §5.4, §6.3, §7, §8);
- o adendo §16 do relatório da 013;
- os READMEs das suítes e da `api/`.

## 4. Fronteira banco × aplicação

| Garantia | Onde é imposta | Observação |
|---|---|---|
| Isolamento entre organizações | Banco: RLS 012, contexto `nekkora.organization_id` | A aplicação só define o contexto por `set_config(..., true)` com bind |
| O portal só lê o que a sessão autoriza | Banco: as funções da 013 (`SECURITY DEFINER`, NK401) | Cada função resolve a sessão e os workspaces autorizados por conta própria; a lista de workspaces do portal nunca substitui essa verificação (§6.4) |
| Sessão nova exige marcador de MFA, CSRF e fator ativo; revogada não reabilita; prazo não se estende | Banco: triggers da 014 | **Barreira estrutural, não prova de MFA** |
| Verificação do segundo fator, anti-replay, tentativas, códigos de recuperação | Aplicação e banco | Lock da conta na aplicação; primitivas `UPDATE … WHERE <disponível> RETURNING` no banco |
| Senha (Argon2id), hash fictício, re-hash só para cima | Aplicação | Semáforo 4 + fila 8 / 2 s → 503 (**provisório**) |
| CSRF (três contextos), Origin/Referer, cookies | Aplicação | — |
| Limites R1–R7, atraso progressivo | Aplicação (Redis) e borda (nginx) | Redis fora do ar → 503, exceto logout, revoke-all e leituras autenticadas |
| Identidade por requisição | Aplicação (Spring Security) | Na entrada, o `SecurityContextHolderFilter` troca o contexto da thread; na saída, ele e o `FilterChainProxy` limpam em `finally` (§6.8) |
| Ausência de segredo em log | Aplicação (logback) e banco (`log_parameter_max_length_on_error=0` no portal) | — |
| Reset de estado da conexão | Aplicação (`DISCARD ALL` na devolução ao pool) | — |
| Restauração lógica | Banco (015) | Backups gerados depois da 015; backups antigos pelo procedimento do §6.3 do registro |

## 5. Resultados (execuções oficiais com o código final)

| Execução | Resultado | Onde |
|---|---|---|
| `./mvnw clean test` (todos os módulos, 001–015) | **BUILD SUCCESS:** core 5, platform 41, portal 12 — **58 testes**, 0 falhas, 0 pulados | relatórios do Surefire no pacote de revisão (`REVISAO/surefire/`) |
| E2E entre processos (`api/e2e/run.sh`, 001–015) | **6/6**, rc=0 (refeito depois das correções da revisão 3) | `api/e2e/saidas/2026-09-26T192328Z_nekkora-e2e/` |
| Restauração lógica (`db/tests/restauracao-015/run.sh`) | **rc=0** | `db/tests/restauracao-015/saidas/2026-09-26T181406Z_nekkora-restauracao/` |
| Atualização populada (001–012 → 015, Flyway) | **rc=0** | `db/tests/atualizacao-populada/saidas/2026-09-26T183122Z_nekkora-atualizacao/` |
| Suíte 014 oficial | **rc=0** (17 etapas, 2 mutações) | `db/tests/mfa-014/saidas/2026-09-26T172304Z_nekkora-mfa-014/` |
| Suíte 013 oficial (001–013 explícitas) | **rc=0** (65 etapas) | `db/tests/portal-013/saidas/2026-09-26T172102Z_nekkora-portal-013c/` |
| Suíte 012 oficial (001–012 explícitas) | **rc=0** (25 etapas) | `db/tests/seguranca-012/saidas/2026-09-26T172020Z_nekkora-seg-012/` |
| Benchmark Argon2id (duas execuções) | Números provisórios (§6.6) | `api/bench-argon2/saidas/2026-09-26T165743Z_cpus2_mem1g/` e `…183606Z_cpus2_mem1g/` |

- As correções da revisão 3 mudaram só código Java (`AuthService`, `SessionFilter`, `Limits`). Por isso foram refeitos a suíte Maven e o E2E; as suítes de banco, a restauração e a atualização não dependem desse código.
- As suítes 012, 013 e 014 não mudaram depois das execuções oficiais listadas. Os runners delas só ganharam READMEs.
- A 015 não entra nelas: cada uma monta sua própria lista explícita de migrations.
- As stacks criadas nesta missão foram removidas depois de salvas as evidências. Continuam no ar só as preexistentes: `nekkora-portal-dev` (55479), `nekkora-portal-013` (55513) e a principal.

## 6. Evidências por requisito

### 6.1 JDBC, contexto e pool (`ConnectionResetIT`, 3)

- `bindContextDoesNotLeakToNextTransaction`:
  - `set_config` por bind;
  - só a própria organização aparece;
  - a transação seguinte na mesma conexão não herda o contexto;
  - sem contexto, a RLS falha fechada.
- `discardAllOnReturnClearsStateAndPreparedStatementsRecover`:
  - mesma conexão física (mesmo PID);
  - o `DISCARD ALL` apaga o GUC de sessão, a tabela temporária e o *prepared statement* do servidor;
  - o pgJDBC prepara de novo sem erro.
- `readOnlyTransactionDoesNotWrite`: escrita em transação só de leitura → 25006.

### 6.2 Autenticação, MFA e sessões

**`AuthenticationFlowIT` (10):**
- o primeiro acesso cadastra o MFA, e a sessão só nasce depois do segundo fator;
- sem enumeração;
- o tempo de conta inexistente fica na faixa da senha errada;
- a tentativa errada é persistida antes do 401 e o desafio bloqueia na quinta;
- o passo TOTP não é reutilizado;
- confirmações concorrentes do **mesmo** desafio criam uma sessão;
- o código de recuperação revoga as outras sessões e não vale duas vezes;
- credencial alterada durante o desafio impede a sessão;
- re-hash só para cima, depois do MFA;
- CSRF por contexto e Origin.

**`SessionsAndAdministrationIT` (7):**
- revalidação a cada requisição;
- prazo absoluto e inatividade;
- logout, revoke-all e troca de senha;
- recuperação de senha;
- reset administrativo com step-up;
- suspensão e reativação;
- limite de 10 sessões.

**Unitários:** `PrimitivesTest` (7) e `ClientIpResolverTest` (2), cobrindo RFC 6238, Base32, PHC e política, semáforo, política de senha, AES-GCM com AAD, códigos de recuperação e proxy confiável.

### 6.3 Limites e Redis (`LimitsAndLogsIT` 4, `RedisUnavailableIT` 1, `ReducingAccessRoutesIT` 2)

- R1 com `Retry-After`, e outro IP segue.
- R2 progressivo, sem bloqueio permanente.
- Os contadores não zeram só com a senha correta.
- Nenhum segredo no log do processo nem no do PostgreSQL.
- Com o Redis fora do ar: 503 no pré-login e na escrita; leitura e revoke-all seguem.
- Com o Redis funcionando e a cota R7 esgotada: logout e revoke-all → 204 e revogação efetiva (§6.9).

### 6.4 Portal (`PortalIT` 10, `CursorAndLabelsTest` 2)

- Processo, datasource e papel próprios.
- Só `GET`.
- Omissão de `workspaceId`.
- Paginação keyset com empate.
- Validação.
- Allowlist.
- NK401 → 401.
- `log_parameter_max_length_on_error=0` fixado, verificado por conexão e na partida.

**Regra de duas chamadas (revisão 4.1), pontos pedidos para a revisão:**

| Ponto | Evidência |
|---|---|
| As duas chamadas usam a mesma conexão e a mesma transação | Código: `PortalService` (linhas 68–69 e 101–102) chama `listWorkspaces` e a função da rota dentro de um único `tx.withoutContext(...)` (`OrgTransactions.withoutContext`, linha 54: um `TransactionTemplate` `READ COMMITTED` de escrita). `PortalConfig`: o gerenciador de transações, o `JdbcTemplate` e o `OrgTransactions` usam o **mesmo** bean `ConnectionResetDataSource`. Teste: `executionRequestUsesOneConnectionForBothFunctionCalls` confere a identidade dos beans e que lista e detalhe retiram **uma** conexão do pool cada |
| A função que devolve os dados verifica a própria autorização | 013: `portal_list_executions`, `portal_get_execution` e `portal_list_execution_events` chamam `portal_resolve_session` e filtram por `portal_authorized_workspaces` (linhas 294/311, 344/356, 380/390). O token vai a cada função como bind |
| A lista de workspaces nunca substitui essa verificação | `PortalService`: o resultado de `listWorkspaces` só produz o booleano `omit` (omitir o campo). Nenhum id da lista é passado à função de dados. Teste: execução de outro cliente → 404 com sessão válida (`PortalIT`, `CrossProcessE2E`) |
| O toque mantém a frequência aprovada | 013: `UPDATE … WHERE last_seen_at <= o_t - interval '1 minute'`. Teste `activityTouchAtMostOncePerMinuteWithTwoFunctionCallsPerRequest`, com um trigger contador **declarado**, só na base descartável e removido ao final: uma requisição de execução (duas funções) → **1** toque; requisições seguintes no mesmo minuto → nenhum toque novo |

### 6.5 Ponta a ponta entre processos (`CrossProcessE2E`, 6)

nginx (TLS descartável, `limit_req`, X-Forwarded-For sobrescrito) → Platform e Portal em containers próprios → PostgreSQL 16 (001–015) e Redis. O cliente fala só com o nginx.
- Atributos dos três cookies `__Host-`.
- O Portal aceita o cookie emitido pela Platform.
- Logout e revoke-all na Platform derrubam o Portal.
- CSRF e Origin → 403.
- Nenhum segredo nos logs de nenhum container.
- Rajada: 57 recusas registradas pelo nginx.
- O Portal recebe só as próprias variáveis.
- A chave TLS é apagada depois da partida.

### 6.6 Argon2id no container (referência declarada: `--cpus=2 --memory=1g`, JRE 21, `MaxRAMPercentage=75`)

| m (KiB) / t | Execução | Sequencial p95 (real / fictício) | 4 simultâneos p95 | Vazão | 12 chegadas (4 + fila 8) | Pico do container |
|---|---|---|---|---|---|---|
| 19456 / 2 | 1 · 2 | 45 / 44 · 51 / 51 ms | 148 · 145 ms | 41 · 41/s | 12 ok · 12 ok | 289 · 294 MiB |
| 47104 / 1 | 1 · 2 | 72 / 66 · 83 / 76 ms | 308 · 361 ms | 20 · 17/s | 12 ok · 12 ok | 609 · 653 MiB |
| **65536 / 3** | 1 · 2 | **199 / 196 · 224 / 224 ms** | **595 · 696 ms** | 8,5 · 7,3/s | 12 ok · 12 ok | 700 · 738 MiB |
| 131072 / 3 | 1 · 2 | 416 / 396 · 502 / 513 ms | 1399 · 1921 ms | 3,4 · 2,6/s | 9 ok + 3×503 · 8 ok + 4×503 | 801 · 801 MiB |

- O hash fictício fica na mesma faixa do real em todos os perfis.
- Houve throttling de CPU em todos.
- O pico de memória é dominado pelo heap da JVM.
- **Correção desta revisão:** no candidato, 4 simultâneos em 2 CPUs **triplicam** o p95 em relação ao sequencial (595/199 ≈ 3,0 na execução 1; 696/224 ≈ 3,1 na execução 2). A revisão 1 dizia "dobram"; a §8.2 já estava certa.
- **Decisão do responsável (revisão 4.1):** Argon2 e concorrência continuam **provisórios**. O resultado não determina que o semáforo deva ser igual ao número de CPUs: a escolha depende também de memória, vazão, fila e latência da aplicação completa. Com o perfil de produção definido, comparar concorrência 1, 2 e 4 com a API em funcionamento. Os parâmetros criptográficos **não** serão reduzidos para compensar contenção.

### 6.7 Atualização populada (Fase 3) e restauração lógica

**Atualização (`db/tests/atualizacao-populada`):**
- massa na origem: 3 organizações, 303 usuários, 660 sessões (300 ativas), 120 concessões, 4 500 execuções e 2 250 eventos;
- cópia física (`pg_basebackup`) idêntica à origem;
- diff de catálogo antes do baseline: **vazio** (3 142 linhas);
- sem histórico de migration; aprovação registrada pelo runner (em produção, humana);
- `migrate` sem baseline **recusado**; `baseline 012` → `migrate` → `validate`; histórico `012 BASELINE, 013, 014, 015`, todos com sucesso;
- catálogo final igual ao da instalação limpa 001–015, exceto `flyway_schema_history`. De 012 para 015 a **única** linha de 001–012 alterada é o `search_path` de `is_valid_command_contract` (mesmo md5 de corpo), e isso é asserção;
- dados: só `permissions` (+1), `role_permissions` (+1) e `user_sessions` (480 linhas; só `revoked_at` de nulo para preenchido);
- papéis reais: triggers da 014 pela mensagem, NK401 para a sessão pré-014, sessão nova lê os dados pré-existentes;
- **restauração lógica da cópia atualizada** (backup gerado depois da 015): rc=0, contagens por tabela idênticas;
- origem intacta.

**Restauração lógica (`db/tests/restauracao-015`, decisão 1 do responsável):**

1. **Controle negativo:** o backup gerado **antes** da 015 falha na restauração (`function is_valid_relative_entrypoint(text) does not exist`).
2. **Mutação:** sem o `search_path`, o bloco de invariantes da 015 falha.
3. **Base A** (populada, 001–015) → **backup novo** → **restauração completa** noutro cluster (B), com `pg_restore --create --exit-on-error`: rc=0.
4. **A × B idênticos:**
   - catálogo: 3 480 linhas;
   - papéis e configurações de papel: 13 linhas;
   - dados linha a linha: 49 tabelas, 8 831 linhas.
5. **Papéis reais em B:**
   - `v01` (`nekkora_platform`): triggers da 014 e sessão nova;
   - `v02` (`nekkora_portal`): NK401 para a sessão revogada; sessão nova lê a concessão e as 84 execuções restauradas, com ids conferidos;
   - CHECK corrigido depois da restauração: publicar com entrypoint válido → 1 linha; com `../fora.py` → 23514 `automation_versions_check3`.
6. **Backup antigo pelo procedimento próprio** (cluster C: `pre-data` → 015 → `data` → `post-data`): catálogo e dados idênticos aos de A com a 015.

**Distinção registrada:** a 015 valida backups gerados **depois** dela. Um backup antigo contém a função defeituosa, e aplicar a 015 depois de uma restauração que já falhou não a conserta. O procedimento próprio está no registro de decisões, §6.3, e em `db/tests/restauracao-015/README.md`.

### 6.8 Pontos de atenção pedidos para a revisão do código

**A. Remoção da limpeza do `SecurityContext` no `SessionFilter`**

*Quem limpa:*
- **Na entrada:** o `SecurityContextHolderFilter` (registrado por `http.securityContext(...)`, antes do `SessionFilter`) troca o contexto da thread pelo do repositório configurado (`RequestAttributeSecurityContextRepository`: atributo da requisição, vazio numa requisição nova).
- **Na saída:** o `SecurityContextHolderFilter` e o `FilterChainProxy` chamam `clearContext()` em `finally`, com sucesso ou com exceção.
- O `SessionFilter` só **define** o contexto depois de validar a sessão no banco. Ele não limpa, porque o `ExceptionTranslationFilter` ainda precisa do contexto para distinguir 401 de 403. O comentário no código (`SessionFilter`, linhas 77–81) registra isso.

*Evidência executável:*

| Teste | O que prova |
|---|---|
| `SecurityContextLifecycleIT` | Chama a cadeia **real** (`springSecurityFilterChain`) quatro vezes na **mesma thread**. (1) Sucesso: o destino vê a autenticação, e depois o ThreadLocal está vazio. (2) Exceção no destino: a exceção sobe, o destino estava autenticado, e depois o ThreadLocal está vazio. (3) Anônima seguinte: 401 `unauthenticated`, destino não alcançado. (4) **Vazamento simulado**, uma identidade autenticada deixada na thread por fora da cadeia: a anônima seguinte continua recebendo 401 e o contexto sujo é descartado |
| `SingleWorkerThreadIT` | Tomcat real com **um único** thread de trabalho: toda requisição usa a mesma thread. Cinco ciclos de: autenticada com sucesso → anônima; autenticada com erro (404 + despacho para `/error`) → anônima. A anônima numa rota inexistente recebe sempre **401**. Com identidade vazada, ela passaria pela autorização e receberia 404 |

**B. Concorrência do MFA**

A revisão estava certa: a suíte SQL da 014 (`conc_*`) prova as primitivas só com as duas sessões disputando o **mesmo** desafio. A segunda fica presa no lock do desafio e nem chega a disputar o passo TOTP ou o código. Os casos pedidos não estavam cobertos. Agora estão, pela API real, em `MfaConcurrencyIT` (5 cenários):
- **cada cenário:** 10 rodadas, com uma conta nova por rodada e largada simultânea (`CountDownLatch`);
- **atraso alternado** entre os lados, para exercitar as duas ordens de chegada;
- **o que se afirma:** o invariante no estado final, qualquer que seja a ordem.

| Cenário | Invariante afirmado | Ordens observadas na execução oficial |
|---|---|---|
| Mesmo passo TOTP em dois desafios **diferentes** do mesmo usuário | Exatamente um 200 e um 401; uma sessão nova | — |
| Mesmo código de recuperação em dois desafios diferentes | Exatamente um 200 e um 401; código usado uma vez; uma sessão ativa | — |
| Conclusão do MFA × troca de senha | A troca sempre conclui (204); a sessão nascida da credencial antiga nunca sobrevive; só a sessão que trocou fica ativa; nenhum desafio pendente | verify 200 (depois revogada) ×8; verify 401 ×2 |
| Conclusão do MFA × suspensão | A suspensão sempre conclui; conta sem sessão ativa | verify 200 ×4; verify 401 ×6 |
| Conclusão do MFA × reset administrativo | O reset sempre conclui; sem sessão ativa; fator desabilitado; nenhum desafio pendente | verify 200 ×5; verify 401 ×5 |

- As duas ordens aparecem nas três corridas.
- Nas rodadas ímpares da troca de senha, o lado do MFA recebe até 900 ms de atraso, porque a troca faz dois Argon2 antes do lock e, sem isso, sempre chegaria depois.
- É evidência de invariantes sob concorrência real, **não** prova exaustiva de todas as intercalações.

### 6.9 Revisão do código, rodada 2: dois pontos corrigidos

**1. Código de recuperação ou passo TOTP consumido sem concluir o login**
- **Defeito** (`AuthService.verify`): o segundo fator era consumido antes do desafio. Se o desafio expirasse nesse intervalo, `consumeChallenge()` devolvia 0 e o método retornava `Denied`, uma saída **normal**: a transação fazia COMMIT com o código ou o passo já gasto, sem sessão, e a resposta era 401. O mesmo padrão existia no `confirmEnrollment` (desafio consumido e `confirm` falhando).
- **Correção:** falha de consumo **depois** de um segundo fator correto lança `ConsumptionFailed` dentro da transação. O `TransactionTemplate` desfaz tudo o que ela consumiu, e a exceção vira `Denied("consumo")` → 401 fora dela. Isso cumpre a §3.2, passo 4: o caminho do código correto é atômico. O caminho do código **errado** (§3.2, passo 3) não passa por aí: continua retornando normalmente, e a tentativa é confirmada.
- **Teste** (`MfaAtomicCompletionIT`, 3):
  - **Instrumentação declarada**, só na base descartável: o prazo do desafio é encurtado para ~2 s, e um trigger restrito à conta do teste atrasa em 4 s o consumo do segundo fator. Assim o desafio expira exatamente entre as duas etapas.
  - **Código de recuperação:** 401; `used_at` continua nulo; nenhuma sessão nova; as sessões existentes ficam intactas (o código não revogou nada); sem evento nem aviso de uso. O **mesmo** código vale no login seguinte (200).
  - **Passo TOTP:** 401; `last_used_step` inalterado; nenhuma sessão nova. O **mesmo** código TOTP vale no login seguinte (200).
  - **Tentativa errada:** continua confirmada (`attempts = 1`).
  - **Mutação:** com o `return new Denied(...)` de volta, os dois primeiros casos falham (código consumido; passo avançado de …678 para …679).

**2. R7 bloqueando logout e revoke-all**
- **Defeito** (`SessionFilter`): `limits.bySessionOrIp()` rodava também nessas rotas. A exceção de `Routes.reducesAccess()` só cobria o Redis indisponível (503), não o 429 com o bucket esgotado. Isso divergia da tabela de rotas do contrato (§5.3: "sem limitador").
- **Correção:** logout e revoke-all não passam pela R7. Continuam valendo:
  - a autenticação (revoke-all exige sessão válida);
  - Origin e CSRF (`CsrfFilter`);
  - a camada 1 do gateway.
- **Teste** (`ReducingAccessRoutesIT`, 2), com o Redis funcionando:
  - a cota R7 da sessão é esgotada; controle: leitura e escrita comum recebem 429 com `Retry-After`;
  - **logout** → 204, e a sessão fica revogada no banco;
  - **revoke-all** → 204, e a sessão atual e a outra sessão da conta ficam revogadas;
  - antes disso, sem CSRF → 403 e com Origin estranho → 403, sem revogar nada; revoke-all sem sessão → 401. O **logout** mantém o comportamento idempotente do contrato (§5.3), e esse caso não é o que este teste cobre.
  - **Mutação:** com a R7 de volta nessas rotas, os dois testes falham (429).

**Também nesta rodada:** o método que monta o 429 no `Limits` se chamava `pageLimit` (sobra do mapeamento global da renomeação) e passou a se chamar `rateLimited`.

## 7. Falhas encontradas e corrigidas

**Produto (revisão 1):**
- `VERIFICA_LOG` com `1/0` dobrado pelo planejador (toda conexão falhava);
- `SessionFilter` limpando o contexto (403 em vez de 401), com a análise completa em §6.8 A;
- `/api/v1/csrf` com 500 em vez de 503 com o Redis fora do ar;
- conflito de bean;
- conflito de `/error`.

**Revisão 3 (rodada 2 da revisão do código):**
- conclusão do MFA não atômica quando o desafio vencia depois do consumo do segundo fator;
- R7 aplicada a logout e revoke-all.

Ambas detalhadas em §6.9.

**Revisão 2:**
- Variáveis de template de rota desalinhadas pela renomeação, dando 500 em suspender, reativar, reset e revogar concessão. Detectado pelos testes e corrigido; a URL não mudou.
- Instabilidade do helper de teste `code()`, que gerava o passo anterior perto da virada de 30 s (401 legítimo pelo contrato). Corrigido no helper; o produto não mudou.
- No harness da 015, a verificação do CHECK passava sem testar nada. A regra só vale para versão publicada, e um CTE com INSERT não é visto pelo UPDATE do mesmo comando. Reescrita com controle positivo (`UPDATE 1`) e negativo (23514).

**Testes e harness (revisão 1):**
- `docker exec -i` consumindo o `while read`;
- `LAST_ERROR_SQLSTATE` mascarando casos;
- CSRF literal num INSERT negativo do harness;
- `\bind :'var'`;
- IP compartilhado;
- envelhecimento de sessões;
- substring "97";
- passos TOTP absolutos;
- 42501 antes da trigger.

Nenhuma asserção foi enfraquecida.

## 8. Achados e decisões

1. **Restauração lógica:** corrigida pela 015 (candidata), para backups gerados depois dela. Backups antigos: procedimento próprio. Continua gate até a aprovação da 015.
2. **Argon2 × concorrência:** com 4 simultâneos em 2 CPUs, o p95 do candidato triplica. Os valores continuam provisórios, pela decisão do §6.6.
3. **Marcador de MFA na 014:** barreira estrutural, não prova (revisão 4). Risco de privilégio do `nekkora_platform`.

## 9. Cenários não cobertos

- Passkeys (fora da v1) e convite (adiado).
- Entrega real de e-mail e *break-glass* de MFA (gates).
- Carga e capacidade:
  - paginação e limites sob concorrência;
  - Redis em cluster ou failover, e latência degradada;
  - várias réplicas.
- Concorrência de 1, 2 e 4 no Argon2 com a API completa (depende do perfil de produção).
- Navegador real (o E2E usa um cliente HTTP com TLS real).
- Rotação de chaves AES/HMAC em uso.
- Divergência de relógio entre instâncias e o banco.
- Traces e métricas (sem exportador configurado).
- HSTS e CSP.
- Atualização e restauração: massa sintética, não uma cópia de produção; Flyway com superusuário; restauração lógica só com `pg_dump -Fc` (os formatos *plain* e *directory* não foram ensaiados).
- Corridas do MFA: invariantes sob concorrência real, sem enumeração exaustiva de intercalações. A concorrência com recuperação de senha por e-mail não foi ensaiada.
- A faixa de tempo contra enumeração foi medida num ambiente só.

## 10. Riscos residuais

- Argon2id e semáforo provisórios.
- Redis indisponível derruba o pré-login (falha fechada, por decisão).
- O IP do cliente depende de `NEKKORA_PROXIES_CONFIAVEIS` correto em cada ambiente.
- Duas resoluções de sessão por requisição de execução (aceito, revisão 4.1).
- Dependências recentes (Boot 4.1.1).
- Senha padrão local do superusuário no `docker-compose.yml` (preexistente).
- Backups lógicos gerados **antes** da 015 continuam exigindo o procedimento próprio.
- Variáveis de ambiente ainda em português, se a padronização em inglês for estendida a elas.

## 11. Instruções reproduzíveis

```sh
# a partir da raiz do repositório, na branch feat/api-v1
cd api && ./mvnw clean test && cd ..
api/e2e/run.sh
api/bench-argon2/run.sh
db/tests/seguranca-012/run.sh
db/tests/portal-013/run.sh            # ou TESTE_PROJETO=... TESTE_PORTA=... se nekkora-portal-013 estiver no ar
db/tests/mfa-014/run.sh
db/tests/restauracao-015/run.sh
db/tests/atualizacao-populada/run.sh
```

- Cada runner cria recursos isolados, recusa nomes e portas em uso e imprime o comando de remoção.
- Segredos são gerados por execução e passados só por ambiente.
- Requisitos: Docker; Java 21; Git Bash, Linux ou macOS; Python 3 (só no ensaio de atualização).

## 12. Estado do repositório

- Commit do conjunto revisado na branch `feat/api-v1`, depois do encerramento (§13); **sem push**. O pacote de revisão r3 traz o mesmo conteúdo, com o `git diff` dos arquivos rastreados e os hashes completos.
- **Novos:**
  - `api/`;
  - `db/migrations/014_mfa_sessoes.sql` e `015_restauracao_logica.sql`;
  - `db/tests/mfa-014/`, `db/tests/restauracao-015/` e `db/tests/atualizacao-populada/`;
  - `docs/relatorios/2026-09-26-decisoes-8-6-8-7-8-10.md` e este relatório.
- **Modificados (rastreados):**
  - `.gitattributes`;
  - runners, compose e READMEs de `db/tests/seguranca-012` e `db/tests/portal-013`;
  - `docs/relatorios/2026-09-25-preparacao-autorizacao-api-nekkora.md`;
  - `docs/relatorios/2026-09-26-validacao-013-portal-externo.md`.
- `db/tests/portal-8-2/` e a stack `nekkora-portal-dev` (preexistentes) não foram alteradas.

## 13. Encerramento da revisão (2026-09-26)

- **Os dois achados da rodada 2 estão encerrados**, dentro da cobertura documentada (§6.9):
  - **MFA:** a exceção sai da transação antes de ser convertida em 401, o que garante o rollback. As tentativas incorretas continuam sendo gravadas.
  - **Logout e revoke-all:** ficam fora da R7, preservando autenticação, Origin e CSRF onde se aplicam.
- **Conferências do responsável no pacote r3:**
  - SHA-256 do ZIP igual ao informado;
  - manifesto com 589 arquivos, sem divergência;
  - Surefire com 58 testes, zero falhas, erros ou ignorados;
  - migrations sem mudança em relação ao pacote anterior.
- **Precisão registrada:** o teste "sem sessão → 401" é de revoke-all; o logout mantém o comportamento idempotente.
- A revisão foi por inspeção do código, dos testes e das evidências, **sem reexecução de Docker** no ambiente do revisor. Não houve novo bloqueio nas alterações da rodada.
- **Autorizado:** commit do conjunto revisado. Os dois `.java` duplicados em `docs/relatorios` eram idênticos às versões do pacote r2 (sha256 conferido), não integram a entrega e foram removidos.
- **Continuam abertos os gates de produção** (registro de decisões, §7):
  - entrega real de e-mail;
  - recuperação operacional de MFA;
  - perfil de produção e calibração do Argon2id e do semáforo;
  - capacidade sob carga;
  - gateway com várias réplicas;
  - credencial de migration dedicada;
  - aprovação da 015 para a restauração lógica.
