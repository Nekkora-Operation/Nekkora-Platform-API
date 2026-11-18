# PLATFORM-CLIENT-EXECUCAO-REMOTA-001 — T02: solicitação e entrega confiável (API)

Depende da T01a (042, habilitação), da T01c parte 2 (045, dependências declaradas) e das autorizações de execução que já
existiam (025/030).

Entrega, no servidor, **a solicitação** de uma execução remota e **a entrega** dela à máquina. Não entrega o pacote, não
guarda logs nem resultados (T03 e T05).

## Decisão da titular (15/11)

Para **aceitar** uma solicitação, exige-se:

1. pessoa com `automation.execute` no workspace (e confirmação reforçada);
2. Worker da máquina autorizado no workspace;
3. máquina habilitada, não pausada e compatível;
4. **autorização vigente da versão publicada para aquele Worker**.

- As condições são **revalidadas antes de iniciar**. Se a autorização expirar ou for revogada durante a espera ou a
  preparação, a tentativa **não inicia** e o motivo fica gravado.
- Na repetição em outra máquina, vale a autorização da mesma versão para o **Worker de destino**. Nada é transferido.
- A autorização continua válida para novas solicitações enquanto não vencer nem for revogada.
- Cada exigência que falta tem um motivo próprio, para a tela dizer o que falta.

Além dessas, as dependências da automação precisam estar declaradas e sem conflito com o pacote (T01c parte 2).

**Habilitar a máquina não cria nem substitui nenhuma autorização**, e a solicitação também não cria.

## O que muda

| Onde | Mudança |
|---|---|
| Banco | migration `046_solicitacao_de_execucao_remota.sql` |
| Platform (pessoas) | rotas `remote-executions` (abaixo) |
| Gateway (máquina) | `GET /agent/api/v1/remote/next`; `POST …/remote/attempts/{attempt}/ack` e `/state` |
| Configuração | `NEKKORA_EXECUCAO_REMOTA_OFERTA_MINUTOS` (15) na Platform; `NEKKORA_EXECUCAO_REMOTA_LEASE_SEGUNDOS` (90) no gateway |
| Contratos | os dois OpenAPI (aditivos) |

Os dois números são pontos de partida de laboratório, configuráveis.

## Solicitação e tentativa

- **Solicitação** (`remote_executions`): o pedido de uma pessoa. Fixa a versão publicada, a impressão do pacote e a
  revisão das dependências, com a lista efetiva congelada. Editar a declaração depois só vale para solicitações novas.
- **Tentativa** (`remote_attempts`): cada vez que a solicitação é mandada a uma máquina. Guarda a autorização usada (a do
  Worker daquela máquina), o estado, o motivo de uma recusa e os dados da entrega.

**A reserva é atômica**: no máximo uma tentativa não terminada por máquina e uma por solicitação (índices únicos
parciais). O estado mostrado antes de solicitar é só indicativo.

### Estados da tentativa

```
QUEUED → OFFERED → RECEIVED → PREPARING → STARTING → RUNNING → SUCCEEDED | FAILED | CANCELLED | TIMED_OUT
QUEUED | OFFERED → CANCELLED | EXPIRED | REFUSED
RECEIVED … STARTING → CANCELLED | REFUSED | ENDED_UNKNOWN          RUNNING → ENDED_UNKNOWN
```

- `REFUSED` tem motivo. Da máquina: `BUSY`, `PAUSED`, `PACKAGE_INVALID`, `ENVIRONMENT_GONE`,
  `ENVIRONMENT_INCOMPATIBLE`, `DEPENDENCIES_MISSING`, `DISK_PRESSURE`, `CLIENT_INCOMPATIBLE`. Do servidor (revalidação):
  `AUTHORIZATION_REVOKED`, `AUTHORIZATION_EXPIRED`, `AUTHORIZATION_MISSING`, `WORKER_NOT_AUTHORIZED`,
  `MACHINE_NOT_LINKED`, `MACHINE_NOT_ENABLED`, `MACHINE_INCOMPATIBLE`.
- `EXPIRED`: a máquina não recebeu dentro do prazo da oferta. A vaga é liberada.
- `ENDED_UNKNOWN`: a máquina voltou, o processo não está mais rodando e não há prova do resultado.
- **Comunicação** é um eixo à parte (`OK`, `LOST`, `NONE`). `LOST` (prazo vencido) **não muda o estado nem encerra a
  tentativa**.

## Rotas da pessoa

| Rota | O que faz |
|---|---|
| `GET …/workspaces/{ws}/remote-executions/candidates?automationVersion=` | máquinas habilitadas do workspace para a versão, com `missing`: o que impede solicitar em cada uma |
| `POST …/workspaces/{ws}/remote-executions` | solicita (step-up); `requestKey` idempotente |
| `GET …/workspaces/{ws}/remote-executions`, `GET …/remote-executions/{id}` | solicitação e tentativas |
| `POST …/remote-executions/{id}/cancel` | pede o cancelamento (step-up) |
| `POST …/remote-executions/{id}/attempts` | executar novamente em outra máquina (step-up) |

O que falta, na lista de máquinas (`missing`) e na recusa da solicitação (`409 conflict` + `reason`):

| `missing` | `reason` da recusa |
|---|---|
| `REQUIREMENTS_NOT_DECLARED` | `remote_requirements_not_declared` |
| `REQUIREMENTS_CONFLICT` | `remote_requirements_conflict` |
| `AUTHORIZATION_MISSING` / `_REVOKED` / `_EXPIRED` | `remote_authorization_missing` / `_revoked` / `_expired` |
| `MACHINE_PAUSED` | `remote_machine_paused` |
| `MACHINE_INCOMPATIBLE` | `remote_machine_incompatible` |
| `MACHINE_BUSY` | `remote_machine_busy` |
| — (máquina vinculada, não habilitada) | `remote_machine_not_enabled` |

A lista de máquinas exige só `automation.read`: quem consulta vê o que falta; a tela oferece a ação que resolve só a quem
tem a permissão dela.

- **Cancelar**: a tentativa que a máquina ainda não recebeu é cancelada na hora. A que já foi recebida só para quando a
  máquina obedecer e disser; até lá o pedido fica gravado e o estado não muda.
- **Executar novamente em outra máquina**: só depois que a tentativa anterior **terminou**
  (`remote_attempt_not_finished` enquanto não). Comunicação perdida não é término. Não há troca automática de máquina.

## Rotas da máquina

A máquina **puxa** (`remote/next`). A entrega dá um **token** e uma **época**; `ack` e `state` levam os dois. Perguntar
de novo antes de confirmar entrega a mesma tentativa com token e época novos: quem ficou com os anteriores recebe
`409 remote_attempt_stale` e não altera nada.

**Revalidação** (função `remote_attempt_blocker`, no banco, usada na criação, antes de entregar e antes de iniciar):

- antes de entregar: se algo deixou de valer, a tentativa fica `REFUSED` com o motivo e nada é entregue;
- antes de iniciar (`state: STARTING`): com o cancelamento pedido fica `CANCELLED`; com uma condição perdida fica
  `REFUSED`. A resposta traz `proceed: false` e a máquina **não inicia**.

Máquina pausada: nada é entregue; a tentativa espera até o prazo da oferta.

## Compatibilidade

O pedido de execução do Agent antigo (`run_requests`, 030) e as autorizações não mudaram. Com
`NEKKORA_EXECUCAO_REMOTA=false`, nenhuma solicitação é aceita (`remote_execution_unavailable`) e nada é gravado.

## Testes (executados neste PR)

| Requisito | Teste |
|---|---|
| Cada exigência que falta tem seu motivo; nada gravado até todas valerem; chave idempotente; máquina ocupada; retrato das dependências; autorização revogada e nova | `RemoteExecutionIT.cadaExigenciaQueFaltaTemOSeuMotivoENadaEGravadoAteTodasValerem` |
| Repetir em outra máquina: só após término; autorização do Worker de destino, nunca transferida | `…repetirEmOutraMaquinaUsaAAutorizacaoDoWorkerDeDestinoENuncaATransfere` |
| Cancelar antes e depois de a máquina receber | `…cancelarOQueAMaquinaNaoRecebeuEImediatoEOQueJaRecebeuDependeDela` |
| Permissões, step-up, outra organização, guardas do banco | `…permissoesConfirmacaoReforcadaIsolamentoEGuardasDoBanco` |
| Entrega com token e época; consumidor obsoleto não altera | `RemoteDeliveryIT.aMaquinaPuxaConfirmaEConduzOEstadoComOTokenEAEpocaVigentes` |
| Revalidação antes de iniciar e antes de entregar | `…antesDeIniciarOServidorRevalidaENaoIniciaSeAAutorizacaoCaiu` |
| Cancelamento, pausa e prazo da oferta | `…cancelamentoPausaEPrazoDaOferta` |
| Só a máquina da tentativa escreve; transições e motivos fechados | `…soAMaquinaDaTentativaEscreveETransicoesEMotivosSaoFechados` |
| Reserva, exigências e transições no banco | `…oBancoGuardaAReservaAsExigenciasEAsTransicoes` |
| Recurso desligado | `AgentAdminIT.comAExecucaoRemotaDesligadaNenhumCodigoNasceMarcado` |

Os testes de tela do gateway com a execução remota ligada passaram a compartilhar um contexto
(`RemoteEnabledGatewayIT`): cada classe com configuração própria esgotava as conexões do banco de teste.

## Fora deste PR / não provado

- Web (solicitar, ver o que falta, cancelar, repetir) e Client (puxar, confirmar, diário): PRs próprios.
- Pacote, execução, logs, resultados, limpeza: T03 a T05.
- Verificação das dependências na máquina e escolha do ambiente (resto da T01c).
- A autoria da solicitação e do cancelamento fica nas próprias linhas (imutáveis); não há ato novo na trilha de auditoria
  dos Agents.
- Nenhum teste com máquina real: a máquina é simulada por HTTP no gateway e por SQL na Platform.

## Complemento: pacote no formato Studio é recusado na solicitação

Decisão da titular (15/11): a execução remota aceita **apenas pacote Python externo** nesta primeira versão, e a recusa
tem de acontecer **na API, antes de criar a solicitação**.

- A regra do pacote é a gravada na versão publicada (`agent_package.fingerprint_rule`). Só
  `nekkora-agent/external-python-fingerprint/1` é aceita.
- `POST …/remote-executions` com outra regra: `409 conflict`, `remote_package_not_supported`. A conferência vem antes de
  todas as outras exigências e nada é gravado (nem solicitação, nem tentativa).
- Repetir em outra máquina (`POST …/attempts`) aplica a mesma recusa a uma solicitação antiga cujo pacote não é externo.
- A lista de máquinas traz `PACKAGE_NOT_SUPPORTED` em `missing` para todas as máquinas dessa versão. Não há ação que
  resolva: é do pacote, não da máquina.
- O aplicativo da máquina **mantém a própria conferência** (recusa com `CLIENT_INCOMPATIBLE`) como proteção adicional.
- Sem migration: a tabela continua aceitando as duas regras; a recusa é do serviço. Uma trava no banco é possível e
  **não foi feita** aqui.

Teste: `RemoteExecutionIT.pacoteNoFormatoStudioERecusadoAntesDeCriarASolicitacaoMesmoComTodoORestoValendo`. A base dos
demais testes de `RemoteExecutionIT` passou a usar a regra do pacote externo.
