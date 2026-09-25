# Validação — 012_security_rls.sql

**Data:** 2026-09-25
**Escopo:** implementação e validação da migration de segurança (papéis por serviço, privilégios mínimos, funções privilegiadas encapsuladas, provisionamento atômico, RLS por organização, contextos de sessão, retirada do papel substituído).
**Base aprovada:** `2026-09-25-contratos-funcoes-rls.md` (rev. 4), `2026-09-25-matriz-privilegios-001-011.md` (rev. 5), `2026-09-25-preparacao-seguranca-postgres.md`. A implementação partiu do contrato rev. 3 e da matriz rev. 4; as revisões seguintes registram as duas mudanças aprovadas durante ela (§3).
**Fora de escopo:** API, ativação do expurgo e requisitos de infraestrutura (TLS, `pg_hba`, senhas, backup).
**Status:** implementada e validada pelo autor nos cenários abaixo. **Ainda não revisada de forma independente nem encerrada.** Cópia canônica no repositório: `docs/relatorios/2026-09-25-validacao-012-security-rls.md`.

## 1. Ambiente e revisão

| Item | Valor |
|---|---|
| Repositório | `C:\Users\nicol\nekkora\nekkora-platform`, commit base `df6640d` (`main` = `origin/main` = `Nekkora-Operation/Nekkora-Platform-API`) |
| Arquivos | `db/migrations/012_security_rls.sql`; `db/bootstrap/001_organizacao_interna_nekkora.sql` (procedimento inicial, §6.2); `db/tests/seguranca-012/` (runner, 25 scripts, README, saídas); nota de escopo no README de `db/tests/regressao-renomeacao/`; este relatório em `docs/relatorios/`. Commit dedicado no branch `feat/012-security-rls` (§1.1) |
| Encerramento da regressão | Testes e relatório já na `main`, pelo responsável. As duas stacks descartáveis (`nekkora-regressao`, `nekkora-regressao-r2`) foram removidas depois de confirmar as 49 saídas em `origin/main`: container, volume e rede removidos um a um, pelo nome exato, sem `down -v` |
| Execuções da suíte (todas preservadas em `db/tests/seguranca-012/saidas/`) | **`2026-09-25T191906Z_nekkora-seg-012b`**: execução de referência, com o procedimento inicial de `db/bootstrap` (organização interna + staff + 5 limites). Stack `nekkora-seg-012b`, porta 55451. **`2026-09-25T184518Z_nekkora-seg-012`**: válida, anterior à criação do bootstrap (seed sem limites para a organização interna); resultados idênticos por arquivo. Stack `nekkora-seg-012`, porta 55450, mantida para revisão. **`2026-09-25T184124Z_nekkora-seg-012_INVALIDA-fixture-s02`**: **inválida**, preservada como evidência do defeito de fixture (§6.1). Todas com 001–012 pelo entrypoint, PostgreSQL 16.15, `cluster_name=nekkora-regressao-isolada` |
| Stack principal | Recriada do zero **depois** do commit da 012 (instalação limpa, §9). Antes disso, continuava em 001–011 |
| Ambiente de desenvolvimento | `nekkora-seg-dev` (porta 55440), usado para iterar e removido ao final. Nenhuma evidência deste relatório vem dele |

### 1.1 Commit dedicado

Branch `feat/012-security-rls`, a partir de `df6640d`. Conteúdo:
- a 012;
- o procedimento inicial;
- a suíte `db/tests/seguranca-012/` com **todas** as saídas, incluindo a execução `_INVALIDA-fixture-s02`, identificada no nome do diretório e no README;
- este relatório.

Nada foi enviado ao remoto. O hash deste commit fica registrado no §9, no commit seguinte, porque um commit não pode conter o próprio hash.

## 2. O que a 012 faz

1. **Privilégios padrão:** `ALTER DEFAULT PRIVILEGES REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC`, no nível **global**, para o papel que executa as migrations. A forma `IN SCHEMA public` não foi usada porque só acrescenta privilégios ao padrão global; usá-la para revogar seria um no-op silencioso. Catálogo: `pg_default_acl` = `{nekkora=X/nekkora}`, tipo `f`, sem schema.
2. **Papéis:**
   - `LOGIN`: `nekkora_platform`, `nekkora_ingestion`, `nekkora_scheduler`, `nekkora_provisioning`;
   - `NOLOGIN`: `nekkora_scheduler_owner`, `nekkora_provisioning_owner`;
   - para todos os `nekkora_*`, inclusive os da 011, atributos explícitos `NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS`;
   - papéis `LOGIN` criados **sem senha**, porque credenciais ficam fora do código.
3. **Tipo** `resource_limit_input (resource_code INTEGER, limit_mode SMALLINT, limit_value BIGINT)`.
4. **`search_path` administrativo:** `ALTER FUNCTION … SET search_path = pg_catalog, public, pg_temp` em todas as funções invoker plpgsql de `public` e em `get_effective_resource_limit`/`get_retention_policy_at`. Não reescreve nenhum corpo e aplica o "princípio geral" do contrato §3.1 a todo o conjunto, não só à lista nominal. Ficaram sem `search_path` fixo apenas as 5 funções SQL puras de validação (`is_valid_*`), que não leem tabelas.
5. **Bootstrap:** `enforce_org_role_assignment_bootstrap` passa a exigir `current_user = 'nekkora_provisioning_owner'` quando o responsável é nulo. É a única mudança de lógica, e já estava acordada.
6. **Funções existentes adaptadas:**
   - `assign_received_at_and_lock_barrier`: passa a `SECURITY DEFINER`, com owner `nekkora_retention_owner` e checagem do contexto **recebido** antes do contexto **interno**;
   - `close_usage_day` e `purge_expired_records`: corpo da 011 envolvido pelo save/restore de `nekkora.fn_organization_id`;
   - `process_schedule_occurrence(uuid, boolean)`: vira implementação interna, com owner `nekkora_scheduler_owner` e sem `EXECUTE` para nenhum papel `LOGIN`.
7. **Oito funções novas**, com as assinaturas exatas do contrato §2: `process_schedule_occurrence(uuid, uuid, boolean)`, `create_schedule_occurrence`, `set_schedule_status`, `provision_organization`, `provision_first_org_admin`, `provision_resource_limit`, `provision_retention_policy`, `reconsolidate_usage_day`. Todas são `SECURITY DEFINER`, com `search_path = pg_catalog, pg_temp`, referências qualificadas, owner `NOLOGIN` e `EXECUTE` só para o chamador previsto.
8. **Privilégios de tabela e coluna** conforme a matriz §13 (ver §3).
9. **RLS:** `ENABLE` + `FORCE` em **42** tabelas (inventário completo no §3.1).
   - **39 tabelas diretas** (toda tabela com `organization_id`), com duas políticas permissivas:
     - `tenant_request_context` (`nekkora_platform`, `nekkora_ingestion`): `organization_id = NULLIF(current_setting('nekkora.organization_id', true), '')::uuid`;
     - `tenant_function_context` (os três owners): o mesmo, sobre `nekkora.fn_organization_id`.
   - **3 tabelas derivadas** (`user_credentials`, `password_reset_tokens`, `email_verification_tokens`), só para `platform`, via a organização do usuário.
   - **Sem RLS:** `permissions`, `roles`, `role_permissions` (catálogo global).
   - A contagem de 39 é verificada dentro da migration, que aborta se o inventário divergir.
10. **Retirada:** `DROP OWNED BY nekkora_app` seguido de `DROP ROLE nekkora_app`.
11. **Invariantes verificados ao final da própria migration:**
    - nenhum `nekkora_*` privilegiado;
    - nenhum papel `LOGIN` dono de objeto;
    - nenhuma membership entre papéis;
    - todo `SECURITY DEFINER` com `search_path` fixo e sem `EXECUTE` público;
    - `CREATE` em `public` fora de `PUBLIC`;
    - `nekkora_app` inexistente.
12. **Atômica:** `BEGIN`/`COMMIT` em volta de tudo. Uma falha não deixa estado parcial.

## 3. Catálogo instalado × matriz aprovada

`00_catalogo_vs_matriz.sql` compara o esperado com o `aclexplode` do catálogo, por diferença simétrica nos dois sentidos.

| Verificação | Esperado | Instalado | Diferença |
|---|---|---|---|
| Privilégios de tabela (`papel`, `tabela`, `privilégio`) | 145 | 145 | **0 / 0** |
| Privilégios de coluna | 15 | 15 | **0 / 0** |
| Funções privilegiadas (owner, `SECURITY DEFINER`, quem executa) | 12 | 12 | **0 / 0** |
| Tabela com `organization_id` sem as duas políticas | 0 | 0 | — |
| `SECURITY DEFINER` com `EXECUTE` para `PUBLIC` | 0 | 0 | — |
| Papéis `nekkora_*` com super/bypassrls/createrole/createdb/replication | 0 | 0 | — |
| Memberships entre papéis | 0 | 0 | — |

**Onde a implementação completa ou interpreta a matriz** (todas refletidas no "esperado" acima):

| Ponto | Decisão | Motivo |
|---|---|---|
| `nekkora_provisioning_owner` recebe `SELECT` em `roles` | **Aprovado** (matriz atualizada, rev. 5) | Dependência concreta: `provision_first_org_admin` resolve `ORG_ADMIN` internamente (contrato §2) |
| `nekkora_provisioning_owner` **não** recebe `SELECT` em `organization_memberships` | Omitido | O vínculo à organização é garantido pela FK composta. A violação vira erro nomeado (`23503`, "vínculo não pertence à organização") |
| `nekkora_ingestion` com `SELECT` só em `organization_retention_policy_revisions` | Mantido o da 011 | A matriz §10 diz que a ingestão "continua" só com `SELECT`. Ela tinha só retenção, então não foi ampliada para limites |
| `nekkora_platform` com `SELECT` em `automation_schedule_occurrences` | Leitura sem escrita | A matriz exclui a tabela de ocorrências do CRUD do platform; leitura para telas |
| `nekkora_platform` sem acesso a `identity_provisioning_events` | Nenhum `GRANT` | Não listado na matriz; a escrita é exclusiva do provisionamento |
| `client_workspace_grants` S/I/U; `_reviews`/`_approvals` S/I | Interpretação de "sem mudança" | `_reviews`/`_approvals` têm `prevent_update`, e a matriz manda não conceder `UPDATE` onde ele existe |
| `set_schedule_status` confere `p_organization_id` contra o contexto de requisição do chamador | **Aprovado** (contrato atualizado, rev. 4) | Rejeita contexto ausente ou divergente **antes** de ler ou alterar qualquer agenda: a checagem vem logo depois da validação dos parâmetros, antes de estabelecer o contexto de função e antes do `SELECT … FOR UPDATE`. Verifica consistência de contexto; a autorização do usuário continua com a API. Testado no I12 |
| Inventário RLS tabela por tabela | **Registrado no §3.1** | Lista explícita das 45 tabelas, com classificação, políticas e papéis com privilégio. É a referência do contrato §6 (rev. 4) |

### 3.1 Inventário RLS tabela por tabela (catálogo instalado)

**Regras.**
- **Direta:** toda tabela de `public` com coluna `organization_id` (39). Recebe `ENABLE` + `FORCE ROW LEVEL SECURITY` e **duas** políticas permissivas `FOR ALL`, com `USING` idêntico ao `WITH CHECK`:
  - `tenant_request_context` — papéis `nekkora_platform`, `nekkora_ingestion`: `organization_id = NULLIF(current_setting('nekkora.organization_id', true), '')::uuid`;
  - `tenant_function_context` — papéis `nekkora_scheduler_owner`, `nekkora_provisioning_owner`, `nekkora_retention_owner`: `organization_id = NULLIF(current_setting('nekkora.fn_organization_id', true), '')::uuid`.
- **Derivada:** tabela sem `organization_id`, ligada a `users` por `user_id` (3). Recebe `ENABLE` + `FORCE` e **uma** política, `tenant_request_context`, só para `nekkora_platform`: `EXISTS (SELECT 1 FROM users u WHERE u.user_id = <tabela>.user_id AND u.organization_id = NULLIF(current_setting('nekkora.organization_id', true), '')::uuid)`, em `USING` e `WITH CHECK`. A subconsulta em `users` também passa pela RLS do chamador.
- **Catálogo global:** sem `organization_id` e sem ligação a tenant (3). Sem RLS.

**Como ler a coluna de papéis:** as políticas são uniformes e **não concedem acesso**. Elas só filtram o que o privilégio já permite. O acesso efetivo a cada tabela é a interseção do privilégio (coluna abaixo; `Ucol` = `UPDATE` só em colunas específicas, ver §3) com a política do grupo do papel. Uma política que alcança um papel sem privilégio na tabela (por exemplo, `tenant_request_context` para `nekkora_ingestion` em `workspaces`) não tem efeito.

| # | Tabela | Classe | Owner | Papéis com privilégio (S=SELECT, I=INSERT, U=UPDATE, D=DELETE) |
|---|---|---|---|---|
| 1 | `permissions` | catálogo | `nekkora` | platform S |
| 2 | `role_permissions` | catálogo | `nekkora` | platform S |
| 3 | `roles` | catálogo | `nekkora` | platform S; provisioning_owner S |
| 4 | `email_verification_tokens` | derivada | `nekkora` | platform SIU |
| 5 | `password_reset_tokens` | derivada | `nekkora` | platform SIU |
| 6 | `user_credentials` | derivada | `nekkora` | platform SIU |
| 7 | `agent_credentials` | direta | `nekkora` | platform SI + Ucol |
| 8 | `agents` | direta | `nekkora` | platform SIU |
| 9 | `automation_schedule_occurrences` | direta | `nekkora` | platform S; scheduler_owner SIU |
| 10 | `automation_schedule_revisions` | direta | `nekkora` | platform SI; scheduler_owner S |
| 11 | `automation_schedules` | direta | `nekkora` | platform SI + Ucol(`name`); scheduler_owner SU |
| 12 | `automation_version_bindings` | direta | `nekkora` | platform SIUD; scheduler_owner S |
| 13 | `automation_version_parameter_bindings` | direta | `nekkora` | platform SI + Ucol |
| 14 | `automation_version_secret_bindings` | direta | `nekkora` | platform SI + Ucol; scheduler_owner S |
| 15 | `automation_versions` | direta | `nekkora` | platform SIU; scheduler_owner S |
| 16 | `automations` | direta | `nekkora` | platform SIU; scheduler_owner S |
| 17 | `client_memberships` | direta | `nekkora` | platform SIU |
| 18 | `client_workspace_grant_approvals` | direta | `nekkora` | platform SI |
| 19 | `client_workspace_grant_reviews` | direta | `nekkora` | platform SI |
| 20 | `client_workspace_grants` | direta | `nekkora` | platform SIU |
| 21 | `clients` | direta | `nekkora` | platform SIU |
| 22 | `execution_events` | direta | `nekkora_retention_tables_owner` | ingestion SI; platform S; retention_owner S + Ucol(redação) |
| 23 | `executions` | direta | `nekkora` | ingestion S; platform SIU; scheduler_owner SI |
| 24 | `identity_invitations` | direta | `nekkora` | platform SIU |
| 25 | `identity_provisioning_events` | direta | `nekkora` | provisioning_owner I |
| 26 | `logs` | direta | `nekkora_retention_tables_owner` | ingestion SI; platform S; retention_owner S + Ucol(redação) |
| 27 | `organization_domains` | direta | `nekkora` | platform SIU |
| 28 | `organization_memberships` | direta | `nekkora` | platform SIU |
| 29 | `organization_purge_runs` | direta | `nekkora_retention_tables_owner` | retention_owner SI |
| 30 | `organization_resource_limit_revisions` | direta | `nekkora` | platform S; provisioning_owner SI; scheduler_owner S |
| 31 | `organization_retention_policy_revisions` | direta | `nekkora` | ingestion S; platform S; provisioning_owner SI; retention_owner S |
| 32 | `organization_role_assignments` | direta | `nekkora` | platform SIU; provisioning_owner SI |
| 33 | `organization_usage_daily` | direta | `nekkora_retention_tables_owner` | retention_owner SIU |
| 34 | `organizations` | direta | `nekkora` | platform SU; provisioning_owner SIU; scheduler_owner SU |
| 35 | `user_sessions` | direta | `nekkora` | platform SIU |
| 36 | `users` | direta | `nekkora` | platform SIU |
| 37 | `worker_agent_assignments` | direta | `nekkora` | ingestion S; platform SIU |
| 38 | `worker_workspace_assignments` | direta | `nekkora` | platform SIU |
| 39 | `workers` | direta | `nekkora` | platform SIU |
| 40 | `workspace_configuration_events` | direta | `nekkora` | platform SI |
| 41 | `workspace_parameter_values` | direta | `nekkora` | platform SI |
| 42 | `workspace_parameters` | direta | `nekkora` | platform SIU |
| 43 | `workspace_role_assignments` | direta | `nekkora` | platform SIU |
| 44 | `workspace_secrets` | direta | `nekkora` | platform SIU; scheduler_owner SU |
| 45 | `workspaces` | direta | `nekkora` | platform SIU; scheduler_owner S |

Totais: 39 diretas × 2 políticas + 3 derivadas × 1 = **81 políticas**; 42 tabelas com `ENABLE` + `FORCE`; 3 sem RLS. `nekkora_scheduler`, `nekkora_provisioning` e `nekkora_retention_executor` não aparecem na tabela: não têm privilégio de tabela, só `EXECUTE`.

**Invariante que protege essa separação:** políticas permissivas que se aplicam ao mesmo papel se combinam por OR. As duas políticas de cada tabela nunca se somam para um mesmo papel porque os grupos são disjuntos: nenhum papel de serviço é membro de um owner, nem o contrário. Uma membership entre eles daria ao papel de serviço também a política de contexto de função, e ele passaria a enxergar linhas por um GUC que ele mesmo pode definir. A 012 verifica "nenhuma membership envolvendo `nekkora_*`" no próprio bloco final e aborta se encontrar; a comparação de catálogo (`00`) reconfere. Qualquer `GRANT <owner> TO <serviço>` futuro quebra esse invariante e precisa ser tratado como mudança de segurança.

**Pontos para a revisão do conteúdo:**
- `organizations` usa a própria chave (`organization_id`) na política. O `platform` só vê e altera a linha da organização do contexto.
- As 4 tabelas cujo owner é `nekkora_retention_tables_owner` (`NOLOGIN`) sofrem as políticas por causa do `FORCE`. Esse papel não está em nenhuma política, então, se um dia agisse, não veria nada.
- `identity_provisioning_events` é tenant direta, mas só o `provisioning_owner` tem privilégio, e só `INSERT`.
- `users` está sob RLS. A resolução do tenant no login (contrato §5.1) acontece **antes** de qualquer consulta a `users`, pelo backend; nenhuma política abre exceção para isso.

## 4. Resultados por cenário (execução válida)

Números citados abaixo: execução de referência `…T191906Z_nekkora-seg-012b`, idêntica por arquivo à `…T184518Z_nekkora-seg-012`. Os horários de concorrência do §5 são da `…T184518Z`.

Os papéis conectam diretamente (`psql -U <papel>`). "Admin" = `nekkora`, usado só para seed, fixture histórica e leitura de catálogo/estado.

### 4.1 Caminhos permitidos

| ID | Papel | Cenário | Resultado |
|---|---|---|---|
| 01 | `nekkora` (executor administrativo) | Procedimento inicial `db/bootstrap/001_organizacao_interna_nekkora.sql`: sem variáveis; real; repetição | ✅ Sem variáveis: rejeitado (exit 3). Real: organização interna + 1 staff + 5 limites, numa transação. Repetição: **recusada** (`a base ja tem organizacoes`) |
| P04 | provisioning | `provision_organization` para A e B, 5 limites explícitos cada (ordem dos elementos irrelevante) | ✅ Organizações e 5 revisões cada, com `revision_number`/`effective_from` atribuídos pelo banco. B **sem** política de retenção implícita |
| P06 | provisioning | Limite avulso (revisão 2) e retenção (eventos FINITE 30, logs INDEFINITE) | ✅ |
| 04 | platform | Setup completo em A e em B sob RLS: identidade, credencial, workspace, parâmetro/secret (auditoria 008 via `nekkora.actor_membership_id`), Worker (capacidade 010), Agent, associações, automação publicada, execução criada → reservada (locks em 7 tabelas) → RUNNING, agendamento + revisão + `UPDATE (name)` | ✅ Cada contexto vê só a própria organização (1 org, 1 usuário, 1 credencial, 1 workspace, 1 execução, 2 eventos de auditoria) |
| B04 | provisioning | `provision_first_org_admin` A e B | ✅ Uma atribuição com responsável nulo e um evento por organização (05c) |
| G01 | ingestion | Eventos e logs em A e B, cada um no próprio contexto | ✅ `received_at` do servidor; linhas-barreira criadas pela barreira encapsulada |
| S02 | scheduler | `create_schedule_occurrence` duas vezes, mesma identidade | ✅ Mesmo `occurrence_id` |
| S05 | scheduler | `process_schedule_occurrence(A, occ, true)` e reprocessamento | ✅ `(2, NULL, exec)` nas duas chamadas; a cadeia de triggers (materialização, imutabilidade, pré-requisitos, finalização) roda como owner sob RLS |
| 09 | scheduler → platform → scheduler → platform | Ocorrência pendente → `set_schedule_status(…, 2)` → processar → retomar | ✅ Pendente fechada como `3/3` pela trigger dentro da função; processamento devolve `(3, 3, NULL)`; retomada não a ressuscita (`resumed_at` atribuído) |
| R01 | executor | `reconsolidate_usage_day` A e B, mesmo dia | ✅ A: 2/71, B: 2/71, A logs: 1/41. Cada total conta só a organização do parâmetro |
| 11b | executor | `close_usage_day` A e B num dia passado com registros das **duas** | ✅ A **3/135**, B **2/70**, iguais aos persistidos. Sem zerar nem misturar (risco da preparação §4.1) |

### 4.2 Operações proibidas (mecanismo registrado)

| Papel | Tentativa | Mecanismo |
|---|---|---|
| provisioning | `INSERT`/`SELECT` em `organizations`, `INSERT` em limites; `set_schedule_status`, `close_usage_day` | Privilégio (tabela/função) |
| platform | `INSERT organizations`; `UPDATE automation_schedules.status`; `INSERT` em ocorrências; qualquer acesso a `organization_usage_daily`; `DELETE executions`; `INSERT execution_events`; `create_`/`process_schedule_occurrence` (3 e 2 parâmetros); `reconsolidate`; barreira; `provision_*`; `CREATE TABLE public.*` | Privilégio (tabela, coluna, função, schema) |
| platform | `INSERT` direto em `organization_role_assignments` com responsável nulo (B06) | **Trigger** (checagem de `current_user`), com o `INSERT` concedido |
| platform | Ler `identity_provisioning_events` | Privilégio |
| ingestion | `UPDATE`/`DELETE`/`TRUNCATE` em eventos e logs; qualquer acesso a `organization_usage_daily`; `workspaces`; `reconsolidate`; `close_usage_day` | Privilégio |
| scheduler | Ler ocorrências/execuções; implementação de 2 parâmetros; `set_schedule_status` | Privilégio |
| executor | `purge_expired_records`; ler `organization_usage_daily`/eventos; barreira; `provision_*` | Privilégio |
| `nekkora_app` | Login | `role "nekkora_app" does not exist` |

### 4.3 Organização divergente

| ID | Papel | Cenário | Resultado |
|---|---|---|---|
| I01/I02 | platform, contexto A | Ler ou atualizar linhas de B (workspace, usuários, credencial) | 0 linhas; `UPDATE 0` sem erro (`USING` filtra) |
| I03/I04 | platform, contexto A | `INSERT` com `organization_id` de B; `UPDATE` movendo linha de A para B | Rejeitado por `WITH CHECK` (`new row violates row-level security policy`) |
| I12 | platform, contexto A | `set_schedule_status(B, agenda_B)`; `set_schedule_status(A, agenda_B)` | Erro nomeado 42501 (contexto divergente); "inexistente ou de outra organização"; agenda de B intacta |
| G03 | ingestion, contexto A | Evento declarado da execução de B | Rejeitado. A trigger de origem não enxerga a execução sob RLS (ver §6.3 sobre a mensagem) |
| G04 | ingestion, contexto A | Barreira chamada direto com organização B | Erro nomeado 42501 |
| S03/S06 | scheduler | Agenda de B com workspace de A e vice-versa; processar a ocorrência de A informando B | Erros nomeados ("não pertence ao workspace", "inexistente ou de outra organização") |
| B01 | provisioning | Bootstrap de A com vínculo de B | Erro nomeado 23503 |
| 10/R01 | executor | Reconsolidar A com eventos de B no mesmo dia | Conta só A |

### 4.4 Contexto ausente, malformado e reutilização de conexão

| ID | Cenário | Resultado |
|---|---|---|
| I05 | platform sem `SET LOCAL` | 0 linhas em todas as tabelas; `INSERT` rejeitado por `WITH CHECK` |
| I06 | Contexto `''` | 0 linhas |
| I07 | Contexto `'nao-e-um-uuid'` | Erro de conversão `invalid input syntax for type uuid`; nada retornado |
| G02 | ingestion sem contexto | Rejeitado (trigger de origem sob RLS) |
| G04 | Barreira sem contexto | Erro nomeado (`contexto=<ausente>`) |
| I12 | `set_schedule_status` sem contexto | Erro nomeado |
| I08 | **Mesma conexão** (PID constante): `SET LOCAL A` → commit → txn sem `SET` → `SET LOCAL B` → rollback → txn sem `SET` | 1 → **0** → 1 (só B) → **0**. O contexto não sobrevive ao fim da transação |
| I09 | **Uso indevido (informativo):** `SET` de sessão, sem `LOCAL` | **Vaza** para a transação seguinte (1 linha) até `RESET`. O banco não distingue `SET` de `SET LOCAL`: a disciplina é do backend/pool (§7) |
| I10 | platform define `nekkora.fn_organization_id` | Não tem efeito: a política do platform só lê `nekkora.organization_id` |
| 11c | **Informativo:** owner sem contexto de função (via `SET ROLE` do superusuário) | Vê **0** eventos de um dia que tem 5, sem erro; com o contexto, vê 3. É o risco que o save/set de `fn_organization_id` em toda função evita |

### 4.5 Contexto de função: restauração, aninhamento e guard

| ID | Cenário | Resultado |
|---|---|---|
| G01/S02/S05/I12/P07 | Valor de `nekkora.fn_organization_id` depois de cada chamada, partindo de vazio | Vazio, restaurado |
| G05 | ingestion: valor anterior **não vazio** (`1111…5555`), inserção com erro dentro de savepoint, depois inserção com sucesso | Depois do erro: `1111…5555`; depois do sucesso: `1111…5555`. A barreira salvou e devolveu exatamente o valor anterior |
| R04 | executor: valor anterior não vazio, `close_usage_day` com sucesso, depois `close_usage_day` com erro (dia fechado) em savepoint | Os dois devolvem `1111…5555` |
| R02 | Mesma transação: `reconsolidate(A, eventos)` → `close_usage_day(A, eventos)` → `reconsolidate(A, logs)` → `reconsolidate(B, eventos)` | `fn_organization_id` restaurado a cada chamada; `nekkora.retention_txn_category` **permanece** `A:1`; mesmo par permitido; troca de categoria e troca de organização rejeitadas. Prova, e não só supõe, que os dois GUCs são independentes (contrato §7) |
| R03 | Mesma conexão (PID constante), transação seguinte com outro par | Guard fora de transação = `''`; nova chamada aceita |

**Precisão sobre a restauração no `EXCEPTION`:** GUCs no PostgreSQL são transacionais. Quando o erro sai da função, a (sub)transação que o recebe é desfeita, e isso **também** reverte o `set_config` feito dentro da função. A restauração explícita no bloco `EXCEPTION`, exigida pelo contrato, está implementada em todas as funções. Porém, de fora da função não há como observar a diferença entre "o handler restaurou" e "o rollback do savepoint restaurou". O que os testes comprovam é o **comportamento observável**: depois do erro, o valor é o anterior. O caminho de **sucesso** com valor anterior não vazio (G05, R04) é o que prova de fato o save/restore por função.

### 4.6 Atomicidade

| ID | Cenário | Resultado |
|---|---|---|
| P02 | 9 arrays inválidos: 4 elementos, código repetido, FINITE nulo/negativo, UNLIMITED com valor, modo inválido, código nulo, array nulo, bidimensional | Todos rejeitados **antes de qualquer `INSERT`**, com mensagem que aponta o elemento |
| P03 | Responsável inexistente: a organização é inserida e a 1ª revisão falha na FK | Organização C **não existe** depois (03) |
| P05 | Organização duplicada | Rejeitada (PK) |
| B02 | Bootstrap com evento inválido: a atribuição é inserida e o evento falha | Atribuição desfeita. B04 seguinte aceito; só uma atribuição por organização (05c) |
| 012 | A própria migration | Transação única; na primeira aplicação em desenvolvimento, a trava de inventário (38 ≠ 39) abortou e **nada** ficou aplicado |

### 4.7 Desvio por objetos do chamador

| ID | Cenário | Resultado |
|---|---|---|
| I11/I13 | platform cria `TEMP TABLE organizations` com A como `CLOSED` (status 3) e depois cria uma execução | A consulta não qualificada **da própria sessão** lê a tabela temporária (status 3). A trigger `enforce_execution_creation_prerequisites` lê `public.organizations` (ACTIVE) e a execução é criada. O `search_path` fixo com `pg_temp` por último impede o desvio |
| I11 | `CREATE TABLE public.intrusa` | `permission denied for schema public` |

## 5. Concorrência (sessões reais, `clock_timestamp()` no servidor)

### 5.1 Materialização concorrente da mesma identidade (contrato §4)

| Instante (UTC) | Sessão | Evento |
|---|---|---|
| 18:45:32.4968 | A (scheduler, PID 252) | Início; `BEGIN` |
| 18:45:32.5045 | A | `create_schedule_occurrence` → `14edf7a0…`; transação aberta (`pg_sleep(8)`) |
| 18:45:35.5241 | B (scheduler, PID 266) | Início; mesma identidade |
| 18:45:37.5436 | Observador | B em `Lock/transactionid`, `pg_blocking_pids(B) = {252}` |
| 18:45:40.5125 → .5137 | A | Commit |
| 18:45:40.5152 | B | Retorna **`14edf7a0…`**, o mesmo id, 1,5 ms após o commit |

Verificação: **uma** linha para `(schedule, scheduled_for)`.

### 5.2 Reconsolidação × inserção ainda não confirmada

| Instante (UTC) | Sessão | Evento |
|---|---|---|
| 18:45:40.9894 | A (ingestion, PID 294) | Início |
| 18:45:41.0009 | A | Evento inserido **sem commit** (a barreira segura `FOR SHARE` na linha de hoje) |
| 18:45:44.0086 | B (executor, PID 302) | `reconsolidate_usage_day(A, 5, hoje)` |
| 18:45:47.0825 | Observador | B em `Lock/transactionid`, bloqueada por `{294}` |
| 18:45:51.0019 → .0036 | A | Commit |
| 18:45:51.0052 | B | `consolidated_at`; resultado **3/135**; conclui às 51.0060 (2,4 ms após o commit) |

Verificação: agregado 3/135 = persistidos 3/135. O total inclui o evento que **não estava confirmado** quando a reconsolidação começou.

**Alcance:** isto demonstra o protocolo `FOR UPDATE` × `FOR SHARE` com cálculo depois do lock, pelo caminho real (ingestão por `nekkora_ingestion` através da barreira encapsulada, sem fixture nem trigger desabilitada), **somente para a reconsolidação**. **Não encerra o teste do fechamento.** Para `close_usage_day`, a "inclusão de uma inserção ainda não confirmada quando o fechamento começa" continua pendente. Ela **pode** ser testada com uma fixture controlada numa base isolada: uma transação de ingestão que segura o lock da barreira de um dia já passado e insere um registro desse dia sem confirmar, enquanto o executor chama o fechamento, **sem** esperar a meia-noite. A **retentativa na virada real do dia** (`assign_received_at_and_lock_barrier` relendo o dia depois de obter o lock) é outro cenário, temporal, e também segue pendente.

## 6. Correções, achados e limitações

### 6.1 Falhas encontradas durante a validação

1. **Inventário RLS (38 × 39):** estimativa minha errada. A trava da migration abortou a primeira aplicação e nada ficou aplicado. Corrigida a constante.
2. **Primeira execução da suíte inválida** (`saidas/…T184124Z…_INVALIDA-fixture-s02/`, mantida como evidência):
   - O `scheduled_for` truncado ao segundo caiu antes do `resumed_at` da retomada feita no mesmo segundo. A trigger rejeitou corretamente, mas `occ1` ficou indefinido, S05/S06 falharam por **erro de sintaxe** e o runner não detectou.
   - No 09b, a "materialização com agenda suspensa" coincidiu com a identidade da ocorrência existente e devolveu o id dela.
   - Correções: timestamps sem truncar; `ON_ERROR_STOP` nos passos positivos; o runner agora falha diante de erro de sintaxe ou variável não substituída (testado contra a execução inválida: teria abortado).
   - **Nenhuma correção na 012 foi necessária por causa disso.** Os defeitos eram de fixture e de runner.
3. **Comportamento observado e mantido:** `create_schedule_occurrence` devolve o id existente para uma identidade já materializada **mesmo com a agenda suspensa**, porque a reconsulta idempotente (contrato §4, passo 4) vem antes da regra da trigger. Isso é coerente com o contrato; nenhuma ocorrência nova é criada.

### 6.2 Procedimento inicial (bootstrap) — registrado

A organização interna da Nekkora e seu primeiro usuário staff **não podem** nascer por `provision_organization`: `provisioned_by_user_id` exige um usuário existente, e todo usuário exige uma organização.

**Procedimento** (`db/bootstrap/001_organizacao_interna_nekkora.sql`, fora de `db/migrations`, portanto nunca executado automaticamente pelo entrypoint):
1. Executado **uma vez**, pelo executor administrativo (superusuário de migrations), numa **única transação**:
   - organização interna (`status` ACTIVE);
   - primeiro usuário staff (`INTERNAL`, `ACTIVE`);
   - **cinco revisões de limite explícitas**, com `provisioned_by_user_id` = esse staff.
2. Todos os valores são parâmetros obrigatórios (`-v`): ids, nome, e-mail e os cinco limites como `resource_limit_input[]`. Não há valor padrão. Os limites passam pelas mesmas regras de `provision_organization`: 5 elementos, códigos `{1..5}` sem repetição, `FINITE ≥ 0`, `UNLIMITED` vazio.
3. **Recusa** executar se não for o executor administrativo ou se já existir qualquer organização. É estritamente o primeiro passo de uma base nova.
4. Nenhuma política de retenção é criada: ausência de política = expurgo não autorizado (010).
5. **Depois dele, somente os caminhos aprovados:** `provision_organization`, `provision_first_org_admin`, `provision_resource_limit`, `provision_retention_policy` (`nekkora_provisioning`) e os fluxos dos demais papéis de serviço.

Validado na suíte (execução `012b`, §4.1 linha 01) e na recriação da base principal (§9).

### 6.3 Mensagem imprecisa sob RLS (sem mudança de lógica)

Com contexto ausente ou divergente, a trigger de origem da 011 não enxerga a execução e responde `execucao sem alocacao nao pode receber evento`. Rejeitar é o comportamento correto (fail-closed), mas a mensagem induz a erro de diagnóstico. Não alterei, para não mexer na lógica da 011. Fica como sugestão para a API ou uma evolução futura.

### 6.4 Limites que continuam valendo

- **RLS não se aplica ao superusuário** (papel de migration). Nenhum papel de serviço é superusuário, `BYPASSRLS` ou dono de tabela (invariante verificado).
- **`SET` sem `LOCAL`** vaza entre transações na mesma conexão (I09). O banco não impede isso; o backend precisa usar `SET LOCAL` e o pool deve limpar a sessão (`DISCARD ALL`/`RESET ALL`) ao devolver a conexão.
- **O contexto não é autenticação:** RLS filtra por um valor que a API já validou (preparação §4).
- **`TEMP` em `database` continua com `PUBLIC`.** O desvio foi neutralizado pelo `search_path` fixo, não pela ausência do privilégio. Revogar `TEMP` é um endurecimento opcional, não aplicado.
- **As 5 funções `is_valid_*`** e as funções da extensão pgcrypto seguem com `EXECUTE` para `PUBLIC` e sem `search_path` fixo. São invoker, não leem tabelas e são usadas em `CHECK`.

## 7. Pendências

1. **Atualização de uma base já populada (001–011 com dados) para a 012: não exercitada.** A base principal foi **recriada** (instalação limpa, §9), por decisão do responsável, porque os dados antigos foram montados fora do fluxo de provisionamento. Ninguém testou aplicar a 012 sobre dados pré-existentes. Nesse cenário, por exemplo, uma organização sem as cinco revisões de limite ficaria fail-closed, e o `nekkora_app` precisaria estar ocioso no momento do `DROP ROLE`.
2. **Inclusão de uma inserção ainda não confirmada no fechamento (`close_usage_day`):** aberta. Testável com fixture controlada, sem esperar a meia-noite (§5.2).
3. **Retentativa na virada real do dia:** aberta, cenário temporal distinto (§5.2).
3. **Infraestrutura** (preparação §6): senhas/rotação, `pg_hba` `hostssl` + `scram-sha-256`, `statement_timeout`/`idle_in_transaction_session_timeout` por papel, identidade de backup. Nenhum item aplicado.
4. **Ativação do expurgo:** continua sem `EXECUTE` para nenhum papel.
5. **Suíte da regressão da renomeação:** encerrada com a 012 (nota no README). O `run.sh` dela aborta ao encontrar 12 migrations.
6. **Revisão independente da 012 e deste relatório:** pendente. A stack `nekkora-seg-012` fica no ar até ela terminar; `nekkora-seg-012b` também.

## 8. Conclusão

Nos cenários executados, a 012 aplica sem erro sobre 001–011, e o catálogo instalado coincide com a matriz aprovada: 145 privilégios de tabela, 15 de coluna e 12 funções, com diferença zero nos dois sentidos, mais os acréscimos e interpretações registrados no §3. Cada serviço executa o próprio fluxo com o próprio papel. Operações não concedidas, organização divergente e contexto ausente ou malformado são rejeitados, com o mecanismo identificado. O contexto de requisição, **quando definido por `SET LOCAL`**, não sobrevive entre transações da mesma conexão. Um `SET` de sessão vaza para a transação seguinte (I09); evitar isso cabe ao backend e ao pool. O contexto de função é salvo e restaurado sem afetar o guard de categoria. Provisionamento e bootstrap são atômicos. As duas corridas testadas resolvem pelo lock, com resultado correto. O expurgo continua desativado.

Do ponto de vista do autor, a 012 está **apta para revisão independente**, com as pendências do §7. Ela ainda **não** está revisada nem encerrada. Esta validação **não** declara cobertura exaustiva nem conformidade LGPD, e não substitui os requisitos de infraestrutura. RLS está validada nos cenários acima, que usam papéis reais e políticas de fato habilitadas; não é uma prova de isolamento para toda consulta futura da API.

## 9. Recriação da base principal (instalação limpa)

Executada depois do commit dedicado **`44a4d44`** (branch `feat/012-security-rls`), por decisão do responsável: a base local é descartável, e os dados antigos tinham sido montados fora do fluxo de provisionamento. Evidências em `db/tests/seguranca-012/instalacao-limpa/saidas/`.

**Identificação antes de agir** (`01_recriacao.txt`): projeto Compose `nekkora-platform`, container `nekkora-postgres` (`3fa66b5f…`), volume `nekkora-platform_nekkora_postgres_data` (criado 17:08:56Z), usado **somente** por esse container. Estado antigo: 001–011, `nekkora_app` presente, 2 organizações.

**Recriação:** `docker rm -f nekkora-postgres`, `docker volume rm nekkora-platform_nekkora_postgres_data` e `docker compose up -d` a partir do repositório. Nada além desses dois recursos foi removido: a rede do projeto e as stacks `nekkora-seg-012`/`012b` ficaram intactas, e não houve `down -v`. Resultado: container `3c612d04…`, mesmo projeto e mesmo nome de volume (recriado às 21:03:29Z), bind das migrations na pasta atual, healthy (`accepting connections`), **001–012 aplicadas pelo entrypoint sem erro**.

**Procedimento inicial** (`02_ids.txt`, `03_bootstrap.txt`): `db/bootstrap/001_organizacao_interna_nekkora.sql` pelo executor administrativo, com ids novos:
- organização interna `df125fb7-4f94-460e-b14c-9e494d05cf4f` ("Nekkora");
- staff `f03286af-1506-4cb4-9d00-6cace2c2ea44` (`staff@nekkora.local`);
- limites `(1,FINITE,0)`, `(2,FINITE,0)`, `(3,FINITE,0)`, `(4,UNLIMITED)`, `(5,UNLIMITED)`.

Uma transação, exit 0: 1 organização, 1 usuário, 5 limites.

**Verificação curta com os papéis reais** (`verifica.sh`; saídas `05`–`09`):

| Verificação | Papel | Resultado |
|---|---|---|
| Catálogo × matriz (mesmo script da suíte) | `nekkora` | 7 diferenças, todas vazias |
| Acesso direto a `organizations` | provisioning | Negado (privilégio) |
| `provision_organization` com 5 limites | provisioning | Organização de verificação `15c4541c-…` criada com 5 limites, sem política de retenção |
| Array com 4 elementos | provisioning | Rejeitado antes de qualquer `INSERT` |
| Fluxo permitido no contexto de V: usuário, vínculo, credencial, workspace | platform | Aceito; o contexto vê só V (1/1/1/1) |
| Contexto da organização interna | platform | Vê só a interna; workspace e credencial de V invisíveis; `UPDATE` em V → 0 linhas; `INSERT` em V → `WITH CHECK` |
| Sem contexto, mesma conexão (PID 221) | platform | Contexto vazio, 0 linhas; `INSERT` → `WITH CHECK` |
| `INSERT organizations`; `provision_first_org_admin` | platform | Negados (privilégio) |
| Bootstrap de ORG_ADMIN com responsável nulo, direto | platform | Rejeitado pela trigger (`current_user=nekkora_platform`) |
| `provision_first_org_admin(V)`; repetição | provisioning | Atribuído (1 atribuição + 1 evento); repetição recusada |
| Estado final | `nekkora` | 2 organizações; `nekkora_app` ausente; 42 tabelas com RLS `ENABLE`+`FORCE`; 81 políticas; triggers 131/131 |

**Alcance:** isto valida a **instalação limpa** da 012 na base principal. **Não** comprova a atualização de uma base já populada (§7, item 1): esse cenário **não foi exercitado**. A base principal agora contém a organização interna e a organização de verificação criadas acima.
