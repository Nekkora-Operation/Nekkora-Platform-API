# Encerramento sem confirmação (migration 053)

PLATFORM-CLIENT-EXECUCAO-REMOTA-001, fechamento. Corrige o achado de 16/11: uma tentativa recebida pela máquina só mudava de
estado quando a própria máquina informava; se o vínculo fosse encerrado ou a máquina nunca mais se comunicasse, a tentativa
ficava "Em execução" para sempre.

## Três situações, três tratamentos

| Situação | O que acontece com a tentativa ativa |
|---|---|
| **Pausar novos trabalhos** (a máquina pausa o recebimento) | Nada. A tentativa em andamento continua sendo acompanhada até terminar; só não chega trabalho novo. Já era assim (T01a/T02); não mudou. |
| **Encerrar o vínculo** — a máquina se desvincula, ou quem administra revoga a máquina | A tentativa é encerrada **na mesma transação** do encerramento do vínculo. Depois dela a máquina não tem mais credencial: não informa estado, não envia registros nem resultados (a revogação não é contornada). |
| **Perder a comunicação** | O prazo da comunicação vencido continua sendo só "sem comunicação". Vencido há mais que a **espera configurada**, a tentativa é encerrada como sem confirmação. |

## O que o servidor grava ao encerrar

O servidor grava só o que **sabe**. Nunca sucesso, falha nem cancelamento, e nunca código de saída.

| Estado em que a tentativa estava | Fica | Por quê |
|---|---|---|
| Na fila, entregue, recebida ou em preparação (1 a 4) | `REFUSED` — "não iniciou" — com `refusalReason` `MACHINE_NOT_LINKED` ou `COMMUNICATION_LOST` | O início depende da resposta do servidor ao pedido `STARTING`. Sem ela, o processo não foi iniciado. |
| Autorizada a iniciar ou em execução (5 e 6) | `ENDED_UNKNOWN` — "Resultado não confirmado" | Não há evidência de como terminou, nem de que terminou. |

`closureCause` diz por que o **servidor** encerrou: `MACHINE_UNLINKED`, `MACHINE_REVOKED` ou `COMMUNICATION_LOST`. Nulo quando o
desfecho veio da máquina.

O banco confere o fato antes de aceitar a causa (gatilho da 053): vínculo encerrado = a máquina não está mais ativa e associada
ao Worker da tentativa; comunicação perdida = tentativa recebida com o prazo da comunicação vencido. Fora disso, a atualização é
recusada, venha do canal das máquinas ou do das pessoas.

## Espera da comunicação perdida

- Configuração: `NEKKORA_EXECUCAO_REMOTA_SEM_COMUNICACAO_MINUTOS`, **padrão 120** (o dobro do tempo máximo de uma tentativa, 60
  min). Aceita de 5 minutos a 30 dias. Tem de ter o mesmo valor na Platform e no agent-gateway.
- É um valor de partida de laboratório. **Muda o que o plano T00 dizia** ("`LOST` não muda o estado da tentativa nem a
  encerra"): agora encerra, depois da espera.
- A regra é a mesma nos dois lados: quando a máquina volta depois da espera, a tentativa **já está** encerrada (o gateway aplica
  a regra antes de olhar o pedido dela). O que a tela mostrou não muda depois.

## Relato tardio

Se a máquina voltar depois de um encerramento por `COMMUNICATION_LOST` e informar como o processo terminou, isso é guardado **ao
lado**: `lateReport { state, exitCode, reportedAt }`. O estado da tentativa **não muda** (uma nova tentativa pode já ter sido
pedida por uma pessoa). Uma vez só. Não existe para encerramento por vínculo (a máquina não tem mais como falar) nem para
tentativa que não iniciou.

## Entrega depois do encerramento

Encerrar o estado **não** encerra a entrega: registros, resultados, os dois fechamentos e o aviso de perda por prazo de guarda
continuam aceitos com o token da atribuição, como já era para qualquer tentativa terminada. O único impedimento é a credencial
da máquina deixar de valer.

## Onde a regra é aplicada

| Momento | Onde |
|---|---|
| Desvínculo pela máquina | `GatewayService.end` |
| Revogação por quem administra | `AgentAdminService.revokeAgent` |
| A máquina volta (qualquer escrita da tentativa, ou a consulta `next`) | `RemoteDelivery.owned` / `next` |
| Uma pessoa consulta uma solicitação ou a lista | `RemoteExecutionService.get` / `list`: se há tentativa parada, ela é encerrada numa transação de escrita própria e a consulta é refeita |
| Nova solicitação para a máquina, repetição, cancelamento | `RemoteExecutionService.reconcile` |

Não há tarefa agendada: a leitura encerra o que achar. Uma tentativa parada que ninguém consulta continua no banco no estado
antigo até a primeira leitura — a tela nunca a mostra em andamento.

Tentativas que já estavam paradas antes da 053 (máquina desvinculada sem o encerramento) são encerradas pela mesma leitura, com
a causa tirada de como o vínculo acabou.

## O que não faz

- Não troca de máquina, não cria tentativa nova, não libera repetição sozinho: repetir continua sendo ato de uma pessoa, com a
  autorização do Worker de destino.
- Não para processo nenhum: isso é do aplicativo da máquina (PR próprio do Client).
- Não entrega depois o que a máquina desvinculada guardou: uma nova habilitação cria outra máquina, que não escreve na tentativa
  da anterior. O plano T00 previa essa entrega ("se a máquina for habilitada de novo… são entregues"); **não foi implementada**,
  por contornar a revogação.

## Testes

| Requisito | Teste |
|---|---|
| Desvínculo encerra a tentativa da máquina (fila, preparação, execução) e só a dela; depois a máquina não escreve nada | `RemoteDeliveryIT.desvinculoPelaMaquinaEncerraATentativaDelaSemInventarDesfecho` |
| Comunicação perdida: antes da espera nada muda; depois, sem confirmação; relato tardio ao lado, uma vez; entrega continua aceita | `RemoteDeliveryIT.semComunicacaoAlemDaEsperaATentativaFicaSemConfirmacaoEORelatoTardioFicaAoLado` |
| O banco só aceita o encerramento com o fato conferido, e nunca como sucesso, falha ou cancelamento | `RemoteDeliveryIT.oBancoSoAceitaOEncerramentoPeloServidorComOFatoConferido` |
| Revogação, leitura que nunca fica "em execução", tentativa já parada, máquina liberada, repetição por ato de pessoa | `RemoteExecutionIT.revogacaoEPerdaDeComunicacaoEncerramATentativaSemInventarDesfechoEALeituraNuncaFicaEmExecucao` |
| Pausar não altera a tentativa em andamento: ela segue acompanhada até o desfecho informado pela máquina; só não chega trabalho novo | `RemoteDeliveryIT.pausarNovosTrabalhosNaoMexeNaExecucaoEmAndamentoQueSegueAcompanhadaAteTerminar` |

## Decisão de 16/11: a espera vencida não libera nada sozinha

A titular aceitou a espera configurável (padrão 120 minutos) para **consolidar** "Resultado não confirmado", com duas condições:

1. **A máquina não é dada por livre.** Encerrar por falta de comunicação não é saber que o processo terminou. Enquanto a
   máquina não voltar a se comunicar (nem relatar o desfecho), ela aparece com `MACHINE_UNCONFIRMED` nos candidatos e uma
   solicitação para ela responde 409 `remote_machine_unconfirmed`. Deixa de valer no primeiro contato dela depois do
   encerramento: a própria máquina é a autoridade sobre estar ocupada e recusa o que não puder receber.
   Tentativa que **nunca iniciou** (o servidor não autorizou o início) não tem processo e não deixa a máquina nessa condição.
2. **A repetição não é liberada sozinha.** "Executar novamente em outra máquina" depois de um encerramento por
   `COMMUNICATION_LOST` sem relato tardio exige `acknowledgeUnconfirmed = true`: a pessoa reconhece que a automação pode ainda
   estar em andamento naquele computador. Sem isso, 409 `remote_previous_result_unconfirmed` e nada é gravado. Continua
   exigindo a confirmação reforçada, e nunca acontece sem uma pessoa.

**Evidências posteriores continuam aceitas.** O relato tardio da máquina (como o processo terminou) é guardado ao lado, uma vez,
sem mudar o estado. Registros e resultados continuam aceitos depois do encerramento, com o token da atribuição.
