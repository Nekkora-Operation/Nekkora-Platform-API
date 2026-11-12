# PLATFORM-AGENT-V2-004 · T13 — "Desvincular esta máquina" também na Platform

> **Precedência.** A decisão de Nícolly nesta missão substitui a limitação do T06 (PLATFORM-AGENT-V2-002) e do T11
> (PLATFORM-AGENT-V2-003): confirmar o desvínculo no Agent passa a **pedir o encerramento do vínculo na Platform**. A
> credencial da máquina continua sem nenhum poder administrativo: ela só encerra **o próprio vínculo atual**.
>
> Este documento foi escrito **antes** da implementação (protocolo, transições, resposta perdida). Mudanças posteriores
> ficam marcadas com a data.

## 1. Vocabulário

| Termo | No modelo |
|---|---|
| **Vínculo** | Um `agent_id`. Cada pareamento cria um Agent novo (`agents`), uma associação ativa `worker_agent_assignments` (1 Worker ↔ 1 Agent) e uma credencial não revogada (`agent_credentials`). O `agent_id` é a **geração** do vínculo: um vínculo novo do mesmo Worker tem outro `agent_id`. |
| **Operação de desvínculo** | Um `operationId` (UUID) gerado pelo Agent **antes** do primeiro envio e guardado de forma durável; no servidor, `agent_unlink_operations` (uma linha por Agent, imutável). |
| **Encerrado** | `agents.status = 4`, associação `status = 2` (encerrada), credenciais revogadas. Igual ao "Revogar vínculo" da Web, com autoria diferente. |

## 2. O que pertence ao Worker e o que pertence ao vínculo

| Pertence ao **Worker** (fica depois do desvínculo; um novo pareamento usa) | Pertence ao **vínculo** (termina com ele; um novo pareamento NÃO herda) |
|---|---|
| O Worker e a situação administrativa dele (`workers`) | A credencial (`agent_credentials`) |
| Autorizações em workspaces (`worker_workspace_assignments`) | A associação Worker ↔ Agent (`worker_agent_assignments`) |
| Autorizações de execução local dadas pela administração ao Worker (`local_run_authorizations`, escopo Worker + workspace) | Códigos de rotação do Agent (`agent_enrollments` de rotação): ficam inúteis, porque a rotação exige o Agent ativo |
| Versões publicadas e impressões dos pacotes (`automation_versions`) | Pedidos de execução endereçados ao Agent (`run_requests.agent_id`) |
| Histórico: execuções, relatórios, eventos, auditoria | Presença (`agent_presence`) e o histórico de comunicação daquele Agent |
| | **No Agent:** a credencial (DPAPI), `vinculo.json`, o cache de autorizações (`autorizacoes.json`), os pedidos abertos e as **aprovações locais de pacote** (`aprovacoes.json`, apagadas na conclusão: um novo pareamento, inclusive em outra organização, não as herda) |

O desvínculo **não exclui nada**: o Worker continua administrativamente ativo, com os workspaces e o histórico.

## 3. Protocolo

### 3.1 Rota

`POST /agent/api/v1/link/unlink` (gateway; `Authorization: Bearer nka1…`; `Nekkora-Agent-Contract: 1.x`)

```json
{ "operationId": "<uuid gerado pelo Agent>", "agentId": "<agent_id do vínculo que o Agent quer encerrar>",
  "launchedRunRequests": ["<run_request_id aceito e INICIADO localmente, ainda sem registro entregue>"] }
```

- **Autoridade derivada só da credencial**: o servidor acha a credencial pelo id do token e confere o segredo (hash,
  tempo constante). O `agentId` do corpo **não dá autoridade**: é só a conferência "eu quero encerrar ESTE vínculo";
  diferente do Agent da credencial → `400 invalid_request` sem efeito. Não há parâmetro de Worker nem de organização: o
  servidor usa os da credencial.
- `launchedRunRequests` (até 50): pedidos que a máquina **iniciou** mas cujo registro ainda não chegou (desvínculo
  "mesmo assim", com entregas pendentes). Eles não são marcados como "não iniciados" (seria falso).

### 3.2 Decisão do servidor (uma transação, READ COMMITTED)

Ordem de travas igual à da revogação administrativa (Agent → credencial), para não haver impasse:

1. Lê a credencial (sem trava) e confere o segredo. Não confere → `401` (o mesmo de sempre).
2. `SELECT … FROM agents … FOR UPDATE` do Agent da credencial; depois a credencial `FOR UPDATE`.
3. Classifica:

| Situação da credencial e do vínculo | Resposta | Efeito |
|---|---|---|
| Não revogada (válida **ou vencida**), Agent ativo, associação ativa | **200** `UNLINKED` | encerra (passo 4) |
| Revogada **por esta mesma operação** (motivo 5, `operationId` igual) | **200** `UNLINKED` (o mesmo corpo da primeira vez) | nenhum (repetição) |
| Revogada por **outra** operação de desvínculo desta máquina | **409** `conflict` · `link_ended_by_machine` | nenhum |
| Revogada pela **administração** (motivo 3/4) | **409** `conflict` · `link_ended_by_administration` | nenhum |
| Revogada por **rotação** (motivo 1/2: credencial antiga; o vínculo continua com outra) | **401** | nenhum |

   Os dois 409 só são dados a quem prova ter o segredo **daquela** credencial: a própria máquina. Eles dizem apenas que
   o vínculo dela terminou e quem o encerrou; nada de outro Agent, Worker ou organização.

4. Encerrar (só no primeiro caso), tudo na mesma transação:
   - `INSERT agent_unlink_operations` (operação, Agent, Worker, associação, credencial, instante, contagens);
   - revoga as credenciais abertas do Agent: `revocation_reason = 5` (DESVÍNCULO PELA MÁQUINA), `revoked_by_agent_id`
     = o próprio Agent, **sem pessoa**;
   - encerra a associação ativa: `ended_by_agent_id` = o próprio Agent, **sem pessoa**;
   - `agents.status = 4`;
   - pedidos de execução abertos deste Agent: `REQUESTED/DELIVERED` no prazo → `REFUSED` com motivo `AGENT_UNLINKED`;
     vencidos → `EXPIRED`; `ACCEPTED` não iniciado → `NOT_STARTED` (`LAUNCH_NOT_ATTEMPTED`); `ACCEPTED` listado em
     `launchedRunRequests` → **fica** `ACCEPTED` (iniciado de fato; o registro não foi entregue);
   - auditoria: `agent_admin_events` ato 14 (DESVINCULADO PELA MÁQUINA), `actor_agent_id` = o Agent, sem
     `actor_membership_id`. Não finge uma decisão humana.
   - Execuções já registradas (`agent_runs`) **não mudam**: o desfecho conhecido fica como está.
   - Workers, workspaces, autorizações de execução local e histórico **não mudam**.

Resposta 200:
```json
{ "operationId": "…", "outcome": "UNLINKED", "agentId": "…", "workerId": "…", "unlinkedAt": "…",
  "runRequestsClosed": 0, "runRequestsKeptAccepted": 0 }
```

### 3.3 Banco (migration 035, sem elevação)

- `agent_unlink_operations`: RLS ENABLE + FORCE com a política de contexto; `nekkora_ingestion` só SELECT/INSERT;
  `nekkora_platform` só SELECT; linha imutável; **única por Agent** (`agent_id`) e por operação.
- `agent_credentials.revoked_by_agent_id`, `worker_agent_assignments.ended_by_agent_id`: o encerramento tem **uma**
  autoria, pessoa **ou** a própria máquina (CHECK: exatamente uma; a máquina só no próprio registro; motivo 5 só com a
  máquina).
- Privilégios novos de `nekkora_ingestion`, **restritos por gatilho**: `agents.status` (só 2 → 4),
  `worker_agent_assignments (status, ended_at, ended_by_agent_id)` (só ativo → encerrado pela própria máquina) e
  `agent_credentials.revoked_by_agent_id` (só motivo 5). Os três exigem uma `agent_unlink_operations` **deste Agent,
  inserida na mesma transação**. Qualquer outro uso pela ingestão é recusado pelo banco. A invariante da 025 ("a ingestão
  não muda o status do Agent") passa a ser: **só por desvínculo da própria máquina, registrado na mesma transação**.
- `run_requests.decision_reason` ganha `AGENT_UNLINKED`; `agent_admin_events` ganha o ato 14.

## 4. Estados no Agent

```
          confirmar (pessoa)                       200 UNLINKED
 VINCULADA ──────────────────► PEDIDO_GRAVADO ──► ENVIANDO ─────────────► CONCLUÍDO (vínculo removido; Worker livre)
     ▲  cancelar: nada muda          │   ▲            │ 409 ended_by_*  ──► ENCERRADO_NA_PLATFORM (vínculo removido)
     │                               │   │            │ 404 (API antiga) ─► SEM_SUPORTE (desvínculo só local, avisado)
     │                               │   └──retentar──┤ sem rede / sem resposta / 5xx / 429 / 401
     │                               ▼                ▼
     │                         PENDENTE_DE_CONFIRMAÇÃO (durável; sobrevive a reinício)
```

- **PEDIDO_GRAVADO**: a intenção (operação, origem, organização, Agent, Worker, credencial, pedidos iniciados) é gravada
  **antes** do primeiro envio (`plataforma/desvinculos/pedido.json`, sem segredos). Na mesma passagem:
  - nenhum início novo pelo vínculo antigo: a tela, os pedidos do servidor e os **lançadores externos** (que leem o
    disco: `LocalGate`) passam a recusar pelo pedido gravado; o cache de autorizações é apagado; pedidos abertos são
    fechados localmente;
  - a credencial (DPAPI) e o `vinculo.json` **ficam**: são o único material para concluir ou reconciliar a operação;
  - o Agent para de pedir trabalho, de entregar e de enviar presença por esse vínculo. Só a operação de desvínculo sai.
- **Retentativa**: 2 s, dobrando até 5 min (com variação), e `Retry-After` respeitado; continua enquanto o Agent está
  aberto. **Agent fechado não envia nada**; ao abrir, retoma.
- **401 isolado não é prova**: mantém PENDENTE ("a Platform recusou a credencial; o resultado do desvínculo não foi
  confirmado") e continua tentando, espaçado. Só 200 ou 409 do §3.2 encerram.
- **Resposta perdida depois do commit**: a repetição com o mesmo `operationId` e a mesma credencial (agora revogada
  com motivo 5 por esta operação) recebe o **mesmo 200**. Essa exceção à regra "credencial revogada → 401" vale **só
  nesta rota e só para a operação que a revogou**; as demais rotas continuam recusando a credencial revogada.
- **Conclusão** (200 ou 409): remove a credencial, o `vinculo.json`, as autorizações e as aprovações locais de pacote,
  grava o registro do desvínculo (com os dados não entregues, marcados como não entregues) e apaga o pedido.
- **Novo pareamento com operação pendente**: bloqueado, com explicação. Há um só `vinculo.json` por instalação; isolar
  com segurança uma operação antiga e um vínculo novo exigiria duas credenciais lado a lado. A intenção não se perde: ao
  haver conexão (necessária para parear), ela é concluída primeiro.

## 5. Execuções e entregas

- **Execução em andamento** (na tela ou por lançador vivo): o desvínculo é recusado, como no T06. A verificação e a
  gravação do pedido acontecem na thread única do transporte; uma corrida com um novo início é fechada por uma segunda
  verificação **depois** de gravar o pedido (o início também consulta o pedido): ou o início vem antes e o desvínculo
  é desfeito localmente com "execução em andamento", ou o pedido vem antes e o início é recusado.
- **Entregas pendentes**: o diálogo mostra a quantidade e oferece **Aguardar envios** (padrão; nada muda) ou
  **Desvincular mesmo assim**, com a consequência explícita. Prosseguir mantém o histórico local marcado como não
  entregue; nada é reenviado a outro vínculo nem a outra organização.

## 6. Prazos

| O quê | Prazo |
|---|---|
| Envio depois da confirmação (com conexão) | imediato (próximo ciclo do transporte, ≤ 1 s) |
| A Web mostrar "Sem máquina vinculada" | na próxima releitura da lista de Workers: **≤ 30 s** com a página visível (T11), ou ao voltar à aba. Não é o prazo de presença (180 s) |
| Sem conexão | nenhum: o Agent tenta de novo ao voltar a conexão (espaçado até 5 min) |

## 7. Compatibilidade

| | Resultado |
|---|---|
| **Agent novo × API antiga** (sem a rota) | `404` → SEM_SUPORTE: o Agent avisa que a Platform não aceita o desvínculo pela máquina, conclui só localmente (o comportamento do T06) e orienta a revogar em Workers. Não mostra "Worker liberado". |
| **API nova × Agent antigo** | nada muda para ele: o desvínculo continua local (Vinculada · Offline até a revogação). As rotas existentes não mudam. |
| **Revogação administrativa** | igual; preserva o histórico. Concorrente com o desvínculo: um espera o outro (trava do Agent); o segundo recebe "já encerrado" (409 em cada lado). |

## 8. Segurança de escopo (resumo)

- Autoridade = posse do segredo de uma credencial; efeito = só o vínculo **daquela** credencial (Agent, associação e
  credenciais dele), na organização **dela**. IDs do corpo nunca escolhem o alvo.
- Um pedido antigo (outro `agent_id`) nunca atinge o vínculo novo do mesmo Worker.
- Heartbeat, entrega, consulta de pedidos e lançamento atrasados: todas as outras rotas exigem credencial não revogada,
  Agent ativo e associação ativa (`MachineAuth.authenticate`, com `FOR SHARE`); depois do commit do desvínculo, 401.
  Nada ressuscita um vínculo: associação encerrada e credencial revogada são imutáveis (004).
- O banco recusa qualquer outro uso dos privilégios novos da ingestão (§3.3).
