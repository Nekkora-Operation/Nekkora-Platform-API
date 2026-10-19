# Contratos pendentes da identidade: step-up, criação de organização, vinculação e step-up por organização em conta vinculada

**Data:** 2026-10-13; parecer incorporado em 2026-10-14. **Natureza:** contrato, sem código. Ele fecha as propostas P1 a P4 do relatório do bloco 3 (`2026-10-13-bloco3-organizacoes-troca.md`, §6) com os ajustes pedidos na revisão da responsável.

**Parecer da responsável sobre as decisões pedidas (2026-10-14):**

| Decisão | Encaminhamento |
|---|---|
| R5 da 017 | de acordo com os eventos 13 e 14 e com `identity_org_creation_allowed`. **A função entra no escopo** (deixa de ser opcional), porque sustenta o significado de `canCreateOrganization` |
| Cookie de identidade em P4 | de acordo, **exclusivamente** na rota de step-up da organização e para a sessão de identidade **exata** que originou aquela sessão de organização |
| Ordem | **P1 → P2 → P4 → P3**, depois de resolvido o cenário de locks da revisão 2 do bloco 3. Assim a organização criada pelo cadastro já pode usar as ações administrativas protegidas |

**Duas condições explícitas do parecer:** a de P4 está na seção P4, e a de `canCreateOrganization` na seção P2. Além delas, a capacidade inicial de execução continua decisão de produto pendente: o zero das fixtures e do bootstrap **não** é regra aprovada.

**Ordem global de locks (revisão 2 do bloco 3).** Todo contrato abaixo trava identidade → sessão de identidade → organização → conta, **contando os locks implícitos das chaves estrangeiras**. Uma escrita que referencia `identities` ou `identity_sessions` (evento, sessão vinculada, desafio) só acontece com essas linhas já travadas (desenho §13.5, requisitos 1 e 7).

**O que a responsável já decidiu na revisão do bloco 3:**

| Item | Situação |
|---|---|
| P1 — direção | aprovada, com reconferência da sessão e do fator sob os locks, anti-replay e janela final. **Auditoria só em log não é aceita:** é preciso um evento persistente e a alteração mínima necessária |
| P2 | chave própria, R11, teto no banco e recusa genérica, de acordo. A capacidade inicial de execução continua decisão de produto separada |
| P3 | duas etapas, de acordo. Antes do código: CSRF, consumo e anti-replay do fator antigo, reconferência da credencial e vínculo do desafio à identidade e à conta exatas. Os bloqueios da entrada e da recuperação locais entram junto |
| P4 | a lacuna procede, e validar o fator da identidade de origem está de acordo. **Não aprovado:** gravar step-up nas duas sessões. O contrato deve definir o alcance e impedir misturar a sessão de organização com outra identidade presente no navegador |

**Uma única mudança de banco é proposta, a R5 da 017 (§5).** Todo o resto cabe nos privilégios e funções que a 017 já tem.

**Regras comuns a todos os contratos abaixo:**
- toda escrita exige `Origin` igual à origem pública e o `X-CSRF-Token` do contexto indicado;
- toda conferência de TOTP segue a regra fechada no bloco 2:
  - o fator é lido **sob o lock** da identidade, ou da conta no caso de fator local;
  - o passo só avança (anti-replay no banco);
  - a janela do passo é **reconferida depois de todas as esperas**, antes do commit;
  - fora da janela, rollback integral;
- o evento é gravado **na mesma transação** do efeito (revisão 1 do bloco 3);
- log sem e-mail, senha, código, token nem hash.

---

## P1 — Step-up da identidade

**Situação em 2026-10-14:** implementado e entregue isoladamente para revisão (relatório `2026-10-14-p1-step-up-identidade.md`), conforme a tabela abaixo, sem mudanças de contrato. Revisão 1 no mesmo dia: a sessão passa a ser reconferida pelo relógio do banco depois de todas as esperas e antes do commit (vencida: `401`, rollback integral), cumprindo o que a tabela já exigia.

| Item | Contrato |
|---|---|
| Rota | `POST /api/v1/auth/step-up` `{code}`: só TOTP. `recoveryCode` não é alternativa, como no step-up por organização |
| Autenticação | sessão de identidade **não restrita**. Restrita: `403`, porque a DC-17.13 não inclui o step-up no que a sessão restrita pode fazer |
| CSRF | `IDENTITY_SESSION` (o mesmo da troca) |
| Limites | R12; R8m pela identidade global, contando o código errado como falha de MFA |
| Chave | `nekkora.entrada-geral.enabled` |
| Transação | `inIdentity(TokenSessao)`, READ COMMITTED: identidade `FOR UPDATE` → sessão `FOR UPDATE` → **reconferência da sessão** sob o lock (ativa, no prazo, não restrita, identidade ACTIVE) → fator confirmado e habilitado lido sob o lock → TOTP → `UPDATE identity_mfa_factors SET last_used_step = passo WHERE factor_id = ? AND (last_used_step IS NULL OR last_used_step < passo)`, que precisa afetar 1 linha → `UPDATE identity_sessions SET mfa_step_up_at = clock_timestamp()` → evento → **janela do passo reconferida**; fora dela, rollback |
| Resposta | `204`. Código errado, replay ou sem fator confirmado: `403` genérico, como `AuthService.stepUp`, com a falha contada em R8m. Sessão que caiu: `401` |
| Evento | `identity_events` tipo **13 = `STEP_UP`**, na mesma transação. Exige a R5 (§5) |
| Privilégios | a Platform já tem `UPDATE (last_used_step)` em `identity_mfa_factors` e `UPDATE (mfa_step_up_at)` em `identity_sessions`. A trigger da 017 já recusa passo que não avança, step-up que recua e step-up no futuro |

---

## P2 — Criação de organização

**Situação em 2026-10-14:** implementado e entregue isoladamente para revisão (relatório `2026-10-14-p2-criacao-organizacao.md`), conforme a tabela abaixo. Duas precisões de implementação, sem mudar o contrato: R11 é uma **reserva** atômica por identidade, devolvida quando a criação comprovadamente não aconteceu (recusa da função ou exceção antes da criação) e mantida em commit confirmado ou resultado desconhecido (revisão 1, parecer de 2026-10-15); por isso podem restar reservas sem criação confirmada; e a recusa da função em que a sessão caiu (revogada ou vencida durante a espera) responde `401`, como no P1, em vez do `403` genérico (aprovado em 2026-10-15). A chave própria só abre com a da entrada geral também ligada.

| Item | Contrato |
|---|---|
| Rota | `POST /api/v1/organizations` `{name, legalName?}` (§6.6 do desenho) |
| Autenticação | sessão de identidade não restrita **com step-up de até 5 min**. Quem confere o step-up é a função (`identity_session_proof`, modo `step_up`), não a aplicação |
| CSRF | `IDENTITY_SESSION` |
| Limites | R11 por identidade (3 por dia), falhando fechado; R12. O teto total de 10 continua **dentro** da função, sob o lock da identidade |
| Chave | **própria**, `nekkora.criacao-organizacao.enabled`, desligada por padrão; desligada, `404` |
| Corpo | `name`: 1 a 120 pontos de código, sem espaços nas pontas e sem caracteres de controle. `legalName`: opcional, com a mesma regra. Fora disso: `400` |
| Transação | uma, `inIdentity(TokenSessao)`, READ COMMITTED, que chama só `identity_create_organization`. A função cria a organização, a conta, o vínculo, o ORG_ADMIN, `identity_users` e o evento 6 (`ORG_CREATED`), tudo no mesmo commit |
| Resposta | `201 {organizationId}`. Função devolveu nulo (sem step-up, teto atingido, identidade inelegível ou sessão restrita): `403` genérico. O front-end faz o step-up imediatamente antes da criação |
| `canCreateOrganization` | passa a significar: chave ligada, sessão não restrita e **a função de consulta da R5 (§5) diz que a identidade ainda pode criar**. O step-up **não** entra no cálculo, porque é pedido na hora |
| **Condição do parecer** | `canCreateOrganization` é **só uma indicação para a interface**. A criação continua reconferindo autorização (sessão, step-up, identidade) e teto **dentro da função de escrita** (`identity_create_organization`), sob o lock da identidade, qualquer que seja o valor mostrado. A função de consulta entra na **matriz de privilégios** (grupo M) e nos testes de sessão **expirada, revogada e restrita** (§5) |
| Capacidade de execução | continua a do bootstrap (zero), até a decisão de produto |

---

## P3 — Vinculação de conta antiga

**Situação em 2026-10-18:** implementado e entregue para revisão no PR da missão PLATFORM-PR-001 (relatório `2026-10-18-p4-p3-contas-vinculadas.md`), conforme as tabelas abaixo, sem mudança de contrato. Precisões de implementação: chave própria `nekkora.vinculacao.enabled`, desligada por padrão e só aberta com a entrada geral (os bloqueios da entrada local valem com ela desligada); a recusa da trigger (23514) no login, na recuperação e na troca de senha locais é respondida como o contrato manda (antes, `500`); o reset administrativo de conta vinculada é recusado com `403` genérico pelo **serviço**, sob o lock da conta e antes de qualquer efeito (parecer de 2026-10-19). A trigger não o recusa, porque as escritas do reset são terminais, e as exceções terminais da 017 continuam como estão; não há rota de reenvio de verificação local na Platform.

### Etapa 1 — prova da conta antiga

| Item | Contrato |
|---|---|
| Rota | `POST /api/v1/links/start` `{organizationId, password, code}` ou `{organizationId, password, recoveryCode}`, com **exatamente um** dos dois códigos; nenhum ou os dois: `400` |
| Autenticação e CSRF | sessão de identidade não restrita; `IDENTITY_SESSION` |
| Conta alvo | procurada **pelo e-mail normalizado da identidade**, na organização pedida. A pessoa nunca informa outro e-mail, então a guarda 1 da DC-17.11 vale por construção |
| Leitura e Argon2 | transação somente leitura com os dois contextos para achar a conta e o hash vigente; **Argon2 fora da transação**, contra o hash ou contra o hash fictício quando não há conta (mesmo custo) |
| Transação de prova | `inIdentityAndOrganization(TokenSessao, org)`, READ COMMITTED. Locks na ordem global: identidade → sessão de identidade (as duas `FOR UPDATE`, com reconferência da sessão) → organização `FOR SHARE` → **conta `FOR UPDATE`** → fator local ou código local alvo |
| Reconferência da credencial | sob o lock da conta, o hash vigente de `user_credentials` tem de ser **igual** ao que o Argon2 conferiu. Senão, recusa, como a reconferência do login |
| Reconferência da conta | sob o lock: conta interna ACTIVE com e-mail verificado; vínculo interno ACTIVE; organização ativa; e-mail igual ao da identidade; **não vinculada** (`identity_account_is_linked`, que a Platform já executa) |
| Fator antigo, TOTP | fator local confirmado e habilitado lido sob o lock da conta; TOTP conferido; **anti-replay**: `consumeStep` (passo só avança, 1 linha afetada); janela final reconferida antes do commit |
| Fator antigo, recuperação | código local vivo localizado pelo hash e **consumido** (`used_at`), 1 linha afetada |
| Por que consumir já na etapa 1 | o código ou o passo prova a posse **agora**, como no login. Se a etapa 2 não acontecer, a conta antiga fica só com um código a menos, como num login comum. Se acontecer, o fechamento da DC-17.12 invalida o resto |
| Desafio | revoga qualquer desafio de vinculação vivo **da mesma identidade**, e então `INSERT identity_link_challenges (identity_id, user_id, token_hash, account_credential_hash = hash conferido, expires_at = now() + 5 min)`. A trigger da 017 trava a conta de novo e confere e-mail e ausência de vínculo. O desafio fica amarrado à **identidade e à conta exatas** pelas colunas, que a trigger torna imutáveis |
| Token | 32 bytes; só o hash no banco; o valor vai num cookie novo, `__Host-nekkora_link`, com `SameSite=Strict`, `Max-Age` de 5 min e `Path=/` |
| Resposta | `204` + cookie. **Qualquer** falha: `401` único, sem distinguir conta inexistente, senha errada, código errado, conta inelegível ou já vinculada |
| Limites | R13, novo, falhando fechado: falhas por identidade (5 em 15 min) e por conta alvo, com a chave HMAC de organização + e-mail; R12 |
| Evento | nenhum na etapa 1 (sem efeito sobre o vínculo); falhas vão ao log sanitizado. O uso de código local já gera `mfa_events` 2 (`RECOVERY_CODE_USED`), como hoje |

### Etapa 2 — prova da identidade e consumo

| Item | Contrato |
|---|---|
| Rota | `POST /api/v1/links/confirm` `{organizationId}` |
| Autenticação e CSRF | sessão de identidade não restrita **com step-up de até 5 min** (P1) e o cookie `__Host-nekkora_link`; `IDENTITY_SESSION` |
| Transação | uma, READ COMMITTED, que chama só `identity_link_account(sessão, desafio, organização)`. A função trava identidade → sessão → organização → desafio → conta → linhas do fechamento; mede a hora depois dos locks; reconfere step-up, conta, e-mail e ausência de vínculo; **consome** o desafio só se ele for da identidade da sessão, estiver no prazo e a credencial da conta ainda for a da etapa 1; e executa o fechamento inteiro da DC-17.12 e o evento 9 (`IDENTITY_LINKED`) |
| Resposta | `204`; função devolveu `false`: `403` genérico. Nos dois casos o cookie do desafio expira |
| Depois | as sessões de organização da conta já foram revogadas pela função; a pessoa entra pela troca do bloco 3. Aviso ao endereço, depois do commit e fora da transação |
| Limites | R12; a etapa 2 não tem código a errar, e a função é de uso único |

### O que entra junto, antes do primeiro vínculo

| Bloqueio | Contrato |
|---|---|
| Login por organização (DC-17.9, camada 1) | a elegibilidade de `AccountRepository` passa a exigir conta não vinculada, por `identity_account_is_linked`. Resultado: o `401` genérico de sempre, e a reconferência sob o lock invalida um desafio de conta vinculada no meio do caminho |
| Recuperação de senha por organização (DC-17.12) | a trigger recusa o token (`23514`). O serviço trata isso como "não enviar", e a resposta continua `202` idêntica, com o mesmo piso |
| Reenvio de verificação de e-mail | mesmo tratamento: `202` idêntico |
| Cadastro de fator local, troca de senha local, reset administrativo | a trigger recusa (`23514`): `403` genérico nas rotas autenticadas e `401` no desafio |
| Testes | os quatro intercalamentos da §4.5 do desenho, pela API, mais estes |

---

## P4 — Step-up por organização em sessão vinculada

**Situação em 2026-10-18:** implementado e entregue para revisão no mesmo PR (relatório `2026-10-18-p4-p3-contas-vinculadas.md`), conforme a tabela abaixo, sem mudança de contrato e sem chave nova (a rota é a existente; a sessão vinculada só nasce pela troca). A igualdade entre o cookie de identidade e a sessão de origem é conferida antes da transação e de novo dentro dela, sob o lock.

**Alcance, conforme o parecer:** a confirmação pedida na organização eleva **só aquela sessão de organização**. A sessão de identidade **não** recebe `mfa_step_up_at`, e nenhuma outra sessão de organização é elevada.

| Item | Contrato |
|---|---|
| Rota | a existente, `POST /api/v1/o/{org}/auth/mfa/step-up` `{code}`, sem rota nova (correção de 2026-10-14: a versão anterior omitia o `/mfa` do caminho real). Sessão do login por organização: o caminho atual, intacto |
| Sessão vinculada | sessão de organização com `identity_session_id` preenchido: o TOTP conferido é o do **fator da identidade de origem**, não o de um fator local |
| **CSRF (condição do parecer)** | o **CSRF da sessão de organização** continua exigido, como hoje na rota (`X-CSRF-Token` da sessão `__Host-nekkora_session`). O cookie de identidade é uma exigência **adicional**: o CSRF global (`IDENTITY_SESSION`) **não** o substitui nem é aceito no lugar dele. Só aquela sessão de organização recebe o step-up |
| Sem mistura de identidades | a rota passa a ler também o cookie `__Host-nekkora_id`, **só neste caso**. A sessão de identidade resolvida tem de ser **exatamente** a de origem: `identity_session_id` do cookie igual ao da sessão de organização. Cookie ausente, de outra identidade ou de outra sessão da mesma identidade: `403`, sem conferir código. É a única rota em que os dois cookies valem juntos, como exceção declarada ao §6.7 do desenho |
| Por que exigir o cookie | sem ele, a Platform não chega ao fator da identidade: no contexto de organização ela não lê `identity_sessions`, e a alternativa seria uma função nova na 017. Exigir a sessão de origem também prova que quem confirma ainda tem a sessão global que originou a de organização |
| Transação | `inIdentityAndOrganization(TokenSessao do cookie, org)`, READ COMMITTED. Locks: identidade → sessão de identidade (reconferida: ativa e não restrita) → organização `FOR SHARE` → conta → sessão de organização `FOR UPDATE` (revalidada, inclusive pela auxiliar da DC-17.4) → igualdade das duas sessões → fator da identidade sob o lock → TOTP → anti-replay no fator da identidade → `user_sessions.mfa_step_up_at` **só na sessão de organização** → eventos → janela final |
| Anti-replay | o passo avança **no fator da identidade**, que é um só. Um código usado aqui não serve para outro step-up, global ou de outra organização |
| Eventos | `mfa_events` 6 (`STEP_UP`) na organização, a auditoria por organização que já existe, e `identity_events` **14 = `ORG_STEP_UP`** com a organização alvo (R5). Os dois na mesma transação |
| Resposta | `204`; código errado, replay, cookie divergente: `403`, como hoje; sessão que caiu: `401` |
| Limites | R8m pela identidade global, em vez de R1/R4 por organização: o fator é global, e o limite tem de ser também |
| Estrutura | `platform.auth` não importa a identidade (R2). O controller encaminha a sessão vinculada para um serviço da identidade, e o lado da organização (validação e `recordStepUp` da sessão, evento em `mfa_events`) entra por uma porta do lado da organização, como a `LinkedSessionIssuer` |

---

## 5. R5 da 017: a alteração mínima

| Peça | Mudança | Por quê |
|---|---|---|
| `identity_events.event_type` | `CHECK (event_type BETWEEN 1 AND 14)`; comentário com `13=STEP_UP` e `14=ORG_STEP_UP` | evento persistente de P1 e P4, pedido no parecer |
| `identity_org_creation_allowed(p_session_token)` → `boolean` (**no escopo**, parecer de 2026-10-14) | `SECURITY DEFINER`, só leitura, `EXECUTE` só para a Platform. Devolve verdadeiro se a sessão está ativa e não restrita, a identidade está ACTIVE e verificada e há menos de 10 vínculos `cadastro_web` | semântica de `canCreateOrganization` (P2). Sem ela, o campo não reflete o teto. **Só indicação**: a criação reconfere tudo na função de escrita |

Nada mais muda na 017: nem privilégio de tabela, nem trigger, nem as funções existentes. A matriz do grupo M e as invariantes que contam as funções executáveis pela Platform são atualizadas junto, com a suíte da 017 rodando inteira. A função ganha testes com sessão **expirada, revogada e restrita**, token inválido, identidade inelegível e teto atingido. Como nas revisões anteriores, a R5 sai num pacote próprio, antes do código que a usa.
