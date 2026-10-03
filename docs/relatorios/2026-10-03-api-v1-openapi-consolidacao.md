# API v1: consolidação do OpenAPI (DC-14)

**ESTADO: REVISÃO 1 APROVADA TECNICAMENTE** (parecer de 2026-10-03). Mini missão documental.
- Nenhuma alteração de código, testes, migrations ou configuração.
- Não houve stage, commit nem push.

**Data:** 2026-10-03.

**Base:**
- **DC-14**, decisão da responsável em 2026-10-03, em resposta à pergunta do executor: OpenAPI definitivo em `api/openapi/nekkora-api-v1.yaml`, cobrindo também as rotas anteriores;
- D-INV-06: OpenAPI versionado e conferido contra os testes HTTP;
- GAP-02, GAP-03 e GAP-06 aprovadas tecnicamente.

**Evidências:** `ops/homologacao/saidas/openapi-v1-20261003T175100Z/` (`compor_openapi_v1.py`, `ConfereOpenApiV1.java`, `conformidade.txt`, `openapi.sha256`, `regressao_gap06_na_arvore_atual.txt`).

## 1. O que foi entregue

**`api/openapi/nekkora-api-v1.yaml`** (versão `1.0.0`, 977 linhas, LF, sha `e0fb4ee4…8d0a`):
- **38 operações:**
  - 21 rotas anteriores da Platform: identidade, sessão e MFA (contrato 8.7), administração mínima e recuperação emergencial de MFA (016);
  - 13 das GAP-02, 03 e 06;
  - 4 do portal externo (013).
- **Segurança por operação:**
  - `preAuthCookie` + `csrfHeader` (pré-autenticação);
  - `challengeCookie` + `csrfHeader` (desafio de MFA);
  - `sessionCookie` + `csrfHeader` (escritas com sessão);
  - `sessionCookie` (leituras);
  - `[]` nas rotas públicas;
  - sessão opcional no `logout`.
- **Respostas e esquemas das rotas anteriores:** descritos a partir dos controladores e do inventário (tabela A da API-V1-INV-01).
  - Respostas: `202` sem enumeração, `204`, `403` de CSRF/Origin, `503` com `Retry-After` e `500` de limite de lista do portal.
  - Esquemas: login, desafio, cadastro de MFA, troca e recuperação de senha, tokens de uso único, `/me`, motivo do reset de MFA, código operacional e itens do portal.
- **Diferenças do portal documentadas no próprio arquivo:**
  - `workspaceId` é omitido quando a conta tem um único workspace;
  - o cursor **não** é assinado;
  - `nextCursor` sai quando a página vem cheia;
  - eventos sem acesso → 200 vazio (DC-12);
  - limites de 200 workspaces e 1.000 eventos, com 500 sem truncar.

**O anexo da proposta** (`2026-10-02-api-v1-contrato-gap02-03-06-anexos/openapi-proposta.yaml`) fica marcado como **SUPERADO**: é histórico, e a fonte é o arquivo definitivo.

**Geração:** `compor_openapi_v1.py` parte do anexo validado (as 13 rotas novas e os componentes) e acrescenta as rotas anteriores, a segurança e os esquemas. O gerador vai junto nas evidências.

## 2. Conferência (`conformidade.txt`, `ConfereOpenApiV1.java` com SnakeYAML 2.6)

| Verificação | Resultado |
|---|---|
| YAML carregável; `$ref` | 38 caminhos, **335 referências, 0 não resolvidas** |
| Rotas do **código** × OpenAPI: mapeamentos `@Get/@PostMapping` dos controladores de `api/*/src/main` × caminho e método do OpenAPI, nos dois sentidos (`/error` interno excluído) | **38 = 38**, nada só no código e nada só no OpenAPI |
| `operationId` únicos | OK |
| Segurança × método: toda escrita (POST) com `csrfHeader`; nenhuma leitura (GET) com `csrfHeader` | **0 incoerências** |
| Catálogo de permissões do OpenAPI × migrations de HEAD | 23 = 23 |
| Chaves proibidas (`errorSummary`, `maxItems: 500`) | nenhuma |
| Referência textual nos testes | **as 38 operações aparecem em ao menos um arquivo de teste** (heurística; ver §3) |

**Correção durante a geração:** a primeira composição deixou resumos com `: ` sem aspas, e o YAML não carregou. O compositor passou a colocar todo `summary` entre aspas antes da conferência registrada.

## 3. Testes HTTP por operação (conferência manual sobre a heurística)

A busca textual do verificador é **ruidosa**: "me" e "executions" casam em vários arquivos, e as rotas internas e do portal compartilham trechos. Por isso a tabela abaixo foi conferida à mão, pelos caminhos usados nos testes:

| Operações | Testes que as exercitam |
|---|---|
| `GET /api/v1/csrf`, login, cadastro e verificação de MFA | `AuthenticationFlowIT`, auxiliares do `PlatformTestBase` (usados por todos os ITs), `CrossProcessE2E` |
| `GET /auth/csrf` | `AuthenticationFlowIT` |
| `GET /me` | `AuthenticationFlowIT`, `SessionsAndAdministrationIT`, `ReducingAccessRoutesIT` e outros 7 |
| step-up, troca de senha, recuperação de senha, suspender, reativar, reset de MFA | `SessionsAndAdministrationIT`, `MfaConcurrencyIT` |
| logout, revoke-all | `ReducingAccessRoutesIT`, `SessionsAndAdministrationIT`, `AuthenticationFlowIT` |
| confirmação de e-mail | `EmailVerificationRouteIT` (GAP-01) |
| revogação de concessão | `ClientGrantRevokeRouteIT` (GAP-01) |
| aviso confirmar e contestar, código operacional | `EmergencyRecoveryFlowIT` (inclusive o complemento da GAP-01) |
| GAP-02 (P01, P02) | `MyPermissionsRouteIT`, `PermissionCatalogIT` |
| GAP-03 (M01, M02, M03) | `AdminReadRoutesIT` |
| GAP-06 (L01–L08) | `ProductReadMatrixIT`, `ProductReadRoutesIT` |
| portal (4 rotas) | `PortalIT`, `CrossProcessE2E` |

**Limite:** a conferência garante que o caminho e o método de cada operação são exercitados por testes HTTP reais. Ela **não** valida automaticamente cada corpo de resposta contra o esquema. Isso seria um validador de contrato em tempo de teste, que pode ser uma próxima melhoria.

## 4. Regressão

- `api/openapi/` **não** é entrada do Maven: o conjunto declarado em `ops/homologacao/lib/fontes_maven.py` cobre `api/pom.xml`, o wrapper e `api/<módulo>/{pom.xml,src}`.
- Nenhuma fonte Java mudou. A regressão aprovada da GAP-06 (331/0/0) **continua válida na árvore atual**: `verificar` → `INTEGRIDADE_EXECUCAO=OK`, `ARVORE_ATUAL=IDENTICA`, `REAPROVEITAMENTO_NA_ARVORE_ATUAL=PERMITIDO` (`regressao_gap06_na_arvore_atual.txt`).

## 5. Próximas etapas (cada uma com autorização própria)

- desempenho da L06: índices em migration própria, com antes e depois pela `MedicaoDc07`;
- integração no Git das GAP-01, 02, 03 e 06 e deste OpenAPI;
- ambiente do frontend, com dados sintéticos e jornadas HTTP dos papéis reais.

## 6. Revisão 1 (parecer de 2026-10-03)

**Evidências:** `ops/homologacao/saidas/openapi-v1-r1-20261003T180044Z/`.
- O pacote anterior (`e8882b7d…`) fica preservado.
- Não houve alteração de código Java, testes nem migrations.
- Não houve regressão Java, que o parecer dispensou.

**O arquivo corrigido** (`api/openapi/nekkora-api-v1.yaml`):
- sha `2a544245…ec22` (antes: `e0fb4ee4…8d0a`);
- diff `nekkora-api-v1.diff`, +51/−41;
- gerado pelo compositor corrigido (`compor_openapi_v1.py`; diff do gerador em `compor_openapi_v1.diff`).

| Achado | Correção |
|---|---|
| `GET /api/v1/csrf` declarava `Org` sem `{org}` no caminho | o compositor só usa `Org` como padrão quando a lista de parâmetros não é informada; lista vazia omite `parameters` |
| `components.responses.NotFound` sem aspas: a vírgula criava a chave nula `de outra organizacao ou sem acesso ao workspace (resposta identica)` | descrições de `Unauthenticated`, `Forbidden` e `NotFound` entre aspas, e todas as descrições de respostas 200 geradas também. Objeto resultante conferido: só `description` e `content` |
| `CodeRequest` aceitava `{}`, os dois campos juntos e só `recoveryCode`; as restrições estavam só na descrição | dois esquemas, com base no comportamento real dos controladores. **`TotpCodeRequest`** (cadastro e step-up): `required: [code]`, `additionalProperties: false`; o `recoveryCode` não é alternativa ao TOTP nessas rotas (o servidor o ignora; no step-up, sem `code` → 400). **`MfaVerifyRequest`** (verificação): `oneOf` com exatamente um entre `code` e `recoveryCode`, `additionalProperties: false`; nenhum ou os dois → 400, como em `AuthController.verify` |
| 18 marcadores `x-duvida` herdados da proposta | convertidos em **`x-decisoes`**, que referenciam DC já decididas. As duas menções em descrições viraram "decisão DC-15" e "decisão DC-10: adiado". O cabeçalho explica que nenhuma está pendente. `x-duvida` = 0 |

**Validação estrutural nova** (`ValidaEstruturaOpenApi.java`, SnakeYAML 2.6). O ambiente está offline, sem validador OpenAPI, `jsonschema` ou PyPI, por isso as regras foram implementadas aqui:
- chaves permitidas por tipo de objeto do OpenAPI 3.1 (raiz, info, path item, operação, parâmetro, corpo, resposta, media type, header, esquema, security scheme, components);
- valor nulo proibido fora de `enum`, `example`, `default` e `const`;
- campos obrigatórios;
- correspondência entre `{x}` do caminho e os parâmetros `in: path` com `required: true`, nos dois sentidos;
- duplicatas, status válidos, `operationId` único, tags e security schemes existentes, `required` × `properties`;
- `$ref` resolvidas;
- proibição de `x-duvida`;
- **instâncias de contrato:** avaliador mínimo de JSON Schema aplicado a 8 corpos válidos e inválidos dos esquemas de código, e conferência de qual esquema cada operação de MFA usa.

**Limite da validação:** cobre as regras e os tipos de objeto que este contrato usa. Não é um validador completo da especificação OpenAPI 3.1. Um validador oficial pode ser acrescentado quando o ambiente tiver acesso à dependência.

| Verificação | Resultado |
|---|---|
| Estrutural no YAML corrigido (`estrutura_corrigido.txt`) | **0 erros**; instâncias **8/8** |
| Estrutural na versão anterior (`estrutura_versao_anterior.txt`) | **reprova**: os três achados e os `x-duvida` |
| **Contraprovas isoladas** (`contraprovas.py`, `contraprovas_resumo.txt`): cada achado reintroduzido sozinho no YAML corrigido | C0, controle sem mutação → aprova; **C1** `Org` em `/api/v1/csrf` → detectado; **C2** `NotFound` sem aspas → detectado; **C3** `CodeRequest` permissivo → detectado (5 instâncias inválidas aceitas); **C4** `x-duvida` → detectado |
| Conformidade com o código (`conformidade.txt`, mesmo `ConfereOpenApiV1`) | 38 rotas = 38 operações; segurança coerente; catálogo 23 = 23; 334 referências, 0 não resolvidas |

**Precisão registrada pelo parecer da revisão 1:**
- `additionalProperties: false` define o **corpo permitido pelo contrato**. **Não** comprova que o servidor rejeita campos extras.
- No cadastro de MFA e no step-up, `recoveryCode` é **ignorado** pelo servidor, como documentado. A restrição do esquema não deve ser apresentada como recusa HTTP comprovada.
- As recusas HTTP comprovadas por teste são as da verificação: nenhum ou os dois campos → 400.
