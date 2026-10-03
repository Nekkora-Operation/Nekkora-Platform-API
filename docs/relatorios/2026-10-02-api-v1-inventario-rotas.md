# API-V1-INV-01: inventário das rotas necessárias à v1

> **RETRATO ANTERIOR À GAP-01.** Este inventário descreve o estado de 2026-10-02 **antes** da GAP-01. O resultado está em `docs/relatorios/2026-10-02-api-v1-gap01-testes-rotas.md`, com 9 testes novos e regressão de 275/0/0 PERMITIDO.
>
> Depois da GAP-01:
> - **ROTA-013** (confirmação de e-mail) e **ROTA-018** (revogação de concessão) passaram a ter testes HTTP específicos;
> - **ROTA-020** (contestação) recebeu o complemento de CSRF/Origin, reuso e vencimento;
> - há **evidência de isolamento entre organizações** conferida por teste para a **confirmação de e-mail** (token de A na rota de B → 400, sem efeito) e para a **revogação de concessão** (concessão de B na rota de A → 404; sessão de A na rota de B → 401; concessão de B intacta).
>
> Onde o texto abaixo trata essas lacunas como abertas, ou diz que não há isolamento entre organizações conferido, vale para o momento anterior. Para as outras rotas, as afirmações abaixo continuam valendo.
>
> **A jornada de verificação de e-mail continua incompleta:** o teste usa um token semeado no banco, e a emissão do token segue na GAP-04.

**ESTADO: PARA REVISÃO, RODADA 2** (documental, depois do parecer sobre o pacote `api-v1-inventario-rotas-20261002T191306Z.zip`, que fica preservado).

**Ajustes da rodada 2:**
1. **Erros por rota:** a coluna `erros` da tabela A agora traz o comportamento **específico** de cada rota, lido no código, e não uma lista genérica. As rotas públicas por token (ROTA-012, ROTA-013, ROTA-019, ROTA-020) **não** têm 401: token inválido dá **400**. O logout dá 204 sem sessão. A falha de login dá 401 sem enumeração. Recuperação dá 202 sempre. O `events` do portal dá 200 com lista vazia para execução não autorizada.
2. **Cobertura por cenário:** nova tabela **`D_cenarios.tsv`**. A coluna `testes` da tabela A passa a dizer explicitamente que só lista arquivos que mencionam a rota (§2, I4).
3. **Lacunas sem destino:** atribuição de papéis (**GAP-14**), concessões e contas externas (**GAP-15**) e relatórios publicados (**GAP-16**) ganharam mini missão ou adiamento explícito a decidir, com as decisões **D-INV-09/10/11**.
4. **Dependências:** a GAP-05 depende da D-INV-06. GAP-03 e GAP-06 **não** dependem da GAP-02: a autorização é aplicada no backend, independente da consulta usada pelo frontend.
5. A proposta de adiar as ações do produto para uma "v1.x" foi **retirada**. Execução, agendamento e gestão de Workers seguem no planejamento da **v1 completa**, e a D-INV-01 passa a tratar só do **recorte da primeira entrega** do frontend.

**Rodada 3** (parecer sobre o pacote `api-v1-inventario-rotas-r2-20261002T192419Z.zip`, preservado): o gerador de cenários passou a **só localizar candidatas**. Ele agora:
- usa o método HTTP na correspondência;
- classifica pelo cenário (401 sem sessão × outro; 403 CSRF × permissão; 429 limite × 503 dependência);
- separa o isolamento em organização, cliente e workspace.

Detalhe no §2 (I4).
- A contagem de rotas com evidência é **resultado da extração (18/25), sujeito à conferência de contexto**, que fica pendente.
- **Nenhum isolamento entre organizações** é afirmado com base em teste.

**Natureza da missão:**
- Análise e documentação.
- Nenhuma implementação, execução de teste, Maven, Docker, consulta a banco, fetch ou publicação.

**Data:** 2026-10-02.

**Tabelas** (`ops/homologacao/saidas/apiv1inv-<instante>/`, também no pacote):
- `A_endpoints.tsv`: inventário de endpoints;
- `B_capacidades.tsv`: capacidades e jornadas;
- `C_lacunas.tsv`: lacunas e mini missões (16 na rodada 2);
- `D_cenarios.tsv`: cenários por rota comprovados por instrução de teste identificada, ou não auditados (rodada 2);
- `rotas_testes.tsv`: rota × arquivos de teste;
- `rotas_do_codigo.txt` e `rotas_da_tabela.txt`: conciliação;
- `baseline.txt`.

## 1. Baseline

| Item | Valor |
|---|---|
| **Commit de referência** | **`2431648f0e07d89733284e2578795e2fe3a2d1ca`** (C4 da frente MFA), árvore `299e98b24f54710dd44db0a84dad642f8f33583a` |
| `main` | `bd9e96a147b9c0c40483b3d818e61c2d661b3fbd` (merge do PR #1, pelas refs locais do fetch das 18:52Z). **Árvore idêntica à do C4**, sem delta de código |
| Local | ramo `feat/mfa-emergencia`, HEAD = C4, stage 0, nenhum rastreado modificado |
| Fonte analisada | **exportação do commit** (`git archive 2431648f`, 2.025 arquivos versionados) numa pasta temporária. Arquivos não rastreados de outras frentes **não** entraram |
| Instruções locais | nenhum `CLAUDE.md`/`AGENTS.md` no repositório |

Este inventário cobre **só** esse commit. Ele não deve ser lido como cobertura de outro HEAD.

## 2. Método e completude

**I2, rotas:**
- busca em `api/*/src/main` por `@(Get|Post|Put|Delete|Patch|Request)Mapping`, `RouterFunction`, servlets, `@RestController`, filtros (`OncePerRequestFilter`, `SecurityFilterChain`, `FilterRegistrationBean`) e configuração;
- as dependências não incluem actuator nem springdoc: não há endpoints gerados por biblioteca;
- módulos do `api/pom.xml`: `nekkora-core`, `nekkora-ops`, `nekkora-platform` e `nekkora-portal`.
  - **`nekkora-ops` não expõe HTTP** (é uma CLI);
  - `api/e2e` é um harness de teste, sem rotas de produto.

**Conciliação:**

| Item | Resultado |
|---|---|
| Definições no código | **26**, em **5 arquivos** (`AuthController`, `AccountController`, `EmergencyNoticeController`, `PortalController`, `GenericErrorController`) |
| Linhas na tabela A | **26** |
| `diff` entre os dois | **vazio** |
| Prefixos | 0 mapeamentos em nível de classe; sem `context-path` (`application.yml` da Platform: porta 8081; do Portal: 8082) |

**Contagens:**
- por método: 18 POST, 7 GET e 1 `*` (`/error`);
- por módulo: Platform 21, Portal 4, core 1;
- duplicidades: nenhuma.

**Exclusões justificadas:**
- `/error`: rota interna do Spring, **não publicada**, porque o gateway responde 404 fora de `/api/v1/` e `/portal/api/v1/`;
- testes, fixtures e homologação (`ops/homologacao`, `api/e2e`) não definem rotas de produto;
- não há arquivos estáticos nem páginas.

**Caminho publicado:**
- O gateway de referência (`ops/homologacao/gateway/nginx.modelo`, igual ao `nginx.conf` e ao `api/e2e/nginx.conf`) faz `proxy_pass` **sem URI**, então o caminho publicado é igual ao interno.
- `location /portal/api/v1/` → portal; `~ ^/api/v1/o/[^/]+/auth/` → platform, na zona de limite `auth`; `/api/v1/` → platform, na zona `geral`; o resto → 404.
- Não há diferença de rotas por profile. O que muda por ambiente é o transporte de e-mail (`local`, teste ou caixa de homologação).

**I3, controles** (evidência de código, não de nome):
- **Sessão:**
  - `SessionFilter` autentica a cada requisição pelo cookie `__Host-nekkora_session`;
  - a sessão é validada no banco **na organização da rota**;
  - o principal só vive no atributo da requisição.
  - `SecurityConfig`: `STATELESS`; rotas públicas explícitas; o resto `authenticated()`.
- **CSRF:** o `CsrfFilter` exige, em **toda escrita** sob `/api/v1/`:
  - `Origin` (ou `Referer`) igual à origem pública;
  - `X-CSRF-Token` do contexto certo: pré-autenticação (Redis), desafio (`auth_challenges`) ou sessão.
  - O contexto vem de `Routes.csrfContext`.
- **Autorização administrativa:**
  - `AdminService.authorize`: conta interna + permissão real da 003 via `AccountRepository.hasPermission` (join `organization_role_assignments` → `role_permissions`);
  - alvo ≠ ator, invariantes da 003 (último `ORG_ADMIN`) e, no reset de MFA, step-up dentro da janela.
- **Isolamento:**
  - Platform: transação com a organização da rota em `set_config` (`OrgTransactions`) → RLS da 012.
  - Portal: processo, credencial e pool próprios (`nekkora_portal`, verificado no startup: sem superusuário e sem BYPASSRLS).
  - Portal: dados **só** pelas 4 funções `SECURITY DEFINER` da 013, que resolvem a sessão (`portal_resolve_session`) e filtram por `portal_authorized_workspaces` dentro da própria função.
  - Portal: `ReadOnlyFilter` responde 404 a qualquer método diferente de GET.
- **Limites:**
  - R7 por sessão ou IP no Redis; logout e revoke-all ficam fora da R7;
  - portal: R7 antes da chamada (por IP) e depois (por sessão), limitação declarada no contrato;
  - gateway: `limit_req` por zona.

**I4, testes:**
- Platform e Portal: **IT HTTP com aplicação real em processo**: `@SpringBootTest(RANDOM_PORT)` com PostgreSQL e Redis reais (`PlatformTestBase`, `PortalIT`), mais `CrossProcessE2E`.
- **Fluxo pelo gateway:** `ops/homologacao/testes/cliente.py` (H05 e `testar-016`).
- **SQL:** `nekkora-ops` (`AvisosIT`, `ConcorrenciaIT`) testa funções da 016, não rotas.
- **Execução vinculada:** a regressão homologada **266/0/0** rodou sobre o snapshot cujas entradas Maven são idênticas às do C4 (verificador IDENTICA/PERMITIDO no G1). O ensaio `ensaio-consol-016` exercitou o gateway.
- **Limite:** esses totais não significam cobertura de todos os cenários de cada rota.
- **Cobertura por cenário (rodada 3, `D_cenarios.tsv`; a versão da rodada 2 fica em `D_cenarios.r2.tsv`).** O script **só localiza evidências CANDIDATAS** e não conclui cobertura.
  - **Critério de candidata:** na mesma instrução de um método `@Test` aparecem:
    - a **chamada do método HTTP da rota** (`.post(` ou `.get(`);
    - o **caminho** (literal com o fim exato, ou variável definida no próprio método);
    - a verificação do status (`.status()` … `isEqualTo(N)`).
  - **Classificação pelo cenário**, usando a descrição `.as(...)`, e não só pelo código:
    - sucesso;
    - 401 sem sessão × 401 por outro motivo (credencial, desafio, token);
    - 403 de CSRF/Origin × 403 de permissão × 403 sem descrição;
    - 404; 400;
    - **429 = limite de requisições** × **503 = dependência indisponível** (o 503 da ROTA-001 comprova o Redis fora, não limite);
    - isolamento **separado por alvo**: organização, cliente ou workspace.
  - **Todas as células são "CANDIDATO (extração)".** A classificação final exige conferir a preparação de cada teste, e essa conferência **não foi feita** nesta rodada. Não se afirma, portanto, isolamento entre organizações para nenhuma rota.
    - A evidência "execução de outro cliente: 404" (ROTA-024) é classificada como **isolamento por cliente**. Não é por organização.
  - "NÃO LOCALIZADO PELA EXTRAÇÃO" **não** afirma ausência de teste: muitos testes chamam as rotas de autenticação por helpers (`PlatformTestBase`), que o critério não segue.
  - Os testes **não** foram executados.
  - **Resultado da extração, sujeito à conferência:** **18 das 25** rotas publicadas têm ao menos uma evidência candidata.
    - Sem candidata: ROTA-002, ROTA-004, ROTA-005, ROTA-011, ROTA-013, ROTA-018 e ROTA-020.
    - Duas delas (013, 018) não são referenciadas por nenhum teste.
    - **Correção da rodada 3:** a ROTA-020 **tem** teste HTTP (`EmergencyRecoveryFlowIT`). O caminho é montado como `"aviso/" + rota`, e nem a busca anterior nem o extrator o viram. Esses testes cobrem sucesso com efeito, token de outra finalidade, de outra organização, malformado e recusa após o término. A GAP-01 complementa CSRF/Origin, reuso, vencimento e ausência de efeito na recusa.

**I1, contrato:**
- as rotas do contrato vêm de `2026-09-26-decisoes-8-6-8-7-8-10.md` §5.3 e §5.4 (8.7);
- as do MFA emergencial, de `2026-09-27-contrato-recuperacao-emergencial-mfa.md`;
- o modelo dos domínios, do `Radiant-Relatorio-Decisoes-Aprovadas-2026-09-24.md`;
- a autorização, de `2026-09-25-preparacao-autorizacao-api-nekkora.md`;
- os gates, de `2026-09-26-validacao-api-v1.md`.

## 3. Resumo por domínio

**Achado estrutural.** O contrato HTTP aprovado (8.7) define a **API v1 como a fatia de identidade, sessão e acesso** mais o **portal externo somente leitura**. Os domínios do produto (workspaces, automações, execuções, agendamentos e Workers/Agents) têm **modelo de dados aprovado e implementado no banco** (migrations 001–012), mas **nenhum contrato HTTP e nenhuma rota**. As decisões Radiant §2 fixam a ordem "fundação de dados → consolidação do Agent → **Platform API** → Studio…".

| Domínio | Rotas | Situação |
|---|---|---|
| Identidade e sessão (login, MFA, step-up, sessão, senha, recuperação) | 14 (ROTA-001 a ROTA-014) | **Implementado e conforme a §5.3**. Recuperação de senha e avisos dependem de e-mail real para produção. **Verificação de e-mail: só a confirmação**; nada na API emite o token |
| MFA emergencial | 3 (ROTA-019 a ROTA-021) + CLI | Implementado e homologado (MFA-CONSOL-1). Abertura, aprovação e execução ficam na CLI de operação, o outro canal previsto |
| Organizações e membros | 2 (suspender e reativar) + reset de MFA | **Parcial para o frontend**: as ações existem, mas **não há rota para listar membros**. Convite e aceite: **adiados** (8.7 §1) |
| Papéis e permissões | 0 | **Ausente**: nem consulta de permissões efetivas nem atribuição. A matriz exata ainda está pendente (Radiant §6) |
| Clientes e acesso externo | 1 (revogar concessão) | **Parcial**: sem listar, criar, aprovar ou revisar concessões; a revogação **não tem teste** |
| Workspaces, automações, execuções (interno), agendamentos, Workers/Agents | 0 | **Ausente na API; existe no banco** |
| Comunicação do Agent | 0 | **Ausente e sem protocolo decidido** |
| Portal externo | 4 (ROTA-022 a ROTA-025) | **Implementado e conforme a §5.4**: isolamento nas funções da 013; limites de 200 workspaces e 1.000 eventos (acima deles, 500, sem truncar) |
| Apoio | `/error` interno | Sem rota de saúde ou prontidão; sem OpenAPI |

## 4. Jornadas mínimas por ator

Papéis reais da 003: `ORG_ADMIN`, `WORKSPACE_ADMIN`, `OPERATOR`, `VIEWER`. Permissões: `org.manage`, `role.assign_admin_roles`, `role.assign_operator_viewer`, `workspace.lifecycle`, `workspace.configure`, `automation.read`, `automation.execute`, `logs.read`, `external_access.manage`, mais `identity.mfa.reset` (014). As identidades técnicas de banco são separadas: `nekkora_platform`, `nekkora_portal` e `op_*`/`nekkora_ops_*`.

| Ator | Jornada | Suporte hoje |
|---|---|---|
| Qualquer conta humana | login → MFA (cadastro ou verificação) → sessão → `me` → troca de senha → logout/revoke-all | **Suportada** (ROTA-001 a ROTA-010 e ROTA-014) |
| Conta (pré-login) | recuperação de senha por e-mail | Suportada na API; **produção exige e-mail real** |
| Conta nova | verificação de e-mail | **Não fecha**: só há a confirmação; nenhuma rota emite o token |
| Administração interna (`ORG_ADMIN`) | ver membros → suspender/reativar → resetar MFA → revogar concessão | **Parcial**: as ações existem, mas o frontend **não tem como listar** membros e concessões para obter os IDs |
| Administração interna | atribuir papéis, criar workspaces, convidar contas | **Não suportada** (ausente ou adiado) |
| Operação (`OPERATOR`) | listar automações → executar → acompanhar → cancelar | **Não suportada** |
| Leitura interna (`VIEWER`) | consultar workspaces, execuções, eventos e logs | **Não suportada** (o portal só atende clientes externos) |
| Usuário externo (portal) | login pela Platform → workspaces concedidos → execuções (filtros, cursor) → detalhe → eventos | **Suportada** |
| Operador (MFA emergencial) | vínculo pela CLI → recuperação emergencial → avisos e contestação por link | **Suportada** (CLI + ROTA-019 a ROTA-021) |
| Agent / serviço | registro, heartbeat, receber trabalho, reportar | **Não suportada; canal não definido** |

O que o frontend **consegue obter hoje:** a identidade da sessão (`me`: tipo, nome, e-mail, organização) e, no portal, os workspaces e execuções concedidos.

O que **teria de inventar ou obter por acesso indevido ao banco:**
- as permissões efetivas, para decidir que ações mostrar;
- os IDs de usuários e concessões para as ações administrativas;
- todo o conteúdo interno do produto: workspaces, automações, execuções, agendamentos e Workers.

**OpenAPI:** **não existe** no repositório (sem springdoc e sem arquivo de especificação). O contrato é a tabela do 8.7 §5.3 e §5.4, que **coincide** com as rotas implementadas, mais o contrato do MFA emergencial.

## 5. Lacunas e mini missões (resumo; detalhe em `C_lacunas.tsv`)

**P0:** nenhum caminho causal de falha de segurança ou isolamento foi identificado na leitura.

| ID | Item | Prioridade |
|---|---|---|
| GAP-01 | Testes HTTP de `client-grants/{grant}/revoke` e `email-verification/confirm` (sem teste), mais o complemento de `aviso/contestar` (CSRF/Origin, reuso, vencimento, sem efeito na recusa) | **P1** |
| GAP-02 | Consulta de permissões efetivas da sessão | **P1** (depende de D-INV-01) |
| GAP-03 | Leitura administrativa mínima: listar membros e concessões, paginado e com permissão | **P1** |
| GAP-05 | OpenAPI consolidado das 25 rotas publicadas | **P1** |
| GAP-06 | API interna de leitura de workspaces, automações e execuções | **P1, se a D-INV-01 incluir na v1** |
| GAP-04 | Emissão do token de verificação de e-mail | P2 |
| GAP-07 | Execução manual e cancelamento | P2 (o fluxo real depende do Agent) |
| GAP-08 | Gestão de workspaces, agendamentos e Workers | P2 |
| GAP-09 | Canal do Agent | P2 |
| GAP-10 | Entrega real de e-mail | P2 (gate de produção) |
| GAP-11 | Saúde e prontidão | P3 |
| GAP-12 | Paginação real no portal | P3 |
| GAP-13 | Gestão de sessões individuais | P3 |
| **GAP-14** | Atribuição e remoção de papéis (regras da 003) | P2 (v1 completa; D-INV-09) |
| **GAP-15** | Criação, aprovação e revisão de concessões; cadastro de clientes e contas externas | P2 (v1 completa; D-INV-10) |
| **GAP-16** | Relatórios publicados no portal (sem modelo de dados no schema atual) | P3 ou adiamento explícito (D-INV-11) |

**Dependências (rodada 2):**
- GAP-05 (OpenAPI) depende da **D-INV-06** e é construída **acompanhando** cada mini missão, para ser consolidada antes da integração.
- GAP-03 e GAP-06 dependem só do recorte (D-INV-01), **não** da GAP-02.
- A autorização de cada rota nova é aplicada **no backend**, pela permissão da 003 ou pelo acesso por workspace com RLS, **independentemente** da consulta de permissões que o frontend use para montar a interface.
- GAP-07 e GAP-08 (ações do produto) permanecem na **v1 completa**.

**Ordem sugerida:**
1. **GAP-01**, já, sem decisão pendente;
2. **D-INV-01** (recorte da primeira entrega) e **D-INV-06** (formato do OpenAPI);
3. **GAP-02**, **GAP-03** e **GAP-06**, independentes entre si, cada uma com sua parte do OpenAPI (GAP-05);
4. depois, a v1 completa: GAP-07, GAP-08, GAP-14, GAP-15 e GAP-09, conforme as decisões.

Cada mini missão tem resultado verificável e critério de aceite na tabela C.

## 6. Decisões da responsável

| ID | Decisão | Recomendação | Consequência |
|---|---|---|---|
| **D-INV-01** | Recorte da **primeira entrega** do frontend interno (entrega por etapas, **sem reduzir a v1**): identidade + leitura (permissões efetivas, membros e concessões, workspaces, automações, execuções), ou outro recorte? | Primeira entrega = identidade + leitura (GAP-02, GAP-03, GAP-06). Execução, agendamento e gestão de Workers seguem na **v1 completa**, em etapas seguintes. A redução de escopo da v1 **não** é proposta | Com esse recorte: 3 mini missões P1 antes da primeira integração. As demais ficam planejadas para a v1 completa |
| D-INV-02 | Convite e onboarding continuam adiados? | Manter adiado. Registrar que contas nascem por provisionamento, com um procedimento operacional documentado | Sem onboarding pela UI na primeira integração |
| D-INV-03 | Onde nasce o token de verificação de e-mail (provisionamento, convite, troca de e-mail)? | Decidir junto com a D-INV-02 | Sem decisão, a ROTA-013 fica órfã |
| D-INV-04 | Canal e protocolo do Agent e o lugar da fila (Radiant: Agent antes da Platform API) | Missão de desenho própria, antes de qualquer rota de execução | Bloqueia execução real, não as telas de leitura |
| D-INV-05 | Publicação de versões de automação na v1 ou só com o Studio? | Fora da v1 (Radiant §10: Studio em fase própria) | Sem publicação pela API |
| D-INV-06 | Formato e ferramenta do OpenAPI (escrito à mão e versionado, ou gerado e revisado) | Escrito e revisado contra a tabela A | Define a etapa "consolidar HTTP/OpenAPI" |
| D-INV-07 | Saúde e prontidão: interna (não publicada) ou não agora | Interna, P3 | Afeta implantação, não o frontend |
| D-INV-08 | Gestão de sessões individuais na v1 | Fora da v1 (revoke-all atende) | — |
| **D-INV-09** | Atribuição e remoção de papéis pela API (GAP-14): v1 completa ou adiamento explícito? | v1 completa, depois de GAP-03 (sem listar membros não há a quem atribuir) | Adiar mantém a gestão de acesso por banco/provisionamento |
| **D-INV-10** | Ciclo de acesso externo pela API: concessão, aprovação, revisão, cliente e conta externa (GAP-15) | v1 completa, em mini missões separadas. Depende de D-INV-02/03 e do e-mail real para o convite | Adiar mantém o portal alimentado só por provisionamento |
| **D-INV-11** | Relatórios publicados no portal (GAP-16) | Decidir antes o modelo de dados (não existe no schema atual). Recomendo adiamento explícito para depois da v1 de leitura de execuções | Sem decisão, a capacidade fica sem destino |

## 7. Respostas diretas

1. **Jornadas que a API já suporta:**
   - login com MFA (cadastro e verificação), step-up, conta (`me`), troca e recuperação de senha, logout e revoke-all;
   - administração por ID: suspender, reativar, resetar MFA e revogar concessão;
   - MFA emergencial (rotas + CLI);
   - portal externo completo de leitura: workspaces, execuções, detalhe e eventos.
2. **Jornadas sem endpoints suficientes:**
   - verificação de e-mail ponta a ponta (sem emissão do token);
   - administração que precisa listar (membros, concessões) e atribuir papéis;
   - consulta de permissões efetivas;
   - **todo o produto interno**: workspaces, automações, execuções, agendamentos e Workers/Agents;
   - convite e onboarding (adiado);
   - comunicação do Agent.
3. **Rotas que existem mas carecem de teste ou contrato:**
   - sem teste HTTP: `client-grants/{grant}/revoke` e `email-verification/confirm`;
   - `mfa-emergencia/aviso/contestar` tem teste HTTP (correção da rodada 3), sem CSRF/Origin, reuso e vencimento;
   - sem evidência candidata localizada pela extração (não auditadas): ROTA-002, ROTA-004, ROTA-005 e ROTA-011;
   - isolamento: a única candidata localizada é **por cliente** (ROTA-024). Isolamento entre **organizações** não tem evidência de teste conferida para nenhuma rota (ver `D_cenarios.tsv`; conferência pendente);
   - **todas** as 25 rotas publicadas carecem de contrato OpenAPI. O contrato hoje é a tabela do 8.7.
4. **Dependências de e-mail real, Agent ou outra infraestrutura:**
   - **e-mail real:** recuperação de senha, verificação de e-mail, avisos de MFA e de senha, avisos do MFA emergencial;
   - **Agent:** execução real, cancelamento confirmado, heartbeat, eventos e logs de execuções novas;
   - **Redis:** CSRF de pré-autenticação e R7;
   - **CLI de operação:** recuperação emergencial.
5. **Primeiras mini missões recomendadas:**
   - **GAP-01** (testes específicos das 3 rotas, com critérios conforme a autenticação de cada uma), sem decisão pendente;
   - depois das D-INV-01 e D-INV-06: **GAP-02**, **GAP-03** e **GAP-06**, independentes entre si, cada uma com a autorização no backend e a sua parte do OpenAPI;
   - **GAP-05** consolida o OpenAPI antes da integração.
6. **Condições para liberar a integração do frontend:**
   - (a) D-INV-01 decidida;
   - (b) as mini missões P1 aplicáveis concluídas com testes HTTP;
   - (c) OpenAPI consolidado e conferido contra a implementação;
   - (d) regressão HTTP das jornadas com os papéis reais;
   - (e) ambiente e dados sintéticos para o frontend.
   - A entrega real de e-mail e o Agent **não** bloqueiam a primeira integração de telas de leitura, mas bloqueiam a produção e as jornadas de execução.

## 8. Escopo cumprido

- Nenhuma alteração funcional, migration, permissão, teste, OpenAPI, frontend ou envio real.
- Nenhuma execução de Maven, Docker, testes HTTP ou consulta a banco.
- Nenhum fetch nem publicação.
- Os documentos ficam sem stage nem commit.
- A árvore de trabalho não foi alterada além deste relatório e das tabelas na evidência.

## Adendo (2026-10-03T02:13:56Z): decisões D-INV-01 e D-INV-06

Decisões da responsável, em resposta à pergunta do executor, alinhadas à recomendação da revisão.

| Decisão | Escolha | Consequência |
|---|---|---|
| **D-INV-01**, recorte da primeira entrega do frontend interno | **Identidade + administração mínima + leitura do produto interno** | Mini missões da primeira entrega: **GAP-02** (permissões efetivas), **GAP-03** (listar membros e concessões) e **GAP-06** (leitura de workspaces, automações e execuções). Execução, agendamento e Workers/Agents **continuam no escopo da v1 completa** (GAP-07, GAP-08, GAP-09), em etapas seguintes |
| **D-INV-06**, contrato OpenAPI | **Arquivo OpenAPI versionado no repositório**, escrito e revisado junto de cada mini missão e conferido contra os testes HTTP | A GAP-05 deixa de ser uma entrega isolada. Cada mini missão (GAP-02/03/06) entrega a sua parte do contrato, e a consolidação acontece antes da integração do frontend. Sem gerador de contrato a partir das anotações |

**Continuam pendentes:** D-INV-02/03 (convite e emissão da verificação de e-mail, GAP-04), D-INV-04 (canal do Agent), D-INV-05, D-INV-07, D-INV-08, D-INV-09, D-INV-10 e D-INV-11.
