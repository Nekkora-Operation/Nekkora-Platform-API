# PLATFORM-CLIENT-EXECUCAO-REMOTA-001 — T03, parte a: a máquina baixa o pacote da tentativa (API)

Depende da T02 (046) e da T01c parte 3 (047). A T03 foi dividida: esta parte entrega o **download do pacote**; os
**resultados** vêm na parte b.

## O que muda

| Onde | Mudança |
|---|---|
| Banco | migration `048_pacote_para_a_maquina.sql` (**versão restrita**, ver abaixo): tabela `remote_attempt_packages`, o pacote fixado em cada tentativa. O canal da máquina **não** recebe privilégio em `automation_artifacts` |
| Gateway (máquina) | `GET /agent/api/v1/remote/attempts/{attempt}/package` |
| Código | `ArtifactStore` e `LocalArtifactStore` passam do módulo da Platform para o módulo comum (`nekkora-core`), sem mudar de comportamento |
| Configuração | o gateway passa a ler `NEKKORA_ARTEFATOS_DIR` (a mesma pasta da Platform), **só para leitura** |
| Contrato | OpenAPI da máquina (aditivo) |

## Regras do download

- Só a **máquina da tentativa**, com o **token e a época vigentes**, em cabeçalhos (`Nekkora-Attempt-Token`,
  `Nekkora-Attempt-Epoch`), nunca na URL. Outra máquina: 404. Token ou época antigos: `409 remote_attempt_stale`.
- Só **entre o recebimento e o início** (`RECEIVED` ou `PREPARING`). Antes de confirmar o recebimento ou a partir de
  `STARTING`: `409 remote_attempt_transition`.
- O pacote é o da **impressão congelada na solicitação**. Nenhuma outra versão, pacote ou tentativa é alcançável por esta rota.
- O servidor **relê e confere os bytes pelo SHA-256** a cada download. Ausentes, corrompidos ou de outro tamanho:
  `409 remote_package_unavailable`, sem devolver byte nenhum. A máquina deve recusar a tentativa com `PACKAGE_INVALID`.
- Armazenamento não configurado ou inacessível: 503.
- A resposta traz `Nekkora-Package-Sha256`. A máquina confere tamanho, SHA-256 e impressão **antes de usar**.
- Baixar renova o prazo da comunicação. Não muda o estado da tentativa.

## Dois pontos para revisão

1. **A 048 foi reescrita na versão restrita** (aprovada pela titular em 16/11; PR próprio). A primeira redação dava ao
   canal da máquina a leitura de `sha256` e `size_bytes` de `automation_artifacts`, limitada só pela organização. Ela
   **não vale mais** e não foi aplicada em nenhum ambiente real. Ver "A 048 restrita".
2. **Impacto de operação.** O gateway precisa enxergar a pasta dos pacotes que hoje só a Platform monta. No E2E a pasta
   passa a ser montada no gateway como somente leitura (`api/e2e/compose.artefatos.yml`). Em homologação e produção isso
   é uma mudança de implantação que **não foi feita nem planejada em detalhe aqui**. Sem ela, o download responde 503 e
   nenhuma execução remota inicia.

## A 048 restrita

**O que o banco passa a limitar.** Antes, só o código do gateway limitava máquina, versão e momento.

| Limite | Como |
|---|---|
| Máquina | a linha de `remote_attempt_packages` só é visível à máquina da tentativa |
| Versão | só existe linha para versão com tentativa criada; criar tentativa já exige (046) autorização vigente da versão para o Worker da máquina de destino |
| Período | só com a tentativa em `RECEIVED` ou `PREPARING`. Na fila, oferecida, iniciando, executando ou terminada: nenhuma linha |
| Organização | como nas demais tabelas |

**Como a linha nasce.** Um gatilho de `remote_attempts` a insere na mesma transação que cria a tentativa. Quem insere
informa só a tentativa e a organização. A máquina, o `sha256` e o tamanho são lidos pelo próprio banco: da tentativa e do
artefato da versão e da impressão fixadas na solicitação. As pessoas (papel `nekkora_platform`) não escolhem esses valores,
não alteram e não apagam a linha.

**De onde vem a máquina.** O gateway grava `nekkora.agent_id` na transação **depois** de conferir a credencial (segredo,
não revogada, não vencida, Agent ativo e associado a um Worker), em `MachineAuth.authenticate`. O valor é o `agent_id` da
linha da credencial no banco. Nenhum cabeçalho, corpo ou caminho da requisição o define. Transação sem esse valor não vê
pacote nenhum.

**O que continua no gateway.** Token e época da atribuição, estado da tentativa, renovação do prazo e conferência dos
bytes pelo SHA-256. Nada foi retirado.

**O que não garante.** O papel do canal é um só para todas as máquinas. Quem controla o processo do gateway escolhe o
valor de `nekkora.agent_id`, como já escolhe o da organização. A restrição protege contra consulta errada ou fora do
fluxo, não contra um gateway comprometido. As outras tabelas lidas pela máquina continuam limitadas pela organização.

**Mudança de comportamento.** Versão publicada sem pacote registrado na Platform não ganha tentativa:

- lista de máquinas: `PACKAGE_NOT_REGISTERED` em `missing`, para todas as máquinas;
- solicitar ou repetir em outra máquina: `409 conflict`, `remote_package_not_registered`, antes de gravar;
- o banco recusa por conta própria a criação da tentativa.

Antes, a máquina recebia a tentativa e recusava no download.

**Implantação.** A 048 mudou de conteúdo. Nenhum ambiente real a tem. O laboratório isolado, que tinha a redação antiga,
é recriado. A migration recusa ser aplicada se encontrar o privilégio antigo.

## Ajustes para a execução (migration 049)

Dois acréscimos pedidos pelo Client para executar (PR próprio, empilhado sobre o do download):

- **A tentativa entregue leva `workspaceId`**: o aplicativo grava o ambiente no registro da execução dele.
- **Motivo de recusa `SESSION_UNAVAILABLE`** (migration `049_recusa_por_sessao_indisponivel.sql`): a máquina o informa quando
  não há sessão interativa utilizável (tela do Windows bloqueada, sessão de serviço, sem área de trabalho). O aplicativo
  não inicia uma automação nessas condições.

## Compatibilidade

Aditivo. Mover as classes do armazenamento não muda rotas nem comportamento da Platform. O Agent anterior não usa a rota.

## Testes (executados neste PR)

| Requisito | Teste |
|---|---|
| Só com o token; só depois do recebimento e antes do início; bytes e SHA-256 certos; sem cache; token ou época antigos; outra máquina; bytes trocados ou ausentes; privilégios da 048 | `RemoteDeliveryIT.aMaquinaBaixaSoOPacoteDaTentativaQueRecebeuEAntesDeIniciar` |
| 048 restrita: só a máquina da tentativa vê a linha; outra máquina, outra organização, sem máquina na transação: nada; fila, oferecida, iniciando, executando, terminada, recusada: nada; o canal da máquina não escreve nem lê `automation_artifacts` | `RemoteDeliveryIT.oBancoSoMostraOPacoteAMaquinaDaTentativaNaOrganizacaoDelaEDuranteAPreparacao` |
| 048 restrita: versão sem pacote registrado não ganha solicitação nem tentativa (API e banco); as pessoas não escolhem, não mudam e não apagam o pacote fixado | `RemoteExecutionIT.versaoSemPacoteRegistradoNaoGanhaSolicitacaoNemTentativaEOPacoteFixadoNaoEEscolhidoPorQuemInsere` |
| Rotas publicadas = rotas mapeadas | `GatewayContractTest` |
| O armazenamento movido continua igual | `PackageInspectorTest` e os testes de ciclo de vida da Platform (CI) |

## Fora deste PR / não provado

- Resultados (envio pela máquina, lista e download pela pessoa): parte b.
- Client: baixar, conferir, executar.
- Limite de taxa próprio para o download: usa só os limites gerais do canal.
- O pacote inteiro é lido para a memória do gateway (até 50 MiB, o limite da 028). Sem leitura em fluxo.
- Nenhum teste com máquina real nem com o armazenamento montado como somente leitura.
