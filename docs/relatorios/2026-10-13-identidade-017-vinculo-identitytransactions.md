# Migration 017: vínculo da IdentidadeResolvida, prova de limpeza transacional e FixtureRunnerIT

**Data:** 2026-10-13. **Estado:** revisão local da infraestrutura e dos testes. A migration 017 não foi alterada. Rotas novas, implantação, ambiente preservado, commit e push continuam fora; nenhum foi tocado.

**Base da revisão:** o pacote `pacote-017-java-2026-10-11`, com 31 hashes conferidos pela responsável. Ele foi **preservado sem alteração**, assim como os pacotes R0 a R3.

> **Precisões registradas depois da aprovação** (2026-10-13). O pacote `pacote-017-java-r1-2026-10-13` foi preservado sem alteração, e o complemento está em `pacote-017-java-r1-complemento-2026-10-13`.
>
> 1. **M3 sem registro no pacote.** O `mutacao_m3_com_reset.log` do pacote está vazio: rodei com `-q`, que não imprime nada quando o teste passa, e o XML do surefire daquela execução foi sobrescrito pelas seguintes. **O registro original não existe**, e o pacote não comprova a execução relatada. O complemento traz uma **reexecução** da M3 de 2026-10-13, sem `-q`, com o diff aplicado, a saída completa e o XML do surefire. O teste passou (1/1) e as fontes foram restauradas idênticas às do pacote aprovado. A correção se sustenta pelo teste no pool cru e pela M2.
> 2. **M1a: sobreposição, não independência.** A M1a mostra que, nos cenários testados (outra thread e transação interna), a conferência do recurso de conexão também recusa o uso. Isso é **sobreposição** das defesas nesses cenários, não independência entre elas. A frase da §1 ("as camadas são independentes") fica corrigida neste sentido.
> 3. **A conferência da conexão é condicional ao gerenciador.** O construtor aceita qualquer `PlatformTransactionManager` e deixa `fonte = null` quando ele não é um `DataSourceTransactionManager`. Nesse caso, a terceira condição não é conferida. A configuração atual usa `DataSourceTransactionManager` (`PlatformConfig`), e nela a conferência funciona. **Requisito registrado:** antes de admitir outro gerenciador, a classe deve recusá-lo no construtor ou implementar um vínculo equivalente para ele.
>
> **Situação da regressão:** **infraestrutura aprovada com pendências registradas**, e não "suíte inteira verde". As pendências são:
> - as 5 falhas de `FixtureRunnerIT` com o bash 3.2 da execução padrão;
> - a intermitência de `ReducingAccessRoutesIT`, cuja existência na base está demonstrada, enquanto a explicação pela recomposição de fichas continua sendo **hipótese**;
> - `CrossProcessE2E` não executado;
> - as semeaduras não adaptadas.

## 1. IdentidadeResolvida vinculada à thread e à transação de origem

**O achado.** `exigirValida()` só conferia um booleano, desligado quando a chamada externa terminava. Enquanto ela estava aberta, havia dois usos indevidos:
- outra thread conseguia consultar o objeto;
- uma transação interna `REQUIRES_NEW` conseguia consultar a identidade da externa, embora esta estivesse suspensa.

**A correção** está em `IdentityTransactions.java`. O objeto é construído **depois** da leitura pela chave, na thread e na transação correntes, e guarda três coisas:
- a thread dona;
- o recurso de conexão ligado à thread, cuja chave é o `DataSource` do `DataSourceTransactionManager`;
- um estado `ATIVA`, `SUSPENSA` ou `ENCERRADA`, mantido por um `TransactionSynchronization` registrado na transação de origem:
  - `suspend()` → `SUSPENSA`;
  - `resume()` → `ATIVA`;
  - `afterCompletion()` → `ENCERRADA`, seja por commit ou por rollback.

O `finally` continua encerrando o objeto, por redundância. Construir o objeto sem transação com sincronização ativa é recusado.

**Cada acesso exige as três condições ao mesmo tempo**, e cada uma tem sua mensagem de recusa:

| Condição | Recusa |
|---|---|
| a thread dona | "fora da thread da transacao que a resolveu" |
| o estado `ATIVA` | "com a transacao de origem suspensa" ou "fora da transacao que a resolveu" |
| o mesmo recurso de conexão ligado à thread | "com outra conexao ligada a thread" |

**Controles novos em `IdentityTransactionsIT`** (papel real `nekkora_platform`, pool Hikari):

| Teste | Situação | Resultado |
|---|---|---|
| `identidadeResolvidaUsoNormalNaTransacaoDeOrigem` | acessos repetidos, intercalados com consultas | aceitos: `A`, `A`, referência presente, 3 linhas |
| `identidadeResolvidaRecusadaEmOutraThreadComAOrigemAberta` | outra thread, com a origem ainda aberta | recusada ("thread"); a thread de origem continua usando |
| `identidadeResolvidaRecusadaNaTransacaoInternaEValidaNaRetomada` | origem → `preAuthentication` interna → retomada → `inIdentity(B)` interna → retomada → interna **desfeita** → retomada | a sequência registrada é exatamente `A`, suspensa, `A`, `B` com a externa suspensa, `A`, suspensa, `A` com 3 linhas. A interna tem a sua própria resolução |
| `identidadeResolvidaEncerradaPorCommitEPorRollback` | depois do `COMMIT`; depois do `ROLLBACK`, provocado por exceção no trabalho | recusada ("encerrada") nos dois casos, também em `referencia()` |
| `identidadeResolvidaNaoAtravessaTransacoes` | o teste anterior, mantido | inalterado |

**Contraprovas.** Apliquei cada mutação no código principal e restaurei o original, conferido byte a byte com `cmp`:

| Mutação | Efeito | O que reprova |
|---|---|---|
| **M1**: as três condições desligadas (equivale ao booleano anterior) | outra thread e transação interna passam a ser aceitas | `…EmOutraThread…` e `…NaTransacaoInterna…` |
| **M1a**: thread e suspensão desligadas, **conferência do recurso mantida** | na primeira tentativa, essa condição sobrou por um erro de indentação na substituição | só `…NaTransacaoInterna…`, porque a recusa vem com outra mensagem. **A conferência do recurso sozinha recusa os dois usos nesses cenários**: as defesas se sobrepõem neles, o que não prova independência entre elas |

## 2. Prova de limpeza transacional sem o DISCARD ALL

**O achado.** `contextoLocalNaoVazaParaATransacaoSeguinte` usava o pool com `ConnectionResetDataSource`. O teste passaria mesmo se o `DISCARD ALL` estivesse corrigindo um contexto indevidamente persistente.

**A correção.** O teste foi substituído por `contextoLocalSomeNoCommitENoRollbackSemReset`. Ele usa um **pool cru de uma conexão**, exclusivo dele, sem reset. A leitura do pid e dos dois GUCs é feita por conexão crua, fora do `IdentityTransactions` e sem a guarda.

| Passo | Prova |
|---|---|
| `inIdentityAndOrganization` com commit | dentro: o mesmo pid, `identity_id = A`, `organization_id = ORG`. Depois: **o mesmo pid** e os dois contextos `''` |
| `inIdentityAndOrganization` com rollback (exceção no trabalho) | dentro: o mesmo pid e o contexto `A`. Depois: **o mesmo pid** e os dois contextos `''` |
| `preAuthentication` em seguida | a guarda passa e, sem contexto, nenhuma linha. Esta é a asserção do teste antigo, preservada |

O teste do `DISCARD ALL` (`setDeSessaoEsquecidoAbortaAGuardaEODiscardAllOApaga`) continua **separado e inalterado**.

**Contraprovas:**

| Mutação | Resultado |
|---|---|
| **M2**: `set_config(…, false)`, ou seja, contexto **de sessão**, nos dois GUCs | o teste novo **reprova** em "depois do COMMIT, sem reset" |
| **M3**: a mesma M2, com o teste novo rodando sobre `ConnectionResetDataSource` | o teste **passa**. Isso confirma que a prova antiga encobria esse defeito |

## 3. Resultado

| | Antes | Agora |
|---|---|---|
| `IdentityTransactionsIT` | 9/9 | **13/13**: 4 testes novos, e 1 substituído pela versão no pool cru |
| `ConnectionResetIT` | 3/3 | 3/3 |

**Regressão completa com a 017** (`com017_regressao_r2.log`): todas as classes iguais à linha de base, exceto:
- `IdentityTransactionsIT`, com 13/13;
- as 5 falhas de `FixtureRunnerIT`, que já existiam, com as mesmas mensagens; a causa está na §4;
- **`ReducingAccessRoutesIT.revokeAllIsNotBlockedByExhaustedR7`: 1 falha**, detalhada abaixo.

**`ReducingAccessRoutesIT`: intermitência que já existia na base, sem relação com a 017.**
- **Mensagem:** `[controle: escrita comum com a cota esgotada] expected: 429 but was: 403`, em `exhaustR7:39`.
- **Repetições da classe isolada:** falhou em **2 de 5 sem a 017** (exportação `20ccb06`) e em 1 de 5 com a 017. Foi sempre a mesma asserção de controle, nas repetições em `logoutIsNotBlockedByExhaustedR7`, que usa o mesmo `exhaustR7`.
- **Causa provável:** o R7 é um balde com recomposição contínua (capacidade 60, 2 por segundo). Se uma ficha voltar entre o último 429 e o POST de controle, a escrita passa do limite e é recusada pelo próprio step-up, com o código `000000`. Daí o 403.
- **O que não muda:** nada no caminho da rota foi alterado, e a 017 não toca no limitador.
- **Tratamento:** fica registrada como pendência da base. Não excluí o teste e não mudei a expectativa.

## 4. FixtureRunnerIT com bash compatível

**Bash usado.** O bash 5.3 foi compilado do código-fonte oficial, obtido pelo cache do Homebrew, numa pasta de rascunho. A compilação foi `--disable-readline --disable-nls`, e o binário depende só de `libSystem` e `libiconv`.
- **Nada foi instalado no sistema.** O `/bin/bash` 3.2 continua sendo o padrão.
- O teste recebeu o bash por `-Dnekkora.bash=`, a propriedade que `Processos.bash()` já aceita, e o mesmo bash no início do `PATH` do processo Maven.
- Antes, conferi que o binário executa `coproc` com `set -u`.

| Base | Bash 3.2 (linha de base e regressão) | Bash 5.3 |
|---|---|---|
| sem a 017 (`20ccb06`) | 4 falhas + 1 erro | **5/5** |
| com a 017 | 4 falhas + 1 erro | **5/5** |

**Causa demonstrada para as cinco:** o bash 3.2 do macOS, sem `coproc`. Com um bash compatível, as cinco passam nas duas bases, sem nenhuma expectativa alterada e sem exclusão. Na execução padrão desta máquina, que usa o 3.2, elas continuam falhando, e isso está registrado.

## 5. Continua como antes

- `CrossProcessE2E` compila, mas não foi validado em execução.
- Os scripts de semeadura da homologação (`semear_f6a.sql`, `semear_frontend.sql`, `test_semear_f6a_banco.sh`) precisam ser adaptados antes de o ambiente receber a 017.
- Nenhuma rota usa o `IdentityTransactions` ainda. Rotas novas, implantação e commit/push continuam aguardando.
- A capacidade inicial de execução continua como decisão de produto pendente.
