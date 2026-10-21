# Instantes terminais com o relógio do banco recuado — migration 018 e caminhos Java

Missão PLATFORM-IDENTIDADE-002, frente 1. Base: `main` em `7548957` (merge do PR #7, head `77cd2db`).

A causa do incidente original (23514 na corrida MFA × reset, `9f98f71`) **continua não confirmada**; ver `2026-10-20-fechamento-23514-mfa-reset.md`. Este trabalho fecha as exposições **reproduzíveis** do mesmo mecanismo, sem atribuir o incidente a elas.

## 1. Problema e comportamento esperado

`clock_timestamp()` é o relógio de parede do servidor e pode recuar entre duas transações. Se uma linha foi criada logo antes do recuo, o `created_at` dela fica **à frente** do instante medido pelo ato que a encerra. A CHECK de ordem (`encerramento >= criação`) então recusa o ato inteiro com 23514, e a ação de segurança é desfeita.

Comportamento esperado:
- a ação de segurança conclui com todos os efeitos;
- as restrições do banco continuam valendo;
- os controles negativos continuam recusados.

## 2. Inventário

Foram levantadas todas as escritas de instante em colunas com CHECK de ordem temporal, em Java (`src/main`) e nas funções das migrations 001–017. A classificação:
- **encerramento:** revoga, invalida, desabilita ou libera;
- **conclusão:** confirma, usa ou consome;
- **prazo de política.**

| Escrita | Restrição | Classe | Situação |
|---|---|---|---|
| `SessionRepository` (revogação por token, por conta, por limite) | `user_sessions` `revoked_at >= created_at` | encerramento | já corrigida no PR #7 |
| `MfaRepository.disableFactor`: códigos `invalidated_at`, fator `disabled_at` (reset administrativo) | 014 | encerramento | **corrigida** |
| `AuthService` (pedido de recuperação de senha): `password_reset_tokens.revoked_at` | 002 | encerramento | **corrigida** |
| `IdentityRepository.revokeLiveTokens` (reenvio da confirmação): `identity_email_tokens.revoked_at` | 017 | encerramento | **corrigida** |
| `AdminService.revokeGrant`: `client_workspace_grants.revoked_at` | 003 | encerramento | **corrigida** |
| `ops_mfa_execute` (016): códigos, fator, sessões | 014 / 002 | encerramento | **corrigida na 018** |
| `ops_operator_revoke`, `ops_operator_disable` (016): `ops_operator_permissions.revoked_at >= granted_at` | 016 | encerramento | **corrigida na 018** |
| `identity_mfa_replace` (017): fator antigo `disabled_at`, fator novo `confirmed_at`, `recovery_cleared_at >= recovery_used_at` | 017 | encerramento (saída da sessão restrita) | **corrigida na 018** |
| `identity_mfa_replace`, `identity_password_change`, `identity_login_complete` (recuperação), `identity_link_account_impl`, `identity_mfa_enroll_pending`: códigos e sessões | 017 | encerramento | já usavam `greatest` na 017 |
| `IdentityAuthRepository` (logout da identidade) | 017 | encerramento | já usava `greatest` |
| `MfaRepository.confirm` (primeiro fator, organização); `identity_mfa_confirm_first` | `confirmed_at >= created_at` | conclusão | **não alterada** (§6) |
| `MfaRepository.consumeRecoveryCode`; `identity_login_complete` (uso do código) | `used_at >= created_at` | conclusão | **não alterada** (§6) |
| `AuthService` (confirmação de recuperação e de e-mail); `IdentityRepository` (confirmação do cadastro): `consumed_at` | 002 / 017 | conclusão | **não alterada** (§6) |
| desafios (`auth_challenges`, `identity_auth_challenges`), vinculação (`identity_link_challenges`), `password_changed_at`, certificados e operadores da 016 | sem CHECK de ordem nessas colunas | — | não expostas |
| `last_seen_at` (`SessionRepository.validate`) | `last_seen_at >= created_at` | — | não exposta: a escrita só ocorre com `last_seen_at <= instante - 1 min` |
| solicitação da 016 (`verified_at`, `preapproved_at`, `notice_confirmed_at`, `final_approved_at`, tentativas `accepted_at + 24 h <= approved_at`) | 016 | prazo de política | **não alterada** (§6) |

## 3. Reprodução dirigida, antes da correção

Método:
- Com o superusuário e as triggers desligadas **só na conexão do teste**, o `created_at` (ou a marca de recuperação) de **uma** classe de linha é colocado 1 minuto à frente do relógio.
- As CHECKs continuam valendo.
- Em seguida o ato real é executado:
  - pela API, nos caminhos HTTP;
  - pelas funções, com os papéis reais, nos caminhos sem rota.

Este é o estado que um passo para trás do relógio produz entre duas transações. O relógio do container não pode voltar dentro do teste.

| Cenário | Antes | Restrição | Depois |
|---|---|---|---|
| reset administrativo, fator à frente | 500 | `user_mfa_factors_check1` | 204, efeitos completos |
| reset administrativo, códigos à frente | 500 | `user_mfa_recovery_codes_check2` | 204, efeitos completos |
| revogação de concessão externa | 500 | `client_workspace_grants_check6` | 204 |
| pedido de recuperação de senha, token anterior à frente | **202 sem token novo, sem e-mail, token anterior vivo** | `password_reset_tokens_check3` | 202, anterior revogado, novo enviado |
| reenvio da confirmação do cadastro | 500 | `identity_email_tokens_check3` | 202, anterior revogado, novo confirma |
| troca de fator, fator antigo à frente | 23514 | `identity_mfa_factors_check1` | `true`, efeitos completos |
| troca de fator, fator novo à frente | 23514 | `identity_mfa_factors_check` | `true` |
| troca na sessão restrita, marca de recuperação à frente | 23514 | `identity_sessions_check3` | `true`, sessão liberada |
| execução da recuperação emergencial (códigos / fator / sessões à frente) | FALHA_CONFIRMADA, 23514 | `user_mfa_recovery_codes_check2` / `user_mfa_factors_check1` / `user_sessions_check2` | OK, EXECUTADA, `0\|0\|0\|0` |
| retirada de habilitação de operador | 23514 | `ops_operator_permissions_check2` | OK |
| desativação de operador | 23514 | `ops_operator_permissions_check2` | OK, nenhuma habilitação ativa |

Controles negativos, verdes antes **e** depois, com as mesmas linhas à frente do relógio:
- troca de fator sem step-up recente: `false`, nada muda;
- execução de solicitação não aprovada: `ESTADO_INVALIDO` nas três variantes, nada muda;
- retirada de habilitação inexistente: `NAO_HABILITADO`.

Testes: `RecuoDoRelogioOrgIT` (4), `RecuoDoRelogioIdentidadeIT` (5), `RecuoDoRelogioIT` (nekkora-ops, 9).

### Falha mascarada

O pedido de recuperação de senha **respondia 202 sem efeito**. `AuthService` tratava qualquer 23514 como a recusa de conta vinculada da 017, que de fato responde 202 sem enviar. O mesmo tratamento genérico existia em mais quatro pontos:
- outros dois caminhos do `AuthService`: emissão do desafio de login e troca de senha;
- `AccountLinkService`;
- `LinkedSessionIssuer`.

Correção: `DbErrors.raisedRefusal`. Só é recusa de regra o 23514 **sem nome de restrição**, isto é, o levantado por trigger ou função com `RAISE ... USING ERRCODE = '23514'`. Uma CHECK violada traz o nome da restrição e passa a propagar como falha observável.

Provas:
- `DbErrorsIT`, contra mensagens reais do servidor: RAISE → recusa; CHECK → não;
- os testes de conta vinculada continuam verdes: `LinkedAccountLocalEntryIT`, `AccountLinkIT`, `IdentityOrgSessionIT` e `IdentityOrgSessionLimitsIT`.

## 4. Correção

**Java:** `greatest(clock_timestamp(), created_at)` nas quatro escritas de encerramento da tabela, o mesmo padrão do PR #7. Também a discriminação do 23514 (§3).

**Migration 018** (`db/migrations/018_instantes_terminais_relogio.sql`). A 016 e a 017 **não** foram editadas.

Funções substituídas, com corpos copiados das originais e alteradas só nas escritas de instante terminal:
- `ops_mfa_execute`, `ops_operator_revoke`, `ops_operator_disable` (016);
- `identity_mfa_replace` (017).

O que fica preservado:
- os mesmos locks, na mesma ordem, e a hora medida depois deles;
- as mesmas reconferências, recusas e auditoria;
- a mesma configuração (`SET`), dono, ACL e `SECURITY DEFINER`, conferidos pela própria migration.

`executed_at`, `disabled_at` do operador, revogação de certificados e registros continuam com o instante medido.

**Privilégio novo:** `SELECT (created_at)` em `user_mfa_factors` e `user_mfa_recovery_codes` para `nekkora_ops_mfa_owner`.
- A 016 concede a esse papel leitura por coluna e não incluía `created_at` nessas duas tabelas; em `user_sessions` já incluía.
- Sem a concessão, o `greatest` falhou com 42501 e a execução foi desfeita (medido).
- O papel é NOLOGIN e dono das funções. Nenhuma escrita nova, e nenhum papel de LOGIN muda.
- A matriz da 016 (`2026-09-27-mfa-emergencia-matriz-016.md`) não foi reescrita; esta seção registra o acréscimo.

**Pré-condição:** a 018 só aplica se os quatro corpos ainda forem, por inteiro, os da 016/017. A comparação é pelo SHA-256 do corpo completo (`prosrc`), fixado na migration; os valores são os do texto das próprias 016 e 017 e foram medidos também numa instalação 001–017. Qualquer diferença (autorização, locks, auditoria, recusas, comentário ou espaço) recusa a 018 antes de qualquer escrita, nomeando a função divergente. Uma reaplicação é recusada pelo mesmo motivo.

Na primeira versão do PR, a pré-condição procurava só um trecho de cada função. Uma alteração em autorização, lock ou auditoria que mantivesse o trecho passaria pela conferência e seria sobrescrita pelo `CREATE OR REPLACE`. Achado da revisão; corrigido e coberto pelo caso B4 da suíte.

**Elevação:**
- As funções pertencem a `nekkora_ops_mfa_owner` e `nekkora_identity_owner`, papéis sem membership de ninguém. Por isso a 018 precisa da janela de SUPERUSER do migrador.
- A 018 entrou no padrão `NEKKORA_ELEVAR` de `db/bootstrap/aplicar_como_migrador.sh`, usado pelas bases de teste e pelo E2E.
- Sem a janela, a recusa medida é de privilégio (`permission denied to set parameter "nekkora.fn_organization_id"`), e nada muda.
- `ops/homologacao/implantacao/elevacao.txt` **não** foi alterado: a 017 também ainda não está lá. Incluir a 017 e a 018, com sha256 e medição, é decisão da etapa de ambiente (frente 5).

### Sentido do instante ajustado

Com o relógio recuado, o valor gravado é o limite inferior aceito pela CHECK (`created_at`, `granted_at` ou `recovery_used_at`). **Não é o instante exato do ato.**

O encerramento vale:
- as leituras usam presença (`IS NULL` / `IS NOT NULL`);
- nenhuma consulta compara esses instantes com o relógio (conferido por busca no código e nas migrations);
- nenhuma janela de autenticação depende deles.

As marcas que **abrem** janela (`mfa_verified_at`, `recovery_used_at`, `mfa_step_up_at`) não são tocadas, e a 017 continua recusando-as no futuro.

## 5. Evidências

| Prova | Resultado |
|---|---|
| reprodução antes da correção | 13 cenários positivos falham como na tabela do §3; 5 controles negativos verdes |
| depois da correção, dirigidos | `RecuoDoRelogio*` 18/18; `DbErrorsIT` 3/3; `FluxoOperacionalIT`, `MfaResetRaceIT`, `LinkedAccountLocalEntryIT`, `AccountLinkIT`, `IdentityOrgSessionIT`, `IdentityOrgSessionLimitsIT`, `IdentityLoginIT`, `SignupIT`, `ClientGrantRevokeRouteIT`: verdes |
| módulo nekkora-ops completo | todos os `*IT` verdes (inclui inventário de locks, isolamento, mutações e separação de funções) |
| suíte de banco `db/tests/relogio-018` | A: 001–018 limpa, seis janelas com retirada `0\|0\|0`, invariantes 012/013/014/016/017/018 depois da retirada, marcador `versao=018`. B1: sem SUPERUSER, recusada, nada muda. B2: base populada 017 → 018 (80 tabelas de `public` e `ops`), diff de dados vazio, catálogo com exatamente 10 linhas previstas (4 corpos, 2 SELECT), igual à instalação limpa, vivos inalterados. B3: reaplicação recusada pela comparação integral. B4: cada uma das quatro funções alterada fora das expressões da 018 (autorização em `ops_mfa_execute`, step-up em `identity_mfa_replace`, lock em `ops_operator_revoke`, auditoria em `ops_operator_disable`), aceita pela pré-condição anterior: 018 recusada nomeando a função; dados, funções, privilégios, marcador e migrador inalterados |
| regressão completa local (`./mvnw -B -ntp test`, o comando do CI) | core 21, ops 164, platform 311, portal 12 = **508/0/0/0** (487 da base + 21 novos) |
| E2E (`api/e2e/run.sh`, agora 001–018) | 018 aplicada pelo migrador com janela retirada `0\|0\|0`, marcador `versao=018`; CrossProcessE2E 6/6, LinkedAccountsE2E 6/6 |
| CI | registrado no PR |

## 6. Limitações e pendências

**Escritas de conclusão** (primeiro fator, uso de código, consumo de token) continuam sem ajuste. Com o relógio recuado, a CHECK recusa o ato sem efeito parcial: falha fechada, sem perda de segurança, e o ato pode ser repetido quando o relógio alcança o `created_at`. A falha agora é observável, não mascarada (§3). Ficam fora deste PR por decisão da revisão. Ajustá-las **não** é uma escolha genérica entre segurança e disponibilidade: o efeito depende de onde o timestamp participa da autorização em cada caminho (por exemplo, se alguma janela de autenticação ou prazo é calculado a partir dele). Cada caminho exige avaliação própria antes de qualquer mudança; não há ajuste indiscriminado de timestamps.

**Prazos de política da 016** não são ajustados: o ajuste dispensaria a espera exigida.

**A 016 continua não habilitada em produção**, pelos motivos do cabeçalho dela.

**Atribuição do incidente original:** inconclusiva, como no relatório de 2026-10-20.

**Relógio do servidor:** a validação e a observabilidade em produção ficam na frente 5.
