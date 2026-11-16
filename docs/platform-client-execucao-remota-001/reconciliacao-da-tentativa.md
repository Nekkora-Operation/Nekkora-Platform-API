# Reconciliação de uma tentativa com resultado não confirmado (migration 058)

Decisões da titular (16/11). Uma tentativa em "Resultado não confirmado" **bloqueia a repetição** da solicitação e deixa a
máquina **indisponível** até ser reconciliada. Há duas reconciliações, e só elas.

## 1. Confirmada pela máquina

A própria máquina afirma que o processo daquela tentativa não está mais em execução nela:

- ao informar um desfecho verdadeiro depois de o servidor já ter encerrado a tentativa (relato tardio);
- ao informar `ENDED_UNKNOWN` com `processAbsent: true` — o desfecho continua desconhecido, mas ela **conferiu** que o processo
  que ela mesma lançou não existe mais.

Sem essa afirmação (`processAbsent` ausente ou falso), nada é reconciliado.

Se a máquina volta e repete `RUNNING` para uma tentativa que o servidor já encerrou, fica registrado que ela **ainda executa**:
ela aparece **ocupada**. Ela não retoma nem recebe de novo a tentativa antiga (o servidor responde `proceed: false`).

## 2. Declarada por uma pessoa

`POST /api/v1/o/{org}/remote-executions/{execution}/attempts/{attempt}/reconciliation`

Para quando não é possível obter a confirmação da máquina.

| Controle | Como |
|---|---|
| Quem | só **ORG_ADMIN** da própria organização — conferido no serviço **e** no banco (gatilho) |
| Confirmação reforçada | exigida |
| Motivo | lista fechada: `MACHINE_OFF_OUT_OF_OPERATION`, `PROCESS_ENDED_BY_ADMINISTRATOR`, `OTHER_VERIFIED` |
| Descrição da verificação | obrigatória, 20 a 2000 caracteres |
| Efeitos parciais | `acknowledgePartialEffects` tem de ser `true`; sozinho **não** basta |
| Registro | autor, data, motivo, verificação, tentativa e máquina — nunca alterado nem apagado |
| Resultado anterior | continua `ENDED_UNKNOWN`, com a causa de antes; nada de sucesso, falha ou código de saída |
| O que libera | **uma nova tentativa** da solicitação, ainda sujeita à permissão, à confirmação reforçada, à autorização do Worker de destino e à escolha de máquina apta |
| O que não faz | não executa nada; **não libera a máquina antiga** |

Revogar o vínculo, esperar um prazo ou marcar "aceito o risco" não liberam nada. Se ninguém consegue verificar a situação da
máquina, o bloqueio fica.

## 3. A máquina antiga

Fica indisponível até **ela** confirmar: `MACHINE_BUSY` se disse que ainda executa, `MACHINE_UNCONFIRMED` se não confirmou nada.
Um novo contato não muda isso. A declaração manual também não.

## 4. Evidência tardia

O que a máquina informar depois de uma declaração manual é gravado em **outra linha** (`MACHINE`), ao lado da `MANUAL`: as duas
aparecem na leitura, e nenhuma altera a outra. O relato tardio do desfecho (053) continua nas colunas próprias.

## 5. Modelo

`remote_attempt_process_reports`, uma linha por tentativa e tipo: 1 = ausente, pela máquina; 2 = declaração manual; 3 = ainda
executa, pela máquina. Tipos 1 e 3 só pelo canal das máquinas; tipo 2 só pela Platform e só por ORG_ADMIN efetivo. Sem elevação.

Leitura: `attempts[].reconciliations` e `attempts[].machineStillRunningAt`.

## 6. O que saiu

`acknowledgeUnconfirmed` (PR #86) foi removido: reconhecer o risco ainda permitia duplicidade. A regra "a máquina fica livre no
primeiro contato" (PR #86) também.

## 7. Dados existentes

Tentativas que já estavam em "resultado não confirmado" ficam sem reconciliação: a repetição delas e a máquina delas ficam
bloqueadas até uma das duas reconciliações.

## 8. Limites

- O aplicativo antigo não envia `processAbsent`: os "resultado não confirmado" que ele informar só saem por declaração manual.
- A máquina só confirma o que ela mesma lançou e acompanha. Quando o aplicativo fechou entre pedir o início e registrar o
  processo, ele não tem o que conferir e não confirma.
- Uma máquina revogada não tem como confirmar: fica indisponível (já não recebe trabalho, por estar revogada).

## 9. Testes

- `RemoteExecutionIT.revogacaoEPerdaDeComunicacao…`: repetição bloqueada depois de revogação e de comunicação perdida; novo
  contato não libera; "ainda executa" vira ocupada; declaração manual com todos os controles e recusas; libera outra máquina e
  não a antiga; evidência tardia ao lado; guardas do banco.
- `RemoteExecutionIT.repetirEmOutraMaquina…`: bloqueada até a máquina confirmar.
- `RemoteDeliveryIT.resultadoNaoConfirmadoSoEReconciliadoPelaMaquina…` e o do relato tardio.
