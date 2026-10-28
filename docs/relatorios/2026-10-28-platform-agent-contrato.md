# Contrato Platform–Agent 1.0 (canal da máquina, identidade, registro de execução e telemetria)

**Estado:** candidato, implementado do lado da API na branch `feat/platform-agent-canal` (migration 025, módulo
`nekkora-agent-gateway` e rotas administrativas da Platform). O Agent ainda não implementa este contrato (ver §12). Base
real: `2026-10-28-platform-agent-inventario.md`.

## 1. Princípios

1. **A organização vem do servidor.** A credencial de máquina leva o identificador da organização e da credencial. O
   serviço define o contexto da organização e só encontra a credencial se ela pertencer àquela organização (RLS) **e** se
   o hash do segredo conferir. Nenhum campo enviado pelo Agent concede acesso a uma organização, Worker, workspace ou
   versão: tudo é relido dos vínculos no servidor, na transação do ato.
2. **Autonomia local.** A execução local já autorizada não depende da API. A API recebe **fatos** depois (registro,
   relatórios do supervisor, eventos). Uma falha da API, do Redis ou do banco nunca pede reexecução: o reenvio de transporte
   entrega os mesmos fatos de novo, e o processo de negócio não se repete.
3. **O desfecho é do supervisor.** O estado de uma execução só muda por relatório do supervisor autenticado, nunca por
   evento do Framework, nem por um evento AGENT do envelope, nem pelo relógio, nem pela ausência de heartbeat.
4. **Nada é inventado.** Sem eventos internos, as etapas aparecem como indisponíveis. Uma lacuna declarada nunca conta
   como recebimento. O resultado desconhecido continua desconhecido.
5. **HTTPS com lotes e ACK.** Sem WebSocket, SSE ou serviço novo de mensageria. O Agent sempre inicia a conexão.

## 2. Processos, papéis e superfícies

| Superfície | Processo | Papel no banco | Autenticação |
|---|---|---|---|
| `/agent/api/v1/*` (máquina) | **`nekkora-agent-gateway`**, porta 8083, novo | `nekkora_ingestion` (012, até aqui sem serviço) | credencial de máquina (`Authorization: Bearer nka1…`), sem cookie, sessão nem CSRF |
| `/api/v1/o/{org}/…` (pessoas: pareamento, autorizações, consultas) | `nekkora-platform` | `nekkora_platform` | sessão da organização + CSRF; atos com step-up de TOTP recente |

O gateway nginx encaminha `/agent/api/v1/` ao processo novo. O processo novo não recebe nenhuma configuração nem credencial
da Platform, e a Platform não recebe as do gateway (mesmo isolamento do Portal).

## 3. Identidades

Os identificadores locais e os da Platform nunca se confundem, e nenhum vínculo é feito por coincidência de nome.

| Identificador | Formato | Quem cria | Onde vive |
|---|---|---|---|
| `organization_id` | UUID | Platform | todas as tabelas (RLS) |
| `worker_id` | UUID | Platform (pessoa com `worker.manage`) | `workers` |
| `agent_id` | UUID | Platform, no resgate do pareamento | `agents` |
| `installation_id` | UUID | Platform, no resgate (`agents.installation_id`). Identifica a instalação; não é segredo nem autorização. Reinstalar = novo pareamento = novo `agent_id` | `agents` |
| `credential_id` | UUID | Platform, no resgate | `agent_credentials` |
| `agent_execution_id` | `ex-` + 32 hex | **Agent** (local) | `agent_runs.agent_execution_id`, único por `agent_id` |
| `framework_execution_id` | `ex-` + 8 a 64 hex | **Framework** (local) | aprendido de `correlation.framework_execution_id` do envelope; o primeiro visto fica e uma divergência é registrada |
| `agent_run_id` | UUID | Platform, no registro | identificador do registro da execução local na Platform (`agent_runs`) |
| `platform_execution_id` | UUID | Platform (`executions.execution_id`) | só em execução **despachada** pela Platform (fase de comandos). Em execução local é **nulo**: uma execução local nunca vira uma linha de `executions` |
| `authorization_id` | UUID | Platform (pessoa com `automation.execute`) | `local_run_authorizations` |

**Nome lógico da máquina:** `agents.name`, informado **explicitamente** pela pessoa que cria o pareamento. O Agent não envia
nome de usuário, nome do computador, caminhos nem outros dados da máquina.

## 4. Pareamento e credencial

### 4.1 Código de pareamento (pessoa → máquina)
- `POST /api/v1/o/{org}/agent-enrollments`, exige `agent.manage`, `agent_credential.manage` e `worker_agent.assign`, e
  `worker.manage` quando cria Worker. Também exige **step-up recente**.
- O corpo indica o Worker existente (`workerId`) **ou** cria um Worker (`workerName`), além de `agentName` e
  `expiresInMinutes` (5 a 60; padrão 15).
- A resposta 201 traz o código **uma vez**: `nkp1.<organization_id>.<enrollment_id>.<segredo>`, segredo de 32 bytes
  aleatórios em base64url, com `Cache-Control: no-store`. O banco guarda só o SHA-256 do código.
- Worker já associado a um Agent ativo é recusado (409): um Worker não é apropriado por outro Agent sem revogar o atual.
- `POST …/agent-enrollments/{id}/cancel` cancela um código ainda não usado.

### 4.2 Resgate (máquina)
- `POST /agent/api/v1/enrollments/redeem` com `{code, credentialSecret, agentVersion, contractVersions}`.
- **O segredo da credencial é gerado pelo Agent** (32 bytes aleatórios, base64url). Ele viaja uma vez por HTTPS, e o servidor
  guarda só o SHA-256. O servidor nunca devolve segredo.
- **Idempotência:** repetir o resgate com o mesmo código **e** o mesmo segredo devolve o mesmo resultado. Uma resposta perdida
  não tranca a máquina para fora. O mesmo código com **outro** segredo é recusado (reutilização indevida).
- Na transação do resgate, sob lock, são conferidos:
  - código válido, não vencido, não cancelado e não consumido por outro segredo;
  - quem criou o código **ainda** tem as permissões (`access_org_permission`, da 023);
  - organização ativa e Worker ativo, sem Agent ativo associado.
  
  Com tudo válido, cria o Agent (ACTIVE), a associação Worker–Agent (`assigned_by` = quem criou o código), a credencial
  (`issued_by` = quem criou o código, validade de 90 dias) e marca o código como consumido.
- A resposta traz `organizationId`, `agentId`, `installationId`, `workerId`, `credentialId`, `credentialExpiresAt` e
  `contractVersion`. O Agent monta a credencial `nka1.<organization_id>.<credential_id>.<segredo>`.
- Todo código inválido recebe a mesma resposta (`401 unauthenticated`): não há como sondar quais códigos existem. Há um
  limite por IP de origem.

### 4.3 Rotação e revogação
- **A rotação é um ato administrativo.** O modelo da 004 exige uma pertinência humana em toda revogação e só permite uma
  credencial não revogada por Agent.
  - `POST /api/v1/o/{org}/agents/{agentId}/credential-rotations` (`agent_credential.manage` e step-up) gera um código de
    rotação (`nkp1…`, mesmo formato).
  - O Agent o resgata no mesmo endpoint **apresentando também a credencial atual** (válida ou vencida, nunca revogada).
  - Na mesma transação, a credencial atual é revogada (motivo 1=ROTATION, `revoked_by` = quem criou o código) e a nova é
    criada. A idempotência é a mesma do resgate.
- `POST /api/v1/o/{org}/agents/{agentId}/revoke` com `{reason: COMPROMISED|ADMINISTRATIVE}` (`agent.manage` e step-up):
  - Agent passa a REVOKED;
  - credencial revogada (motivos 3 ou 4);
  - associação Worker–Agent encerrada;
  - efeito imediato na API.
- **Concorrência:** toda operação da máquina trava a linha da credencial em `FOR SHARE` na transação do ato. Uma revogação
  confirmada antes faz a operação ser recusada. Uma revogação concorrente espera a operação em curso terminar: o que já tinha
  sido aceito fica, e nada depois dela é aceito.

### 4.4 Autenticação de cada chamada da máquina
`Authorization: Bearer nka1.<org>.<credential>.<segredo>`. O servidor confere, na transação da operação:
- formato;
- credencial da organização (RLS), hash em tempo constante, não revogada e não vencida;
- Agent ACTIVE;
- associação Worker–Agent ativa.

Qualquer falha devolve `401 unauthenticated`, uniforme. Cookies, sessões e CSRF da Platform não valem no canal da máquina, e
a credencial da máquina não vale na Platform.

## 5. Presença (heartbeat)

- `POST /agent/api/v1/heartbeat` com `{agentVersion, contractVersion, capabilities[]}`. As capacidades vêm de uma lista
  fechada: `RUN_LOCAL`, `OBS_1_0`, `SUPERVISOR_REPORT_1_0`.
- Atualiza `agents.last_heartbeat_at` e `agents.agent_version`, e grava as capacidades em `agent_presence`.
- A resposta traz `serverTime`, `credentialExpiresAt` e `contractVersion`.
- **Conectividade** é calculada na consulta: `CONNECTED` se o último contato tem até 180 s (o Agent deve bater a cada 60 s);
  senão, `DISCONNECTED`.
- **A ausência de heartbeat nunca encerra execução, nunca muda estado e nunca libera Worker.** Nenhum processo reage a ela.

## 6. Autorização de execução local e registro

### 6.1 Autorização (pessoa)
- `POST /api/v1/o/{org}/workspaces/{ws}/local-run-authorizations` com `{workerId, automationVersionId, validUntil}`, que
  exige `automation.execute` **no workspace** e step-up. Ela vincula:
  - organização, workspace e Worker, que precisa estar **autorizado no workspace** (`worker_workspace_assignments`
    ativa: `PUT /api/v1/o/{org}/workers/{w}/workspaces/{ws}`, com `worker_workspace.authorize`);
  - automação e **versão publicada** (`automation_versions.status = 2`);
  - **pacote imutável:** `package_fingerprint` = `command_json.artifact.sha256` da versão. Para um pacote STUDIO_PYTHON, esse
    valor é a impressão digital do Agent (`sha256:<hex>` sem o prefixo). A autorização copia o valor, e o banco confere a
    igualdade.
  - validade (`validUntil`, até 365 dias) e revogação (`…/{id}/revoke`).
- O gatilho do banco reconfere tudo isso na inserção (autoridade de quem autoriza, versão publicada, artefato, Worker
  autorizado). A autorização é imutável, exceto pela revogação.
- `GET /agent/api/v1/authorizations`: o Agent baixa as autorizações ativas **do seu Worker** e as guarda para uso offline.

### 6.2 Limites offline (declarados)
- O Agent pode executar offline, dentro do `validUntil` da autorização que guardou.
- **Uma revogação no servidor não interrompe uma máquina desconectada.** Ela vale para a máquina no próximo download das
  autorizações. Execuções que começaram antes disso continuam sendo fatos e são aceitas.
- O registro grava `authorization_state_at_registration` (`ACTIVE`, `REVOKED` ou `EXPIRED`): a API mostra quando uma execução
  chegou depois da revogação ou do vencimento. **Nunca recusa o fato** de uma execução que aconteceu com uma autorização
  daquela máquina.
- Uma autorização de outra organização, de outro Worker ou inexistente é recusada (`403`).

### 6.3 Registro da execução (máquina)
- `POST /agent/api/v1/runs` com `{agentExecutionId, authorizationId, packageFingerprint, supervisor:{…}}`:
  - **Idempotente por (`agent_id`, `agent_execution_id`).** O mesmo registro devolve o mesmo `runId`; o mesmo
    `agentExecutionId` com outra autorização ou outro pacote é recusado (`409`).
  - O pacote informado precisa ser o da autorização (`403`).
- Workspace, automação, versão e Worker vêm **da autorização**, nunca do corpo.
- **Pacote local sem versão publicada não é registrável** nesta versão (não existe autorização para ele). Não se cria versão
  fictícia. A proposta de representação própria está no §13.

## 7. Relatório do supervisor (desfecho)

`POST /agent/api/v1/runs/{agentExecutionId}/supervisor` com os campos do `estado.json` 1.0.0:
- `state`, `code` (`AGT-XXX-nnn`), `exitCode`;
- `counts` (até 16 chaves inteiras);
- `created`, `started`, `ended` (relógio do Agent, guardado como **informado**, nunca usado para ordenar);
- `adopted`, `suspensions`, `locks`.

| Situação | Tratamento |
|---|---|
| mesmo estado e mesmos dados do atual | idempotente (`UNCHANGED`) |
| transição permitida pelo Agent (READY→STARTING→RUNNING→terminal; READY→BLOCKED/CANCELLED; STARTING→BLOCKED/FAILED/CANCELLED/OUTCOME_UNKNOWN; RUNNING→SUCCEEDED/FAILED/CANCELLED/TIMED_OUT/OUTCOME_UNKNOWN) | aplicada (`APPLIED`) |
| estado anterior ao atual (relatório atrasado, ex.: RUNNING depois de SUCCEEDED) | não muda nada; registrado como `STALE` |
| contradição (outro terminal depois de um terminal, ou mesmo terminal com dados diferentes) | **não muda o estado**; registrado como `CONFLICT` em `agent_run_reports`, a execução fica marcada (`supervisor_conflicts`) e a resposta é `409` |

Todos os relatórios ficam em `agent_run_reports`, que só recebe inserções.

**Resultado desconhecido:** `OUTCOME_UNKNOWN` é terminal e aparece como resultado `UNKNOWN`, nunca como falha nem como
sucesso. Nada na API agenda uma nova tentativa. Evidências tardias (ex.: um protocolo publicado encontrado depois, achado
A02 da COMPAT-001) não mudam o estado. O campo fica reservado para uma versão futura do contrato.

## 8. Eventos (envelope OBS 1.0.0), lotes, deduplicação e ACK

`POST /agent/api/v1/runs/{agentExecutionId}/events` com `{events: [envelope…], declaredMissing: [{from, to}…]}`.

**Validação** (as mesmas regras do Agent, conferidas no código do Agent):
- `schema = nekkora-agent/observability`. Versão `1.0.x`: só os campos conhecidos. `1.y` (y > 0): os campos novos são
  descartados e contados. Outra versão maior é recusada.
- `agent_execution_id` igual ao da URL.
- Formatos, enums e tamanhos de cada campo, com a mesma regra de "4 ou mais dígitos seguidos" em `step_id`, `status` e
  `error_code`.
- `counts`: até 16 chaves, inteiros ≥ 0. Profundidade máxima 2. Envelope de até 4 KiB.
- Um evento inválido é recusado sozinho (`INVALID`, com o motivo) e não impede os outros.

**Identidade e deduplicação:**
- escopo: organização + Agent + execução (`agent_run_id`) + `sequence`;
- conteúdo: SHA-256 do envelope **canônico** (campos conhecidos, chaves ordenadas);
- mesma identidade e mesmo conteúdo → `DUPLICATE`, sem efeito;
- mesma identidade e conteúdo diferente → `CONFLICT`: recusado, o original fica e a recusa é registrada em
  `agent_ingestion_refusals`.

**ACK** (resposta 200, enviada **só depois do commit**):

| Campo | Significado |
|---|---|
| `storedThrough` | maior `k` tal que todos os eventos `1..k` estão **gravados** |
| `settledThrough` | maior `k` tal que cada número `1..k` está gravado **ou** foi declarado ausente pelo Agent. **Declarado ausente não é recebido** |
| `results[]` | por evento: `STORED`, `DUPLICATE`, `CONFLICT` ou `INVALID` (com o motivo) |

- **Fora de ordem:** cada evento é gravado pela sua `sequence`. Os dois números só avançam quando o intervalo fica contínuo.
- **Retomada:** o Agent reenvia a partir de `settledThrough + 1`. Se o ACK se perdeu, o reenvio devolve `DUPLICATE` e o mesmo
  ACK.
- **Falha antes do commit** (banco, Redis ou API indisponíveis): erro 5xx **sem ACK**. O Agent reenvia os mesmos fatos.
- **`declaredMissing`:** números que o Agent sabe que nunca vai entregar. Por exemplo, a lacuna local `AGT-OBS-009`: o
  envelope `EVENT_GAP` gravado depois indica a perda. Esses números ficam registrados como ausentes na origem. A completude
  vira `INCOMPLETE`, e o intervalo nunca é apresentado como recebido. Um número já gravado não pode ser declarado ausente
  (`CONFLICT`).

**Limites:**
- até 500 eventos e 2 MiB por lote;
- até 25.000 eventos por execução (o Agent guarda no máximo 20.064);
- taxa por organização: 600 lotes por minuto, limitador no Redis. Com o Redis indisponível, a resposta é 503 sem ACK.

**Ordem:** sempre por `sequence`, a ordem de observação do Agent. `timestamp_utc` e `observed_utc` são preservados como
vieram. O relógio nunca ordena.

## 9. Completude e situação

Quatro dimensões, sempre separadas nas consultas:

| Dimensão | Valores | Fonte |
|---|---|---|
| Processo | `NOT_STARTED` (READY), `STARTING`, `RUNNING`, `ENDED` | relatório do supervisor |
| Resultado | `PENDING` (não terminal), `KNOWN` (SUCCEEDED, FAILED, CANCELLED, TIMED_OUT, BLOCKED), `UNKNOWN` (OUTCOME_UNKNOWN) | relatório do supervisor |
| Transporte | `CONNECTED` / `DISCONNECTED` e o último contato | heartbeat e chamadas da máquina |
| Histórico | `COMPLETE`, `PENDING`, `INCOMPLETE` + motivos | eventos e declarações |

**Motivos de histórico (cada um com origem distinta):**
- `DELIVERY_PENDING`: atraso. Há números ainda não entregues, ou a coleta não terminou.
- `LOST_AT_SOURCE`: o Agent declarou perda na origem dele. Envelopes `EVENT_GAP` com `AGT-OBS-005` (limite do histórico) ou
  `AGT-OBS-006` (limite do supervisor), `SOURCE_REPLACED` ou `SOURCE_UNSAFE`.
- `LOST_LOCAL`: perda local do Agent. `EVENT_GAP` com `AGT-OBS-009`, ou números em `declaredMissing`.
- `PURGED_LOCAL`: expurgo no Agent antes da entrega (`HISTORY_PURGED`, `AGT-OBS-010`).
- `INVALID_REFUSED`: eventos recusados pela API ou pelo Agent (`EVENT_INVALID`).
- `PURGED_SERVER`: expurgo no servidor. **Reservado.** Esta versão não expurga eventos do Agent, e a retenção do servidor
  continua pendente (§11).

`COMPLETE` exige o `COLLECTION_COMPLETED` recebido, `storedThrough` igual à sua `sequence` e nenhum motivo. Uma execução sem
eventos do Framework aparece com as etapas indisponíveis (`stepsAvailable: false`).

## 10. Consultas (Platform)

Sob `automation.read` no workspace da execução, com paginação por cursor estável:
- `GET /api/v1/o/{org}/agent-runs`: lista ordenada por (`registered_at`, `agent_run_id`), com filtros `workspaceId`,
  `automationId`, `state`, `from` e `to`;
- `GET /api/v1/o/{org}/agent-runs/{runId}`: correlação (`runId`, `agentExecutionId`, `frameworkExecutionId`,
  `platformExecutionId`, `agentId`, `workerId`, `workspaceId`, `automationId`, `automationVersionId`, `authorizationId`),
  origem (`LOCAL_AUTHORIZED`), estado do supervisor e código, as quatro dimensões do §9 com os motivos, último contato,
  contagens do supervisor e das transações vistas nos eventos, e conflitos;
- `GET /api/v1/o/{org}/agent-runs/{runId}/events?afterSequence=&limit=`: etapas reais em ordem de `sequence`, só com os campos
  do envelope (que já não tem texto livre);
- administração: `GET /api/v1/o/{org}/agents`, `/agents/{id}`, `/workers`, `/workspaces/{ws}/local-run-authorizations`.

**Não expostos:** stdout e stderr (o Agent nem os guarda), segredos, caminhos, conteúdo de negócio e o hash da credencial.
O **Portal externo não ganha rota nova** nesta versão: a visão do cliente externo continua só com as execuções da 013. Uma
exposição ao Portal precisa de decisão própria sobre concessões.

## 11. Retenção e armazenamento

| Lado | Regra desta versão |
|---|---|
| Agent | o atual: 50 execuções e cota de 96 MiB, sem fila de saída. **Precisa** (§12) de retenção que não apague o que não teve ACK, e de declarar em `declaredMissing` o que perdeu |
| API | os eventos ficam enquanto houver espaço. Um erro de gravação (inclusive espaço esgotado) dá 5xx sem ACK, e o Agent continua com os fatos. O expurgo por política de retenção (010) **não** cobre `agent_run_events` nesta versão, e a consolidação diária de bytes da 011 (`close_usage_day`) não conta esses eventos. Os dois ficam como pendência explícita |

## 12. Encaminhamento ao Agent (bloqueio da integração real)

1. **Repositório e commit:** `Nekkora-Agent` `f77e00e` (local; o remoto está vazio).
2. **Ausente:** cliente HTTPS e toda a parte da máquina deste contrato:
   - resgate do pareamento com o segredo gerado localmente e o armazenamento protegido da credencial;
   - heartbeat;
   - download das autorizações;
   - registro da execução com `authorizationId`;
   - envio dos relatórios do supervisor;
   - fila de saída do `atividade.jsonl` com reenvio desde `settledThrough`;
   - `declaredMissing` para as lacunas `AGT-OBS-009`;
   - retenção atrelada ao ACK.
   
   O `ArchitectureTest` proíbe rede no código de produção: é preciso uma exceção restrita a um pacote novo.
3. **Evidência:** `ArchitectureTest.noNetworkOrShellUsageInProductionCode` e a observabilidade §8 ("Nada disso existe
   hoje").
4. **Alteração mínima:** pacote `com.nekkora.agent.platform` com:
   - um cliente HTTPS (`java.net.http`, TLS do sistema, sem proxy implícito);
   - uma credencial guardada com DPAPI do usuário;
   - um transportador que lê o `atividade.jsonl` pela posição e envia lotes;
   - o relatório do supervisor a cada transição.
   
   O `ExecutionController` não muda de semântica.
5. **Exemplos seguros:** `docs/relatorios/2026-10-28-platform-agent-exemplos/` (sem segredos reais).
6. **Testes de aceite:**
   - um pareamento repetido com resposta perdida não tranca a máquina;
   - lote reenviado depois de ACK perdido;
   - Agent reiniciado no meio de uma entrega;
   - perda local declarada;
   - credencial revogada para tudo;
   - nada é relançado por falha de transporte.
7. **Pronto na API:** este contrato inteiro, com testes de integração e o simulador `api/agent-sim`.
8. **Pausado:** despacho remoto, cancelamento remoto e agendamento, que dependem de um canal de comando no Agent. O
   desenho está em §14. Pode continuar: tudo do lado da API que não depende de comandos.

## 13. Proposta (não implementada): pacote local sem versão publicada

Representação própria `LOCAL_UNPUBLISHED`:
- registro com a impressão do pacote e o gerador declarado, **sem** `automation_version_id`;
- autorização local explícita por Worker e impressão;
- na API, as execuções aparecem marcadas como "pacote não publicado".

Impacto: um novo tipo de autorização e consultas que aceitam execução sem versão. Fica para decisão da titular.

## 14. Despacho, cancelamento e agendamento (desenho para a fase seguinte)

**Despacho:**
- comando com `command_id` estável, puxado pelo Agent (`GET /agent/api/v1/commands`);
- recebimento confirmado (`RECEIVED`) separado do início (`STARTED`, com o `agent_execution_id`);
- lease só para reentrega do comando, nunca como prova de fim;
- o Agent precisa de um registro local por `command_id` para recusar comandos repetidos.

**Cancelamento:** é um pedido. O cancelamento efetivo só é informado com o relatório `CANCELLED` do supervisor daquela
execução.

**Agendamento:** usa as ocorrências da 009 com a política atual (`LATE` → `SKIPPED`), sem compensação múltipla.

**Bloqueio:** tudo isso depende do canal de comando no Agent (§12).

## 15. Versões e recusas

- Cabeçalho `Nekkora-Agent-Contract: 1.0` em toda chamada da máquina. Esta API aceita a versão maior 1. Outra versão maior
  recebe `400 unsupported_contract`.
- Envelope 1.0.x estrito; 1.y tolerante, como no Agent.
- `agentVersion`: texto `[0-9A-Za-z.+-]{1,40}`, só informativo.
