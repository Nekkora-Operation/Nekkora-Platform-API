# T09 — Criação excepcional pela Nekkora e reconciliação das sessões do Agent anterior

Missão PLATFORM-LICENCIAMENTO-001. Migration `068` (**com elevação; só laboratório e testes** até autorização específica).
Rotas em `LicenseOperatorController`; testes em `LicenseOperatorIT`. Revisado na sexta rodada, com as decisões da titular.

## 1. Criação excepcional de organização

O operador indica explicitamente a pessoa responsável, que recebe a administração da nova organização. Ficam registrados
responsável, operador e justificativa. O operador não ganha acesso.

- Rota: `POST /api/v1/o/{org}/license-operations/organizations`, com `responsibleEmail`, `name`, `legalName` e `justification`.
  `{org}` é a organização interna da Nekkora.
- Quem pode: o operador de licenças (sessão da organização interna, segundo fator, cadastro de operadores), com confirmação
  reforçada. O banco reconfere o cadastro.
- A pessoa indicada é localizada pelo **e-mail verificado** de uma identidade ativa. Ela recebe a conta de administradora da
  nova organização e passa a ser a responsável (origem 4 em `organization_responsibility`).
- O operador **não** recebe conta, papel nem acesso à organização criada.
- Registro: `organization_exceptional_creations` (só acréscimo).

O que a exceção dispensa, e o que não dispensa:

| Regra da criação comum | Na criação pela Nekkora |
|---|---|
| Perfil: membro não cria, solicita | dispensada: um membro pode ser indicado |
| **Limite de três organizações vazias** | **vale por padrão**: 409 `empty_organization_limit_reached`, nada criado |
| Ultrapassar o limite de vazias | só por **exceção explícita**: `exceedEmptyLimit: true` com `limitJustification` própria (10 a 1000 caracteres). Fica gravado em `empty_limit_exceeded` e `limit_justification` |
| Teto de organizações criadas (017) | vale, e não é ultrapassável por aqui: 409 `organization_limit_reached` |
| **Cliente não cria nem solicita (D9)** | **mantida**: 409 `organization_responsible_is_client`, com ou sem a exceção do limite. A criação pela Nekkora não promove um Cliente |
| Nome repetido para a mesma pessoa | mantida: `organization_name_taken` |
| Modelo de nascimento (chave D7) | o mesmo de qualquer organização nova |

Pedir a exceção sem precisar dela não a grava: `empty_limit_exceeded` só fica verdadeiro quando o limite foi de fato
ultrapassado. A resposta traz `emptyLimitExceeded`.

## 2. Resultado desconhecido do Agent anterior: segura a transferência e reserva vaga

Resultado desconhecido não comprova que a execução terminou, nem que deixou de ocupar a máquina. A sessão em `OUTCOME_UNKNOWN`:

- conta como trabalho possível na origem (`license_work_in_progress`): a transferência responde
  `license_transfer_work_in_progress`;
- **reserva uma vaga de execução** da organização (`capacity_state`), até a reconciliação.

A leitura da capacidade mostra as duas coisas separadas:

| Campo de `concurrentExecutions` | O que é |
|---|---|
| `active` | execuções ativas: remota entregue até em execução, execução da Platform, Agent anterior informado ativo |
| `reservedUncertain` | reservas por situação incerta: ocupação incerta da execução remota e resultado desconhecido do Agent anterior |
| `breakdown.previousAgentUnconfirmed` | só as sessões do Agent anterior em resultado desconhecido |
| `used` | a soma das duas |

Confirmação confiável: para o Agent anterior, `OUTCOME_UNKNOWN` é estado final — a máquina não envia outro depois. A única
confirmação posterior possível hoje é a reconciliação do suporte.

## 3. Reconciliação pelo suporte

Não havia mecanismo para as sessões do Agent anterior: o que existe (058, 060) é das tentativas de execução remota e é feito
pelo administrador da organização. Foi criado um registro próprio, com o mesmo princípio.

- Lista: `GET …/organizations/{target}/previous-agent-sessions` — sessões sem desfecho confiável que ainda seguram
  transferência e vaga. Só identificador, automação, estado e instantes.
- Ato: `POST …/previous-agent-sessions/{sessionId}/reconciliation`, com `justification` e `verification`, ambas
  obrigatórias, com confirmação reforçada.
- Registro: `agent_run_reconciliations` (só acréscimo): sessão, estado que ela tinha, última notícia vista, operador,
  justificativa, verificação e instante.

**Não há espera obrigatória.** O suporte pode reconciliar assim que tiver verificado que o processo terminou ou que a
máquina está fora de operação. O que autoriza o ato é a verificação registrada; tempo sem contato, sozinho, não basta e
nunca libera a vaga.

O que a reconciliação **não** faz:

- não altera a sessão: o estado continua o informado pela máquina;
- não grava desfecho, código de saída nem fim;
- não declara a máquina livre nem encerra processo algum.

O que ela faz: a sessão reconciliada deixa de segurar a transferência de licença e a vaga de execução.

### Relato posterior da máquina

A declaração fica no histórico. O relato é acrescentado (a sessão e os relatos dela, tabelas da 025) e tratado pelo conteúdo:

| O que a máquina informa depois da declaração | Efeito |
|---|---|
| Um desfecho (concluída, falhou, cancelada, encerrada por tempo, bloqueada) | resolve a dúvida por si: a sessão deixa de estar sem desfecho |
| Execução **ainda ativa** (pronta, iniciando, em execução) | a ocupação volta: a sessão segura de novo transferência e vaga, e pede nova reconciliação. A lista mostra `previouslyReconciledAt` |
| Resultado desconhecido | não diz que há execução ativa: a declaração continua valendo |

A declaração anterior nunca é apagada nem alterada.

## 4. Sessões sem encerramento que ocupam vagas

Uma sessão iniciada pelo Agent anterior que nunca informa o fim (máquina desligada, reinstalada, descartada) fica em pronta,
iniciando ou em execução, e ocupa uma vaga de execução da organização no modelo por licença.

- **O tempo decorrido não libera a vaga.** Não há expiração automática.
- Caminho administrativo:
  1. o administrador da organização abre chamado no suporte, informando a automação e a máquina;
  2. o operador abre a organização em Suporte Nekkora › Operador de licenças e confere a lista de sessões sem desfecho;
  3. o operador verifica, fora do sistema, que o processo terminou ou que a máquina está fora de operação (por exemplo:
     confirmação escrita do administrador de que a máquina foi desligada, formatada ou substituída);
  4. o operador reconcilia a sessão, registrando a justificativa e a verificação realizada.
- Onde a organização vê a ocupação: Administração › Licenças e capacidade, com as execuções ativas separadas das reservas
  por situação incerta.

Limite declarado: a Platform não tem como saber, sozinha, se a máquina ainda executa. A reconciliação é um atestado do
suporte, gravado com quem atestou e o que verificou.

## 5. Recusas

| Motivo | Quando |
|---|---|
| `organization_responsible_not_found` | não há identidade ativa com esse e-mail verificado |
| `organization_responsible_is_client` | a pessoa indicada só tem conta do tipo Cliente |
| `empty_organization_limit_reached` | o responsável está no limite de vazias e a exceção explícita não foi pedida |
| `organization_limit_reached` | o responsável atingiu o teto de organizações criadas |
| `previous_agent_session_has_outcome` | a sessão já tem desfecho informado pela máquina |
| `previous_agent_session_already_reconciled` | já há declaração válida para a sessão |

## 6. Outras mudanças desta etapa

- `GET /api/v1/o/{org}/license-operations`: a sessão é de um operador? Para a tela mostrar a área do operador.
- `GET …/organizations/{target}`: modelo, licenças e automações da organização alvo.
- Execução remota: campo `waitingForSlot` — a tentativa está na fila e a organização está no limite de simultâneas.
- `licenseModel` na lista de organizações e em `/me`.

## 7. Testes

`LicenseOperatorIT`, contra PostgreSQL real com as migrations aplicadas pelo migrador:

- `resultadoDesconhecidoDoAgentAnteriorSeguraATransferenciaAteAReconciliacaoPeloSuporte`: transferência recusada; o
  resultado desconhecido reserva vaga, e a leitura separa ativas de reservas; recusas (sem verificação, outra organização,
  administrador do cliente); a sessão não muda; dez dias sem notícia não liberam a vaga; notícia há cinco minutos não impede
  o ato; relato tardio de resultado desconhecido não desfaz a declaração; relato de execução ativa devolve a ocupação e a
  declaração anterior permanece; o desfecho resolve; só acréscimo.
- `criacaoExcepcionalPeloOperadorComResponsavelIndicado`: quem não cria; responsável inexistente; Cliente (com e sem a
  exceção do limite); a pessoa indicada administra; o operador sem conta e sem acesso; **a quarta vazia é recusada**; a
  exceção exige justificativa própria e fica gravada só onde foi usada; nome repetido; operador retirado do cadastro.

## 8. Limites

- A pessoa indicada precisa já ter identidade na Platform, com e-mail verificado. A exceção não cadastra ninguém.
- O banco não reconfere a sessão nem o autenticador do operador; isso é da aplicação. O banco reconfere o cadastro.
- Ao aplicar a 068, sessões já em resultado desconhecido passam a reservar vaga em organização do modelo por licença.
- A prévia do operador (063) não foi alterada: "trabalho em andamento" nela não inclui o resultado desconhecido.
