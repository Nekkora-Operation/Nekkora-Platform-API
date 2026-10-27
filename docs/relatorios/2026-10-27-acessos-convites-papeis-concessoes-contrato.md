# Contrato: convites, papéis e concessões (frente B da missão de consolidação, acessos e recuperação operacional)

**Data:** 2026-10-27. **Base:** `feat/email-real-homologacao` (PR #21, que contém #20 e #22), sem merge. Branch dependente: `feat/acessos-convites-papeis`.
**Situação:** contrato da implementação desta missão. **Revisão de 2026-10-27 (tarde):** a responsável aceitou as decisões de convite e de step-up e decidiu a segunda pessoa nas concessões e a direção da entrada externa (§9). Duas delas exigiram mudança de código, na migration **024** (PR #24): o convite de emissor que perdeu a autoridade agora é revogado e nunca volta (§4.5), e a aprovação de concessão exige pessoa diferente do solicitante (§7). A mesma 024 fecha a recusa explícita do reset operacional de conta vinculada (§10). O que continua pendente está no §9.

## 1. Vocabulário (como o código usa cada termo)

| Termo | O que é no banco | Onde |
|---|---|---|
| **Identidade global** | a pessoa: e-mail único, senha, MFA, sessões de identidade | `identities`, `identity_*` (017) |
| **Conta da organização** | o contexto de conta dentro de UMA organização (interna ou externa) | `users` (002), único por `(organization_id, email_normalized)` |
| **Ligação identidade↔conta** | qual conta da organização a identidade usa; imutável | `identity_users` (017), canais `cadastro_web`, `vinculacao` e, nesta missão, `convite` |
| **Vínculo** | o pertencimento da conta interna à organização (1 por conta) | `organization_memberships` (002): PENDING, ACTIVE, SUSPENDED, REVOKED |
| **Papel** | conjunto fixo de permissões; ORG_ADMIN (organização) e WORKSPACE_ADMIN, OPERATOR, VIEWER (workspace) | `roles`, `role_permissions` (003) |
| **Atribuição** | papel ativo de um vínculo (na organização ou num workspace) | `organization_role_assignments`, `workspace_role_assignments` (003) |
| **Concessão** | acesso EXTERNO fixo, só leitura pelo Portal, de uma conta de cliente a um workspace do mesmo cliente; não usa papéis | `client_workspace_grants` + aprovações e revisões (003); lida pelo Portal (013) |
| **Convite** | pedido de entrada de uma pessoa na organização, com prazo e token de uso único | `identity_invitations` (002), sem uso até esta missão |

## 2. Matriz antes desta missão

| Jornada | Existe no banco? | Existe na API? | Já foi testada? | Lacuna |
|---|---|---|---|---|
| Criar, listar, revogar, reenviar convite | só a tabela (002), sem função, trigger nem unicidade | não | não | tudo |
| Aceitar convite (identidade com e sem conta na organização) | não | não | não | função de aceite (precisa gravar `identity_users`) |
| Listar membros e papéis organizacionais | sim | `GET /o/{org}/members`, `/me/permissions` | sim | papéis de workspace por membro |
| Atribuir, alterar, revogar papel | tabelas e triggers (último ORG_ADMIN) | não | só indiretamente | rotas, autoridade, step-up, auditoria |
| Retirar vínculo | UPDATE de status | não | só a reação das sessões | rota, efeito nas sessões, auditoria |
| Último administrador | trigger `enforce_org_admin_presence` (003) | P0001 → 403 | sem teste dedicado | teste dedicado |
| Concessões: listar, revogar | sim | sim (`/client-grants`, `/revoke`) | sim | revogação sem auditoria nem lock da organização |
| Concessões: criar, aprovar, renovar, revisar | tabelas de aprovação e revisão | não | não | rotas e auditoria |
| Auditoria de acesso | só bootstrap do 1º ORG_ADMIN | — | — | registro de quem, alvo, escopo e mudança |
| Avisos na fila | `mail_outbox` (021) | `MailIntents.record` | sim (outros avisos) | tipos de convite e de mudança de acesso |

## 3. Regras comuns a todas as rotas de administração de acesso

1. **Autorização pela matriz da 003, relida dentro da transação do ato**, junto com a sessão do ator (padrão de `AdminService`). Nenhuma decisão usa o principal do filtro.
2. **Ordem de locks:** organização `FOR UPDATE` primeiro (disciplina da 003), depois as contas envolvidas em ordem crescente de `user_id`, depois a linha alvo (convite, atribuição, concessão). Reconferência da sessão, da permissão e do step-up **depois** dos locks; efeitos só depois disso.
3. **Autenticação recente (step-up de 5 min na mesma sessão):** exigida nos atos que **ampliam** acesso: criar e reenviar convite, atribuir e alterar papel, aprovar, renovar e revisar com KEEP uma concessão. Os atos que **reduzem** acesso (revogar convite, revogar papel, retirar vínculo, suspender ou revogar concessão) dispensam só o step-up, para não atrasar a resposta a incidente. Continuam exigindo sessão de organização válida, CSRF, autoridade conferida sob lock e auditoria. **[aceita em 2026-10-27, D-CONV-2]**
4. **Sem autoatribuição:** o ator nunca é o alvo (papel, vínculo). Duas garantias: o serviço (403 `OWN_ACCOUNT`) e o banco (gatilho da 023, §8).
5. **Isolamento:** toda leitura e escrita passa pelo RLS da organização da rota; alvo de outra organização responde 404 `alvo`, igual a alvo inexistente.
6. **Auditoria:** todo ato com efeito grava uma linha em `access_events` (023) na mesma transação: tipo, organização, vínculo e conta do ator (ou a identidade, no aceite), conta e vínculo alvo, papel e workspace, convite, atribuição ou concessão, papel anterior (na alteração) e correlação. Recusa não grava evento (só log com `regra=`).
7. **Avisos:** intenção registrada em `mail_outbox` na MESMA transação do ato (021). Repetição idempotente não gera aviso novo.
8. **Papéis de organização nunca alteram senha, fator MFA ou códigos de recuperação da identidade global.** Nenhuma rota desta missão toca `identity_*` de credencial; a retirada de vínculo revoga só as sessões da organização.

## 4. Convites

### 4.1 Modelo
- Convite para **conta interna**, com no máximo **um** papel: ORG_ADMIN, ou WORKSPACE_ADMIN/OPERATOR/VIEWER num workspace da organização. Sem papel, a pessoa entra como membro sem permissões.
- Convite para **conta externa** (cliente): **fora deste corte**, ver D-EXT no §9.
- **Destinatária sem identidade:** primeiro conclui o cadastro global já existente (`/api/v1/signup`, confirmação do e-mail, MFA) e depois aceita. O convite **não** cria identidade nem é uma segunda forma de cadastro. O e-mail do convite aponta para a página `/o/{org}/convite#token=…`, que leva ao cadastro ou à entrada e depois chama o aceite.
- **Prazo:** 7 dias desde a emissão, conferido no aceite com a hora do banco medida depois dos locks; o banco recusa prazo maior. No reenvio, o token anterior é revogado na mesma transação (§4.4). **[aceita em 2026-10-27, D-CONV-1]** **Token:** 32 bytes aleatórios (base64url, 43 caracteres), só o SHA-256 no banco, com finalidade própria (só o aceite de convite o consome), consumo único por `UPDATE … WHERE ainda pendente`.

### 4.2 Rotas (sessão da organização, CSRF)
| Rota | Permissão | Step-up | Efeito |
|---|---|---|---|
| `POST /api/v1/o/{org}/invitations` `{email, role?, workspaceId?}` | `org.manage` **e** a de atribuir o papel do convite | sim | 201 `{invitationId, email, role, workspaceId, expiresAt}`; o token só vai para a fila cifrada |
| `GET /api/v1/o/{org}/invitations?state=pending\|all` | `org.manage` | não | lista sem token nem hash, com estado derivado: PENDENTE, EXPIRADO, ACEITO, REVOGADO |
| `POST /api/v1/o/{org}/invitations/{id}/revoke` | `org.manage` | não | 204; idempotente se já revogado; 409 se já aceito |
| `POST /api/v1/o/{org}/invitations/{id}/resend` | como a criação | sim | 201 com um convite NOVO (token e prazo novos); o anterior é revogado com motivo REENVIADO e o novo aponta para ele |

### 4.3 Aceite (sessão da identidade, CSRF, step-up da identidade)
`POST /api/v1/invitations/accept` `{organizationId, token}` → 200 `{organizationId}`; depois a identidade troca de sessão pela rota existente (`/api/v1/sessions/organization`).

Função `identity_accept_invitation` (023, SECURITY DEFINER, dono `nekkora_identity_owner`), no padrão da vinculação:
1. resolve a sessão da identidade; trava a identidade (ativa e com e-mail verificado);
2. define o contexto da organização e trava a organização (`FOR UPDATE`), que precisa estar ATIVA (`status = 1`); o token só é procurado **dentro** dessa organização;
3. trava o convite pelo hash, pendente;
4. mede a hora **depois** dos locks e reconfere: prova de step-up da identidade, prazo, `email_normalized` do convite = e-mail normalizado da identidade, **e a autoridade do emissor** (vínculo e conta ativos, `org.manage` e a permissão de atribuir o papel do convite, no instante do aceite);
5. casos da destinatária:
   - **sem conta na organização:** cria conta interna ativa (e-mail e verificação da identidade), vínculo ativo, a atribuição do papel (`assigned_by` = vínculo do emissor) e a ligação `identity_users` com canal `convite`;
   - **já ligada a uma conta da organização com vínculo ativo:** recusa `JA_MEMBRO`; o convite continua pendente;
   - **já ligada a uma conta da organização com vínculo retirado (REVOKED) e conta ativa:** **readmissão**: reativa o vínculo e grava só a atribuição do convite. Os papéis antigos continuam revogados e nada é restaurado automaticamente (concessões são de contas externas e não entram na readmissão interna) **[aceita em 2026-10-27, D-CONV-3]**;
   - **conta existente na organização com o mesmo e-mail, NÃO ligada a esta identidade** (conta legada ou de outra identidade): recusa `CONTA_EXISTENTE`. **Nenhuma vinculação automática por igualdade de e-mail**; a pessoa usa a vinculação existente (`/api/v1/links/*`, com prova da senha da conta);
6. consome o convite (`accepted_at`, `accepted_user_id`, `accepted_identity_id`) e grava o evento de auditoria.

Resposta: convite inexistente, expirado, revogado, já aceito, de outro e-mail, de emissor sem autoridade ou de organização não ativa → **404 genérico** `convite`, sem distinguir o motivo (sem oráculo); o motivo vai só para o log. `JA_MEMBRO` e `CONTA_EXISTENTE` → 409, porque só chegam a quem provou ser a destinatária.

### 4.4 Duplicados, repetição e reenvio
- **Um convite aberto por (organização, e-mail)**, garantido por índice único parcial (023). Criar outro com um aberto e no prazo → 409 `CONVITE_PENDENTE` (use o reenvio). Um aberto **vencido** é fechado na mesma transação (motivo EXPIRADO) e o novo é criado.
- Destinatária que já tem conta na organização → 409 `JA_CADASTRADA`, exceto a readmissão do §4.3.
- **Reenvio:** o token anterior é revogado na mesma transação do novo. Só o último convite da cadeia é aceitável.
- Aceite repetido depois do sucesso → 404 genérico (token consumido), sem efeito.

### 4.5 Efeitos externos ao convite
- **Organização suspensa ou encerrada:** nenhuma criação (a sessão da organização deixa de valer); aceite recusado enquanto não estiver ATIVA. Os convites pendentes voltam a valer se a organização voltar a ATIVA dentro do prazo.
- **Emissor perde a autoridade** (papel revogado, vínculo retirado ou suspenso, conta suspensa): os convites abertos dele são **revogados no mesmo ato** pelo banco (024, motivo `EMISSOR_SEM_AUTORIDADE`, sem revogador). Devolver o papel ao emissor, ou reativar a conta ou o vínculo dele, **não** faz o convite antigo valer de novo; é preciso emitir outro. A reconferência da autoridade no aceite (023) continua como segunda barreira. **[aceita em 2026-10-27, D-CONV-4]**
  - *Antes da 024:* o convite só ficava inaceitável enquanto o emissor estivesse sem autoridade, e recuperar o papel o fazia valer de novo. Prova: `AccessInvitationsIT.vencidoRevogadoOrganizacaoSuspensaEEmissorSemAutoridadeRecusam`, nos dois caminhos (papel devolvido, conta reativada).

## 5. Papéis

| Rota | Permissão | Step-up |
|---|---|---|
| `GET /api/v1/o/{org}/users/{user}/roles` | `org.manage` | não |
| `POST /api/v1/o/{org}/users/{user}/roles` `{role, workspaceId?}` | ver regra de autoridade | sim |
| `POST /api/v1/o/{org}/role-assignments/{id}/replace` `{role}` | autoridade sobre o papel atual **e** o novo | sim |
| `POST /api/v1/o/{org}/role-assignments/{id}/revoke` | autoridade sobre o papel | não |

**Autoridade (003):** ORG_ADMIN e WORKSPACE_ADMIN exigem `role.assign_admin_roles` organizacional (só ORG_ADMIN tem). OPERATOR e VIEWER exigem `role.assign_operator_viewer`: organizacional (ORG_ADMIN) ou de workspace, **só** no workspace em que o ator é WORKSPACE_ADMIN. Um WORKSPACE_ADMIN nunca concede papel de administração nem atua fora do próprio workspace.

**Alvo:** vínculo interno ativo e conta ativa (para atribuir); qualquer atribuição ativa da organização (para revogar). Nunca o próprio ator.

**Alterar** = revogar a atribuição atual e criar a nova na mesma transação, no mesmo escopo (organização → organização; workspace → mesmo workspace). Auditoria única com papel anterior e novo.

**Repetição:** atribuir um papel já ativo devolve 200 com a atribuição existente, sem evento; revogar uma já revogada → 204 sem evento; alterar para o mesmo papel → 200 sem efeito.

**Último administrador:** a 003 já define a política: nenhuma operação pode deixar a organização sem ORG_ADMIN efetivo (atribuição ativa, vínculo ativo, conta ativa). Recusa 403 `invariante`, sem efeito. Duas revogações concorrentes dos dois últimos administradores serializam na organização e a segunda é recusada.

**Sessões abertas:** a permissão é relida do banco a cada requisição (sem cache): o papel revogado deixa de valer na próxima requisição; as sessões não são revogadas.

## 6. Retirada de vínculo

`POST /api/v1/o/{org}/users/{user}/membership/revoke` (permissão `org.manage`, sem step-up, nunca o próprio ator). Na mesma transação, depois dos locks (organização → conta): vínculo REVOKED, todas as atribuições ativas do vínculo revogadas, sessões da organização da conta revogadas e desafios invalidados (como no `suspend`), evento e aviso. A conta continua existindo (histórico) e a ligação com a identidade não muda (é imutável). As sessões da identidade, o fator e os códigos da identidade **não** mudam. Último administrador: recusa 403 `invariante`. Repetição: 204 sem efeito novo. Readmissão: só por convite novo (§4.3).

## 7. Concessões (acesso externo)

**Escopo entregue:** o ciclo funciona sobre **cliente, conta externa e vínculo de cliente já existentes**: criar, aprovar, renovar, revisar e revogar. **A jornada externa completa não foi entregue.** Faltam a criação de cliente, a entrada da pessoa externa e o convite externo, que dependem do contrato do §9 (D-EXT). Hoje esses registros só nascem por provisionamento ou fixture.

Permissão `external_access.manage` (só ORG_ADMIN), lock da organização antes da concessão, auditoria em todas. **Segunda pessoa (D-GRANT-1, 024):** quem cria fica gravado como solicitante (`requested_by_membership_id`, imutável), e a aprovação INITIAL exige outro ORG_ADMIN. O serviço responde 403 e o banco recusa com 42501. Consequência: uma organização com um único ORG_ADMIN não aprova concessão externa. Uma concessão pendente sem solicitante gravado (anterior à 024, só de provisionamento ou fixture) não é aprovada (409): revogue e crie de novo.

| Rota | Step-up | Efeito |
|---|---|---|
| `POST /api/v1/o/{org}/client-grants` `{clientMembershipId, workspaceId, startsAt?, expiresAt?}` | não (nasce PENDENTE, sem acesso) | 201; vínculo de cliente ativo, cliente ativo, workspace do mesmo cliente; uma concessão não revogada por (vínculo, workspace): repetição → 409 |
| `POST …/client-grants/{id}/approve` `{nextReviewAt, expiresAt?, justification?}` | sim | PENDENTE → ATIVA, aprovação INITIAL; aprovador ≠ solicitante (403); sem solicitante gravado: 409 |
| `POST …/client-grants/{id}/renew` `{nextReviewAt, expiresAt?, justification}` | sim | ATIVA ou SUSPENSA → ATIVA, aprovação RENEWAL com os valores anteriores e novos |
| `POST …/client-grants/{id}/review` `{decision: KEEP\|SUSPEND\|REVOKE, notes?, nextReviewAt?}` | só KEEP | KEEP exige `nextReviewAt` novo; SUSPEND → SUSPENSA; REVOKE → REVOGADA |
| `POST …/client-grants/{id}/revoke` (existente) | não | passa a travar a organização e a gravar auditoria; continua idempotente; aceita concessão PENDENTE (cancelamento antes da aprovação) |

**Defeito encontrado e corrigido:** a 003 exigia `approved_at` em todo estado diferente de PENDENTE (`CHECK (status = 1 OR approved_at IS NOT NULL)`). Revogar uma concessão pendente violava a restrição, e a rota existente da GAP-01 respondia **500**; além disso, a pendente nunca podia ser cancelada e bloqueava para sempre uma nova pelo índice de não revogadas. Prova do antes: `GrantLifecycleIT.revogacaoDiretaAuditaEPermissaoEEscopoSaoConferidos` falhou com 500 antes da correção. A 023 troca a restrição por `status IN (1, 4) OR approved_at IS NOT NULL`: ATIVA e SUSPENSA continuam exigindo aprovação (suíte, caso C3).

O Portal lê só concessão ATIVA, aprovada, no prazo e dentro da revisão (013), a cada requisição: suspensão e revogação valem na próxima leitura.

## 8. Garantias do banco (023) e do serviço, separadas

| Garantia | Banco (023) | Serviço |
|---|---|---|
| Convite: hash único, finalidade e consumo único | índice único; congelado depois de aceito ou revogado; só transições aberto → aceito / revogado | gera o token, nunca o registra em log |
| Convite: prazo de no máximo 7 dias | gatilho na inserção | — |
| Um convite aberto por organização e e-mail | índice único parcial | 409 `CONVITE_PENDENTE` |
| Emissor autorizado na emissão | gatilho: vínculo e conta do emissor ativos, `org.manage` e a permissão do papel | mesma regra, com step-up |
| Aceite: e-mail, prazo, organização ativa, autoridade do emissor, sem vinculação automática | **função** (todas as conferências sob lock) | — |
| Sem autoatribuição e autoridade de quem atribui ou revoga papel | gatilho em `organization_role_assignments` e `workspace_role_assignments` | mesma regra, com step-up para ampliar |
| Último ORG_ADMIN | gatilho da 003 (inalterado) | 403 `invariante` |
| Aprovação e revisão de concessão por quem tem `external_access.manage` | gatilho em aprovações e revisões; na concessão, toda transição para ATIVA (aprovação, renovação, reativação) e a revogação conferem o aprovador ou o revogador gravado | mesma regra |
| Segunda pessoa na aprovação INITIAL (024) | concessão nasce PENDENTE, com solicitante que tem a permissão; solicitante imutável; aprovação INITIAL e PENDENTE → ATIVA por vínculo diferente do solicitante (sem solicitante: recusa) | 403 `SECOND_PERSON`; 409 sem solicitante |
| Convite de emissor sem autoridade nunca volta (024) | gatilhos depois da revogação de papel, da saída do vínculo e da suspensão da conta: revogam os convites abertos do emissor que perdeu a autoridade (motivo 4) | — (o banco faz no mesmo ato) |
| Suspensão e extensão de prazo (KEEP) da concessão | **sem** conferência própria na tabela da concessão (não há coluna de autor para esses atos); o autor é o da linha de revisão gravada na mesma transação, que o gatilho confere | o serviço grava a revisão e a mudança juntas |
| Auditoria imutável | `access_events` sem UPDATE, DELETE nem TRUNCATE | grava na transação do ato |
| Concessão PENDENTE cancelável | restrição de aprovação trocada (REVOGADA sem aprovação) | revogação idempotente |
| Step-up | **não** (só o serviço) | `user_sessions.mfa_step_up_at` em 5 min |
| Efeito nas sessões | a validação da sessão exige vínculo ativo (existente) | revoga sessões na retirada de vínculo |

Os gatilhos de autoridade **não** barram o superusuário (administração do cluster e fixtures declaradas de teste), como a 022. O bootstrap do primeiro ORG_ADMIN (responsável nulo) continua restrito ao provisionamento (003/012).

## 9. Decisões (revisão de 2026-10-27)

| Id | Decisão | Estado | Onde está no código |
|---|---|---|---|
| D-CONV-1 | prazo de 7 dias, conferido no aceite; reenvio invalida o token anterior | **aceita** | 023 (gatilho e aceite), `InvitationService` |
| D-CONV-2 | step-up para ampliar acesso; reduzir exige sessão válida, CSRF, autoridade e auditoria, sem novo step-up | **aceita** | `AccessActions`, serviços de acesso |
| D-CONV-3 | readmissão por convite, sem restaurar papéis ou concessões antigos | **aceita** | 023 (aceite) |
| D-CONV-4 | convite de emissor que perdeu a autoridade deixa de valer e não ressuscita; emitir outro | **aceita; código mudou** | **024** (revogação no ato, motivo 4) |
| D-GRANT-1 | segunda pessoa nas concessões de acesso externo nesta V1: aprovador diferente do solicitante | **decidida; código mudou** | **024** + `GrantAdminService` |
| D-GRANT-1a | a renovação e a revisão KEEP de concessão já aprovada continuam com uma pessoa só (step-up, autoridade, auditoria) | **a confirmar** | — (para exigir segunda pessoa também nesses atos, é preciso um passo de pedido e outro de aprovação) |
| D-EXT | entrada da pessoa externa: reutilizar a identidade global, com conta e permissões externas delimitadas, sem criar outra senha implicitamente. O convite externo só é habilitado depois de fechado esse contrato | **direção definida; contrato a fechar** | 023 recusa convite externo (`42501`, D-EXT); nenhuma rota externa nova |
| D-ELEV-023/024 | linhas de elevação da 023 e da 024 para aplicar num ambiente de homologação | **pendente** (quando houver ambiente alvo) | `aplicar_como_migrador.sh` eleva as duas nas suítes e nas bases de teste |

## 10. Reset operacional (016) de conta vinculada: recusa explícita (024)

Requisito da separação entre identidade global e conta da organização: o reset operacional mexe só na entrada **local** da conta (fator, códigos, desafios e sessões da organização). Ele nunca pode atingir quem entra pela identidade global.

| | Antes da 024 | Com a 024 |
|---|---|---|
| Abertura, verificação, pré-aprovação e aprovação | recusa por efeito: a vinculação exige o fator local desabilitado (017, E-3), e o alvo cai em `SEM_FATOR_CONFIRMADO` | recusa declarada `CONTA_VINCULADA` (`ops.alvo_inelegivel`, antes do fator) |
| Execução de solicitação aprovada antes da vinculação | recusa por efeito: `FATOR_DIVERGENTE` | recusa declarada `CONTA_VINCULADA`, **antes** da conferência do fator; solicitação RECUSADA |
| Privilégio do dono da 016 | sem acesso a `identity_users` | continua sem acesso a tabela de identidade; consulta só `identity_user_is_linked(conta)`, uma função do dono da 017 que devolve um booleano (EXECUTE só para o dono da 016) |

**Prova:** `ResetContaVinculadaIT`, no módulo `nekkora-ops`, com papéis reais e o PostgreSQL real.
- A conta vinculada fica no estado que a vinculação real deixa: entrada local fechada, ligação com a identidade, fator confirmado e sessão da identidade, e uma sessão da organização nascida da identidade.
- Abertura recusada (`CONTA_VINCULADA`): nenhuma solicitação, nenhum aviso, nenhum evento de reset.
- Execução depois da aprovação recusada (`CONTA_VINCULADA`, RECUSADA). Ficam idênticos o fator, os códigos, os desafios e as sessões da conta; o fator e as sessões da identidade; e os avisos. Nenhum evento de reset.
- Controle positivo: a mesma sequência numa conta **não** vinculada executa, com os efeitos do reset e um evento.
- Contraprova sem a 024, rodada de propósito: a abertura volta a `SEM_FATOR_CONFIRMADO` e a execução a `FATOR_DIVERGENTE`.
