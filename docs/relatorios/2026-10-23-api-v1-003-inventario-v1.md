# Inventário restante da V1 (missão PLATFORM-API-V1-003, item 8)

**Data:** 2026-10-23. **Base:** `main` `bf977d7` mais as PRs desta missão (correção da 021, homologação 016–021 e ativação 022), ainda sem merge. **Inventário anterior cruzado:** `2026-10-02-api-v1-inventario-rotas.md` (GAP, D-INV) e o levantamento e a consolidação de 2026-10-11 (`2026-10-11-v1-platform-pendencias-levantamento.md`, `2026-10-11-v1-platform-consolidacao-decisoes.md`, pacotes `v1-platform-*-20261011T*.zip` da responsável).

**Decisão vigente: D-V1-0 = opção B.** A V1 é a V1 completa. GAP-07, GAP-08 e GAP-09 (execução, agendamento e Workers, e o canal do Agent) continuam necessários. **A V1 não está completa** e não pode ser declarada completa sem a integração com o Agent.

Legenda do estado: **feito** (código e evidência na `main`); **PR** (feito numa PR desta missão, sem merge); **parcial**; **aberto**; **decisão** (falta decisão de produto antes de implementar).

## 1. Lista única

### 1.1 Identidade, acesso e organizações

| Requisito | Origem | Implementação existente | Evidência | Pendência | Dependência | Critério de conclusão |
|---|---|---|---|---|---|---|
| Cadastro web e confirmação pela titular | missão 1 (017) | `/api/v1/signup*`, `identity_*` | `SignupIT`, `IdentityLoginIT`; J1 no ensaio | **feito** (chave desligada fora de ambientes descartáveis) | e-mail real (GAP-10) para ligar fora do ensaio | e-mail real homologado e chave ligada por decisão |
| Entrada geral, MFA e step-up da identidade | missão 1 (017), P1 | `/api/v1/auth/*` | ITs; J2 | **feito** | — | — |
| Recuperação e substituição do MFA global | missão 002 (#10) | modos explícitos de `enroll/*` | `IdentityFactorReplacementIT`; J3 | **feito** | — | — |
| Troca de senha e regeneração de códigos | missão 002 (#11, 019) | `/auth/password`, `/auth/mfa/recovery-codes` | ITs; J4 | **feito** | — | — |
| Recuperação de senha por e-mail | missão 002 (#12, 020) | `/auth/password-reset/*` | ITs; J5 e J10 (concorrência) | **feito** (chave desligada fora do ensaio) | GAP-10 | e-mail real homologado |
| Criação, listagem e troca de organização | P2, missão 1 | `/organizations`, `/me/organizations`, `/sessions/organization` | ITs; J6 | **feito** | — | — |
| Vinculação de conta antiga | P3 | `/links/*` | `AccountLinkIT`; J7 | **feito** | — | — |
| Step-up por organização em sessão vinculada | P4 | `/o/{org}/auth/mfa/step-up` | `LinkedOrgStepUpIT` | **feito** | — | — |
| Capacidade inicial zero e estado "aguardando ativação" | §5 do plano da 002; missão 003 item 5 | 022, `activationState` | `db/tests/ativacao-022`; `OrgActivationIT`; `IdentityOrganizationJourneysIT` | **PR** | merge da PR; valores comerciais (decisão de produto) | merge; decisão dos valores padrão da ativação (se houver) |
| Ativação operacional auditada | idem | `org_activation_activate`, CLI `ativar-organizacao` | `OrgActivationIT` (6) | **PR** | merge; linha de elevação da 022 para levar a um ambiente | merge e homologação num ambiente com a 022 |
| Mudança de capacidade depois da ativação | decorrente do item 5 | — (o provisionamento existente volta a valer depois de ATIVADA) | — | **decisão** | produto | ato `org_activation_revise` auditado, ou registro de que o provisionamento basta |
| **Convite e onboarding de contas na organização** (D-INV-02) | inventário §6; F6b | **nenhuma rota**: contas novas só por provisionamento ou pela própria identidade (cadastro) | as jornadas J7/J8 precisaram de PREP (`semear_api_v1.sql`) | **decisão** | D-INV-02/03; GAP-10 | decisão; se (b), rotas de convite com e-mail real |
| **Emissão do token de verificação de e-mail da conta** (GAP-04, D-INV-03) | inventário | só a confirmação (`/o/{org}/auth/email-verification/confirm`); nenhum emissor na Platform | `EmailVerificationRouteIT` (token semeado) | **decisão** | D-INV-02/03 | decisão; se mantida, emissão com testes e OpenAPI |
| **Atribuição e remoção de papéis** (GAP-14, D-INV-09, F6b) | inventário; consolidação §2 | **nenhuma rota** (só leitura de membros e permissões) | OpenAPI | **decisão** (recomendado: V1 completa) | D-INV-09 | rotas com step-up, regra de "último ORG_ADMIN" da 003, testes e OpenAPI |
| **Clientes e concessões** (GAP-15, D-INV-10, F6b) | idem | leitura (`/clients`, `/client-grants`) e **revogação** (`/client-grants/{grant}/revoke`); sem criação de cliente, conta externa, concessão, aprovação ou revisão | `AdminReadRoutesIT`, `ClientGrantRevokeRouteIT` (GAP-01) | **decisão** (recomendado: V1 completa) | D-INV-10; convite; GAP-10 | rotas do ciclo de acesso externo em mini-missões |
| Suspensão e reativação de conta | contrato 8.7 | `/o/{org}/users/{user}/suspend` e `/reactivate` | ITs; J8 (pela API) | **feito** | — | — |
| Retirada de vínculo e suspensão de organização | — | **sem rota** (só banco/provisionamento) | J8 (PREP) e `IdentityOrganizationJourneysIT` (reação) | **decisão** | F6b; operação | decidir se entram na V1 como rota administrativa ou operacional |
| Sessões individuais (GAP-13, D-INV-08) | inventário | revoke-all existe | — | **decisão** (recomendado: fora da V1) | — | decisão registrada |

### 1.2 Execução, agendamento e Agent (Marco 2: necessários na opção B)

| Requisito | Origem | Implementação existente | Pendência | Dependência | Critério de conclusão |
|---|---|---|---|---|---|
| **GAP-09 canal do Agent** (D-INV-04) | inventário; Radiant §9/§16 | **banco pronto em parte**: `workers`, `agents`, `agent_credentials` (004), associação Worker↔Agent e Worker↔workspace; papéis `nekkora_ingestion` (sem serviço) e barreira de ingestão (012). **Nenhuma rota, serviço ou credencial de Agent na API.** O Agent desktop (`Nekkora-Agent`, DESKTOP-001) executa localmente e diz "Execução pela Platform: Indisponível nesta versão" | **desenho** (decisão D-INV-04) antes de qualquer rota | contrato com o Agent (§3) | desenho aprovado; canal implementado com testes, OpenAPI e Agent real |
| **GAP-07 execução manual e cancelamento** | idem | banco: `executions` (006) com transições e motivos, eventos e logs (007), parâmetros e segredos (008), limites e admissão (010: WORKERS_REGISTERED, CONCURRENT_EXECUTIONS, PENDING_EXECUTIONS, agora com a ativação da 022). API: só **leitura** (`/o/{org}/executions*`, GAP-06) | rotas de criação e cancelamento; despacho ao Agent | GAP-09; ativação (022) para haver capacidade | criação e cancelamento com idempotência (`request_key`), admissão pelos limites e testes HTTP |
| **GAP-08 workspaces, agendamentos e Workers** | idem | banco: agendamentos e ocorrências (009, `process_schedule_occurrence`), Workers (004). API: leitura de workspaces | rotas de gestão; serviço de agendamento (papel `nekkora_scheduler`, sem serviço) | GAP-09 (para Workers); D-INV-05 (publicação de versões) | mini-missões com testes HTTP e OpenAPI |
| Publicação de versões de automação (D-INV-05) | inventário | sem rota | **decisão** (recomendado: fora da V1, Studio em fase própria) | — | decisão registrada |

### 1.3 Trilha transversal (gates de produção)

| Requisito | Origem | Situação atual | Pendência | Critério de conclusão |
|---|---|---|---|---|
| **E-mail real** (GAP-10) | inventário; missão 002 PR 5 | transporte Graph e fila persistente **feitos** (021, revisão 2 na PR desta missão). Homologação real **não** feita | ações externas autorizadas (`2026-10-23-api-v1-003-email-real-preparacao.md`) | F1–F6 do relatório do e-mail com evidência real |
| **Portões da 016** (5b/hml03 → P5 → ativação operacional da 014/016) | consolidação §6 | a 016 roda nas suítes e em ensaios. No `ensaio-api-v1-002`, 016–020 com elevação vinculada e pré-voo (PR). **Nenhum portão operacional executado**; produção sem 014/016 ativadas | executar na ordem, cada um com autorização própria; aprovar a proposta de elevação 016–020 (PR) | portões executados com evidência; decisão da topologia de produção (016 rev. 5: só o modelo do migrador declarado é suportado) |
| **Origem do cliente atrás do proxy** (B10) | levantamento B10 | `ClientIpResolver` aceita `X-Forwarded-For` **só** de `NEKKORA_PROXIES_CONFIAVEIS`, e o gateway de homologação sobrescreve o cabeçalho com `$remote_addr`. **Confirmado de novo no ensaio:** no Docker Desktop, todo cliente chega como o IP da ponte (`10.252.99.0/24` nos logs, truncado) e divide os limites por IP (R1, R7, R12…) | depende da **topologia de borda de produção** (CDN/balanceador → nginx → Platform), ainda não decidida | nginx com `set_real_ip_from` restrito aos endereços do balanceador e `real_ip_header`, a Platform confiando só no nginx, e prova com dois clientes de IPs distintos |
| **L06** (lista de execuções sem filtro, ≈ 886/814 ms contra 200/500 ms) | GAP-06 (DC-07) | índice recomendado, **não aplicado** | **decisão D-L06**: critério de desempenho da V1 ou não | (a) migration de índice com portão e nova medição; ou (b) aceitação documentada com o padrão de 30 dias |
| Saúde e prontidão (GAP-11, D-INV-07) | inventário | — | **decisão** (recomendado: interna, P3) | decisão; endpoint interno se (a) |
| Capacidade sob carga | decisões 8.6/8.7/8.10 §7 | gate aberto | medição | medição com perfil declarado |

### 1.4 Web (fora desta missão; listado para a V1 completa)

Sem alteração de estado por esta missão, conforme a consolidação de 2026-10-11:
- **Marco 1:** F7 portal (P2), troca de senha e revoke-all (P5), F8 reexecutável contra a API real com N6–N8 (P3/P4), rastreabilidade (G1/G2), Q2 intermitência da F5, fonte da lib (D-M1-7), acessibilidade e contraste (D-M1-8).
- **Condicionados:** F6b (D-INV-09/10), aviso emergencial no web (D-M1-1), emissão da verificação (B1).

**Contratos que a web precisa e que esta missão entregou ou manteve:**
- `activationState` e `organizationActivationState` (PR da 022);
- os caminhos de página dos e-mails, sem mudança: `/cadastro/confirmar`, `/recuperar-senha`, `/o/{org}/recuperar-senha`, `/o/{org}/mfa-emergencia/confirmar` e `/contestar`.

## 2. Itens reconferidos a pedido da missão

| Item | Conferência |
|---|---|
| **F6b** | Continua não iniciada no backend. A API tem só leitura e a revogação de concessão. Depende de D-INV-09/10 (recomendação registrada: V1 completa) e, para o convite, de D-INV-02/03 e do e-mail real |
| **Convites** | Sem rota. Mudou o contexto: com a entrada geral (017), uma pessoa cria a própria identidade e organização. **Adicionar alguém a uma organização existente continua só por provisionamento.** Isso apareceu concretamente nesta missão: as jornadas J7 e J8 precisaram de semeadura declarada |
| **Papéis** | Sem atribuição nem remoção pela API (GAP-14) |
| **Concessões** | Só leitura e revogação (GAP-15 aberto) |
| **Gates operacionais da 016** | Nenhum portão executado. Esta missão preparou a elevação 016–020 vinculada a um ambiente descartável, com pré-voo por versão e prova de retirada em sucesso e falha (PR da homologação). Não é a ativação operacional |
| **Origem do cliente atrás do proxy** | Aberto; reconfirmado no ensaio (IP da ponte do Docker para todos os clientes). Depende da topologia de borda |
| **L06** | Aberto; depende da D-L06 |
| **GAP-07/08/09** | Necessários na opção B. Levantamento das dependências do contrato com o Agent no §3 |

## 3. O que depende do contrato com o Agent (preparação da próxima entrega)

O banco já fixa parte do contrato, que a próxima entrega deve respeitar e não reinventar:
- **Workers e Agents** (004): Worker é recurso lógico com status administrativo; Agent é a instalação autenticada (`agent_credentials`, com motivos de revogação). Conectividade vem do heartbeat e ocupação vem das execuções, em campos separados.
- **Execuções** (006): PENDING → STARTING → RUNNING → SUCCEEDED/FAILED/CANCELLED, terminais congelados. Ausência de heartbeat **não** encerra a execução; pedido de cancelamento **não** libera capacidade por si.
- **Eventos e logs** (007): ciclo de vida do processo (5 tipos) e canais AGENT/STDOUT/STDERR. Persistir evento não muda estado (isso é da ingestão futura). Barreira de ingestão e consumo diário (010–012).
- **Comando** (005/006): `command_json` versionado (`contract_version`, artefato com referência e sha256, runtime, entrypoint, argumentos, parâmetros, vínculos de segredo) e `command_snapshot` na execução.
- **Limites** (010 + 022): admissão por WORKERS_REGISTERED, CONCURRENT_EXECUTIONS e PENDING_EXECUTIONS, zerados até a ativação.

**Decisões do desenho D-INV-04** (não inventadas aqui; para a missão de desenho):
1. Transporte e sentido: Agent puxando por HTTPS (consulta longa) ou canal persistente; quem inicia a conexão atrás do firewall do cliente.
2. Autenticação do Agent: certificado de cliente por instalação ou token rotacionado (`agent_credentials` já prevê rotação e revogação); vínculo com o Worker.
3. Lugar da fila e reivindicação: PostgreSQL (`FOR UPDATE SKIP LOCKED`, como o despacho de e-mail da 021) ou outro; posse com prazo e o que acontece com posse vencida durante a execução.
4. Semântica do resultado incerto: o Agent desktop já distingue SUCCEEDED, FAILED e `OUTCOME_UNKNOWN` (sem protocolo de resultado). Hoje a 006 não tem estado "desconhecido"; é preciso decidir como esse caso aparece sem reexecutar indevidamente.
5. Entrega do pacote: referência e sha256 do artefato (`command_json`) contra o pacote local conferido do Agent (DESKTOP-001: "cópia conferida do pacote").
6. Cancelamento: pedido, confirmação pelo Agent (`CANCEL_CONFIRMED`) e o caso do Agent desconectado.
7. Heartbeat: período, o que ele altera e o que não altera (já fixado: não encerra execução nem libera Worker).
8. Ingestão de eventos e logs: limites de tamanho e ritmo, ordem, deduplicação e o papel `nekkora_ingestion`.

**Próxima entrega proposta:** missão de **desenho** do canal (D-INV-04), documento e decisão, sem rota de execução, cruzando o Agent desktop (`Nekkora-Agent`, `docs/desktop-001/arquitetura.md`) com 004–012 e 022. Depois: GAP-09, GAP-07 e GAP-08 em mini-missões.

## 4. Decisões concretas necessárias (sem bloquear o trabalho independente)

| Id | Decisão | Recomendação registrada | Efeito |
|---|---|---|---|
| D-ATV-1 | Valores de capacidade na ativação: cada ativação informa os seus (atual) ou um padrão comercial | cada ativação informa (sem padrão no banco) | sem decisão, o operador escolhe por ativação, com motivo auditado |
| D-ATV-2 | Mudança de capacidade depois da ativação: ato auditado próprio ou provisionamento existente | ato próprio (`org_activation_revise`), mesma auditoria | sem decisão, o provisionamento continua podendo alterar depois de ATIVADA |
| D-ELEV-003 | Aprovar as linhas 016–020 vinculadas ao `ensaio-api-v1-002` | aprovar (necessidade medida, pré-voo e prova de retirada) | sem aprovação, o ambiente fica documentado mas a lista volta a 011–013 |
| D-021-R2 | Aprovar a revisão 2 da 021 (invariante pela ACL) | aprovar (defeito reproduzido, contraprovas) | sem ela, a 021 não aplica em topologia com backup lógico |
| D-INV-02/03/09/10 | Convite, verificação, papéis, ciclo externo | como na consolidação de 2026-10-11 | F6b |
| D-INV-04 | Canal do Agent | missão de desenho própria | Marco 2 |
| D-L06 | Critério de desempenho | — | índice ou aceitação |
| D-BORDA | Topologia de borda de produção (para a origem do cliente) | definir antes da implantação | B10 |
