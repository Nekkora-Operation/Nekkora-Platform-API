# GAP-03: membros, concessões e clientes (ROTA-M01, M02 e M03)

**ESTADO: PARA REVISÃO.** Não houve stage, commit nem push. Commit e publicação da GAP-01, GAP-02 e GAP-03 são etapas separadas.

**Data:** 2026-10-03.

**Base:**
- contrato GAP-02/03/06, revisão 2;
- preparação aprovada tecnicamente (`2026-10-03-api-v1-gap03-preparacao.md`), com a correção do teste do "ou" incorporada;
- decisões **da responsável**: DC-01, DC-02, DC-03, DC-15 e, em 2026-10-03, DC-04, DC-05 e DC-06;
- código: HEAD `2431648f`, mais GAP-01 e GAP-02 (C1), todos sem commit.

**Evidências:** `ops/homologacao/saidas/gap03-20261003T134611Z/`.

## 1. O que foi entregue

| Componente | Arquivo | Papel |
|---|---|---|
| Permissão organizacional compartilhada | `access/WorkspaceAccess.java` (alterado) | `hasOrganizationPermission` e `requireAnyOrganizationPermission` ("ao menos uma"). Passam pela **mesma checagem de consistência da C1** antes de decidir (500 com log) e aceitam só permissões de escopo organização. A fonte de permissões disponíveis é injetável **só no teste do componente**; em produção vem das atribuições organizacionais do banco |
| Javadoc | `access/WorkspaceAccess.java` | ressalva da C1 aplicada: os caminhos implementados usam `visibleWorkspacesQuery`; a constante é pública e **não** impede uso direto |
| Rótulos | `web/Labels.java` (novo) | status de conta, vínculo, concessão e cliente/workspace. Código desconhecido no banco → 500 com log. Filtro desconhecido, vazio, repetido ou acima do máximo → 400 |
| Rotas | `web/AdminReadController.java` (novo) | **M01** `GET /members` (`org.manage`); **M02** `GET /client-grants` (`external_access.manage`); **M03** `GET /clients` (`org.manage` **ou** `external_access.manage`). Ordem das verificações: 401 → 400 → 403. Leitura de `limit + 1` |

**Detalhes das rotas:**
- **M01:** `mfaConfigured` é só o booleano (DC-04): fator com `confirmed_at` preenchido e `disabled_at` nulo. `organizationRoles` traz só as atribuições ativas, com o código validado no catálogo.
- **M02:** a exceção do DC-05 sai da própria consulta (RLS por organização + filtro), **sem** consulta de existência e sem 404.
- **M03:** ordena por `left(name, 48) COLLATE "C"` + id, com a **mesma expressão** no `ORDER BY` e na página seguinte (§1.5.1). A resposta traz o nome completo.
- Nenhuma migration, permissão de banco, configuração, segredo ou rota existente foi alterada.

## 2. Testes

| Classe | Testes | O que confere |
|---|---|---|
| `access/OrganizationPermissionTest` (unitário) | 5 | **"ou" com o método real e as duas permissões exigidas**, variando as disponíveis: só `org.manage` → permite; só `external_access.manage` → permite; ambas → permite; nenhuma, ou só permissões não exigidas → 403. Também cobre rotas de uma permissão e erro de programação para permissão de workspace, código desconhecido ou nenhuma exigida |
| `WorkspaceAccessIT` (+1) | 5 | permissão organizacional com a **fonte real** (banco, `nekkora_platform`): ORG_ADMIN tem as duas, OPERATOR nenhuma (403). Papel organizacional fora do catálogo com `org.manage` → 500. ORG_ADMIN válido com papel de workspace inconsistente → 500 (política do vínculo inteiro). 3 logs conferidos |
| `AdminReadRoutesIT` (novo) | 10 | ver a tabela abaixo |

**`AdminReadRoutesIT` por rota:**

| Rota | O que confere |
|---|---|
| **M01** | campos exatos e em ordem; ausência de `email_normalized`, segredos e sessões; `mfaConfigured` para os 5 casos (logado com MFA, sem fator, desativado, não confirmado, confirmado); atribuição revogada fora de `organizationRoles`; conta externa e organização B ausentes; filtros `status`; paginação estável com 6 vínculos de mesmo `created_at`, ordem pelo id; **revogação de `org.manage` entre páginas → 403**; 401 (anônimo, outra organização); 403 (OPERATOR, sem papéis, conta externa); **400 antes de 403** para conta externa; 400 para `status` desconhecido, vazio, repetido e acima de 4, `limit` e cursor (outra rota, filtro trocado, outro vínculo), com controle positivo |
| **M02** | campos e nulabilidade de concessão ativa, revogada e pendente (`approvedAt` e `nextReviewAt` nulos na pendente); filtros `status`, `clientId`, `workspaceId` e combinados; **exceção DC-05**: `clientId`/`workspaceId` de outra organização, inexistentes ou combinação sem resultado → **200 vazio** (não 404), com controles positivos; UUID malformado → 400; paginação **descendente** estável com desempate (`limit` 3 e 1); cursor com filtro trocado → 400; 403 para WORKSPACE_ADMIN sem `external_access.manage` e para conta externa; 401; revogação entre páginas → 403; organização B ausente |
| **M03** | **ordenação binária** (maiúsculas < minúsculas < acentuadas); desempate de nomes iguais por id; **mesmos 48 primeiros pontos de código ficam em ordem de id, não pelo restante do nome** (ids escolhidos para que a ordem pelo id contrarie a do sufixo); nome completo na resposta; paginação estável com `limit` 1, 2 e 4; filtros `status`; **nomes de 1.000 caracteres** (ASCII, controles, emoji, aspas/barra, CJK) no fim de cada página → página seguinte aceita, cursor ≤ 872; **mudança concorrente** (§5.4): renomear para antes do cursor (omitido), para depois (aparece), mudar só depois do 48º ponto de código (posição inalterada, aparece uma vez com o nome novo), arquivar com filtro `status` (sai); organização sem clientes → `[]`; 401; 403 (OPERATOR, sem papéis, externa); 400 |

**Limite:** o caso "só uma das duas permissões" não tem papel no catálogo atual. Ele é coberto pelo teste do componente e pela mutação M1, sem alterar papéis do produto.

**Fixture de concessão:** a trigger da 003 torna `created_at` imutável depois da inserção. Por isso as concessões de massa são inseridas já com o `created_at` escolhido.

## 3. Execuções

| Execução | Resultado |
|---|---|
| Focalizada (`testes_focalizado.txt`; GAP-02 e GAP-03 juntas) | **44/44** na primeira execução |
| **Mutações, primeira rodada: INVÁLIDA** (`mutacoes_INVALIDA_bash_wsl/`) | o `subprocess` do Python resolveu `bash` para o bash do **WSL**, que não iniciou; **nenhum teste rodou**. O critério do script ("código ≠ 0 e sem erro de compilação") contou isso como detecção. Erro do auxiliar, preservado e **não usado como evidência** |
| **Mutações, rodada válida** (`mutacoes_resumo.txt`, `mutacoes.py`) | Git Bash pelo caminho completo; critério endurecido: testes executados > 0, falhas de teste nomeadas e sha restaurado. **5/5 detectadas, todas restauradas com sha idêntico.** Ver a tabela abaixo |
| `validar-config` (`validar/`) | código 0, **184 OK e 0 falhas**, nomes **idênticos** à referência de 184 |
| **Regressão completa** (`regressao/`) | ver §5 |

**Mutações da rodada válida:**

| Mutação | Testes que falharam |
|---|---|
| **M1** "ou" → "e" | exatamente `onlyOrgManageIsAllowed` e `onlyExternalAccessManageIsAllowed` |
| **M2** M01 sem checagem de permissão | os 2 testes de 403 da M01 |
| **M3** M03 comparando o nome completo (`ORDER BY` com prefixo) | os 3 testes de ordenação, nomes longos e mudança concorrente |
| **M4** filtro da M02 com consulta de existência e 404 | o teste da exceção DC-05 |
| **M5** permissão organizacional sem `requireConsistent` | o teste de consistência no `WorkspaceAccessIT` |

## 4. OpenAPI

- `openapi-proposta.yaml` passou para `0.5.0-gap03`:
  - `listMembers`, `listClientGrants` e `listClients` com `x-estado: implementado`;
  - resposta **500** (`CatalogInconsistent`) incluída nas três, cobrindo também rótulo de status desconhecido;
  - DC-04, DC-05 e DC-06 registrados como decididos.
- **Validação** (`validacao_openapi.txt`): 13 rotas, 155 referências resolvidas e catálogo igual ao das migrations de HEAD.
- O arquivo continua como anexo enquanto o **DC-14** estiver pendente.

## 5. Arquivos, patches e regressão

- **Novos:** `Labels.java`, `AdminReadController.java`, `OrganizationPermissionTest.java` e `AdminReadRoutesIT.java`.
- **Alterados em relação à GAP-02 C1:** `WorkspaceAccess.java`, `AccessFixtures.java` e `WorkspaceAccessIT.java`.
- **Hashes finais:** `depois.sha256`, com os 16 arquivos da GAP-02 + GAP-03.
- **Patches** (`P_patches/`, verificados em `verificacao_patch.txt`):

| Arquivo | Verificação |
|---|---|
| `incremental_sobre_gap02c1.patch` | aplicado sobre as fontes **do pacote C1** (`943272aa…`), reproduz os 16 arquivos |
| `gap02_gap03_total.patch` | aplicado sobre o blob de HEAD, reproduz os 16 arquivos; a aplicação reversa confere com a árvore |

- **Regressão completa** (`regressao/`, `regressao_maven.sh -B verify`, 13:54Z–14:05Z):
  - **319 testes, 0 falhas, 0 erros, 0 ignorados** (303 + 16 novos: 5 + 1 + 10);
  - `REAPROVEITAMENTO=PERMITIDO`; `verificar`: `INTEGRIDADE_EXECUCAO=OK`, `ARVORE_ATUAL=IDENTICA`, `REAPROVEITAMENTO_NA_ARVORE_ATUAL=PERMITIDO`;
  - fontes `04f086fe…ef5c`; 0 `Address already in use`;
  - os **16 arquivos** entregues batem com as fontes registradas na regressão (`fontes_x_regressao.txt`, 16/16).
- **Fim de linha:** todos os arquivos estão em LF, como os demais da árvore. O aviso de CRLF vem do `core.autocrlf=true` global; ao commitar, aplicar `autocrlf=false`.

## 6. Empacotamento: achado da varredura e redação

**Primeiro pacote** (`api-v1-gap03-membros-concessoes-clientes-20261003T140625Z.zip`, sha `14a6d18e…593c`): varredura com **código 1**, 2 achados de "formato de token", ambos em `mutacao_M2_members_sem_permissao.txt`. O controle positivo também reprovou, porque exige zero achados na evidência real. **O pacote foi preservado e não é a entrega.**

**Classificação dos 2 achados:**
- os dois são a **tag HMAC de um cursor de paginação** (a parte depois do `.`);
- a mutação M2 retirou a checagem de permissão, então o teste recebeu uma página 200 em vez de 403, e a mensagem de falha imprimiu o corpo com o `nextCursor`;
- não é credencial: é a assinatura de posição feita com a chave HMAC **aleatória** daquela execução de teste, que nunca é gravada.

**Tratamento:**
- a evidência original em `ops/homologacao/saidas/gap03-…/` ficou **intacta**;
- no pacote entregue, só a **cópia** dos logs de mutação teve as tags de cursor substituídas por `<TAG_HMAC_DE_CURSOR_REDIGIDA>`: **2 ocorrências**, ambas no log da M2;
- `redacoes.txt` registra o arquivo, a contagem e o sha256 do original (`26e1e44f…dee3`);
- o código da redação está no gerador do pacote.

**Pacote entregue:** varredura **0 achados** e controle positivo OK.

**Recomendação para a revisão:** decidir se mensagens de falha de teste devem evitar imprimir corpos com cursor, ou se a varredura deve classificar tags de cursor de teste. Nesta entrega, o critério de zero achados foi mantido.

## 7. Rodada corretiva 1 (parecer de 2026-10-03)

**Evidências:** `ops/homologacao/saidas/gap03r1-20261003T142020Z/`. Só testes mudaram. Não houve alteração de produto, contrato, migrations nem decisões, e não houve stage, commit nem push.

| Ponto do parecer | Correção |
|---|---|
| **1.** Faltava a revogação entre páginas da M03 (§5.5) | `clientsRevocationBetweenPagesGives403`: a segunda ORG_ADMIN abre a página 1. O mesmo cursor vale antes da revogação (controle positivo). Depois de perder a atribuição, continuar com o cursor válido dá **403**. A primeira ORG_ADMIN segue com acesso, o que preserva a regra do último administrador |
| **2.** Os testes paginados de M01 e M02 fixavam todos os `created_at` iguais e só provavam o desempate | `membersOrderByCreatedAtThenIdAscendingWithMicrosecondsAndTies` e `clientGrantsOrderByCreatedAtThenIdDescendingWithMicrosecondsAndTies`: 7 itens com instantes que **decrescem com o id** (a ordem do id contraria a da data), dois empates e passos de **1 µs**. O teste confere que a massa distingue a chave principal. Todas as páginas são conferidas com `limit` 1, 2, 3 e 7: M01 por data e id **ascendentes**, M02 por data e id **descendentes**, sem repetição nem omissão, e cada `createdAt` com microssegundos |
| Logs com cursor | as mensagens de falha do `AdminReadRoutesIT` mostram só `status`, código de erro, quantidade de itens e se o `nextCursor` está presente ("presente (ocultado)"). Nunca o corpo completo nem o valor do cursor. A varredura **não** foi flexibilizada |

**Execuções:**

| Execução | Resultado |
|---|---|
| Focalizada (`testes_focalizado.txt`) | **47/47** |
| **Mutações** (`mutacoes_resumo.txt`; Git Bash por caminho completo; critério de testes executados e falhas nomeadas) | **3/3 detectadas**, todas restauradas com sha idêntico, e **0** valores com formato de token nos logs. Ver a tabela abaixo |
| `validar-config` (`validar/`) | 184/0, nomes idênticos à referência |
| **Regressão completa** (`regressao/`, 14:26Z–14:39Z) | **322 testes, 0 falhas, 0 erros, 0 ignorados** (319 + 3). `REAPROVEITAMENTO=PERMITIDO`; `verificar`: `INTEGRIDADE_EXECUCAO=OK`, `ARVORE_ATUAL=IDENTICA`, `REAPROVEITAMENTO_NA_ARVORE_ATUAL=PERMITIDO`. Fontes `6df2f557…a88e`. 0 `Address already in use`. Os 16 arquivos batem com as fontes da regressão (16/16) |

**Mutações:**

| Mutação | Testes que falharam | Observação |
|---|---|---|
| **M6** `/members` ordenado só pelo id | `membersOrderByCreatedAt…` | o teste antigo de datas iguais, rodado junto, **passou** com a mutação: era a lacuna apontada |
| **M7** `/client-grants` ordenado só pelo id | `clientGrantsOrderByCreatedAt…` | o teste antigo, rodado junto, passou |
| **M8** `/clients` sem checagem de permissão | `clientsRevocationBetweenPagesGives403` | a mensagem de falha mostrou só os campos de diagnóstico |

**Patches** (`P_patches/`, `verificacao_patch.txt`):

| Arquivo | Conteúdo | Verificação |
|---|---|---|
| `incremental_sobre_gap03.patch` | só `AdminReadRoutesIT.java` (+134/−6) | aplicado sobre as fontes do pacote GAP-03 (`88670194…`), reproduz os 16 arquivos |
| `gap02_gap03_total.patch` | GAP-02 + GAP-03 sobre HEAD | reproduz os 16 arquivos; a aplicação reversa confere |
