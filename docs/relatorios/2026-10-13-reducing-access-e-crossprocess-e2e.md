# ReducingAccessRoutesIT e CrossProcessE2E

**Data:** 2026-10-13. **Estado:** revisão local. Não foram alterados:
- a migration 017;
- o código principal;
- o `api/e2e/run.sh` do repositório.

Rotas novas, implantação, ambiente preservado, Microsoft 365 e commit/push continuam fora.

**O que mudou no repositório:**
- `ReducingAccessRoutesIT.java`, só na preparação (§1);
- este relatório;
- o levantamento de modularidade, revisado (pedido em separado).

## 1. ReducingAccessRoutesIT: a causa deixa de ser hipótese

### 1.1 Medição

**Diagnóstico.** Usei uma classe temporária, `DiagR7TemporarioIT`: ela foi copiada para a árvore de testes, executada e removida em seguida (conferido). A classe repete o esgotamento de `exhaustR7` 20 vezes por execução. Depois de cada requisição, lê no Redis o saldo do balde `nk:rl:r7:s:<hash da sessão>`. Foram 5 execuções, ou seja, 100 esgotamentos.

| Medida | Resultado |
|---|---|
| saldo do balde logo antes do POST de controle | entre **0,014 e 0,994** ficha |
| controles que não deram 429 | **2 em 100**: `post=403` com saldo 0,994 (execução 2, rodada 13) e 0,988 (execução 4, rodada 1) |
| tempo entre a última leitura aceita e o POST | 13 a 71 ms |

### 1.2 Mecanismo demonstrado

1. O balde (capacidade 60, 2 por segundo) recompõe 0,002 ficha por ms.
2. Durante as 60 leituras aceitas, ele acumula uma fração. Depois das recusas, essa fração continua no balde, e cada recusa atualiza o saldo sem consumir nada.
3. Com saldo alto, poucos milissegundos de latência completam uma ficha. Com 0,994, bastam 3 ms.
4. Aí o POST de step-up passa do limitador e é recusado pelo próprio step-up, com o código `000000`. Daí o `403`, que é a mesma mensagem da falha registrada (`expected: 429 but was: 403`, em `exhaustR7`).

**Relação com a 017:** nenhuma. A intermitência depende só do saldo fracionário e da latência.

### 1.3 Ajuste

O ajuste é só de preparação. Depois das três leituras recusadas, que continuam sendo o controle de leitura, um script Lua zera o saldo do balde **desta sessão**. Ele usa a hora do próprio Redis, a mesma que o script do limitador usa. Para fazer isso, o teste recebe `StringRedisTemplate`.
- O script recusa uma chave inexistente, e a preparação confere isso com `enforce`. Assim, uma chave errada não deixa o ajuste sem efeito em silêncio.
- **Não mudaram** as asserções, as expectativas, os testes executados, a configuração da aplicação nem o limitador.
- Com o saldo em zero, o POST de controle só passaria se demorasse 500 ms. O maior intervalo medido foi de 71 ms.

**Alternativa descartada:** baixar a taxa do R7 numa configuração só desta classe. Não resolve o resíduo inicial e afeta o balde **por IP**, que é compartilhado no Redis com as outras classes.

### 1.4 Validação

| Execução | Resultado |
|---|---|
| classe ajustada, com a 017, 10 vezes | **10/10**, 2/2 testes cada (20 esgotamentos) |
| classe ajustada, sem a 017 (exportação `20ccb06`, cópia de rascunho restaurada e conferida), 5 vezes | **5/5** |
| execução final, depois de corrigir só o comentário | 2/2 |
| **contraprova**: saldo forçado em 0,999 em vez de 0 | **reprova os 2 testes**, com `controle: escrita comum com a cota esgotada … expected: 429 but was: 403`. Fonte restaurada e conferida com `cmp` |

A contraprova reproduz de forma determinística a falha original, o que confirma que a asserção continua sensível ao defeito que ela controla.

## 2. CrossProcessE2E

### 2.1 O que o `run.sh` do repositório faz hoje

Achados da leitura e da tentativa de execução:

| Achado | Efeito |
|---|---|
| chama `./mvnw`, mas o `mvnw` está no git como `100644`, sem execução | nesta máquina, para no build (`Permission denied`). A execução anterior, de 2026-09-26, foi no Windows (`cygpath`) |
| monta **só 001–015**, aplicadas pelo superusuário no initdb | não testa 016 nem 017, nem o modelo do migrador que a 016 exige (revisão 5) e que `RealDatabase` usa |
| a chave privada TLS só é apagada depois de as aplicações responderem | numa falha antes disso, `key.pem` fica em `api/e2e/saidas/`. Aconteceu na primeira tentativa, e **apaguei a pasta** daquela rodada |

Para não alterar o script do repositório, executei **duas cópias de rascunho**, com os diffs incluídos no pacote.

### 2.2 Cópia A: o `run.sh` com o mínimo necessário (001–015)

**Diferenças em relação ao original:** só `./mvnw` → `sh ./mvnw` (2 ocorrências) e `AQUI` apontando para o `api/e2e` do repositório.

**Resultado: 6 testes, com 3 falhas e 2 erros.**

| Teste | Resultado |
|---|---|
| `platformLoginWithMfaEnrollmentThroughSingleOrigin` | `500`. No log da pilha: `column "reason_code" of relation "mfa_events" does not exist`. A coluna vem da **016** |
| `portalInAnotherProcessAcceptsSameCookie`, `platformLogoutRevokesPortalAccess` | `NullPointerException`: consequência do primeiro, sem sessão |
| `secondLoginWithMfaAndRevokeAll` | `mfa_enrollment_required`: consequência do primeiro, porque a matrícula não terminou |
| `noSecretsInContainerLogs` | contagem de segredos vistos menor que o mínimo: consequência das anteriores |

**Conclusão:** o código atual precisa da 016, e o `run.sh` parou na 015. O E2E do repositório está **desatualizado desde a 016**, independentemente da 017.

### 2.3 Cópia B: 001–017 pelo modelo do migrador

**Diferenças em relação à cópia A:**
- as migrations 001–017 vão para uma subpasta do diretório montado;
- um invólucro `000_aplicar_como_migrador.sh` chama `db/bootstrap/aplicar_como_migrador.sh`, a mesma montagem de `RealDatabase`;
- o log do migrador é conferido como em `RealDatabase.conferirModeloDoMigrador`: sem `FALHA`, com `fim … estado=0|0|0` e com `invariantes_016_pos_retirada=OK`.

O compose não mudou.

**Resultado: 6/6, `E2E OK`.** Conferências no banco da própria pilha, antes de derrubá-la:
- migrador: 017 aplicada elevada e retirada, com `estado 0|0|0`;
- hash da 017 aplicada igual ao da R3 (`4ac0cc89…`);
- marcador `nekkora-inicializacao-completa versao=017`;
- 27 funções `identity_*`, a tabela `identities` e 7 gatilhos da 017 (`refuse_local_entry_for_linked_account` e `enforce_user_session_mfa_on_insert`);
- 2 sessões de organização criadas pelo fluxo.

**O que isto valida:** o caminho entre processos, com nginx, Platform, Portal, PostgreSQL e Redis, das **contas não vinculadas**, com a 017 instalada e a fixture adaptada (`CrossProcessE2E.sqlIn`). **Não valida** nenhuma rota de identidade, porque elas não existem.

### 2.4 Proposta para o `run.sh` do repositório (não aplicada)

Proponho levar ao script do repositório, para decisão da responsável:
1. `sh ./mvnw`, ou o bit de execução no `mvnw`;
2. a montagem pelo modelo do migrador, com 001 até a última migration, como na cópia B;
3. a remoção de `key.pem` também em caso de falha (`trap`).

### 2.5 Limpeza

- As duas pilhas foram derrubadas com o comando que o próprio script imprime (`docker compose -p nekkora-e2e down`), **sem** `-v`, conforme a regra do script.
- Ficaram **4 volumes anônimos**, dois de cada rodada, criados às 15:09 e às 15:11 UTC. Não os removi; os identificadores estão no pacote.
- Nenhuma chave privada ficou em `saidas/`.

## 3. Situação das pendências

| Pendência | Situação |
|---|---|
| intermitência de `ReducingAccessRoutesIT` | causa demonstrada; ajuste de preparação validado. **Aguarda revisão** |
| `CrossProcessE2E` | **executado**: 6/6 com 001–017 pelo migrador (cópia de rascunho). O script do repositório continua desatualizado (§2.4) |
| `FixtureRunnerIT` com o bash 3.2 | inalterado: passa com bash compatível (relatório do vínculo, §4) |
| semeaduras | não adaptadas; continuam pendentes antes da implantação da 017 |
| regressão completa | não reexecutada nesta rodada. Continua descrita como **infraestrutura aprovada com pendências registradas** |
