# Contrato Platform–Agent 1.0: exemplos seguros de entrada e saída

Exemplos para quem implementar o lado do Agent (contrato, §12). **Nenhum segredo é real**: os valores `<…>` são
marcadores e os UUIDs são ilustrativos. Os envelopes são do registro real a1 da COMPAT-001 (só códigos, identificadores e
instantes). Toda chamada da máquina leva `Nekkora-Agent-Contract: 1.0`.

## 1. Resgate do pareamento

A pessoa recebeu da Platform `nkp1.<organization_id>.<enrollment_id>.<segredo do código>`. O Agent gera **localmente** o
segredo da credencial (32 bytes aleatórios em base64url, 43 caracteres) e o guarda protegido (DPAPI do usuário) **antes** de
chamar.

```http
POST /agent/api/v1/enrollments/redeem
Nekkora-Agent-Contract: 1.0
Content-Type: application/json

{"code":"nkp1.<organization_id>.<enrollment_id>.<segredo do código>",
 "credentialSecret":"<43 caracteres base64url gerados pelo Agent>",
 "agentVersion":"0.1.0","contractVersions":["1.0"]}
```

```http
200 OK
Cache-Control: no-store

{"organizationId":"7a1c…","agentId":"5e2f…","installationId":"c41d…","workerId":"9b77…",
 "credentialId":"0d3a…","credentialExpiresAt":"2027-01-26T14:35:06Z","contractVersion":"1.0"}
```

A credencial é `nka1.<organizationId>.<credentialId>.<credentialSecret>`. **Resposta perdida:** repetir o mesmo corpo devolve
o mesmo resultado. Outro `credentialSecret` com o mesmo código: `401 {"error":"unauthenticated"}`.

## 2. Heartbeat e autorizações

```http
POST /agent/api/v1/heartbeat
Authorization: Bearer nka1.<organizationId>.<credentialId>.<credentialSecret>

{"agentVersion":"0.1.0","contractVersion":"1.0","capabilities":["RUN_LOCAL","OBS_1_0","SUPERVISOR_REPORT_1_0"]}
```

```json
{"serverTime":"2026-10-28T14:40:00.123Z","credentialExpiresAt":"2027-01-26T14:35:06Z","contractVersion":"1.0"}
```

```http
GET /agent/api/v1/authorizations
```

```json
{"items":[{"authorizationId":"3f90…","workspaceId":"1c2e…","automationId":"8d41…","automationVersionId":"b2c0…",
  "packageFingerprint":"5664f1a56556e8137dc6e62c435485469e18501d853a8f51268cb377ce1c0b3d","validUntil":"2026-11-27T14:30:00Z"}]}
```

O Agent só executa por esta via um pacote cuja impressão digital (sem o prefixo `sha256:`) seja `packageFingerprint`. Ele
guarda a lista para uso offline, dentro de `validUntil`.

## 3. Registro da execução

```http
POST /agent/api/v1/runs

{"agentExecutionId":"ex-6f670400dd5d9a00d9569fdec8d2c4f4","authorizationId":"3f90…",
 "packageFingerprint":"5664f1a56556e8137dc6e62c435485469e18501d853a8f51268cb377ce1c0b3d",
 "supervisor":{"state":"RUNNING","started":"2026-10-28T12:35:05.958158700Z"}}
```

```json
201 {"runId":"e8a4…","created":true,"state":"RUNNING","reportOutcome":"APPLIED"}
```

Repetir devolve `200` com o mesmo `runId`. O mesmo `agentExecutionId` com outra autorização ou outro pacote: `409`.

## 4. Eventos (lote) e ACK

```http
POST /agent/api/v1/runs/ex-6f670400dd5d9a00d9569fdec8d2c4f4/events

{"events":[
 {"schema":"nekkora-agent/observability","version":"1.0.0","agent_execution_id":"ex-6f670400dd5d9a00d9569fdec8d2c4f4","sequence":2,"timestamp_utc":"2026-10-28T12:35:05.896527300Z","observed_utc":"2026-10-28T12:35:06.011323500Z","source":"AGENT","event_code":"STARTING","severity":"INFO","category":"LIFECYCLE","status":"STARTING","correlation":{"source_position":117}},
 {"schema":"nekkora-agent/observability","version":"1.0.0","agent_execution_id":"ex-6f670400dd5d9a00d9569fdec8d2c4f4","sequence":3,"timestamp_utc":"2026-10-28T12:35:05.958158700Z","observed_utc":"2026-10-28T12:35:06.011323500Z","source":"AGENT","event_code":"RUNNING","severity":"INFO","category":"LIFECYCLE","status":"RUNNING","correlation":{"source_position":192}}
]}
```

```json
200 {"runId":"e8a4…","storedThrough":0,"settledThrough":0,
     "results":[{"sequence":2,"status":"STORED","reason":null},{"sequence":3,"status":"STORED","reason":null}],"missing":[]}
```

`storedThrough` 0: a sequence 1 ainda não chegou, e o ACK só avança sobre intervalo contínuo. Quando o lote com a 1 chegar,
os dois números vão para 3. A retomada é a partir de `settledThrough + 1`. O mesmo lote de novo devolve `DUPLICATE` e o
mesmo ACK.

**Perda local declarada:** depois de um `EVENT_GAP` com `AGT-OBS-009`, o Agent declara os números que nunca vai entregar:

```json
{"events":[ … ], "declaredMissing":[{"from":301,"to":320}]}
```

Esses números contam só em `settledThrough`, nunca em `storedThrough`. A completude fica `INCOMPLETE` (`LOST_LOCAL`).

**Recusas por evento** (o resto do lote segue):
- `{"sequence":5,"status":"CONFLICT","reason":"conteudo_diferente"}`: a mesma sequence com outro conteúdo. O original fica.
- `{"sequence":7,"status":"INVALID","reason":"unknown_field"}`: campo fora do envelope 1.0.0, por exemplo `message`.

**Sem ACK:** `5xx`, `429` (com `Retry-After`) e queda da conexão. Nada foi gravado: reenviar os mesmos fatos. **Nunca
reexecutar a automação.**

## 5. Relatório do supervisor

Os campos do `estado.json` 1.0.0, a cada transição e na reconciliação:

```http
POST /agent/api/v1/runs/ex-6f670400dd5d9a00d9569fdec8d2c4f4/supervisor

{"state":"SUCCEEDED","code":null,"exitCode":0,"counts":{"SUCCEEDED":5},"created":"2026-10-28T12:35:04.985233100Z",
 "started":"2026-10-28T12:35:05.958158700Z","ended":"2026-10-28T12:35:14.265516Z","adopted":false,"suspensions":0,"locks":0}
```

```json
200 {"runId":"e8a4…","outcome":"APPLIED","state":"SUCCEEDED"}
```

Um relatório contraditório depois do terminal:

```json
409 {"runId":"e8a4…","outcome":"CONFLICT","state":"SUCCEEDED"}
```

O estado não muda, e o conflito fica registrado. Queda do Agent: `{"state":"OUTCOME_UNKNOWN","code":"AGT-EXE-025", …}`, um
terminal que continua desconhecido.

## 6. Rotação

A pessoa gera o código de rotação na Platform. O Agent gera um segredo novo e chama o mesmo `redeem` **com a credencial
atual** no `Authorization`. A anterior é revogada (motivo 1) na mesma transação. Resposta perdida: repetir com o segredo
novo devolve o mesmo resultado, mesmo que a credencial antiga já esteja revogada.
