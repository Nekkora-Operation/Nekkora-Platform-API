# PLATFORM-CLIENT-EXECUCAO-REMOTA-001 — T01c, parte 3: escolha do Python por máquina e automação (API)

Depende da T01c parte 1 (044, inventário), da parte 2 (045, dependências declaradas) e da T02 (046, solicitação e entrega).

Entrega, no servidor, **a escolha do ambiente Python** que uma automação usa **numa máquina** habilitada, e a leva até a
tentativa. Sem ela, o Client não teria como saber com qual Python executar.

## O que muda

| Onde | Mudança |
|---|---|
| Banco | migration `047_escolha_do_python.sql` |
| Platform (pessoas) | `PUT /o/{org}/automations/{automation}/remote-machines/{agent}/python-selection` |
| Platform (pessoas) | a lista de máquinas da versão (`remote-executions/candidates`) ganha `python` e dois itens em `missing` |
| Gateway (máquina) | a tentativa entregue (`remote/next`) ganha `pythonEnvironmentId` |
| Contratos | os dois OpenAPI (aditivos) |

## Regras

- A escolha é **por automação e por máquina**. A de uma máquina não vale para outra; repetir em outra máquina exige a escolha
  feita nela.
- Só pode ser escolhido um ambiente do **inventário atual** da máquina. A Platform guarda o identificador opaco que a própria
  máquina publicou e a versão que o inventário dizia. **Nunca um caminho**: nem guarda, nem envia.
- Exige `automation.manage` no ambiente da automação. Fica um ato na trilha da automação (ato 11).
- Trocar substitui a escolha. A tentativa guarda o **retrato** da escolha no momento em que nasce; trocar depois não muda
  tentativas já criadas.
- **Sem escolha, a solicitação é recusada** (`409 conflict`, `remote_python_not_selected`) e nada é gravado. Se o ambiente
  escolhido sumiu do inventário da máquina: `remote_python_not_in_inventory`. Nenhum outro ambiente é escolhido sozinho.
- A lista de máquinas diz o que falta: `PYTHON_NOT_SELECTED` ou `PYTHON_NOT_IN_INVENTORY`.

Esta exigência se soma às da decisão da titular para a T02 (pessoa com `automation.execute`, Worker no ambiente, máquina
habilitada, não pausada e compatível, autorização vigente da versão para o Worker) e à das dependências declaradas. Ela vem
do plano (T00, seção 5.9): **ponto para confirmação da titular**, por ser uma condição a mais para aceitar a solicitação.

## O que não faz

- **Não verifica as dependências** no ambiente e não instala nada. Quem confere o ambiente, antes de iniciar, é a máquina
  (T03): ambiente removido recusa com `ENVIRONMENT_GONE`; dependência ausente, com `DEPENDENCIES_MISSING`.
- **A verificação sob demanda** prevista no plano (a Platform pede, a máquina confere as dependências daquela automação
  naquele ambiente e responde, sem iniciar nada) **não está neste PR**. Até ela existir, a incompatibilidade só aparece
  quando uma solicitação é recusada pela máquina.
- Não revalida a escolha no momento da entrega: o retrato da tentativa é o que vale, e a máquina confere.

## Compatibilidade

Aditivo. As tentativas criadas antes da 047 ficam com o retrato nulo (nenhuma iniciou). Com `NEKKORA_EXECUCAO_REMOTA=false`,
a escolha responde `remote_execution_unavailable` e nada é gravado.

## Testes (executados neste PR)

| Requisito | Teste |
|---|---|
| Sem escolha nada é solicitado; só ambiente do inventário; caminho nunca aceito; versão vem do inventário; ato 11; retrato na tentativa, imutável; trocar substitui; inventário sem o ambiente; escolha não vale para outra máquina; permissão; guardas do banco | `RemoteExecutionIT.oPythonEEscolhidoPorMaquinaEAutomacaoESemEleNadaESolicitado` |
| As demais exigências da solicitação continuam valendo com a escolha feita | os outros testes de `RemoteExecutionIT` (o preparo agora publica o inventário e escolhe o Python pela API) |
| A tentativa entregue leva o identificador escolhido | `RemoteDeliveryIT.aMaquinaPuxaConfirmaEConduzOEstadoComOTokenEAEpocaVigentes` |
| Contratos | `AutomationLifecycleContractTest`, `AccessContractTest`, `GatewayContractTest` |

## Fora deste PR / não provado

- Web (escolher o Python na página da versão) e Client (usar o ambiente escolhido): PRs próprios.
- Verificação sob demanda das dependências na máquina.
- Nenhum teste com máquina real: o inventário é gravado por SQL com o papel da máquina.
