# T09 — Criação excepcional pela Nekkora e reconciliação das sessões do Agent anterior

Missão PLATFORM-LICENCIAMENTO-001, quinta rodada. Migration `068` (**com elevação; só laboratório e testes** até autorização
específica). Rotas em `LicenseOperatorController`; testes em `LicenseOperatorIT`.

## 1. Criação excepcional de organização

Decisão da titular: o operador indica explicitamente a pessoa responsável, que recebe a administração da nova organização.
Ficam registrados responsável, operador e justificativa. O operador não ganha acesso.

- Rota: `POST /api/v1/o/{org}/license-operations/organizations`, com `responsibleEmail`, `name`, `legalName` e `justification`.
  `{org}` é a organização interna da Nekkora.
- Quem pode: o operador de licenças (sessão da organização interna, segundo fator, cadastro de operadores), com confirmação
  reforçada. O banco reconfere o cadastro.
- A pessoa indicada é localizada pelo **e-mail verificado** de uma identidade ativa. Ela recebe a conta de administradora da
  nova organização e passa a ser a responsável (origem 4 em `organization_responsibility`).
- O operador **não** recebe conta, papel nem acesso à organização criada.
- Registro: `organization_exceptional_creations` (só acréscimo): organização, responsável, operador, organização do operador,
  justificativa e instante.

O que a exceção dispensa, e o que não dispensa (**escolhas desta implementação, a confirmar**):

| Regra da criação comum | Na exceção |
|---|---|
| Perfil: membro não cria, solicita | dispensada: um membro pode ser indicado |
| Teto de organizações criadas e limite de três vazias | dispensados; a organização criada **conta** nos limites da pessoa dali em diante |
| Cliente não cria nem solicita (D9) | **mantida**: recusa `organization_responsible_is_client` |
| Nome repetido para a mesma pessoa | mantida: `organization_name_taken` |
| Modelo de nascimento (chave D7) | o mesmo de qualquer organização nova |

## 2. Resultado desconhecido do Agent anterior segura a transferência

Decisão da titular: resultado desconhecido não comprova que a execução terminou. A sessão em `OUTCOME_UNKNOWN` passa a contar
como trabalho possível na origem (`license_work_in_progress`), e a transferência responde
`license_transfer_work_in_progress` até a reconciliação.

Confirmação confiável: para o Agent anterior, `OUTCOME_UNKNOWN` é estado final — a máquina não envia outro depois. A única
confirmação posterior possível hoje é a reconciliação do suporte. Se um dia o Agent passar a informar um desfecho depois do
desconhecido, ele vale por si (a sessão deixa de estar em 1, 2, 3 ou 9).

O resultado desconhecido **não** ocupa vaga de execução: para a máquina a sessão terminou; o que não se sabe é o desfecho. Ele
segura só a transferência.

## 3. Reconciliação pelo suporte

Não havia mecanismo para as sessões do Agent anterior: o que existe (058, 060) é das tentativas de execução remota e é feito
pelo administrador da organização. Foi criado um registro próprio, com o mesmo princípio.

- Lista: `GET …/organizations/{target}/previous-agent-sessions` — sessões sem desfecho confiável ainda não reconciliadas. Só
  identificador, automação, estado e instantes; nada do conteúdo da execução.
- Ato: `POST …/previous-agent-sessions/{sessionId}/reconciliation`, com `justification` e `verification` (a verificação
  realizada), ambas obrigatórias, com confirmação reforçada.
- Registro: `agent_run_reconciliations` (só acréscimo): sessão, estado que ela tinha, última notícia vista, operador,
  justificativa, verificação e instante.

O que a reconciliação **não** faz:

- não altera a sessão: o estado continua o informado pela máquina (desconhecido continua desconhecido; em execução continua
  em execução);
- não grava desfecho, código de saída nem fim;
- não declara a máquina livre nem encerra processo algum.

O que ela faz: a sessão reconciliada deixa de segurar a transferência de licença e, se ocupava, a vaga de execução.

Validade: a reconciliação vale enquanto a máquina não informar nada **depois** dela. Se a máquina voltar a informar sobre a
sessão (`last_report_at` posterior), a sessão volta a contar, e pede nova reconciliação. Isso cobre o engano do suporte: uma
sessão viva reaparece sozinha.

## 4. Sessões sem encerramento que ocupam vagas

Uma sessão iniciada pelo Agent anterior que nunca informa o fim (máquina desligada, reinstalada, descartada) fica em pronta,
iniciando ou em execução, e ocupa uma vaga de execução da organização no modelo por licença.

- **O tempo decorrido não libera a vaga.** Não há expiração automática.
- Caminho administrativo:
  1. o administrador da organização abre chamado no suporte, informando a automação e a máquina;
  2. o operador confere a lista de sessões sem desfecho da organização;
  3. o operador verifica, fora do sistema, que não há execução (por exemplo: confirmação escrita do administrador de que a
     máquina foi desligada, formatada ou substituída);
  4. o operador reconcilia a sessão, registrando a justificativa e a verificação realizada.
- Trava: a sessão sem encerramento só pode ser reconciliada depois de **uma hora sem notícia** da máquina
  (`previous_agent_session_recently_reported`). A hora não libera nada; só permite o ato.
- Onde a organização vê a ocupação: Administração › Licenças e capacidade, na composição das vagas ("Agent anterior").

Limite declarado: a Platform não tem como saber, sozinha, se a máquina ainda executa. A reconciliação é um atestado do
suporte, gravado com quem atestou e o que verificou.

## 5. Recusas novas

| Motivo | Quando |
|---|---|
| `organization_responsible_not_found` | não há identidade ativa com esse e-mail verificado |
| `organization_responsible_is_client` | a pessoa indicada só tem conta do tipo Cliente |
| `previous_agent_session_has_outcome` | a sessão já tem desfecho informado pela máquina |
| `previous_agent_session_already_reconciled` | já reconciliada depois da última notícia |
| `previous_agent_session_recently_reported` | sem encerramento e com notícia há menos de uma hora |

## 6. Outras mudanças desta etapa

- `GET /api/v1/o/{org}/license-operations`: a sessão é de um operador? Para a tela mostrar a área do operador.
- `GET …/organizations/{target}`: modelo, licenças e automações da organização alvo, para o operador escolher os alvos dos atos.
- Execução remota: campo `waitingForSlot` — a tentativa está na fila e a organização está no limite de simultâneas neste
  instante. É capacidade, não licença.

## 7. Testes

`LicenseOperatorIT`, contra PostgreSQL real com as migrations aplicadas pelo migrador:

- `resultadoDesconhecidoDoAgentAnteriorSeguraATransferenciaAteAReconciliacaoPeloSuporte`: transferência recusada com
  resultado desconhecido; lista do operador; recusas (sem verificação, outra organização, administrador do cliente); a sessão
  não muda; dez dias sem notícia não liberam a vaga; notícia recente impede o ato; o ato libera; a máquina voltando a informar
  devolve a sessão à contagem; só acréscimo.
- `criacaoExcepcionalPeloOperadorComResponsavelIndicado`: quem não cria; responsável inexistente; Cliente; a pessoa indicada
  administra e é a responsável; o operador sem conta e sem acesso; limites dispensados; nome repetido; operador retirado do
  cadastro.

## 8. Limites

- A pessoa indicada precisa já ter identidade na Platform, com e-mail verificado. A exceção não cadastra ninguém.
- O banco não reconfere a sessão nem o autenticador do operador; isso é da aplicação. O banco reconfere o cadastro.
- A desativação de organização (067) usa a mesma regra de trabalho em andamento: passa a ser recusada também com sessão em
  resultado desconhecido não reconciliada.
- A prévia do operador (063) não foi alterada: "trabalho em andamento" nela não inclui o resultado desconhecido.
