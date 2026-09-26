# Validação da migration 013 — portal externo (decisão 8.2)

**Data:** 2026-09-26
**Status:** migration candidata `db/migrations/013_portal_externo.sql` **implementada e validada nos cenários listados** (§6), numa stack PostgreSQL 16 isolada, com os papéis reais. **Não** foi aplicada na base principal. **Sem** commit e sem push. **O portal não está completo:** o código Spring ainda não existe neste checkout (§10). O fechamento formal da 8.2 fica com o responsável (§12).
**Decisão do responsável (2026-09-26):** 8.2 **fechada para a camada de banco**, com base neste relatório; a conclusão vale para a cobertura documentada, não exaustiva. A migration **não** está aprovada para merge nem produção: falta a revisão direta da migration e da suíte. O portal como produto **não** está completo (§14).
**Base:** `docs/relatorios/2026-09-25-desenho-portal-externo-8-2.md` (revisão 2, D1–D7) e `2026-09-25-preparacao-autorizacao-api-nekkora.md` (§11.4 e §11.5).
**Evidência oficial:** `db/tests/portal-013/saidas/2026-09-26T044801Z_nekkora-portal-013/` (runner `db/tests/portal-013/run.sh`, exit 0).

A cobertura **não é exaustiva**. Ela prova os cenários da §6 contra o banco e nada além deles.

## 1. Checkout e ambiente

| Item | Valor |
|---|---|
| Repositório | `nekkora-platform`, branch `main`, commit `0a333db16e86bfd3ff8f1246bcf91a3563c87d24` (o da missão) |
| Árvore | Sem alterações rastreadas. Não rastreados: `db/migrations/013_portal_externo.sql` e `db/tests/portal-013/` (esta missão); `db/tests/portal-8-2/` (**preexistente**, preservado intacto, §2) |
| Fontes de referência | Desenho da 8.2, relatório da API e demais relatórios em `docs/relatorios/` são idênticos, byte a byte, às cópias em `../relatorios/`; migrations 001–012 presentes (SHA-256 registrados no início) |
| Migration 013 | SHA-256 `c6192bff305f3888710ef9430751452b7a48a5bc7078691a16e16ddebb4c14d9` (a mesma do run oficial) |
| Ferramentas | Docker 29.7.2, Compose 5.3.1, PostgreSQL 16.15 (`postgres:16-alpine`), Git Bash 5.3 (Windows 11). Sem `psql` no host: todo `psql` roda dentro do container de teste |

## 2. Divergências e decisões registradas antes de implementar

| # | Divergência | Decisão |
|---|---|---|
| V1 | A missão manda usar "o limite interno provisório documentado no desenho". O desenho (§4.4, §10) e o relatório da API (§11.2, §11.5) **não documentam valor**: N fica para a 8.7. O valor 100 só existia na candidata preexistente, não aprovada | Responsável (2026-09-26): **N = 100, provisório** até a 8.7. Fica numa constante (`c_max_page`) na função e no cabeçalho da migration |
| V2 | O desenho (§8) prefere a migration fora de `db/migrations/` até a aprovação; a missão pede `db/migrations/013_portal_externo.sql` | Responsável: **`db/migrations/`** (missão). Consequências em V5 e §9 |
| V3 | `db/tests/portal-8-2/`, não rastreado e criado hoje entre 00:49 e 00:53: candidata 013 e harness incompleto. O único run dele parou na fixture (`ux_executions_active_worker`) sem executar nenhum teste e deixou no ar a stack `nekkora-portal-dev` (porta 55479) | Responsável: **reaproveitar como base** numa suíte nova (`db/tests/portal-013/`). A pasta anterior e a stack `nekkora-portal-dev` **não foram tocadas** |
| V4 | Docker Desktop parado no terminal no início da missão | O responsável iniciou o Docker. Com isso, os containers `nekkora-postgres` e `nekkora-portal-dev` voltaram sozinhos (`restart: unless-stopped`), sem comando meu |
| V5 | Com a 013 em `db/migrations/` (montado como `docker-entrypoint-initdb.d`), a suíte histórica `db/tests/seguranca-012/` exige exatamente 12 migrations e **deixa de passar** neste checkout. Uma recriação do volume principal também aplicaria a 013 | Registrado. A suíte da 012 **não foi alterada** |

**Escolhas de implementação que não estão literalmente no desenho** (todas conferidas pela suíte):
- A política de UPDATE em `user_sessions` chama-se `portal_function_context_update`. O desenho usa o mesmo nome da de SELECT, mas o nome de política é único por tabela.
- Há uma função interna a mais, `portal_authorized_workspaces` (SQL, `STABLE`, INVOKER, sem EXECUTE para o portal). É a reconferência de conta, vínculo, organização, cliente, concessão e workspace, chamada **dentro do comando de leitura** (mesmo snapshot).
- Validação do token mais estrita que "43 caracteres base64url": o último caractere precisa ser um dos 16 possíveis para 32 bytes (`^[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$`). É a codificação canônica de D2.
- Duas configurações de log de reforço além das cinco do desenho: `log_min_duration_sample = -1` e `log_transaction_sample_rate = 0`.
- O toque restringe também `organization_id = p_organization_id`, além de `session_id`.
- Invariantes da §8 da migration ampliados: dono de objeto, sequências, privilégio de tabela inteira do owner e `USAGE` explícito.
- Índice de desempenho do keyset (desenho §8, item 8, "a medir"): **não incluído** (§10).

## 3. Stack isolada e base principal

| Item | Valor |
|---|---|
| Projeto / container | `nekkora-portal-013` / `nekkora-portal-013-postgres` |
| Volume / rede / porta | `nekkora-portal-013_nekkora_postgres_data` / `nekkora-portal-013_default` / **55513** |
| Marca | `cluster_name = nekkora-portal-013-isolada`. Os scripts administrativos recusam rodar sem ela |
| Proteções do runner | Aborta se container, volume ou rede já existirem, se a porta estiver publicada ou em uso, ou se o nome for o da stack principal. Não executa `down -v` nem limpeza global |
| Stacks de ensaio | `nekkora-portal-013dev` (porta 55514) e `nekkora-portal-013ens` (55516), criadas e removidas por mim. Antes de cada remoção, conferi o rótulo `com.docker.compose.project` |
| Estado atual | `nekkora-portal-013` **continua no ar**. Remoção: `docker rm -f nekkora-portal-013-postgres && docker volume rm nekkora-portal-013_nekkora_postgres_data && docker network rm nekkora-portal-013_default` |

**Base principal não alterada.** Nenhum comando desta missão se conectou a `nekkora-postgres`, ao volume `nekkora-platform_nekkora_postgres_data` ou à porta 5432. A identidade da stack principal foi registrada antes do primeiro comando e depois do run oficial, e bate: mesmo ID de container, criação `2026-09-25T21:03:29Z`, mesma imagem, `RestartCount = 0`, mesmo volume (`zz_stack_principal_antes_depois.txt`). Por decisão, o conteúdo da base principal não foi consultado: a evidência é a ausência de comandos contra ela, somada aos metadados idênticos.

## 4. Migration 013 (resumo do que foi instalado)

- **Papéis:**
  - `nekkora_portal`: LOGIN, sem SUPERUSER/CREATEDB/CREATEROLE/REPLICATION/BYPASSRLS, **sem membership**;
  - `nekkora_portal_owner`: NOLOGIN, com os mesmos atributos negados.
- **Portal:** `USAGE` explícito em `public` e `EXECUTE` concedido em exatamente 4 funções, que são as **únicas funções `SECURITY DEFINER`** que ele executa. Não é "EXECUTE em apenas quatro funções" em sentido absoluto: as funções `SECURITY INVOKER` antigas de `public` continuam executáveis por `PUBLIC` (§9, item 5). Nenhum privilégio de tabela, view, coluna ou sequência.
- **Owner:** `SELECT` por coluna, exatamente a lista do desenho §4.1 (58 colunas em 10 tabelas), mais `UPDATE (last_seen_at)`. Sem INSERT, DELETE ou TRUNCATE.
- **RLS:** 10 políticas `portal_function_context FOR SELECT` e 1 `portal_function_context_update FOR UPDATE` (USING = WITH CHECK), todas só sobre `nekkora.fn_organization_id`. As 81 políticas da 012 seguem intactas.
- **Funções** (owner `nekkora_portal_owner`, `search_path = pg_catalog, pg_temp`, referências `public.*` qualificadas, `REVOKE ALL FROM PUBLIC`):
  - públicas, `SECURITY DEFINER`, `VOLATILE`:
    - `portal_list_workspaces(uuid, text)`;
    - `portal_list_executions(uuid, text, uuid, smallint[], timestamptz, timestamptz, timestamptz, uuid, integer)`;
    - `portal_get_execution(uuid, text, uuid)`;
    - `portal_list_execution_events(uuid, text, uuid)`;
  - internas, INVOKER, sem EXECUTE para o portal:
    - `portal_resolve_session(uuid, text)`;
    - `portal_authorized_workspaces(uuid, uuid, uuid, timestamptz)`.
- **Log do papel do portal** (`ALTER ROLE … SET`, globais):
  - `log_error_verbosity = terse`;
  - `log_min_error_statement = panic`;
  - `log_parameter_max_length = 0`;
  - `log_statement = none`;
  - `log_min_duration_statement = -1`;
  - reforço: `log_min_duration_sample = -1` e `log_transaction_sample_rate = 0`.
- **Expurgo:** nenhuma alteração em `purge_expired_records` e nenhum EXECUTE aos papéis do portal.
- **Diff de catálogo 001–012 × 001–013** no mesmo cluster (base `nekkora_ref` com 001–012): **0 linhas removidas ou alteradas; 88 acrescentadas, todas do portal** (59 ACL de coluna, 6 funções, 10 ACL de função, 2 ACL de schema, 11 políticas). A 013 é aditiva (`01_diff_resumo.txt`).

## 5. Matriz de papéis usada

| Papel | Conexão | Usado para | Vale como evidência de isolamento do portal? |
|---|---|---|---|
| `nekkora_portal` | LOGIN real (`psql -U`, socket do container), **sem `SET ROLE`**. Todas as chamadas por **bind** (`\bind … \gset`) | Todos os testes de autorização, isolamento, contexto, sessão, allowlist, paginação e logs | **Sim**. Cada script afirma `current_user = session_user = nekkora_portal` |
| `nekkora_platform` | LOGIN real, sob RLS (`SET LOCAL nekkora.organization_id`) | Fixture de produto; revogações e suspensões (estado e concorrência) | n/a |
| `nekkora_provisioning` / `nekkora_ingestion` | LOGIN real | Organizações da fixture / eventos e logs pela barreira real | n/a |
| `nekkora` (superusuário de teste) | LOGIN | Catálogo, base de referência, bootstrap, ajuste de `last_seen_at` na preparação, verificação de estado, coordenador e observador de concorrência, controle positivo de log, mutações | **Não**. Declarado em cada script |
| `nekkora_portal_owner` | `SET ROLE` a partir do superusuário (é NOLOGIN) | **Só** `t05b_owner`: limite por coluna do owner | **Não**. Declarado |

## 6. Resultados por grupo (run oficial)

Arquivos relativos a `saidas/2026-09-26T044801Z_nekkora-portal-013/`. Toda asserção aborta o script (`ON_ERROR_STOP`) e o runner (exit ≠ 0) em caso de divergência.

| Grupo | Papel | O que foi verificado | Resultado | Evidência |
|---|---|---|---|---|
| **Instalação** | superusuário (entrypoint) | 001–013 aplicadas **em ordem**, iguais a `db/migrations`, sem ERROR/FATAL, em volume novo | Passou | `aplicacao_entrypoint.txt` |
| **Catálogo** | superusuário | Contrato exato: atributos, memberships, 7 configurações de papel, USAGE/CREATE, owners, `prosecdef`, volatilidade, `proconfig`, ACL e colunas de retorno das 6 funções; privilégios de coluna do owner por **diferença simétrica** com o desenho; 11 políticas; RLS nas 42 tabelas; sem OFFSET; sem EXECUTE do expurgo. Diff com 001–012. Invariantes da 012 §15 e da 013 §8 reexecutados | Passou | `t00_catalogo.txt`, `01_diff_*`, `01_invariantes.txt` |
| **Privilégio mínimo** | `nekkora_portal` | Entre as funções `SECURITY DEFINER`, só as 4 públicas do portal são executáveis (catálogo, t00); as `SECURITY INVOKER` antigas seguem executáveis por `PUBLIC` e não foram chamadas uma a uma (§9, item 5). SELECT, INSERT, UPDATE, DELETE e TRUNCATE negados (42501) nas **45 tabelas**; COPY negado; EXECUTE negado em 13 funções (2 internas e as 11 SECURITY DEFINER da 011/012, inclusive o expurgo); CREATE TABLE/FUNCTION/VIEW/SCHEMA negados; `SET ROLE` negado para 11 papéis; `SET SESSION AUTHORIZATION`, GRANT de papel, ALTER ROLE de atributos, CREATE ROLE e ALTER OWNER negados | Passou | `t05_privilegio.txt` |
| | owner (SET ROLE, declarado) | Owner **não lê** `payload`, `fingerprint`, `occurred_at`, `error_summary`, `exit_code`, `command_snapshot`, IDs de pessoa, `worker_id`, e-mail, nome, descrições, nome de organização ou cliente, `last_access_at`, `logs`, secrets, credenciais, agendamentos, Workers ou Agents; não escreve fora de `last_seen_at`; sem contexto, não vê nenhuma linha | Passou | `t05b_owner.txt` |
| **Isolamento** | `nekkora_portal` | Z (cliente C2) não vê nada de C1; W (O2) e K (O4) só veem o seu; organização alegada errada, inexistente ou NULL → NK401 nas 4 funções | Passou | `t01_isolamento_escopo.txt` |
| **Escopo por vínculo** | `nekkora_portal` | Mesmo cliente C1: X (só WS1) não lê WS2 por `p_workspace_id`, `p_execution_id`, eventos ou cursor de WS2 (0 linhas); Y é simétrico. ID inexistente e ID fora do escopo dão a mesma resposta (0 linhas). Repetido para outro cliente e outra organização | Passou | `t01_isolamento_escopo.txt` |
| **Troca de contexto** (gate) | `nekkora_portal` | 8 passos: `nekkora.organization_id` = O2/O1; `nekkora.fn_organization_id` = O2/O1/não-uuid; GUCs arbitrários de usuário, cliente, vínculo, workspace, concessão e sessão apontando para Y/C2/WS2 e para O2/W; SET LOCAL em transação. Em todos, as 4 funções devolvem md5 **idêntico** ao baseline e SELECT direto continua 42501. Y com GUCs de X continua vendo só WS2. `nekkora.organization_id` do chamador fica intacto | Passou; **as variantes vulneráveis m1a e m1b foram rejeitadas** (§7) | `t03_troca_contexto.txt`, `m1a_t03_funcao_final.txt`, `m1b_t03_funcao_final.txt` |
| **Estados** | `nekkora_portal` (+ superusuário para verificar) | NK401 com a mensagem fixa em **28 casos**: token ausente, curto, com 44 caracteres, caractere inválido, não canônico, **hash usado como credencial**, inexistente; sessão revogada, vencida em 12 h ou inativa (as 3 nas 4 funções); limite de 15 min + 5 s; conta interna, suspensa ou com e-mail não verificado; vínculo suspenso; cliente suspenso; organização suspensa; sessão inválida com `p_limit` inválido (NK401 antes de 22023). **0 linhas** nas 4 funções para concessão pendente, suspensa, revogada, expirada, antes de `starts_at` ou com revisão vencida, e para workspace inativo (WS4). Reativação da conta **não restaura** a concessão revogada: sessão antiga NK401, sessão nova com 0 linhas, trigger da 003 bloqueia reabrir | Passou | `t04a_*`, `t04b_*`, `t10a–c_*` |
| **Sessão e atividade** | `nekkora_portal` + superusuário | NK401 **sem alteração** em `last_seen_at` (8 sessões e as inativas de 16 min e 15 min + 5 s, conferidas com valor exato). Sessão vencida não é revivida; `expires_at = created_at + 12 h` em todas. Recurso fora do escopo: 0 linhas **com toque** (7 sessões, mais o limite de 14 min). 1 escrita por minuto: 61 s → escreve; 1,5 s depois → não escreve. Nunca retrocede (`last_seen_at` no futuro fica igual). 22023 não toca. `last_access_at` nulo em todas as concessões | Passou | `t04_verifica.txt`, `t04c_*`, `t10c_verifica.txt` |
| **Nenhum caso altera concessões** | superusuário | Impressão digital do **conteúdo de todas as 45 tabelas** (em `user_sessions`, sem `last_seen_at`) antes e depois de todo o bloco de chamadas do portal (t05 a t04c): **idêntica** | Passou | `s0_*`, `s1_*`, `s_diff_estado.txt` |
| **Concorrência** | `nekkora_portal` × `nekkora_platform` (+ observador) | **7a:** A lê 7 linhas numa transação aberta; B revoga a concessão e faz commit **sem esperar** (< 2 s); a 2ª leitura de A **na mesma transação** devolve 0; nova transação, 0. Ordem leitura 1 < commit de B < leitura 2 conferida. **7b:** a requisição validada trava a sessão; a revogação de B **espera** (> 3 s; observador vê B bloqueada por A); o commit de B vem depois do de A; a chamada seguinte dá NK401. **7b-reverso:** a revogação em curso bloqueia a chamada (> 3 s, observador), que depois dá NK401. **7c:** com a variante de pausa (corpo idêntico ao instalado + 1 linha, conferido no catálogo), o coordenador confirma o portal **parado depois da resolução** (esperando o advisory lock, com xid). B confirma a mudança; liberada, a leitura devolve **0** para conta, vínculo, cliente, organização, concessão e workspace; o **controle** sem mudança devolve 1. Cada sessão foi aguardada e verificada **separadamente** | Passou | `sessoes_concorrentes.txt`, `conc7*` |
| **Allowlist e paginação** | `nekkora_portal` | Linhas completas impressas (inclusive e13, com `error_summary`, `exit_code` e payload-marcador na base): nenhum marcador, e-mail ou argumento; eventos só com `(event_type, received_at)`; a varredura do runner não achou `MARCADOR_` nas saídas do portal. Keyset com limite 2: `[e15,e17] [e16,e14] [e13,e12] [e11] []`, empate de `created_at` atravessando a página sem perda nem repetição; mesmo cursor, mesma página. `p_limit` 1 e 100 aceitos; 0, −5, 101, NULL e cursor incompleto → 22023 com mensagem fixa. Filtros de status e período só restringem; eventos ordenados por `sequence` (com `received_at` invertido de propósito). Colunas exatas conferidas no catálogo. Sem OFFSET | Passou | `t06_allowlist_paginacao.txt`, `t00_catalogo.txt` |
| **Logs** | `nekkora_portal` + superusuário (controle) | As 7 configurações com `source = user`. SET, SET LOCAL, RESET, `set_config`, `ALTER ROLE … SET/RESET`, `ALTER ROLE … IN DATABASE` (que se sobreporia à global): **49 negativas** (42501). ALTER DATABASE, `session_preload_libraries` e `LOAD 'auto_explain'` negados. `PGOPTIONS -c` na conexão: **7 conexões recusadas** (FATAL). `SET log_parameter_max_length_on_error = -1` aceito; chamada por bind com token-marcador → NK401; marcador **ausente** do log do servidor. **Controle positivo** (superusuário, verbosidade default, `on_error = -1`): marcador **presente** no log | Passou; **mutação m4 detectada** | `t09*`, `t09_log_servidor.txt`, `t09_pgoptions.txt` |
| **Integridade** | `nekkora_portal` | `fn_organization_id` do chamador restaurado depois de sucesso, de NK401 capturado e de 22023 com savepoint; `nekkora.organization_id` intacto. Tabelas, funções (`sha256`, `encode`, `clock_timestamp`, `current_setting`), operador `=` e tipo `uuid` homônimos criados em `pg_temp` pelo portal não mudam o resultado. 22023 desfaz o toque (sem efeito parcial). A concessão revogada é imutável (trigger da 003); o owner não escreve fora de `last_seen_at` | Passou | `t08_robustez.txt`, `t04c_verifica.txt`, `t10a_*`, `t05b_owner.txt` |
| **Tokens** | runner | Nenhum dos 31 tokens de sessão aparece em saída alguma **nem no log do servidor**. Varredura heurística de strings com formato de token em todas as pastas `saidas/` (inclusive ensaios): só nomes de objetos e marcadores deliberados | Passou | `zz_tokens_fora_das_saidas.txt`, `99_log_servidor.log` |
| **Estado final** | superusuário | Catálogo final **byte a byte igual** ao da instalação (variantes e mutações removidas); invariantes reexecutados | Passou | `99_*` |

## 7. Testes negativos (variantes deliberadamente erradas)

Cada variante é derivada do corpo **instalado**, por substituição única conferida. Ela é aplicada **só na base de teste**, e o teste correspondente **precisou falhar** com a mensagem esperada. Em seguida a função original foi restaurada (impressão digital idêntica: `0e9cfa70…`) e o teste repetido **passou** com a função final (`mutacoes/resumo.txt`).

| Mutação | Defeito introduzido | Teste | Resultado |
|---|---|---|---|
| **m1a** (troca de contexto) | `portal_resolve_session` aceita a identidade do GUC `nekkora.user_id` (alternativa 1 rejeitada) | t03 | **Detectada**: `passo 6: list_workspaces identico ao baseline`. Repetição com a função final: passou |
| **m1b** | `portal_list_workspaces` usa o `fn_organization_id` do chamador | t03 | **Detectada**: `passo 3` → NK401. Repetição: passou |
| **m2** | Leitura sem reconferência de conta, vínculo, cliente e organização | 7c | **Detectada nos 4 casos**. Repetição dos 4 casos: passou |
| **m3** | Toque em `last_seen_at` antes da validação (revive a sessão inativa) | t04a | **Detectada**: sessão inativa há 16 min obteve `00000`. Repetição (t04a, t04b e verificação): passou |
| **m4** | Papel sem `log_error_verbosity = terse` | busca no log | **Detectada**: marcador no log do servidor. Repetição: ausente |

Observação sobre a m1a: a primeira ideia de variante, que confiava só em um GUC de **vínculo**, não vazaria nada. A reconferência exige `m.user_id` = usuário da sessão, e o resultado mudaria para vazio. Por isso a variante usada injeta a **identidade** (`nekkora.user_id`), que de fato amplia o escopo para o de Y.

## 8. Bugs encontrados e correções

| # | Onde | Bug | Correção e regressão |
|---|---|---|---|
| B1 | **Migration 013** (bloco de invariantes) | `has_sequence_privilege` foi avaliado sobre um índice antes do filtro `relkind = 'S'` (a ordem do AND não é garantida) → `"… is not a sequence"`. A migration abortou **inteira**, sem estado parcial (transação única) | Troca por `CASE WHEN relkind = 'S' …`. Stack de desenvolvimento recriada; 001–013 aplicadas limpas; o run oficial repete isso |
| B2 | Fixture preexistente (`portal-8-2`) | Várias execuções STARTING/RUNNING no mesmo Worker (`ux_executions_active_worker`, 006) | Execuções iniciadas e encerradas em sequência; só e12 fica RUNNING |
| B3–B12 | Harness (ensaios) | Falhas do **próprio harness**, nenhuma das funções: (a) `MSYS_NO_PATHCONV` global quebrava `git.exe`/`docker.exe` e o runner saía sem mensagem (2 ensaios interrompidos); (b) `pg_stat_activity` em cache dentro da transação do coordenador 7c; (c) corrida do controle 7c na sondagem, trocada por marcador em arquivo; (d) impressão digital incluindo a variante de pausa; (e) repetição da m3 sem o t04b; (f) regex de OFFSET casando com comentário; (g) regex `97` casando com microssegundos; (h) `COPY` dentro de PL/pgSQL; (i) `regrole` em view temporária; (j) `\bind` com `:'var'` mantendo aspas e valor com espaço virando dois argumentos | Corrigidas uma a uma e cobertas pelo ensaio completo (`2026-09-26T044517Z_…013ens`, OK) e pelo run oficial (`…T044801Z_…013`, OK). Os ensaios interrompidos ficam em `saidas/` como histórico: `…T043635Z`, `…T043648Z` (aborto do runner), `…T043722Z`, `…T043911Z`, `…T044135Z`, `…T044322Z` |

Nenhum defeito de segurança foi encontrado nas funções do portal. A única correção na migration foi a B1, do bloco de verificação.

## 9. Achados e observações

1. **`ALTER ROLE nekkora_portal RESET ALL` executado pelo próprio papel responde `00000`**, mas não remove as configurações restritas ao superusuário. O catálogo foi conferido depois (`t09b`). Não é vulnerabilidade, mas quem ler o log da aplicação pode achar que o reset funcionou.
2. **O cliente recebe o token no CONTEXT** quando o próprio papel liga `log_parameter_max_length_on_error` (`t09c`: 1 ocorrência na saída do cliente, 0 no log do servidor). Isso comprova no banco a obrigação separada do Spring (§10).
3. **Sombreamento de tipo em `pg_temp` afeta a consulta do próprio cliente:** um `pg_temp.uuid` criado na sessão muda o `::uuid` do SQL do cliente, porque no `search_path` padrão do cliente `pg_temp` é pesquisado primeiro para tipos e tabelas. As funções não são afetadas (`pg_catalog` primeiro). Relevante só se o processo do portal executar SQL de terceiros na mesma conexão, o que não deve fazer.
4. **Restauração no ramo `EXCEPTION`** das funções não é observável isoladamente de fora: o rollback da (sub)transação que recebe o erro também desfaz o `set_config`. A suíte prova o efeito observável; remover só esse ramo não seria detectado.
5. **Funções INVOKER de `public` executáveis por PUBLIC** (155, de 001–011, inclusive as de `pgcrypto`) continuam executáveis pelo portal. O resultado comprovado é mais estreito que "EXECUTE em apenas quatro funções": as 4 funções públicas do portal são as **únicas funções privilegiadas** (`SECURITY DEFINER`) acessíveis a ele (t00, t05), e o portal não tem acesso direto a tabela, view, coluna ou sequência (t05, inventário completo). Que as INVOKER não abrem acesso a tabela decorre disso por construção, porque rodam com os privilégios do chamador; elas **não** foram chamadas uma a uma pela suíte. Endurecer isso exigiria mudar contratos anteriores: fora do escopo, registrado como risco residual.
6. **Suíte da 012** deixa de passar neste checkout (V5). Na próxima revisão da 012, decidir se a contagem vira "≥ 12" ou se a suíte passa a montar só 001–012.

## 10. Não executado, pendências e riscos residuais

**Pendências da aplicação (Spring). O código não existe neste checkout; a suíte SQL não prova nada disto:**
- fixar `log_parameter_max_length_on_error = 0` na conexão do datasource do portal, **conferir na inicialização e não subir** se estiver diferente;
- registrar erros do datasource **só por SQLSTATE e classe**, sem texto da mensagem do servidor, sem cookie e sem token. Desligar a captura de parâmetros JDBC (proxy de SQL, OpenTelemetry) e o registro do cabeçalho `Cookie` nos logs HTTP;
- uma requisição = uma transação `READ COMMITTED` **de escrita** (não `readOnly`) = uma chamada; pool próprio com a credencial do `nekkora_portal`, fora do repositório (C1); `DISCARD ALL` na devolução (C5); nunca concatenar o token no SQL;
- tradução: NK401 → 401 genérico; 0 linhas → 404 no detalhe ou lista vazia; 22023 → 400; mapeamento de códigos para os rótulos do §11.5 (código desconhecido = erro interno); omissão de `workspace_id` quando o vínculo tem um só workspace;
- **Platform** (login e logout externos, D4): gerar o token em 32 bytes base64url e gravar o SHA-256 hex minúsculo **no mesmo formato**. Aqui a criação de sessão foi simulada por INSERT na fixture.

**Não executado nesta missão:**
- atualização de uma base **já populada** (001–012 com dados → 013): só a instalação limpa foi exercitada;
- desempenho: índice candidato do keyset (desenho §8, item 8) não incluído nem medido; serialização de requisições simultâneas da mesma sessão (§11.4) não medida;
- `auto_explain` não está instalado. Se for habilitado, exigir `auto_explain.log_parameter_max_length = 0`;
- limite de página definitivo (8.7): hoje é 100 provisório (V1);
- endurecimento de `user_sessions` por trigger e limpeza de sessões (C11) continuam fora.

**Riscos residuais aceitos no desenho:** um processo do portal comprometido lê os dados das sessões cujos tokens passam por ele, dentro das concessões válidas, e pode manter uma sessão roubada viva por toques até o prazo absoluto de 12 h.

## 11. O que o banco provou × o que depende da aplicação

| Provado no banco (papel real, stack isolada) | Depende da aplicação ou de operação |
|---|---|
| Entre as funções privilegiadas (`SECURITY DEFINER`), o portal só executa as 4 públicas do portal; nenhum acesso direto a tabela, view, coluna ou sequência; nenhuma elevação de papel. (As `SECURITY INVOKER` antigas continuam executáveis por `PUBLIC`, §9 item 5.) | Credencial do `nekkora_portal` fora do repositório; pool próprio; `DISCARD ALL` |
| Escopo = vínculo autenticado × workspace concedido e ativo, reconferido no comando de leitura; IDs, filtros e cursor só restringem | Rotas `/o/{organization_id}/…`, CSRF, cookie (8.7) |
| GUCs definidos pelo cliente não mudam o escopo; `nekkora.organization_id` intocado | O portal não define GUC (desenho §4.6) |
| NK401 e 22023 com mensagem fixa; sessão inválida sem toque; atividade válida com toque ≤ 1/min; nada além de `last_seen_at` é escrito | Tradução para 401/404/400 sem distinguir causa |
| Token fora do log **do servidor**, mesmo com o papel tentando reverter os parâmetros | Token fora do log **da aplicação**: parâmetro fixado e verificado na inicialização; log só por SQLSTATE |
| Revogação de sessão espera a requisição em curso; suspensão ou revogação confirmada antes da leitura zera o resultado | Revogar sessões na suspensão e na troca de senha (§11.4), feito pela Platform |

## 12. Critério de pronto da missão

| Critério | Situação |
|---|---|
| 1. Aplicação limpa de 001–013 | ✅ `aplicacao_entrypoint.txt` |
| 2. Todos os grupos críticos com os papéis reais | ✅ §6 |
| 3. Teste negativo de troca de contexto rejeita a variante vulnerável e passa na função final | ✅ m1a e m1b detectadas; `m1a_t03_funcao_final.txt` e `m1b_t03_funcao_final.txt` passaram |
| 4. Teste de log demonstra ausência do token no log do servidor | ✅ `t09_log_servidor.txt` (ausente no papel real, presente no controle positivo e na m4) |
| 5. Relatório separa cobertura executada de pendências | ✅ §6–§11 |

**Conclusão:** migration 013 implementada e validada nos cenários listados. Os critérios de banco que o desenho (§7, §10) põe para fechar a 8.2 foram atendidos; o fechamento formal e a aprovação da migration ficam com o responsável, depois da revisão. **O portal não está completo:** faltam as obrigações do Spring e da Platform (§10).

## 13. Limites desta entrega

- A 013 **não foi aplicada** na base principal. A stack principal e o volume principal não foram tocados (§3).
- **Sem commit e sem push.** Arquivos entregues, não rastreados:
  - `db/migrations/013_portal_externo.sql`;
  - `db/tests/portal-013/` (runner, README, 44 scripts SQL, 5 mutações, saídas);
  - este relatório.
- `db/tests/portal-8-2/` (preexistente) e a stack `nekkora-portal-dev` não foram alterados nem removidos. Decidir o destino deles fica com o responsável.

## 14. Decisão do responsável (2026-09-26)

- **8.2 fechada para a camada de banco:** a instalação limpa e os cenários previstos passaram com os papéis reais, inclusive o teste negativo de troca de contexto. A conclusão vale para a cobertura documentada; não declara cobertura exaustiva.
- **Migration não aprovada para merge ou produção:** o responsável recebeu o relatório, mas ainda não inspecionou a migration nem a suíte. O pacote de revisão está em `../relatorios/revisao-013-0a333db.zip` (fora do repositório).
- **Portal como produto incompleto.** Faltam:
  - código Spring do portal;
  - integração da Platform: proteção dos logs da aplicação, formato do hash gravado no login (D2) e reset explícito da conexão no pool (`DISCARD ALL`).
- **Próximos passos:**
  1. revisar a migration e a suíte;
  2. fechar 8.6 (abuso/MFA), 8.7 (contrato HTTP) e 8.10 (parâmetros do Argon2id) antes das rotas de autenticação;
  3. avançar com a implementação Spring Boot.
- **Continuam pendentes:** atualização de uma base populada para a 013 e limite definitivo de página (hoje 100, provisório).

## 15. Revisão direta (2026-09-26)

- O responsável revisou diretamente a migration 013, os testes e as saídas oficiais do pacote `revisao-013-0a333db.zip`. Não encontrou bloqueio de segurança no SQL dentro do escopo da 8.2. O hash da migration confere com o registrado (`c6192bff…14d9`).
- A revisão foi **estática**: a suíte não foi reexecutada no ambiente do revisor, que não tem Docker. A evidência de execução continua sendo o run oficial (§6).
- **8.2 encerrada para a camada de banco**, conforme o escopo e a cobertura documentados. O portal não está completo: continuam pendentes o Spring, a integração da Platform e as decisões 8.6, 8.7 e 8.10 (§14).
- **Precisão documental incorporada:** a redação do teste 5 (desenho §7), da §4, da §6, da §9 (item 5) e da §11 deste relatório, e do resumo da 8.2 no relatório da API, deixou de afirmar "EXECUTE em apenas quatro funções" em sentido absoluto. O comprovado é que as quatro funções públicas do portal são as únicas funções privilegiadas acessíveis a ele e que o portal não tem acesso direto às tabelas.
- Os comentários da migration (cabeçalho e bloco de invariantes) usam "EXECUTE em quatro funções SECURITY DEFINER" e "entre as funções SECURITY DEFINER, o portal executa exatamente as quatro". Não foram alterados, para preservar o hash revisado. Se forem ajustados, a migration muda de hash e precisa de nova revisão.

## 16. Adendo (2026-09-26, missão da API v1)

- **Harness:** a suíte passou a montar **só** 001–013, escolhidas explicitamente e copiadas para `saidas/<execução>/migracoes_montadas` (variável `TESTE_MIGRACOES`). A 014 e as posteriores não entram. As asserções não mudaram. A mesma mudança foi aplicada à suíte da 012 (só 001–012).
- **Reexecução oficial após a 014 existir no repositório:** projeto `nekkora-portal-013c`, porta 55516, rc=0, com as 13 migrations montadas; saídas em `db/tests/portal-013/saidas/2026-09-26T172102Z_nekkora-portal-013c/`. A migration 013 não mudou (`c6192bff…14d9`).
- **Atualização populada para a 013:** validada, junto com a 014, em `db/tests/atualizacao-populada` (pendência da §14).
- **Limite de página:** N = 100 passou a ser definitivo (registro de decisões 8.6/8.7/8.10, revisão 4).
- **Uso pelo portal Spring — alteração explícita do contrato (aceita pelo responsável em 2026-09-26; registro de decisões, revisão 4.1, §5.4):** uma requisição de execução utiliza uma transação `READ COMMITTED` de escrita, com **duas** chamadas às funções autorizadas da 013 (`portal_list_workspaces` e a função da rota). O motivo é omitir `workspaceId` quando o vínculo tem um só workspace concedido (desenho §11.5, decisão 5): as funções de execução não devolvem quantos workspaces o vínculo tem. Nenhuma função nova foi criada, e a 013 não mudou. Evidências:
  - mesma conexão e mesma transação: `PortalIT.executionRequestUsesOneConnectionForBothFunctionCalls`;
  - a função de dados mantém a própria autorização: cada função da 013 chama `portal_resolve_session` e `portal_authorized_workspaces` (linhas 294/311, 344/356, 380/390); execução de outro cliente continua 404;
  - toque no máximo 1/min: `PortalIT.activityTouchAtMostOncePerMinuteWithTwoFunctionCallsPerRequest`.
  Detalhes em `docs/relatorios/2026-09-26-validacao-api-v1.md`.
