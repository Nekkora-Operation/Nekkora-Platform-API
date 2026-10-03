# GAP-02: catálogo de permissões, consulta de permissões (P01/P02), autorização por workspace e cursor compartilhados

**ESTADO: PARA REVISÃO, COM A RODADA CORRETIVA 1 (§6).** Não houve stage, commit nem push. A integração no Git é uma etapa separada, como a dos testes da GAP-01.

**Data:** 2026-10-03.

**Base:**
- contrato GAP-02/03/06, revisão 2, aprovada tecnicamente (`docs/relatorios/2026-10-02-api-v1-contrato-gap02-03-06-proposta.md`);
- decisões **da responsável** em 2026-10-03, respondidas em pergunta do executor:
  - **DC-01:** ORG_ADMIN global como política explícita;
  - **DC-02:** conta externa recebe 403;
  - **DC-03:** paginar as atribuições na P02;
  - **DC-15:** subchave derivada para o cursor; a rotação da chave de serviço invalida os cursores;
- código de referência: HEAD `2431648f` (árvore = `main` `bd9e96a1`), mais os testes da GAP-01 ainda não commitados.

**Evidências:** `ops/homologacao/saidas/gap02-20261003T043436Z/`.

## 1. O que foi entregue

| Componente | Arquivo | Papel |
|---|---|---|
| Catálogo | `access/PermissionCatalog.java` | os 23 códigos com a aplicabilidade do contrato (§0.1): **10 da organização** e **13 de workspace**; os 4 papéis e seus escopos |
| Autorização compartilhada | `access/WorkspaceAccess.java` | **regra única** da §1.2, com regra (a) e regra (b), vínculo e conta ativos e leitura do estado atual. Oferece: condição SQL de visibilidade (`VISIBLE_WORKSPACE_CONDITION` + `bind`), decisão pontual (`hasWorkspacePermission`), `requireWorkspace` (404 idêntico), papéis organizacionais e atribuições explícitas paginadas. Código fora do catálogo, ou permissão organizacional em papel de workspace → **500**, com log `evento=api.catalogo_desconhecido`, nunca omitido |
| Cursor compartilhado | `paging/CursorCodec.java` | formato da §1.5, com os vínculos de rota, organização, vínculo da sessão, pai, impressão dos filtros e ordenação. HMAC com `K_cursor = HMAC-SHA256(chave de serviço, "nekkora/api-cursor/v1")`, comparação em tempo constante e impressão de 11 caracteres do id da chave. Aceita até 1.024 caracteres e recusa **emitir** cursor acima disso (500) |
| Página | `paging/Paging.java` | `limit` de 1 a 100, padrão 25 |
| Rotas | `web/PermissionsController.java` | **P01** `GET /api/v1/o/{org}/me/permissions` e **P02** `GET /api/v1/o/{org}/me/permissions/workspaces` (ordem por `workspace_id`, leitura de `limit + 1`). A ordem das verificações segue a §1.3: 401 (filtro) → 400 → 403 (conta externa) |
| Configuração | `config/PlatformConfig.java` (+12 linhas) | beans `CursorCodec` (chave `nekkora.keys.hmac`/`hmacId` já existente; nenhum segredo novo) e `WorkspaceAccess` |

**Nenhuma** migration, permissão de banco, configuração de ambiente, segredo novo nem rota existente foi alterada.

## 2. Testes

| Classe | Tipo | Testes | O que confere |
|---|---|---|---|
| `paging/CursorCodecTest` | unitário | 9 | **derivação:** vetor fixo calculado fora da aplicação (Python `hmac`); subchave ≠ chave de serviço; tag feita com a própria chave de serviço é recusada. **Assinatura inválida:** bit no payload, bit na tag, tag truncada, tag de outro payload, tag vazia, formato inválido. **Chave desconhecida e rotação:** cursor antigo recusado após a troca; mesmo id com material novo recusado; janela de transição aceita o antigo só enquanto configurada. **Vínculos:** rota, organização, vínculo, pai (inclusive nulo × valor), filtros e ordenação. **Impressão canônica** dos filtros. **Tipos da posição.** **Tamanho com o serializador Java real** (Jackson da aplicação): pior caso **871** caracteres (≤ 872 calculado), com prefixos de 48 pontos de código ASCII, controle, aspas, barra invertida, emoji, CJK e U+2028; > 1.024 recusado na leitura e na emissão |
| `access/CatalogValidationTest` | unitário | 3 | permissão organizacional em papel de workspace, código desconhecido e papel em escopo errado → 500; `allWorkspaces` só com as 13 |
| `PermissionCatalogIT` | IT (banco real, papel `nekkora_platform`) | 2 | `public.permissions` = os 23 do catálogo; papéis e escopos; **matriz papel × permissão = §0.1** (oráculo literal); papéis de workspace só com permissões de workspace; papel fora do catálogo → **500** na P01 e na P02 (nunca omitido) |
| `WorkspaceAccessIT` | IT (componente real com `nekkora_platform` e RLS) | 3 | **tabela V da §5.2** (visibilidade), com 9 atores: ORG_ADMIN, ORG_ADMIN + VIEWER em W3, WORKSPACE_ADMIN em W1, OPERATOR em W1, VIEWER em W2, atribuição revogada, sem papéis, conta suspensa com papel, vínculo externo. Cada um conferido em W1, W2, W3 (arquivado), workspace de outra organização e workspace inexistente. A condição de listagem e a decisão pontual **concordam**. Matriz por permissão. `requireWorkspace` dá 404 idêntico nos três casos. Revogação lida no estado atual (sem cache), inclusive a do ORG_ADMIN |
| `MyPermissionsRouteIT` | IT HTTP (aplicação, PostgreSQL e Redis reais) | 9 | **tabela E da §5.2** (atribuição explícita): ORG_ADMIN tem as 23 e `allWorkspaces` com as 13, mas **P02 vazia**; ORG_ADMIN + VIEWER em W3 arquivado; WORKSPACE_ADMIN, OPERATOR e VIEWER com permissões exatas; dois papéis no mesmo workspace sem repetição; revogada e sem papéis. **401:** anônimo, sessão de outra organização, `{org}` malformado. **400** antes de **403** para conta externa. **400:** `limit` 0, 101, `abc`, `1.5`; cursor vazio, malformado e acima de 1.024. **Paginação estável** com 5 e 4 itens (`limit=2`): cada item uma vez, em ordem, sem página intermediária vazia, última com `nextCursor` nulo; `limit` pode mudar entre páginas. **501 atribuições** alcançáveis sem truncar e sem 500. **Revogação entre páginas:** o item revogado some e o cursor continua válido; revogação do ORG_ADMIN; `revoke-all` → 401. **Vínculos do cursor via HTTP:** outro vínculo, outra organização, outra rota, outra ordenação, outros filtros, com pai, tipo errado e cursor adulterado → 400, cada caso com controle positivo |

**Oráculo independente:** as listas de códigos esperadas nos ITs são literais, transcritas da §0.1. Não vêm do catálogo da aplicação.

**Execuções:**

| Execução | Resultado |
|---|---|
| Focalizada, tentativa 1 (`testes_gap02_focalizado_tentativa1.txt`) | 24/26 (1 falha + 1 erro), ambos **na montagem dos testes novos**, não no produto. (1) Segundo "primeiro login" de uma conta que já tinha MFA. (2) Concessão externa num workspace sem cliente (FK 23503). Corrigidos só os testes |
| Focalizada (`testes_gap02_focalizado.txt`) | **26/26**, BUILD SUCCESS |
| **Mutação** (`mutacao_regra_a.txt`) | regra (a) desligada no componente → `WorkspaceAccessIT` **3 de 3 falharam**. O arquivo foi restaurado, com sha idêntico ao anterior |
| **Regressão completa** (`regressao/`, `regressao_maven.sh -B verify`, 04:37Z–04:48Z) | **301 testes, 0 falhas, 0 erros, 0 ignorados** (275 anteriores + 26 novos). Reator SUCCESS. `REAPROVEITAMENTO=PERMITIDO`. `verificar`: `INTEGRIDADE_EXECUCAO=OK`, `ARVORE_ATUAL=IDENTICA`, `REAPROVEITAMENTO_NA_ARVORE_ATUAL=PERMITIDO`. Fontes `42cf947c…069b`. 0 `Address already in use` |

## 3. OpenAPI (D-INV-06)

- `openapi-proposta.yaml` passou para a versão `0.4.0-gap02`. P01 e P02 estão marcadas com `x-estado: implementado`, citando o controlador e os testes. As demais rotas continuam como proposta.
- Conferido contra os testes: os campos da P01 (o teste confere a lista exata e a ordem dos 5 campos), os itens da P02, os 400/401/403/500 e os enums de código.
- **Validação** (`validacao_openapi.txt`): 13 rotas, 152 referências resolvidas e catálogo igual ao das migrations.
- O arquivo continua como anexo da proposta: o caminho definitivo depende do **DC-14**, ainda pendente.

## 4. Arquivos (diff fechado: `P_patches/gap02.patch`)

- **Novos:** 5 de produto e 6 de teste.
- **Alterado:** só `PlatformConfig.java`, com +12 linhas e nenhuma removida.
- **Hashes:** em `antes.sha256` e `depois.sha256`.
- **Verificação do patch** (`verificacao_patch.txt`):
  - a aplicação reversa confere com a árvore atual;
  - aplicado sobre o blob de HEAD, o patch reproduz os 12 arquivos com o mesmo sha.
- **Desvio do auxiliar, registrado:** a primeira tentativa de aplicação usou `git archive`, que converteu a cópia de HEAD para CRLF (`core.autocrlf=true`) e recusou o patch. Refeito com `git show`.
- **Fim de linha:** todos os arquivos estão em LF, como o `PlatformConfig.java` já estava no índice e no disco (`i/lf w/lf`). O aviso "LF will be replaced by CRLF" vem do `core.autocrlf=true` global do Git para Windows. Ao commitar, aplicar a regra do projeto (`autocrlf=false`).

## 5. Observações e limites

- **Ordem de `workspace_id`:** a ordem é a do PostgreSQL, por bytes do UUID, que coincide com a do texto minúsculo. **Não** é a de `UUID.compareTo` do Java. Os testes ordenam pelo texto.
- **Cursor ainda não usado por outras rotas:** o vínculo a "outra rota" foi provado com um cursor emitido pelo próprio codificador para `listWorkspaces`. A GAP-06 confere o cruzamento real entre rotas paginadas.
- **Papéis `TESTE_*`:** o `PermissionCatalogIT` cria papéis com esse prefixo na base **de teste** compartilhada, para provar o 500. O próprio IT os exclui da comparação do catálogo.
- **Permissão organizacional dentro de papel de workspace:** coberta só por teste unitário (`CatalogValidationTest`). Montá-la no banco compartilhado exigiria alterar um papel real.
- **Componentes compartilhados prontos para GAP-03 e GAP-06:** `WorkspaceAccess`, `CursorCodec` e `Paging`. As decisões DC-04 a DC-14 continuam pendentes e precisam ser registradas antes das missões que dependem delas.

## 6. Rodada corretiva 1 (parecer de 2026-10-03)

**Evidências:** `ops/homologacao/saidas/gap02c-20261003T124451Z/`. Não houve stage, commit nem push. Nenhuma decisão de produto foi reaberta e nenhuma migration foi alterada.

| Ponto do parecer | Correção |
|---|---|
| **1.** A listagem (`VISIBLE_WORKSPACE_CONDITION`), `hasWorkspacePermission` e `requireWorkspace` não validavam o papel contra o catálogo. Um papel fora do catálogo com `automation.read` **concedia acesso**, enquanto a P02 dava 500 | `WorkspaceAccess.requireConsistent` confere **todas** as atribuições ativas, organizacionais e de workspace, do vínculo. Ela roda **antes** de decidir em todos os caminhos: a nova porta única `visibleWorkspacesQuery` (o `bind` estático público foi retirado; a consulta precisa conter a condição), `hasWorkspacePermission`, `requireWorkspace`, `organizationGrant`, `hasWorkspaceAssignments` e `workspaceAssignments`. Inconsistência → **500 com log** `evento=api.catalogo_desconhecido`, nunca escondida e nunca usada para conceder acesso. **Consequência:** a P01 também dá 500 para um vínculo com papel inconsistente de workspace; na entrega anterior ela devolvia 200 nesse caso. Registrado no contrato (§1.2) |
| **2.** `NOT LIKE 'TESTE_%'` era amplo demais, e `_` é curinga no LIKE | as fixtures fora do catálogo são criadas só por `AccessFixtures.roleOutsideCatalog` e registradas pelo **ID exato** (`ROLES_CREATED_BY_TESTS`; a base de teste é uma por JVM). O IT exclui **só** esses IDs. **Contraprova** (`unexpectedRoleOutsideTheFixturesFailsTheComparison`): um papel `TESTE_…` não registrado **reprova** a comparação; só depois o teste o registra, para não contaminar os demais |
| **3.** Faltava a evidência do `validar-config` | `validar/`: código 0, **184 OK e 0 falhas**, nomes **idênticos** à referência de 184 (`consol1-…/D_proposta/validar_config_referencia_184_nomes.txt`, sha `ba7ec90b…`), pelo mesmo método do G1 (`valida.sh`, etapa c3) |

**Testes novos ou alterados:**
- `WorkspaceAccessIT.roleOutsideCatalogIsAServerErrorInEveryAccessPath` (novo). Três vínculos inconsistentes: só papel de workspace desconhecido; papel desconhecido mais OPERATOR válido em outro workspace; papel organizacional desconhecido.
  - Listagem, decisão pontual e `requireWorkspace` dão 500, inclusive no workspace do papel válido, porque a política vale para o vínculo inteiro.
  - Um log por recusa (12), conferido no output capturado.
  - Controles positivos com OPERATOR válido e ORG_ADMIN, antes e depois das recusas.
- `PermissionCatalogIT`:
  - exclusão por ID exato e a contraprova acima;
  - o 500 agora é conferido em **P01 e P02** para papel desconhecido de workspace e de organização, com 4 logs e controle positivo com VIEWER válido.
- Os demais testes não mudaram.

**Execuções:**

| Execução | Resultado |
|---|---|
| Focalizada (`testes_focalizado.txt`) | **28/28** |
| **Mutação** (`mutacao_sem_consistencia_na_visibilidade.txt`) | `requireConsistent` retirado de `visibleWorkspacesQuery` → o teste novo falha (papel desconhecido volta a dar acesso). Arquivo restaurado com sha idêntico |
| `validar-config` (`validar/`) | 184/0, nomes idênticos à referência |
| **Regressão completa** (`regressao/`, 12:49Z–13:00Z) | **303 testes, 0 falhas, 0 erros, 0 ignorados** (301 + 2 novos). `REAPROVEITAMENTO=PERMITIDO`; `verificar`: `INTEGRIDADE_EXECUCAO=OK`, `ARVORE_ATUAL=IDENTICA`, `REAPROVEITAMENTO_NA_ARVORE_ATUAL=PERMITIDO`. Fontes `9eee6746…f61a`. 0 `Address already in use`. Os **12 arquivos** da GAP-02 batem com as fontes da regressão (`fontes_x_regressao.txt`, 12/12) |

**Diffs** (`P_patches/`, verificados em `verificacao_patch.txt`):

| Arquivo | Conteúdo | Verificação |
|---|---|---|
| `incremental.patch` | só o que mudou desde o pacote `7c93fd1d…`: `WorkspaceAccess.java`, `AccessFixtures.java`, `WorkspaceAccessIT.java` e `PermissionCatalogIT.java`; +249/−56 | aplicado sobre as fontes **do próprio pacote entregue**, reproduz os 12 arquivos atuais |
| `gap02_total.patch` | GAP-02 inteira sobre HEAD | aplicado sobre o blob de HEAD, reproduz os 12 arquivos; a aplicação reversa confere com a árvore |

**Hashes finais:** `depois.sha256`.

**Aprovação e ressalva de redação** (parecer de 2026-10-03): a correção C1 foi aprovada tecnicamente e a GAP-02 está **concluída no escopo contratado**.
- A frase "não há como pular a checagem" é **imprecisa**: a constante `VISIBLE_WORKSPACE_CONDITION` continua pública, então código futuro poderia usá-la fora de `visibleWorkspacesQuery`.
- A afirmação precisa é: **os caminhos implementados e testados passam pela validação de consistência.**
- A GAP-06, e qualquer rota futura, deve usar só `visibleWorkspacesQuery`, `hasWorkspacePermission` e `requireWorkspace`.
- O javadoc de `WorkspaceAccess`, que diz "so pode ser executada por", fica para ajuste na próxima mini missão com código (GAP-03), dentro da regressão dela, para não invalidar a regressão aprovada desta entrega.

**Desvio do auxiliar:** a primeira extração do pacote anterior estourou o limite de 260 caracteres de caminho do Windows. Refeita extraindo só as fontes para um caminho curto, sem efeito no resultado.
