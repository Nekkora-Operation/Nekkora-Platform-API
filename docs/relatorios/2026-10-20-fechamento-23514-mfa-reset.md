# Fechamento do 23514 na corrida conclusão do MFA × reset administrativo

**Data:** 2026-10-20. **Missão:** API-FECHAMENTO-001. **Base:** branch `feat/identidade-p4-p3-contas-vinculadas` em `697f946` (P4/P3 com a revisão 1 e as correções de CI). **Migrations:** nenhuma alteração; 017 continua na R5 (sha256 `90b8ddbc…`).

## 1. Problema e impacto

Na regressão completa do candidato `9f98f71` (2026-10-19, macOS, Docker Desktop), `MfaConcurrencyIT#mfaCompletionVersusAdminResetNeverLeavesAnActiveSessionOrFactor` falhou na rodada 3 de 10: o reset administrativo do MFA de uma conta **não vinculada**, em corrida com a conclusão do MFA dessa conta, respondeu `500` com SQLSTATE `23514`. A mensagem do PostgreSQL se perdeu com o container. A ocorrência continua registrada em `2026-10-18-p4-p3-contas-vinculadas.md` §8.6; as execuções verdes posteriores não a apagam.

**Impacto do defeito encontrado:** se o relógio de parede do servidor de banco recua entre a criação de uma sessão de organização e a revogação dela, a revogação viola a CHECK `revoked_at >= created_at` da 002 e a ação **inteira** é desfeita com `500`. Para o reset do MFA, isso significa: nenhum efeito (fator, códigos, desafios e sessões continuam como estavam, sem evento e sem aviso). A falha é atômica, não deixa estado parcial, mas a ação de segurança não acontece e a sessão recém-criada continua valendo até nova tentativa. O mesmo vale para todo caminho que revoga sessões de organização: suspensão, troca e redefinição de senha, uso de código de recuperação, "encerrar todas as sessões", logout e o limite de sessões por conta na conclusão do MFA. O Portal não revoga sessões de organização (conferido).

## 2. Causa demonstrada

As revogações de sessão de organização em `SessionRepository` gravavam `revoked_at = clock_timestamp()` (ou o instante lido antes, no limite de sessões). `clock_timestamp()` é o relógio de parede do servidor e pode recuar entre duas transações. A sessão criada pela conclusão do MFA tem `created_at = clock_timestamp()` lido dentro da transação dela. Com um recuo maior que o intervalo entre a criação e a revogação, `revoked_at < created_at` e a CHECK `user_sessions_check2` recusa.

As revogações da identidade (017: `identity_link_account`, funções de sessão de identidade; `IdentityAuthRepository.revokeSession`) já usavam `greatest(instante, created_at)` exatamente por isso. As de organização, mais antigas (014), não.

**Intercalamento:** a conclusão do MFA e o reset serializam no lock `FOR UPDATE` da conta (verificado: os dois tomam `users ... FOR UPDATE` antes de qualquer escrita).
1. A conclusão obtém a conta primeiro, consome o passo e o desafio, cria a sessão S com `created_at = t1` e confirma.
2. O reset, que esperava a conta, obtém o lock e revoga as sessões da conta com `clock_timestamp() = t2`.
3. Sem recuo, `t2 > t1` (a revogação acontece depois do commit da criação). Com recuo do relógio maior que `t2 - t1` (alguns milissegundos nessa ordem), `t2 < t1`: `23514`, rollback do reset, `500`.

**O ambiente que dispara:** o servidor de banco dos testes roda na VM Linux do Docker Desktop no macOS. O relógio dessa VM não é disciplinado por NTP (`adjtimex`: `status 64 (UNSYNC)`, `freq.adjust 0`). Ele é ressincronizado com o host aos saltos. Um recuo dessa VM já foi medido neste mesmo projeto: em 2026-09-28, `clock_timestamp()` voltou cerca de 1,8 ms entre dois comandos consecutivos e violou `executions_check10` (`2026-09-27-mfa-emergencia-implementacao.md`, execução `2026-09-28T025340Z`). O check do CI roda em Linux nativo, sem VM, e não teve a falha.

**Classificação:** defeito do produto (revogação sem a proteção que a identidade já tinha), disparado pelo ambiente (recuo do relógio da VM local). Não é defeito do teste: o teste afirmava o comportamento certo (o reset sempre conclui).

## 3. Atribuição da ocorrência original: limite declarado

A ocorrência de `9f98f71` **não pode ser atribuída com certeza**: dela restam o status (`500`), o SQLSTATE (`23514`) e a rodada. O nome da restrição se perdeu. A atribuição a este mecanismo é a única explicação que sobrevive à eliminação abaixo, mas é **inferida**:

| Fonte de 23514 alcançável pelo reset de conta não vinculada | Por que não explica a ocorrência |
|---|---|
| Trigger `refuse_local_entry_for_linked_account` (017), ramo terminal: coluna fora da lista alterada junto | As escritas do reset alteram só as colunas previstas. Determinística: falharia em toda rodada em que o reset vem primeiro |
| Mesma trigger, ramo não terminal (conta não encontrada no contexto; conta vinculada) | As escritas do reset são todas terminais (retornam antes). A conta não é vinculada |
| `prevent_user_session_reactivation` (014/017) | Só recusa reabrir sessão revogada, mudar campos imutáveis ou step-up; o `UPDATE` do reset filtra `revoked_at IS NULL` e só preenche `revoked_at` |
| Imutabilidade de fator, desafio e códigos (014) | Levantam P0001, não 23514 (e o reset mapeia P0001 para 403) |
| CHECKs de `mfa_events` (tipo, ator ≠ alvo, motivo do tipo 3) | Determinísticas: mesmo ator, alvo e motivo em todas as rodadas |
| CHECKs de exclusividade e hash de `auth_challenges` | Garantidas pelo `WHERE` (`consumed_at IS NULL AND invalidated_at IS NULL`) e pela serialização na conta |
| `disabled_at >= created_at` (fator) e `invalidated_at >= created_at` (códigos) | Dependem do relógio, mas fator e códigos nascem no primeiro login, segundos antes. Exigiriam um recuo de segundos |
| **`revoked_at >= created_at` (sessão, `user_sessions_check2`)** | **Depende do relógio e a linha nasce milissegundos antes, na ordem "conclusão primeiro". Única fonte compatível com uma falha rara, só nessa corrida e só no ambiente com VM** |

A leitura do vínculo acrescentada ao reset na revisão 1 (`identity_account_is_linked`) não escreve e só levanta 42501. O mecanismo existe sem ela: no código da `main` (`493e4f9`, sem a revisão 1), o reset e a suspensão com a sessão à frente do relógio respondem `500` com `user_sessions_check2` (§5). A revisão 1 pode ter mudado o tempo dos intercalamentos, mas não é necessária para a falha.

**Sonda de relógio nesta sessão:** duas sondas de `clock_timestamp()` em PostgreSQL descartável, uma ociosa (4 min) e outra durante uma regressão completa (40 min), não registraram recuo (§5). A ausência nesta janela não contradiz o recuo já medido em 2026-09-28; mostra que ele é raro, como a falha.

## 4. Correção

`SessionRepository` (só código da aplicação, sem migration):

| Método | Antes | Depois |
|---|---|---|
| `revokeForAccount` (reset, suspensão, troca e redefinição de senha, código de recuperação, encerrar todas as sessões) | `revoked_at = clock_timestamp()` | `revoked_at = greatest(clock_timestamp(), created_at)` |
| `revokeByToken` (logout) | `revoked_at = clock_timestamp()` | `revoked_at = greatest(clock_timestamp(), created_at)` |
| `revokeExcess` (limite de sessões na criação) | `revoked_at = ?` | `revoked_at = greatest(?, created_at)` |

É a forma que as revogações da identidade já usam. Com o relógio correto, `greatest` devolve o próprio instante e nada muda. Com recuo, a sessão é revogada assim mesmo, com `revoked_at = created_at`. Nenhuma consulta usa o valor de `revoked_at` além de "nulo ou não" (conferido).

**Garantias preservadas:** a CHECK da 002 continua valendo e não foi contornada; nenhum 23514 é convertido em sucesso ou em recusa genérica; a ordem global de locks, a recusa da conta vinculada (403 sob os locks, antes dos efeitos), o anti-replay, o consumo único, a atomicidade entre efeitos e evento e as exceções terminais da 017 não mudaram.

## 5. Provas

Todas locais (macOS arm64, Docker Desktop 27.4.0, Java 21.0.5). Saídas dos testes com o diagnóstico sanitizado do PostgreSQL (§6).

**`MfaResetRaceIT` (novo, 6 testes):**

| Teste | O que prova |
|---|---|
| `conclusaoDoMfaPrimeiroResetDepois` | Barreira: conta travada pelo superusuário, conclusão do MFA na fila, reset na fila atrás dela (as duas esperas conferidas em `pg_stat_activity`). Conclusão 200, reset 204; a sessão do MFA nasceu e foi revogada; fator, códigos e desafios fechados; um evento de reset com motivo; aviso ao titular |
| `resetPrimeiroConclusaoDoMfaDepois` | A ordem inversa: reset 204; conclusão 401, sem sessão nova; mesmos efeitos |
| `resetRevogaSessaoCriadaAFrenteDoRelogioDoBanco` | O mecanismo: depois da conclusão, a sessão dela passa a ter `created_at` 1 min à frente do relógio do banco (superusuário, triggers desligadas só na conexão do teste, CHECKs valendo). Reset 204 com todos os efeitos |
| `suspensaoRevogaSessaoCriadaAFrenteDoRelogioDoBanco` | Mesma classe na suspensão |
| `logoutDeSessaoCriadaAFrenteDoRelogioDoBanco` | Mesma classe no logout |
| `limiteDeSessoesRevogaSessaoCriadaAFrenteDoRelogioDoBanco` | Mesma classe no limite de 10 sessões: a conclusão do MFA revoga a mais antiga, à frente do relógio |

A simulação move o `created_at` para a frente porque o relógio do container não pode voltar dentro do teste. É o mesmo estado que um recuo produz. **É reprodução do mecanismo, não da ocorrência original.**

| Execução | Commit / código | Resultado |
|---|---|---|
| Antes da correção | código de `697f946` | ordens 1 e 2: passam; os 4 cenários do mecanismo: **500**, `sqlstate=23514`, `new row for relation "user_sessions" violates check constraint "user_sessions_check2"` = `CHECK ((revoked_at IS NULL) OR (revoked_at >= created_at))`, no `UPDATE ... SET revoked_at = clock_timestamp()` (reset, suspensão, logout) e no `UPDATE ... SET revoked_at = $1` (limite) |
| Sem a revisão 1 | `main` em `493e4f9` | reset e suspensão do mecanismo: **500**, mesma restrição; ordens 1 e 2 passam |
| Depois da correção | candidato | **6/6** |
| Contraprova M16: sem o `greatest` em `revokeForAccount` | candidato mutado | reset e suspensão do mecanismo reprovam (2) |
| Contraprova M17: sem o `greatest` em `revokeByToken` | candidato mutado | logout reprova (1) |
| Contraprova M18: sem o `greatest` em `revokeExcess` | candidato mutado | limite de sessões reprova (1) |
| Caminhos afetados, depois da correção | candidato | `MfaResetRaceIT` 6, `MfaConcurrencyIT` 5, `SessionsAndAdministrationIT` 8, `AuthenticationFlowIT` 10, `AccountLinkIT` 18, `MfaAtomicCompletionIT` 3: **48/0/0/0** (antes dos dois testes de logout e limite) |

**Sondas do relógio** (PostgreSQL 16 descartável `nekkora-relogio-pg`, `clock_timestamp()` amostrado a cada ~0,5 ms numa só sessão):

| Janela | Amostras | Recuos |
|---|---|---|
| Ociosa, 4 min | 113.513 | 0 |
| Durante a regressão completa de `697f946` (481/0/0/0) e dos testes dirigidos, 40 min | 1.169.851 | 0 |

Regressão completa, E2E e check do PR no candidato final: §7.

## 6. Diagnóstico preservado e sanitizado

- `DiagnosticoPg` (só teste): quando um ato não conclui, imprime do log do PostgreSQL de teste a linha de erro (com a restrição), a definição da restrição lida do catálogo e o texto da consulta (parâmetros como `$n`). A linha `DETAIL: Failing row contains (...)`, que traz hash de token e CSRF da linha recusada, é trocada por um marcador; parâmetros nunca são impressos.
- `MfaConcurrencyIT` passa a usar o mesmo diagnóstico. A versão anterior imprimia as linhas `DETAIL` sem filtro, e isso iria para os artefatos do CI numa próxima falha.

## 7. Candidato final

**Commit validado:** `563aed5` (correção e testes). O commit seguinte só traz este relatório e a nota no relatório de P4/P3.

| Execução | Resultado |
|---|---|
| Regressão completa em worktree limpo de `563aed5` (Bash 5.3 para o `FixtureRunnerIT`) | core 18, ops 155, platform 302 (296 + 6 novos), portal 12 = **487 testes em 78 classes, 0 falhas, 0 erros, 0 pulados** (contagens lidas do log) |
| E2E entre processos (`api/e2e/run.sh`, projeto descartável `nekkora-e2e-fech001`, removido depois, só ele) | `CrossProcessE2E` **6/6**, `LinkedAccountsE2E` **6/6** |
| Suítes de banco (`db/tests`) | **não executadas**: nenhuma migration, privilégio ou função SQL mudou |
| Check do PR | registrado no próprio PR, separado destas execuções locais |

## 8. Limitações e riscos remanescentes

- **Atribuição da ocorrência de `9f98f71`:** inferida por eliminação (§3), não provada. Se ela se repetir, o diagnóstico da §6 registra a restrição.
- **Mesma classe de defeito fora desta correção:**
  - `016`, função de reset operacional (`UPDATE public.user_sessions SET revoked_at = v_agora`, `v_agora = clock_timestamp()`). Está numa migration aplicada; a correção exige migration nova (`018`: `CREATE OR REPLACE` da função com `greatest(v_agora, created_at)`, sem tocar a 016) e passa pela suíte de banco da 016. **Não aplicada; fica para decisão.**
  - `disabled_at >= created_at` (fator) e `invalidated_at >= created_at` (códigos) no reset: exigem recuo de segundos (fator e códigos nascem no primeiro login). Mesma proteção possível em `MfaRepository.disableFactor`; não aplicada por não haver causa demonstrada.
  - Os prazos da 016 e demais CHECKs entre instantes: já analisados em `2026-09-27-mfa-emergencia-implementacao.md`.
- **Requisito de implantação já registrado (gate da 016):** relógio do servidor de banco sincronizado com correção gradual (*slew*), sem saltos para trás, e monitorado. Esta correção não substitui o gate: tira das revogações de sessão a dependência dele.
