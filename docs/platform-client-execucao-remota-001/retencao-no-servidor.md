# Retenção no servidor dos registros e dos arquivos de resultado (migration 057)

Decisão da titular (16/11):

- **Registros e arquivos de resultado: 30 dias, configuráveis**, contados a partir da **conclusão da entrega** ou do
  **encerramento explícito** dessa entrega.
- **Histórico mínimo da execução e auditoria: fora** desta exclusão automática.
- Mecanismo: reutilizar o existente se for adequado, com plano de permissões e migration revisáveis. **Isto não autoriza elevação
  nem aplicação em produção.**
- Remoção dos arquivos: um componente de backend com acesso restrito ao armazenamento. A limpeza registra o que venceu, remove os
  bytes e confirma a exclusão, com repetição segura em caso de falha. A Web informa que o conteúdo expirou.

## 1. Quando o prazo começa

O prazo é por **tentativa** e por **tipo** (registros; resultados) e conta do fim da entrega daquele tipo:

| Fim da entrega | O que aconteceu | Tipo |
|---|---|---|
| `CLOSED` | a máquina fechou o envio (050/051) | entrega concluída |
| `LOSS_NOTICE` | a máquina avisou que perdeu, pelo prazo de guarda dela, o que ainda guardava (052) | encerramento explícito |
| `LINK_ENDED` | a tentativa terminou e o vínculo sob o qual ela rodou foi encerrado — a máquina não tem mais como enviar (053) | encerramento explícito |

**Entrega aberta não vence.** Uma tentativa que terminou, cuja máquina continua vinculada e nunca fechou o envio nem avisou
perda, fica com o conteúdo guardado sem prazo. É a leitura literal da decisão; o limite está declarado na seção 8.

A data é calculada **pelo banco** (`remote_attempt_delivery_ended_at`), não informada pela aplicação.

## 2. O que sai e o que fica

| Sai | Fica |
|---|---|
| o **texto** de cada linha de registro | a linha: sequência, instante da captura, canal, se foi cortada, quando chegou |
| os **bytes** de cada arquivo de resultado | o arquivo: nome, tamanho, SHA-256, quando chegou |
| | a solicitação e a tentativa: estados, datas, causa do encerramento, código de saída, relato tardio |
| | os fechamentos dos envios e os avisos de perda da máquina |
| | a trilha (quem baixou qual resultado) |
| | o registro do que venceu |

Nenhum `DELETE` em tabela nenhuma.

## 3. O mecanismo existente (010/011/026): avaliado e não reutilizado

O expurgo que a Platform já tem foi desenhado para a contagem de consumo diário:

- é por **dia de recebimento**, com barreira na ingestão e fechamento verificável do dia — aqui o prazo é por tentativa, a partir
  do fim da entrega;
- exige política por organização e revisões; aqui a decisão é um prazo único configurável;
- as tabelas e funções dele pertencem a papéis próprios (`nekkora_retention_owner`, `nekkora_retention_tables_owner`): qualquer
  mudança exige a janela de **SUPERUSER** (foi o caso da 026) — e a elevação não está autorizada;
- o `EXECUTE` do expurgo dele **nunca foi concedido** a ninguém;
- ele não trata bytes fora do banco.

O que foi reaproveitado dele é o **princípio**: o conteúdo é apagado, a linha fica, e existe um registro do que venceu. As 050 e
051 já tinham nascido preparadas para isso (`removed_at`, `redacted_at`, com gatilhos que só aceitam essa mudança).

Do que a Platform já usa, a limpeza reaproveita o **ciclo agendado** (o mesmo do despacho de e-mail e da reconciliação da 055) e
a **lista de organizações** da 055.

## 4. A limpeza (`RemoteRetention`)

Por organização, em cada ciclo:

1. **Registra o que venceu**: uma linha em `remote_attempt_content_expirations` por tentativa e tipo. O banco reconfere que a
   entrega terminou e que o prazo venceu, e grava ele mesmo o fim da entrega, quantos itens e quantos bytes havia.
2. **Remove o conteúdo**: apaga o texto das linhas; para cada arquivo, remove os bytes do armazenamento, **confere que não estão
   mais lá** e só então grava `removed_at`.
3. **Confirma**: grava `confirmed_at`. O banco só aceita quando não sobra conteúdo.

**Repetição segura**

- Um vencimento registrado e não confirmado é retomado no ciclo seguinte.
- A remoção dos bytes não é transacional com o banco. Se os bytes saírem e a marca não for gravada, o ciclo seguinte encontra os
  bytes ausentes — que já é "removido" — e grava a marca.
- Os bytes são guardados por organização e SHA-256. Enquanto **outro resultado ainda presente** usar os mesmos bytes, eles ficam.
  A conferência e a remoção acontecem sob a mesma trava (por organização e SHA-256) que o recebimento de um resultado passa a
  tomar: a limpeza nunca remove os bytes que um envio está gravando.
- Duas instâncias da Platform não se atrapalham: o registro do vencimento é único por tentativa e tipo.

Depois do vencimento registrado, nada novo daquele tipo é aceito para a tentativa.

## 5. Plano de permissões (para revisão)

| Papel | Antes | Depois da 057 |
|---|---|---|
| `nekkora_platform` em `remote_attempt_log_lines` | `SELECT` | + `UPDATE (message, redacted_at)` |
| `nekkora_platform` em `remote_attempt_results` | `SELECT` | + `UPDATE (removed_at)` |
| `nekkora_platform` em `remote_attempt_content_expirations` | — | `SELECT`, `INSERT`, `UPDATE (confirmed_at)` |
| `nekkora_ingestion` em `remote_attempt_content_expirations` | — | `SELECT` (para recusar o que chegar depois do vencimento) |
| `nekkora_ingestion` nas linhas e nos resultados | `SELECT`, `INSERT` | inalterado: não altera, não apaga |

O que o banco garante além do privilégio:

- o texto de uma linha só é apagado, e um arquivo só é marcado como removido, com o vencimento daquela tentativa e tipo
  **registrado e ainda não confirmado**;
- o vencimento só é aceito com a entrega terminada e o prazo vencido, pelas datas do banco; prazo de 1 a 3650 dias;
- o registro do vencimento não é alterado nem apagado; a confirmação acontece uma vez.

**Sem elevação**: tabela e funções novas, gatilhos `SECURITY INVOKER`, concessões em tabelas do próprio dono das 050/051. Nenhum
papel novo, nenhuma função `SECURITY DEFINER`.

**Armazenamento**: só `RemoteRetention` recebe `ResultPurge`, o único tipo que remove bytes do armazenamento de resultados. Quem
recebe `ResultStore` lê (a Platform) ou grava (o canal das máquinas). Pacote publicado não tem remoção nenhuma.
Operacionalmente, o processo da Platform precisa poder **remover arquivos** em `NEKKORA_RESULTADOS_DIR` (hoje ele só lê).

## 6. Configuração

| Variável | Padrão | Significado |
|---|---|---|
| `NEKKORA_EXECUCAO_REMOTA_RETENCAO` | `false` | liga o ciclo. **Desligado por padrão**: ligar é decisão operacional de cada ambiente |
| `NEKKORA_EXECUCAO_REMOTA_RETENCAO_DIAS` | 30 | prazo, de 1 a 3650 |
| `NEKKORA_EXECUCAO_REMOTA_RETENCAO_CICLO_MINUTOS` | 60 | intervalo do ciclo |

## 7. O que a leitura mostra

`GET …/logs` e `GET …/results` ganham `expiration` (nulo = não venceu): quando venceu, quando a remoção foi confirmada, o prazo
aplicado, o fim da entrega e como, e quantos itens havia. As linhas continuam vindo, com `textRemoved = true`; os arquivos, com
`available = false`. O download de um arquivo removido responde 409 `remote_result_unavailable`.

## 8. Limites declarados

- **Entrega aberta não vence** (seção 1). Uma máquina que continua vinculada e nunca conclui o envio mantém o conteúdo sem prazo.
  Se isso não for o desejado, falta decidir um teto.
- O prazo é **um só para todas as organizações**. Não há política por organização.
- O armazenamento de resultados é uma **pasta local**. Não há armazenamento externo nesta missão, e a remoção só foi exercitada
  nele.
- **Não testado**: duas instâncias da Platform rodando o ciclo ao mesmo tempo; um envio real do mesmo conteúdo concorrendo com a
  remoção (a trava existe, mas não há teste de concorrência); o ciclo agendado em si (o teste chama o ciclo diretamente).
- A migration **não foi aplicada** fora do banco de teste e do laboratório isolado.

## 9. Teste

`RemoteExecutionIT.retencaoNoServidorVenceDoFimDaEntregaApagaSoOConteudoEPodeSerRepetida`:

- entrega concluída há 31 dias vence; há 29 dias não; entrega aberta não vence; vínculo encerrado há 31 dias vence como
  `LINK_ENDED`;
- o texto das linhas sai e as linhas ficam; os bytes saem e o nome, o tamanho e o SHA-256 ficam; a tentativa, o desfecho e os
  fechamentos ficam;
- bytes que outra tentativa ainda usa não são removidos, e a outra continua baixável;
- falha na remoção dos bytes: o vencimento fica registrado, nada é marcado como removido, o banco recusa a confirmação e o ciclo
  seguinte retoma;
- repetir o ciclo não muda nada;
- guardas do banco: sem vencimento não há remoção; entrega aberta e prazo não vencido são recusados; o canal das máquinas não
  registra vencimento; nada novo entra depois; o registro não muda nem some; outra organização não vê.
