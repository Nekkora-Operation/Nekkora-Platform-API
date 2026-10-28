# Platform–Agent: inventário da base real (2026-10-28)

Missão PLATFORM–AGENT (contrato, identidade, telemetria e orquestração). Este documento registra o que existe **antes** de
qualquer tabela ou rota nova. O contrato está em `2026-10-28-platform-agent-contrato.md`.

## 1. Base da API

| Item | Valor |
|---|---|
| `main` remota | `e43b957` (merge do #21). Contém #22 (`e9f56bf`), #20 (`0f23355`) e #21 (`75640b1`): migrations **001–022** |
| #23, #24, #25 | marcados como *merged*, mas **nas branches-base empilhadas**, depois de essas branches entrarem na `main`: #23 e #25 em `feat/email-real-homologacao`, #24 em `feat/acessos-023-banco`. **Não estão na `main`** (conferido por conteúdo: a `main` não tem `023_acessos_convites.sql` nem `024_…`, e os commits `dbc4366`, `00c6070` e `ff36ff1` não são ancestrais de `e43b957`) |
| Integração | PR **#26** (`chore/integrar-023-024-016b10b11`, `4cd1ec8`): merge sem conflito das duas branches sobre `e43b957`, com `validar-config` OK. Esta missão é empilhada sobre ele (`feat/platform-agent-canal`), com migrations a partir da **025** |
| Instruções do repositório | não há `CLAUDE.md` nem `AGENTS.md`. Valem o `README.md`, os relatórios de `docs/relatorios/` e os padrões das migrations e das suítes (`db/tests/*/README.md`) |
| Estado de trabalho do clone principal | `feat/email-real-homologacao` (antiga), com os não rastreados `db/tests/ativacao-022/saidas/` e `db/tests/correio-021/saidas/` preservados. A missão trabalha no worktree próprio `npa-agent` |

## 2. O que existe na API para Workers, Agents, credenciais, versões, execuções e agendamentos

**Banco (todas as tabelas com RLS por organização pela 012):**

| Estrutura | Migration | Estado |
|---|---|---|
| `workers` (1=ACTIVE, 2=SUSPENDED, 3=RETIRED), `agents` (1=PENDING, 2=ACTIVE, 3=SUSPENDED, 4=REVOKED; `installation_id` UUID único; `agent_version`; `last_heartbeat_at`), `worker_agent_assignments` (histórico, um ativo), `worker_workspace_assignments`, `agent_credentials` (hash SHA-256, validade, revogação com motivo e **pertinência obrigatória**; **uma credencial não revogada por Agent**) | 004 | tabelas e gatilhos de imutabilidade. **Nenhuma rota, serviço ou Java** |
| Permissões `worker.manage`, `agent.manage`, `agent_credential.manage`, `worker_agent.assign`, `worker_workspace.authorize` (só ORG_ADMIN) | 004 | no catálogo; nenhum uso em Java |
| `automations` e `automation_versions` com `command_json` (`contract_version` 1, `artifact.reference` e `artifact.sha256`, `runtime`, `entrypoint`, `arguments`, `parameters`, `secret_bindings`) | 005, 008 | banco. API: **só leitura** (`/o/{org}/automations*`, GAP-06) |
| `executions` (1=PENDING, 2=STARTING, 3=RUNNING, 4=SUCCEEDED, 5=FAILED, 6=CANCELLED; idempotência por `request_key`; reserva de Worker por gatilho; `pid` e `exit_code`; pedido de cancelamento) | 006, 008, 009, 010 | banco. API: **só leitura** (`/o/{org}/executions*`). **Sem** `OUTCOME_UNKNOWN`, `TIMED_OUT` ou `BLOCKED`. Criação só pelo scheduler (009) ou por um futuro serviço |
| `execution_events` (5 tipos de ciclo de vida, `UNIQUE(execution_id, sequence)`, `payload.schema_version` inteiro 1, `error_summary` livre ≤ 2 KB), `logs` (AGENT/STDOUT/STDERR) | 007 | banco. API: leitura de eventos por execução. **Nenhuma ingestão** |
| `automation_schedules`, revisões e ocorrências (PENDING/EXECUTION_CREATED/SKIPPED, LATE/OVERLAP…), `process_schedule_occurrence` | 009 | banco e funções. **Nenhum processo scheduler** e nenhuma rota |
| Limites (WORKERS_REGISTERED, CONCURRENT_EXECUTIONS, PENDING_EXECUTIONS, bytes diários) e retenção | 010, 011, 022 | banco. Capacidade zero até a ativação (022) |
| Papel `nekkora_ingestion` (LOGIN, sem atributos), `SELECT/INSERT` em `execution_events` e `logs`, barreira de uso diário | 011, 012 | **papel sem serviço** ("sucessor de nekkora_app") |
| Funções de autoridade `access_membership_effective` e `access_org_permission` (SECURITY INVOKER) | 023 (PR #26) | usadas por gatilhos e pela Platform |

**API Java:** os módulos `nekkora-core`, `nekkora-platform` (8081), `nekkora-portal` (8082) e `nekkora-ops`. Nenhum código de
Worker, Agent, credencial de máquina, ingestão ou agendamento. O OpenAPI (`api/openapi/nekkora-api-v1.yaml`) só tem as
leituras de execução e de eventos.

**Conclusão:** as tabelas existem, mas **nenhum serviço as opera**. Nada aqui prova que pareamento, credencial, ingestão ou
despacho funcionem.

## 3. Agent usado como referência

| Item | Valor |
|---|---|
| Repositório | `Nekkora-Operation/Nekkora-Agent`. **O remoto está vazio**: nenhuma capacidade do Agent está publicada |
| Commit inspecionado | `f77e00e` (branch local `fix/agent-f-agt-01-001`; o código é o de `9c81ddc`, e o ZIP de laboratório é `cd04a7c4…`) |
| Arquivos | `exec/ExecutionState.java`, `exec/ExecutionRecord.java` (`nekkora-agent/execution` 1.0.0), `exec/Launch.java` (`classify`: protocolo de resultado), `exec/ExecutionController.java` (`reconcile`, `cancel`), `obs/ActivityEvent.java`, `obs/ActivityLog.java`, `docs/desktop-001/arquitetura.md`, `observabilidade.md` (§3 envelope, §4 limites e retenção, §8 fronteira com a Platform) e o schema `observabilidade/nekkora-agent-observability-1.0.0.schema.json` |
| Evidência real recente | missão STUDIO-AGENT-COMPAT-001 (28/10): pacote gerado pelo Studio executado pelo Agent empacotado, `SUCCEEDED` (294 envelopes válidos), `CANCELLED` (420), `OUTCOME_UNKNOWN` depois de queda (668). Pacote de revisão local `revisao-2026-10-28-studio-agent-compat` |

**Envelope `nekkora-agent/observability` 1.0.0** (conferido no schema, no código e nos registros reais):
- campos: `agent_execution_id` (`ex-` + 32 hex), `sequence` (≥ 1, contínua por execução, ordem de **observação**), `timestamp_utc`
  (opcional, ocorrência na origem), `observed_utc`, `source` (AGENT/FRAMEWORK/COLLECTOR), `event_code`, `severity`, `category`
  (LIFECYCLE/ACTIVITY/BUSINESS/FAILURE/TELEMETRY), `phase`, `transaction_ref`, `step_id`, `studio_step_id`, `action`,
  `attempt`, `elapsed_ms`, `status`, `error_code`, `counts` (≤ 16 chaves inteiras), `correlation.framework_execution_id` e
  `correlation.source_position`;
- nenhum campo livre;
- `additionalProperties: false`;
- lacunas declaradas pelo coletor: `EVENT_GAP` (`AGT-OBS-005/006/009`, `counts.lost`), `HISTORY_PURGED` (`AGT-OBS-010`),
  `EVENT_INVALID`, `SOURCE_*`;
- **achado A01 da COMPAT-001:** o arquivo do schema não é JSON válido (escape `\.` no padrão de `step_id`). A API usa a
  regra correta (`\\.`) e não carrega esse arquivo.

**Protocolo do supervisor** (`estado.json` 1.0.0, a autoridade do desfecho):
- estados: `READY`, `STARTING`, `RUNNING`, `SUCCEEDED`, `FAILED`, `CANCELLED`, `TIMED_OUT`, `BLOCKED` e `OUTCOME_UNKNOWN`;
- transições fechadas: terminal não sai, e `SUCCEEDED` só vem de `RUNNING`;
- `SUCCEEDED` só com protocolo (summary `COMPLETED` e, em lista, planilha `COMMITTED`) e exit 0;
- sem protocolo: exit 0 → `OUTCOME_UNKNOWN` (`AGT-EXE-021`); exit ≠ 0 → `FAILED` (`020`);
- queda do Agent → `OUTCOME_UNKNOWN` (`025`), suspensão → `026`;
- **nada é relançado.**

**Persistência local:**
- `atividade.jsonl`: até 20.000 eventos ou 8 MiB por execução, cota total de 96 MiB;
- retenção de 50 execuções terminadas;
- fila de gravação limitada e conferida (B-OBS-03);
- **não há** fila de saída nem retenção atrelada a ACK (a observabilidade §8 diz que o transportador "deve" tê-las).

## 4. Capacidades: implementada, documentada, simulada ou planejada

| Capacidade | API | Agent |
|---|---|---|
| Execução local supervisionada, aprovação por impressão digital, cópia conferida | — | **implementada** e homologada |
| Envelope OBS 1.0.0 gravado localmente | — | **implementada** |
| Transporte para a Platform (HTTPS, ACK por sequence) | ausente | **documentada** como futura (observabilidade §8). **Não implementada**: o `ArchitectureTest` proíbe rede (`java.net.http`, `Socket`, `URL(`) no código de produção |
| Pareamento, identidade de instalação e credencial de máquina | tabelas (004), sem serviço | **ausente** (sem identidade de instalação; o nome da máquina pode ser fictício) |
| Heartbeat | coluna `last_heartbeat_at` (004) | **ausente** |
| Registro de execução na Platform | `executions` (006), sem serviço; sem estados locais | **ausente** (o registro local existe) |
| Comandos remotos e cancelamento remoto | `executions` com pedido de cancelamento (006), sem serviço | **ausente** ("Execução pela Platform: Indisponível nesta versão") |
| Agendamento | banco (009), sem scheduler | **ausente** |

## 5. Matriz

| Capacidade | API existente | Agent existente | Lacuna | Responsável |
|---|---|---|---|---|
| Pareamento | `agents`, `workers`, `worker_agent_assignments` e as permissões (004); nenhum serviço | nada (sem identidade de instalação) | **API:** código de pareamento de uso único, autorizado por uma pessoa com `agent.manage` e `agent_credential.manage`; resgate pela máquina; auditoria. **Agent:** gerar o segredo, guardar a credencial, resgatar | API (esta missão) / Agent (encaminhamento) |
| Credenciais | `agent_credentials`: hash, validade, revogação com pertinência, uma ativa por Agent (004) | nada | **API:** autenticação de máquina separada de cookie e sessão; rotação como ato administrativo com código; revogação. **Agent:** apresentar a credencial e trocá-la pelo código de rotação | API / Agent |
| Registro da execução | `executions` (006) só para execução orquestrada: exige `request_key`, `command_snapshot` igual ao publicado, `requested_by` e reserva de Worker; sem estados locais | `estado.json` 1.0.0 com `agent_execution_id` | **API:** autorização de execução local vinculada a Worker, workspace, versão e pacote imutável; registro idempotente por (Agent, `agent_execution_id`) com os estados reais do supervisor. **Agent:** enviar o registro e a origem | API / Agent |
| Eventos e ACK | `execution_events` (5 tipos, FK para `executions`); papel `nekkora_ingestion` sem serviço | envelope 1.0.0 local, `sequence` contínua, lacunas declaradas | **API:** ingestão autenticada do envelope com deduplicação, recusa de conflito e ACK depois do commit. **Agent:** fila de saída, reenvio desde o último ACK, retenção atrelada ao ACK | API / Agent |
| Resultado e reconciliação | sem `OUTCOME_UNKNOWN` (006) | estados e códigos `AGT-EXE-*`; `reconcile()` sem relançar | **API:** guardar o desfecho do supervisor, transições validadas, conflito registrado (nunca sobrescrito em silêncio). **Agent:** enviar cada relatório do supervisor | API / Agent |
| Comandos e cancelamento | pedido de cancelamento em `executions` (006), sem serviço | ausente | **Agent:** canal de comando (puxar comandos por HTTPS), confirmação de recebimento × início, cancelamento por identificador. A API depende disso | **Agent primeiro**; API pausada nesta parte |
| Agendamento | banco e funções (009), sem processo | ausente | depende do despacho | pausado (depende de comandos) |

**Consequência para o primeiro marco:** a parte da API pode ser construída e validada contra um Agent simulado, e também
alimentada com **registros reais** do Agent (os `atividade.jsonl` e `estado.json` da COMPAT-001). A integração real
depende do transporte no Agent, que não existe hoje. O encaminhamento concreto está no contrato, §12.
