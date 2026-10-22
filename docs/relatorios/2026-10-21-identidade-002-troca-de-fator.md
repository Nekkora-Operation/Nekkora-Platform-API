# Substituição e recuperação do MFA da identidade (missão 002, PR 2)

**Base:** `main` em `8e7ef18`. **Depende de:** PR #9 (só documentação; nenhuma dependência de código).

## 1. Comportamento entregue

`POST /api/v1/auth/mfa/enroll/start` e `/confirm` passam a ter **dois modos explícitos**. O modo é decidido num só lugar (`Routes.enrollMode`), que o `CsrfFilter` e o controller usam:

| Credencial apresentada | Modo | CSRF exigido |
|---|---|---|
| só o cookie de desafio `__Host-nekkora_id_mfa` | desafio de login: primeiro fator (inalterado) | do desafio |
| só o cookie de sessão `__Host-nekkora_id` | sessão de identidade: substituição autorizada | da sessão |
| os dois | **ambíguo:** `400 invalid_request` antes de qualquer acesso ao banco, e de novo no controller | — |
| nenhum | desafio (falha como antes) | do desafio |

### Substituição (modo sessão)

O modo sessão segue a DC-17.13 e só a própria sessão de identidade o usa. Nenhum papel de organização tem autoridade aqui.

**Caminhos autorizados:**
- **A, troca de aparelho:** sessão comum com step-up de até 5 min (`POST /api/v1/auth/step-up`, TOTP do fator atual).
- **B, fator perdido:** senha + código de recuperação (`/mfa/verify` com `recoveryCode`) abre a sessão **restrita** (T0, já existente). A troca precisa acontecer em até 15 min da marca de recuperação.

**Início** (`identity_mfa_enroll_pending` no modo sessão, já existente na 017):
- a função reconfere a prova `troca` depois dos locks;
- descarta o pendente anterior e cria o novo;
- devolve a URI otpauth.

**Conclusão (T1)** roda numa transação READ COMMITTED, na ordem global da 017:
1. identidade travada (`FOR UPDATE`);
2. fator pendente lido sob o lock;
3. TOTP conferido no segredo **desse** fator;
4. `identity_mfa_replace` chamada com o id exato dele. A função trava sessão, fator antigo, fator novo, códigos e demais sessões, mede a hora e reconfere a prova. Na mesma chamada ela:
   - desabilita o fator antigo e confirma o novo;
   - invalida os códigos vivos e grava os novos;
   - levanta a restrição da sessão;
   - revoga as **demais** sessões;
   - grava o evento 11;
5. passo da confirmação gravado no fator novo (anti-replay: o mesmo código não serve depois para step-up nem para login);
6. no fim, depois de todas as esperas, sessão e janela do passo reconferidas. Fora delas, rollback integral.

**Resposta da conclusão:** `{"recoveryCodes": [...], "restricted": false}`. O conjunto novo é mostrado uma única vez. A sessão da requisição continua, já sem restrição. O aviso `NOTICE_MFA_REPLACED` ao titular sai depois do commit; se falhar, a troca não é desfeita (só um log, sem e-mail nem token).

**Recusas:**

| Situação | Resposta |
|---|---|
| sem prova de troca, sem fator pendente, código errado, replay ou passo vencido | `403` genérico, sem efeito. Código errado conta em R8m, como o step-up |
| sessão que caiu, inclusive durante as esperas | `401`, sem efeito. O cookie de sessão que não resolve passa pelo CSRF, como o logout sem sessão; não há nada a proteger |
| `recoveryCode` no modo sessão, ou `code` ausente | `400` |

### `restricted` explícito

- `/mfa/verify` → `{"csrfToken", "restricted"}`. É `true` quando a sessão nasceu de código de recuperação.
- `/mfa/enroll/confirm`, modo desafio → `{"recoveryCodes", "csrfToken", "restricted": false}`.
- **Novo** `GET /api/v1/me/session` → `{"restricted", "csrfToken"}`, para a interface retomar a jornada depois de recarregar.
  - Sessão de identidade; sem sessão válida: `401`.
  - Mesma chave da entrada geral: desligada, `404`.

### Defeito pré-existente corrigido

No modo desafio, a recusa de `identity_mfa_enroll_pending` virava **500** em vez do `401` contratado. O caso reproduzido foi uma identidade suspensa depois do login, com o desafio ainda vivo. A causa: o `NULL` da função caía em `query(UUID.class).single()`, que o trata como erro de tipo. Falhava fechado, sem efeito.
- Reproduzido por `recusaDaFuncaoNoModoDesafioE401` antes da correção (500).
- Corrigido para os dois modos (`IdentityAuthRepository.nullableUuid`).

## 2. Garantias e evidências

| Garantia | Teste (`IdentityFactorReplacementIT`, salvo indicação) |
|---|---|
| Troca completa: fatores, passo gravado no novo, códigos (antigos invalidados, só os novos vivos), demais sessões revogadas, a da requisição viva, evento 11, aviso | `caminhoAComStepUpSubstituiOFatorDeFormaCompleta` |
| Depois da troca: TOTP antigo recusado; código da confirmação não serve de novo; TOTP novo aceito; código de recuperação antigo recusado e novo aceito | idem |
| Sem step-up não inicia nem conclui | `sessaoComumSemStepUpNaoInicia` |
| Step-up vencido entre início e conclusão: 403 e rollback integral; novo step-up destrava | `stepUpVencidoAntesDaConclusaoRecusaSemEfeito` |
| T0 → T1: restrita (resposta e `me/session`), organizações e step-up recusados; depois da troca a mesma sessão é liberada, os restantes do conjunto antigo são invalidados e a marca é levantada | `caminhoBRecuperacaoDepoisTrocaLiberaASessaoRestrita` |
| Restrita fora dos 15 min: início e conclusão recusados, sem efeito, continua restrita | `sessaoRestritaForaDosQuinzeMinutosNaoTroca` |
| O TOTP tem de ser do pendente atual (antigo, pendente descartado e código qualquer recusados); código de recuperação não confirma fator (400) | `soOTotpDoFatorPendenteAtualConclui` |
| Isolamento entre identidades | `sessaoDeOutraIdentidadeNaoConcluiOPendenteAlheio` |
| Modo ambíguo: 400 nas duas rotas, sem tocar desafio nem fator | `credenciaisDosDoisModosSaoRecusadas` |
| CSRF da sessão e Origin exigidos no modo sessão | `modoSessaoExigeCsrfDaSessao` |
| Sessão encerrada: 401 nas rotas e em `me/session` | `sessaoEncerradaE401` |
| Depois da espera pelo lock da identidade: step-up vencido (403), sessão revogada (401) e pendente descartado (403), cada um com rollback integral | `autorizacaoSessaoEFatorReconferidosDepoisDaEspera` |
| Falha do aviso não desfaz a troca | `falhaDoAvisoNaoDesfazATroca` |
| Modo desafio inalterado, com `restricted` | `modoDesafioContinuaEInformaRestricted` |
| Recusa da função no modo desafio: 401 (antes, 500) | `recusaDaFuncaoNoModoDesafioE401` |
| Contrato publicado: rotas, códigos e esquemas de segurança dos dois modos; `GET /api/v1/me/session` | `SignupContractTest` (OpenAPI `api/openapi/nekkora-api-v1.yaml`) |

**Contraprova:** desligar a gravação do passo no fator novo faz `caminhoAComStepUpSubstituiOFatorDeFormaCompleta` falhar. O teste detecta a falta do anti-replay.

**E2E pela borda** (`api/e2e/run.sh`, nginx → Platform → PostgreSQL 001–018 + Redis), nova classe `IdentityCredentialsE2E`, 5/5:
- primeiro fator e organização criada;
- caminho A com a outra sessão revogada;
- caminho B, de restrita a liberada, com troca de sessão para a organização e `/o/{org}/me` 200;
- modo ambíguo 400 na borda;
- nenhum segredo nos logs dos containers.

`CrossProcessE2E` 6/6 e `LinkedAccountsE2E` 6/6 na mesma execução.

**Regressão local** (`./mvnw -B -ntp test`): core 21, ops 164, platform 325, portal 12; 522 testes, 0 falhas, 0 erros, 0 ignorados.

**Suíte de banco:** não executada. Nenhuma migration, função, privilégio ou invariante mudou. As funções usadas são as da 017 e da 018.

## 3. Organizações (contratos afetados por este PR)

Este PR muda o que a sessão restrita vira depois de T1. Verificações:

| Verificação | Onde |
|---|---|
| Restrita: listagem e troca 403 | `IdentityOrgSessionIT.sessaoRestritaARecuperacaoERecusadaNasDuasRotas` (já existente) |
| Restrita: criação 403 | `OrganizationCreationIT.sessaoRestritaRecebe403` (já existente) |
| Restrita: vinculação 403 | `AccountLinkIT.corpoInvalidoE400SessaoAusenteE401ERestritaE403` (já existente) |
| Depois de T1, a mesma sessão lista (200) | `caminhoBRecuperacaoDepoisTrocaLiberaASessaoRestrita` (novo) |
| Depois de T1, troca e acessa a organização | E2E jornada 3 (novo) |
| Nenhum administrador de organização reseta o MFA global | `AccountLinkIT.resetAdministrativoDeContaVinculadaERecusadoSemEfeito` e o banco (só `nekkora_identity_owner` muda fator de identidade). Este PR não acrescenta caminho de organização |

Os demais contratos de organização (listagem, criação, troca, suspensão, vínculo, step-up vinculado, sem repetição automática da criação) não mudam aqui. Os testes existentes continuam na regressão completa.

## 4. Timestamps desta jornada (critério do PR #9, §4)

| Escrita | Situação com relógio recuado | Participa de autorização? | Decisão |
|---|---|---|---|
| `enroll_pending`: pendente anterior `disabled_at` | `greatest` desde a 017 | não | sem mudança |
| `replace`: `disabled_at`, `confirmed_at`, `invalidated_at`, `revoked_at`, `recovery_cleared_at` | `greatest` (018) | `recovery_cleared_at` só por presença | sem mudança |
| `last_used_step` | inteiro de passo, não é relógio do banco | anti-replay | sem mudança |
| T0: `used_at` do código de recuperação (conclusão) | CHECK `used_at >= created_at` recusa sem efeito parcial | não | mantém falha fechada: o titular repete o login |
| `recovery_used_at`, `mfa_step_up_at` (abrem janela) | — | sim | **não ajustadas**, como exigido |

Nenhuma exposição nova foi demonstrada nesta jornada.

## 5. Limitações e pendências

- **Sessão restrita sem saída depois de 15 min:** a pessoa sai e repete a recuperação com outro código.
- **Perda de senha e de todos os códigos:** continua lacuna declarada na DC-17.13, para missão no molde da 016. O PR 4 (recuperação de senha por e-mail) preserva o MFA e não cobre a perda do fator.
- **Aviso `NOTICE_MFA_REPLACED`:** enviado pela porta atual, síncrona depois do commit e sem persistência. A fila persistente com retentativas é do PR 5. Até lá, o transporte de produção continua sendo um gate.
- **Regeneração independente de códigos e troca de senha:** PR 3.
