# PLATFORM-CLIENT-EXECUCAO-REMOTA-001 — T01c, parte 1: inventário de Python da máquina (API)

Depende da T01a (042). Vem depois da T01b (043) só na numeração das migrations.

A T01c foi dividida. Esta parte entrega **o inventário**: a máquina habilitada diz o que tem de Python, e quem pode
executar no workspace consulta. Ficam para as próximas partes: a **declaração de dependências** da automação (seção 5.8
do plano) e a **verificação sob demanda e a escolha do ambiente** por máquina e automação (seção 5.9), que precisam do
canal de entrega da T02.

## O que muda

| Onde | Mudança |
|---|---|
| Banco | migration `044_inventario_de_python_da_maquina.sql` |
| Gateway (máquina) | `PUT /agent/api/v1/remote/python-environments` |
| Platform (pessoas) | `GET /api/v1/o/{org}/workspaces/{workspace}/remote-machines/{agent}/python-environments` |
| Contratos | `openapi/nekkora-agent-v1.yaml` e `openapi/nekkora-api-v1.yaml` (aditivos) |

## O que entra no inventário, e o que não entra

Por ambiente, só quatro campos:

| Campo | O que é |
|---|---|
| `environmentId` | identificador **opaco**, derivado na máquina (43 caracteres), estável para o mesmo ambiente na mesma máquina |
| `pythonVersion` | por exemplo `3.12.4` |
| `kind` | `INSTALLATION` ou `VIRTUAL_ENVIRONMENT` |
| `observedAt` | quando a máquina sondou o ambiente |

**Não entra**: caminho, nome de usuário ou de pasta, nome da máquina, lista de pacotes instalados, variável de ambiente.
Isso é garantido em três lugares: o controlador recusa qualquer campo a mais (`400`, o pedido inteiro); o serviço confere
o formato de cada valor; e o banco tem `CHECK` no identificador e na versão. A Platform não tem como voltar do
identificador a um caminho, e nunca envia caminho à máquina.

## Regras

- É um **retrato**: cada publicação substitui a anterior da mesma máquina, numa transação. Recusa não muda o retrato.
- Lista vazia é válida: a máquina não achou Python.
- No máximo 32 ambientes; identificador repetido recusa.
- Só máquina **habilitada** publica (vinculada mas não habilitada: `409 remote_execution_not_enabled`).
- Limite: 6 publicações por minuto por máquina (`429` com `Retry-After`).
- Consulta: exige `automation.execute` no workspace; só para máquina habilitada, ativa, associada e com o Worker
  autorizado naquele workspace. Qualquer outra situação é `404`.

Publicar o inventário **não escolhe ambiente, não verifica dependências e não autoriza execução**. É o que a máquina
disse; a prova de que uma automação roda num ambiente é a verificação sob demanda (próxima parte).

## Banco (044)

`agent_python_environments`: retrato por máquina. A máquina (`nekkora_ingestion`) apaga e insere as próprias linhas, sob
gatilho (só máquina habilitada e ativa; no máximo 32); não altera. As pessoas (`nekkora_platform`) só leem. Aditiva; a
reversão é apagar a tabela (a máquina publica de novo). **Só aplicada no banco descartável dos testes.**

## Testes (executados neste PR)

| Requisito | Teste |
|---|---|
| Publicar substitui o retrato; lista vazia | `PythonInventoryIT.aMaquinaHabilitadaPublicaORetratoECadaPublicacaoSubstituiAAnterior` |
| Caminho, campo a mais, formato inválido, repetição, mais de 32: `400`, retrato intacto | `…nenhumCaminhoNemCampoAMaisEntraERecusaNaoMudaORetrato` |
| Só máquina habilitada; cada uma o próprio retrato; guardas do banco; limite por minuto | `…soAMaquinaHabilitadaPublicaECadaUmaSoOProprioRetrato` |
| Consulta: permissões, workspace, outra organização, máquina comum, revogada; sem campo de caminho | `RemoteMachinesIT.inventarioDePythonDaMaquinaSoParaQuemExecutaNoAmbienteESemCaminho` |
| Rotas publicadas | `GatewayContractTest`, `AgentContractTest` |

## Fora deste PR / não provado

- Client (descobrir, derivar o identificador opaco e publicar) e Web (mostrar o inventário): PRs próprios.
- Declaração de dependências; verificação sob demanda; escolha do ambiente por máquina e automação.
- Nenhum teste com máquina real.
