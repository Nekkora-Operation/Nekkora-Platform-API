# PLATFORM-INICIO-001 — Como as execuções são contadas no Início

Regras do resumo de execuções (`GET /api/v1/o/{org}/execution-summary`) e da "última execução" de cada automação
(`GET /api/v1/o/{org}/automations`). O único lugar que as aplica é `ExecutionSummaryReads`; o teste é `ExecutionSummaryIT`.

## 1. O que é uma execução na contagem

Uma execução é **um pedido de execução**, nunca uma tentativa. Há quatro origens na Platform, e nenhuma linha é contada
duas vezes:

| Origem | Tabela | Conta como |
|---|---|---|
| Execução da Platform (manual ou agendada) | `executions` | uma por linha |
| Execução numa máquina, relatada pelo Agent | `agent_runs` | uma por linha, exceto a que aponta para uma execução da Platform (`platform_execution_id`), já contada acima |
| Pedido de execução ao Agent anterior que **não** virou execução | `run_requests` | uma por linha; o pedido que virou execução (`RUN_LINKED`) é contado pela execução dele, em `agent_runs` |
| Solicitação de execução remota ao Agent Client | `remote_executions` | uma por solicitação, com o estado da **última** tentativa |

Execuções locais privadas do Agent Client ("Executar neste computador") nunca são enviadas à Platform e não entram.

Hoje `platform_execution_id` é sempre nulo (o banco só aceita as origens 1 e 2 de `agent_runs`, que o proíbem); a exclusão
existe para que a contagem continue certa se esse vínculo passar a ser usado.

## 2. Solicitações, tentativas e repetições

- **Tentativas** (`remote_attempts`): repetir uma solicitação remota em outra máquina cria uma tentativa, não uma execução.
  A solicitação conta uma vez e vale o estado da tentativa mais recente. Uma solicitação que falhou e depois teve sucesso
  numa nova tentativa conta como **um sucesso**.
- **Repetições** (`executions.retry_of_execution_id`): repetir uma execução da Platform é **outro pedido**, com outra linha.
  Conta de novo.
- **Pedido e execução do Agent anterior**: o pedido aceito que virou execução conta uma vez (pela execução). O pedido
  recusado, expirado, revogado ou ainda pendente conta uma vez (pelo pedido).

## 3. Qual data decide o período

O instante em que **a Platform registrou o pedido**: `executions.created_at`, `agent_runs.registered_at`,
`run_requests.requested_at`, `remote_executions.requested_at`. Intervalo semiaberto `[from, to)`. O relógio da máquina
(`agent_created_at` e semelhantes) nunca é usado. Uma execução pedida no período conta nele mesmo que termine depois.

## 4. Situações

Cada execução cai em exatamente uma situação; a soma das seis é o total.

| Situação | `executions` | `agent_runs` | `run_requests` (sem execução) | última tentativa remota |
|---|---|---|---|---|
| Sucesso (`succeeded`) | SUCCEEDED | SUCCEEDED | — | SUCCEEDED |
| Erro (`failed`) | FAILED por PROCESS_FAILED | FAILED, TIMED_OUT | — | FAILED, TIMED_OUT |
| Em andamento (`inProgress`) | PENDING, STARTING, RUNNING | READY, STARTING, RUNNING | REQUESTED e DELIVERED dentro do prazo; ACCEPTED | QUEUED a RUNNING (com a oferta ainda válida); solicitação sem tentativa |
| Cancelada (`cancelled`) | CANCELLED antes do despacho ou confirmado | CANCELLED | REVOKED | CANCELLED |
| Não iniciada (`notStarted`) | FAILED por START_FAILED; CANCELLED por prazo da fila vencido | BLOCKED | REFUSED, NOT_STARTED, EXPIRED; pedido com o prazo vencido | REFUSED, EXPIRED; oferta vencida sem recebimento |
| Não confirmada (`unconfirmed`) | — | OUTCOME_UNKNOWN | — | ENDED_UNKNOWN |

Consequências, por decisão da missão:

- **Erro é só erro da automação**: o processo existiu e falhou, ou estourou o tempo. Cancelamento, execução que não
  começou e resultado não confirmado **nunca** entram em "erro".
- **Não confirmada nunca vira sucesso nem erro.** O relato tardio de uma tentativa encerrada por falta de comunicação
  (`late_state`) fica ao lado, como na tela da execução, e não muda a situação.
- Estouro de tempo (TIMED_OUT) conta como erro: o processo da automação rodou além do limite.

## 5. Dupla contagem

- As quatro tabelas são conjuntos distintos; a única ligação entre elas é pedido → execução do Agent anterior
  (`run_requests.agent_run_id`), tratada pela regra da seção 1.
- A contagem de execuções remotas parte de `remote_executions`, não de `remote_attempts`.

## 6. Completude e permissões

- A contagem é uma agregação no banco sobre **todas** as linhas do período; não depende de página nem de limite de lista.
- Toda consulta usa a condição compartilhada de visibilidade (`WorkspaceAccess.visibleWorkspacesQuery`, permissão
  `automation.read`), dentro da transação da organização (RLS ativa). Quem lê só alguns ambientes recebe a conta só
  deles; quem não lê nenhum recebe zeros. Conta externa: 403.
- A leitura não grava nada. Uma oferta vencida ainda não marcada conta como expirada, como nas outras leituras.

## 7. Agendamentos (lacuna)

O banco tem o modelo de agendamentos desde a migration 009 (`automation_schedules`, `automation_schedule_revisions`,
`automation_schedule_occurrences`) e as permissões `automation_schedule.read` e `automation_schedule.manage`. **Não existe**,
porém, rota para criar, ler ou suspender agendamentos, nem processo que materialize ocorrências e crie as execuções. Sem
isso não há "próxima execução" real para mostrar: o Início informa que a funcionalidade ainda não está disponível.

Trabalho necessário para existir (fora desta missão): rotas de leitura e de gestão de agendamentos; o agendador (cálculo da
próxima ocorrência por expressão cron e fuso, materialização, tolerância de atraso, criação da execução); o destino da
execução agendada (hoje as execuções são feitas pelo Agent, por pedido de uma pessoa); telas de gestão; auditoria.
