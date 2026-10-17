# Migration 017: linha de base, IdentityTransactions, fixtures e regressão Java

**Data:** 2026-10-11. **Estado:** infraestrutura transacional e fixtures implementadas e validadas localmente. Rotas novas, implantação, ambiente preservado, commit e push continuam fora desta autorização; nenhum foi tocado. A capacidade inicial de execução continua como decisão de produto pendente.

**Base:** commit `20ccb06` da `main`, sem commit desta etapa. Os pacotes R0 a R3 foram **preservados sem alteração**.

## 0. Errata da R3 (avisos)

A frase "avisos emitidos e entregues pela Platform" exagerava o que a parte D mostra. A parte D demonstra só o **fluxo de banco** dos avisos sob `nekkora_platform`, com um resultado de entrega **simulado** (`ops_notice_delivery_result`). Não há remetente Java, transporte nem e-mail.

| Onde | Correção |
|---|---|
| `2026-10-11-identidade-017-validacao-r3.md` | parágrafo de errata no topo e texto da tabela |
| `db/tests/identidade-017/README.md` | o mesmo texto |
| `parte_d.sh` | comentário, `RAISE NOTICE` e linha de resumo |

O ZIP e os logs da R3 não foram alterados, e a suíte não foi reexecutada.

## 1. Linha de base sem a 017

**Execução.** Exportação limpa do `20ccb06` (`git archive`), sem a 017 e com o `aplicar_como_migrador.sh` original. Rodou `mvnw -B test -Dmaven.test.failure.ignore=true`, para que a falha do `nekkora-ops` não pulasse o `nekkora-platform`.

**A contagem é de 47 ITs, não 37.** O reator executa 47 classes `*IT`, mais 7 classes `*Test` de unidade. A lista completa está em `linha_de_base_classes.txt`. Duas classes não entram na contagem:
- `MedicaoDc07`, que só roda por nome;
- `CrossProcessE2E`, que não casa com `*IT`.

**Resultado.**

| Módulo | Situação | Testes | Falhas | Erros |
|---|---|---|---|---|
| nekkora-core | verde | 5 | 0 | 0 |
| nekkora-ops | 21 de 22 classes verdes | 155 | 4 | 1 |
| nekkora-platform | verde | 159 | 0 | 0 |
| nekkora-portal | verde | 12 | 0 | 0 |

**As 5 falhas da linha de base, todas em `com.nekkora.ops.homologacao.p1c.FixtureRunnerIT`:**

| Teste | Tipo | Mensagem |
|---|---|---|
| `contatosPeloRunnerNaoTocamORegistroDeB9` | falha | `ops/homologacao/lib/estado.sh: line 27: TRAVA_PG_PID: unbound variable` |
| `solicitacaoAplicadaComRegistroPreventivoEReconciliacao` | falha | a mesma |
| `solicitacaoRecusadaFicaImpropriaParaB9` | falha | `runner imprime a classificacao` (a saída não traz a classificação) |
| `bancoIndisponivelNaExecucaoFicaIndeterminadaEImpropriaParaB9` | falha | a mesma |
| `falhaDepoisDoCommitNuncaEDescritaComoRollback` | erro | `tempo esgotado esperando: fixture no UPDATE` |

**Causa provável: o ambiente, não o banco nem a 017.**
- O único bash desta máquina é o `/bin/bash` 3.2.57 do macOS, que não tem `coproc`.
- `adquire_trava_banco`, em `estado.sh:26-27`, abre `coproc TRAVA_PG` e lê `$TRAVA_PG_PID`. Sob `set -u`, o runner aborta antes de agir.
- As duas falhas de classificação e o tempo esgotado são compatíveis com o runner parado nesse ponto, mas **não as isolei uma a uma**.

Nenhuma das 5 virou exclusão. Elas continuam na execução e no relatório, e confirmar a causa num bash ≥ 4 fica pendente.

## 2. Execução com a 017, antes de adaptar as fixtures

Rodei a árvore de trabalho com a 017 e as fixtures originais (`com017_antes_fixturas.log`) para obter a lista empírica de falhas. Foram **225 testes com falha ou erro**, todos de preparação e nenhum de asserção:

| Causa | Testes | Origem |
|---|---|---|
| `contexto organizacional ausente ou divergente` (42501), de `identity_check_context` | 224 | `INSERT` ou `UPDATE` não terminal de fixture nas 7 tabelas da entrada local, sem `nekkora.organization_id`. Isso inclui os 5 do `FixtureRunnerIT`, que passaram a falhar na fixture antes do runner |
| `sessao de identidade nova nasce sem revogacao…` (23514) | 1 (o `setUp`) | a fixture nova do `IdentityTransactionsIT` inseria uma sessão já revogada. A trigger da 017 está certa; corrigi a fixture |

**Tabelas guardadas pela 017:**
- `user_credentials`, `auth_challenges`, `user_mfa_factors`, `user_mfa_recovery_codes`, `password_reset_tokens` e `email_verification_tokens`, por `refuse_local_entry_for_linked_account`;
- o `INSERT` em `user_sessions`, por `enforce_user_session_mfa_on_insert`.

As transições só terminais não exigem contexto, e as fixtures que só fazem isso **não foram tocadas**. Transições terminais são `disabled_at`, `used_at`, `invalidated_at` e `revoked_at`. O mesmo vale para os `UPDATE`s em `user_sessions`.

## 3. Adaptação das fixtures

**Regra seguida (contrato §3.2):**
- só a **preparação** muda: a escrita passa a rodar numa transação com `set_config('nekkora.organization_id', <org da conta>, true)`;
- nenhuma asserção e nenhum valor esperado mudam;
- nenhuma trigger da 017 é desligada;
- os testes de autorização continuam com o papel real;
- `MedicaoDc07` não foi tocado.

**Helpers novos:**
- `RealDatabase.asSuperuserIn(org, sql…)`;
- `OpsBanco.sqlNa(org, sql…)`;
- `PlatformTestBase.organizationOf(user)`, uma leitura que descobre a organização da conta;
- `CrossProcessE2E.sqlIn(org, sql…)`.

**Mudanças por arquivo:**

| Arquivo | Mudança |
|---|---|
| `ops/testsupport/OpsBanco` | `usuario` (credencial), `fatorConfirmado` e `estadoDeAcesso` (sessão, desafio, códigos) → `sqlNa(org, …)` |
| `ops/Revisao4DeclaracaoIT` | `sinal()` ganha o `set_config` como primeiro passo da transação, antes do `FOR UPDATE` da conta. Como não trava nada, a ordem e as esperas de lock não mudam. `last_used_step = 4242` → `sqlNa` |
| `platform/PlatformTestBase` | `insertUser` → `asSuperuserIn(org, …)`; novo `organizationOf` |
| `platform/AuthenticationFlowIT` | conta sem vínculo (credencial) e as 2 trocas de `password_hash` → `asSuperuserIn(org.id(), …)` |
| `platform/EmailVerificationRouteIT` | `seed` do token → `asSuperuserIn(organizationOf(u), …)` |
| `platform/MfaAtomicCompletionIT` | `expireChallengeSoon` → `asSuperuserIn(organizationOf(user), …)`. O desligamento da trigger **da 014** que já existia continua, e as três instruções agora rodam numa única transação |
| `platform/AccessFixtures` | `mfaFactor` → `asSuperuserIn(org, …)` |
| `platform/EmergencyRecoveryFlowIT` | `senhaReal` → `sqlNa(org da conta, …)` |
| `platform/InventarioLocksPlatformIT` | os 2 `INSERT`s de token de e-mail → `sqlNa` |
| `platform/mail/ClienteH016IT` | o bloco da organização sintética → `sqlNa(org, …)` |
| `platform/e2e/CrossProcessE2E` | o bloco da fixture → `sqlIn(ORG, …)`. Compila, mas **não roda** no `mvn test` (`CrossProcessE2E` não casa com `*IT`) |
| `portal/PortalIT` | `externalSession` → `asSuperuserIn(ORG, …)` |

O diff completo está em `java_017.diff`. As **19 linhas removidas** são todas chamadas de preparação (`sql`/`asSuperuser` → `sqlNa`/`asSuperuserIn`), mais o retorno de `sinal()`. Nenhuma linha removida ou acrescentada contém asserção.

## 4. IdentityTransactions

O arquivo é `nekkora-core/.../db/IdentityTransactions.java`, com o bean em `PlatformConfig`. Ele implementa o contrato §1.1-1.3:
- cada chamada abre uma transação `READ COMMITTED` e `REQUIRES_NEW`;
- **guarda:** `nekkora.identity_id` e `nekkora.organization_id` precisam estar vazios; senão lança `ContextoVazado` e aborta sem executar;
- **resolução:** pela chave exata (`TokenSessao`, `TokenDesafio`, `TokenEmail`, `EmailCadastro`), usando as funções de consulta da 017 dentro da mesma transação. No cadastro (`NovaIdentidade`), o UUID é gerado pela aplicação;
- **contexto:** `set_config(…, true)` por bind;
- **trabalho.**

A classe não aceita UUID solto: o `identity_id` sai só da resolução. A `IdentidadeResolvida` falha se for usada depois da transação.

**Escopo:** nenhuma rota usa a classe ainda. As rotas ficam para a etapa seguinte.

**`IdentityTransactionsIT`: 9 de 9 verdes.** Todos os testes rodam com o papel real `nekkora_platform`, por pool Hikari:

| Teste | O que prova |
|---|---|
| `mesmaConexaoDuasIdentidadesSemVazamento` | mesma conexão física (mesmo `pg_backend_pid`): A vê só A e B vê só B |
| `contextoLocalNaoVazaParaATransacaoSeguinte` | depois do `COMMIT`, o GUC está vazio e, sem contexto, nenhuma linha aparece |
| `setDeSessaoEsquecidoAbortaAGuardaEODiscardAllOApaga` | `SET` de sessão esquecido em pool cru: a guarda aborta sem executar; com `ConnectionResetDataSource`, o `DISCARD ALL` apaga |
| `chaveQueNaoResolveNaoExecutaOTrabalho` | token inexistente ou revogado: `Optional.empty()`, sem contexto e sem trabalho |
| `identidadeResolvidaNaoAtravessaTransacoes` | a `IdentidadeResolvida` usada depois do commit lança `IllegalStateException` |
| `identityIdDeOutraPessoaVindoDaRequisicaoNaoAlcancaNada` | o contexto é o da chave; ler ou escrever na outra identidade dá 0 linhas |
| `outraThreadNaoHerdaContexto` | o trabalho feito em outra thread não vê nada (falha fechada) |
| `soAVinculacaoTemOsDoisContextos` | só `inIdentityAndOrganization` define os dois GUCs |
| `cadastroSoInsereComOUuidGeradoEPostoNoContexto` | o `INSERT` com o UUID gerado passa; para outro UUID → `42501` |

## 5. Regressão com a 017

`com017_regressao.log`: **idêntica à linha de base, classe por classe e teste por teste.**

| | Linha de base (sem 017) | Com 017 |
|---|---|---|
| Classes `*IT` | 47 | 48 (47 + `IdentityTransactionsIT`) |
| Verdes | 46 | 47 |
| `ConnectionResetIT` | 3/3 | 3/3 |
| `FixtureRunnerIT` | 4 falhas + 1 erro | as mesmas 5, com as mesmas mensagens (só o caminho absoluto muda: exportação × árvore) |
| Demais classes | verdes | verdes, com o mesmo número de testes |

**Nenhuma falha nova e nenhuma exclusão.**

## 6. Ressalvas e pendências

1. **FixtureRunnerIT:** as 5 falhas são da linha de base e têm causa provável no ambiente (bash 3.2 sem `coproc`). Confirmar num bash ≥ 4 fica pendente.
2. **Scripts de homologação não adaptados:** `ops/homologacao/sql/semear_f6a.sql`, `semear_frontend.sql` e `testes/test_semear_f6a_banco.sh` também escrevem nas tabelas guardadas sem contexto. Eles alimentam o ambiente de homologação, que não recebeu a 017 e está fora desta autorização. Vão falhar quando a 017 for aplicada lá, e precisam da mesma adaptação antes disso.
3. **`CrossProcessE2E`** foi adaptado e compila, mas não foi executado.
4. **`MfaAtomicCompletionIT`** continua desligando a trigger **da 014** (`trg_auth_challenges_prevent_change`) na preparação, como já fazia. Nenhuma trigger da 017 é desligada por fixture alguma.
5. A RLS limita o acesso pelo contexto, mas não prova autenticação. As consultas exatas não impedem sondagem repetida. As ressalvas da R2 e da R3 continuam valendo.
