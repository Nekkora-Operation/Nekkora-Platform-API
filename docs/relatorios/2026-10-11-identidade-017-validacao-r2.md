# Migration 017, R2: validade da prova depois de esperas e RLS por identidade (alternativa A)

**Data:** 2026-10-11. **Estado:** candidata, R2 validada localmente. Java, ambiente preservado, Microsoft 365, commit e push continuam fora desta autorização; nenhum foi tocado.

**Base da revisão:** o pacote `pacote-017-r1-2026-10-11`, com 400 hashes conferidos pela responsável. Ele foi **preservado sem alteração**, assim como o pacote da R0.

| Rodada | Pasta em `db/tests/identidade-017/saidas/` |
|---|---|
| R0 | `2026-10-11T144726Z_nekkora-id017` (preservada) |
| R1 | `2026-10-11T151106Z_nekkora-id017` (preservada) |
| **R2** | **`2026-10-11T153518Z_nekkora-id017`**: do zero, PostgreSQL 16.14, modelo do migrador, commit `20ccb06` da `main`, sem commit desta etapa |

## 1. Intervalo de validade depois de esperas

**O achado.** A responsável apontou o caso em `identity_mfa_confirm_first`: o desafio era conferido antes da espera pelo lock do fator, e o consumo não reconferia `expires_at`. Revisei as outras funções sem presumir que conferir antes de uma espera basta, e o mesmo padrão aparecia em mais quatro.

| Função | Onde podia esperar depois da conferência | O que podia vencer |
|---|---|---|
| `identity_mfa_confirm_first` | lock do fator pendente | o desafio |
| `identity_mfa_enroll_pending` | lock do pendente anterior, que é descartado | o desafio, ou a sessão e a janela da DC-17.13 |
| `identity_mfa_replace` | locks do fator novo, dos códigos vivos e das demais sessões | a sessão e a janela da DC-17.13 |
| `identity_link_account` | locks das linhas do fechamento (sessões, desafios, tokens, códigos e fator da conta) | o desafio de vinculação, e a sessão com step-up |
| `identity_create_organization` | — (só identidade e sessão) | reconferido com a hora tomada depois dos locks, por coerência |

**A correção.** Nas cinco funções, todos os locks são adquiridos primeiro, inclusive os das linhas que a função vai alterar depois. Só então a hora (`v_t`) é medida, e a prova é reconferida com ela, antes de qualquer efeito: por `identity_session_proof` no caso de sessão, ou pelo próprio desafio. Depois da hora medida, nenhuma escrita espera lock. A partir daí, o tratamento muda por função (texto corrigido em 2026-10-11 a pedido da responsável; a versão do ZIP entregue dizia que o consumo condicionado valia nas cinco):

| Função | Prova | O que acontece depois dos locks e da hora medida |
|---|---|---|
| `identity_mfa_confirm_first` | desafio de cadastro | **a primeira escrita é o consumo do desafio**, condicionado a `expires_at > v_t` e com `ROW_COUNT = 1`. Sem esse consumo, nenhum efeito |
| `identity_link_account` | desafio de vinculação, e sessão com step-up | a sessão é reconferida; **a primeira escrita é o consumo do desafio**, condicionado a `expires_at > v_t` e à credencial vigente, com `ROW_COUNT = 1`. Sem esse consumo, nenhum efeito |
| `identity_mfa_enroll_pending` | desafio de cadastro **ou** sessão | **sem consumo**: a validade do desafio, ou a autorização da sessão pela DC-17.13, é reconferida com `v_t` **antes** dos efeitos. Se falhar, nenhum efeito |
| `identity_mfa_replace` | sessão (DC-17.13) | **sem consumo**: a autorização é reconferida com `v_t` antes dos efeitos |
| `identity_create_organization` | sessão com step-up | **sem consumo**: a autorização é reconferida antes dos efeitos |

**Reprodução: grupo V, com sincronismo explícito.**
- T1 trava a linha em que a função vai esperar **depois** de já ter conferido identidade, sessão ou desafio, e segura por 6 segundos.
- A prova vence em 4 segundos.
- T2 começa 1 segundo depois e fica esperando exatamente nesse ponto. O observador viu a espera em `pg_stat_activity` nos 4 cenários.

| Cenário | Espera de T2 | Prova que vence | Resultado | Estado conferido depois |
|---|---|---|---|---|
| V-1 | lock do **fator** em `identity_mfa_confirm_first` | desafio | `false` | fator pendente, nenhum confirmado, nenhum código, desafio nem consumido nem invalidado |
| V-2 | lock do pendente anterior em `identity_mfa_enroll_pending` | desafio | `NULL` | só o pendente original, intacto |
| V-3 | lock do fator novo em `identity_mfa_replace` | sessão | `false` | antigo confirmado, novo pendente, os mesmos 8 códigos vivos |
| V-4 | lock de uma sessão antiga, linha do fechamento, em `identity_link_account` | desafio de vinculação | `false` | conta não vinculada, desafio não consumido, sessão antiga ativa, fator da conta habilitado |

**Mutações.** M4 recoloca o corpo **da R1** de `identity_mfa_confirm_first`, que mede a hora mas não condiciona o consumo a ela; M5 recoloca o da vinculação, que mede a hora antes do fechamento. Com cada uma, V-1 e V-4 **reprovam**: a R1 confirmaria e vincularia depois da espera. Restauradas, o catálogo final é idêntico ao da instalação.

## 2. Alternativa A: RLS por identidade

| Condição | Implementação | Prova |
|---|---|---|
| sem contexto, nada; com contexto, só aquela identidade, inclusive nas filhas | RLS `ENABLE + FORCE` nas 10 tabelas; `identity_request_context` (`FOR ALL`, `USING` e `WITH CHECK`) para o `nekkora_platform` em 9; nada em `identity_users` | P-1 (sem contexto: zero linhas nas 7 tabelas com linhas; `UPDATE` não alcança nada; `INSERT` recusado). P-2 e P-3 (com o contexto de A: todas as linhas de A, nenhuma de B, em cada tabela filha) |
| escrita isolada | a mesma política, com `WITH CHECK` | P-3: `UPDATE` em linhas de B afeta 0 linhas em seis tabelas; `INSERT` para B em eventos, sessões, desafios e tokens de e-mail → `42501` |
| reuso da conexão | — | P-4: na mesma conexão, A → B → A sem enxergar a anterior; `SET LOCAL` dentro da transação não vaza depois do `COMMIT` |
| `identity_credentials` sem `SELECT` | só `INSERT (identity_id, password_hash)`, sem `SELECT` nem `UPDATE` | P-5: `SELECT` e `UPDATE` → `42501` mesmo com contexto; `INSERT` para outra identidade → `42501`; o hash sai só por `identity_resolve_login` |
| funções anteriores à autenticação | quatro funções de consulta por chave exata (§13.3 do desenho) | P-7: e-mail com prefixo, maiúsculas, curingas, vazio e nulo → zero linhas; tokens malformados, inexistentes, já revogados, consumidos ou invalidados → zero linhas; o **formato** de cada função conferido no catálogo |
| matriz com herança | `has_*_privilege`, que inclui herança e `PUBLIC`; mais RLS e políticas | grupo M: 348 linhas, iguais à matriz esperada revisada |

O grupo P soma 27 asserções positivas e 11 rejeições com SQLSTATE e mensagem conferidos.

**Grant que faltava, encontrado pelo teste.** Na primeira execução do grupo P, o dono não tinha `SELECT` em `identity_email_tokens`, que `identity_email_token_lookup` lê. Corrigi na migration. A R2 verde já roda com a correção.

## 3. Regressão

| Parte | R1 | R2 |
|---|---|---|
| Catálogo × 001–015: fora do previsto; remoções | 0; 2 | 0; 2 (817 acréscimos) |
| Invariantes 012/013/014/017 depois da retirada | verdes | verdes. A 017 ganhou a invariante 10 (RLS e políticas das tabelas de identidade) e a 3 passou a 14 funções |
| Grupos A, B, C, C′, D, G, H, I, J, K, L, N, R | verdes | verdes; os testes passaram a definir o contexto de identidade, sem mudar nenhuma expectativa |
| Esperas de lock observadas | 21 | 25 (+4 do grupo V) |
| Pontos com E-1..E-3 conferidos | 72 | 79 |
| Mutações | 3 de 3 | 5 de 5 |
| Base populada; 001–017 com a 016 e seus controles positivos | verdes | verdes |

## 4. Ressalvas mantidas

- **A RLS limita o acesso pelo contexto, mas não prova que a aplicação autenticou aquela pessoa.** O contexto é definido pela aplicação, como na RLS por organização.
- **As funções de consulta exata não impedem sondagens repetidas** por um papel de banco comprometido, que pode testar e-mails ou tokens um a um. Elas limitam a forma da resposta, não o número de perguntas.
- **Step-up:** o banco limita a janela, mas não comprova que o TOTP foi validado.
- **Capacidade de execução zero:** continua provisória nos testes e **não** é decisão de produto. A recomendação da responsável, ainda pendente, é cadastro imediato, execução dependente de ativação explícita e essa condição visível na web.

## 5. Consequências para a camada Java, que fica para depois

- Toda leitura e escrita nas tabelas de identidade exige `SET LOCAL nekkora.identity_id` na transação. Antes da autenticação, o `identity_id` sai das quatro funções de consulta.
- No cadastro, a aplicação gera o `identity_id` e o define como contexto antes do `INSERT`.
- Troca de senha e re-hash da identidade não têm caminho nesta migration. Ficam para uma função própria.
