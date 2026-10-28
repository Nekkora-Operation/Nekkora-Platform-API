# Platform–Agent: relatório da entrega do primeiro marco (lado da API)

**Status: API validada; integração real pendente da dependência identificada (transporte no Agent).**

O Agent atual (`Nekkora-Agent` `f77e00e`, só local) não tem cliente de rede: o `ArchitectureTest` proíbe rede no código de
produção, e a observabilidade §8 diz que o transportador "não existe hoje". Por isso nenhuma execução do **Agent real**
chegou à API pelo canal. O que está provado:
- a API inteira contra o contrato, com testes unitários, de integração e E2E entre processos;
- um Agent **simulado** alimentado pelos **registros reais** do Agent empacotado (sucesso, cancelamento e queda da missão
  COMPAT-001).

O encaminhamento ao Agent está no contrato, §12. A consolidação pedida pela titular em 28/10 está no §9 deste relatório.

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
| Repetição do resgate depois de rotação ou revogação: recusada, sem reativar credencial, desfazer rotação nem restaurar associação | `AgentGatewayIT.repeticaoDoResgateNaoDesfazRotacao…` | I |
| Impressão do pacote: vetor da regra `package-fingerprint/1` (CRLF, binário, texto canônico) | `PackageFingerprintVectorTest` (2) | U |
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
| Revogação do Agent encerra credencial, associação e códigos abertos; o Worker aceita um novo pareamento (recuperação da credencial perdida) | `AgentAdminIT` | I |
| Autorização local: Worker autorizado, versão publicada, impressão declarada na versão (`agent_package`); versão só com `artifact.sha256` recusada | `AgentAdminIT` | I |
| Consultas: correlação, quatro dimensões, etapas por sequence com cursor, isolamento por workspace e por organização | `AgentAdminIT` | I |
| Rotas = OpenAPI, com os códigos exercitados | `AgentContractTest` (2), `GatewayContractTest` (1) | U |
| Migration: instalação, atualização populada, recusa sem a 024, falha no meio, reaplicação, privilégios, RLS, gatilhos (inclusive o `sha256` dos bytes do artefato recusado como impressão) e 8 contraprovas | suíte `db/tests/canal-agent-025` | banco |
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

## 7. Decisões da responsável (parecer de 28/10)

| Id | Decisão | Encaminhamento da titular | Situação |
|---|---|---|---|
| D-PA-1 | ordem de merge | #26 antes deste PR, conferindo que o #26 leva a 023, a 024 e a recuperação operacional à `main`. Depois, atualizar a base e os checks deste PR | conferência no §9.4 |
| D-PA-2 | identidade do pacote | exigir correspondência, mas definir o que o hash mede. A impressão do Agent mede conteúdo normalizado, e `artifact.sha256`, bytes. Só seriam iguais se medissem a mesma coisa. Sem mudanças no Studio para acomodar uma equivalência não demonstrada | **feito** (§9.1): as duas medidas foram separadas |
| D-PA-3 | rotação administrativa | adequada para este bloco. Documentar a recuperação da credencial perdida, que exige novo ato autorizado sem contornar a autenticação | **feito** (contrato, §4.3; teste em `AgentAdminIT`) |
| D-PA-4 | pacote sem versão publicada | fora deste bloco. A exigência atual fica, e o registro de execução local não publicada será tratado à parte | mantido |
| D-PA-5 | Portal externo | fatia posterior, com as concessões existentes. Sem ampliar a exposição agora | mantido |
| D-PA-6 | retenção e uso diário | continuar na API agora, em PR próprio, antes de habilitar ingestão contínua | PR próprio, em seguida a este |
| D-PA-7 | estados para o despacho | decidir junto com o contrato de comandos. Sem acrescentar `OUTCOME_UNKNOWN`, `TIMED_OUT` e `BLOCKED` ao mesmo enum: resultado, prazo e impedimento podem ser dimensões diferentes | pausado com a fase de comandos |
| D-PA-8 | transporte no Agent | encaminhar agora, com a correção do schema OBS e a revisão pontual da regra arquitetural que proíbe rede | contrato, §12 reescrito |

## 8. Testes

| Rodada | Resultado |
|---|---|
| Regressão completa local (`./mvnw -o verify`, Windows, commit `2a49760`) | core 21 + ops 182 + platform 447 + portal 12 + agent-gateway 29 = **691**, 0 falhas, 0 ignorados, BUILD SUCCESS |
| E2E entre processos (`api/e2e/run.sh`, 001–025) | **29/29**: AgentChannelE2E 5, AccessInvitationsE2E 5, CrossProcessE2E 6, IdentityCredentialsE2E 7, LinkedAccountsE2E 6 |
| Suíte `db/tests/canal-agent-025` | OK: A, B0–B3, C1–C3 (22 recusas e 5 controles positivos) e C5 (8 contraprovas). Depois da consolidação (§9): 23 recusas |
| Instalação limpa 001–025 (`aplicar_como_migrador.sh`) | `aplicadas=25 estado=0\|0\|0`, a 025 `elevada=nao`, invariantes da 016 depois da retirada OK |

As contagens do CI por commit estão no PR.

## 9. Consolidação de 28/10 (parecer da titular)

O parecer pediu três pontos inequívocos antes de o contrato ir ao Agent. As mudanças estão neste PR. Os testes acompanham os
pontos, sem repetir a regressão completa.

### 9.1 Impressão do pacote (D-PA-2)

- **Achado:** a impressão do Agent (`ExecutionPackage.fingerprint`) é o SHA-256 de um texto canônico. Ele contém a regra, o
  tipo, o hash do descritor e os hashes declarados dos arquivos listados, com `CRLF` trocado por `LF` no texto UTF-8. Ela
  **não** mede os bytes de um arquivo distribuído. Igualá-la a `artifact.sha256` (bytes do artefato, 005) estava errado.
- **Mudança:**
  - a versão declara a impressão em `command_json.agent_package {fingerprint_rule, fingerprint}`;
  - a autorização guarda `package_fingerprint` e `package_fingerprint_rule`, que só aceita `nekkora-agent/package-fingerprint/1`;
  - o gatilho confere os dois valores contra a versão;
  - o canal devolve `packageFingerprintRule`;
  - `artifact.sha256` não é mais comparado.
  - A 025 ainda é candidata e não foi aplicada em ambiente preservado. O diff de catálogo previsto ganhou duas linhas
    aditivas (607 → 609).
- **Regra e vetores:** contrato, §6.4. Os dois vetores (sintético `cada6a64…` e pacote real do Studio `5664f1a5…`) dão o mesmo
  resultado em três implementações:
  - a função do próprio Agent, chamada sobre as classes compiladas sem alterar o repositório;
  - a referência Java do `PackageFingerprintVectorTest`;
  - uma reimplementação independente.
- **Studio:** nenhuma mudança. A regra usa só o que o pacote gerado já contém.
- **Provas:**
  - `PackageFingerprintVectorTest` (2);
  - `AgentAdminIT`: uma versão só com `artifact.sha256`, mesmo igual à impressão, é recusada;
  - suíte da 025, caso `C3_autorizacao_sha_do_artefato`;
  - fixtures com `artifact.sha256` deliberadamente diferente da impressão.

### 9.2 ACK e lacunas

- O código já se comportava como pedido. Faltava deixar inequívoco no contrato (§8.1 a §8.3):
  - `storedThrough` mede recebimento, e `settledThrough` é o cursor de entrega;
  - `declaredMissing` é perda **definitiva**, irrevogável, nunca atraso;
  - quando o Agent pode descartar a cópia local, inclusive nos casos `INVALID` e `CONFLICT`.
- **Testes já existentes:**
  - `AgentGatewayIT.lacunaDeclarada…`: evento tardio de número declarado recusado e declaração sobre evento gravado recusada;
  - `…ForaDeOrdemComAckPerdido…`.

### 9.3 Repetição do resgate (falha corrigida)

- **Falha real:** depois de uma rotação ou de uma revogação, repetir o resgate com o código e o segredo antigos ainda
  devolvia `200` com o vínculo antigo. Nada era reativado, porque a autenticação de cada chamada recusaria a credencial.
  Mesmo assim, a máquina receberia um "sucesso" enganoso.
- **Correção:** a repetição passa pela mesma autenticação do §4.4, com o segredo provando a posse. Ela só devolve o vínculo
  enquanto a credencial emitida ainda vale; senão, `401` uniforme.
- **Teste:** `AgentGatewayIT.repeticaoDoResgateNaoDesfazRotacaoNemReativaCredencialRevogadaNemRestauraAssociacao`. Ele
  cobre a rotação, a repetição da rotação, a revogação e o estado final conferido no banco.

### 9.4 Testes desta consolidação

| Rodada | Resultado |
|---|---|
| Gateway (`AgentGatewayIT`, `AgentGatewayLimitsIT`, `ObsEnvelopeTest`, `SupervisorReportTest`, `GatewayContractTest`, `PackageFingerprintVectorTest`) e Platform (`AgentAdminIT`, `AgentContractTest`) | gateway **32** (`AgentGatewayIT` 13, `AgentGatewayLimitsIT` 2, `ObsEnvelopeTest` 8, `SupervisorReportTest` 6, `GatewayContractTest` 1, `PackageFingerprintVectorTest` 2) e Platform **7** (`AgentAdminIT` 5, `AgentContractTest` 2), 0 falhas |
| Suíte `db/tests/canal-agent-025` | OK. Uma rodada intermediária falhou **antes** da 025, na massa antiga da 001–012: o relógio da VM do Docker recuou cerca de 0,35 ms entre duas chamadas a `clock_timestamp()`. A rodada seguinte, sem mudança, passou |
