# Troca de senha e regeneração dos códigos da identidade (missão 002, PR 3)

**Base:** o PR #10 (substituição do MFA), do qual este depende pelo serviço `IdentityCredentialService` e pelo modo sessão. **Migration nova:** `019_identidade_regeneracao_codigos.sql`. 016, 017 e 018 não são editadas.

## 1. Comportamento entregue

### Troca de senha: `POST /api/v1/auth/password`

Requisitos:
- sessão de identidade **comum** (não restrita) e CSRF dela;
- step-up de até 5 min (`POST /api/v1/auth/step-up`, só TOTP).

Corpo: `{"currentPassword", "newPassword"}`. As duas senhas são zeradas depois do uso.

Ordem:
1. R12.
2. Sessão: sem sessão válida, 401. Sessão restrita: 403, antes de qualquer Argon2.
3. Política da senha nova, a mesma da Platform: 400 fora dela.
4. R8.
5. Hash vigente lido por `identity_resolve_login`. A Platform continua sem `SELECT` em `identity_credentials`.
6. Argon2 da senha atual, **fora** de transação. Errada: 403, conta em R8 pela identidade global.
7. Hash novo.
8. Uma transação com `identity_password_change` (017). A função:
   - reconfere a prova `step_up` depois dos locks;
   - só troca se o hash vigente **ainda** for o conferido (troca concorrente: 403, sem sobrescrever);
   - invalida os desafios pendentes;
   - revoga as **demais** sessões; a da requisição continua;
   - grava o evento 12.

Resposta 204. O aviso `NOTICE_PASSWORD_CHANGED` ao titular sai depois do commit, sem token e sem organização. A falha do aviso não desfaz a troca.

**MFA preservado:** a entrada seguinte com a senha nova exige o fator ou um código de recuperação.

**Re-hash no login:** inalterado. `IdentityLoginIT.credencialComParametrosAntigosERehashadaNaConclusao` continua na regressão.

### Regeneração independente dos códigos: `POST /api/v1/auth/mfa/recovery-codes`

Mesma exigência: sessão comum com step-up recente. O step-up da identidade só aceita TOTP, então isso é "step-up recente por TOTP".

`identity_recovery_codes_regenerate` (019) roda numa transação:
1. trava identidade, sessão, fator confirmado e códigos vivos;
2. reconfere a prova `step_up` no instante medido depois dos locks;
3. invalida o conjunto vivo e grava o novo para o fator vigente, de forma atômica;
4. grava o evento 15 (`RECOVERY_CODES_REGENERATED`).

Resposta: `{"recoveryCodes": [...]}`. O conjunto é mostrado uma única vez; no banco ficam só os hashes. Sessão restrita: 403. O aviso `NOTICE_RECOVERY_CODES_REGENERATED` sai depois do commit.

### Migration 019

- **Evento 15:** a CHECK `identity_events_event_type_check` passa de `BETWEEN 1 AND 14` para `BETWEEN 1 AND 15`, com o mesmo nome. O comentário da coluna ganha o código novo.
- **Função nova** `identity_recovery_codes_regenerate(text, text[])`: SECURITY DEFINER, dona `nekkora_identity_owner`, `search_path` fixo, EXECUTE só para `nekkora_platform`.
  - Precisa ser função do dono: a trigger da 017 só deixa o dono criar e invalidar códigos de identidade.
- **Pré-condição:** 018 aplicada, função ainda inexistente e a CHECK exatamente a da 017.
- **Invariantes (§4):**
  - atributos e EXECUTE da função nova;
  - lista exata das funções de identidade executáveis pela Platform, que substitui a invariante 3 da 017;
  - CHECK nova;
  - a Platform continua sem escrita nos códigos e sem membership no dono.
- **Elevação:** precisa da janela de SUPERUSER, como 017 e 018. O dono não tem membros, e `ALTER FUNCTION ... OWNER TO` exige poder assumir o papel. `db/bootstrap/aplicar_como_migrador.sh` passou a incluir a 019 no padrão. `ops/homologacao/implantacao/elevacao.txt` não é alterado (ação de ambiente, §5).
- **Encerramento dos códigos:** usa `greatest(instante, created_at)`, o ajuste de encerramento da 018. O valor gravado é o limite inferior aceito pela CHECK, não o instante exato.

## 2. Evidências

**Testes de integração** (`IdentityPasswordAndCodesIT`, 9, papel real `nekkora_platform`):

| Garantia | Teste |
|---|---|
| Troca de senha: hash novo, evento 12, outra sessão revogada, a da requisição viva, desafio pendente invalidado (o CSRF dele não resolve mais: 403, nenhuma sessão nasce), aviso sem token; senha antiga 401; senha nova abre `mfa_required` | `trocaDeSenhaComStepUpRevogaAsDemaisEPreservaOMfa` |
| Sem step-up, senha atual errada, política e corpo incompleto: 403/400 sem efeito | `semStepUpSenhaAtualErradaOuSenhaNovaForaDaPoliticaNaoTrocam` |
| Sessão restrita: as duas operações 403, sem efeito | `sessaoRestritaNaoTrocaSenhaNemRegeneraCodigos` |
| Senha trocada por outro caminho durante a espera pelo lock: 403, não sobrescrita, sem evento nem revogação | `senhaAlteradaDuranteAEsperaNaoESobrescrita` |
| Step-up vencido durante a espera: as duas operações 403, sem efeito | `stepUpVencidoDuranteAEsperaRecusaAsDuasOperacoes` |
| Regeneração: só o conjunto novo vivo, no fator vigente; o anterior invalidado; evento 15; aviso; código antigo recusado na entrada, código novo aceito (sessão restrita, como todo código) | `regeneracaoSubstituiOConjuntoInteiro` |
| Regeneração sem step-up, com CSRF de outro contexto ou sem sessão: 403/403/401, sem efeito | `regeneracaoSemStepUpOuSemSessaoNaoMudaNada` |
| Regenerações concorrentes: serializam na identidade; no fim, exatamente um conjunto vivo, e ele é um dos devolvidos | `regeneracoesConcorrentesDeixamUmSoConjuntoVivo` |
| Falha do aviso não desfaz troca nem regeneração | `falhaDoAvisoNaoDesfazAsAlteracoes` |

**Contrato publicado** (`api/openapi/nekkora-api-v1.yaml`): rotas, códigos de resposta e esquemas de segurança. Conferido por `SignupContractTest`.

**Suíte de banco** `db/tests/codigos-019`:

| Caso | Resultado |
|---|---|
| A, instalação limpa 001–019 | sete janelas com retirada `0\|0\|0`; invariantes 012/013/014/016/017 (adaptadas onde indicado) e 019 depois da retirada; marcador `versao=019` |
| B1, 019 sem SUPERUSER | recusada com `must be able to SET ROLE "nekkora_identity_owner"`; nada muda |
| B2, base populada 018 → 019 (80 tabelas) | dados idênticos; catálogo com exatamente 5 linhas previstas (CHECK antiga e nova, função, dois EXECUTE); igual à instalação limpa; invariantes depois da retirada |
| B3, reaplicação | recusada pela pré-condição; nada muda |

**E2E pela borda** (`api/e2e/run.sh`, PostgreSQL 001–019):
- `IdentityCredentialsE2E` 6/6. A jornada nova cobre regeneração com step-up, troca de senha, senha antiga recusada, MFA preservado e código regenerado aceito, sem segredos nos logs.
- `CrossProcessE2E` 6/6 e `LinkedAccountsE2E` 6/6.

**Regressão local** (`./mvnw -B -ntp test`): core 21, ops 164, platform 334, portal 12; 531 testes, 0 falhas, 0 erros, 0 ignorados. CI: no PR.

## 3. Timestamps desta jornada (critério do PR #9, §4)

| Escrita | Situação com relógio recuado | Decisão |
|---|---|---|
| `identity_password_change`: `password_changed_at = v_t` | sem CHECK de ordem contra outra coluna | sem mudança |
| `identity_password_change`: desafios `invalidated_at = v_t` | `identity_auth_challenges` **não** tem CHECK de ordem entre `invalidated_at` e `created_at` (só prazo, exclusão mútua com consumo e limpeza dos hashes) | sem mudança |
| `identity_password_change`: sessões `revoked_at` | `greatest` desde a 017 | sem mudança |
| `identity_credentials.password_changed_at` | sem CHECK de ordem | sem mudança |
| 019: códigos `invalidated_at` | `greatest`, pela CHECK `invalidated_at >= created_at` dos códigos | — |
| `mfa_step_up_at` (abre janela) | — | **não ajustada** |

Nenhuma exposição nova foi demonstrada nesta jornada.

## 4. Organizações

Este PR não muda contratos de organização. A troca de senha revoga as demais **sessões de identidade**. As sessões de organização derivadas caem na requisição seguinte, pela regra da DC-17.4 que já existia (`IdentityOrgSessionIT.logoutDaIdentidadeDerrubaASessaoDeOrganizacaoDerivadaSemEscritaNaOrganizacao`).

## 5. Limitações e ação de ambiente

- **`elevacao.txt` de homologação:** a 019 se junta às linhas pendentes de 016–018 do PR #9. Sha256 dos bytes LF desta versão: no comentário do PR.
- **Avisos:** usam a porta síncrona atual. A fila persistente é do PR 5.
- **Contas antigas por organização:** inalteradas, como exige a missão.
