# Contexto da pessoa para o Nekkora Agent (Client)

Missão AGENT-CLIENT-NAVEGACAO-001 · T01. Versão do contrato: `desktop-navigation/1`. Estende o `desktop-login/1`
(`docs/agent-client-login-001/t02-login-do-aplicativo-desktop.md`). OpenAPI: `api/openapi/nekkora-api-v1.yaml`, tag
"Login do aplicativo desktop".

## 1. O que muda

O aplicativo, depois do login pelo navegador, passa a poder perguntar **quem é a pessoa, em quais organizações ela
está e o que ela vê em cada uma** (workspaces, automações, versões), e a **renomear uma automação** onde ela já pode.
Tudo com o mesmo token Bearer opaco de 15 minutos, em rotas só do aplicativo (`/api/v1/desktop/…`).

Nada disso é concedido a quem já tem token: é um **escopo novo, pedido no login e aprovado pela pessoa**.

## 2. Escopo

| `scope` no pedido | O que a sessão do desktop pode |
|---|---|
| `identity` | Como antes: `GET /desktop/session` e `POST /desktop/session/revoke` |
| `identity navigation` | O anterior e as rotas da seção 4 |

- O texto é **exato**. Outra ordem, caixa, separador ou espaço a mais: `400`, como qualquer escopo desconhecido.
- O escopo viaja do pedido para o código e do código para a sessão. `POST /desktop/token` e `GET /desktop/session`
  devolvem o escopo concedido em `scope`. A troca não pede nem amplia escopo.
- `GET /api/v1/desktop/authorization` (página de confirmação, no navegador) devolve também `scope`, o escopo pedido,
  para a Web dizer à pessoa o que ela está concedendo. `application` e `account` não mudam.
- Sessão emitida com `identity` recebe `403 {"error":"forbidden"}` em toda rota de navegação e **nunca** ganha o
  outro escopo: é preciso entrar de novo. Pedido, código ou sessão gravados pela versão anterior (sem escopo) são
  lidos como `identity`.

## 3. Como a pessoa é conferida (fotografia na aprovação + conferência a cada requisição)

**O problema.** Listar as organizações de uma identidade exige hoje o token bruto da sessão do navegador
(`identity_organizations(token)`, `identity_org_session_target(token, org)`), e a plataforma não lê `identity_users`.
A sessão do desktop guarda só `(identidade, sessão de identidade de origem)`; guardar o token do navegador está fora
de questão.

**A solução, sem função nova no banco.**

1. **Na aprovação** (`POST …/authorization/approve`, navegador), único momento em que o token da sessão de identidade
   está legitimamente na requisição, e só quando o pedido traz `navigation`: na mesma transação que reconfere a sessão,
   as funções que já existem resolvem as organizações elegíveis e a conta candidata em cada uma. A lista de pares
   `(organização, conta)` — no máximo 200, na ordem da lista da Web (nome, id) — segue com o código e depois com a
   sessão do desktop, no Redis. O token do navegador não é guardado.
2. **A cada requisição de navegação**, nesta ordem:
   1. token e sessão de identidade de origem, exatamente como em `GET /desktop/session` → `401`;
   2. escopo da sessão → `403`;
   3. a organização do caminho precisa ser um dos pares **desta** sessão → `404`;
   4. numa única transação com os contextos da identidade e da organização: sessão de origem ainda ativa (`401`);
      `identity_org_session_allowed(org, conta, sessão de origem)` — a função da 017/029 que já mantém válida a sessão
      de organização nascida da troca: conta ligada a esta identidade, sessão de origem ativa e não restrita, conta
      interna ativa e verificada, organização ativa, vínculo ativo — falso → `404`; vínculo ativo da conta lido das
      tabelas da organização; e então a **mesma** autorização e as **mesmas** consultas da Web (`WorkspaceAccess`,
      `ProductReads`) com esse vínculo.

O par guardado é só a candidata: não autoriza nada sozinho. O aplicativo envia apenas o token e os ids do caminho;
conta, vínculo, papel e Worker nunca vêm dele. Nada fica em cache entre requisições.

**Por que uma transação só, de `IdentityTransactions.inIdentityAndOrganization`.** A conferência precisa do contexto
da identidade (RLS de `identity_sessions`) e do contexto da organização (exigido por `identity_org_session_allowed`
e pela RLS das tabelas lidas). `ProductReads` e `WorkspaceAccess` só dependem de `nekkora.organization_id`, que essa
transação define do mesmo modo que `OrgTransactions.inOrganization`; o contexto de identidade não amplia a RLS das
tabelas de organização. Abrir `OrgTransactions` por dentro suspenderia a transação e usaria outra conexão; abrir depois
separaria a conferência da leitura. A guarda de contexto vazado (`ContextoVazado`) roda ao abrir a transação, com os
dois contextos ainda vazios, como nos demais usos. A renomeação usa a mesma transação em modo de escrita e só trava
linhas da organização (organização, conta de quem age, automação), como os demais atos do ciclo de vida.

**Limites conhecidos.**

- Organização em que a pessoa **entrou depois do login** (convite aceito, organização criada) só aparece no **login
  seguinte**. A sessão dura 15 minutos e não se renova, então a espera máxima é essa.
- O que ela **perdeu** depois do login vale na requisição seguinte: vínculo retirado, conta ou organização suspensa
  (a organização some da lista e vira `404`), papel revogado (o workspace some; as capacidades mudam), logout ou
  revogação da sessão do navegador (`401`).
- Mais de 200 organizações: só as 200 primeiras (nome, id); o servidor registra `evento=desktop.organizacoes_truncadas`.
- `GET /desktop/organizations` abre uma transação curta por organização da lista (como a lista da Web já faz para o
  identificador).

**Alternativa que não foi feita (decisão da responsável).** Uma função `SECURITY DEFINER` nova, do dono da identidade,
que recebesse `(identidade, sessão de origem)` e devolvesse as organizações elegíveis **agora**, eliminaria a
fotografia e o limite de "só no próximo login". É uma migration **com elevação** (função do dono da 017, como a 019,
020, 023, 024 e 029), com revisão de segurança própria. Não foi feita nesta missão.

## 4. Rotas

Todas: `Authorization: Bearer <token>`; cookies ignorados; sem CSRF e sem `Origin`; `Cache-Control: no-store`; chave
`nekkora.desktop-login.enabled` com a entrada geral (desligada: `404` antes da cadeia); limitador R12 por IP.

### `GET /api/v1/desktop/me`

`200 {"email": "ana@exemplo.com", "displayName": "Ana"}`

### `GET /api/v1/desktop/organizations`

```json
{"items": [{"organizationId": "…", "name": "Acme", "handle": "acme-1a2b", "activationState": "AGUARDANDO_ATIVACAO"}]}
```

Só as organizações da fotografia que passam na conferência de agora. Ordem: `name`, `organizationId` (a da lista da
Web). Sem cursor. Duas organizações podem ter o mesmo nome; `handle` e `organizationId` as distinguem. `handle` pode
ser `null`. `activationState`: `AGUARDANDO_ATIVACAO` ou `ATIVADA`. Pessoa sem organização: `200 {"items": []}`.

### `GET /api/v1/desktop/organizations/{org}/workspaces?cursor=&limit=`

```json
{"items": [{"workspaceId": "…", "name": "Financeiro"}], "nextCursor": null}
```

A consulta de `GET /api/v1/o/{org}/workspaces?status=active`: workspaces **ativos** em que o vínculo tem
`automation.read` efetiva. Ordem: nome (48 primeiros caracteres, ordenação `"C"`) e `workspaceId`. `limit` de 1 a 100,
padrão 25.

### `GET /api/v1/desktop/organizations/{org}/workspaces/{workspace}/automations?cursor=&limit=`

```json
{"items": [{"automationId": "…", "name": "Fechamento",
            "latestPublishedVersion": {"automationVersionId": "…", "versionNumber": 4, "publishedAt": "2026-11-13T12:00:00Z",
                                       "package": {"name": "relatorio", "version": "1.3.0", "fingerprint": "…64 hex…"}}}],
 "nextCursor": null,
 "capabilities": {"rename": true, "execute": false}}
```

A consulta de `GET /api/v1/o/{org}/workspaces/{workspaceId}/automations?status=active`: só automações **ativas**;
workspace fora do alcance → `404`. `latestPublishedVersion` é `null` sem versão publicada; `package` é `null` quando a
versão não tem pacote recebido (`automation_artifacts`), e seus campos podem ser `null` (pacote do Studio não declara
nome nem versão). `capabilities` vale para o workspace, nesta requisição: `rename` = `automation.manage`;
`execute` = `automation.execute`. Não saem conteúdo de arquivo, hash de arquivo, comando, segredo, nem quem criou.

### `GET /api/v1/desktop/organizations/{org}/automations/{automation}/versions?cursor=&limit=`

```json
{"items": [{"automationVersionId": "…", "versionNumber": 5, "status": "draft", "publishedAt": null, "package": null}],
 "nextCursor": null}
```

A consulta e a visibilidade de `GET /api/v1/o/{org}/automations/{automationId}/versions` (inclusive para automação não
ativa). Ordem: `versionNumber` decrescente. `status`: `draft` ou `published`.

### `PATCH /api/v1/desktop/organizations/{org}/automations/{automation}`

Corpo `{"name": "Novo nome"}` → `200 {"automationId": "…", "name": "Novo nome"}`.

- Nome pela regra da criação de automação (`AutomationLifecycleService`): 1 a 80 caracteres, sem caracteres de
  controle, não só espaços; os espaços das extremidades são retirados. Fora dela: `400`.
- Automação fora do alcance de leitura (inexistente, de outra organização, de workspace sem acesso): `404`.
- Visível, sem `automation.manage` no workspace: `403`.
- Automação ou workspace não ativo: `409 {"error":"conflict"}`, como nos demais atos do ciclo de vida.
- Só `automations.name` muda (`updated_at` pelo gatilho da 005). Versões, artefatos, manifestos, impressões e conteúdo
  publicado não são tocados. Nome igual ao atual: `200`, sem gravar nem auditar. Nome de automação não é único.
- Auditoria: `automation_admin_events`, ato 8, com o vínculo de quem renomeou (seção 7).

### Cursores

Opacos e assinados, o mesmo mecanismo das rotas da Web (`CursorCodec`), presos a **esta rota**, organização, vínculo e
recurso pai. Cursor de outra organização, outro workspace, outra automação, outro vínculo ou da rota equivalente da
Web: `400`. O cursor não é autoridade: cada página refaz a conferência inteira.

### Erros

Corpo sempre `{"error": "<código>"}`.

| Status | Código | Quando |
|---|---|---|
| 400 | `invalid_request` | `cursor`, `limit`, id do caminho ou corpo inválidos |
| 401 | `unauthenticated` | Token ausente, desconhecido, vencido, ou sessão do navegador encerrada |
| 403 | `forbidden` | Sessão sem o escopo `identity navigation`; na renomeação, automação visível sem `automation.manage` |
| 404 | `not_found` | Organização, workspace ou automação fora do alcance da pessoa (resposta idêntica à de inexistente); chave desligada |
| 409 | `conflict` | Renomeação de automação, ou em workspace, não ativo |
| 429 | `rate_limited` | Limitador (`Retry-After`) |
| 500 / 503 | `internal_error` / `service_unavailable` | Falha do servidor ou de dependência |

Ordem das conferências: chave e limitador → `401` → `403` de escopo → `400` de `limit` ou corpo → `404` da organização
→ `400` de cursor → `404`/`403`/`409` do recurso. Id ou `limit` que não converte e JSON malformado são recusados (`400`)
antes da autenticação, sem tocar o banco. Uma consulta que falha é `5xx`; lista vazia é sempre um `200` legítimo.

## 5. Permissões por rota

| Rota | Escopo | Na organização | No workspace |
|---|---|---|---|
| `GET /desktop/me` | `navigation` | — | — |
| `GET /desktop/organizations` | `navigation` | vínculo interno ativo, conta ativa, organização ativa | — |
| `GET …/workspaces` | `navigation` | idem | `automation.read` efetiva (por workspace; `ORG_ADMIN` em todos) |
| `GET …/workspaces/{w}/automations` | `navigation` | idem | `automation.read` |
| `GET …/automations/{a}/versions` | `navigation` | idem | `automation.read` no workspace da automação |
| `PATCH …/automations/{a}` | `navigation` | idem | `automation.read` para ver (senão `404`) e `automation.manage` para renomear (senão `403`) |

Pelos papéis do catálogo: `VIEWER` e `OPERATOR` veem e não renomeiam; `WORKSPACE_ADMIN` (no workspace) e `ORG_ADMIN`
renomeiam.

## 6. Compatibilidade

- Clients que pedem `scope=identity` não mudam em nada: mesmas rotas, mesmas respostas (`GET /desktop/session` com as
  mesmas três chaves e `"scope":"identity"`).
- `GET /desktop/authorization` ganha o campo `scope` (aditivo). A Web atual, que não o lê, continua funcionando; para
  mostrar à pessoa o que está sendo concedido, precisa passar a lê-lo.
- Valores no Redis gravados pela versão anterior continuam legíveis (escopo `identity`); a versão anterior da API não lê
  os valores novos, então numa reversão as sessões de desktop em curso (15 min) terminam e a pessoa entra de novo.
- O token do aplicativo continua não autenticando nenhuma rota fora de `/api/v1/desktop/`, e os cookies do navegador
  continuam não valendo nas rotas do aplicativo. O canal das máquinas (`/agent/api/v1`, outro processo, outra
  credencial) não muda.

## 7. Migration

`db/migrations/038_renomeacao_de_automacao.sql`, **sem elevação**: só amplia o CHECK de
`automation_admin_events.act` de 1–7 para 1–8 (`8 = AUTOMATION_RENAMED`), acrescenta o CHECK do ato 8 (automação
obrigatória; versão e artefato nulos) e atualiza o comentário da coluna. Nenhuma tabela, coluna, função, papel, dono,
política ou privilégio novo; nenhuma linha existente muda. O cabeçalho traz o diagnóstico e a reversão manual. Não
entra em nenhuma lista de elevação.

O evento não guarda o nome antigo nem o novo, como os demais atos; a tabela não tem coluna de sessão, então nada da
sessão do desktop é gravado nela.

## 8. Testes

`DesktopNavigationIT` (Platform real, PostgreSQL e Redis reais), `DesktopLoginContractTest` (rotas publicadas = rotas
dos controllers; esquemas), `IdentityRoutesGateTest` (chave e isenção de CSRF pela forma exata). Pessoas, organizações
e vínculos nascem pelas rotas reais (cadastro, criação de organização, convite aceito); workspaces, automações, versões,
pacotes, papéis de workspace e as mudanças de estado dos ensaios de revogação são preparados no banco.

Não exercitado aqui: o token do aplicativo apresentado ao canal das máquinas (`/agent/api/v1`), que é outro processo
(`nekkora-agent-gateway`). O sentido inverso (credencial `nka1…` nas rotas do aplicativo → `401`) é testado.
