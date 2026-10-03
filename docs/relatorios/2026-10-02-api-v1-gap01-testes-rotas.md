# GAP-01: testes HTTP da verificação de e-mail, da revogação de concessão e complemento da contestação de aviso

**ESTADO: PARA REVISÃO.**
- Só testes. Nenhuma alteração de produto, migration, permissão ou configuração.
- Sem stage, commit ou push.

**Data:** 2026-10-02, das 21:09Z às 21:24Z.

**Correções documentais pós-revisão** (sem repetir testes):
- são **9 testes novos**; os 13 da execução focalizada incluem 4 existentes;
- a afirmação sobre controle positivo ficou restrita aos blocos que o têm (§2);
- o inventário foi marcado como retrato anterior à GAP-01.

**Base:**
- inventário API-V1-INV-01, rodada 3, e parecer da revisão com o escopo da GAP-01;
- código de referência: HEAD `2431648f` (árvore = `main` `bd9e96a1`) mais os 3 arquivos de teste abaixo.

**Evidências:** `ops/homologacao/saidas/gap01-20261002T210936Z/`.

## 1. Correção do inventário que mudou o escopo

A rota de **contestação de aviso** (ROTA-020) **tem** teste HTTP desde a homologação da 016 (`EmergencyRecoveryFlowIT`). O caminho é montado como `"aviso/" + rota`, e o inventário não o viu.

**O que esses testes já cobriam:**
- sucesso com efeito (solicitação CANCELADA);
- token de confirmação usado para contestar (400);
- token de outra organização (400);
- token malformado (400) sem escrita;
- recusa por finalidade divergente registrada;
- contestação depois da execução (400).

Por isso, a GAP-01 cobriu a contestação **só no que faltava**. A correção está registrada no inventário (tabelas A, B, C e §2).

## 2. Testes

| Rota | Arquivo | Cenários (resposta **e** efeito persistido) |
|---|---|---|
| `POST /api/v1/o/{org}/auth/email-verification/confirm` (pública por token; **sem 401**) | `EmailVerificationRouteIT` (novo, 4 testes) | **(1)** token válido sem sessão → 204; e-mail verificado e token consumido; nenhuma sessão criada. Reuso → 400 `invalid_request`. **(2)** token vencido, revogado, desconhecido, malformado e ausente → 400; contas não verificadas, tokens não consumidos. **(3)** token da organização A na rota da organização B → 400, sem efeito; o mesmo token continua válido na própria organização (204). **(4)** sem `X-CSRF-Token`, com token CSRF errado, com outra Origin e sem Origin → 403, sem efeito; controle positivo com CSRF e Origin corretos → 204 |
| `POST /api/v1/o/{org}/client-grants/{grant}/revoke` (sessão + `external_access.manage`) | `ClientGrantRevokeRouteIT` (novo, 4 testes) | **(1)** ORG_ADMIN → 204; efeito: status 4, `revoked_at` e revogador = vínculo do ORG_ADMIN. Repetição → 204 **idempotente**, `revoked_at` inalterado. **(2)** sem sessão → 401; conta interna sem a permissão → 403; conta externa (a titular da concessão) → 403; concessão intacta. **(3)** sem CSRF, CSRF errado, outra Origin, sem Origin → 403, sem efeito; controle → 204. **(4)** **isolamento entre organizações:** ORG_ADMIN de A, rota de A, concessão de B → 404; sessão de A na rota de B → 401; concessão de B intacta. Concessão inexistente → 404 |
| `POST /api/v1/o/{org}/auth/mfa-emergencia/aviso/contestar` (pública por token; **sem 401**) | `EmergencyRecoveryFlowIT` (+1 método; os métodos existentes não foram alterados) | sem CSRF, outra Origin e sem Origin → 403, com estado e token intactos; contestação válida → 204 e CANCELADA; **reuso** → 400, estado mantido; **vencido** (fixture temporal já existente, `recuarSolicitacao` de 73 h além do `t_total` de 72 h) → 400, sem cancelar e sem consumir o token |

**Controle positivo:** existe **só** nestes blocos:
- CSRF/Origin da confirmação de e-mail (mesma chamada correta → 204);
- confirmação de e-mail entre organizações (o mesmo token continua válido na própria organização → 204);
- CSRF/Origin da revogação de concessão (→ 204);
- CSRF/Origin da contestação (contestação válida → 204).

**Não há** controle positivo nos testes de token vencido/revogado/desconhecido/malformado, de sem sessão/permissão/conta externa e de isolamento da revogação, nem no de vencimento da contestação. Nesses, a evidência é o status recusado e a ausência de efeito no banco.

**Achados de produto:** nenhum. Todos os comportamentos verificados coincidem com o contrato 8.7 e com a leitura do código registrada no inventário (tabela A).

**Observação de leitura, sem teste nesta missão:** na confirmação de e-mail, se o `target_email` do token não for o e-mail atual da conta, o token é consumido e a resposta é 400. É coerente com "token para outro e-mail", mas não há caso de teste. Fica registrado.

## 3. Execução

| Execução | Resultado |
|---|---|
| Execução focalizada (`-Dtest=EmailVerificationRouteIT,ClientGrantRevokeRouteIT,EmergencyRecoveryFlowIT`) | **13/13**, BUILD SUCCESS (`testes_gap01.txt`): **9 testes novos** (4 + 4 + 1 método novo) e **4 existentes** do `EmergencyRecoveryFlowIT` |
| **Regressão completa** (`regressao_maven.sh … -B verify`, 21:12Z–21:23Z) | **275 testes, 0 falhas, 0 erros, 0 ignorados** (266 anteriores + 9 novos). Reator completo SUCCESS. **`REAPROVEITAMENTO=PERMITIDO`**; `verificar`: `INTEGRIDADE_EXECUCAO=OK`, `ARVORE_ATUAL=IDENTICA`, **`REAPROVEITAMENTO_NA_ARVORE_ATUAL=PERMITIDO`**. Fontes `743c12d1…24d9`. 0 `Address already in use` |

## 4. Arquivos (diff fechado: `P_patches/gap01.patch`)

| Arquivo | Tipo | sha256 (bytes) |
|---|---|---|
| `api/nekkora-platform/src/test/java/com/nekkora/platform/EmailVerificationRouteIT.java` | novo (151 linhas, LF) | `17df89170be186121b4293a83ee9184e3a61ce41bae5100c30a4f9de4d1885cf` |
| `api/nekkora-platform/src/test/java/com/nekkora/platform/ClientGrantRevokeRouteIT.java` | novo | `63124ad96f27f0c5319f96ea7cbdb9a7a043326aabfc993a45b2524507630ff6` |
| `api/nekkora-platform/src/test/java/com/nekkora/platform/EmergencyRecoveryFlowIT.java` | +60 linhas, 0 removidas | `46c31cc6…2d825` → `da8c8a06ba02a47f6e4dc4d3f73598cacc04b309a066c1863d214e5b517c0f10` (cópia de trabalho CRLF; blob normalizado `f0f71f0e…`) |

- O patch, aplicado (`patch --binary`) sobre a cópia anterior, reproduz os 3 arquivos: `verificacao.txt`, 3/3 OK.
- Nada mais mudou.
- **Sem stage nem commit:** a integração desses testes no Git é uma decisão posterior, por um commit próprio.

## 5. Efeito no inventário

| Rota | Antes (rodada 3) | Agora |
|---|---|---|
| ROTA-013 (confirmação de e-mail) | sem teste | sucesso, reuso, vencido/revogado/desconhecido/malformado/ausente, outra organização, CSRF/Origin, todos com efeito conferido |
| ROTA-018 (revogação de concessão) | sem teste | sucesso, idempotência, 401, 403 (permissão e conta externa), CSRF/Origin, **isolamento entre organizações** nos dois sentidos, 404 |
| ROTA-020 (contestação) | testes existentes, sem CSRF/reuso/vencimento | complemento: CSRF/Origin, reuso e vencimento |

**Continuam abertos:**
- a conferência de contexto das demais evidências candidatas (tabela D);
- as decisões **D-INV-01** (recorte da primeira entrega) e **D-INV-06** (OpenAPI);
- GAP-04: a emissão do token de verificação continua inexistente na API. Este teste semeia o token no banco, como o produto o persiste.
