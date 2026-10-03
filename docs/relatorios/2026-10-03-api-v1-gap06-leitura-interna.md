# GAP-06: leitura interna de workspaces, automações, versões, execuções e eventos (ROTA-L01 a L08) e medição do DC-07

**ESTADO: APROVADA TECNICAMENTE** (parecer de 2026-10-03), no escopo de leitura interna, junto com a medição do DC-07. O desempenho da L06 fica como **pendência conhecida**, sem reprovar a missão: 200 e 500 ms eram referências.
- Não houve stage, commit nem push.
- Nenhum índice, migration, privilégio, configuração ou segredo foi criado ou alterado.
- DC-14, commits e publicação continuam separados.

**Data:** 2026-10-03.

**Base:**
- preparação aprovada tecnicamente (`2026-10-03-api-v1-gap06-preparacao.md`, revisão 1), com os dois detalhes do parecer incorporados: M03 na medição e custo do `requireConsistent` medido à parte;
- **decisões da responsável:** DC-01 a DC-13 e DC-15;
- código: HEAD `2431648f`, mais GAP-01, GAP-02 e GAP-03, todos sem commit.

**Evidências:** `ops/homologacao/saidas/gap06-20261003T153142Z/`.

## 1. O que foi entregue

| Componente | Arquivo | Papel |
|---|---|---|
| Autorização de recurso | `access/WorkspaceAccess.java` (alterado) | `requireAutomation` e `requireExecution`: resolvem o recurso **na organização da rota** e aplicam `requireWorkspace`. 404 idêntico para inexistente, de outra organização ou sem acesso. Consistência do catálogo conferida em todos os casos (500 com log) |
| Leituras | `web/ProductReads.java` (novo) | autorização e consulta das 8 rotas, **usadas pelo controlador e pela medição** (mesmo SQL, mesmos parâmetros). O SQL das listas fica em montadores estáticos (`workspacesSql`, `automationsSql`, `versionsSql`, `executionsSql`, `eventsSql`) |
| Rotas | `web/ProductReadController.java` (novo) | L01–L08. Ordem 401 → 400 (parâmetros, período, cursor) → 403 (conta externa) → 404 |
| Rótulos | `web/Labels.java` (alterado) | versão, status e motivo de execução, origem e tipo de evento. Código desconhecido → 500 com log |
| M03 | `web/AdminReadController.java` (alterado, GAP-03) | **só refatoração:** o SQL da M03 foi extraído para `clientsSql(…)`, para a medição usar o mesmo texto. Comportamento inalterado; os testes da GAP-03 passam |

**Exclusões confirmadas por teste**, pelas chaves das respostas: sem `command_json`, `command_snapshot`, `payload`, `errorSummary`, `pid`, `exitCode`, Worker/Agent, criador da automação, `request_key`/`fingerprint` e `authorizedBy` (DC-08 a DC-11).

**Solicitante** (DC-09):
- manual → `{membershipId, displayName}`;
- agendada → **nulo**. A execução agendada do teste foi criada pelo fluxo real da 009 (agenda, revisão, ocorrência, `process_schedule_occurrence`).

## 2. Testes

| Classe | Testes | O que confere |
|---|---|---|
| `ProductReadMatrixIT` (novo) | 1 | **tabela V nas oito rotas**, com expectativa por ator, rota e recurso, nunca uma resposta única para a matriz. **Atores:** 7 internos (ORG_ADMIN, ORG_ADMIN + VIEWER em W3, WORKSPACE_ADMIN, OPERATOR, VIEWER, revogada, sem papéis). **Recursos:** W1, W2, W3 arquivado, organização B e inexistente. **Expectativas:** listas sem workspace no caminho (L01, L06) devolvem só os visíveis, ou 200 vazio; detalhes, subordinados e filtros da L06 dão 200 ou **404 com corpo idêntico** (`{"error"}`); conta externa → **403**; sem sessão e sessão de outra organização → **401** |
| `ProductReadRoutesIT` (novo) | 8 | ver a tabela abaixo |

**`ProductReadRoutesIT` por tema:**

| Tema | O que confere |
|---|---|
| Campos e exclusões | campos exatos de L01–L08; `client` nulo; `latestPublishedVersion` = **maior publicada** com rascunho posterior, e nulo sem publicada; L05 descendente e paginada |
| Execução | solicitante manual e agendada; `retryOfExecutionId`; **`durationMs`** = 1.234 ms para 1,234567 s (truncado), nulo em andamento, pendente e cancelada antes do despacho |
| Eventos (L08) | paginados por `sequence`, sem `payload`; lista vazia → 200 |
| Período (L06) | `[from, to)` com os limites; só `from`; só `to`; `to = from` em fusos diferentes → vazio; **366 dias exatos → 200, + 1 ms → 400**; `to < from`, sem fuso e inválido → 400 |
| Filtros (L06) | `workspaceId`, `automationId` e `status`; incompatíveis → vazio; `status` inválido, repetido e acima de 6 → 400; UUID malformado → 400; inexistente → 404; `automationId` sem acesso → 404 |
| Ordenação composta (L06) | 7 execuções com datas que decrescem com o id, empates e passos de 1 µs; todas as páginas com `limit` 1, 2, 3 e 7; `createdAt` em µs |
| Nomes (L01, L03) | ordem binária; prefixo de 48 pontos de código com desempate por id contrário ao sufixo; nomes de 1.000 caracteres (ASCII, controles, emoji) no fim da página; cursor ≤ 872 |
| Mudança concorrente (§5.4) | renomear para antes ou depois do cursor; execução mais nova não aparece; automação inserida depois do cursor aparece |
| Revogação (§5.5) | itens de W1 somem de L01 e L06; W1 no caminho ou no filtro → 404; acesso direto (L02, L04, L05, L07, L08) → 404 idêntico; W2 segue acessível; ORG_ADMIN revogado → lista vazia e 404; `revoke-all` → 401 |
| Cursores entre rotas reais | cursor da L03 de W1 usado em W2, na L01 e na L05; M03 ↔ L01; L05 de outra automação; L06 com filtros trocados; L08 de outra execução → 400, com controles positivos |
| DC-05 × DC-12 | o **mesmo** `workspaceId` de outra organização dá 200 vazio na M02 e 404 na L06; eventos de execução de outra organização → 404 (o portal externo devolve vazio; diferença documentada) |
| UUID malformado | 400 em L02–L08, **inclusive para conta externa** (400 antes de 403) |

**Mensagens de falha:** só status, código de erro, quantidade de itens e presença do `nextCursor`. Nunca o corpo completo nem o cursor.

**Execuções:**

| Execução | Resultado |
|---|---|
| Focalizada (`testes_focalizado.txt`; GAP-02, 03 e 06) | **56/56** |
| **Mutações** (`mutacoes_resumo.txt` e `mutacoes_resumo_M3_….txt`) | **7/7 detectadas**, todas restauradas com sha idêntico e 0 valores com formato de token nos logs. Ver a tabela abaixo |
| `validar-config` (`validar/`) | 184/0, nomes idênticos à referência |
| Regressão completa | §5 |

**Mutações:**

| Mutação | Testes que falharam |
|---|---|
| M1: `requireAutomation` sem a regra de workspace | matriz |
| M2: L06 sem o predicado de visibilidade | matriz |
| M3: eventos com lista vazia em vez de 404 | matriz + DC-05×DC-12 + revogação |
| M4: `durationMs` contra o relógio atual | campos |
| M5: L06 ordenada só pelo id | ordenação composta |
| M6: `to` inclusivo | período |
| M7: `requireExecution` sem a regra de workspace | matriz |

**Desvio do auxiliar, registrado** (`mutacao_M3_tentativa1_SELETOR_INVALIDO/`):
- na primeira rodada, a M3 **não executou testes**: o seletor `-Dtest` juntava duas classes com `+`, que o surefire só aceita entre métodos da mesma classe;
- o critério endurecido classificou o caso como **não detectada**, em vez de contar como sucesso;
- refeita só a M3 com o seletor correto (vírgula entre classes): 3 testes falharam e a mutação foi detectada.

## 3. DC-07: medição (`medicao_dc07/`)

**Como foi medido:**
- **Execução:** a classe `MedicaoDc07` roda **fora da regressão** (nome que não casa com `*Test`/`*IT`), só por nome explícito.
- **Banco:** o mesmo PostgreSQL descartável do teste.
- **Consultas:** mesmo código das rotas (`ProductReads` e `AdminReadController.clientsSql`), mesmo SQL e parâmetros, papel `nekkora_platform`, RLS e contexto organizacional ativos (`OrgTransactions.inOrganization`).
- **Fontes medidas:** `medicao_fontes.sha256` bate com as fontes finais (4/4, `medicao_x_fontes_finais.txt`).

**Tentativa 1 falhou na massa, não no produto** (`medicao_tentativa1_FALHA_massa/`):
- causa: o Worker de apoio aos eventos foi criado sem os limites da 010 configurados;
- nenhum tempo foi produzido;
- correção: configurar os limites antes;
- **medição válida:** 15:43Z–15:58Z.

**Ambiente** (`ambiente.txt`, `maquina_hospedeira.txt`):

| Item | Valor |
|---|---|
| Banco | PostgreSQL 16.15 (`postgres:16-alpine`), driver JDBC 42.7.13, parâmetros padrão: `shared_buffers` 128 MB, `work_mem` 4 MB, `effective_cache_size` 4 GB, 2 workers paralelos por gather, `jit` ligado |
| Docker | Docker Desktop 29.7.2 com 16 CPUs e 15,2 GiB de memória visíveis à VM |
| Máquina | AMD Ryzen 7 8700F (8 núcleos, 16 lógicos), 31,2 GB, Windows 11 Pro |
| Concorrência registrada | **7 outros contêineres `postgres:16-alpine`** (ambientes de homologação preservados) rodavam na mesma VM durante a medição. Fica registrado como fator de ruído |

**Massa efetiva** (`volumes.tsv`):

| Item | Organização medida | Organizações O2 e O3 |
|---|---|---|
| Workspaces | 200 | 20 cada |
| Automações | **20.124** (o quente com 5.000; 76 em cada um dos outros 199) | 1.520 cada |
| Versões | 20.124 (uma publicada por automação) | — |
| Execuções | **1.000.000** em 12 meses; **300.000 no workspace quente**. Status: 800.000 concluídas, 100.000 falhas, 50.000 canceladas, 50.000 pendentes | 100.000 cada |
| Eventos | 50.000 (5 em cada uma de 10.000 execuções) | — |
| Clientes | **5.000** (volume da M03 definido nesta entrega) | 1.000 cada |

- **Carga:** 298 s, com `session_replication_role = replica` só durante a carga em lote (triggers e FKs desligadas; CHECKs ativos), seguida de `ANALYZE`.

**Como ler os resultados:**
- **componente** = rota sem HTTP: contexto, autorização e consulta;
- **consulta** = só o SQL da página;
- **autorização** = só a parte do componente compartilhado;
- **http** = requisição completa, com filtro de sessão, limitador R7 no Redis e serialização, num subconjunto de casos e com intervalo de 600 ms entre requisições;
- 3 execuções de aquecimento, 30 medidas, p95 em ms (posto mais próximo).

**Resultados (p95 em ms):**

| Caso | ORG_ADMIN componente | OPERATOR (3 ws) componente | Referência | HTTP (ORG_ADMIN / OPERATOR) |
|---|---|---|---|---|
| L06 sem filtro, 1ª página | **886,5** | **421,1** | 200 | 861,0 / 479,9 |
| L06 sem filtro, após 10.000 | **814,0** | 462,9 | 500 | 825,6 / 458,1 |
| L06 só `from` (6 meses) | **565,6** | **462,0** | 200 | — |
| L06 workspace quente (300 mil), 1ª página | **301,2** | **299,2** | 200 | — |
| L06 workspace quente, após 10.000 | 315,7 | 315,0 | 500 | — |
| L06 `status` seletivo (pendentes, 5%) | **203,8** | 155,0 | 200 | — |
| L06 período de 30 dias | 182,8 | 190,8 | 200 | — |
| L01 (200 workspaces) | 11,8 | 26,2 | 200 | 34,9 / 31,1 |
| L03 (5.000 automações), 1ª página | 40,1 | 40,3 | 200 | 40,8 / 42,7 |
| L03, após 2.500 | 23,7 | 24,5 | 500 | — |
| L05 | 12,9 | 14,2 | 200 | — |
| L08 | 10,9 | 14,2 | 200 | — |
| M03 (5.000 clientes), 1ª página | 17,3 | — (403) | 200 | 30,2 / — |
| M03, após 2.500 | 17,0 | — | 500 | — |

**Autorização isolada (p95 em ms)** — `requireConsistent` medido à parte, como pedido:

| Chamada | ORG_ADMIN | OPERATOR |
|---|---|---|
| `requireConsistent` | 8,0 | 7,3 |
| `requireWorkspace` | 14,8 | 9,7 |
| `requireAutomation` | 15,9 | 11,6 |
| `requireExecution` | 11,9 | 13,7 |
| `requireAnyOrganizationPermission` | 9,4 | — |

Cada valor é uma transação inteira, com `set_config` e de uma a três consultas pequenas.

**Desvios em relação às referências.** Os valores acima da referência estão em **negrito**:
- primeira página da L06 sem filtro, só com `from` e no workspace quente, para os dois atores;
- página profunda sem filtro do ORG_ADMIN;
- `status` seletivo do ORG_ADMIN, por margem de 3,8 ms.

As demais rotas ficaram entre 10 e 41 ms, de 5 a 20 vezes abaixo da referência.

**Causa, pelos planos** (`planos/`):
- **L06 sem filtro e só com `from`:** sem índice por data, o plano junta **todas** as execuções que passam no filtro e só então faz a ordenação top-N, até 1.000.000 de linhas e cerca de 70 mil blocos lidos do disco por página. O keyset não ajuda sem um índice que sirva a ordenação. O custo cresce com o volume da organização, não com a posição da página: a 1ª página e a profunda custam quase o mesmo.
- **Workspace quente:** a ordenação de cerca de 300 mil linhas usa **disco** (`external merge`, cerca de 20 MB) com o `work_mem` padrão de 4 MB.
- **L03, M03 e L01:** varredura sequencial do conjunto pequeno com top-N em memória. Escala bem no volume medido.
- **Autorização:** o custo isolado é de 7 a 16 ms por chamada. O predicado de visibilidade dentro do SQL da L06 aparece no plano como `InitPlan` de custo desprezível (cerca de 0,1 ms).

**Recomendações (não implementadas; cada uma exige decisão própria):**
1. **Migration própria** com índices para a ordenação da L06, por exemplo `executions (organization_id, created_at DESC, execution_id DESC)` e `executions (workspace_id, created_at DESC, execution_id DESC)`. Remedir com a mesma `MedicaoDc07`.
2. Depois do índice, se o caso do OPERATOR com vários workspaces continuar alto, avaliar o formato da consulta (por exemplo, uma subconsulta ordenada por workspace visível, combinada). Também seria decisão à parte.
3. `work_mem` e demais parâmetros do servidor são configuração do ambiente; fica registrado que a medição usou os valores padrão da imagem.

Nada disso foi aplicado: a decisão registrada é medir, e ficar acima da referência não autoriza criar índice nem alterar migration.

**Natureza da medição:** é evidência de desempenho neste ambiente (contêiner local no Docker Desktop, com outros contêineres ativos). Os números servem para comparar planos e ordens de grandeza.

## 4. OpenAPI

- `openapi-proposta.yaml` passou para `0.6.0-gap06`:
  - as 8 operações L com `x-estado: implementado`;
  - 500 (`CatalogInconsistent`) nas 8;
  - decisões DC-04 a DC-13 registradas.
- **Validação** (`validacao_openapi.txt`): 13 rotas, 163 referências resolvidas e catálogo igual ao das migrations.
- O arquivo continua anexo (DC-14 pendente).

## 5. Arquivos, patches e regressão

- **23 arquivos no total** das GAP-02, 03 e 06 (`depois.sha256`). **Na GAP-06:**
  - novos: `ProductReads`, `ProductReadController`, `ProductFixtures`, `ReadRoutesSupport`, `ProductReadMatrixIT`, `ProductReadRoutesIT` e `MedicaoDc07`;
  - alterados: `WorkspaceAccess`, `Labels` e `AdminReadController` (só extração do SQL da M03).
- **Patches** (`P_patches/`, `verificacao_patch.txt`):

| Arquivo | Conteúdo | Verificação |
|---|---|---|
| `incremental_sobre_gap03r1.patch` | 10 arquivos, +2018/−16 | aplicado sobre as fontes do pacote GAP-03 R1 (`00a34f4e…`), reproduz os 23 arquivos |
| `gap02_gap03_gap06_total.patch` | GAP-02 + 03 + 06 sobre HEAD | reproduz os 23 arquivos; a aplicação reversa confere |

- Todos os arquivos estão em LF.
- **Regressão completa** (`regressao/`, 16:02Z–16:15Z, depois da medição para não disputar recursos):
  - **331 testes, 0 falhas, 0 erros, 0 ignorados** (322 + 9 novos);
  - `REAPROVEITAMENTO=PERMITIDO`; `verificar`: `INTEGRIDADE_EXECUCAO=OK`, `ARVORE_ATUAL=IDENTICA`, `REAPROVEITAMENTO_NA_ARVORE_ATUAL=PERMITIDO`;
  - fontes `4814af9c…7dc6`; 0 `Address already in use`;
  - a `MedicaoDc07` **não** roda na regressão (0 ocorrências no log);
  - os 23 arquivos batem com as fontes da regressão (`fontes_x_regressao.txt`, 23/23).
