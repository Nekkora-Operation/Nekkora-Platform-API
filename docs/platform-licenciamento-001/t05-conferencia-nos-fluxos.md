# PLATFORM-LICENCIAMENTO-001 — T05: a licença conferida pelo servidor nos fluxos (E3)

Depende das PRs do modelo (061), do vínculo (062) e dos atos do operador (063). Migration **064, sem elevação**.
Decisões aplicadas: D3, D8, S1 e S2 (texto em `t01-decisoes-e-esclarecimentos.md`).

**Só vale para organização no modelo por licença.** Nenhuma organização está nesse modelo ao aplicar a 064: ligar é ato
do operador, por organização, com prévia. No modelo anterior nada muda.

## O que o servidor confere

| Trabalho novo | Onde | Sem licença vigente |
|---|---|---|
| Solicitação de execução remota | `POST …/remote-executions` | 409 `license_required` ou `license_expired`; nada gravado |
| Nova tentativa em outra máquina | `POST …/remote-executions/{id}/attempts` | idem |
| Oferta e início da tentativa pela máquina | revalidação do servidor (`remote_attempt_blocker`) | a tentativa que ainda não começou é recusada (`LICENSE_REQUIRED`, `LICENSE_EXPIRED`) |
| Pedido de execução ao Agent anterior | `POST …/run-requests` | 409, nada gravado |
| Autorização de execução local ou de monitoramento | `POST …/local-run-authorizations` | 409, nada gravado |
| Registro de execução ou de sessão de monitoramento pelo Agent anterior | canal da máquina, `POST /agent/api/v1/runs` | 403 com o motivo; nada gravado |

- `license_required`: a automação não tem licença vinculada. `license_expired`: tem, mas fora de período.
- A conferência é feita **duas vezes**: pela aplicação, para responder com o motivo, e por gatilho do banco na gravação. Um
  caminho que esqueça de conferir é recusado pelo banco, não admitido.
- Esconder ou desabilitar botão não é a regra: as telas só leem o mesmo estado (`missing` das máquinas candidatas traz
  `LICENSE_REQUIRED` ou `LICENSE_EXPIRED` antes de qualquer outro item).

## O que continua aceito depois do vencimento

- A tentativa remota que **já começou** termina e entrega registros e resultados; a reconciliação continua.
- Eventos, relatório do supervisor e lacunas de execução **já registrada** pelo Agent anterior.
- Leitura de todo o histórico, pelas regras de acesso e retenção de hoje.
- A execução local privada do Agent Client não passa pela Platform e não é tocada.

Vencimento não apaga nada, não suspende a organização e não revoga autorização.

## D3 — validade da autorização

- **Agent anterior:** a validade continua. É ela que limita o uso sem conexão.
- No modelo por licença, uma autorização nova nasce com `validUntil` = o **menor** entre o pedido e o **fim da cobertura
  sem lacuna**: o fim do período que cobre agora, estendido só pelos períodos seguintes que começam exatamente onde o
  anterior termina. Uma renovação que começa depois de um intervalo **não** estende a autorização. Não basta olhar o fim do
  último período cadastrado, e o servidor não olha.
- O teto de 366 dias da regra antiga continua valendo por cima.
- Autorizações existentes não são alteradas.

## S1 — execução remota sem renovação periódica

No modelo por licença, a execução remota do Agent Client deixa de ser recusada porque o prazo da autorização passou. A
autorização daquela versão para o Worker da máquina continua **exigida**, e a **revogação continua valendo**. O prazo
comercial passa a ser o da licença, conferido na solicitação, em cada tentativa nova, na oferta e antes do início.

No modelo anterior nada muda: o prazo da autorização continua recusando.

## S2 — aceitar a versão no mesmo passo da solicitação

`authorizeVersion: true` na solicitação (ou na nova tentativa) é o aceite explícito daquela versão naquela máquina.

- Só cria a autorização se não houver uma que sirva. É o **mesmo ato** da rota de autorização: mesmas recusas, mesma
  linha em `local_run_authorizations` com quem aceitou, mesmo registro na trilha (ato 10).
- Exige as duas permissões. Hoje as duas são a mesma (`automation.execute` no ambiente) com a mesma confirmação reforçada.
- Sem o campo, ou com `false`, nada é autorizado: a falta de autorização continua recusando.
- A autorização é da **versão** solicitada. Não existe autorização para versões futuras (S3 não foi aprovada).

## D8 — registro pelo Agent anterior

No modelo por licença, um registro **novo** é aceito quando a autorização usada valia no início informado e há uma destas
bases, gravada pelo banco em `agent_runs.license_basis`:

| Base | Significado |
|---|---|
| 1 | licença vigente no início e ainda vigente no registro |
| 2 | licença vigente no início; os dados chegaram depois do vencimento |
| 3 | **transição**: autorização emitida antes de a organização passar ao modelo por licença, dentro da validade já emitida |
| vazio | organização no modelo anterior |

Fora disso o registro é recusado: `run_not_covered` (a autorização não valia naquele início), `license_required` ou
`license_expired`.

O que vem de onde:

- Organização, automação, versão, Worker e autorização: **das linhas do servidor**. A autorização é lida pela chave e tem
  de ser do Worker da máquina; a automação e a versão são as dela.
- Da máquina vem só o **instante do início**.

**Transição.** Autorizações anteriores à migração não são revogadas nem renovadas. Elas seguem valendo até a validade já
emitida, e cada registro feito com elas fica marcado com a base 3. A prévia do operador (063) mostra quantas existem e até
quando a mais longa vale. Não há renovação automática: vencida, a autorização nova só nasce com licença.

**Limite da comprovação.** Quando a execução aconteceu sem conexão, o instante do início é o que a máquina informa. O
servidor o limita ao intervalo entre a emissão da autorização e agora e exige que a autorização valesse nele, mas não tem
como provar o relógio da máquina. As bases 2 e 3 dependem desse instante. Não prometemos bloqueio imediato de máquina
desconectada.

## Concorrência

Todo início de trabalho pega a trava compartilhada do modelo da organização; ligar a exigência pega a mesma trava em modo
exclusivo, depois da linha da organização (mesma ordem dos dois lados). Um início nunca corre contra a prévia que o
operador está confirmando: ou já confirmou e aparece na prévia refeita, ou espera o ato terminar.

## Testes

`RemoteExecutionIT.noModeloPorLicencaOServidorConfereALicencaEmCadaInicioDeTrabalho` (API real, papel real do serviço):

- modelo anterior sem mudança, incluindo a recusa por autorização vencida;
- sem licença: máquinas candidatas, solicitação, autorização e S2 recusados; nada gravado; o gatilho recusa a gravação direta;
- com licença vinculada pela rota real: S1 (autorização vencida não recusa);
- D3: validade limitada ao fim da licença; segue por renovação contígua; **para antes da lacuna**; o banco recusa validade além;
- S2: sem aceite recusa; com aceite autoriza e solicita; autorização da versão, de quem aceitou, na trilha; quem só lê não usa;
- revogação continua recusando;
- vencimento: trabalho novo e nova tentativa recusados; a tentativa que não começou é barrada antes do início; a que
  estava em execução conclui; histórico legível;
- outra organização, no modelo anterior, não é afetada.

`AgentGatewayIT.noModeloPorLicencaORegistroExigeCoberturaNoInicioEATransicaoFicaRegistrada` (canal real da máquina):

- modelo anterior: registro sem base;
- transição: base 3; autorização revogada antes do início: `run_not_covered`;
- sem licença, nenhuma autorização nova nasce;
- com licença: base 1; depois do vencimento, eventos e relatório da execução registrada continuam entrando;
- execução iniciada na validade e entregue depois: base 2; iniciada depois do vencimento: `license_expired`, nada gravado.

Não testado nesta etapa:

- o pedido ao Agent anterior (`run-requests`) pela rota: a recusa usa o mesmo gatilho e a mesma chamada da solicitação
  remota, mas não há teste de rota próprio;
- como o Agent anterior **apresenta** a recusa 403 no registro: o Agent não foi alterado nem executado aqui;
- a entrega pelo canal real do Agent Client com licença vencida (o teste confere a revalidação do servidor por consulta).

## Reversão

Comandos no cabeçalho da 064, enquanto nenhuma organização estiver no modelo por licença. Depois disso, a reversão
operacional é desligar a exigência por organização (ato do operador).
