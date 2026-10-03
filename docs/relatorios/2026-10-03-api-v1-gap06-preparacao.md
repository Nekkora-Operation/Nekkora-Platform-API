# GAP-06: preparação da implementação (leitura interna de workspaces, automações e execuções)

**ESTADO: PREPARAÇÃO PARA REVISÃO, REVISÃO 1.** A §4 detalha a medição conforme os pontos de conferência do parecer de 2026-10-03: SQL e parâmetros equivalentes, papel real, RLS e contexto organizacional, custo da autorização e registro para reprodução. As referências de tempo não são critério de aceite.
- Não há código, teste, migration nem commit. A implementação começa depois do aceite desta preparação.
- Os commits das GAP-01, GAP-02 e GAP-03 continuam etapas separadas.

**Base:**
- contrato GAP-02/03/06, revisão 2: §1, §4 (ROTA-L01 a L08), §5 e §6;
- GAP-02 e GAP-03 **concluídas no escopo contratado**. Os componentes reutilizados são `WorkspaceAccess` (com `visibleWorkspacesQuery`, `hasWorkspacePermission`, `requireWorkspace` e a checagem de consistência), `CursorCodec`, `Paging` e `Labels`;
- **decisões da responsável**: DC-01, DC-02, DC-03 e DC-15; DC-04 a DC-06; e, em 2026-10-03, em resposta à pergunta do executor:
  - **DC-07:** medir com volume sintético antes de fechar;
  - **DC-08:** `command_json`, `command_snapshot` e `payload` fora;
  - **DC-09:** solicitante incluído, criador adiado;
  - **DC-10:** `errorSummary` adiado;
  - **DC-11:** Worker/Agent fora;
  - **DC-12:** eventos sem acesso → 404;
  - **DC-13:** logs fora;
- só o **DC-14** (caminho definitivo do OpenAPI) continua pendente; não bloqueia a GAP-06;
- leitura do esquema em HEAD `2431648f`.

## 1. Fatos do esquema usados

| Fato | Fonte | Uso |
|---|---|---|
| `workspaces` (`name`, `description`, `status` 1–3, `client_id` anulável, `created_at`, `updated_at`) | 001 | L01, L02 |
| `automations` (`workspace_id`, `name`, `description`, `status` 1–3, `created_by_membership_id`, datas); `UNIQUE (organization_id, workspace_id, automation_id)` | 005 | L03, L04. O criador **não** é exposto (DC-09) |
| `automation_versions` (`version_number` > 0, único por automação; `status` 1 = rascunho, 2 = publicada, transição terminal; `published_at` ⇔ publicada; `command_json`) | 005 | L05 e `latestPublishedVersion` = maior `version_number` com `status = 2`. `command_json` **não** é exposto (DC-08) |
| `executions` (`status` 1–6, `status_reason` 1–6, `created_at`, `started_at`, `finished_at`, `cancel_requested_at`, `retry_of_execution_id`, `requested_by_membership_id`, `command_snapshot`, `error_summary`, `pid`, `exit_code`, `worker_*`) | 006 | L06, L07. **Não** saem: `command_snapshot`, `request_key`, `request_fingerprint`, `error_summary`, `pid`, `exit_code`, `worker_*`, `dispatched_at`, `reserved_at`, `authorized_by_membership_id`, `schedule_occurrence_id` (DC-08, DC-10, DC-11) |
| `executions.origin` (1 = manual, 2 = agendada) e `requested_by_membership_id` **anulável** só na agendada | 009 (evolução aditiva da 006) | `origin` e `requestedBy` (DC-09): nulo na agendada |
| `execution_events` (`sequence` > 0, única por execução; `event_type` 1–5; `occurred_at`, `received_at`; `payload`) | 007 | L08. `payload` **não** é exposto (DC-08) |
| `nekkora_platform` tem SELECT em `workspaces`, `clients`, `automations`, `automation_versions`, `executions` e `execution_events`; RLS por organização | 012 linhas 913–926 | nenhuma permissão nova |
| **Índices:** `executions (automation_id)`, `execution_events (execution_id)`, `automation_versions (automation_id)`. Os únicos `(organization_id, workspace_id, …)` de `executions` e `automations` atendem a filtros pelo prefixo, mas **não** à ordenação por `created_at` ou por nome | 005–007 | risco de desempenho → **DC-07** (§4) |
| Massa de execuções e eventos: o `PortalIT` já monta execuções válidas (contrato de comando e payloads) | testes do portal | reaproveitar o padrão de massa, sem copiar código de produção |

## 2. Desenho

### 2.1 Autorização: só os métodos do componente (ressalva da C1 da GAP-02)

| Rota | Autorização |
|---|---|
| L01 `/workspaces` | `visibleWorkspacesQuery(…, "automation.read")`; sem nenhum → `items: []` |
| L02 `/workspaces/{w}` | `requireWorkspace(w, "automation.read")`; senão 404 |
| L03 `/workspaces/{w}/automations` | `requireWorkspace` → 404 |
| L04 `/automations/{a}`, L05 `/automations/{a}/versions` | **novo** `requireAutomation(j, org, m, a, perm)`: resolve o workspace da automação **na organização da rota**, aplica `requireWorkspace` e devolve o workspace. 404 idêntico para inexistente, de outra organização ou sem acesso |
| L06 `/executions` | sem filtro: `visibleWorkspacesQuery` (subconsulta dos workspaces visíveis). `workspaceId` → `requireWorkspace` (404). `automationId` → `requireAutomation` (404). Os dois acessíveis mas incompatíveis → 200 vazio |
| L07 `/executions/{x}`, L08 `/executions/{x}/events` | **novo** `requireExecution(j, org, m, x, perm)`, análogo ao de automação. Na L08, sem acesso → **404** (DC-12), diferente do portal |

**Regras comuns:**
- todos os caminhos passam pela checagem de consistência do catálogo (500 com log);
- conta externa → 403 (DC-02);
- ordem das verificações: 401 → 400 → 403 → 404.

### 2.2 Consultas e paginação (`READ_ONLY`, uma transação por requisição)

| Rota | Ordenação (mesma expressão no `ORDER BY` e na página seguinte) | Posição no cursor | Pai no cursor |
|---|---|---|---|
| L01 | `left(w.name, 48) COLLATE "C"`, `w.workspace_id` | prefixo, id | — |
| L03 | `left(a.name, 48) COLLATE "C"`, `a.automation_id` | prefixo, id | `workspaceId` |
| L05 | `version_number DESC` | número | `automationId` |
| L06 | `e.created_at DESC`, `e.execution_id DESC` | instante em µs, id | — (filtros na impressão) |
| L08 | `sequence ASC` | número | `executionId` |

**Detalhes:**
- `latestPublishedVersion` vem de uma subconsulta pela maior `version_number` publicada.
- `durationMs` segue a §1.7: `finished_at − started_at` truncado em ms, nulo se faltar algum.
- `requestedBy` = `{membershipId, displayName}`, a partir de `organization_memberships` + `users`; nulo quando `requested_by_membership_id` é nulo (agendada).
- **Período da L06** (§1.6):
  - `[from, to)` sobre `created_at`, cada limite opcional;
  - data sem fuso → 400;
  - `to < from` → 400;
  - `to = from` → 200 vazio;
  - `to − from` maior que 366 × 24 h exatas → 400.
- **Rótulos novos em `Labels`:**
  - status de execução: `pending`, `starting`, `running`, `succeeded`, `failed`, `cancelled`;
  - motivo: `process_completed`, `start_failed`, `process_failed`, `cancelled_before_dispatch`, `cancel_confirmed`, `queue_deadline_exceeded`;
  - origem: `manual`, `scheduled`;
  - tipo de evento: `process_started`, `process_succeeded`, `process_failed`, `process_start_failed`, `process_cancelled`;
  - status de versão: `draft`, `published`.

  Os rótulos de execução são os mesmos do portal. Código desconhecido → 500 com log.
- Controlador novo: `ProductReadController`.

## 3. Testes planejados

**Matriz §5.1 nas colunas L01–L08**, no padrão das GAP-02 e GAP-03: resposta **e** dados, mensagens de falha só com campos de diagnóstico e sem cursor.

| Grupo | Cenários |
|---|---|
| **Autorização compartilhada (§5.2, tabela V)** | um teste parametrizado sobre **todas** as rotas L. Os 9 atores da GAP-02 (ORG_ADMIN, ORG_ADMIN + VIEWER em W3, WORKSPACE_ADMIN, OPERATOR, VIEWER, revogada, sem papéis, suspensa, externa) × W1, W2, W3 (arquivado), outra organização e inexistente. A expectativa sai da tabela V (listas, 200, 404 ou 403), e uma rota divergente falha |
| **404 idêntico** | inexistente, de outra organização e sem acesso dão o mesmo corpo em L02, L03, L04, L05, L07, L08 e nos filtros da L06. UUID malformado → 400, inclusive para conta externa |
| **Comparação DC-05 × DC-12** | o mesmo `workspaceId` de outra organização: **200 vazio** na M02 (exceção) e **404** na L06 (regra geral), lado a lado. Eventos sem acesso → 404 na rota interna; a diferença com o portal fica registrada |
| **Campos e nulabilidade** | itens exatos e em ordem. Ausência de `command_json`, `command_snapshot`, `payload`, `errorSummary`, `pid`, `exitCode` e Worker/Agent, conferida pelas chaves. `client` nulo em workspace interno. `latestPublishedVersion` nulo sem publicada e igual à **maior** publicada com rascunhos posteriores. `requestedBy` na manual e nulo na agendada. `retryOfExecutionId` |
| **`durationMs`** | concluída (valor exato em ms, com truncamento de µs); em andamento, pendente e cancelada antes do despacho → nulo |
| **Período (L06)** | `from` e `to` ausentes; `createdAt = from` aparece; `createdAt = to` não aparece; `to = from` → vazio; `to < from` → 400; exatamente 366 dias → 200; 366 dias + 1 ms → 400; sem fuso → 400 |
| **Filtros (L06)** | `workspaceId`, `automationId` e `status` combinados; `automationId` sem acesso → 404; incompatível → vazio; `status` desconhecido, repetido ou acima de 6 → 400 |
| **Paginação estável (§5.3)** | L06 com datas distintas, empates e µs, ids contrários às datas, em todas as páginas (lição da GAP-03). L01 e L03 com ordenação binária, desempate e nomes longos (48 pontos de código) no fim da página. L05 e L08 em todas as páginas |
| **Mudança concorrente (§5.4)** | L01 renomeado para antes ou depois do cursor; L06 com execução nova inserida depois da página 1 (não aparece); L03 com automação inserida depois do cursor (aparece); mudança de `status` com filtro |
| **Revogação (§5.5)** | a tabela completa: listas sem workspace no caminho (itens somem), rotas com workspace ou recurso no caminho e filtros da L06 (**404**), acesso direto (L02, L04, L05, L07, L08 → 404), revogação do ORG_ADMIN e `revoke-all` (401) |
| **Cursores entre rotas paginadas reais** | cursor da L03 usado na L05 ou na M03, cursor de W1 usado em W2 e cursor da L06 com filtros trocados → 400 (pendência registrada na GAP-02) |

**Contraprovas por mutação** (Git Bash por caminho completo; critério de testes executados e falhas nomeadas; sha restaurado). Cada uma precisa reprovar o teste específico:
- `requireAutomation` sem `requireWorkspace`;
- L06 sem a subconsulta de visibilidade;
- eventos com lista vazia em vez de 404;
- `durationMs` contra o relógio atual;
- ordenação da L06 só pelo id;
- `to` inclusivo.

**Outras conferências:**
- `validar-config` comparado com a referência de 184 nomes;
- regressão completa com os arquivos conferidos contra `fontes.sha256`;
- patch incremental sobre o pacote da GAP-03 R1 (`00a34f4e…`) e patch total sobre HEAD;
- pacote com varredura de tokens e controle positivo.

## 4. DC-07: medição (antes de fechar a GAP-06)

| Item | Proposta |
|---|---|
| Onde | o PostgreSQL **descartável** do teste (Testcontainers, imagem `postgres:16-alpine`, a mesma da regressão). Nunca bases protegidas |
| Como | uma classe de medição **fora** da regressão: nome que não casa com `*Test`/`*IT` (ex.: `MedicaoDc07`), executada só por nome explícito (`-Dtest=MedicaoDc07`). Assim a regressão não ganha testes ignorados |
| Massa | a da §6 do contrato, gerada como superusuário (preparação declarada) com `generate_series`: 3 organizações, 200 workspaces, 20.000 automações (1 workspace com 5.000), **1.000.000 de execuções** em 12 meses (1 workspace "quente" com 300.000) e 5 eventos por execução numa amostra de 10.000 |
| Consultas | o **mesmo texto SQL e os mesmos parâmetros** das rotas, vindos do montador de consulta do próprio controlador (sem cópia), executados com o **papel real `nekkora_platform`**, **RLS ativa** e **contexto organizacional ativo** (a mesma `OrgTransactions.inOrganization`, que fixa `nekkora.organization_id`). **O custo da autorização compartilhada entra na medição**: as consultas de lista incluem a condição de visibilidade de `visibleWorkspacesQuery`, e as de detalhe e subordinadas incluem `requireAutomation`/`requireExecution` e a checagem de consistência do catálogo, medidas por requisição completa e também separadamente (autorização × consulta da página). Casos: L06 na 1ª página e em página profunda (cursor após 10.000), sem filtro, só com `from`, com `workspaceId` quente, com `status` seletivo e com 30 dias; L03 no workspace de 5.000 (1ª e profunda); L01 com 200; L05; L08. Tudo para ORG_ADMIN e para OPERATOR com 3 workspaces. **Qualquer parte não medida é identificada como não medida** no relatório |
| Registro (reprodução) | versão do PostgreSQL, da imagem e do driver; **recursos do contêiner** (CPUs e memória visíveis ao Docker, `shared_buffers`, `work_mem`) e da máquina hospedeira; **volumes efetivos** por `count(*)` por tabela e por organização; texto das consultas e valores dos parâmetros (sem segredo); `EXPLAIN (ANALYZE, BUFFERS)` de cada caso; **aquecimento** (3 execuções descartadas) e tempos de 30 execuções, com p50, p95 e máximo; data e hora, e sha das fontes medidas |
| Critério | os 200 ms (primeiras páginas) e 500 ms (profundas) do contrato são **referências**, não critérios de aceite: a decisão registrada (DC-07) é medir. A entrega mostra os resultados, os **desvios** em relação à referência e as recomendações |
| Se ficar acima da referência | registrar o desvio e, se for o caso, recomendar índice como **migration própria**, a decidir à parte. Ficar acima da referência **não autoriza** criar índice nem alterar migrations; a GAP-06 não faz nenhuma das duas coisas |

**Natureza da medição:** é evidência de desempenho **naquele ambiente** (contêiner local, Docker no Windows), útil para comparar planos e ordens de grandeza; não precisa representar produção.

## 5. OpenAPI

- As 8 operações L recebem `x-estado: implementado` depois dos testes.
- Os 500 por inconsistência de catálogo ou rótulo entram nas respostas.
- O arquivo continua anexo enquanto o **DC-14** estiver pendente.

## 6. Fora do escopo

- Ações (executar, cancelar, agendar), Workers e Agents: continuam na v1 completa.
- Logs (DC-13).
- `errorSummary` (DC-10).
- Criador da automação (DC-09).
- `command_json`, `command_snapshot` e `payload` (DC-08).
- Índices: migration própria, se a medição indicar (DC-07).
