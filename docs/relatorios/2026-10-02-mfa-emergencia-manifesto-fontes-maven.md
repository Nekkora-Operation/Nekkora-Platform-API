# Manifesto de fontes da regressão Maven: evidência verificável da árvore que sustenta um resultado

**ESTADO: PARA REVISÃO.**
- Ferramenta nova, sem efeito em ambientes.
- Não toca Docker, H03, H02, migrations, ensaios preservados nem `hml03`.
- Sem stage, commit ou push.

**Data:** 2026-10-02.

**Evidências:** `ops/homologacao/saidas/maven-manifesto-<instante>/`, fora do Git.

## 1. Problema e objetivo

**Problema:** a C2 (256/0) não registrou quais fontes a produziram. Para reaproveitá-la no `ensaio-016c`, foi preciso reconstruir a prova depois do fato, com comparação de árvores, reversão do patch e *mtime*.

**Objetivo:** toda execução relevante passa a produzir, **antes** de o Maven começar, um retrato verificável das fontes de entrada. **No fim**, confere de novo as mesmas fontes. Se uma entrada mudar durante a execução, o resultado de testes pode existir, mas a evidência termina como `FONTES_ESTAVEIS=FALHA` e `REAPROVEITAMENTO=PROIBIDO`.

## 2. A ferramenta

### Uso

```
ops/homologacao/regressao_maven.sh <diretório de saída novo> [argumentos do mvnw; padrão: -B verify]
python ops/homologacao/lib/fontes_maven.py verificar <diretório da execução> <raiz>   # reaproveitar depois
python ops/homologacao/lib/fontes_maven.py comparar <manifesto a> <manifesto b>
```

### Diretório de saída (nunca sobrescrito: se existir, recusa sem rodar o Maven)

| Arquivo | Conteúdo |
|---|---|
| `fontes.sha256` | **Antes do Maven.** sha256 dos **bytes no disco** de cada arquivo de entrada, no formato do `sha256sum` (`sha256sum -c` confere; caminhos com espaço funcionam). Ordem determinística: caminho relativo com `/`, ordenado por bytes UTF-8 |
| `fontes_lf.sha256` | Mesmo conjunto, com conteúdo normalizado para LF, para comparar checkouts com `core.autocrlf` diferente (o caso clone × árvore da C2) |
| `contexto.txt` | Início; raiz; HEAD e ramo; árvore limpa ou suja; entradas com alteração não commitada; **entradas não rastreadas que estão no manifesto** (contagem e lista em `entradas_nao_rastreadas.txt`; elas **entram**, nunca são ignoradas); `core.autocrlf`; número de arquivos; sha256 de `fontes.sha256` e de `fontes_lf.sha256`; comando `api/mvnw …`; versão do Java |
| `maven.txt` | Saída completa do `mvnw` |
| `fontes_fim.sha256` e `fontes_diferencas.txt` | **Depois do Maven:** o mesmo manifesto recalculado e a comparação, com `ALTERADO`, `ADICIONADO` e `REMOVIDO` por caminho |
| `resultado.txt` | Ver abaixo |
| `manifesto.sha256` | Integridade dos arquivos da própria evidência |

### `resultado.txt`

- `RESULTADO_TESTES`: OK ou FALHA, pelo código do Maven e pelos totais do surefire. Os totais somam só as linhas de resumo por módulo, nunca as de classe.
- `TESTES`, `FALHAS`, `ERROS`, `IGNORADOS`.
- `FONTES_ESTAVEIS`: OK ou FALHA, com os caminhos.
- **`REAPROVEITAMENTO=PERMITIDO`** somente com testes OK, **ao menos um teste executado** e fontes estáveis. Senão `PROIBIDO`, com `MOTIVO`.

`verificar`: confere a integridade da evidência e se a **árvore atual** ainda é a do início. Imprime `REAPROVEITAMENTO_NA_ARVORE_ATUAL=PERMITIDO` só se a execução permitia e a árvore é idêntica. Evidência adulterada ou árvore diferente dá `PROIBIDO`.

### Conjunto de entrada

Declarado em `fontes_maven.py`, derivado do que o código lê; mudança só com revisão:
- `api/pom.xml`, `api/mvnw`, `api/mvnw.cmd`, `api/.mvn/**`.
- `pom.xml` e `src/**` de **cada módulo de `<modules>`** do `api/pom.xml`. É lido do pom: módulo novo entra sozinho.
- `db/migrations/**` e `db/bootstrap/**`: `RealDatabase` e `OpsBanco` copiam as migrations e o script do modelo.
- `ops/homologacao/**`: os testes de `nekkora-ops` e `nekkora-platform` executam `homologacao.sh`, `lib/`, `sql/`, `postgres/*.modelo`, `testes/h016.sh`, `testes/cliente.py` e `lib/indice_caixa.py`. Fica a árvore inteira, como superconjunto conservador.
- **Excluídos em qualquer nível:** `target/`, `node_modules/`, `__pycache__/`, `*.pyc`, e `ops/homologacao/saidas/` e `ops/homologacao/evidencias/`, que são saídas.
- **`api/e2e` fica fora:** o surefire só inclui `*Test`/`*IT`, e o E2E exige ambiente próprio.
- Caminho com quebra de linha ou barra invertida é **recusado** (não representável sem ambiguidade). Espaço é permitido.

**Sem mtime:** o manifesto usa só conteúdo, verificado pelo `validar-config`.

**Limite declarado:** uma mutação feita **e desfeita** durante a execução, antes do fim, não é detectada, porque o manifesto compara início e fim.

## 3. Provas

### 3.1 Testes: `test_fontes_maven.py`, 18/18

Rodam em repositório **sintético** temporário, com Git próprio e um `api/mvnw` falso. Não rodam Maven real nem Docker.

**Manifesto:**
- conjunto com exclusões (`target`, `node_modules`, `saidas`, `evidencias`, `__pycache__`, `.pyc`, arquivo fora do conjunto);
- **arquivo com espaço no nome** incluído;
- **arquivo não rastreado** incluído;
- **ordem determinística**: duas gerações idênticas e o mesmo manifesto num repositório criado na ordem inversa;
- bruto × LF (CRLF detectado);
- **compatível com `sha256sum -c`**, inclusive o caminho com espaço;
- `comparar` com ALTERADO, REMOVIDO e ADICIONADO;
- recusas de caminho com quebra de linha ou barra invertida, de `api/pom.xml` sem `<modules>` e de manifesto ilegível.

**Execução:**

| Caso | Resultado |
|---|---|
| estável | `OK/OK/PERMITIDO`; totais 5 testes e 1 ignorado (a linha de classe não conta). **O `mvnw` falso confirmou que o manifesto já existia ao iniciar.** Contexto completo; não rastreado listado; `verificar` → PERMITIDO |
| **mutação durante** | `FONTES_ESTAVEIS=FALHA`, `ALTERADO api/m1/src/main/java/A.java`, `PROIBIDO`. `RESULTADO_TESTES=OK` continua registrado |
| **arquivo adicionado durante** | `FALHA`, `ADICIONADO …/Nova.java`, `PROIBIDO` |
| **arquivo removido durante** | `FALHA`, `REMOVIDO db/migrations/002_y.sql`, `PROIBIDO` |
| saída em `target/` durante a execução | não é entrada: `FONTES_ESTAVEIS=OK` |
| falha de testes | `FALHA/OK/PROIBIDO` |
| nenhum teste executado | `PROIBIDO` ("nenhum teste executado") |
| diretório de saída existente | recusado com código 3; **o Maven não roda** |
| **mutação depois** | `verificar` → `ARVORE_ATUAL=DIFERENTE` (`ALTERADO db/migrations/001_x.sql`), `PROIBIDO` |
| evidência adulterada (`PROIBIDO` trocado por `PERMITIDO`) | `INTEGRIDADE_EXECUCAO=FALHA`, `PROIBIDO` |

### 3.2 Integração com o Maven real (`R_real/validate`)

`regressao_maven.sh … -B validate`, sem testes, porque o objetivo não é uma regressão nova:
- **209 arquivos** no manifesto, estáveis do início ao fim.
- Contexto: HEAD `dd74a8f`; árvore suja; 20 entradas alteradas e **53 não rastreadas, todas no manifesto**; `core.autocrlf=true`; JDK 21.
- `REAPROVEITAMENTO=PROIBIDO`, motivo "nenhum teste executado", como deve ser.
- `verificar`: integridade OK e árvore idêntica.

### 3.3 `validar-config`

- **170/0** = 162 + 6 da seção 16 + 2 de sintaxe Python da seção 1 (`fontes_maven.py` e `test_fontes_maven.py` novos). Diferença de linhas registrada.
- **Seção 16:**
  - `bash -n` do runner;
  - testes do manifesto e do runner;
  - **ordem manifesto → Maven → manifesto**, por linha, com comparação;
  - regra de reaproveitamento;
  - sem mtime, ordem por bytes, conjunto declarado e recusa de caminho não representável;
  - saída nunca sobrescrita.
- **Erro de redação na primeira execução:** a verificação "sem mtime" casava a palavra "mtime" da própria docstring. Corrigido para padrões de uso em código (`st_mtime`, `getmtime`, `os.stat(`, `scandir`…). A execução preservada está em `G_validar/validar_execucao1_ancora_mtime_na_docstring.txt`.

**Controles com cópias adulteradas** (`G_validar/controles_secao16.txt`): ver §3.4.

### 3.4 Controles da seção 16

Cópias adulteradas numa árvore temporária; o original não foi alterado. A cópia sem adulteração passa 6/6. **7 de 7 reprovadas:**

| Cópia adulterada | Reprovada por |
|---|---|
| C1 manifesto "inicial" gerado depois do Maven | testes (o `mvnw` falso não encontra o manifesto) e ordem por linha (59 < 58) |
| C2 sem recálculo e comparação no fim | testes e verificação de ordem/comparação |
| C3 fontes instáveis sem proibir o reaproveitamento | testes e regra de reaproveitamento |
| C4 manifesto por **mtime** em vez de conteúdo | testes e verificação "sem mtime" |
| C5 ordem não determinística | testes e verificação de ordem por bytes |
| C6 diretório de saída existente sobrescrito | testes e verificação de não sobrescrita |
| C7 nenhum teste executado tratado como reaproveitável | testes e regra de reaproveitamento |

## 4. Arquivos

Diff fechado em `P_patches/manifesto-maven.patch`; aplicado sobre o estado anterior, reproduz os 4 arquivos.

| Arquivo | Tipo | sha256 LF |
|---|---|---|
| `ops/homologacao/lib/fontes_maven.py` | novo (+140) | `49b3e2d5005c50c3ab937dfc7471b0214c2e0469090349c39fa28f36757861bc` |
| `ops/homologacao/regressao_maven.sh` | novo (+90) | `5f137a25eaa85442bf97c6427d2760bfd98b434bd9e589b181d91301d4926a6a` |
| `ops/homologacao/testes/test_fontes_maven.py` | novo (+215) | `033bc5915cb80e8270bb355e8eaa611ed1b9f75f6b3c3fa29bf6ca28e3e2f94f` |
| `ops/homologacao/testes/validar_config.sh` | +17 (seção 16) | `1a94d256…9d24` → `378a5f9eeb44d241335231fad94fa7e2a47c768e02819832a361583db4191092` |

**Não mudaram:** H03, H02, migrations, `elevacao.txt`, `implantar.sh`, `criar` nem código Java.

## 5. Escopo e uso daqui em diante

- **Recursos:** nenhum container, rede, volume ou imagem criado. Os testes usam repositórios temporários removidos ao fim. `ensaio-016a/016b/016c` e `hml03` não foram tocados.
- **Repositório:** sem stage, commit ou push. Na árvore de trabalho entram os 4 arquivos do §4 e este relatório.
- **Proposta de regra:** regressões que sustentem um portão passam a ser executadas por `regressao_maven.sh`. O reaproveitamento só vale com `REAPROVEITAMENTO=PERMITIDO` **e** `verificar` dando `PERMITIDO` na árvore do portão.

## Adendo MFA-CONSOL-1 (2026-10-02): registro da decisão da revisão

*Acrescentado na missão MFA-CONSOL-1 (E0.1). O corpo acima não foi alterado; o pacote citado continua sendo a evidência daquele momento. Origem das citações: mensagens da responsável na sessão Claude Code `d31e2723-029b-47ee-a98d-bf0f4f24ed47`, horário UTC. Só trechos de decisão.*

| Item | Registro |
|---|---|
| Entrega | manifesto de fontes da regressão Maven |
| Pacote | `homologacao-016-maven-manifesto-20261002T023053Z.zip`, `22a394271345744eb7d6af524d82d3b4b96a9f7aa9dc6a266b3b14e395ec24cc` (34/34) |
| Decisão 1 | 2026-10-02T03:22:54Z: ferramenta, testes e `validar-config` 170/0 aprovados; "Gate do pacote de evidência HOLD — ZIP Maven correto não anexado nesta entrega" |
| Decisão 2 | 2026-10-02T03:42:53Z: "Gate Maven PASS completo", depois de a revisão extrair o pacote correto de dentro do ZIP H03 alterado |
| Regra aprovada | "regressao_maven.sh obrigatório" para regressão que sustenta portão; reaproveitamento só com `resultado.txt` = PERMITIDO **e** `verificar` = PERMITIDO |
| Limitação aceita | mutação feita e desfeita durante a execução não é detectada (declarado) |
| Uso posterior | primeira regressão com testes por esta ferramenta: WIN-TRANSPORT-1 (verify1 FALHA, verify2 PERMITIDO) |
