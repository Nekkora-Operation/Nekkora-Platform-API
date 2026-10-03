# GAP-03: preparação da implementação (membros, concessões e clientes)

**ESTADO: PREPARAÇÃO APROVADA TECNICAMENTE** (parecer de 2026-10-03), com a correção do teste do "ou" (§3) incorporada. A evidência da correção é entregue com a GAP-03.
- Não há código, teste, migration nem commit. A implementação começa depois do aceite desta preparação.
- Commit e publicação da GAP-01 e da GAP-02 continuam etapas separadas.

**Base:**
- contrato GAP-02/03/06, revisão 2 (`2026-10-02-api-v1-contrato-gap02-03-06-proposta.md`): §1, §3 (ROTA-M01, M02, M03) e §5;
- GAP-02 **concluída no escopo contratado** (parecer de 2026-10-03, correção C1, pacote `943272aa…`). Os componentes `WorkspaceAccess`, `CursorCodec` e `Paging` são reutilizados;
- **decisões da responsável** em 2026-10-03, respondidas em pergunta do executor:
  - **DC-04:** `mfaConfigured` só como booleano, sob `org.manage`;
  - **DC-05:** filtro inexistente ou de outra organização em `/client-grants` → lista vazia, como exceção documentada;
  - **DC-06:** `/clients` incluída, com contrato e testes completos;
  - já valiam DC-01, DC-02, DC-03 e DC-15;
- leitura do esquema no commit `2431648f` (tabela abaixo).

## 1. Fatos do esquema usados

| Fato | Fonte | Uso |
|---|---|---|
| `organization_memberships` (`membership_id`, `user_id`, `status` 1–4, `created_at`); `account_type = 1` fixo, então só contas internas | 002 | M01 lista só internos, sem filtro extra |
| `users` (`display_name`, `email`, `email_verified_at`, `status` 1–4) | 002 | campos do `Member`; `email_normalized` **não** sai |
| `user_mfa_factors` (`confirmed_at`, `disabled_at`); no máximo um fator não desativado por conta | 014 | `mfaConfigured = EXISTS (confirmed_at IS NOT NULL AND disabled_at IS NULL)` |
| `client_workspace_grants` (status 1–4, `approved_at`, `starts_at`, `expires_at`, `next_review_at`, `last_access_at`, `revoked_at`, `created_at`); FKs garantem cliente, workspace e vínculo externo da mesma organização | 003 | M02 |
| `client_memberships` (`client_membership_id`, `user_id`, `status`) | 002 | `externalAccount` da M02 |
| `clients` (`name` `TEXT`, `status` 1–3, `created_at`, `updated_at`) | 001 | M03 |
| `nekkora_platform` tem SELECT nessas tabelas (012) e em `user_mfa_factors` (014); a RLS é por organização | 012, 014 | nenhuma permissão de banco nova |
| Índices: `client_workspace_grants (organization_id, client_id)`; **nenhum** por `(organization_id, created_at)` em vínculos e concessões, nem por nome em `clients` | 001–003 | funciona sem migration. O risco de desempenho com volume fica registrado; a medição formal é do DC-07 (GAP-06), e um índice seria migration própria |

## 2. Desenho

### 2.1 Componentes (reutilização, sem cópia)

| Item | Onde | Nota |
|---|---|---|
| **Permissão organizacional** | `WorkspaceAccess.hasOrganizationPermission(j, org, m, perm)` e `requireAnyOrganizationPermission(j, org, m, perm...)`. **Novos**, no mesmo componente | chamam `requireConsistent` antes (mesma política de 500 com log da C1). Só aceitam permissão de escopo **organização** (§0.1), senão é erro de programação. Sem a permissão → 403. Assim M01, M02 e M03 **não** usam `AccountRepository.hasPermission`, que não valida o catálogo |
| Cursor e `limit` | `CursorCodec`, `Paging` | rotas `listMembers`, `listClientGrants`, `listClients`; ordenações `createdAt.asc+id.asc`, `createdAt.desc+id.desc`, `name48.asc+id.asc` |
| **Rótulos** | `com.nekkora.platform.web.Labels` (novo) | status de usuário, vínculo, concessão e cliente (§1.7 do contrato). Código desconhecido no banco → 500 com log, nunca omitido. Valor de filtro desconhecido → 400 |
| Rotas | `AdminReadController` (novo) | `GET /members`, `GET /client-grants`, `GET /clients` |
| **Javadoc** de `WorkspaceAccess` | ajuste de redação (ressalva do parecer da C1) | trocar "so pode ser executada por" por: "os caminhos implementados usam `visibleWorkspacesQuery`; a constante é pública e não impede uso direto, então rotas novas devem usar só os métodos do componente" |

**Ordem das verificações (§1.3):**
1. 401, pelo filtro;
2. **400**: `status` desconhecido, vazio, repetido ou acima do máximo (4 / 4 / 3); UUID malformado em `clientId`/`workspaceId`; `limit`; cursor e vínculos do cursor;
3. **403**: conta externa (DC-02); sem a permissão organizacional.

**`status` repetido → 400.** O OpenAPI declara `uniqueItems`. A impressão canônica do cursor já ignora a ordem.

### 2.2 Consultas (uma transação `READ_ONLY` por requisição)

**M01 `/members`** (`org.manage`):
- `organization_memberships om JOIN users u`, filtro `om.status = ANY(:status)` opcional;
- `ORDER BY om.created_at, om.membership_id`, com página seguinte `WHERE (om.created_at, om.membership_id) > (:c, :i)`;
- `organizationRoles` por subconsulta das atribuições organizacionais **ativas**, com o código validado no catálogo (escopo organização). Desconhecido → 500;
- `mfaConfigured` por `EXISTS` (DC-04).

**M02 `/client-grants`** (`external_access.manage`):
- `client_workspace_grants g JOIN clients c JOIN workspaces w JOIN client_memberships cm JOIN users u`;
- filtros opcionais `status`, `g.client_id = :clientId`, `g.workspace_id = :workspaceId`. Com a RLS por organização, ID de outra organização ou inexistente resulta naturalmente em **lista vazia** (DC-05), sem consulta de existência;
- `ORDER BY g.created_at DESC, g.grant_id DESC`, com página seguinte `WHERE (g.created_at, g.grant_id) < (:c, :i)`.

**M03 `/clients`** (`org.manage` **ou** `external_access.manage`):
- `ORDER BY left(c.name, 48) COLLATE "C", c.client_id`;
- página seguinte `WHERE (left(c.name, 48) COLLATE "C", c.client_id) > (CAST(:p AS text) COLLATE "C", :i)`, a **mesma expressão** do `ORDER BY` (§1.5.1);
- a resposta traz o nome completo.

**Posições no cursor:** `createdAt` em UTC com microssegundos, igual à precisão do `timestamptz`; prefixo do nome como o banco devolveu, sem novo recorte na aplicação.

## 3. Testes planejados (ITs no padrão da GAP-01/02 e matriz §5.1)

| Rota | Cenários |
|---|---|
| **M01** | sucesso com ORG_ADMIN e campos conferidos, inclusive a ausência de `email_normalized` e de dados de MFA além do booleano; `mfaConfigured` verdadeiro, falso sem fator, falso com fator desativado e falso com fator não confirmado; `organizationRoles` só ativas; filtro `status` (inclusive revogado e suspenso); paginação estável em 3 páginas com `limit=2` e desempate por id com `created_at` igual; lista só com o próprio ORG_ADMIN; organização B ausente (contagem); 401; 403 para conta externa e para OPERATOR; 400 para `status` desconhecido, vazio, repetido e acima de 4, `limit` e cursor (adulterado, de outra rota, com filtro trocado, de outro vínculo); **revogação entre páginas**: perder `org.manage` → 403 na página 2 |
| **M02** | sucesso com concessão, cliente, workspace e conta externa; revogada com `status=revoked` e `revokedAt`; nulabilidade (`approvedAt`, `expiresAt`, `nextReviewAt`, `lastAccessAt`, `revokedAt`); filtros `status`, `clientId` e `workspaceId` combinados; **exceção DC-05**: `clientId` e `workspaceId` de outra organização → 200 vazio, inexistentes → 200 vazio, cada um com controle positivo do mesmo filtro com ID válido; UUID malformado → 400; paginação estável descendente com desempate; cursor com filtro trocado → 400; 401; 403 para conta externa e sem `external_access.manage`; isolamento: concessões de B ausentes; revogação entre páginas → 403 |
| **M03** | sucesso com ORG_ADMIN; filtro `status`, com arquivado aparecendo; **ordenação binária** com maiúscula, minúscula e acento; desempate de nomes iguais por `clientId`; **nomes longos**: dois clientes iguais nos 48 primeiros pontos de código ficam em ordem de id; nome de 1.000 caracteres (ASCII, controles, emoji) no **fim da página 1** → página 2 aceita e cursor ≤ 872; **mudança concorrente** (§5.4): renomear para antes do cursor (omitido, documentado), para depois (aparece), só depois do 48º ponto de código (posição inalterada), mudar `status` com filtro; organização sem clientes → `[]`; 401; 403 para conta externa e OPERATOR; 400; isolamento |
| Componente | `hasOrganizationPermission` e `requireAnyOrganizationPermission`: papel fora do catálogo → 500 com log, com controle positivo; permissão de workspace passada como organizacional → erro de programação |

**Limite conhecido (M03, "só uma das duas permissões"):**
- no catálogo atual, só o ORG_ADMIN tem `org.manage` ou `external_access.manage`, e tem as duas;
- depois da C1, um papel-fixture fora do catálogo dá 500, então não serve para separar as duas permissões;
- ~~proposta original~~ (corrigida pelo parecer de 2026-10-03): chamar o método com uma permissão por vez para o ORG_ADMIN **não** distingue "ou" de "e";
- **teste do "ou", corrigido:** chama o método real `requireAnyOrganizationPermission(..., "org.manage", "external_access.manage")`, com as **duas** permissões exigidas, variando as permissões **disponíveis** por uma dependência controlada (fonte de permissões injetada no teste). A lógica de decisão é a real:

  | Disponíveis | Esperado |
  |---|---|
  | só `org.manage` | permitir |
  | só `external_access.manage` | permitir |
  | ambas | permitir |
  | nenhuma (ou só permissões não exigidas) | 403 |

- a mutação "ou" → "e" precisa reprovar os dois primeiros casos;
- os ITs HTTP continuam cobrindo os perfis do catálogo: ORG_ADMIN → 200; OPERATOR e sem papéis → 403;
- nenhum papel do produto é alterado.

**Contraprovas previstas (mutações, cada uma restaurada com sha conferido):**
- tirar a checagem de permissão da M01 → os testes de 403 falham;
- usar o nome completo na comparação da M03, mantendo o prefixo no `ORDER BY` → o teste de nome longo ou da página seguinte falha;
- trocar o filtro da M02 por consulta de existência com 404 → o teste da exceção DC-05 falha;
- tirar `requireConsistent` de `hasOrganizationPermission` → o teste do papel fora do catálogo falha.

**Outras conferências:**
- `validar-config` comparado com a referência de 184 nomes;
- regressão completa `regressao_maven.sh` com `REAPROVEITAMENTO=PERMITIDO`, e os arquivos entregues conferidos contra `fontes.sha256` da regressão;
- patch fechado sobre HEAD;
- pacote com varredura de tokens e controle positivo.

## 4. OpenAPI

- As operações `listMembers`, `listClientGrants` e `listClients` recebem `x-estado: implementado` depois dos testes.
- Ajustes já previstos no anexo:
  - `status` com `uniqueItems`;
  - limites de itens 4 / 4 / 3;
  - a descrição da exceção DC-05.
- O arquivo continua como anexo da proposta enquanto o **DC-14** estiver pendente.

## 5. Fora do escopo

- Ações de membros e concessões (convite, aprovação, edição): continuam na v1 completa.
- GAP-15 (gestão de clientes).
- Índices: seriam migration própria (DC-07).
- Comparação real da exceção DC-05 com uma rota por workspace: as rotas L são da GAP-06, onde o mesmo caso dá 404 e será conferido.
