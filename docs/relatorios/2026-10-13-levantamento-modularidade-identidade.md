# Levantamento de modularidade antes das rotas de identidade

**Data:** 2026-10-13. **Natureza:** só leitura. Nenhum código, POM ou configuração foi alterado por este levantamento.

**Base:** árvore de trabalho sobre o commit `20ccb06`, com a 017 e com o `IdentityTransactions` aprovado.

**Objetivo:** mapear responsabilidades e dependências atuais e propor o lugar dos serviços de identidade, **sem** transformar esta etapa numa migração para microserviços.

**Revisão de 2026-10-13**, depois da leitura da responsável. Ela corrige:
- a contradição sobre `auth.crypto` (§4.2);
- o serviço único de identidade, que passa a ser um serviço por fluxo (§4.1);
- o contrato da porta de emissão (§4.3);
- a tabela das rotas, que passa a classificar cada endpoint (§5).

A versão anterior está preservada no pacote `pacote-017-java-r1-complemento-2026-10-13`.

## 1. Mapa atual

**Módulos Maven:** quatro, sob o pai `nekkora-api`. Juntos têm cerca de 8,2 mil linhas no código principal.

| Módulo | Executável | Responsabilidade | Depende de (escopo principal) |
|---|---|---|---|
| `nekkora-core` | — (biblioteca) | transação por organização (`OrgTransactions`) e por identidade (`IdentityTransactions`); reset de conexão; tokens; limitador Redis; filtros e erros HTTP comuns; IP do cliente | Spring Web MVC, JDBC, Data Redis e driver PostgreSQL |
| `nekkora-platform` | `PlatformApplication` | API da plataforma: autenticação por organização, MFA, sessões, administração, leituras de produto, avisos de emergência, e-mail | `nekkora-core`, Spring Security e Bouncy Castle |
| `nekkora-portal` | `PortalApplication` | portal externo, só leitura, com sessão externa | `nekkora-core` |
| `nekkora-ops` | `OpsCli` | ferramenta de operação: CA, verificação do IdP e atos da 016 | PostgreSQL, Bouncy Castle e Jackson. **Não** depende do core |

**Dependências só de teste:** `nekkora-platform` depende do `test-jar` do `nekkora-ops` e do `nekkora-core`, para usar `OpsBanco`, `Cenarios` e `RealDatabase`. No código principal, a Platform **não** depende do Ops. Os dois só se encontram no banco, pelas funções da 016.

**Isolamento real:** já existe uma fronteira forte, mas ela está **no banco**, não no Java:
- papéis separados (`nekkora_platform`, `nekkora_portal`, papéis `ops_*`);
- RLS por organização e por identidade;
- funções `SECURITY DEFINER` com donos próprios.

Os dois executáveis HTTP compartilham só o `nekkora-core`.

## 2. Responsabilidades dentro do `nekkora-platform`

| Pacote | Classes principais | Papel |
|---|---|---|
| `web` | 13 classes: controllers, `SessionFilter`, `CsrfFilter`, `PreAuthCsrf`, `Routes`, `Cookies` | borda HTTP: roteamento por `/api/v1/o/{org}/…`, contexto de CSRF, cookies e R7 |
| `auth` | `AuthService` (587 linhas), `crypto/*` | `AuthService`: login, cadastro e confirmação de MFA, verificação, logout, revogação, step-up, troca e recuperação de senha, confirmação de e-mail. `crypto`: Argon2, TOTP, cifra de segredo, códigos de recuperação, Base32 e HMAC |
| `account`, `session`, `mfa` | um repositório em cada | acesso a `users`, `user_credentials`, `user_sessions`, `auth_challenges`, fatores e códigos. **Sem estado**: recebem `(JdbcClient, org, …)` e a transação vem de quem chama |
| `access` | `WorkspaceAccess`, `PermissionCatalog` | autorização por papel e por workspace |
| `admin`, `emergency`, `mail`, `limits`, `paging` | um serviço em cada | administração, avisos da 016, envio, limites, cursores |
| `config` | `PlatformConfig` (25 beans), `SecurityConfig`, `PlatformProperties` | montagem |

## 3. Dependências internas: o que ajuda e o que atrapalha

**O que ajuda:**
- **Repositórios sem estado e transação explícita.** A transação nunca é aberta dentro do repositório; quem abre é o serviço, por `OrgTransactions`. O `IdentityTransactions` segue o mesmo padrão, o que facilita a separação.
- **`auth.crypto` não depende de organização.** Só importa `core.token` e `core.web`, então serve à identidade sem mudança. É a única parte de `platform.auth` que a identidade pode importar (§4.2). O mesmo vale para `Limits`, que depende só do core e de `PlatformProperties`, e para `MailSender`.
- **Fechamento por padrão nas rotas sem organização.** `Routes.csrfContext` usa o URI inteiro quando a rota não é `/o/{org}`, e o padrão é `SESSION`, que exige uma sessão de organização. `SecurityConfig` só libera `POST`s públicos listados, todos sob `/api/v1/o/*/auth/…`. Uma rota de identidade nova, portanto, **é recusada** até ser mapeada explicitamente.

**O que atrapalha:**
1. **`config` é um ponto de convergência com ciclos.** Há ciclos `config ↔ auth`, `config ↔ limits`, `config ↔ session` e `config ↔ web`, porque `PlatformProperties` mora em `config` junto com a montagem dos beans. Não quebra nada, mas impede tratar qualquer pacote como unidade separável.
2. **`AuthService` concentra todo o ciclo de autenticação por organização.** Depende de 7 pacotes da Platform (`account`, `session`, `mfa`, `crypto`, `limits`, `mail`, `config`). Pôr a identidade aqui aumentaria o acoplamento justamente onde ele já é maior.
3. **Controllers acessam o banco diretamente.** `ProductReads`, `AdminReadController`, `ProductReadController`, `PermissionsController` e `AccountController` usam `JdbcClient` e `OrgTransactions` direto, sem um serviço no meio. Para leituras simples isso é aceitável, mas não deve virar o modelo das rotas de identidade, que têm regras de sequência (§1.3 do contrato).
4. **Os filtros são acoplados ao formato da rota de organização.** `SessionFilter` e o R7 decidem pela presença de `/o/{org}`. Fora dele, a chave do R7 é o IP. Isso importa para as rotas de identidade (§5).

## 4. Onde ficam os serviços de identidade (recomendação)

**Ficam no `nekkora-platform`, num pacote próprio, e não num módulo ou serviço novo.** Há três razões:

1. **A troca de identidade para sessão de organização** (`POST /api/v1/sessions/organization`, desenho §6.5) precisa dos dois mundos em sequência: `identity_org_session_target`, depois `inOrganization`, o lock da conta e a criação de `user_sessions` com `identity_session_id`. Separar em processos exigiria uma chamada remota no meio dessa sequência, o que é pior para atomicidade e para a resposta uniforme (DC-17.6).
2. **O papel de banco é o mesmo.** As 16 funções da 017 estão concedidas a `nekkora_platform`, e um executável novo exigiria outro papel e outra matriz de privilégios na 017.
3. **O isolamento que importa já está no banco:** RLS por identidade, funções de chave exata e `identity_credentials` sem `SELECT`. Um módulo Java a mais não acrescenta proteção a isso.

### 4.1 Serviços separados por fluxo

**Correção de 2026-10-13, apontada pela responsável.** A versão anterior propunha um único `IdentityService` com cadastro, login, MFA, senha, sessões e organizações. Isso repetiria a concentração já apontada no `AuthService` (§3, item 2). Dentro de `platform.identity`, os serviços ficam **separados por fluxo**. Nomes indicativos:

| Serviço | Fluxo | Rotas (desenho §6) |
|---|---|---|
| `SignupService` | cadastro, confirmação e reenvio | `/signup`, `/signup/confirm`, `/signup/confirmation/resend` |
| `IdentityLoginService` | entrada geral, desafio, MFA da entrada e logout | `/auth/login`, `/auth/mfa/enroll/start`, `/auth/mfa/enroll/confirm`, `/auth/mfa/verify`, `/auth/logout` |
| `IdentityCredentialService` | troca de senha, substituição e recuperação do fator (DC-17.13) | ainda sem contrato HTTP no desenho |
| `IdentityStepUpService` | step-up da sessão de identidade (P1) | `/auth/step-up` |
| `IdentityOrganizationsService` | organizações da identidade e criação de organização | `GET /me/organizations`, `POST /organizations` |
| `OrgSessionExchange` | troca por sessão de organização, pela porta da §4.3 | `POST /sessions/organization` |

**Nota de 2026-10-14 (P1).** O step-up saiu de `IdentityCredentialService` para um serviço próprio, `IdentityStepUpService`. Ele é pré-condição de outros fluxos (criação de organização, P2; vinculação, P3), não parte da troca de senha ou de fator. Assim a regra de separar por fluxo continua valendo.

**Nota de 2026-10-14 (P2).** A criação de organização entrou em `IdentityOrganizationsService`, como esta tabela previa, ao lado da listagem que calcula `canCreateOrganization`. A rota chama só `identity_create_organization` e não importa nada de `platform.auth` além do que a listagem já usava.

**O que eles compartilham:** só infraestrutura, ou seja, `IdentityTransactions`, `auth.crypto`, `Limits`, `MailSender` e os repositórios sem estado de `platform.identity`, no padrão de `account`, `session` e `mfa`.

**O que não compartilham:** um serviço de fluxo não chama outro. Se dois fluxos precisarem da mesma regra, ela vai para um componente comum sem estado, ou fica no banco, e não para um serviço que orquestre os demais.

### 4.2 Estrutura e regras de dependência

| Onde | O quê |
|---|---|
| `core.db.IdentityTransactions` | já existe e fica onde está, ao lado de `OrgTransactions` |
| `platform.identity` (novo) | os serviços da §4.1 e os repositórios sem estado |
| `platform.identity` → organização | a porta estreita da §4.3, por exemplo `OrgSessionIssuer`. É o único ponto em que a identidade toca código de organização |
| `platform.web` | `IdentityController`, ou um controller por fluxo, e `IdentityFilter`, irmão do `SessionFilter` (desenho §6.7). Mais os contextos `IDENTITY_CHALLENGE`, `IDENTITY_SESSION` e `IDENTITY_LOGOUT` em `Routes`/`CsrfFilter`, e os cookies `__Host-nekkora_id` e `__Host-nekkora_id_mfa` em `Cookies` |
| reaproveitados sem mudança | `auth.crypto.*`, `Limits` (com as regras R8 a R12 do desenho §7) e `MailSender` |

**Regras de dependência:**
- **`platform.identity` pode importar `platform.auth.crypto` e nada mais de `platform.auth`.** Correção de 2026-10-13, apontada pela responsável: a versão anterior proibia qualquer importação de `platform.auth` e, ao mesmo tempo, propunha reaproveitar `auth.crypto`, o que era contraditório. A exceção explícita evita mover código já validado só para reorganizar pacotes.
- **`platform.auth` (fora de `crypto`) e `platform.identity` não se importam, em nenhum sentido.** A entrada local de conta vinculada é recusada pelo banco (DC-17.9), não por chamada Java entre os serviços de autenticação local e global.
- **`platform.auth.crypto` continua sem importar nada de `platform`.** Hoje ele só importa `core.token` e `core.web` (§3). É isso que o torna reaproveitável.
- **`platform.identity` usa `OrgTransactions` só na porta da §4.3.**
- **Serviços de fluxo não se importam entre si** (§4.1).
- **Controllers de identidade não acessam `JdbcClient` diretamente:** tudo passa pelos serviços e pelo `IdentityTransactions`.

**Verificação:** essas regras serão verificadas por teste na etapa das rotas, desde o primeiro bloco, com ArchUnit ou um teste simples sobre os imports. O teste não foi criado neste levantamento.

**Quando extrair para um módulo Maven `nekkora-identity`:** só se aparecer um segundo consumidor real (outro executável), se a identidade passar a ter ciclo de implantação próprio, ou se as regras acima não puderem ser mantidas por teste. Nenhum desses critérios vale hoje.

### 4.3 Contrato da porta de emissão da sessão de organização

**Precisão de 2026-10-13, pedida pela responsável:** a resolução inicial **não autoriza sozinha** a emissão. `identity_org_session_target` roda antes da transação da organização, e entre as duas o vínculo, a sessão de identidade ou o estado da conta podem mudar. No momento da criação da `user_sessions`, os três precisam continuar válidos.

**O que o banco já garante na criação.** A 017 entrega isso pela trigger `enforce_user_session_mfa_on_insert` (`017_identidade_cadastro.sql`, linha 1678). Na mesma transação do `INSERT`, ela:
- toma o lock da conta (`SELECT … FROM users … FOR UPDATE`) e recusa a operação se a conta não estiver visível no contexto;
- chama `identity_org_session_allowed` (linha 1513), que reconfere no ramo 2:
  - o **vínculo**: a conta está em `identity_users` e ligada à identidade da sessão;
  - a **sessão de identidade**: não revogada, não expirada, com MFA e não restrita;
  - a **identidade**: `status = 2`, com fator de identidade confirmado e habilitado.

**Lacuna encontrada na leitura.** Na R3, nem a trigger nem `identity_org_session_allowed` reconferiam, na criação, o **estado da conta e do vínculo com a organização**: `account_type`, `status` e e-mail verificado da conta, `status` da organização e `status` do vínculo interno. Essas condições só eram lidas na resolução inicial.

**Decisão da responsável (2026-10-13): revisar a 017.** A garantia passou para o banco na **R4**, descrita no desenho, §13.5:
- no ramo 2, a trigger trava a organização `FOR SHARE` antes da conta;
- a auxiliar reconfere as cinco condições depois dos locks;
- na R4b, a trigger passou a exigir READ COMMITTED no ramo 2;
- o ramo 1 fica inalterado;
- a suíte da 017 ganhou o grupo Q e as mutações M8, M9 e M10.

**Contrato da porta**, dentro de uma única transação READ COMMITTED com os contextos da organização alvo e da identidade (`IdentityTransactions.inIdentityAndOrganization`; revisão 1 do bloco 3, ver a correção abaixo):
1. Recebe só `(organizationId, userId, identitySessionId)` e o MFA herdado. A candidata é tratada como tal, nunca como autorização.
2. **Pré-condição de quem chama (revisão 2):** a ordem global da 017 é identidade → sessão de identidade → organização → conta. Quem chama a porta já segura a identidade e a sessão de identidade (`FOR UPDATE`, pela `identity_org_session_target` repetida na mesma transação, que também reconfere a candidata). Depois, a porta trava a **organização `FOR SHARE`** antes das sessões da conta. `SessionRepository.create` revoga sessões excedentes antes do `INSERT`, e sem esse lock a ordem ficaria invertida em relação a `suspend`.
3. Trava a **conta `FOR UPDATE` depois da organização e antes da política de limite**: organização → conta → conferência e revogação das excedentes → `INSERT`.
4. Cria a `user_sessions` com `identity_session_id`, numa variante de `create` que grave essa coluna. A trigger reconfere vínculo, sessão de identidade, identidade e as cinco condições de elegibilidade.
5. Em qualquer recusa, devolve o `sem_acesso` uniforme da DC-17.6, sem distinguir a causa.
6. A transação é **READ COMMITTED**. O banco recusa a emissão vinculada em outro isolamento (R4b, desenho §13.5).
7. O evento da emissão, ou da recusa, é gravado **na mesma transação**.

**Correção de 2026-10-13 (bloco 3, revisão 1), apontada pela responsável.** A versão anterior dizia "não trava a conta antes da organização" e deixava o lock da conta com a trigger, o que permitia ultrapassar o teto de sessões sob concorrência (desenho §13.5, requisito 2). Ela também previa a emissão numa transação só de organização, com o evento gravado depois, o que deixava sessão emitida sem evento, ou erro na resposta com a sessão já criada. A porta passou a rodar na transação de quem chama. Os pacotes Java continuam separados: a identidade só alcança a emissão pela interface `OrgSessionIssuer`.

**Correção de 2026-10-14 (bloco 3, revisão 2), apontada pela responsável.** O item 2 dizia "organização `FOR SHARE` antes de qualquer outra coisa". Isso contrariava a ordem global da 017 e ignorava os locks implícitos das chaves estrangeiras: o `INSERT` da sessão e o do evento pedem `FOR KEY SHARE` na sessão de identidade e na identidade, depois da organização. O resultado era um ciclo com a vinculação, reproduzido (`40P01`). Agora a identidade e a sessão de identidade vêm primeiro (desenho §13.5, requisitos 1 e 7). A mesma auditoria corrigiu o logout da identidade, que revogava a sessão antes de travar a identidade.

## 5. Classificação das rotas de identidade

**Correção de 2026-10-13, apontada pela responsável.** Cada endpoint é classificado nos quatro eixos abaixo. Isso **não** significa incluir todos na lista pública nem em `reducesAccess`: a lista pública e `reducesAccess` recebem só o que a tabela marca.

A coluna "lista pública" reflete o padrão atual de `SecurityConfig.PUBLIC_POST_ROUTES`. Nele, as rotas de desafio de MFA e o logout da organização já estão na lista, porque a autenticação delas é feita pelo filtro e pelo serviço, não pelo Spring Security.

| Rota | Autenticação | CSRF (desenho §6.7) | Limites (desenho §7) | Lista pública | `reducesAccess` |
|---|---|---|---|---|---|
| `POST /api/v1/signup` | nenhuma (pré-autenticação) | `PRE_AUTHENTICATION` | R9, R12 | sim | não |
| `POST /api/v1/signup/confirmation/resend` | nenhuma | `PRE_AUTHENTICATION` | R9b, R12 | sim | não |
| `POST /api/v1/signup/confirm` | token de confirmação | `PRE_AUTHENTICATION` | R12 | sim | não |
| `POST /api/v1/auth/login` | nenhuma | `PRE_AUTHENTICATION` | R8, R12 | sim | não |
| `POST /api/v1/auth/mfa/enroll/start` | desafio de identidade | `IDENTITY_CHALLENGE` | R12 | sim | não |
| `POST /api/v1/auth/mfa/enroll/confirm` | desafio de identidade | `IDENTITY_CHALLENGE` | R8m, R12 | sim | não |
| `POST /api/v1/auth/mfa/verify` | desafio de identidade | `IDENTITY_CHALLENGE` | R8m, R12 | sim | não |
| `POST /api/v1/auth/logout` | sessão de identidade; sem ela, no-op | `IDENTITY_LOGOUT` | **nenhum** | sim | **sim** |
| `GET /api/v1/me/organizations` | sessão de identidade, não restrita | — (leitura) | R12 | não | não |
| `POST /api/v1/sessions/organization` | sessão de identidade, não restrita | `IDENTITY_SESSION` | R10, R12 | não | não |
| `POST /api/v1/organizations` (P2, implementada em 2026-10-14) | sessão de identidade, não restrita, step-up em 5 min (conferido pela função) | `IDENTITY_SESSION` | R11 (reserva por identidade), R12 | não | não |
| `POST /api/v1/auth/step-up` (P1, 2026-10-14) | sessão de identidade, não restrita | `IDENTITY_SESSION` | R8m (identidade global), R12 | não | não |

As rotas da DC-17.13 (troca de senha, substituição e recuperação do fator) e as de vinculação (DC-17.11) **ainda não têm contrato HTTP** no desenho. Elas serão classificadas nesta mesma tabela quando o contrato for fixado, antes de entrarem em código. O step-up da identidade foi classificado em 2026-10-14, com o contrato P1.

**Pontos de atenção da etapa das rotas:**

| Ponto | Situação atual | O que a etapa das rotas precisa fazer |
|---|---|---|
| contexto de CSRF das rotas sem organização | o padrão é `SESSION`, que fecha | mapear cada rota explicitamente, conforme a tabela acima |
| `PUBLIC_POST_ROUTES` | só rotas `/o/*/…` | acrescentar exatamente as rotas marcadas "sim" na tabela |
| R7 (balde de escrita) | chave pela sessão de organização ou, fora dela, pelo IP | o desenho §7 define R8 a R12 para as rotas sem organização. **Decisão em aberto:** se as escritas com sessão de identidade também passam pelo R7, com a chave da sessão de identidade fornecida pelo `IdentityFilter`. O logout fica fora de qualquer limitador |
| `Routes.reducesAccess` | compara o sufixo da rota de organização (`/auth/logout`, `/auth/sessions/revoke-all`) | não cobre `/api/v1/auth/logout`; precisa de entrada explícita, e só dela |
| os dois cookies | `SessionFilter` só em `/o/{org}` | o `IdentityFilter` só fora de `/o/{org}`; os dois cookies nunca na mesma rota (desenho §6.7) |
| `IdentityTransactions` | conferência de conexão condicionada a `DataSourceTransactionManager` | manter esse gerenciador ou recusar outro no construtor (requisito registrado em 2026-10-13) |
| porta da troca | resolução inicial e criação em transações distintas | contrato da §4.3; a elegibilidade está garantida no banco pela R4 da 017 |
| montagem | `PlatformConfig` com 25 beans | os beans de identidade podem ir numa `IdentityConfig` própria, sem aumentar os ciclos de `config` |

## 6. O que este levantamento não fez

- Não alterou código, POM, configuração nem a migration 017.
- Não criou o pacote `platform.identity` nem o teste de arquitetura.
- Não propõe separar os executáveis existentes nem extrair microserviços. Os ciclos de `config` e os controllers com acesso direto ao banco ficam registrados como dívida, sem correção nesta etapa.
