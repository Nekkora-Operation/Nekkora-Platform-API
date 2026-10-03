# Proposta de contrato HTTP: GAP-02 (permissões efetivas), GAP-03 (membros e concessões), GAP-06 (leitura interna)

**ESTADO: REVISÃO 2 APROVADA TECNICAMENTE COMO BASE DE IMPLEMENTAÇÃO** (parecer de 2026-10-03, sobre o pacote `b37e7632…`).
- A aprovação técnica **não** substitui decisões de produto e **não** autoriza commit nem publicação.
- O aceite inclui a semântica de ordenação da §1.5.1: nomes iguais nos 48 primeiros pontos de código ficam em ordem de **id**, não pelo restante do nome.
- Na implementação, os testes da §5.6 precisam confirmar o tamanho do cursor com o **serializador Java real** e a continuação das páginas com nomes longos, Unicode e prefixos iguais. A contraprova atual é só do formato.

**Decisões da responsável** (2026-10-03, respostas à pergunta do executor; as opções eram as recomendações da §7):

| DC | Decisão |
|---|---|
| DC-01 | `ORG_ADMIN` com acesso implícito a todos os workspaces da organização, como **política explícita de produto**. Resolve a divergência entre a 003 e o Radiant §6 a favor da regra (a) da §1.2 |
| DC-02 | conta externa recebe **403 `forbidden`** nas rotas internas |
| DC-03 | atribuições explícitas **paginadas** na ROTA-P02, sem limite de 500, sem erro 500 e sem truncar |
| DC-04 (2026-10-03) | `mfaConfigured` na lista de membros: **só o booleano**, sob `org.manage` |
| DC-05 (2026-10-03) | `/client-grants`: filtro `clientId`/`workspaceId` inexistente ou de outra organização → **200 com lista vazia**, como exceção documentada; UUID malformado → 400 |
| DC-06 (2026-10-03) | ROTA-M03 `/clients` **incluída** na GAP-03, com contrato e testes completos |
| DC-07 (2026-10-03) | **medir com volume sintético** antes de fechar a GAP-06 (§6), registrando volume, consultas, planos e resultado. Um índice, se necessário, é migration própria |
| DC-08 (2026-10-03) | `command_json`, `command_snapshot` e `payload` de eventos **fora** |
| DC-09 (2026-10-03) | **solicitante incluído** (`requestedBy`, nulo em execução agendada); criador da automação **adiado** |
| DC-10 (2026-10-03) | `errorSummary` **adiado** até a sanitização ser comprovada |
| DC-11 (2026-10-03) | dados do Worker/Agent **não** nesta etapa |
| DC-12 (2026-10-03) | eventos de execução sem acesso ou inexistente → **404**; a diferença em relação ao portal fica documentada |
| DC-13 (2026-10-03) | logs **fora** da primeira entrega; mini missão posterior |
| DC-14 (2026-10-03) | OpenAPI definitivo em **`api/openapi/nekkora-api-v1.yaml`**, cobrindo também as rotas anteriores; o anexo da proposta deixa de ser a fonte depois da consolidação |
| DC-15 | chave do cursor = **subchave derivada** `HMAC-SHA256(nekkora.keys.hmac, "nekkora/api-cursor/v1")`, sem segredo novo. **Registrado:** a rotação da chave de serviço invalida todos os cursores emitidos |

**GAP-02 implementada em 2026-10-03, para revisão e sem commit:** relatório `2026-10-03-api-v1-gap02-permissoes.md`. Inclui o catálogo, P01/P02, `WorkspaceAccess`, `CursorCodec` e `Paging`; regressão 301/0/0 PERMITIDO. Na rodada corretiva 1, a consistência do catálogo passou a valer em todos os caminhos, com exclusão por ID exato e `validar-config` 184/0; regressão 303/0/0 PERMITIDO.

**GAP-03 implementada em 2026-10-03, para revisão e sem commit:** relatório `2026-10-03-api-v1-gap03-membros-concessoes-clientes.md`, com M01, M02 e M03 e a permissão organizacional compartilhada; regressão 319/0/0 PERMITIDO. Rodada corretiva 1 (só testes: revogação na M03, ordenação composta por data e mensagens sem cursor): regressão 322/0/0 PERMITIDO.

**GAP-06 aprovada tecnicamente em 2026-10-03 (sem commit); desempenho da L06 como pendência conhecida:** relatório `2026-10-03-api-v1-gap06-leitura-interna.md`, com L01–L08 e a medição do DC-07 (L06 acima da referência sem índice; recomendação de migration própria, não aplicada); regressão 331/0/0 PERMITIDO.

**GAP-03 concluída no escopo contratado** (parecer de 2026-10-03, R1). **Todas as DC estão decididas** (DC-14 em 2026-10-03, em resposta à pergunta do executor). As decisões da GAP-06 (DC-07 a DC-13) foram registradas em 2026-10-03, em resposta à pergunta do executor. As decisões da GAP-03 (DC-04 a DC-06) foram registradas em 2026-10-03, em resposta à pergunta do executor.

**Histórico:** a revisão 2 foi entregue como PROPOSTA PARA REVISÃO.
- Nada implementado: sem código, testes, migrations nem commits.
- O OpenAPI proposto está em `2026-10-02-api-v1-contrato-gap02-03-06-anexos/openapi-proposta.yaml` (versão `0.3.0-proposta`).

**Revisão 2:** revisão só documental, a partir do parecer sobre a revisão 1 (pacote `9c3b2c96…`). Não houve testes de banco nem repetição da GAP-01.

| Ponto do parecer | Onde está |
|---|---|
| o servidor podia gerar um cursor que ele próprio recusaria: nome `TEXT` sem limite dentro de um cursor de no máximo 1.024 caracteres | §1.5.1: ordenação por **chave de tamanho limitado** (prefixo de 48 pontos de código + id), id da chave como impressão fixa, campos com tamanho máximo e pior caso calculado (≤ 872 caracteres) |
| "período aberto não aumenta o custo por página" não estava comprovado | §1.6: afirmação retirada; o custo depende da medição do DC-07 |
| "nunca dá erro por causa da mudança" | §1.4: restrito a mudanças que não retiram autorização |
| a matriz compartilhada misturava visibilidade e atribuição explícita | §5.2: duas tabelas de expectativa separadas |
| DC-15: testes de derivação, assinatura inválida, chave desconhecida e rotação; registrar que a rotação invalida cursores | §5.6 e §7 (DC-15) |
| começar pela GAP-02 | §8 |

**Contraprova local do tamanho do cursor:** feita sem banco e com chave aleatória (`ops/homologacao/saidas/apiv1inv-20261002T190454Z/contrato_r2/tamanho_cursor.py` e `.txt`).
- **Revisão 1:** nome ASCII de 1.000 caracteres → cursor de **1.763** caracteres, acima de 1.024, logo recusado. Isso reproduz o achado do parecer, que mediu cerca de 1.720; a diferença vem dos tamanhos dos demais campos.
- **Revisão 2:** o maior cursor observado tem **871** caracteres (48 caracteres de controle, 6 bytes cada no JSON). O limite teórico é **872**.

**Sobre as decisões:** os DC continuam **pendentes**. Esta revisão não registra nenhum como autorizado.

**Revisão 1** (mantida como registro): revisão só documental, a partir do parecer sobre a versão inicial (pacote `c7f59322…`). Não houve testes de banco nem repetição da GAP-01.

| Ponto do parecer | Onde está |
|---|---|
| catálogo completo de permissões (23, não 9) e aplicabilidade organização × workspace | §0.1, §2 |
| paginação sobre o estado atual, autorização reaplicada por página, sem promessa sob mudança concorrente | §1.4 |
| cursor vinculado a organização, rota, recurso pai, filtros e ordenação, com integridade (HMAC) | §1.5, DC-15 |
| DC-03: atribuições explícitas paginadas, sem erro 500 e sem truncar | ROTA-P02 |
| `errorSummary` retirado | ROTA-L07, DC-10 |
| `from`/`to`, UUID malformado, exceção do `/client-grants`, `durationMs`, `/clients`, revogação entre páginas e acesso direto | §1.3, §1.6, §1.7, ROTA-M02, ROTA-M03, ROTA-L06, §5 |
| regra de autorização por workspace compartilhada entre as rotas | §1.2 |
| plano de medição do DC-07 | §6 |

**Sobre as decisões (§7):**
- As recomendações do parecer aparecem como **recomendação da revisão**.
- **Nenhuma** foi decidida pela responsável até esta revisão.
- O contrato foi redigido segundo essas recomendações, mas cada DC continua **pendente de decisão**.

**Base:**
- D-INV-01: primeira entrega = identidade + administração mínima + leitura do produto interno;
- D-INV-06: OpenAPI versionado, revisado a cada mini missão e conferido contra os testes HTTP;
- decisões **da responsável**, respondidas em pergunta do executor;
- inventário API-V1-INV-01 (rodada 3) e GAP-01;
- leitura do commit `2431648f` (= `main` `bd9e96a1`).

**Organização em mini missões:**
- a implementação segue em três mini missões **independentes**: GAP-02, GAP-03 e GAP-06;
- **cada rota autoriza no backend**;
- a consulta de permissões (GAP-02) só ajuda o frontend a apresentar ações e **não** é pré-requisito de GAP-03 nem de GAP-06;
- o componente de autorização por workspace (§1.2) é **um só**. A primeira mini missão que precisar dele o cria; as outras o reutilizam.

## 0. Fatos do código e do banco que sustentam a proposta

| Fato | Fonte | Consequência |
|---|---|---|
| A RLS da 012 isola **só por organização** (`tenant_request_context` com `nekkora.organization_id`) | `012_security_rls.sql` | outra organização fica invisível pelo banco. O acesso **por workspace** precisa ser aplicado pela aplicação |
| **Não existe** função de acesso efetivo por workspace no banco | busca nas migrations 001–016 | a regra (§1.2) é definida neste contrato e implementada na aplicação, num componente único, com teste |
| `permissions` **não tem** coluna de escopo; só `roles.scope` (1 = organização, 2 = workspace) | `003_access_control.sql` linhas 56–78 | a aplicabilidade de cada permissão (organização × workspace) é **definida neste contrato** (§0.1) e conferida por teste contra o catálogo do banco |
| Atribuições: `organization_role_assignments` e `workspace_role_assignments`, com `status` 1 = ativa e 2 = revogada; revogada ⇔ `revoked_at` preenchido | 003 linhas 122–243 | "atribuição ativa" = `status = 1` |
| Papéis: `ORG_ADMIN` (organização); `WORKSPACE_ADMIN`, `OPERATOR` e `VIEWER` (workspace). A 003 descreve o `ORG_ADMIN` como quem "administra a organização e todos os seus workspaces" | 003 | ver **DC-01** |
| `nekkora_platform` tem `SELECT` nas tabelas de identidade, clientes, workspaces, concessões, catálogo e atribuições, automações, versões, execuções, eventos e logs | `012` linhas 912–937 | nenhuma permissão de banco nova é necessária |
| Execuções: `finished_at` preenchido ⇔ estado final (4, 5, 6). `finished_at ≥ started_at` quando ambos existem, e `finished_at ≥ created_at` | `006_executions.sql` linhas 80–92 | regra do `durationMs` (§1.7) |
| Portal: `from` inclusivo, `to` exclusivo (`created_at >= p_from AND created_at < p_to`). Período conferido só com as duas datas, por `Duration.toDays() > 366`. Data sem fuso → 400 | `013` linhas 314–315; `PortalController` | a regra interna (§1.6) é a mesma, com o limite de 366 dias escrito de forma exata |
| Portal: `durationMs` = `finished_at − started_at` em ms (`Duration.toMillis`), nulo se faltar algum | `PortalService` linha 171 | mesma regra (§1.7) |
| UUID malformado em `@PathVariable UUID` ou em parâmetro tipado → `MethodArgumentTypeMismatchException` → **400 `invalid_request`** (`GenericErrorHandler`). Organização malformada no caminho → `Routes.organization` vazia → sem sessão resolvida | `nekkora-core` `GenericErrorHandler`; `SessionFilter`; `Routes` | §1.3 |
| Portal: cursor = base64url de `{"c":createdAt,"i":id}`, **sem integridade** nem vínculo a filtros | `Cursor.java` | o cursor interno é **outro** formato (§1.5). O do portal não muda nesta proposta |
| Existe chave HMAC de serviço (`nekkora.keys.hmac` + `hmacId`, ≥ 32 bytes), usada para identidade no limitador e no log | `PlatformProperties.Keys`; `IdentityHmac` | base possível da chave do cursor. Ver **DC-15** |
| Índices: **nenhum** em `executions (organization_id, workspace_id, created_at)` nem em `automations (workspace_id, …)` | migrations | desempenho com volume é risco. Ver **DC-07** e §6 |

### 0.1 Catálogo completo de permissões (`public.permissions`, migrations 001–016)

| Código | Migration | Papéis que a têm | Aplicabilidade (contrato) |
|---|---|---|---|
| `org.manage` | 003 | ORG_ADMIN | **organização** |
| `workspace.lifecycle` | 003 | ORG_ADMIN | **organização** (criar e arquivar workspaces) |
| `workspace.configure` | 003 | ORG_ADMIN, WORKSPACE_ADMIN | workspace |
| `role.assign_admin_roles` | 003 | ORG_ADMIN | **organização** (atribui ORG_ADMIN e WORKSPACE_ADMIN) |
| `role.assign_operator_viewer` | 003 | ORG_ADMIN, WORKSPACE_ADMIN | workspace |
| `automation.read` | 003 | todos os quatro | workspace |
| `automation.execute` | 003 | ORG_ADMIN, WORKSPACE_ADMIN, OPERATOR | workspace |
| `logs.read` | 003 | ORG_ADMIN, WORKSPACE_ADMIN, OPERATOR | workspace |
| `external_access.manage` | 003 | ORG_ADMIN | **organização** |
| `worker.manage` | 004 | ORG_ADMIN | **organização** |
| `agent.manage` | 004 | ORG_ADMIN | **organização** |
| `agent_credential.manage` | 004 | ORG_ADMIN | **organização** |
| `worker_agent.assign` | 004 | ORG_ADMIN | **organização** |
| `worker_workspace.authorize` | 004 | ORG_ADMIN | **organização** (a 004: "papeis de workspace nao administram maquinas compartilhadas") |
| `automation.manage` | 005 | ORG_ADMIN, WORKSPACE_ADMIN | workspace |
| `automation.publish` | 005 | ORG_ADMIN, WORKSPACE_ADMIN | workspace |
| `workspace_parameter.read` | 008 | ORG_ADMIN, WORKSPACE_ADMIN | workspace |
| `workspace_parameter.manage` | 008 | ORG_ADMIN, WORKSPACE_ADMIN | workspace |
| `workspace_secret.read` | 008 | ORG_ADMIN, WORKSPACE_ADMIN | workspace |
| `workspace_secret.manage` | 008 | ORG_ADMIN, WORKSPACE_ADMIN | workspace |
| `automation_schedule.read` | 009 | ORG_ADMIN, WORKSPACE_ADMIN | workspace |
| `automation_schedule.manage` | 009 | ORG_ADMIN, WORKSPACE_ADMIN | workspace |
| `identity.mfa.reset` | 014 | ORG_ADMIN | **organização** |

**Totais:** 23 permissões, sendo **10 organizacionais** e **13 de workspace**.

**Regra de aplicabilidade:** é definida pelo contrato, porque o banco não guarda escopo de permissão.
- Uma permissão **organizacional** só aparece em `organization.permissions`. **Nunca** aparece em `allWorkspaces` nem em atribuições de workspace.
- Uma permissão **de workspace** pode aparecer nos três lugares.
- **Hoje** os papéis de workspace (003, 005, 008, 009) só contêm permissões de workspace. Nenhuma organizacional foi concedida a WORKSPACE_ADMIN, OPERATOR ou VIEWER.

**Fora do catálogo:** o esquema `ops` (016) tem um modelo próprio de permissões de operador (`ops.ops_operator_permissions`). Ele **não** faz parte de `public.permissions` e **não** aparece nesta API.

**Consistência sem omissão silenciosa:** o catálogo da aplicação (23 códigos com aplicabilidade) é a fonte do enum do OpenAPI.
- **Em execução:** um código de permissão ou de papel que o banco devolva e o catálogo não conheça → **500 `internal_error`**, com log `evento=api.catalogo_desconhecido` (sem dado pessoal). Nunca é omitido nem devolvido fora do enum. Vale o mesmo para uma permissão organizacional encontrada num papel de escopo workspace.
- **Em teste:** um IT de catálogo (§5) compara `public.permissions` e `role_permissions` com o catálogo da aplicação. Uma migration futura que crie permissão ou a conceda a papel de workspace **sem** atualizar o contrato falha na regressão, antes de chegar à rota.

## 1. Regras comuns às três mini missões

### 1.1 Autenticação, CSRF, conta e organização

- Todas as rotas são `GET`, sob `/api/v1/o/{org}/…`, e exigem **sessão válida na organização da rota** (`SessionFilter`).
- **CSRF:** não se aplica (só GET).
- **Limitador:** R7 por sessão.
- **Contas externas:** recebem **403 `forbidden`** em todas estas rotas (DC-02).
- **Membros inativos:** vínculo e conta precisam estar ativos (`organization_memberships.status = 2`, `users.status = 2`). Suspensão revoga as sessões, o que dá 401.

### 1.2 Acesso efetivo por workspace: regra única e componente compartilhado

**Regra.** Um usuário interno com vínculo **M** na organização **O** tem a permissão de workspace **P** no workspace **W** de O se:
- **(a)** M tem atribuição **organizacional** ativa (`organization_role_assignments.status = 1`) cujo papel inclui P. Hoje, só o `ORG_ADMIN`; vale para **todos** os workspaces de O, **se o DC-01 for aprovado**; **ou**
- **(b)** M tem atribuição **de workspace** ativa em W (`workspace_role_assignments.status = 1`) cujo papel inclui P.

P precisa ser de **workspace** (§0.1). Permissão organizacional nunca vale "em W".

**Visibilidade:**
- Um workspace é **visível** a M quando M tem `automation.read` em W.
- Automações, versões, execuções e eventos de W seguem a visibilidade de W.
- Workspaces arquivados ou suspensos continuam visíveis para leitura, com o `status` informado (Radiant §8).

**Componente compartilhado `WorkspaceAccess`** (nome sugestivo). É a **única** implementação da regra, usada por **todas** as rotas desta proposta, inclusive a P01/P02. Nenhum controlador escreve SQL de autorização próprio. Operações:

| Operação | Uso | Resultado sem acesso |
|---|---|---|
| `visibleWorkspacesPredicate(M, P)`: um único fragmento SQL (CTE) parametrizado | listagens sem workspace no caminho (L01, L06) | itens omitidos |
| `requireWorkspace(M, W, P)` | L02, L03 e filtro `workspaceId` da L06 | **404** |
| `requireResource(M, tipo, id, P)`: resolve o workspace da automação ou execução **na organização da rota** e aplica `requireWorkspace` | L04, L05, L07, L08 e filtro `automationId` da L06 | **404**, idêntico para "inexistente", "de outra organização" e "sem acesso" |
| `effectivePermissions(M)` | P01, P02 | — |

**Requisitos do componente:**
- lê sempre o estado **atual** do banco, na mesma transação da consulta da página. Não há cache de permissões entre requisições;
- **consistência do catálogo em todos os caminhos** (listagem, decisão pontual, `requireWorkspace`, P01 e P02), incluída na correção da GAP-02 em 2026-10-03. Vale nos caminhos implementados e testados; as rotas novas devem usar **só** `visibleWorkspacesQuery`, `hasWorkspacePermission` e `requireWorkspace`, porque a constante SQL pública não impede o uso direto. Antes de decidir, o componente confere **todas** as atribuições ativas do vínculo. Papel fora do catálogo ou no escopo errado, permissão desconhecida, ou permissão organizacional em papel de workspace → **500** com log. A inconsistência nunca é escondida e nunca concede acesso, mesmo quando o papel desconhecido traz a permissão pedida;
- a regra (a) entra num só ponto. Se o DC-01 for decidido de outro modo, muda só o componente e os testes da §5.2;
- a matriz de autorização da §5.2 é **um teste parametrizado**, executado contra **todas** as rotas com a mesma massa. Uma rota que se comportar diferente falha nesse teste.

### 1.3 Ordem das verificações e respostas 400, 401, 403 e 404

Cada requisição é avaliada **nesta ordem**. A primeira falha define a resposta:

| # | Verificação | Resposta |
|---|---|---|
| 1 | Sem cookie, sessão inválida, expirada, revogada, de outra organização, ou `{org}` malformado no caminho (nenhuma sessão é resolvida) | **401 `unauthenticated`** |
| 2 | Formato dos parâmetros: UUID malformado no **caminho** (`workspaceId`, `automationId`, `executionId`) ou em **filtro** (`workspaceId`, `clientId`, `automationId`); `limit` fora de 1–100 ou não inteiro; `status` desconhecido, vazio ou repetido além do máximo; data sem fuso ou inválida; período inválido (§1.6); cursor malformado, adulterado ou fora do vínculo (§1.5) | **400 `invalid_request`** |
| 3 | Conta externa | **403 `forbidden`** |
| 4 | Rota com permissão **organizacional** exigida (M01, M02, M03) que o usuário não tem | **403 `forbidden`** |
| 5 | Workspace, ou recurso de workspace, no caminho ou em filtro de rota **por workspace**, inexistente, de outra organização ou sem acesso | **404 `not_found`**, corpo idêntico nos três casos |
| — | Limite R7 (a qualquer momento depois de 1) | **429 `rate_limited`** com `Retry-After` |

**Detalhes:**
- **UUID malformado → 400, e não 404.** É o comportamento atual do produto (`GenericErrorHandler`). Não revela existência, porque um texto que não é UUID não identifica recurso algum.
- A ordem 2 → 3 decorre do Spring, que converte os parâmetros antes do controlador. Ela é **contratual**: uma conta externa com UUID malformado recebe 400. O teste da §5.1 fixa isso.
- **Lista vazia (200):** usuário interno sem nenhum workspace visível em L01 e L06 sem filtro.
- **Exceção da rota M02 (`/client-grants`):** ver a própria rota.
- O corpo de erro é sempre `{"error":"<código>"}` (contrato 8.7 §5.2).

### 1.4 Paginação: semântica

- **Keyset sobre o estado atual.** Cada página é uma consulta nova sobre o estado do banco **no momento da requisição**: os itens **depois** da posição do cursor, na ordenação da rota, que **atendem aos filtros e à autorização atuais**. Não há snapshot entre páginas.
- **A autorização é reaplicada a cada página** (§1.2). O cursor **não é autoridade**: carrega só posição e vínculo (§1.5), nunca permissão.
- **`limit`:** de 1 a 100, padrão 25. Pode mudar entre páginas, porque não faz parte do vínculo.
- **Resposta:** `{ "items": [...], "nextCursor": "<opaco>" | null }`.
  - A aplicação lê `limit + 1` linhas. `nextCursor` só é emitido se havia mais itens **no momento daquela consulta**. Por isso a última página normalmente vem com itens e `nextCursor = null`.
  - Uma página vazia com `nextCursor = null` só ocorre quando os itens restantes deixaram de existir ou de ser visíveis entre as requisições.
- **Ordenação determinística:** campo da rota mais desempate pelo identificador UUID.

**Garantias:**

| Situação | O que o contrato garante | O que **não** garante |
|---|---|---|
| **Estável:** nada muda entre as páginas | cada item visível aparece **exatamente uma vez**, na ordem declarada; a última página tem `nextCursor = null` | — |
| **Com mudança concorrente:** inserção, exclusão, renomeação (o campo de ordenação muda), mudança de `status` com filtro de `status`, concessão de acesso | cada página é **válida, ordenada e autorizada pelo estado atual**; nunca contém item de workspace que o usuário já não pode ver. Mudanças **nos dados que não retiram autorização** (inserção, exclusão, renomeação, `status`, concessão de acesso) não causam erro: o cursor continua aceito | **ausência de repetição ou de omissão**. Exemplos: um workspace renomeado para antes do cursor é **omitido**; renomeado para depois, pode **repetir**; uma execução criada depois da 1ª página, em ordem descendente, **não** aparece nas seguintes; um item que muda de `status` pode sair ou entrar no filtro |

**Revogação entre páginas e acesso direto.** Uma mudança que **retira autorização** pode, sim, produzir erro na página seguinte (401, 403 ou 404), como na tabela abaixo:

| Mudança | Próxima página, ou próximo acesso direto |
|---|---|
| sessão revogada ou vínculo suspenso | **401** |
| perda da permissão organizacional (M01, M02, M03) | **403** |
| perda do acesso a W, em rota com W ou recurso de W **no caminho** (L02–L05, L07, L08) ou no filtro `workspaceId`/`automationId` da L06 | **404** |
| perda do acesso a W, em listagem **sem** W no caminho (L01, L06 sem filtro) | itens de W **deixam de aparecer** a partir dessa página; as demais seguem normalmente |
| revogação do `ORG_ADMIN` (regra a) | o usuário passa a ver só o que a regra (b) lhe dá |

### 1.5 Cursor: formato, vínculo e integridade

O cursor das rotas internas é **opaco** e **assinado**. Base64url sozinho só dá formato; não detecta adulteração. Por isso:

```
cursor   = base64url(payload) "." base64url(tag)            (sem preenchimento '=')
payload  = JSON canônico (chaves nesta ordem, sem espaços):
           {"v":1,"k":"<impressão do id da chave>","r":"<rota>","o":"<organização>","m":"<vínculo>",
            "p":"<recurso pai | null>","f":"<impressão dos filtros>","s":"<ordenação>","pos":[...]}
tag      = HMAC-SHA256(K_cursor, bytes do payload)          (32 bytes)
```

**Campos e vínculos:**

| Campo | Vínculo | Origem |
|---|---|---|
| `v` | versão do formato | constante 1 |
| `k` | chave usada (rotação) | **impressão de tamanho fixo** do id da chave: os 11 primeiros caracteres de `base64url(SHA-256(id))`. O `hmacId` da configuração não tem limite de tamanho, então o id não entra literalmente (DC-15) |
| `r` | **rota** | identificador estável da operação (`listWorkspaces`, `listExecutions`, …), constante do código, `[A-Za-z]{1,32}` |
| `o` | **organização** | `{org}` da rota |
| `m` | **vínculo** da sessão (adicional ao parecer) | `membershipId` da sessão. Impede reaproveitar o cursor de outro usuário. A autorização já seria reaplicada; o vínculo só torna a recusa explícita |
| `p` | **recurso pai** | `workspaceId` (L03), `automationId` (L05), `executionId` (L08); `null` nas demais |
| `f` | **filtros** | SHA-256, em base64url, da forma canônica dos filtros: nomes em ordem fixa; `status` sem repetição e em ordem alfabética; UUID em minúsculas; datas convertidas para instante UTC com precisão de microssegundo; ausente = `null`. `limit` **não** entra |
| `s` | **ordenação** | identificador da ordenação da rota (ex.: `name48.asc+id.asc`), constante do código, `[A-Za-z0-9.+_]{1,32}` |
| `pos` | posição | a **chave de ordenação** do último item da página (§1.5.1), nunca um campo de tamanho ilimitado |

**Verificação.** Feita nesta ordem; qualquer falha → **400 `invalid_request`**, sem dizer qual:
1. formato (duas partes base64url, tamanho ≤ 1024; o servidor nunca emite cursor maior que 872, §1.5.1);
2. `k` conhecido;
3. tag em **comparação de tempo constante** (`MessageDigest.isEqual`);
4. `v` suportada;
5. `r`, `o`, `m`, `p`, `f` e `s` iguais aos da requisição atual. O cliente **repete os mesmos filtros** a cada página;
6. `pos` com tipos válidos.

**Comportamento:**
- O cursor **não expira por tempo**: é posição, não credencial.
- Depois de uma rotação que retire a chave `k`, os cursores antigos dão 400 e o frontend recomeça da 1ª página. Esse comportamento é documentado.
- O cursor do **portal** não muda nesta proposta. Fica registrado que ele não tem integridade nem vínculo a filtros; é candidato a uma revisão própria.

#### 1.5.1 Chave de ordenação de tamanho limitado: o servidor nunca emite um cursor que recusaria

**Problema (parecer da revisão 1):**
- os nomes de workspace, automação e cliente são `TEXT` sem limite;
- na revisão 1, o nome completo ia para `pos`. Um nome válido no banco gerava um cursor maior que 1.024, e a página seguinte dava 400;
- truncar **só no cursor** mudaria a posição em relação à ordenação real;
- aumentar o limite arbitrariamente não resolve.

**Solução:** a **ordenação** das rotas por nome passa a ser feita por uma **chave de tamanho limitado**, e o cursor guarda exatamente essa chave. Não há truncamento no cursor: a consulta ordena e compara pelo mesmo valor.

| Rotas | Ordenação (SQL) | `pos` |
|---|---|---|
| L01 workspaces | `ORDER BY left(name, 48) COLLATE "C", workspace_id` | `[left(name, 48), workspaceId]` |
| L03 automações | `ORDER BY left(name, 48) COLLATE "C", automation_id` | `[left(name, 48), automationId]` |
| M03 clientes | `ORDER BY left(name, 48) COLLATE "C", client_id` | `[left(name, 48), clientId]` |
| M01, M02, L06 | `created_at` + id (tamanho fixo) | `[createdAt em UTC com microssegundos, id]` |
| P02 | `workspace_id` | `[workspaceId]` |
| L05 / L08 | `version_number` / `sequence` | `[inteiro]` |

- Página seguinte: `WHERE (left(name, 48) COLLATE "C", id) > (:pos0, :pos1)`, a **mesma expressão** do `ORDER BY`.
- `left(text, n)` conta **pontos de código** no PostgreSQL; a aplicação compara o mesmo valor que o banco devolveu, sem recortar de novo.

**Semântica documentada da ordenação por nome:**
- itens ordenados pelos **48 primeiros pontos de código** do nome, em comparação binária;
- itens cujos nomes coincidem nesses 48 pontos de código ficam em ordem de **id**, **não** pelo restante do nome. Para nomes de até 48 pontos de código, é a ordem binária completa;
- a resposta traz sempre o **nome completo**; só a ordenação usa o prefixo;
- o índice por expressão, se necessário, é avaliado no DC-07 e seria migration própria.

**Tamanho máximo do cursor emitido.** Todos os campos têm tamanho máximo:
- `o`, `m`, `p`: UUID, 36;
- `k`: 11;
- `f`: 43;
- `r`, `s`: ≤ 32;
- `pos`: ≤ 48 pontos de código, mais um UUID.

No JSON, um ponto de código ocupa no máximo **6 bytes** (controle escapado como `\u00XX`); os demais ocupam até 4 bytes em UTF-8.

| | Valor |
|---|---|
| payload máximo | 332 bytes fixos + 48 × 6 = **620 bytes** |
| cursor máximo | ⌈620 / 3⌉ × 4 + 1 + 43 = **≤ 872 caracteres** |
| maior cursor observado na contraprova (48 caracteres de controle) | **871** |
| folga até o limite de aceitação (1.024) | **152** |

- **Invariante de implementação:** o codificador **recusa emitir** (erro de programação, 500 com log) um cursor maior que 1.024. Ele não pode ocorrer com os campos acima. Um teste unitário fixa o pior caso (§5.6).
- Se um campo novo entrar no cursor, o cálculo acima precisa ser refeito na mesma revisão do contrato.

### 1.6 Filtros de período (`from`, `to`) da ROTA-L06

- **Aplicam-se a** `createdAt` da execução.
- **Intervalo semiaberto:** `from` **inclusivo** (`created_at >= from`) e `to` **exclusivo** (`created_at < to`), como no portal.
- **Formato:** ISO-8601 **com fuso** (`2026-10-01T00:00:00Z` ou `…-03:00`). Sem fuso ou inválido → 400.

**Combinações:**

| `from` | `to` | Efeito |
|---|---|---|
| ausente | ausente | sem restrição de período |
| presente | ausente | `created_at >= from`, sem limite superior |
| ausente | presente | `created_at < to`, sem limite inferior |
| presente | presente | `from <= created_at < to`. `to < from` → **400**; `to = from` → **200 com lista vazia** (intervalo vazio); `to − from` > **366 dias exatos** (366 × 24 h) → **400** |

- **Diferença registrada:** o portal compara por `Duration.toDays() > 366`, que trunca e aceita até 366 dias, 23 h, 59 min. A rota interna usa o limite exato.
- **Volume:** `limit` limita o **resultado**, não o trabalho da consulta. Com período aberto ou largo, filtros seletivos e sem índice dedicado, a consulta pode examinar e ordenar muitas linhas para devolver uma página. **O custo não está comprovado**: depende da medição do DC-07 (§6), que inclui período aberto e período de 30 dias.

### 1.7 Representação

- Datas em ISO-8601 com fuso (`OffsetDateTime`). Identificadores UUID em texto.
- Campos opcionais aparecem como `null`; nunca são omitidos.
- Rótulos em inglês minúsculo. Código desconhecido no banco → **500** (como o portal), nunca omitido.
- **`durationMs`:**
  - fórmula: `finished_at − started_at`, em **milissegundos inteiros**, com truncamento (`Duration.toMillis()`);
  - é **nulo** se `started_at` **ou** `finished_at` for nulo. Os casos são: em espera ou em andamento; cancelada antes do despacho; falha de início sem `started_at`;
  - nunca é negativo: a 006 garante `finished_at >= started_at`;
  - **não** é calculado contra o relógio atual para execuções em andamento.

**Rótulos:**

| Campo | Valores |
|---|---|
| status de usuário | `pending`, `active`, `suspended`, `closed` |
| status de vínculo | `pending`, `active`, `suspended`, `revoked` |
| status de cliente e de workspace | `active`, `suspended`, `archived` |
| status de concessão | `pending`, `active`, `suspended`, `revoked` |
| status de automação | `active`, `suspended`, `archived` |
| status de versão | `draft`, `published` |
| execução | rótulos do portal |
| origem da execução | `manual`, `scheduled` |

## 2. GAP-02: permissões efetivas

### ROTA-P01 `GET /api/v1/o/{org}/me/permissions`

| Item | Proposta |
|---|---|
| Autorização | qualquer conta **interna** ativa (a própria). Conta externa → 403 |
| Isolamento | só as atribuições do vínculo da sessão, na organização da rota |
| Entrada | nenhuma |
| Paginação | não: a resposta tem tamanho limitado pelo catálogo (≤ 23 permissões, ≤ 4 papéis). As atribuições **por workspace** ficam na ROTA-P02 |

**Resposta 200:**
```json
{
  "organizationId": "7c1f…",
  "membershipId": "a41e…",
  "organization": {
    "roles": ["ORG_ADMIN"],
    "permissions": ["agent.manage", "agent_credential.manage", "automation.execute", "…(as 23)…", "workspace.lifecycle"]
  },
  "allWorkspaces": {
    "permissions": ["automation.execute", "automation.manage", "…(as 13 de workspace)…", "workspace_secret.read"]
  },
  "hasWorkspaceAssignments": true
}
```

**Campos:**
- `organization.roles`: papéis das atribuições organizacionais ativas.
- `organization.permissions`: união das permissões desses papéis. Pode conter qualquer um dos **23** códigos (`PermissionCode`).
- `allWorkspaces.permissions`: as permissões **de workspace** de `organization.permissions` (§0.1), que valem em todos os workspaces pela regra (a). Só contém os **13** códigos de workspace (`WorkspacePermissionCode`). Fica vazia se o DC-01 não for aprovado como recomendado.
- `hasWorkspaceAssignments`: indica se existe ao menos uma atribuição de workspace ativa. Assim o frontend sabe se precisa consultar a ROTA-P02.
- O frontend calcula o efetivo em W como `allWorkspaces.permissions ∪ (permissões de W na ROTA-P02)`.
- Listas em ordem alfabética, sem repetição. Usuário sem papéis → listas vazias, `hasWorkspaceAssignments = false`, **200**. Nenhum campo nulo.
- Código de papel ou permissão desconhecido → **500** (§0.1).

**Erros:** 401; 403 (conta externa); 429; 500 (catálogo inconsistente).

### ROTA-P02 `GET /api/v1/o/{org}/me/permissions/workspaces` (DC-03: paginar)

| Item | Proposta |
|---|---|
| Autorização | a da P01 |
| Conteúdo | **só as atribuições explícitas** (regra b) do vínculo da sessão: um item por workspace com ao menos uma atribuição ativa, inclusive de workspace arquivado ou suspenso |
| Entrada | `cursor`, `limit` (§1.4–§1.5) |
| Ordenação | `workspaceId` ascendente. É imutável e único, então a ordem não muda com renomeação |
| Sem truncar | todas as atribuições são alcançáveis por paginação. Não há limite de 500 nem resposta 500 por volume |

**Item:**
```json
{ "workspaceId": "e9b0…", "roles": ["OPERATOR"], "permissions": ["automation.execute", "automation.read", "logs.read"] }
```

- `permissions` contém só códigos **de workspace** (`WorkspacePermissionCode`). Uma permissão organizacional num papel de workspace → 500 (§0.1).
- O item **não** inclui o nome do workspace. O frontend usa a ROTA-L01/L02, que aplica a visibilidade.

**Erros:** 400 (cursor, `limit`); 401; 403 (conta externa); 429; 500 (catálogo).

### Testes da GAP-02

- **ORG_ADMIN:** os 23 códigos em `organization.permissions`; os 13 de workspace em `allWorkspaces`; nenhum organizacional em `allWorkspaces`.
- **OPERATOR** em W1 e **VIEWER** em W2: P01 com listas organizacionais vazias e `hasWorkspaceAssignments = true`; P02 com 2 itens e as permissões exatas de cada papel.
- **WORKSPACE_ADMIN:** em P02, as 13 permissões de workspace (o papel tem hoje todas as de workspace: 5 da 003, 2 da 005, 4 da 008, 2 da 009); nenhuma organizacional.
- Usuário sem papéis: P01 vazia com `false`; P02 `items: []`.
- Atribuição revogada não aparece em P01 nem em P02.
- **Paginação da P02** (estável) com 5 atribuições e `limit=2`: 3 páginas, cada atribuição uma única vez, ordem por `workspaceId`, última com `nextCursor = null`.
- **Volume:** 501 atribuições → todas alcançáveis por páginas, sem 500. Isso confirma a retirada do limite.
- Cursor da P02 adulterado, de outra rota ou de outro vínculo → 400.
- Conta externa → 403; sem sessão → 401; sessão da organização A na rota de B → 401.
- Não vaza atribuições de outro usuário nem de outra organização.
- **IT de catálogo:** `public.permissions` tem exatamente os 23 códigos do catálogo da aplicação. Os papéis de escopo 2 só têm permissões de workspace. Os papéis × permissões coincidem com a §0.1.

## 3. GAP-03: membros, concessões e clientes

### ROTA-M01 `GET /api/v1/o/{org}/members`

| Item | Proposta |
|---|---|
| Autorização | `org.manage` (organizacional). Sem ela → **403** |
| Isolamento | `organization_memberships` da organização da rota. **Só contas internas**; as externas ficam nas concessões (ROTA-M02) |
| Entrada | `status` (repetível, até 4: `pending\|active\|suspended\|revoked`), `cursor`, `limit` |
| Ordenação | `createdAt` ascendente, desempate por `membershipId` ascendente |

**Resposta 200:**
```json
{ "items": [ {
    "membershipId": "a41e…", "userId": "0d2c…", "displayName": "Ana Souza", "email": "ana@empresa.test",
    "emailVerified": true, "accountStatus": "active", "membershipStatus": "active",
    "mfaConfigured": true, "organizationRoles": ["ORG_ADMIN"], "createdAt": "2026-09-01T12:00:00Z" } ],
  "nextCursor": null }
```

**Campos e limites:**
- `organizationRoles` traz só as atribuições ativas.
- `mfaConfigured` (DC-04): só o booleano, sob `org.manage`. É `true` se existe fator TOTP com `confirmed_at` preenchido e `disabled_at` nulo.
- Não retorna hash de senha, segredo TOTP, códigos de recuperação, sessões nem `email_normalized`.

**Erros:** 400; 401; 403 (conta externa ou sem `org.manage`); 429.

**Testes:**
- sucesso com ORG_ADMIN e campos conferidos;
- `mfaConfigured` verdadeiro, falso sem fator, falso com fator desativado;
- paginação estável em 3 páginas com `limit=2`;
- filtro `status`;
- cursor adulterado, com outro filtro ou de outra rota → 400;
- sem `org.manage` → 403; conta externa → 403; 401;
- organização B ausente (contagem conferida);
- lista com só o próprio ORG_ADMIN.

### ROTA-M02 `GET /api/v1/o/{org}/client-grants`

| Item | Proposta |
|---|---|
| Autorização | `external_access.manage` (organizacional). Sem ela → **403** |
| Isolamento | concessões da organização da rota |
| Entrada | `status` (repetível, até 4), `clientId`, `workspaceId`, `cursor`, `limit` |
| Ordenação | `createdAt` **descendente**, desempate por `grantId` descendente |

**Exceção à regra do 404 (§1.3, linha 5), documentada (DC-05):**
- Aqui `clientId` e `workspaceId` são **filtros de uma rota organizacional**. Quem tem `external_access.manage` vê **todas** as concessões da organização, e o filtro só restringe.
- `clientId` ou `workspaceId` **inexistente ou de outra organização** → **200 com `items: []`**. Não revela existência fora da organização. Não há "sem acesso" a distinguir: a permissão é organizacional.
- A regra do 404 continua valendo para as rotas **por workspace** (L02–L08) e para os filtros da L06.
- UUID **malformado** nesses filtros → **400** (§1.3).

**Resposta 200:**
```json
{ "items": [ {
    "grantId": "5f0a…", "status": "active",
    "client": { "clientId": "c1…", "name": "Cliente X" },
    "workspace": { "workspaceId": "e9b0…", "name": "Operação X" },
    "externalAccount": { "clientMembershipId": "cm…", "userId": "u…", "displayName": "João", "email": "joao@cliente.test",
                         "membershipStatus": "active" },
    "approvedAt": "2026-09-10T10:00:00Z", "startsAt": "2026-09-10T10:00:00Z", "expiresAt": null,
    "nextReviewAt": "2026-12-10T10:00:00Z", "lastAccessAt": null, "revokedAt": null, "createdAt": "2026-09-10T09:58:00Z" } ],
  "nextCursor": null }
```

**Nulabilidade:** `approvedAt`, `expiresAt`, `nextReviewAt`, `lastAccessAt` e `revokedAt` podem ser nulos, conforme a 003 (`next_review_at` é obrigatório quando ativa).

**Erros:** 400; 401; 403; 429.

**Testes:**
- sucesso com dados da concessão, do cliente e da conta externa;
- revogada com `status=revoked` e `revokedAt`;
- filtros `status`, `clientId` e `workspaceId`;
- **exceção:** `clientId` de outra organização → 200 vazio; `clientId` inexistente → 200 vazio; `workspaceId` de outra organização → 200 vazio. Comparar com uma rota por workspace, onde o mesmo caso dá 404;
- `clientId` malformado → 400;
- paginação estável; cursor com filtro trocado → 400;
- 403 sem a permissão e para conta externa; 401;
- isolamento: concessões de B ausentes.

### ROTA-M03 `GET /api/v1/o/{org}/clients` (DC-06: incluir com contrato e testes completos)

| Item | Proposta |
|---|---|
| Autorização | `org.manage` **ou** `external_access.manage` (organizacionais). Sem nenhuma → **403** |
| Isolamento | `clients` da organização da rota |
| Entrada | `status` (repetível, até 3: `active\|suspended\|archived`), `cursor`, `limit` |
| Ordenação | **48 primeiros pontos de código** de `name`, ascendente, em **comparação binária** (`left(name, 48) COLLATE "C"`), desempate por `clientId` ascendente (§1.5.1). O nome é mutável, então vale a ressalva de renomeação (§1.4) |

**Item:**
```json
{ "clientId": "c1…", "name": "Cliente X", "status": "active", "createdAt": "…", "updatedAt": "…" }
```

**Erros:** 400; 401; 403 (conta externa ou sem as duas permissões); 429.

**Testes:**
- sucesso com ORG_ADMIN;
- sucesso com usuário que tem **só** `external_access.manage` e com usuário que tem **só** `org.manage`. Hoje só o ORG_ADMIN tem uma delas; o teste usa um papel de teste criado na massa do IT, ou registra que, com o catálogo atual, esse caso é coberto só pelo ORG_ADMIN. A escolha fica para a mini missão, sem alterar o catálogo do produto;
- usuário interno sem as duas permissões (OPERATOR) → 403; conta externa → 403; 401;
- filtro `status`; arquivado aparece com o `status`;
- **ordenação binária:** nomes com maiúscula, minúscula e acento na ordem esperada; desempate de nomes iguais por `clientId`; **nomes longos:** dois clientes com os mesmos 48 primeiros pontos de código e finais diferentes ficam em ordem de `clientId`; nome de 1.000 caracteres (inclusive com caracteres de controle e emoji) pagina sem 400 e o cursor emitido tem ≤ 872 caracteres;
- paginação estável; cursor adulterado ou com filtro trocado → 400; `limit` fora da faixa → 400;
- isolamento: clientes de B ausentes;
- organização sem clientes → `items: []`.

## 4. GAP-06: leitura interna do produto

**Em todas as rotas desta seção:**
- a autorização vem do componente da §1.2;
- a ordem das verificações é a da §1.3;
- a paginação e o cursor seguem a §1.4 e a §1.5.

### ROTA-L01 `GET /api/v1/o/{org}/workspaces`

| Item | Proposta |
|---|---|
| Autorização | sessão interna; só workspaces **visíveis** (`visibleWorkspacesPredicate`). Sem nenhum → `items: []` |
| Entrada | `status` (repetível, até 3), `cursor`, `limit` |
| Ordenação | `left(name, 48) COLLATE "C"` ascendente, desempate por `workspaceId` (§1.5.1) |

**Item:**
```json
{ "workspaceId": "e9b0…", "name": "Operação X", "description": null, "status": "active",
  "client": { "clientId": "c1…", "name": "Cliente X" }, "createdAt": "…", "updatedAt": "…" }
```

- `client` é nulo em workspace interno.

**Testes:**
- ORG_ADMIN vê todos;
- OPERATOR vê só os atribuídos;
- atribuição revogada some;
- usuário sem papéis → `items: []`;
- arquivado aparece com o `status`;
- paginação estável;
- conta externa → 403; 401;
- organização B ausente.

### ROTA-L02 `GET /api/v1/o/{org}/workspaces/{workspaceId}`

- Retorna o item da L01 (`requireWorkspace`).
- Inexistente, de outra organização ou sem acesso → **404** idêntico. UUID malformado → 400.

### ROTA-L03 `GET /api/v1/o/{org}/workspaces/{workspaceId}/automations`

| Item | Proposta |
|---|---|
| Autorização | `requireWorkspace(W, automation.read)`; sem acesso → **404** |
| Entrada | `status` (repetível, até 3), `cursor`, `limit` |
| Ordenação | `left(name, 48) COLLATE "C"` ascendente, desempate por `automationId` (§1.5.1) |

**Item:**
```json
{ "automationId": "a1…", "workspaceId": "e9b0…", "name": "Fechamento diário", "description": null, "status": "active",
  "latestPublishedVersion": { "automationVersionId": "v…", "versionNumber": 3, "publishedAt": "…" },
  "createdAt": "…", "updatedAt": "…" }
```

- `latestPublishedVersion` é nulo se não houver versão publicada.

### ROTA-L04 `GET /api/v1/o/{org}/automations/{automationId}`

- Retorna o item da L03 (`requireResource`).
- Inexistente, de outra organização ou de workspace sem acesso → **404**.
- **Sem `command_json`** (DC-08).
- **Sem o criador:** o criador da automação fica para depois (DC-09).

### ROTA-L05 `GET /api/v1/o/{org}/automations/{automationId}/versions`

- **Autorização:** `requireResource(automação, automation.read)`; sem acesso → 404.
- **Ordenação:** `versionNumber` descendente, único por automação. **Paginação:** padrão, com `p = automationId` no cursor.
- **Item:** `{ "automationVersionId", "versionNumber", "status": "draft|published", "publishedAt" (nulo em rascunho), "createdAt" }`.
- **Sem `command_json`** (DC-08).

### ROTA-L06 `GET /api/v1/o/{org}/executions`

| Item | Proposta |
|---|---|
| Autorização | sessão interna; só execuções de workspaces visíveis. Sem nenhum workspace → `items: []` |
| Filtros por workspace (regra geral, **sem** a exceção da M02) | `workspaceId` inexistente, de outra organização ou sem acesso → **404**. `automationId` inexistente, de outra organização ou de workspace sem acesso → **404** (`requireResource`). `workspaceId` e `automationId` acessíveis mas incompatíveis (automação de outro workspace) → **200 vazio** |
| Entrada | `workspaceId`, `automationId`, `status` (repetível, até 6), `from`, `to` (§1.6), `cursor`, `limit` |
| Ordenação | `createdAt` **descendente**, desempate por `executionId` descendente (como o portal) |

**Item:**
```json
{ "executionId": "x…", "workspaceId": "e9b0…", "automation": { "automationId": "a1…", "name": "Fechamento diário" },
  "automationVersion": { "automationVersionId": "v…", "versionNumber": 3 }, "origin": "manual",
  "status": "succeeded", "statusReason": "process_completed",
  "createdAt": "…", "startedAt": "…", "finishedAt": "…", "durationMs": 81234, "cancelRequestedAt": null }
```

**Nulabilidade:**
- `statusReason`, `startedAt`, `finishedAt`, `durationMs` (§1.7) e `cancelRequestedAt` podem ser nulos;
- `workspaceId` é **sempre** retornado. A regra de omissão do portal não vale aqui.

### ROTA-L07 `GET /api/v1/o/{org}/executions/{executionId}`

- Retorna o item da L06 mais `retryOfExecutionId` e `requestedBy`:
  - `requestedBy` (DC-09): `{membershipId, displayName}` do solicitante; **nulo** em execução agendada.
- **`errorSummary` retirado** (DC-10): fica adiado até que a sanitização da origem esteja comprovada. `logs.read` sozinho não resolve.
- Inexistente, de outra organização ou sem acesso → **404**.
- **Não retorna:** `command_snapshot`, `request_key`, `request_fingerprint`, `pid`, `exit_code` nem dados do Worker/Agent (DC-08, DC-11).

### ROTA-L08 `GET /api/v1/o/{org}/executions/{executionId}/events`

- **Autorização:** a da execução (`requireResource`). Inexistente ou sem acesso → **404**. É diferente do portal, que devolve lista vazia (DC-12).
- **Ordenação:** `sequence` ascendente, única por execução. **Paginação:** padrão, com `p = executionId`.
- **Item:** `{ "sequence", "type", "occurredAt", "receivedAt" }`, **sem `payload`** (DC-08).

### Fora desta proposta

- **Logs** (`logs.read`): mini missão posterior, com regras de sanitização e retenção (DC-13).
- **Ações** (executar, cancelar, agendar, Workers): continuam na v1 completa.
- **`errorSummary`** (DC-10) e **criador da automação** (DC-09).

## 5. Testes HTTP

Cada mini missão entrega ITs no padrão da GAP-01: `PlatformTestBase`, aplicação real, PostgreSQL e Redis reais, conferindo **resposta e dados**.

### 5.1 Matriz mínima por rota

| Cenário | P01 | P02 | M01 | M02 | M03 | L01 | L02 | L03 | L04 | L05 | L06 | L07 | L08 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| sucesso com campos e nulabilidade conferidos | x | x | x | x | x | x | x | x | x | x | x | x | x |
| 401 sem sessão / sessão de outra organização / `{org}` malformado | x | x | x | x | x | x | x | x | x | x | x | x | x |
| 403 conta externa | x | x | x | x | x | x | x | x | x | x | x | x | x |
| 403 sem a permissão organizacional | — | — | x | x | x | — | — | — | — | — | — | — | — |
| 400 UUID malformado (caminho ou filtro), inclusive com conta externa (ordem da §1.3) | — | — | — | x | — | — | x | x | x | x | x | x | x |
| 404 inexistente / de outra organização / sem acesso, com corpo idêntico | — | — | — | — | — | — | x | x | x | x | x (filtros) | x | x |
| exceção: filtro inexistente ou de outra organização → 200 vazio | — | — | — | x | — | — | — | — | — | — | — | — | — |
| isolamento: dados de outra organização ausentes | x | x | x | x | x | x | x | x | x | x | x | x | x |
| lista vazia (200) | — | x | — | x | x | x | — | x | — | x | x | — | x |
| paginação **estável** (§5.3) | — | x | x | x | x | x | — | x | — | x | x | — | x |
| cursor: adulterado, de outra rota, de outro pai, com filtro trocado, de outro vínculo, de outra organização, chave desconhecida → 400 | — | x | x | x | x | x | — | x | — | x | x | — | x |
| `limit` 0, 101 e não inteiro → 400 | — | x | x | x | x | x | — | x | — | x | x | — | x |
| nome de 1.000 caracteres no fim da página: página seguinte aceita (§1.5.1, §5.6) | — | — | — | — | x | x | — | x | — | — | — | — | — |
| filtros e 400 de valor inválido | — | — | x | x | x | x | — | x | — | — | x | — | — |
| mudança concorrente (§5.4) | — | — | — | — | x | x | — | x | — | — | x | — | — |
| revogação entre páginas e no acesso direto (§5.5) | — | x | x | x | — | x | x | x | x | x | x | x | x |

**Casos específicos da L06:**
- `from` ausente, `to` ausente e ambos ausentes;
- **limites:** execução com `createdAt = from` aparece; com `createdAt = to` **não** aparece;
- `to = from` → 200 vazio; `to < from` → 400;
- exatamente 366 dias → 200; 366 dias + 1 ms → 400;
- data sem fuso → 400;
- `durationMs`: concluída (valor exato em ms), em andamento (nulo), cancelada antes do despacho (nulo);
- `automationId` sem acesso → 404; `workspaceId` + `automationId` incompatíveis → 200 vazio.

### 5.2 Autorização compartilhada (um teste parametrizado sobre todas as rotas L e P02)

**Massa:**
- organização A com W1, W2 e W3 (arquivado);
- organização B espelhada;
- atores conforme as tabelas abaixo.

**Duas expectativas distintas.** Visibilidade e atribuição explícita **não** são a mesma coisa:
- **Visibilidade (V):** o que o usuário pode ler, pelas regras (a) **ou** (b) da §1.2. Vale para L01–L08 e para os filtros da L06.
- **Atribuição explícita (E):** só a regra (b). Vale para a **P02** e para `hasWorkspaceAssignments` da P01.

Um `ORG_ADMIN` sem atribuições de workspace **vê todos** os workspaces (V), mas tem **P02 vazia** (E). O teste nunca deriva E de V, nem V de E.

**Tabela V (visibilidade esperada; L01–L08):**

| Ator | W1 | W2 | W3 (arquivado) |
|---|---|---|---|
| ORG_ADMIN sem atribuição de workspace | sim | sim | sim |
| ORG_ADMIN + VIEWER explícito em W3 | sim | sim | sim |
| WORKSPACE_ADMIN em W1 | sim | — | — |
| OPERATOR em W1 | sim | — | — |
| VIEWER em W2 | — | sim | — |
| atribuição revogada em W1 | — | — | — |
| sem papéis | — | — | — |
| conta externa | 403 em todas as rotas | | |

**Tabela E (atribuições explícitas esperadas; P02 e `hasWorkspaceAssignments`):**

| Ator | Itens da P02 | `hasWorkspaceAssignments` | `allWorkspaces.permissions` (P01) |
|---|---|---|---|
| ORG_ADMIN sem atribuição de workspace | **nenhum** | `false` | as 13 de workspace |
| ORG_ADMIN + VIEWER explícito em W3 | W3: `[VIEWER]`, `[automation.read]` | `true` | as 13 de workspace |
| WORKSPACE_ADMIN em W1 | W1: `[WORKSPACE_ADMIN]`, as 13 de workspace | `true` | vazia |
| OPERATOR em W1 | W1: `[OPERATOR]`, `[automation.execute, automation.read, logs.read]` | `true` | vazia |
| VIEWER em W2 | W2: `[VIEWER]`, `[automation.read]` | `true` | vazia |
| atribuição revogada em W1 | nenhum | `false` | vazia |
| sem papéis | nenhum | `false` | vazia |
| conta externa | 403 | 403 | 403 |

**Esperado:**
- cada rota L é conferida contra a **tabela V**: itens, 200, 404 ou 403;
- a P02 e a P01 são conferidas contra a **tabela E**;
- as duas tabelas vêm da §1.2, e uma rota que divergir da sua tabela falha;
- **se o DC-01 for decidido contra a regra (a):** nas linhas de ORG_ADMIN, a tabela V passa a ser igual às atribuições explícitas, e `allWorkspaces` fica vazio. A tabela E não muda.

### 5.3 Paginação estável (sem mudanças entre páginas)

- Massa com N itens, N não múltiplo de `limit` e outro caso com N múltiplo; itens com o **mesmo** valor do campo de ordenação, para exercitar o desempate.
- **Asserções:**
  - a união das páginas = conjunto esperado;
  - cada item aparece **uma vez**;
  - a ordem é a declarada;
  - a última página tem `nextCursor = null`;
  - nenhuma página intermediária vem vazia.

### 5.4 Paginação com mudança concorrente (separada da estável)

Entre a página 1 e a página 2, o teste altera o banco. Ele só confere o que o contrato garante (§1.4): a página é válida, ordenada, autorizada pelo estado atual, e não há erro. **Não** afirma completude global.

| Mudança | Asserção específica |
|---|---|
| renomear um workspace ou cliente para **antes** do cursor | página 2 sem erro; o item renomeado **não** aparece (omissão documentada) |
| renomear para **depois** do cursor | o item aparece na posição do novo nome (pode ser repetição; documentada) |
| renomear mudando só depois do 48º ponto de código | a posição não muda (§1.5.1); o item aparece uma vez, com o nome novo |
| inserir uma execução mais nova (L06, ordem descendente) | não aparece nas páginas seguintes |
| inserir uma automação com nome depois do cursor (L03) | aparece |
| mudar o `status` de um item, com filtro de `status` | sai ou entra conforme o filtro atual |

### 5.5 Revogação entre páginas e acesso direto

| Mudança entre requisições | Rotas | Esperado |
|---|---|---|
| revogar a atribuição do OPERATOR em W1 | L01, L06 sem filtro (página 2) | itens de W1 ausentes; demais workspaces normais |
| idem | L03 de W1, L06 com `workspaceId=W1` ou `automationId` de W1 (página 2) | **404** |
| idem | **acesso direto** L02 (W1), L04 (automação de W1), L05 (versões), L07 (execução de W1), L08 (eventos) | **404**, corpo idêntico ao de inexistente |
| idem | P02 | o item de W1 some |
| revogar `ORG_ADMIN` de um usuário sem atribuições de workspace | L01, L02 | lista vazia; 404 |
| revogar a atribuição que dá `org.manage` ou `external_access.manage` | M01, M02, M03 (página 2) | **403** |
| revogar as sessões do usuário (`revoke-all` ou suspensão) | qualquer | **401** |

### 5.6 Codificador de cursor e chave (DC-15)

São testes **unitários** do componente compartilhado, mais um IT por rota paginada.

**Derivação da chave:**
- `K_cursor` é igual a `HMAC-SHA256(chave de serviço, "nekkora/api-cursor/v1")`, conferido com um vetor fixo de teste: chave e valor esperados gravados no teste, sem chave real;
- `K_cursor` é **diferente** da chave de serviço;
- uma tag de cursor **não** valida como HMAC de identidade (`IdentityHmac`), nem o inverso.

**Assinatura inválida:** 1 bit alterado no payload, 1 bit alterado na tag, tag truncada, tag de outro payload ou tag vazia → recusa (400 na rota).

**Chave desconhecida:** cursor com `k` que não corresponde a nenhuma chave configurada → 400.

**Rotação:**
- cursor emitido com a chave antiga, depois que a configuração passa a ter **só** a chave nova → 400;
- um cursor novo, emitido com a chave nova, funciona;
- se a configuração aceitar a chave anterior durante uma janela de transição (decisão de implementação, fora deste contrato), o cursor antigo vale **só** enquanto a chave anterior estiver configurada.

**Tamanho:**
- o pior caso da §1.5.1 (48 caracteres de controle no prefixo, UUIDs, `r` e `s` com 32) gera cursor ≤ 872 caracteres;
- um cursor ≥ 1.025 caracteres recebido → 400;
- o codificador recusa emitir cursor acima de 1.024.

**IT por rota ordenada por nome (L01, L03, M03):** nome de 1.000 caracteres (ASCII, controles, emoji) na **última posição da página 1** → a página 2 é aceita, sem 400.

**Para cada mini missão:** `validar-config` sem regressão e `regressao_maven.sh` com `REAPROVEITAMENTO=PERMITIDO`.

## 6. DC-07: plano de medição (antes de fechar a mini missão GAP-06)

- **Onde:** base **descartável** do laboratório da mini missão. Nunca bases protegidas.
- **Volume sintético mínimo** (registrado no relatório):

| Item | Volume |
|---|---|
| organizações | 3; a medida usa a maior |
| workspaces na organização medida | 200 |
| automações | 20 000 no total, com 1 workspace de 5 000 |
| execuções | 1 000 000 na organização medida, distribuídas em 12 meses, com 1 workspace "quente" de 300 000 |
| eventos | 5 por execução, numa amostra de 10 000 execuções |
| atores | ORG_ADMIN (vê tudo) e OPERATOR com 3 workspaces |

- **Consultas medidas, cada uma com `EXPLAIN (ANALYZE, BUFFERS)` registrado:**
  - L06 1ª página e página profunda (cursor após 10 000 itens), sem filtro (período aberto), só com `from` (aberto acima), com `workspaceId` quente, com `status` seletivo e com período de 30 dias, para os dois atores;
  - L03 no workspace de 5 000 automações, 1ª página e profunda, com a ordenação por `left(name, 48) COLLATE "C"` (§1.5.1);
  - L01 com 200 workspaces, mesma ordenação;
  - M03 com volume de clientes a definir na GAP-03, mesma ordenação;
  - L08 numa execução com eventos.
- **Registro:**
  - versão do PostgreSQL e máquina;
  - volume efetivamente gerado (contagens por `SELECT count(*)`);
  - texto das consultas;
  - p50 e p95 de 30 execuções de cada, depois de 3 de aquecimento;
  - planos.
- **Critério:** proposta de referência, para decisão no DC-07: p95 ≤ 200 ms nas primeiras páginas e ≤ 500 ms nas profundas, no volume acima.
- **Se reprovar:** a necessidade de índice vira **migration própria**, decidida e aprovada à parte. A mini missão não cria índice.

## 7. Dúvidas de contrato: recomendação da revisão e decisão pendente

As recomendações abaixo são **do parecer de revisão**. **A responsável ainda não decidiu nenhum DC.** O contrato foi redigido segundo as recomendações para que possa ser revisado por completo. Uma decisão diferente muda os pontos indicados.

| ID | Dúvida | Recomendação da revisão | Se a decisão for outra |
|---|---|---|---|
| **DC-01** | O `ORG_ADMIN` tem acesso implícito a **todos** os workspaces? A 003 diz que sim; o Radiant §6 diz que "papéis organizacionais não se replicam automaticamente" | `ORG_ADMIN` global como **política explícita aprovada**, resolvendo a divergência documental (registro na documentação de produto). A migration **não** decide o produto automaticamente | sai a regra (a) do §1.2 (um ponto no componente). `allWorkspaces` fica sempre vazio; o ORG_ADMIN precisa de atribuição por workspace |
| DC-02 | Conta externa nas rotas internas | **403** | 200 vazio nas listas e 404 nos detalhes |
| DC-03 | Atribuições explícitas na P01 | **paginar** (ROTA-P02); sem 500 e sem truncar | — |
| DC-04 | `mfaConfigured` nos membros | **só o booleano**, sob `org.manage` | campo sai do `Member` |
| DC-05 | Filtro com ID de outra organização ou inexistente na M02 | **lista vazia**, com a exceção documentada | 404, alinhado às rotas por workspace |
| DC-06 | ROTA-M03 (`/clients`) | **incluir**, com contrato e testes completos | rota sai da GAP-03 |
| **DC-07** | Desempenho sem índice dedicado | **medir com volume sintético** antes de fechar a GAP-06, registrando volume, consulta e resultado (§6) | — |
| **DC-08** | `command_json`, `command_snapshot` e `payload` de eventos | **manter fora** | permissão própria e revisão de sanitização |
| DC-09 | Quem solicitou ou criou | **incluir o solicitante** (`requestedBy`); **adiar o criador** da automação | — |
| DC-10 | `errorSummary` | **adiar**, até que a sanitização esteja comprovada | volta à L07 com a regra de visibilidade decidida |
| DC-11 | Dados do Worker/Agent na execução | como proposto: **não** nesta etapa | — |
| DC-12 | Eventos sem acesso: 404 (interno) × lista vazia (portal) | como proposto: **404**, com a diferença documentada | lista vazia |
| DC-13 | Logs na primeira entrega | como proposto: **não**; mini missão posterior | — |
| DC-14 | Caminho definitivo do OpenAPI | como proposto: `api/openapi/nekkora-api-v1.yaml`, com as rotas existentes na consolidação (GAP-05) | — |
| **DC-15** (novo, revisão 1; ajustado na revisão 2) | Chave do HMAC do cursor | derivar uma subchave **com finalidade específica** da chave HMAC de serviço já existente: `K_cursor = HMAC-SHA256(nekkora.keys.hmac, "nekkora/api-cursor/v1")`, com `k` = impressão de 11 caracteres do `hmacId` (§1.5). Não exige segredo novo, e a separação de domínio impede reaproveitar a tag em outro uso. **Consequência a registrar na decisão: a rotação da chave de serviço também invalida todos os cursores emitidos** (400; o frontend recomeça da 1ª página). Testes obrigatórios na implementação: derivação, assinatura inválida, chave desconhecida e rotação (§5.6) | chave dedicada `nekkora.keys.cursor` + id, entregue pelo serviço de segredos como as demais; a rotação dos cursores fica independente da chave de serviço. Muda só a configuração; os testes da §5.6 continuam |

**Origem do DC-15:** não estava no primeiro parecer; é recomendação **do executor**. O parecer da revisão 1 considerou a direção adequada, com as exigências acima. Continua pendente de decisão.

## 8. Ordem e independência das mini missões

- **Orientação do parecer da revisão 1: começar pela GAP-02.** Depois de fechado o contrato, a GAP-02 entrega:
  - o **catálogo** de permissões (§0.1), com o IT de catálogo;
  - as rotas P01 e P02;
  - os **componentes compartilhados**: `WorkspaceAccess` (§1.2) e o codificador de cursor com a chave do DC-15 (§1.5, §5.6), com testes próprios.
- **GAP-03** e **GAP-06** seguem depois e **reutilizam** esses componentes, sem cópia nem regra própria.
- **Decisões necessárias** (todas pendentes):
  - **GAP-02:** DC-01, DC-02, DC-03 e DC-15;
  - **GAP-03:** também DC-04, DC-05 e DC-06;
  - **GAP-06:** também DC-07 a DC-13.
- Cada mini missão entrega:
  - as rotas, com autorização no backend;
  - os ITs da §5;
  - o trecho correspondente do OpenAPI, conferido contra os testes;
  - regressão PERMITIDO.
- A GAP-06 também entrega a medição do DC-07.
- **O commit dos testes da GAP-01 continua uma etapa separada.**
