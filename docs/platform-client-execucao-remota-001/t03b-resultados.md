# PLATFORM-CLIENT-EXECUCAO-REMOTA-001 — T03, parte b: resultados da tentativa (API)

Depende da T03 parte a (048) e dos ajustes para a execução (049).

Entrega, no servidor, **os resultados**: os arquivos que a automação gravou na pasta de saída e a máquina enviou depois de
executar. Não entrega registros de texto (stdout/stderr): T05.

## O que muda

| Onde | Mudança |
|---|---|
| Banco | migration `050_resultados_da_tentativa.sql`: `remote_attempt_results`, `remote_attempt_result_closures`, ato 12 na trilha da automação |
| Gateway (máquina) | `PUT /agent/api/v1/remote/attempts/{attempt}/results/{number}`; `POST …/results/complete` |
| Platform (pessoas) | `GET /o/{org}/remote-executions/{id}/attempts/{attempt}/results`; `GET …/results/{number}/content` |
| Configuração | `NEKKORA_RESULTADOS_DIR`: pasta própria dos resultados. O gateway **grava**; a Platform **lê** |
| Contratos | os dois OpenAPI (aditivos) |

## Regras

- **Só de tentativa que iniciou.** O token e a época da atribuição continuam valendo depois do término, para o envio.
- **Limites** (pontos de partida de laboratório): 50 arquivos, 20 MiB por arquivo, 50 MiB no total por tentativa. O total é
  guardado também no banco.
- **O que excede o limite não é enviado e é contado**: a máquina fecha o envio dizendo quantos arquivos mandou e quantos
  ficaram de fora por limite. A tela pode dizer "N arquivos não enviados: limite".
- **Resultado não muda o estado da tentativa.** Arquivo que falta, que excede o limite ou que não chegou não transforma uma
  execução concluída em falha, nem o contrário.
- O nome é **relativo à pasta de saída**, com `/`. Sem `..`, sem barra invertida, sem caminho absoluto, sem caractere de
  controle, até 200 caracteres. Conferido na aplicação e no banco.
- O servidor **confere o SHA-256** declarado contra os bytes que chegaram, grava os bytes (duráveis, endereçados pelo hash,
  por organização) e só então o metadado.
- Repetir o mesmo arquivo no mesmo número (resposta perdida) não muda nada. Outro arquivo no mesmo número, nome repetido ou
  envio depois do fechamento: `409 remote_result_conflict`. Passar do total: `409 remote_result_limit`.
- **Fechamento**: só depois que a tentativa terminou; a contagem tem de ser a que o servidor recebeu (um envio perdido
  aparece aqui). Sem fechamento, a lista diz que o envio não terminou.
- **Leitura e download exigem `logs.read`** no ambiente da solicitação. O download é sempre um anexo
  (`application/octet-stream`, `nosniff`), nunca interpretado pelo navegador, e fica na trilha da automação (ato 12) só
  quando os bytes são de fato entregues.
- Bytes removidos, ausentes ou que não conferem: `409 remote_result_unavailable`. O metadado continua na lista, com
  `available: false` quando a remoção foi registrada.

## Pontos para revisão

1. **Impacto de operação.** Uma pasta nova (`NEKKORA_RESULTADOS_DIR`), com escrita pelo gateway e leitura pela Platform. Feito
   só no ambiente de testes (`api/e2e/compose.artefatos.yml`). Homologação e produção: **não feito nem detalhado aqui**. Sem
   a pasta, o envio e o download respondem 503; a execução em si não é afetada.
2. **Retenção não implementada.** A tabela tem onde registrar a remoção dos bytes (`removed_at`), mas nenhum papel tem
   permissão de marcá-la e não há tarefa que apague arquivos. Os 30 dias propostos no plano **não estão em vigor**: hoje nada
   é apagado.
3. O arquivo inteiro é lido para a memória no envio e no download (até 20 MiB).

## Compatibilidade

Aditivo. Sem a pasta de resultados configurada, nada muda para o que já existia.

## Testes (executados neste PR)

| Requisito | Teste |
|---|---|
| Envio só de tentativa que iniciou; nomes recusados; SHA-256 conferido; bytes no armazenamento; repetição; conflito; token obsoleto; outra máquina; subpasta, acento e arquivo vazio; estado da tentativa inalterado; fechamento e contagem; nada depois do fechamento; limite total; guardas do banco | `RemoteDeliveryIT.aMaquinaEnviaOsResultadosDaTentativaQueExecutouEFechaOEnvio` |
| Lista, fechamento com o que ficou de fora, download como anexo, trilha só no download entregue, bytes ausentes, permissão | `RemoteExecutionIT.osResultadosSaoListadosEBaixadosSoPorQuemPodeVerRegistrosEODownloadFicaNaTrilha` |
| Contratos | `GatewayContractTest`, `AutomationLifecycleContractTest`, `AccessContractTest` |

## Fora deste PR / não provado

- Client (enumerar a pasta de saída, enviar, fechar) e Web (listar e baixar): PRs próprios.
- Retenção e remoção dos arquivos.
- Arquivo acima de 20 MiB recusado pelo filtro de tamanho: não exercitado por teste (o teste do total usa 11 MiB).
- Nenhum teste com máquina real.
