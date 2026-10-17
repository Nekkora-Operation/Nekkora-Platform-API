# Migration 017, R3: conclusão do login, troca de senha e atualização 016 → 017

**Data:** 2026-10-11. **Estado:** candidata, R3 validada localmente. Java, implantação, ambiente preservado, Microsoft 365, commit e push continuam fora; nenhum foi tocado.

**Errata (2026-10-11, a pedido da responsável; ZIP e registros da rodada preservados).** Onde este relatório e o resumo da rodada dizem que os avisos da 016 foram "emitidos e entregues pela Platform", leia-se: **fluxo de banco dos avisos executado sob o papel `nekkora_platform`** — `ops_notice_pending`, `ops_notice_issue`, `ops_notice_claim` e `ops_notice_confirm` — **com o resultado de entrega simulado**, registrado por `ops_notice_delivery_result` com o estado 4 (ACEITA). A parte D **não** prova a execução do remetente Java, o transporte nem o recebimento de e-mail. Esses são da camada Java e da Missão 2.

**Base da revisão:** o contrato da integração Java (`2026-10-11-identidade-017-contrato-integracao-java.md`), cuja direção foi aprovada pela responsável com três ajustes, todos incorporados no contrato e implementados aqui. Os pacotes R0, R1 e R2 estão preservados sem alteração.

| Rodada | Pasta em `db/tests/identidade-017/saidas/` |
|---|---|
| R0, R1, R2 | `2026-10-11T144726Z`, `…151106Z`, `…153518Z` (preservadas) |
| **R3** | **`2026-10-11T182625Z_nekkora-id017`**: do zero, PostgreSQL 16.14, modelo do migrador, commit `20ccb06`, sem commit desta etapa |

## 1. Os três ajustes da responsável

| Ajuste | Onde | Prova |
|---|---|---|
| **Contexto transacional.** A entrada geral volta a resolver a identidade **depois** do Argon2 e confere estado e credencial antes de criar o desafio. A sequência é guarda → resolução → contexto → acesso | contrato, §1.1–§1.3 | é contrato da camada Java. No banco, o login só cria sessão se a credencial do desafio for a vigente (S-5) |
| **Re-hash perdido ≠ autenticação obsoleta.** Finalidade conferida; credencial vigente **sempre**; resultado `SENHA_OBSOLETA` sem sessão; sem equivalência presumida; consumo, fator ou código e sessão numa só operação, com rollback integral | contrato §2.1; `identity_login_complete`, `identity_mfa_confirm_first` | S-1 a S-8, V-5, K-6, M6 |
| **Troca de senha fecha os desafios.** Invalida os desafios pendentes, com limpeza dos hashes, e revoga as demais sessões, com os locks antes da hora | contrato §2.2; `identity_password_change` | S-9, L, V-6, M7 |

## 2. Provas da R3

**Grupo S** (17 asserções positivas e 4 rejeições conferidas):

| Cenário | Resultado |
|---|---|
| S-1 | login TOTP: desafio consumido com hashes limpos, passo avançado, sessão comum criada |
| S-2 | passo repetido → `FATOR_RECUSADO`, sem efeito |
| S-3 | cada função consome só a sua finalidade; o desafio de cadastro continua vivo e conclui depois |
| S-4 | re-hash aplicado com a credencial vigente |
| S-5 | dois desafios validados contra o mesmo hash: A conclui com re-hash. **B (re-hash concorrente) e C (sem re-hash pendente) recebem `SENHA_OBSOLETA`**: invalidados, hashes limpos, nenhuma sessão |
| S-6 | recuperação: sessão restrita com o código apontando para ela; código já usado é recusado |
| S-8 | erro na criação da sessão (hash repetido): **rollback integral**. O desafio continua vivo, o passo e a credencial ficam intactos |
| S-9 | troca de senha: credencial nova, desafio pendente invalidado e incapaz de concluir, demais sessões revogadas, a sessão da troca preservada |
| S-11 | a Platform não cria sessão, não consome desafio e não usa código: `42501` |

**Grupo L**, ampliado: `identity_login_complete` e `identity_password_change` sem cada prova (desafio inexistente, finalidade errada, desafio consumido, passo repetido, código inexistente, parâmetros inválidos; sem step-up, step-up vencido, sessão restrita, revogada ou inexistente, hash divergente, senha igual). O instantâneo das 59 tabelas ficou idêntico antes e depois.

**Concorrência:**
- **V-5:** o login de recuperação espera pela outra sessão que revogaria, e o desafio vence durante a espera → `DESAFIO_INVALIDO`. Nenhum código é usado, nenhuma sessão nasce e as duas sessões anteriores continuam ativas.
- **V-6:** a troca de senha espera pelo desafio pendente que invalidaria, e a sessão vence → `false`. Credencial e desafio intactos.
- **K-6:** dois logins de recuperação com o mesmo código em paralelo → um usa o código; o outro não cria sessão.

**Mutações:** 7 de 7 detectadas. As novas:
- **M6** tira a conferência da credencial vigente: o desafio obsoleto passaria a gerar sessão.
- **M7** tira a invalidação dos desafios na troca de senha.

M3 e M4 foram reescritas como mutações de texto sobre o corpo da R3, porque a assinatura da função mudou. Os corpos R0 e R1 continuam nos pacotes anteriores.

**Defesas que a aplicação não alcança mais diretamente, mantidas e provadas como superusuário** (`t_s_superusuario.sql`):
- a marca de recuperação no futuro é recusada mesmo quando a sessão é inserida pelo dono;
- um código consumido apontando uma sessão não restrita é recusado no commit.

## 3. Parte D: base populada com a 016 → 017

| Passo | Resultado |
|---|---|
| base 015 → 016 com a massa das suítes anteriores | 303 contas, 60 fatores, 60 sessões ativas |
| **massa da 016 pelos caminhos reais**: 4 operadores com certificado TLS de 15 min registrado por `nekkora_ops_ca`, cadastro e habilitações, 4 contatos, 2 aberturas por retorno, verificação, pré-aprovação, fluxo de banco dos avisos sob `nekkora_platform` com entrega simulada, confirmação do titular, aprovação final | 22 atos aceitos; S1 APROVADA, S2 VERIFICADA |
| fixtures temporais **declaradas** da 016 | contato envelhecido 31 dias; S1 recuada 24 h + 1 min (as de `OpsBanco` e da homologação) |
| instantâneo de `public` + `ops` (70 tabelas) antes e depois da 017 | **idêntico**; nenhuma sessão revogada |
| **depois da 017**: certificados registrados de novo; **execução de S1**; consulta; pré-aprovação de S2; fluxo de banco dos avisos sob `nekkora_platform`, com entrega simulada | 6 atos aceitos. S1 EXECUTADA, com reset registrado: fator desabilitado, códigos e desafios invalidados, sessões revogadas e `mfa_events` tipo 4. Os efeitos passaram pelas triggers da 017. S2 PRÉ-APROVADA |
| titular resetado volta pela porta antiga | fator novo e sessão (ramo 1 da §4.4) |
| invariantes 012/013/014/016/017 depois da retirada | verdes |
| catálogo de `public` e de `ops` × base limpa 001–017 no mesmo cluster | idênticos |
| restauração lógica (`pg_dump`/`pg_restore`) | contagens idênticas em `public` e `ops` |

Isso não ativa a 016 em ambiente nenhum: tudo roda em containers descartáveis e sem rede.

## 4. Regressão

| Parte | R2 | R3 |
|---|---|---|
| catálogo: fora do previsto; remoções | 0; 2 | 0; 2 (830 acréscimos) |
| invariantes 012/013/014/017 | verdes | verdes; a invariante 3 passou a 16 funções e a 7 cobre os privilégios retirados |
| grupos anteriores | verdes | verdes. As sessões dos testes passaram a nascer pelo login real. As adaptações, declaradas em cada arquivo, são estas: |
| esperas de lock observadas | 25 | 27 |
| pontos com E-1..E-3 | 79 | 85 |
| ↳ recusas por privilégio | — | D-9, G-8, H-15, P-3 e S-11: escritas que a Platform perdeu agora falham com `42501` (antes, por RLS ou trigger). As defesas de trigger continuam provadas como superusuário |
| ↳ cenários reescritos | — | D-7: T0 agora acontece dentro de `identity_login_complete`. G-4 e G-5: o controle positivo usa uma sessão comum criada **depois** da restrita, porque T0 revoga as anteriores. I-4: uma identidade PENDING nem obtém sessão. P-2 e P-4: as contagens exatas viraram "todas maiores que zero" para A e "todas zero" para B, porque o login cria desafios e sessões adicionais. Sessões impossíveis por função (sem fator, recuperação retroativa) foram para a fixture declarada `f03` |
| grupo M | 348 | 358, com a diferença revisada linha a linha: Platform sem `INSERT` de sessão, sem `UPDATE (consumed_at)` e sem `UPDATE` de códigos; o dono com o mínimo para as três funções |
| mutações | 5 de 5 | 7 de 7 |
| B (015 → 017) e C (001–017 com a 016, com controles positivos) | verdes | verdes |
| **D (016 → 017)** | — | **verde** |

## 5. Pendências

- **Capacidade de execução no cadastro:** decisão de produto pendente. O zero da suíte não a resolve. A recomendação registrada da responsável é cadastro imediato, execução dependente de ativação explícita e essa condição visível na web.
- **Integração Java:** aguarda a revisão deste pacote. Na regressão futura, falhas da linha de base serão identificadas uma a uma, sem exclusões automáticas.
- `ops/homologacao/implantacao/elevacao.txt` sem a 017: fica para a etapa de ambiente.
