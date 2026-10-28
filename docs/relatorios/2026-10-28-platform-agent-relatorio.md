# Platform–Agent: relatório da entrega do primeiro marco (lado da API)

**Status: API validada; integração real pendente da dependência identificada (transporte no Agent).**

O Agent atual (`Nekkora-Agent` `f77e00e`, só local) não tem cliente de rede: o `ArchitectureTest` proíbe rede no código de
produção, e a observabilidade §8 diz que o transportador "não existe hoje". Por isso nenhuma execução do **Agent real**
chegou à API pelo canal. O que está provado:
- a API inteira contra o contrato, com testes unitários, de integração e E2E entre processos;
- um Agent **simulado** alimentado pelos **registros reais** do Agent empacotado (sucesso, cancelamento e queda da missão
  COMPAT-001).

O encaminhamento ao Agent está no contrato, §12.

Documentos:
- inventário: `2026-10-28-platform-agent-inventario.md`;
- contrato: `2026-10-28-platform-agent-contrato.md`;
- exemplos: `2026-10-28-platform-agent-exemplos/exemplos.md`.

## 1. Base

- A `main` (`e43b957`) só tem #22, #20 e #21. #23, #24 e #25 foram mesclados nas branches intermediárias **depois** de essas
  branches entrarem na `main`, então a `main` termina na migration 022.
- O PR **#26** integra esse conteúdo aprovado à `main` (merge sem conflito, `validar-config` OK).
- Esta entrega é empilhada sobre o #26 (migration **025**). **Ordem de merge: #26, depois este PR.**

## 2. O que foi entregue

| Peça | Conteúdo |
|---|---|
| Migration `025_canal_agent.sql` | 9 tabelas com RLS forçada (códigos de uso único, presença, autorização de execução local, registro da execução, relatórios do supervisor, eventos imutáveis, ausências declaradas, recusas, auditoria); 6 gatilhos de regra SECURITY INVOKER (autoridade relida, transições do supervisor, terminal congelado, contadores que só avançam, registro igual à autorização); concessões mínimas a `nekkora_platform` e `nekkora_ingestion`. **Sem elevação** (nenhum papel, dono ou SECURITY DEFINER novo) |
| Processo `nekkora-agent-gateway` (porta 8083, `/agent/api/v1`, papel `nekkora_ingestion`) | resgate idempotente (segredo gerado pela máquina), rotação com a credencial atual, heartbeat, autorizações do Worker, registro idempotente, relatório do supervisor (APPLIED, UNCHANGED, STALE, CONFLICT), ingestão em lote com deduplicação, recusa de conflito, ausências declaradas e ACK (`storedThrough`/`settledThrough`) só depois do commit; limite de lotes por organização depois da autenticação; falhas de autenticação por IP |
| Platform (pessoas) | códigos de pareamento e rotação (step-up, auditoria sem o código), cancelamento, revogação do Agent (credencial, associação e códigos abertos numa transação), Worker no workspace, autorização de execução local, listas de Agents e Workers e consultas das execuções do Agent (correlação, quatro dimensões, etapas por `sequence`, isolamento por workspace) |
| OpenAPI | tag "Canal do Agent" em `nekkora-api-v1.yaml` (14 rotas) e `nekkora-agent-v1.yaml` (6 rotas da máquina), com testes de contrato |
| E2E | o processo novo na stack (nginx `/agent/api/v1`, 2 MiB, conexão de 2 s); a jornada `AgentChannelE2E`; a medição `AgentBench` |

## 3. Matriz depois da entrega

| Capacidade | API | Agent | Situação |
|---|---|---|---|
| Pareamento | **implementado e testado** (unitário, integração, E2E) | ausente | API pronta. Agent: encaminhamento (§12) |
| Credenciais | **implementado e testado** (rotação administrativa, revogação imediata, concorrência) | ausente | idem |
| Registro da execução | **implementado e testado** | registro local existe; envio ausente | idem |
| Eventos e ACK | **implementado e testado** com os envelopes reais | envelope local existe; transporte, fila de saída e retenção atrelada ao ACK ausentes | idem |
| Resultado e reconciliação | **implementado e testado** (`OUTCOME_UNKNOWN`, STALE e CONFLICT registrados) | estados e `reconcile()` existem; envio ausente | idem |
| Comandos e cancelamento | **pausado** (desenho no contrato, §14) | ausente | depende do canal de comando no Agent |
| Agendamento | **pausado** | ausente | depende do despacho |

## 4. Provas

**Tipos de prova:** unitário (U), integração da API com banco e Redis reais (I), Agent simulado entre processos (S) e Agent
real (R). **Nenhuma prova R:** o Agent não tem transporte.

| Exigência da missão | Prova | Tipo |
|---|---|---|
| Envelope validado com as regras do Agent; os 1.382 envelopes reais aceitos; forma canônica igual ao conteúdo | `ObsEnvelopeTest` (8) | U |
| Sentinelas de dado sensível recusadas (CPF, cartão, e-mail, caminho, número de pedido, texto livre) | `ObsEnvelopeTest.recusas`, `AgentGatewayIT.entradaMaliciosa…` | U, I |
| Transições do supervisor; terminal sem contradição silenciosa; relatório atrasado; instantes em microssegundos | `SupervisorReportTest` (6) | U |
| Pareamento repetido com resposta perdida; outro segredo recusado; código vencido, cancelado, malformado ou com organização trocada (401 único) | `AgentGatewayIT` | I |
| Credencial separada de cookie e sessão; contrato obrigatório (`unsupported_contract`) | `AgentGatewayIT` | I |
| Rotação com a credencial atual, a anterior revogada na mesma transação, repetição idempotente | `AgentGatewayIT` | I |
| Dois resgates concorrentes no mesmo Worker: um passa e o outro recebe 409 | `AgentGatewayIT` (sincronização por latch, estado final conferido) | I |
| Lote fora de ordem, ACK perdido (DUPLICATE e o mesmo ACK), desfecho só pelo supervisor | `AgentGatewayIT` com os envelopes reais do a1 | I |
| Mesma sequence com outro conteúdo: recusada e registrada com os dois hashes | `AgentGatewayIT` | I |
| Lacuna declarada não conta como recebida; evento tardio de número declarado é recusado; `OUTCOME_UNKNOWN` continua desconhecido | `AgentGatewayIT` com o a5 | I |
| Falha de gravação no meio do lote: sem ACK, nada gravado, e o reenvio entrega | `AgentGatewayIT` (gatilho de teste) | I |
| Revogação concorrente: o lote espera o lock (observado em `pg_stat_activity`) e é recusado depois do commit da revogação | `AgentGatewayIT` | I |
| Isolamento: autorização de outro Worker ou organização recusada; a mesma identidade local em duas máquinas são duas execuções; id da URL divergente | `AgentGatewayIT` | I |
| Limite por organização (429 com `Retry-After`, nada gravado); Redis fora (503, sem ACK, nada gravado) | `AgentGatewayLimitsIT` (2) | I |
| Código: permissões, step-up, auditoria sem o código; Worker ocupado; cancelamento; conta sem permissão | `AgentAdminIT` | I |
| Quem criou o código perdeu a autoridade: o resgate é recusado no banco (42501) | `AgentAdminIT` | I |
| Revogação do Agent encerra credencial, associação e códigos abertos | `AgentAdminIT` | I |
| Autorização local: Worker autorizado, versão publicada, artefato igual ao pacote | `AgentAdminIT` | I |
| Consultas: correlação, quatro dimensões, etapas por sequence com cursor, isolamento por workspace e por organização | `AgentAdminIT` | I |
| Rotas = OpenAPI, com os códigos exercitados | `AgentContractTest` (2), `GatewayContractTest` (1) | U |
| Migration: instalação, atualização populada, recusa sem a 024, falha no meio, reaplicação, privilégios, RLS, gatilhos e 8 contraprovas | suíte `db/tests/canal-agent-025` | banco |
| **Primeiro marco entre processos:** pareamento pela API; transporte interrompido (gateway parado, 502 sem ACK) e retomada; ACK perdido; desfecho e completude COMPLETE pela Platform, sem duplicata; queda com lacuna (UNKNOWN, INCOMPLETE); revogação; nenhum segredo nos logs | `AgentChannelE2E` (5) | S |
| Volume, latência e consumo | `AgentBench` (§5) | S |

## 5. Medição da ingestão

A stack E2E local roda num único host Windows (Docker Desktop): nginx, gateway, PostgreSQL 16 e Redis. **Não representa
capacidade de produção.**

| Item | Valor |
|---|---|
| Agents simulados | 8, em paralelo (cada um parea com o próprio segredo e manda 2 execuções) |
| Eventos | **16.000** (8 × 2 × 1.000), em 64 lotes de 250, pelo nginx com TLS |
| Tempo total | 1.642 ms, ou cerca de 9.700 eventos/s |
| Latência por lote (cliente, ida e volta) | p50 133 ms · p95 168 ms · p99 189 ms · máximo 189 ms |
| Conferência no banco | 16.000 eventos, 16.000 identidades distintas, 16 execuções `SUCCEEDED` com `stored_through` = 1.000 |
| Tamanho de `agent_run_events` | 15 MB para 16.000 eventos (envelope + projeções + índice) |
| Memória (`docker stats`, antes → depois) | gateway 254 → 330 MiB; PostgreSQL 112 → 148 MiB; nginx 3 → 5 MiB |

## 6. Limitações declaradas

1. **Agent real:** sem transporte, a integração real não foi feita (§12 do contrato).
2. **Expurgo e consolidação diária:** o expurgo por política de retenção (010/011) e o `close_usage_day` não cobrem
   `agent_run_events`. O espaço do servidor cresce sem limite automático nesta versão.
3. **Offline:** uma revogação no servidor não interrompe uma máquina desconectada. O fato chega marcado
   (`authorizationStateAtRegistration`).
4. **Pacote sem versão publicada** não é registrável. A proposta está no §13 do contrato.
5. **Portal externo** sem rota nova.
6. **Comandos, cancelamento remoto e agendamento:** pausados (dependem do Agent).
7. **Elevação:** a 025 não precisa de janela. O runner de homologação não foi estendido à 025 nesta entrega. As suítes, os
   testes Java e o E2E aplicam 001–025 pelo modelo do migrador.

## 7. Decisões pedidas à responsável

| Id | Decisão | Recomendação |
|---|---|---|
| D-PA-1 | ordem de merge: #26 e depois este PR | sim (este PR depende da 023/024 para a numeração e das funções de autoridade da 023) |
| D-PA-2 | identidade do pacote: `command_json.artifact.sha256` = impressão digital do Agent (STUDIO_PYTHON) | sim; o Studio e a publicação futura precisam adotar a mesma regra |
| D-PA-3 | rotação da credencial como ato administrativo (código de rotação), não automática | sim: é o que o modelo da 004 permite (revogação com pertinência humana, uma credencial ativa) |
| D-PA-4 | pacote local sem versão publicada (§13) | decidir se entra numa fatia própria |
| D-PA-5 | exposição das execuções do Agent ao Portal externo | fatia própria, com as concessões |
| D-PA-6 | retenção e uso diário de `agent_run_events` | estender o expurgo e o `close_usage_day` numa migration própria |
| D-PA-7 | para o despacho: estados `OUTCOME_UNKNOWN`/`TIMED_OUT`/`BLOCKED` em `executions` e a correlação com `agent_runs` | decidir antes da fase de comandos |
| D-PA-8 | Agent: implementar o §12 (transporte) e corrigir o schema OBS (achado A01) | encaminhar ao repositório do Agent |

## 8. Testes

| Rodada | Resultado |
|---|---|
| Regressão completa local (`./mvnw -o verify`, Windows, commit `2a49760`) | core 21 + ops 182 + platform 447 + portal 12 + agent-gateway 29 = **691**, 0 falhas, 0 ignorados, BUILD SUCCESS |
| E2E entre processos (`api/e2e/run.sh`, 001–025) | **29/29**: AgentChannelE2E 5, AccessInvitationsE2E 5, CrossProcessE2E 6, IdentityCredentialsE2E 7, LinkedAccountsE2E 6 |
| Suíte `db/tests/canal-agent-025` | OK: A, B0–B3, C1–C3 (22 recusas e 5 controles positivos) e C5 (8 contraprovas) |
| Instalação limpa 001–025 (`aplicar_como_migrador.sh`) | `aplicadas=25 estado=0\|0\|0`, a 025 `elevada=nao`, invariantes da 016 depois da retirada OK |

As contagens do CI por commit estão no PR.
