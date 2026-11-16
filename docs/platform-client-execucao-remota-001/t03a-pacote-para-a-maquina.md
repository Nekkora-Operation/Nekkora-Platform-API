# PLATFORM-CLIENT-EXECUCAO-REMOTA-001 — T03, parte a: a máquina baixa o pacote da tentativa (API)

Depende da T02 (046) e da T01c parte 3 (047). A T03 foi dividida: esta parte entrega o **download do pacote**; os
**resultados** vêm na parte b.

## O que muda

| Onde | Mudança |
|---|---|
| Banco | migration `048_pacote_para_a_maquina.sql`: o papel do canal da máquina passa a ler `sha256` e `size_bytes` de `automation_artifacts` |
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

1. **A 048 abre uma fronteira que a 036 fechou de propósito.** A 036 deixou a máquina ler só metadados do catálogo, sem a
   chave do armazenamento (o SHA-256 dos bytes). O download precisa dela do lado do servidor. A leitura continua limitada à
   organização da credencial e a colunas nomeadas; sem leitura da tabela inteira, sem escrita. O SHA-256 não é segredo: é o
   que a máquina confere depois de baixar.
2. **Impacto de operação.** O gateway precisa enxergar a pasta dos pacotes que hoje só a Platform monta. No E2E a pasta
   passa a ser montada no gateway como somente leitura (`api/e2e/compose.artefatos.yml`). Em homologação e produção isso
   é uma mudança de implantação que **não foi feita nem planejada em detalhe aqui**. Sem ela, o download responde 503 e
   nenhuma execução remota inicia.

## Compatibilidade

Aditivo. Mover as classes do armazenamento não muda rotas nem comportamento da Platform. O Agent anterior não usa a rota.

## Testes (executados neste PR)

| Requisito | Teste |
|---|---|
| Só com o token; só depois do recebimento e antes do início; bytes e SHA-256 certos; sem cache; token ou época antigos; outra máquina; bytes trocados ou ausentes; privilégios da 048 | `RemoteDeliveryIT.aMaquinaBaixaSoOPacoteDaTentativaQueRecebeuEAntesDeIniciar` |
| Rotas publicadas = rotas mapeadas | `GatewayContractTest` |
| O armazenamento movido continua igual | `PackageInspectorTest` e os testes de ciclo de vida da Platform (CI) |

## Fora deste PR / não provado

- Resultados (envio pela máquina, lista e download pela pessoa): parte b.
- Client: baixar, conferir, executar.
- Limite de taxa próprio para o download: usa só os limites gerais do canal.
- O pacote inteiro é lido para a memória do gateway (até 50 MiB, o limite da 028). Sem leitura em fluxo.
- Nenhum teste com máquina real nem com o armazenamento montado como somente leitura.
