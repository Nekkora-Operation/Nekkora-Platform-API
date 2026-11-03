# AGENT-PYTHON-001: diferença de contrato da API (migration 030)

A missão é de 03/11/2026 e o dono principal é o Nekkora-Agent. A matriz e o contrato completo estão em
`Nekkora-Agent: docs/agent-python-001/README.md`. Este documento registra **só o que muda na API**, que tem um dono único
para cada item. A base é a `dev` `770b31f`, que tem a mesma árvore da `main` anterior aos commits de e-mail #43.

## 1. Origem explícita (`source_kind`)

Os valores aparecem em `local_run_authorizations`, `agent_runs` e `run_requests`. As linhas anteriores ficam com 1.

| Código | Nome | Versão | Impressão | Regra |
|---|---|---|---|---|
| 1 | `STUDIO_PACKAGE` | sim | sim | `nekkora-agent/package-fingerprint/1` (inalterada) |
| 2 | `EXTERNAL_PACKAGE` | sim | sim | `nekkora-agent/external-python-fingerprint/1` |
| 3 | `EXTERNAL_MONITORED` | **não** | **não** | — |

O monitoramento não inventa impressão. A Platform conhece só a automação, o Worker e a validade; código, caminho e argumento
ficam na máquina. A CHECK `local_run_authorizations_source_kind_package` e a `agent_runs_source_kind_package` amarram a origem
à presença ou ausência de versão e impressão.

`agent_run_on_update` passou a usar `IS DISTINCT FROM` nas colunas que agora aceitam nulo. Com `<>`, uma troca de NULL para
um valor passaria. O IT tem a prova vermelho/verde.

## 2. Pessoas (`nekkora-platform`)

| Rota | Mudança |
|---|---|
| `POST …/workspaces/{ws}/local-run-authorizations` | `{"sourceKind":"EXTERNAL_MONITORED", workerId, automationId, validUntil}` cria uma autorização de monitoramento, com a mesma autoridade (automation.execute + step-up). Sem `sourceKind`, nada muda. `sourceKind` de pacote no corpo responde 400, porque essa origem vem da regra da versão |
| `GET …/local-run-authorizations` | `sourceKind` e `packageFingerprintRule` |
| `GET …/agent-runs[/{id}]` | `origin` (`LOCAL_AUTHORIZED` ou `PLATFORM_REQUEST`), `sourceKind`, `correlation.runRequestId`, `automationName` e `agentName`. `automationVersionId` vem nulo no monitoramento |
| `POST …/workspaces/{ws}/run-requests` | **nova**: `{authorizationId, agentId, validForSeconds 60..86400, requestKey}`. Exige automation.execute + step-up. A autorização precisa estar ativa e ser de pacote, e o Agent precisa estar ativo e associado ao Worker. A mesma `requestKey` devolve o mesmo pedido (200) |
| `GET …/workspaces/{ws}/run-requests`, `GET /run-requests/{id}` | **novas**: estado (o vencido é lido como `EXPIRED`), entrega, decisão, execução ligada (início só pelo supervisor) e máquina |
| `POST /run-requests/{id}/revoke` | **nova**: só antes da decisão. Depois dela, 409 |
| `PUT …/artifact` | aceita também `nekkora-external.json`, o pacote Python externo: descritor 1.0.x, entrada `.py` relativa listada, Python, dependências `nome==versão` e arquivos com hash. As recusas são de vocabulário fechado (`entrada_fora_da_raiz`, `dependencia_sem_versao_exata`, `arquivo_proibido`, `descritor_ambiguo`…). **Nada é executado** |

## 3. Máquina (`nekkora-agent-gateway`)

| Rota | Mudança |
|---|---|
| `POST /heartbeat` | capacidades `EXTERNAL_PYTHON_1_0` e `RUN_REQUEST_1_0` |
| `GET /authorizations` | `sourceKind`. No monitoramento, versão, impressão e regra vêm nulas. Um Agent anterior recusa esse item, porque ele não tem impressão (falha fechada) |
| `POST /runs` | `packageFingerprint` vai ausente **se e somente se** a autorização for de monitoramento. `runRequestId` exige um pedido ACEITO por esta máquina, com o mesmo `agentExecutionId`, e passa o pedido a `RUN_LINKED` |
| `GET /run-requests` | **nova**: pedidos `DELIVERED`/`ACCEPTED` deste Agent. A primeira leitura marca a entrega, e o pedido vencido vira `EXPIRED` |
| `POST /run-requests/{id}/decision` | **nova**: `ACCEPTED` (com o `agentExecutionId` reservado), `REFUSED` (com motivo fechado) ou `NOT_STARTED`. Repetir a mesma decisão dá 200 `UNCHANGED`. Uma decisão diferente dá 409 `CONFLICT`; vencido ou revogado também dá 409; pedido de outra máquina dá 404 |

## 4. Estados do pedido (`run_requests.state`)

As transições são conferidas no gatilho, com o instante do servidor:

| De | Para |
|---|---|
| 1 `REQUESTED` | 2 `DELIVERED`, 3 `ACCEPTED`, 4 `REFUSED`, 7 `EXPIRED` ou 8 `REVOKED` |
| 2 `DELIVERED` | 3, 4, 7 ou 8 |
| 3 `ACCEPTED` | 5 `NOT_STARTED` ou 6 `RUN_LINKED` |

- A decisão é imutável.
- A aceitação só acontece no prazo.
- A revogação exige automation.execute.
- O terminal fica congelado, e só a contagem de leituras continua mudando.

## 5. Testes e prova

| Teste | Escopo |
|---|---|
| `ExternalPythonGatewayIT` (3) | monitoramento sem impressão; registro idempotente; pedido entregue, decidido e registrado; conflito; recusa; não iniciado; outra máquina; revogado; vencido |
| `ExternalPythonRunRequestIT` (4) | autorização de monitoramento; recusas no banco; leitura com origem; pedido com step-up e chave; revogação antes e depois da decisão; vencido |
| `PackageInspectorExternalTest` (3) | vetor `e71ca1e0…` reproduzido também pelo Agent e pelo gerador Python; recusas |
| `RunRequestContractTest` | rotas, códigos e segurança na OpenAPI |

Os testes existentes do canal (`AgentGatewayIT`, `AgentAdminIT`, `AutomationLifecycleIT`, `PackageInspectorTest`, contratos)
continuam verdes.

**Achado (sem relação com a missão):** `AgentGatewayIT.codigoVencidoCanceladoMalformadoOuComOrganizacaoTrocadaEUmSo401` é
instável. O código "adulterado" troca o último caractere por `A`; quando ele já é `A`, o código fica válido, o que acontece em
1 de cada 64 vezes.
