# Reconciliação de uma tentativa com resultado não confirmado (migration 058)

Decisões da titular (16/11). Uma tentativa em "Resultado não confirmado" **bloqueia a repetição** da solicitação e deixa a
máquina **indisponível** até ser reconciliada. Há duas reconciliações, e só elas.

## 1. Confirmada pela máquina

A própria máquina afirma, com `processAbsent: true` junto de um estado final, que **nada** daquela tentativa continua em execução
nela — o processo que ela lançou **e os processos filhos dele** (seção 8):

- junto de `ENDED_UNKNOWN` — o desfecho continua desconhecido, mas ela conferiu a árvore de processos;
- junto do desfecho verdadeiro (`SUCCEEDED`, `FAILED`, `CANCELLED`, `TIMED_OUT`) informado depois de o servidor já ter encerrado a
  tentativa. O desfecho sozinho, sem a afirmação, **não** reconcilia: fica como relato tardio.

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

- O aplicativo antigo não envia `processAbsent`: os "resultado não confirmado" que ele informar só saem por declaração manual,
  e a máquina dele continua indisponível — inclusive quando ele informa, depois, como o processo principal terminou.

## 8. Processos filhos e identificação incerta (decisão de 16/11)

Confirmar que o processo terminou considera **os filhos** da execução, não só o processo principal. A máquina só afirma
`processAbsent: true` quando:

- acompanhou a execução e **viu** o fim do processo principal (ou o encerrou);
- nem o processo principal nem nenhum descendente anotado durante o acompanhamento está vivo (mesmo PID e mesmo início).

Com algum vivo, ou sem como saber, ela **não afirma**. Casos que ficam sem afirmação:

| Caso | Por quê |
|---|---|
| um processo filho continua rodando depois do fim da automação | há processo ativo da tentativa |
| o aplicativo estava fechado quando o processo principal terminou | o fim não foi visto: pode ter ficado um filho que o sistema não associa mais à execução |
| o aplicativo fechou entre pedir o início e registrar a execução | não há processo conhecido para conferir |
| o aplicativo não conhece mais a execução | idem |
| o sistema não informa o início de um processo vivo com o mesmo PID | não dá para saber se é o mesmo |

Quando o fim foi informado sem a afirmação, o aplicativo **confere de novo a cada consulta** e, havendo prova, repete o mesmo
relato com `processAbsent: true` — o servidor grava a reconciliação pela máquina nesse momento. Informar o desfecho do processo
principal (`SUCCEEDED`, `FAILED`...) numa tentativa não confirmada **não** reconcilia mais por si: fica como relato tardio, ao lado.

**Limites conhecidos**

- Um descendente criado, depois da última anotação, por um processo que já terminou (no intervalo de uma volta do
  acompanhamento — 0,5 s — ou enquanto o aplicativo esteve fechado com o processo principal ainda vivo) não é visto.
- Nos casos em que a identificação é incerta **para sempre** (o fim não foi visto), a máquina não tem como confirmar: ela continua
  indisponível, e a tentativa só sai por declaração — que não libera a máquina. Não há, nesta versão, um ato que libere essa
  máquina. **Decisão pendente da titular.**
- A máquina só confirma o que ela mesma lançou e acompanha. Quando o aplicativo fechou entre pedir o início e registrar o
  processo, ele não tem o que conferir e não confirma.
- Uma máquina revogada não tem como confirmar: fica indisponível (já não recebe trabalho, por estar revogada).

## 9. Testes

- `RemoteExecutionIT.revogacaoEPerdaDeComunicacao…`: repetição bloqueada depois de revogação e de comunicação perdida; novo
  contato não libera; "ainda executa" vira ocupada; declaração manual com todos os controles e recusas; libera outra máquina e
  não a antiga; evidência tardia ao lado; guardas do banco.
- `RemoteExecutionIT.repetirEmOutraMaquina…`: bloqueada até a máquina confirmar.
- `RemoteDeliveryIT.resultadoNaoConfirmadoSoEReconciliadoPelaMaquina…` e o do relato tardio.
