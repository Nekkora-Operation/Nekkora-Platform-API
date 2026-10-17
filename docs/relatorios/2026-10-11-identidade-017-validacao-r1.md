# Migration 017, R1: correções da revisão e nova rodada da suíte

**Data:** 2026-10-11. **Estado:** candidata, R1 validada localmente. Java, ambiente preservado, Microsoft 365, commit e push continuam fora; nenhum foi tocado.

**Base da revisão:** o pacote `pacote-017-2026-10-11`, com 210 hashes conferidos pela responsável. Ele foi **preservado sem alteração**, e a rodada da R0 continua em `db/tests/identidade-017/saidas/2026-10-11T144726Z_nekkora-id017/`. O relatório da R0 é o `2026-10-11-identidade-017-validacao.md`.

**Rodada da R1:** `db/tests/identidade-017/saidas/2026-10-11T151106Z_nekkora-id017/`. Feita do zero, com PostgreSQL 16.14 e o modelo do migrador, sobre a mesma base: commit `20ccb06` da `main`, sem commit desta etapa.

## 1. Correções

| # | Falha da R0 (revisão) | Correção na R1 | Prova na R1 |
|---|---|---|---|
| 1a | `user_credentials.user_id` mutável | `user_id` e `created_at` imutáveis para toda conta (trigger `prevent_user_credential_reassignment`) | N-8: mover a credencial para a conta vinculada sem credencial → `23514`. N-9: mover para outra conta não vinculada → `23514` |
| 1b | Tokens sem imutabilidade; a exceção terminal deixava mudar dono, hash ou prazo | Só `consumed_at` e `revoked_at` mudam, de nulo para preenchido, uma vez; o resto é imutável; token terminal não muda (trigger `prevent_local_token_change`) | N-1 a N-5: revogação ou consumo junto com `user_id`, `token_hash`, `expires_at` ou `target_email` → `23514`. N-6: troca de dono isolada → `23514`. N-7: token revogado não volta nem é consumido |
| 1c | A exceção terminal liberava a linha inteira | Libera só as colunas terminais que estavam nulas e a limpeza prevista, esta só para nulo. **Outra coluna junto é recusada**, em qualquer conta | N-10 (`attempts` junto), N-11 (`credential_hash` não nulo), N-12 (`key_id`), N-13 (`secret_ciphertext`) → `23514` numa conta **não vinculada** |
| 2 | Desafio validado antes da espera pelo lock, sem reconferir | `identity_challenge_resolve`: lock da identidade, depois reconferência com `clock_timestamp()` e lock da linha do desafio. `identity_mfa_confirm_first` consome o desafio na mesma transação | R-1 a R-4, abaixo; mutação M3 |

## 2. Provas específicas

**Grupo R: sincronismo explícito.** T1 trava a identidade. T2 começa um segundo depois, lê o desafio ainda válido e **fica esperando o lock**, o que o observador confirma em `pg_stat_activity` nos 4 cenários. Antes de liberar, o desafio é invalidado por T1 (R-1, R-3) ou vence, com validade de 4 s e T1 segurando por 6 s (R-2, R-4).

| Cenário | Função | Resultado de T2 | Estado final conferido |
|---|---|---|---|
| R-1 | `identity_mfa_confirm_first` | `false` | fator continua pendente, nenhum confirmado, nenhum código |
| R-2 | `identity_mfa_confirm_first` | `false` | o mesmo, e o desafio não foi consumido |
| R-3 | `identity_mfa_enroll_pending` | `NULL` | nenhum fator criado |
| R-4 | `identity_mfa_enroll_pending` | `NULL` | nenhum fator criado |

**Mutação M3.** A suíte aplica o corpo **da R0** de `identity_mfa_confirm_first`, arquivado em `sql/mutacoes/r0_identity_mfa_confirm_first.sql`, e repete R-2. A R0 confirma o fator depois da espera e a suíte **reprova**: "R-2 sob M3: desafio expirado durante a espera não autoriza". A transação de T2 é desfeita, então nada fica confirmado. Depois a função é restaurada, e o catálogo final é idêntico ao da instalação.

**Controles positivos.**

| Controle | Resultado |
|---|---|
| Escritas no formato exato do Java atual (`AuthService`, `MfaRepository`, `AccountRepository`) em conta não vinculada: revogar e consumir token de recuperação; consumir token de verificação; tentativas do desafio; consumir e invalidar desafio com limpeza dos hashes; trocar o segredo do fator pendente; confirmar fator com passo; consumir passo; usar e invalidar código; trocar senha | todas aceitas, com o efeito conferido (N-P) |
| Vinculação de conta com estado vivo (desafio, tokens de recuperação e de verificação, código, sessão pela porta antiga) | aceita, com o fechamento completo e E-1..E-3 em zero (N-V); também a fixture e o grupo C |
| Com a 016 instalada: o bloco de efeitos do reset operacional, **extraído do arquivo da 016** (4 `UPDATE`s), executado com o papel `nekkora_ops_mfa_owner` sobre conta com desafio, códigos, fator e sessão vivos | aceito; desafio, códigos, fator e sessão terminaram em estado terminal; E em zero |

## 3. Regressão

| Parte | R0 | R1 |
|---|---|---|
| Catálogo × 001–015: acréscimos fora do previsto; remoções | 0; 2 previstas | 0; 2 previstas. São 780 acréscimos, 12 a mais que na R0, todos conferidos linha a linha: 3 triggers, 3 funções (2 de trigger e `identity_challenge_resolve`), as 3 ACLs dessas funções e 3 privilégios de coluna do dono em `identity_auth_challenges`. Os corpos alterados de funções que já existiam na R0 aparecem com outro md5 no catálogo |
| Invariantes 012/013/014/017 depois da retirada | verdes | verdes (a invariante 5 da 017 passou a contar 12 triggers) |
| Grupos A, B, C, C′, D, E, G, H, I, J, K, L | verdes | verdes, sem mudança de expectativa |
| Esperas de lock observadas | 17 | 21 (+4 do grupo R) |
| Pontos com E-1..E-3 conferidos | 64 | 72 |
| Grupo M | 259 linhas | 263 linhas. Diferença revisada: o dono ganhou `UPDATE (consumed_at, credential_hash, pending_password_hash)` em `identity_auth_challenges`, para travar e consumir o desafio, e a função interna `identity_challenge_resolve`. **A Platform não mudou** |
| Mutações detectadas | 2 de 2 | 3 de 3 |
| Base populada 015 → 017 | dados idênticos, 60 sessões preservadas | idem |
| 001–017 com a 016 | verde | verde, com os controles positivos |

## 4. O que continua em aberto

1. **Leitura global das tabelas de identidade: não aprovada.** O desenho (§13.2) traz a justificativa tabela por tabela, a alternativa mínima recomendada (A: RLS por identidade no modelo da 012 e funções só para as leituras de antes da identificação) e a alternativa B. Os grants **não foram alterados** na R1. A implementação vem numa R2, depois da sua decisão.
2. **Autenticidade do step-up**, registrada (§13.1): o banco limita a janela, mas não comprova que o TOTP foi validado.
3. **Capacidade de execução zero** continua sendo configuração provisória dos testes; a decisão de produto é sua.
4. **Regra de "caminho único" da 012**: a mudança está registrada no cabeçalho da `017` e no desenho, sem alterar a 012.
5. **Pendências de antes:** fixtures Java, `elevacao.txt` de homologação e a ordem 016 × 017.

## 5. Efeito das novas imutabilidades nas contas atuais

As triggers de tokens e de `user_credentials.user_id` valem para **toda** conta, não só para as vinculadas, como a revisão pediu. Conferi todas as escritas do Java atual nessas tabelas e todas cabem na regra; o controle N-P exercita cada uma. Uma escrita futura que tente mudar dono, hash ou prazo de token passa a ser recusada pelo banco.
