# PLATFORM-AGENT-V2-003 · T12: uma autorização local vigente por escopo

## Problema
"Autorizar monitoramento" (e "Autorizar execução local") podiam ser repetidos sem limite:
- **Web:** o formulário continuava ativo com uma autorização vigente.
- **API:** a rota `POST /api/v1/o/{org}/workspaces/{workspaceId}/local-run-authorizations` fazia um `INSERT` simples.
- **Banco:** `local_run_authorizations` (025/030) não tinha nenhuma regra de unicidade.

Cada clique gravava mais uma autorização "Vigente", e o gateway entregava todas ao Agent.

## Domínio (diagnóstico)
| Autorização | Tabela | Chave | Expira | Já era única? |
|---|---|---|---|---|
| Worker no workspace | `worker_workspace_assignments` | org + Worker + workspace | não | sim (índice parcial da 004; a rota é idempotente) |
| Execução local de pacote (`STUDIO_PACKAGE`, `EXTERNAL_PACKAGE`) | `local_run_authorizations` | org + autorização do Worker no workspace + **versão** | `valid_until` (até 366 dias) | **não** |
| Monitoramento de Python externo (`EXTERNAL_MONITORED`) | `local_run_authorizations` | org + autorização do Worker no workspace + **automação** | `valid_until` (até 366 dias) | **não** |

A autorização Worker–workspace não muda. A T12 trata as duas linhas de baixo.

## Regra
- **Vigente:**
  - não revogada (`revoked_at IS NULL`);
  - `valid_until` depois do **horário do servidor**, o mesmo critério do gateway;
  - a fronteira é aberta: no instante exato de `valid_until`, ela já está vencida.
- **Escopo:** organização + autorização do Worker no workspace (`worker_workspace_assignment_id`, que fixa o Worker **e** o workspace) + versão (pacote) ou automação (monitoramento).
- **No máximo uma vigente por escopo.** Uma nova só é aceita depois de revogar ou vencer a anterior.
  - As anteriores ficam no histórico: a 025 já proíbe apagar.
  - Nada é estendido, substituído ou revogado para aceitar a repetição.
- **Continuam autorizáveis:** outro Worker, outra versão, outra automação, outro workspace, e pacote e monitoramento da mesma automação.
- **Worker que sai do workspace e volta:** ganha outra autorização no workspace e, portanto, é outro escopo. As autorizações antigas deixam de chegar ao Agent, porque o gateway exige a autorização do Worker ativa.

## Onde a regra vale
- **Banco (033):**
  - O gatilho de inserção da 030 ganhou a regra. Ele trava o escopo com `pg_advisory_xact_lock` e só então procura uma vigente.
  - Em READ COMMITTED (`OrgTransactions`), a busca depois da trava vê o que uma inserção concorrente confirmou. Duas abas, duas pessoas ou uma chamada direta ao banco nunca gravam duas.
  - A recusa sai como SQLSTATE `23505` com o nome `ux_local_run_authorizations_vigente`.
- **Por que gatilho e não índice:** "vigente" depende do relógio, e um índice único parcial não aceita `clock_timestamp()`. Uma restrição de exclusão por intervalo exigiria a extensão `btree_gist`, que a Platform não usa.
- **API:** a recusa vira **409** `conflict` + `reason: local_run_authorization_exists`.
  - Nada é gravado nem auditado; a auditoria continua sendo só da concessão real, ato 10.
  - A recusa vai para o log como as demais (`evento=acesso.negado`), sem identificadores.
- **Repetição depois de perder a resposta:** cai no mesmo 409 e nunca cria outra. A Web relê a lista e mostra a vigente.
- **Inalterados:**
  - confirmação de identidade (step-up), permissões (`automation.execute`) e as demais recusas da 025/030;
  - a revogação (`POST .../{authorization}/revoke`);
  - a entrega ao Agent.

## Contrato
- `ApiError.LOCAL_RUN_AUTHORIZATION_EXISTS` → `{"error":"conflict","reason":"local_run_authorization_exists"}`.
- OpenAPI:
  - `reason` passa a ter o novo valor;
  - a rota de criação documenta a regra e responde 409 com `LocalRunAuthorizationConflict`;
  - um cliente que não conhece o motivo vê a recusa comum de conflito.

## Dados existentes
1. **Antes da 033**, rode o diagnóstico, que é só leitura:
   ```
   psql -X -v ON_ERROR_STOP=1 -d nekkora -f db/diagnosticos/033_autorizacoes_vigentes_repetidas.sql
   ```
   A seção 1 lista os escopos com mais de uma vigente.
2. **Se houver algum**, a 033 **para antes de qualquer alteração** e lista os mesmos registros. Não há escolha automática.
3. **Procedimento:** na Web, em "Execução local" (versão) ou "Monitoramento" (automação), use **Revogar** nas autorizações que não devem ficar. Revogar não apaga, e o histórico é preservado. Depois, aplique a 033 de novo.

## Testes
| Requisito | Teste |
|---|---|
| Clique repetido, resposta perdida e repetição pela API (inclusive com validade maior): 409, nada gravado, estendido ou revogado; auditoria só da concessão | `AutorizacaoVigenteUnicaIT.repeticaoDoMesmoEscopoRecusadaSemGravarNemMudarAVigente` |
| Duas abas e duas pessoas ao mesmo tempo (6 rodadas, pacote e monitoramento): sempre uma 201 e uma 409 | `duasAbasEDuasPessoasAoMesmoTempoGravamUmaSo` |
| Chamada direta ao banco com o papel real da Platform: 23505 | `insercaoDiretaNoBancoTambemRecusada` |
| Nova depois da revogação; histórico com as duas | `novaAutorizacaoDepoisDaRevogacaoComHistorico` |
| Imediatamente antes do vencimento, recusada; depois do vencimento, pelo relógio do banco, aceita; a vencida fica sem revogação | `novaAutorizacaoSoDepoisDoVencimentoNoHorarioDoServidor` |
| Outro Worker, versão, automação e workspace; monitoramento e pacote da mesma automação | `escoposDistintosContinuamAutorizaveis` |
| Isolamento entre organizações (o 409 não revela a outra); Worker que volta ao workspace | `isolamentoEntreOrganizacoesENovaAutorizacaoDoWorkerNoWorkspace` |
| Migração: base limpa; base populada com dados idênticos; base com repetições: para sem alterar nada e aplica depois da revogação, sem perder linhas | `db/tests/autorizacao-vigente-033/run.sh` (A, B e C) |
| Testes anteriores do canal (`AgentAdminIT`, `ExternalPythonRunRequestIT`, `AgentGatewayIT`, `ExternalPythonGatewayIT`, contratos) | sem mudança, passam |

## Limites
- **Sem renovação:** nada estende a validade. Para continuar depois do vencimento, cria-se outra autorização.
- **Fixtures:** os testes de migração montam a massa com gatilhos desligados, como administrador do cluster (preparação declarada, não evidência). O comportamento é provado pela API com o papel real.
- **Web:** a tela mostra a vigente e não oferece outra equivalente. Isso é entregue no PR da Web da T12.
