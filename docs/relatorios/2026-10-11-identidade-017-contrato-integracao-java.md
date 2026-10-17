# Contrato da integração Java com a 017 (proposta para revisão)

**Data:** 2026-10-11. **Estado:** direção aprovada pela responsável, com três ajustes incorporados nesta revisão (§1.1, §1.2, §1.3, §2.1, §2.2). A R3 da 017 implementa a parte de banco. Nenhum código Java, migration, fixture ou configuração foi alterado para escrevê-la. A R2 da 017 foi aprovada no escopo da migration e da suíte local; isso **não** libera implantação nem commit/push.

**Base conferida nesta árvore:**
- `nekkora-core/.../db/OrgTransactions.java`;
- `nekkora-core/.../testsupport/RealDatabase.java`;
- `nekkora-platform/.../{AuthService, AccountRepository, MfaRepository, SessionFilter}.java`;
- as fixtures e os ITs de `nekkora-platform`, `nekkora-ops` e `nekkora-portal`.

Os quatro itens pedidos pela responsável estão nas §1 a §4. A §5 junta o que depende de decisão dela.

---

## 1. Contexto de identidade: definição e limpeza transacional

### 1.1 Regra

Segue o padrão que `OrgTransactions` já usa para a organização:

| Regra | Como |
|---|---|
| contexto só **dentro de transação** | `IdentityTransactions` nova em `nekkora-core`, irmã de `OrgTransactions`: `READ COMMITTED`, `REQUIRES_NEW` |
| sequência dentro da transação | **(1)** guarda de contexto vazio → **(2)** resolução por função de consulta (ou UUID novo, no cadastro) → **(3)** `SELECT set_config('nekkora.identity_id', ?, true)`, com o valor por bind e `is_local = true` → **(4)** acesso às tabelas. O `set_config` **não** é o primeiro comando; é o primeiro depois da resolução, e nenhum acesso a tabela de identidade vem antes dele |
| limpeza | **automática no `COMMIT` ou `ROLLBACK`**, porque o valor é local. Nunca `SET` sem `LOCAL`, nunca `set_config(..., false)`; uma regra de revisão de código e um teste (§1.4) garantem isso |
| guarda de vazamento (passo 1) | primeiro comando da transação: confere que `nekkora.identity_id` **e** `nekkora.organization_id` estão vazios, e aborta se não estiverem. Isso pega um `SET` de sessão esquecido numa conexão reaproveitada do pool |
| mesma thread | sem `@Async`, `CompletableFuture` ou código reativo dentro da transação, como já diz `OrgTransactions` |

### 1.2 De onde vem o `identity_id` posto no contexto

Esta é a regra que torna a RLS útil. A RLS limita o acesso pelo contexto, mas não prova autenticação. O valor só pode vir de duas fontes:

1. **do resultado de uma função de consulta por chave exata, na mesma transação**: `identity_session_lookup(token do cookie)`, `identity_challenge_lookup(token)`, `identity_email_token_lookup(token)` ou `identity_signup_state(e-mail)`, este último só no cadastro;
2. **de um UUID gerado pela própria aplicação no cadastro**, antes do `INSERT` em `identities`.

**Nunca** de parâmetro de rota, corpo de requisição, cabeçalho ou cookie lido diretamente. A API da classe expressa isso: `inIdentity(...)` não recebe um `UUID` solto. Recebe a **chave** (token ou e-mail normalizado), e a resolução acontece dentro da própria transação.

**Um `ResolvedIdentity` não atravessa transações** (ajuste da responsável). Ele vale só dentro da transação que o produziu. Uma transação nova que precise da identidade **resolve de novo pela chave** e reconfere o estado de que depende. O caso da entrada geral está na §1.3.

### 1.3 Fluxos e contexto

| Fluxo (§6 do desenho) | Transação |
|---|---|
| `IdentityFilter` (toda rota sem organização) | sem contexto → `identity_session_lookup` → contexto = `identity_id` devolvido → trabalho da rota |
| cadastro | `identity_signup_state(e-mail)`. Livre: gera o UUID, define o contexto e insere a identidade, a credencial e o token. Pendente: contexto da identidade devolvida, revoga o token e emite outro. Ativa: nada no banco, só o aviso |
| confirmação de cadastro | `identity_email_token_lookup` → contexto → consome o token e ativa a identidade |
| entrada geral | **T1:** `identity_resolve_login(e-mail)` devolve o `identity_id`, o estado e o hash, e a transação termina. **Argon2, fora de transação e sem conexão**, confere a senha contra esse hash. **T2:** guarda → `identity_resolve_login(e-mail)` **de novo** → confere que o `identity_id` é o mesmo, que o estado continua ACTIVE e verificado, e que o hash devolvido é **igual** ao que o Argon2 conferiu. Qualquer divergência dá o `401` genérico e nenhuma escrita. Só então vem o contexto e o desafio, gravado com `credential_hash` = esse hash. O resultado de T1 não autoriza T2: só a resolução e a conferência dentro de T2 |
| MFA, verificação (finalidade 1) | `identity_challenge_lookup` → contexto → TOTP conferido pela aplicação → **`identity_login_complete`**, que consome, usa o fator e **cria a sessão** numa só operação (§2.1) |
| MFA, primeiro cadastro (finalidade 2) | `identity_challenge_lookup` → contexto → `identity_mfa_enroll_pending` e `identity_mfa_confirm_first`, que também consome, confere a credencial vigente e **cria a sessão** |
| recuperação, T0 | `identity_login_complete` no modo recuperação: consumo do desafio e do código, sessão restrita, revogação das demais sessões e invalidação dos demais desafios, numa só operação |
| troca por sessão de organização | `identity_org_session_target` (função), e depois `OrgTransactions.inOrganization` para inserir `user_sessions`. **Não** precisa de contexto de identidade |
| vinculação, etapa 1 | é o **único** fluxo com os dois contextos: `inIdentityAndOrganization`, porque a trigger do desafio de vinculação trava a conta sob a RLS de organização. Etapa 2: `identity_link_account` |
| criação de organização, `/me/organizations` | funções de dono; contexto de identidade só para gravar evento, se a rota gravar |
| logout | `identity_session_lookup` → contexto → revoga a sessão; sem sessão válida, nada a fazer |

### 1.4 Testes de integração

- **Pool com uma única conexão** atendendo, em sequência, requisições de duas identidades: a segunda nunca vê nada da primeira.
- Um `SET` de sessão injetado de propósito faz a guarda de vazamento abortar a transação seguinte.
- Requisição com o `identity_id` de outra pessoa no corpo: nenhum efeito, porque o contexto vem da sessão.
- `@Async` dentro da transação: zero linhas, falha fechada.

---

## 2. Senha da identidade: funções autorizadas

A R2 tirou `SELECT` e `UPDATE` de `identity_credentials`. Re-hash e troca de senha precisam de funções próprias, que **nunca devolvem o hash**.

**Proposta: entram na própria 017 como R3.** A 017 ainda é candidata e não foi aplicada em base persistente. Uma 018 dependeria de uma candidata.

### 2.1 Conclusão do login: consumo, fator, sessão e re-hash numa só operação

Ajuste da responsável: separar re-hash perdido de autenticação obsoleta, e fazer tudo de forma atômica.

**`identity_login_complete(p_challenge_token, p_metodo, p_passo_totp, p_codigo_hash, p_sessao_hash, p_csrf, p_sessao_expira) → (status, identity_session_id)`**

| Regra | Contrato |
|---|---|
| **finalidade** | aceita **só** desafio de finalidade 1 (verificação). O desafio de cadastro (2) só é consumido por `identity_mfa_confirm_first`. A troca de finalidade dá `DESAFIO_INVALIDO`, sem efeito |
| locks, todos antes da hora | identidade → desafio → credencial → fator → (recuperação: o código, as demais sessões e os demais desafios) |
| **credencial vigente, sempre** | com ou sem re-hash pendente, `credential_hash` do desafio tem de ser **igual** ao `password_hash` vigente. Se não for, `SENHA_OBSOLETA`: o desafio é **invalidado** (hashes limpos) e **nenhuma sessão nasce**, mesmo que o MFA tenha passado |
| re-hash concorrente da mesma senha | **não há tratamento de equivalência.** Hash diferente, sozinho, não prova que a senha é a mesma, e o banco não verifica Argon2. Se outro login concluiu antes um re-hash, este desafio vira `SENHA_OBSOLETA` e a pessoa digita a senha de novo. É o custo aceito de não presumir equivalência |
| re-hash | só quando há `pending_password_hash` **e** a credencial confere: comparar e trocar sobre o hash validado, sem mudar `password_changed_at` |
| uso do fator | TOTP: o passo informado tem de ser maior que `last_used_step` (anti-replay). Recuperação: o código tem de estar vivo. Se falhar, `FATOR_RECUSADO`, sem efeito (as tentativas continuam com a aplicação) |
| **atomicidade** | depois da hora medida, a primeira escrita é o consumo condicionado à validade; em seguida o re-hash, o uso do fator ou do código, a **criação da sessão** e, na recuperação, a revogação e a invalidação de T0. Tudo no mesmo comando: qualquer erro desfaz tudo, junto com a transação da aplicação |
| sessão | **só nasce por função.** A Platform **perde** `INSERT` em `identity_sessions`, `UPDATE (consumed_at)` em `identity_auth_challenges` e `UPDATE` nos códigos de recuperação. Ficam com ela o step-up (`mfa_step_up_at`, `last_used_step`), `last_seen_at` e a revogação |

Os mesmos contratos de credencial vigente, re-hash e criação de sessão valem para **`identity_mfa_confirm_first`** (finalidade 2), que passa a devolver `(status, identity_session_id)`.

### 2.2 Troca de senha

**`identity_password_change(p_session_token, p_expected_hash, p_new_hash) → boolean`**

1. **Locks antes da hora:** identidade → sessão → credencial → **desafios de autenticação pendentes** → demais sessões.
2. **Prova:** sessão no modo `step_up` (não restrita, step-up de até 5 minutos), medida depois dos locks.
3. **Primeira escrita:** comparar e trocar sobre `p_expected_hash`. Se não trocar, nenhum efeito.
4. **Mesmo comando:** `password_changed_at`, **invalidação de todos os desafios de autenticação pendentes, com limpeza dos hashes** (ajuste da responsável: um desafio emitido antes da troca não completa o login), revogação das **demais** sessões e o evento `PASSWORD_CHANGED` (12).

**Limite declarado:** o banco não verifica Argon2. Comparar e trocar prova só que a aplicação apresentou o hash vigente.

### 2.3 Fora desta proposta

Recuperação de senha da identidade por e-mail (`identity_email_tokens.purpose = 2`, reservado). É fluxo próprio, depende da Missão 2 e fica para decisão posterior.

### 2.4 Testes, na suíte de banco (R3)

| Cenário | Esperado |
|---|---|
Implementados como grupo S da R3, com espera de lock observada nos casos de concorrência e mutações que precisam reprovar. O detalhe está no relatório da R3.

---

## 3. Fixtures e regressão dos fluxos antigos

### 3.1 O que quebra, e por quê

As triggers da 017 exigem `nekkora.organization_id` antes de conferir o vínculo, **inclusive para superusuário** (§13, item 7 do desenho). Hoje, **16 arquivos de teste** escrevem nas tabelas protegidas (`user_sessions`, `user_credentials`, `auth_challenges`, `user_mfa_factors`, `user_mfa_recovery_codes`, `password_reset_tokens`, `email_verification_tokens`) sem esse contexto, quase sempre por `DB.asSuperuser`:

| Módulo | Arquivos |
|---|---|
| `nekkora-platform` | `PlatformTestBase` (base de quase todos os ITs: `insertUser` grava `user_credentials`), `AccessFixtures`, `AuthenticationFlowIT`, `EmailVerificationRouteIT`, `EmergencyRecoveryFlowIT`, `InventarioLocksPlatformIT`, `MfaAtomicCompletionIT`, `SessionsAndAdministrationIT`, `mail/ClienteH016IT`, `e2e/CrossProcessE2E` |
| `nekkora-ops` | `testsupport/OpsBanco` (base de todos os ITs do módulo), `ConcorrenciaIT`, `EstadosEPrazosIT`, `Revisao4DeclaracaoIT`, `Revisao4PrazosEImpedimentosIT` |
| `nekkora-portal` | `PortalIT` |

Na prática, **quase todos os 37 ITs falham já na preparação**. Isso não é regressão de comportamento: o produto sempre escreve dentro de `inOrganization`. É a fixture que escreve fora do caminho que o banco agora exige.

Dois ITs (`MfaAtomicCompletionIT` e `SessionsAndAdministrationIT`) desligam triggers da 014 pelo nome (`trg_auth_challenges_prevent_change` e `trg_user_sessions_prevent_reactivation`) para forjar prazos. As triggers da 017 continuam ligadas nesses casos. Com o contexto definido, um prazo forjado numa conta não vinculada passa normalmente. Nenhuma fixture precisa desligar trigger da 017.

### 3.2 Adaptação proposta

- **`RealDatabase.asSuperuserIn(org, sql...)`**: uma transação com `SET LOCAL nekkora.organization_id` antes dos comandos. Toda escrita de fixture nas sete tabelas passa a usá-la, com a organização da conta.
- **Proibido** nas fixtures: desligar trigger da 017, usar `session_replication_role` para isso, ou criar conta vinculada por escrita direta. Vínculo de fixture só por `identity_link_account` ou `identity_create_organization`, como a suíte de banco já faz.
- `MedicaoDc07` usa `session_replication_role = replica` só na carga em lote, que é preparação declarada e não toca contas vinculadas. Continua como está, mas registrado.

### 3.3 Regressão

1. **Linha de base:** os 37 ITs na árvore atual (`20ccb06`), com 001–016, **sem** a 017, para registrar o que já falha antes.
2. **Com a 017 e as fixtures adaptadas:** os mesmos 37. Critério: **todo IT que passava na linha de base passa**, sem mudar nenhuma asserção de comportamento. Só a preparação muda, e cada mudança fica listada no relatório com o arquivo e o motivo.
3. **ITs novos** da §10 do desenho (`SignupRouteIT`, `GeneralLoginIT` etc.), quando a camada Java existir, mais os testes da §1.4.

---

## 4. Ordem de aplicação da 016 e da 017 em base persistente

**Fatos:**
- As duas são independentes: nenhuma substitui objeto da outra.
- A suíte da 017 provou 001–015 + 017 (limpa e populada) e 001–017 com a 016 (limpa).
- **Não** foi provada a atualização **de uma base populada que já tem a 016** para a 017.
- O `aplicar_como_migrador.sh` aplica em ordem numérica e marca a base com a última versão aplicada.

**Recomendação: 016 → 017, sempre nessa ordem.**
- Aplicar a 017 numa base sem a 016 deixaria uma lacuna na numeração. O marcador e o modelo do migrador não preveem isso, e a 016 depois entraria fora de ordem.
- **Se a 016 não for aprovada**, a 017 espera, ou é renumerada por decisão explícita. Ela não pula a 016.
- **Portão novo antes de qualquer base persistente:** acrescentar à suíte da 017 a parte D, atualização de base populada **016 → 017**, com massa da 016 (operadores, solicitações, avisos) e o instantâneo dos dados dos dois schemas.
- `ops/homologacao/implantacao/elevacao.txt` precisa incluir a 017 nessa etapa de ambiente. Hoje só o script local inclui.

---

## 5. Decisões da responsável

| # | Decisão | Recomendação |
|---|---|---|
| 1 | Funções de senha (§2) entram na 017 como R3, ou numa 018 | R3 da 017 |
| 2 | O `UPDATE (consumed_at)` direto da Platform em `identity_auth_challenges` sai, e o consumo passa só por função (§2.1) | sim |
| 3 | `asSuperuserIn` e as proibições para fixtures (§3.2) | sim |
| 4 | Ordem 016 → 017, com o portão novo de base populada 016 → 017 (§4) | sim |
| 5 | Capacidade de execução no cadastro | **decisão de produto, pendente.** O zero da suíte não a resolve. A recomendação registrada é sua: cadastro imediato, execução dependente de ativação explícita, condição visível na web |

**Ordem de trabalho proposta, depois das decisões:**
1. R3 da 017: §2 e a parte D da suíte (§4), com nova rodada e pacote.
2. Linha de base dos 37 ITs sem a 017.
3. `IdentityTransactions` e as fixtures adaptadas (§1, §3), com a regressão completa.
4. A camada Java dos fluxos novos.

Implantação, ambiente preservado e commit/push continuam fora até autorização explícita.
