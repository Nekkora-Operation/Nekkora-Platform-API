# Processos da tentativa: ocupação, "finalizando" e identificação incerta (migration 060)

Decisões da titular (16/11). Complementa `reconciliacao-da-tentativa.md` (058) e `retencao-no-servidor.md` (057, 059).

## 1. A regra

Terminar o processo **principal** não libera a máquina enquanto houver **descendente** da tentativa ativo. Vale para a tentativa
não confirmada e para a execução **normal** (concluída, falhou, cancelada, tempo esgotado).

- O estado e o código de saída do processo principal ficam registrados na hora.
- A **ocupação** da máquina, a **repetição** da solicitação e a **limpeza** no computador esperam os demais processos.
- Se a máquina perder a capacidade de identificá-los, a ocupação passa a **incerta**.
- **Não liberam a máquina:** tempo, novo contato, fim do vínculo, declaração de reconciliação da tentativa.
- Processos permanentes em segundo plano não fazem parte desta versão: uma automação que os deixe mantém a máquina ocupada.

## 2. O que a máquina informa

Junto de um estado final, em `processTree` (canal das máquinas, `POST …/attempts/{t}/state`):

| Valor | Significado | Fato gravado | Leitura (`attempts[].processes`) | Máquina |
|---|---|---|---|---|
| `ABSENT` | tem **prova** de que nenhum processo da tentativa continua | 1 | nulo; `reconciliations` ganha `MACHINE` | livre |
| `ACTIVE` | o principal terminou e há processo vivo | 4 | `ACTIVE` | `MACHINE_FINISHING` — "Finalizando" |
| `UNCERTAIN` | não tem como saber | 5 | `UNCERTAIN` | `MACHINE_UNCONFIRMED` — ocupação incerta |
| ausente (aplicativo antigo) | — | execução normal: nada; não confirmada: 5 | — | normal: livre, como antes; não confirmada: incerta |

A máquina repete o **mesmo** relato quando o que ela sabe muda. Sem o fato 1, vale o mais grave: incerta (5) sobre finalizando
(4) sobre "ainda executa" (3). Por isso a máquina que disse "ainda executa" e depois informou o fim sem confirmar os processos
aparece com **ocupação incerta**, não "em andamento".

Repetição: enquanto a última tentativa tiver processo pendente e nenhuma confirmação (da máquina ou declarada), responde
`409 remote_previous_processes_pending` (ou `remote_previous_result_unconfirmed`, se não confirmada). A declaração de um
ORG_ADMIN libera a repetição em outra máquina; **não** libera a máquina.

## 3. Como o aplicativo identifica os descendentes (Windows)

Exceção de arquitetura, restrita: `WindowsSupervision`, a única classe da camada de execução com código nativo. Declara onze
funções do Windows, conferidas por teste (`ArchitectureTest.aSupervisaoDeProcessosSoChamaAsFuncoesDeclaradas`); nenhuma cria
processo, executa comando, abre shell ou lê outro processo.

**Grupo de processos (Job Object).** O processo que o aplicativo lança entra num grupo do próprio Windows, com nome derivado só do
identificador da execução. Todo descendente nasce dentro do grupo e não consegue sair — inclusive o neto de um filho que já
terminou, que nenhuma relação pai-filho mostra mais. O Windows informa **quantos processos do grupo estão vivos**. Não há PID a
comparar: a reutilização de PID não engana a contagem. A prova de que acabou é o Windows dizer que o grupo está **vazio**.

**Janela do lançamento.** Entre lançar e agrupar passam milissegundos. A cada volta do acompanhamento (0,5 s) o aplicativo põe
no grupo qualquer descendente que tenha nascido antes; se algum ficar de fora, o grupo deixa de servir de prova (ocupação incerta).

**Anotação.** Os processos vistos vivos são anotados (PID + instante de início) num arquivo da execução, no computador. Serve
para dizer "há processo vivo" depois de reabrir o aplicativo; **não** serve de prova de que acabou. Nada dele é enviado.

**Marca da sessão do Windows.** No lançamento o aplicativo grava um nome na tabela da sessão do Windows. Esse nome só deixa de
existir quando a sessão acaba — saída do usuário ou reinício do Windows — e quando ela acaba nenhum processo dela continua.
Não depende do relógio nem de PID. A falta da marca encerra a dúvida sobre os **processos**; não diz nada sobre o **resultado**
da automação, que continua como estava.

## 4. O que sobrevive ao encerramento do aplicativo

| Situação | O que o aplicativo reaberto consegue | Resultado |
|---|---|---|
| Aplicativo fechou e reabriu com o processo principal **vivo** | reabre o grupo pelo nome (o processo principal herda o acesso no lançamento) e volta a supervisionar com a mesma exatidão | prova quando o grupo esvaziar |
| Processo principal terminou com o aplicativo **fechado**, e há processo anotado vivo | vê o processo anotado vivo | `ACTIVE` |
| Idem, e nenhum processo anotado está vivo | o grupo não é mais alcançável | **`UNCERTAIN`** — evidência insuficiente |
| Aplicativo fechou durante o "finalizando" | idem às duas linhas acima | `ACTIVE` ou `UNCERTAIN` |
| A sessão do Windows acabou (saída do usuário ou reinício) | a marca da execução não existe mais | prova (`ABSENT`) |

## 5. Quando a evidência continua insuficiente

- O grupo não pôde ser criado, o processo não pôde ser agrupado, ou o Windows deixou de responder sobre ele.
- O processo principal terminou com o aplicativo fechado e nenhum processo anotado está vivo: pode haver um descendente de
  processo já terminado, que não aparece em lista nenhuma fora do grupo.
- Execução lançada por versão anterior do aplicativo (sem o arquivo de processos).
- A marca da sessão não pôde ser gravada no lançamento: a falta dela não prova nada.

Nesses casos a máquina informa `UNCERTAIN` e continua indisponível até a sessão do Windows acabar. Não há outro ato que a libere.

## 6. Limpeza no computador

Nada da pasta de uma tentativa (código, cópia executada, pasta de saída) é apagado enquanto não houver prova de que os processos
dela acabaram — nem pela limpeza ao terminar, nem pela varredura periódica, nem pelo prazo de guarda. Quando a prova chega, o
aplicativo faz a limpeza que estava esperando.

## 7. Retenção no servidor: dois prazos diferentes

| | Quando começa | Com execução ativa ou incerta |
|---|---|---|
| **Teto por falta de progresso** (059) | último conteúdo novo recebido | **não vence** — inclui "finalizando" e "incerta" (060) |
| **Retenção após encerramento explícito** (057) | entrega concluída, aviso de perda, ou **vínculo encerrado** | **vence**: o encerramento explícito do vínculo inicia o prazo mesmo com o resultado desconhecido |

Remover o conteúdo pelo prazo preserva o histórico mínimo, a auditoria e o registro do que venceu. **Não** confirma o término do
processo, **não** libera a máquina e **não** transforma o conteúdo removido em conteúdo entregue.

## 8. Compatibilidade e riscos

- **Aplicativo antigo:** não informa `processTree`. Execução normal: a máquina é liberada como antes. Tentativa não confirmada:
  ocupação incerta; só sai por declaração, e a máquina continua indisponível.
- **Automações que tentam tirar um processo do grupo** (`CREATE_BREAKAWAY_FROM_JOB`): o Windows recusa a criação desse processo. O
  grupo não permite a saída de propósito — permitir tornaria a supervisão cega.
- **Hibernação e suspensão:** não encerram a sessão; os processos continuam e a marca também — nada é dado por encerrado.
- **Desligamento com "inicialização rápida":** a sessão do usuário é encerrada no desligamento, então espera-se que a marca suma.
  **Não verificado.**
- **Não exercitado em laboratório:** o fim real da sessão do Windows (o computador de laboratório não pode ser desligado nem ter a
  sessão encerrada). Coberto só por teste que retira a marca.
