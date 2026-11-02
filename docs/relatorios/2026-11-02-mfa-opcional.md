# Segundo fator opcional na entrada geral (migration 029)

Missão PLATFORM-LOCAL-002, item 2. Branch `feat/mfa-opcional-001`, sobre a `main` `e04a57c`. Candidata até a decisão da titular.

## 1. Mapa das ações antes da mudança

Até a 028, o banco só deixava nascer sessão de identidade com MFA (`identity_sessions.mfa_verified_at NOT NULL`). Também só aceitava sessão de organização vinculada com o fator da identidade confirmado (`identity_org_session_allowed`). As ações abaixo exigiam step-up por TOTP feito há até 5 minutos na mesma sessão.

| Ação | Onde é exigido | Sessão |
|---|---|---|
| Criar organização | `identity_create_organization` (017, prova `step_up`) | identidade |
| Aceitar convite | `identity_accept_invitation` (023, prova `step_up`) | identidade |
| Vincular conta antiga | `identity_link_account` (017, prova `step_up`) | identidade |
| Trocar senha | `identity_password_change` (017, prova `step_up`, mais a senha atual) | identidade |
| Regenerar códigos de recuperação | `identity_recovery_codes_regenerate` (019) | identidade (só existe com fator) |
| Trocar o fator | `identity_mfa_enroll_pending` / `identity_mfa_replace` (prova `troca`) | identidade (só existe com fator) |
| Emitir, reenviar convite | `InvitationService` → `AccessActions.requireStepUp` | organização |
| Atribuir, alterar papel | `RoleAdminService` → `requireStepUp` | organização |
| Aprovar concessão, aprovar renovação, revisão KEEP | `GrantAdminService` → `requireStepUp` | organização |
| Parear Agent, rotacionar credencial, revogar Agent | `AgentAdminService` → `requireStepUp` | organização |
| Autorizar Worker no workspace, autorizar execução local | `AgentAdminService` → `requireStepUp` | organização |
| Publicar versão | `AutomationLifecycleService` → `requireStepUp` | organização |
| Reset administrativo de MFA | `AdminService.resetMfa` | organização |

Algumas ações não pediam step-up e continuam sem pedir: criar workspace, cadastrar automação, criar versão e enviar artefato. O mesmo vale para revogar convite, revogar papel e retirar vínculo, que reduzem acesso.

## 2. Política aprovada

Pergunta feita à titular em 2026-11-02 (o que vale para quem não ativou o MFA). Resposta registrada:

> "Sem MFA, a senha deve ser exigida para cada criação sensível, como convites, organizações e etc. […] tudo que se encaixar nessas categorias, deve ser aplicad uma senha"

Implementação:

- **Quem nunca confirmou fator:** entra só com a senha. Em toda ação da tabela acima, a senha digitada de novo há até 5 minutos na mesma sessão substitui o código do autenticador.
- **Exceções, que continuam exigindo o fator:** regenerar códigos e trocar fator, que não existem sem um fator.
- **Quem tem ou já teve fator:** nada muda. O TOTP é exigido no login e nas ações, e a senha nunca substitui o código (o banco recusa).
- **Identidade com o fator desabilitado por reset operacional:** conta como "já teve fator" e não recebe sessão só de senha. O login segue o caminho anterior (`mfa_enrollment_required`).
- **Ativação voluntária:** fica em "Segurança da conta". Depois de ativada, ela é exigida sempre.
- **Recuperação de senha:** não toca o fator. Conferido nas ITs `IdentityPasswordRecoveryIT` e `MfaOpcionalIT`.

**Ressalva registrada.** Sem MFA, quem tiver só a senha de uma conta administradora consegue as ações da tabela, incluindo publicar, convidar e gerir credenciais do Agent. A política foi aprovada nesses termos. Ativar o MFA volta ao nível anterior.

## 3. Desenho

**Banco (029)**
- **Colunas e CHECKs:**
  - `identity_sessions.mfa_verified_at` passa a aceitar nulo, só para identidade que nunca confirmou fator: trigger de INSERT e `identity_session_resolve`/`identity_session_lookup`.
  - Coluna nova `password_step_up_at` em `identity_sessions` e `user_sessions`, gravada só pelo dono (`nekkora_identity_owner`). A trigger recusa qualquer outro papel, inclusive o superusuário.
  - CHECKs: a marca de senha só existe em sessão sem MFA; a marca de recuperação só em sessão com MFA.
- **Funções novas** (dono `nekkora_identity_owner`, `EXECUTE` só para a Platform):
  - `identity_login_password`: conclui o desafio de finalidade 2 sem QR e grava o evento 18.
  - `identity_password_reconfirm`: confere a credencial vigente sob o lock da identidade, grava a marca na sessão de identidade ou de organização vinculada e grava o evento 19.
  - `identity_mfa_activate`: confirma o fator com o passo aceito, grava os códigos, revoga todas as sessões, cria a sessão nova com MFA e grava o evento 20.
- **Prova `step_up` redefinida:** aceita o ramo da senha só em sessão sem MFA de identidade que nunca confirmou fator. O modo `troca` não muda. O modo novo `ativacao` é usado por `identity_mfa_enroll_pending`.
- **Sessão de organização vinculada sem MFA:**
  - o marcador da sessão de organização acompanha o da sessão de identidade de origem (trigger, falha fechada);
  - `identity_org_session_allowed` deixa de valer no instante em que a identidade confirma um fator.
- **Invariantes da 029** (§9, rodam de novo depois da retirada):
  - nenhum fator, código ou sessão alterado pela migration;
  - nenhuma marca nova preexistente;
  - donos e privilégios das funções;
  - escrita da marca só pelo dono;
  - portal sem acesso à coluna nova.

**API**
- **Login:** `POST /api/v1/auth/login` responde `authenticated` com o cookie da sessão para quem nunca confirmou fator.
- **Rotas novas:**
  - `POST /api/v1/auth/password-confirm`;
  - `POST /api/v1/auth/mfa/activate/start` e `/confirm`;
  - `POST /api/v1/o/{org}/auth/password-confirm`.
- **Campos novos:** `GET /api/v1/me/session` traz `mfaEnabled` e `stepUpMethod`; `GET /api/v1/o/{org}/me` traz `stepUpMethod`.
- **Step-up na organização:** `AccessActions.requireStepUp` e o reset administrativo aceitam a marca de senha só em sessão vinculada sem MFA (`SessionRepository.RECENT_STEP_UP_SQL`).

## 4. Provas

| Prova | Resultado |
|---|---|
| `db/tests/mfa-opcional-029/run.sh` (limpa 001-029; populada 028→029 com identidades com fator, fator desabilitado e sem fator) | OK: sem a janela, recusada sem efeito; com a janela, as colunas anteriores ficam idênticas, o catálogo é igual ao da instalação limpa, os invariantes passam depois da retirada, a sessão existente continua e a sessão sem MFA é recusada para quem já teve fator; a reaplicação é recusada |
| `MfaOpcionalIT` (papel real `nekkora_platform`) | 7/7: conta nova sem MFA; senha exigida para criar organização; organização sem MFA (convite com senha, aceite pela segunda pessoa sem MFA); ativação voluntária; conta com MFA continua pedindo o código; banco recusa marca e sessão indevidas; recuperação preserva o fator ativado |
| ITs de identidade e acessos (21 classes) | Passam. As 4 que testavam o cadastro do primeiro fator no desafio do login (caminho que a 029 torna inalcançável) foram reescritas para a ativação |
| Regressão Maven completa | ver o PR |
