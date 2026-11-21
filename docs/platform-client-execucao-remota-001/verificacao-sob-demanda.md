# Verificação de dependências sob demanda (migration 054)

PLATFORM-CLIENT-EXECUCAO-REMOTA-001, plano T00 seção 5.9. Fecha a pendência "verificação sob demanda": uma pessoa pergunta, **antes
de solicitar a execução**, se as dependências declaradas da automação estão no Python escolhido de uma máquina.

## O que é e o que não é

- A Platform pede; a máquina confere **só** as dependências daquela automação, **só** no ambiente Python escolhido para ela
  naquela máquina, e responde **uma vez**.
- **Nada é instalado**, nenhum ambiente é criado ou escolhido, a escolha do Python não muda.
- **É informação.** A conferência que vale para executar continua sendo a da máquina, imediatamente antes de iniciar (recusa com
  `DEPENDENCIES_MISSING`, `ENVIRONMENT_GONE`…). Um resultado bom aqui não libera nada; um ruim não bloqueia a solicitação.
- A resposta nunca traz a lista do que está instalado: só o que **falta do que foi perguntado**. Nenhum caminho.

## Fluxo

1. Pessoa: `POST /o/{org}/automations/{automation}/remote-machines/{agent}/dependency-check` (exige `automation.execute` no
   ambiente da automação). O pedido **congela** o que será conferido: o ambiente escolhido agora e a lista efetiva vigente (com a
   revisão).
2. Máquina: recebe o pedido junto com a consulta de trabalho (`GET /remote/next`, campo `dependencyCheck`) ou, quando tem uma
   tentativa em andamento, por `GET /remote/dependency-checks/pending`. Conferir não é executar: o pedido chega com a máquina
   pausada ou ocupada.
3. Máquina: `POST /remote/dependency-checks/{check}/answer` com o resultado.
4. Pessoa: lê o resultado em `dependencyCheck`, na lista de máquinas (`candidates`).

## Resultados

| `state` | `outcome` | Significa |
|---|---|---|
| `PENDING` | — | aguardando a máquina, dentro do prazo de 2 minutos |
| `ANSWERED` | `SATISFIED` | todas presentes, na versão exata |
| `ANSWERED` | `MISSING` | faltam (ou estão em outra versão) as de `missing` |
| `ANSWERED` | `ENVIRONMENT_GONE` | o ambiente escolhido não existe mais na máquina |
| `ANSWERED` | `NOT_VERIFIED` | a máquina não conseguiu conferir |
| `UNANSWERED` | — | o prazo venceu sem resposta: **não foi possível verificar** (máquina sem contato, aplicativo fechado). **Nunca "incompatível"** |

`current: false` avisa que o que foi conferido não é mais a configuração de agora (a escolha do Python ou a revisão das
dependências mudou depois).

## Limites

| Limite | Valor | Ao atingir |
|---|---|---|
| Pedidos por máquina e automação | 1 por intervalo — padrão 60 s, `NEKKORA_EXECUCAO_REMOTA_VERIFICACAO_INTERVALO_SEGUNDOS` (10 a 3600) | `429` + `rate_limited` / `remote_dependency_check_too_soon`; nada é criado. A tela diz "não foi possível verificar agora" |
| Prazo da resposta | padrão 120 s, `NEKKORA_EXECUCAO_REMOTA_VERIFICACAO_RESPOSTA_SEGUNDOS` (30 a 600) | o pedido fica `UNANSWERED`; resposta tardia: `409` + `remote_dependency_check_closed` |
| Itens em `missing` | até 400, 200 caracteres cada, só do que foi perguntado | `400`; nada é gravado |

São pontos de partida de laboratório.

## Recusas do pedido

`remote_python_not_selected`, `remote_python_not_in_inventory`, `remote_requirements_not_declared`,
`remote_requirements_conflict`, `remote_execution_unavailable` (recurso desligado); máquina fora do ambiente ou inexistente: `404`.

## Banco (054)

`remote_dependency_checks`: o pedido (imutável) e a resposta (gravada uma vez, dentro do prazo). Gatilhos conferem quem pede
(`automation.execute`), que o ambiente é o escolhido, que a revisão é a vigente e o piso de 10 segundos entre pedidos (o intervalo que vale é configuração; ver o fim deste documento). RLS por organização.
Pessoas: `SELECT`, `INSERT`. Máquina: `SELECT` e `UPDATE` só das colunas da resposta. Ninguém apaga. Sem elevação.

**Não há retenção para esta tabela**: as linhas acumulam (no máximo uma por minuto por máquina e automação).

## Testes

| Requisito | Teste |
|---|---|
| O pedido congela escolha e lista; limite por intervalo (padrão: um por minuto), por máquina e automação; resposta aparece na lista; mudança de escolha ou de revisão marca `current: false`; sem resposta fica `UNANSWERED`; recusas; permissões; guardas do banco | `RemoteExecutionIT.verificacaoSobDemandaCongelaOQueSeraConferidoTemLimiteESemRespostaNuncaViraIncompativel` |
| A máquina recebe só o que é dela, responde uma vez, só com o que foi perguntado, dentro do prazo; chega pausada e ocupada sem mexer na tentativa; guardas do banco | `RemoteDeliveryIT.aMaquinaRecebeOPedidoDeVerificacaoDeDependenciasERespondeUmaVezDentroDoPrazoESoOQueEDela` |

## Decisão de 16/11: parâmetros iniciais configuráveis (migration 056)

A titular aceitou a permissão de executar, um pedido por minuto e dois minutos para responder como **parâmetros iniciais
configuráveis**.

- O intervalo e o prazo da resposta são configuração da Platform (tabela acima). A 054 fixava "60 segundos" dentro do gatilho; a
  056 deixa no banco só um piso de 10 segundos.
- **Escopo do limite: organização + automação + máquina.** Não existe limite por organização nem global: o pedido de um cliente
  nunca segura o de outro, e o pedido para uma máquina não segura o de outra. A trava que serializa os pedidos também é por
  máquina e automação.
- **Tempo esgotado significa "Não foi possível verificar"** — nunca "incompatível". É o estado `UNANSWERED`.
