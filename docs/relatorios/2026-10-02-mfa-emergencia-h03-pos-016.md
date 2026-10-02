# H03 pós-016: expectativas por perfil do modelo de migrations (F-R2b1c-1), para revisão

**ESTADO: PARA REVISÃO.**
- Se aprovado, o F-R2b1c-1 fica **CLOSED na ferramenta atual**.
- O resultado histórico do `ensaio-016c` (**H03 = FALHA**) **não muda**: é evidência daquele momento, com a ferramenta de então. Esta entrega não reescreve o passado.
- O P4 continua PASS.

**Data:** 2026-10-02, das 00:55Z às 01:55Z.

**Evidências:** `ops/homologacao/saidas/h03p016-20261002T005508Z/`, fora do Git.

## 1. O que mudou

**Regra:** o H03 deixa de supor que todo ambiente termina na 015.
- O **modelo** é derivado da cópia de migrations do ambiente e conferido contra `HOMOLOG_ATE`.
- Cada modelo tem **um perfil declarado num só lugar**: `ops/homologacao/testes/h03_perfis.sh`.
- Modelo sem perfil, cópia inconsistente ou divergência com `HOMOLOG_ATE` resulta em **FALHA fechada**: o H03 não avalia nada.
- Modelo novo (017…) significa um perfil novo no mesmo arquivo, e não um `if` no H03.

| Incompatibilidade (F-R2b1c-1) | Correção |
|---|---|
| **Referência:** antes era instalada por superusuário via `initdb.d`; a 016 rev5 a recusa | Referência descartável instalada pelo **caminho suportado do produto**: `db/bootstrap/aplicar_como_migrador.sh`, com o migrador dono do banco, janelas SUPERUSER/BYPASSRLS só nas versões declaradas, retirada conferida e §19 da 016 depois da retirada. **Exige o marcador `nekkora-inicializacao-completa versao=<modelo>`**; sem ele, referência inválida e H03 FALHA. É o mesmo modelo de `docker-compose.modelo.yml` e das bases Java. Nenhum superusuário instala migrations na referência |
| **Políticas da 014:** a regra "exatamente uma política" | **Perfil 016:** a regra da 014 é trocada pelo **conjunto exato** do modelo, `ops_function_context,tenant_request_context`. Não é relaxamento: política ausente, extra, trocada ou RLS desligada falham. O trecho original tem de existir exatamente uma vez; senão, o perfil falha. **Perfil 015:** mantém a regra original, sem mudança |
| **Papéis com senha:** lista fixa do desenho 015 | Lista **por perfil**. A 016 acrescenta `nekkora_ops_ca` e `nekkora_ops_intake` **só no perfil 016** |
| Invariantes | Perfil 015: 012 §15, 013 §8, 014 §8 e 015, sem mudança (4 blocos). Perfil 016: os mesmos com a troca na 014, **mais a §19 da 016** (5 blocos). O H03 não pula a 016 |
| Diagnóstico | Cada conferência que falha registra o motivo em `h03_nota.txt`. Antes, só "ver h03_*" |

**Arquivos** (diff fechado em `P_patches/h03p016.patch`; aplicado sobre o estado anterior, reproduz os 4 arquivos):

| Arquivo | Tipo | sha256 LF antes → depois |
|---|---|---|
| `ops/homologacao/testes/executar.sh` (só a função `h03`) | +44 −35 | `5ff9deec…cb9e6` → `2eedc65f73102203c4198fff6aa9840dd394d7b003aa2f6e3a7423202b7c0b0f` |
| `ops/homologacao/testes/h03_perfis.sh` | novo, +116 | → `b552099af9995569714b932e5b4a4583be4fd7ca040b9f2e39952b98f0071b68` |
| `ops/homologacao/testes/test_h03_perfis.sh` | novo, +59 | → `01f53e521a133af56cb2038c6318d3888b9645308bcf9535f2975985bc901d26` |
| `ops/homologacao/testes/validar_config.sh` | +18 (seção 15) | `69f6bef2…1baae` → `1a94d256c591029f445fc8309f15b2811d9e96a43ff4072c13f27aba854f9d24` |

**Não mudaram:** `h03_estado.sql`, `catalogo.py`, `aplicar_como_migrador.sh`, migrations 014/016 rev5, `elevacao.txt` operacional, `implantar.sh` e `criar`.

## 2. Medição antes da mudança (`T_medicao/`)

Feita em bases descartáveis pelo modelo suportado:
- 001–015: os 4 blocos passam.
- 001–016: **só a §8 da 014 falha**, pela regra de uma política. 012, 013, 015 e a §19 da 016 passam.
- A referência pelo modelo suportado reproduz o catálogo do ambiente `ensaio-016c`, comparado com o lado do ambiente já capturado pelo H03 daquele ensaio, sem consultar o ambiente preservado: **0 linhas de diferença fora da tabela do Flyway**.

## 3. Provas

### 3.1 Sem Docker: `test_h03_perfis.sh`, 25/25

- **Modelo:**
  - 001–015/015 → `015`; 001–016/016 → `016`.
  - Recusas: cópia 016 com `ATE=015`; cópia 015 com `ATE=016`; `ATE` vazio; lacuna; 017 sem perfil; versão repetida; arquivo estranho; cópia vazia; cópia inexistente.
- **Perfis:** listas de papéis e número de blocos; perfil desconhecido recusado; invariantes sem perfil recusados.
- **Montagem:**
  - perfil 015 com a regra original;
  - perfil 016 com 5 blocos, a troca **exata** e o restante da §8 da 014 idêntico;
  - recusas com âncora ausente, âncora duplicada, sem a §19, 016 ausente da cópia e transformação desconhecida.

### 3.2 Bases descartáveis pelo modelo suportado (`T_base/execucao2`: 12/12 OK)

- **Invariantes por perfil:**
  - 015 sobre a base 015 e 016 sobre a base 016: OK.
  - Recusados: perfil 015 sobre a base 016 (regra antiga); perfil 016 sobre a cópia 015; perfil 016 sobre a base 015.
- **Mutações numa cópia da base 016,** cada uma com o motivo apontando a tabela mutada:
  - política obrigatória ausente;
  - política extra numa tabela da 014;
  - política da 014 trocada;
  - RLS desligada.
- **`h03_referencia`:**
  - 015 e 016 pelo modelo: OK (catálogos de 3.480 e 3.677 linhas).
  - **Referência incompatível** (a 016 sem a janela SUPERUSER): recusada. A instalação parou na 016, sem marcador, motivo registrado.
- **Execução 1:** ficou preservada com erro de harness (o código 3 do psql era comparado com 1); as recusas já estavam corretas.

### 3.3 Ambientes reais pelo executor (clone de lab `4e5b097`, `autocrlf=false`, 24 caminhos)

O clone tem os 22 aprovados com `executar.sh` e `validar_config.sh` nas versões novas, mais os 2 arquivos novos. A árvore e o clone são idênticos nesses 4 arquivos.

**Compatibilidade 015 (`ensaio-h03a`, `criar` com `HOMOLOG_ATE=015`):**
- `HOMOLOG_TESTES=h03 testar` → **H03 OK, perfil H03-015**.
- Referência pelo modelo (marcador `versao=015`); catálogo de 3.475 linhas, **0 diferenças fora do Flyway**; 4 blocos de invariantes sem erro.

**Compatibilidade 016 (`ensaio-h03b`, caminho suportado):**
- `criar` com `HOMOLOG_ATE=016` e lista de lab (014 BYPASSRLS e 016 SUPERUSER vinculadas), `implantar` (janela da 016), `concluir-criacao`.
- **H03 OK, perfil H03-016.**
- Referência criada pelo modelo (marcador `versao=016`).
- Políticas pós-016: 5 blocos, inclusive o conjunto exato da 014 e a §19 da 016.
- Papéis pós-016: `…,nekkora_ops_ca,nekkora_ops_intake,…`.
- Catálogo de 3.672 linhas, **0 diferenças fora do Flyway**.

**Controles negativos no `ensaio-h03b`.** Cada mutação foi aplicada, o H03 real executado e a mutação desfeita (`R_runner/h03b/controles.txt`):

| Controle | Resultado |
|---|---|
| N1 política obrigatória ausente (`ops_function_context` em `user_mfa_factors`) | **FALHA** no invariante do perfil 016 ("…: user_mfa_factors") e no catálogo |
| N2 política extra inesperada (`mfa_events`) | **FALHA** no invariante ("…: mfa_events") e no catálogo |
| N3 papel esperado sem senha (`nekkora_ops_intake`) | **FALHA** em papéis com senha × perfil 016 |
| N4 papel inesperado com LOGIN e senha | **FALHA** em papéis com senha (lista com `h03_inesperado`) |
| N5 referência incompatível (script do modelo sem a janela da 016) | **FALHA**: "referência do modelo 016 inválida", referência vazia |
| N6 cópia com 017 sem perfil / N7 cópia com lacuna | **Recusados antes do H03** pela guarda de integridade do executor ("conjunto de migrations … diferente do registrado na criação" → BLOQUEADO; `testar` não roda). É falha fechada anterior. A guarda do próprio H03 para esses casos está coberta em 3.1 |
| N8 modelo divergente de `HOMOLOG_ATE` (`ambiente.env` do ambiente descartável) | **FALHA fechada no H03**: "cópia termina em 016, mas HOMOLOG_ATE=015"; "modelo … não determinado; H03 não avalia nada" |
| Depois das reversões | **H03 OK de novo** (duas vezes), com o catálogo do ambiente **idêntico** ao da prova positiva. `git status` do clone limpo; papel de controle removido; nenhum container de referência restante |

### 3.4 `validar-config`

- **162/0** = 154 + 6 da seção 15 + 2 `bash -n` dos novos `.sh`. Diferença de linhas registrada.
- **Seção 15:**
  - testes do `h03_perfis.sh`;
  - sem a lista fixa de papéis do 015;
  - sem a regra fixa de 1 política, e invariantes só pelo perfil;
  - perfil 016 com a §19 e a troca, perfil 015 com a regra original;
  - referência pelo modelo suportado com marcador e sem migrations no `initdb.d`;
  - modelo não determinado = FALHA fechada.
- **Controles com cópias adulteradas, 7 de 7 reprovados** (`G_validar/controles_secao15.txt`):
  - volta da lista fixa;
  - volta da regra de 1 política no H03;
  - perfil 016 sem a troca;
  - H03 pulando a 016;
  - referência por superusuário via `initdb.d` e sem marcador;
  - fail-open com perfil padrão;
  - detecção ignorando `HOMOLOG_ATE`.
- A primeira tentativa do C5 foi inválida (erro de sintaxe no script de adulteração) e foi refeita. Registrado no arquivo.

## 4. Inventário e escopo

- **B12 início × fim: OK**, idêntico por ID.
- **Recursos desta missão, todos removidos por ID:**
  - containers das medições e bases;
  - `ensaio-h03a` e `ensaio-h03b`, com os volumes anônimos do Redis;
  - os segredos dos dois ambientes, registrando só a contagem de arquivos;
  - os containers de referência, removidos pelo próprio H03.
- **Clone de lab** `../lab-h03p016-20261002T010356Z/nekkora-platform` (`4e5b097`, sem remoto): preservado como proveniência para a revisão.
- **`ensaio-016a`, `ensaio-016b`, `ensaio-016c` e `hml03`:** idênticos ao início da missão (mesmos inícios, 0 reinícios), não tocados.
- **Repositório original:**
  - sem stage, commit ou push;
  - `elevacao.txt` operacional, 014 e 016 rev5 inalterados;
  - na árvore de trabalho entram os 4 arquivos do §1 e este relatório.
- **Limite do pacote:** os segredos de `ensaio-h03a` e `ensaio-h03b` foram descartados antes do empacotamento. Por isso, as saídas brutas desses ambientes ficam **fora** do pacote; só entram cópias selecionadas dos registros do H03.

## 5. Situação proposta

| Item | Estado proposto |
|---|---|
| F-R2b1c-1 | **CLOSED na ferramenta atual**, se aprovado |
| Histórico do `ensaio-016c` | Continua com **H03 FALHA** (ferramenta anterior) |
| P4 | Continua **PASS** |
| Pendências | Manifesto de fontes do Maven (próxima, curta); H02 × lista histórica; transporte em Windows; 5b; P5; portão de ativação 014/016 |

## Adendo MFA-CONSOL-1 (2026-10-02): registro da decisão da revisão

*Acrescentado na missão MFA-CONSOL-1 (E0.1). O corpo acima não foi alterado; o pacote citado continua sendo a evidência daquele momento. Origem das citações: mensagens da responsável na sessão Claude Code `d31e2723-029b-47ee-a98d-bf0f4f24ed47`, horário UTC. Só trechos de decisão.*

| Item | Registro |
|---|---|
| Entrega | H03 pós-016 (F-R2b1c-1) |
| Pacote | `homologacao-016-h03p016-20261002T013045Z.zip`, `31e6dffb40f4be9bcc11be9b47ee2027865a630c003cc5bf7c7ce541ded2ec7c` (244/244) |
| Decisão | 2026-10-02T01:49:48Z: "Aprovo o gate do H03 pós-016. O F-R2b1c-1 passa para CLOSED na ferramenta atual, sem qualquer alteração no registro histórico do ensaio-016c." `validar-config` 162/0 |
| Aceitos | N6/N7 recusados por guarda anterior mais geral (fail-closed); 3 UUIDs como constantes sintéticas |
| Incidente | o ZIP canônico foi alterado depois da aprovação (+1 entrada). O alterado está preservado como `…INCIDENTE-alterado-c54216c9.zip`; o nome canônico foi restaurado para `31e6dffb…` com autorização de 2026-10-02T03:42:53Z ("Isso não reabre o gate H03 nem muda o P4") |
| Limitação | H03 executado pelo runner só com `HOMOLOG_TESTES=h03`, ainda não numa suíte `testar` completa com as demais ferramentas atuais |
