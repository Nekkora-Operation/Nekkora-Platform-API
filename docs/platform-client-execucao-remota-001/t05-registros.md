# PLATFORM-CLIENT-EXECUCAO-REMOTA-001 — T05: registros (saída do processo) da tentativa

Lado da Platform. O Client e a Web têm PRs próprios.

## O que existe agora

A saída padrão e a saída de erro do processo de uma **tentativa remota** chegam à Platform em lotes, durante e depois da
execução, e são lidas por quem tem `logs.read` no ambiente.

**Captura de stdout/stderr é funcionalidade nova** (plano, seção 5.12): antes desta missão a saída do processo era lida e
descartada.

## Regras

- **Só tentativa remota.** Não existe rota para registro de execução iniciada no próprio computador: essa saída fica só
  no Client.
- **Só de tentativa que iniciou**, até o fechamento do envio.
- **Canal não é nível.** `STDOUT`, `STDERR` e `EXECUTOR` (aviso do aplicativo da máquina). O nível é sempre `UNKNOWN`;
  nada é classificado pelo texto. O formato estruturado com nível, previsto no plano, **não foi feito**.
- **Ordem**: a da captura pela máquina (`sequence`). Não é uma ordem global entre os dois canais.
- **O resultado do robô não muda** por causa de registro: linha que falta, cortada ou descartada não altera o estado nem
  o código de saída da tentativa.

## Limites (pontos de partida de laboratório)

| Limite | Valor | Ao atingir |
|---|---|---|
| Linha | 8 KiB | a máquina corta e marca `truncated` |
| Lote | 200 linhas, 256 KiB | lote maior: 400 |
| Por tentativa | 50.000 linhas e 20 MiB | `409 remote_log_limit`; nada do lote é gravado; a máquina conta o excedente e informa no fechamento (`droppedLines`) |

O limite por linha, a sequência sem buraco e o total por tentativa também são guardados no banco.

## Rotas

**Máquina (gateway)**

| Rota | Para quê |
|---|---|
| `POST /agent/api/v1/remote/attempts/{t}/logs` | lote de linhas. Corpo: `token`, `epoch`, `lines[]` (`sequence`, `capturedAt`, `channel`, `truncated`, `message`) |
| `POST /agent/api/v1/remote/attempts/{t}/logs/complete` | fecha o envio: `lines` (enviadas) e `droppedLines` (descartadas por limite) |

- Lote repetido depois de resposta perdida não duplica: linha já guardada com o mesmo conteúdo é ignorada.
- Buraco na sequência, linha repetida com outro conteúdo, envio já fechado: `409 remote_log_conflict`.
- Fechamento só depois do término; a contagem tem de ser a que o servidor recebeu.
- Com a tentativa em andamento, o lote renova o prazo da comunicação.

**Pessoas (Platform)**

| Rota | Para quê |
|---|---|
| `GET /api/v1/o/{org}/remote-executions/{x}/attempts/{t}/logs?after=&limit=` | linhas a partir de `after` (exclusivo), até 500 por página; `total`, `nextAfter` e o fechamento |

Exige `logs.read`. Fora do alcance: 404.

## Segredos

Antes de gravar, o servidor remove do texto: credencial de máquina (`nka1.…`), código de pareamento (`nkp1.…`), valor de
cabeçalho de autorização (`Authorization: …`, `Bearer …`) e o token da própria atribuição. O Client remove antes de
enviar; o servidor é a segunda barreira.

**Não há promessa** sobre outros dados que o próprio robô imprima (senhas próprias, dados pessoais).

## Banco: migration 051 (candidata, sem elevação)

- `remote_attempt_log_lines`: tentativa, sequência, instante da captura, canal, nível, cortada, texto, recebida em,
  texto apagado em.
- `remote_attempt_log_closures`: linhas enviadas, linhas descartadas, quando fechou.
- A máquina insere e lê; as pessoas só leem. Ninguém altera nem apaga por estes papéis.
- A única alteração prevista é a retenção apagar o **texto** de uma linha, uma vez; o registro de que a linha existiu fica.

## Testes

| O que | Teste |
|---|---|
| Lotes, sequência, repetição sem duplicar, conflito, segredos removidos, fechamento, limite de 20 MiB, guardas do banco, resultado inalterado | `RemoteDeliveryIT.aMaquinaEnviaASaidaDoProcessoEmLotesEFechaOEnvioSemMudarOResultado` |
| Leitura paginada, canais, `truncated`, descarte informado, permissão, a Platform não escreve nem apaga | `RemoteExecutionIT.osRegistrosDaTentativaSaoLidosEmPaginasSoPorQuemPodeVerRegistrosEDizemOQueFoiDescartado` |
| Rotas publicadas no contrato | `GatewayContractTest` (22 rotas), `AccessContractTest` |

## Não feito nesta PR

- **Retenção** (30 dias no plano): não implementada. O banco já aceita apagar o texto; falta a tarefa e a permissão dela.
- **Nível com evidência** (linha estruturada): não feito.
- **Trilha de quem leu os registros**: a leitura não é auditada (o download de resultado é).
- **Leitura ao vivo**: a Web relê; não há envio do servidor para o navegador.
- Limite de corpo do proxy para o lote (até 256 KiB de texto mais o JSON): não testado fora do laboratório.
