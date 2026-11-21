# Reconciliação periódica das tentativas (migration 055)

Decisão da titular (16/11): o estado de uma tentativa **não pode depender de alguém abrir a tela**. A 053 encerrava a tentativa
que não tem mais quem a conclua em dois momentos: quando a máquina voltava e quando uma pessoa consultava. Esta entrega
acrescenta o terceiro: um ciclo do próprio servidor.

## Mecanismo

Reaproveita o que a Platform já usa para o despacho de e-mail: um ciclo agendado dentro do próprio processo
(`ScheduledExecutorService`), sem serviço novo, sem fila nova e sem papel novo no banco.

- `RemoteReconciler.cycle()` lê a lista das organizações que já tiveram tentativa de execução remota e entra em cada uma, numa
  transação própria, com o contexto dela.
- Em cada organização aplica **as mesmas regras** da leitura e do canal das máquinas (`AttemptClosure`):
  - oferta que a máquina não recebeu no prazo → `EXPIRED`;
  - máquina que não está mais vinculada → "não iniciou" ou "resultado não confirmado", com a causa;
  - tentativa recebida e sem comunicação além da espera → o mesmo, com a causa `COMMUNICATION_LOST`.
- Nunca grava sucesso, falha, cancelamento nem código de saída. O banco confere o fato antes de aceitar (053).
- Repetir é seguro; duas instâncias da Platform não se atrapalham; a falha numa organização não interrompe as outras.

## A lista das organizações

As tabelas da execução remota só são visíveis com o contexto de uma organização. Para o ciclo saber onde olhar, a 055 cria
`remote_execution_organizations`: uma linha por organização, gravada por gatilho na primeira tentativa (as que já tinham
tentativas entram na própria migration).

É a **única leitura sem contexto** desta missão e devolve **só identificadores de organização**. A Platform não altera nem apaga
a lista; o canal das máquinas não a lê. Sem contexto, as tentativas continuam invisíveis.

## Configuração

| Variável | Padrão | Significado |
|---|---|---|
| `NEKKORA_EXECUCAO_REMOTA_RECONCILIACAO_SEGUNDOS` | 60 | intervalo do ciclo (5 a 3600); `0` desliga o ciclo |
| `NEKKORA_EXECUCAO_REMOTA_SEM_COMUNICACAO_MINUTOS` | 120 | espera depois de o prazo da comunicação vencer (1 minuto a 30 dias) |

O ciclo só existe com `NEKKORA_EXECUCAO_REMOTA=true`. O piso da espera passou de 5 minutos para 1, para o ensaio de laboratório.

## Sem elevação

Tabela nova e gatilho `SECURITY INVOKER`. Nenhum papel novo, nenhuma função `SECURITY DEFINER`.

## Teste

`RemoteExecutionIT.oCicloDoServidorEncerraSemLeituraESemAMaquinaOQueNaoTemMaisQuemConclua`: sem nenhuma leitura e sem contato de
máquina, o ciclo encerra a tentativa sem comunicação, a que nunca iniciou, a de máquina desvinculada e a oferta vencida; não toca
na que ainda está dentro da espera nem na de outra organização; repetir não muda nada; privilégios da lista conferidos.
