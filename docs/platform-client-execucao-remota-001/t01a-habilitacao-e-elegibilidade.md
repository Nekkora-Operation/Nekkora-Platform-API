# PLATFORM-CLIENT-EXECUCAO-REMOTA-001 — T01a: habilitação da máquina e elegibilidade (API)

Primeira parte da T01. Entrega, no servidor, **como uma máquina passa a poder receber execuções solicitadas pela
Platform** e **como a Platform enxerga essas máquinas**. Não entrega solicitação, entrega, download, execução, logs nem
resultados (T02 em diante). Nada aqui é ligado por padrão.

Plano de contratos: `t00-plano-de-contratos.md` (PR do T00). Decisões da titular aplicadas: 1 (reaproveitar o código de
pareamento, marcado, com confirmação no Client e conferência de permissão no servidor) e 2 (contrato separado, ao lado do
antigo, preservando o comportamento dele).

## 1. O que muda

| Onde | Mudança |
|---|---|
| Banco | migration `042_habilitacao_de_execucao_remota.sql` |
| Platform (pessoas) | `POST /api/v1/o/{org}/agent-enrollments` aceita `remoteExecution`; `GET /api/v1/o/{org}/workspaces/{workspace}/remote-machines` |
| Gateway (máquina) | intenção `pair-remote` no resgate; `remote` no batimento; `POST /agent/api/v1/remote/receiving` |
| Configuração | `NEKKORA_EXECUCAO_REMOTA` (padrão `false`), lida pela Platform e pelo gateway |
| Contratos | `openapi/nekkora-api-v1.yaml`, `openapi/nekkora-agent-v1.yaml` (aditivos) |

## 2. Habilitar é um ato de duas partes

1. **Na Platform**, quem administra o vínculo e o Worker cria um código de pareamento com `remoteExecution: true`. São as
   mesmas permissões e a mesma confirmação reforçada (step-up) do pareamento comum, conferidas pela aplicação e de novo
   pelos gatilhos do banco. O código tem o mesmo formato; a marca fica no servidor.
2. **Na máquina**, a pessoa confirma e o aplicativo resgata o código declarando `Nekkora-Agent-Intent: pair-remote`.

O resgate cria o vínculo e a habilitação **na mesma transação**. Fica gravado **quem habilitou**: a pessoa que criou o
código (não a máquina, não quem vier a solicitar execuções).

O que **não** habilita:

- entrar no aplicativo com a conta de uma pessoa (o login não passa por nenhuma destas rotas);
- um código comum resgatado com `pair-remote` → `409 conflict`, `reason: code_not_for_remote_execution`;
- um código marcado resgatado sem a confirmação da máquina (um Agent anterior, ou `pair`) → `409`,
  `reason: remote_code_without_enablement`;
- um código de rotação (nunca é marcado: `CHECK` no banco);
- escrita direta no banco: a linha de habilitação só nasce se o código marcado foi consumido **nesta transação**, para o
  mesmo Agent, Worker e criador. Um código marcado já consumido não habilita outra máquina depois; uma máquina já pareada
  não é habilitada por nenhuma rota;
- aprovações locais antigas: nada nelas é lido aqui.

Nas recusas, o código **não é consumido** e nada é criado (nem Agent, nem associação, nem Worker).

## 3. Recurso desligado

Com `NEKKORA_EXECUCAO_REMOTA=false` (o padrão):

- criar código marcado → `409 conflict`, `reason: remote_execution_unavailable`, nada gravado (nem o Worker);
- resgatar código marcado → o mesmo `409`, código não consumido;
- a lista de máquinas responde `200` com `items` vazio (não há como existir máquina habilitada);
- pareamento comum, rotação, batimento, catálogo e execuções locais: **como antes, campo por campo**.

A variável precisa ter o mesmo valor na Platform e no gateway. Ligada só na Platform, cria-se o código mas o resgate é
recusado; ligada só no gateway, nenhum código marcado existe.

## 4. Pausar e retomar

`POST /agent/api/v1/remote/receiving` com `{"receiving":"PAUSED"|"ENABLED"}`. Só a própria máquina, pela credencial.
Idempotente; cada mudança real gera um registro de auditoria. Pausar não cancela o que está em andamento e não encerra o
vínculo. A Platform (pessoas) não pausa nem retoma por ninguém: o papel `nekkora_platform` só lê a tabela.

O servidor é a referência: o batimento da máquina habilitada devolve `remote.receiving`.

## 5. O que a Platform enxerga

`GET …/workspaces/{workspace}/remote-machines` exige `automation.execute` no ambiente (sem ela: `404`, como as demais
leituras do ambiente). Entram as máquinas habilitadas cujo Agent está ativo e associado a um Worker **autorizado naquele
ambiente**. `state` é o primeiro que valer:

| state | Quando |
|---|---|
| `OFFLINE` | sem contato autenticado nos últimos 180 s (a mesma janela de `GET /agents`) |
| `INCOMPATIBLE` | o aplicativo não declara `REMOTE_RUN_1_0` |
| `PAUSED` | a máquina pausou o recebimento |
| `NO_DISK_SPACE` | a máquina informou pouco espaço no último batimento |
| `BUSY` | a máquina informou uma execução em andamento |
| `AVAILABLE` | nenhum dos anteriores |

O estado é **indicativo**. Não reserva a máquina; a reserva será decidida de forma atômica quando a execução for
solicitada (T02). `BUSY` e `NO_DISK_SPACE` são o que a máquina disse no último batimento e valem até o próximo.

Esta lista **não autoriza versão alguma**. As autorizações vigentes de automação e monitoramento
(`worker_workspace_assignments`, `local_run_authorizations`) continuam como estão; a T02 as conferirá ao aceitar uma
solicitação (decisão 3 da titular).

## 6. Banco (042)

- `agent_enrollments.remote_execution` (`false` por padrão; só em código de pareamento).
- `agent_remote_enablements`: uma linha por Agent habilitado, com Worker, código de origem, quem habilitou, quando e o
  recebimento. RLS forçada por organização. `nekkora_platform`: só leitura. `nekkora_ingestion`: leitura, inserção (sob o
  gatilho acima) e alteração **apenas** de `receiving`. Ninguém apaga.
- `agent_presence`: quatro capacidades novas (`REMOTE_RUN_1_0`, `REMOTE_LOGS_1_0`, `REMOTE_RESULTS_1_0`,
  `PY_INVENTORY_1_0`), limite de 12 por batimento, colunas `remote_busy` e `disk_pressure`.
- `agent_admin_events`: atos 15 (habilitada), 16 (pausada) e 17 (retomada), com a máquina como autora.

Não remove nem reescreve nada das migrations anteriores. Aplicada em produção ou homologação, sem ligar a variável, não
muda comportamento. **Não foi aplicada em nenhum ambiente além do banco descartável dos testes.**

Reversão: o cabeçalho da migration traz a consulta de conferência e os passos; como é aditiva, basta desligar a variável
para voltar ao comportamento anterior sem tocar no banco.

## 7. Compatibilidade com o Agent antigo

- Sem cabeçalho de intenção, com `pair` ou `rotate`: respostas idênticas às de antes (o campo `remoteExecution` só existe
  quando é `true`; `remote` no batimento só para a máquina habilitada).
- O Agent antigo não envia `remote` no batimento; nada é exigido dele.
- Nenhuma rota, tabela ou gatilho do contrato antigo de pedidos de execução (`run_requests`) foi alterado.

## 8. Testes (executados neste PR)

| Requisito | Teste |
|---|---|
| Código marcado + confirmação da máquina habilita e grava quem habilitou; repetição não duplica | `RemoteEnablementIT.codigoMarcadoComAConfirmacaoDaMaquinaHabilitaEGravaQuemHabilitou` |
| Código e intenção precisam casar; recusa não consome; pareamento comum inalterado | `RemoteEnablementIT.codigoEIntencaoPrecisamCasarENadaEConsumidoNaRecusa` |
| Capacidades novas, sinais e recebimento no batimento | `RemoteEnablementIT.batimentoAceitaAsCapacidadesNovasEDevolveORecebimento` |
| Pausar/retomar: da máquina, idempotente, auditado; não habilitada → 409 | `RemoteEnablementIT.pausarERetomarSaoDaMaquinaIdempotentesEAuditados` |
| O banco recusa habilitação fora do resgate, alteração e exclusão; isolamento entre organizações | `RemoteEnablementIT.oBancoSoAceitaAHabilitacaoPeloResgateDoCodigoMarcado`, `RemoteMachinesIT.aHabilitacaoSoNasceNaTransacaoDoResgateDoCodigoMarcado` |
| Recurso desligado (gateway) | `RemoteEnablementDisabledIT` |
| Recurso desligado (Platform) | `AgentAdminIT.comAExecucaoRemotaDesligadaNenhumCodigoNasceMarcado` |
| Código marcado: permissões, step-up, resposta comum inalterada | `RemoteMachinesIT.codigoMarcadoExigeAsMesmasPermissoesEAConfirmacaoReforcadaEARespostaComumNaoMuda` |
| Lista: seis estados, ambiente, permissões, outra organização, Agent revogado | `RemoteMachinesIT.maquinasHabilitadasDoAmbienteComOEstadoIndicativo` |
| Rotas e motivos publicados | `AgentContractTest`, `GatewayContractTest` |

Nos testes da Platform o resgate é simulado por SQL com o papel real `nekkora_ingestion`, numa transação, como o gateway
faz; o resgate por HTTP é exercitado nos testes do gateway.

## 9. Fora deste PR / não provado

- Client (confirmação na máquina, credencial protegida, pausar) e Web (criar o código marcado, ver as máquinas): PRs
  próprios da T01a.
- Renovação da credencial sem refazer a habilitação: T01b.
- Inventário de Python e dependências: T01c.
- A decisão 3 (alcance por autorizações vigentes) só passa a ser **aplicada** na T02; aqui ela apenas não é contornada.
- Nenhum teste com máquina real, segundo computador ou desligamento: os testes são de integração com PostgreSQL e Redis
  reais em contêiner.
