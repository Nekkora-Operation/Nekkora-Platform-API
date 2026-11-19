# PLATFORM-LICENCIAMENTO-001 — T06: transferência de licença pelo suporte (E4, D6)

Depende das PRs 061 a 064. **A migration 065 exige elevação e está restrita ao laboratório.**

## O que é

A licença de uma automação passa a atender **outra automação da mesma organização**, sem mexer no ciclo. Quem faz é o
operador da Nekkora, a pedido de um administrador da organização. O pedido chega pelo **atendimento externo**: não há
central de chamados na Platform, nem rota de pedido ou de transferência para o administrador.

`POST /api/v1/o/{org}/license-operations/organizations/{alvo}/licenses/{id}/transfer`

| Campo | O que é |
|---|---|
| `toAutomationId` | a automação de destino |
| `requesterEmail` | o administrador da organização que pediu |
| `ticketReference` | a referência do chamado no atendimento |
| `identityVerification` | como o operador confirmou que o pedido veio daquela pessoa |
| `justification` | a justificativa |

## O que prova o quê

Informar o identificador de um administrador **não prova** que ele pediu. Quem atesta o pedido é o operador, e o atestado
fica gravado: chamado e verificação da identidade são obrigatórios e vão para a trilha.

| Conferência | Aplicação | Banco |
|---|---|---|
| Operador no cadastro, ativo, de outra organização | sim | **sim** |
| Sessão, segundo fator e confirmação reforçada do operador | sim | não |
| Solicitante administra a organização neste instante | **sim** | exige só que seja um vínculo da organização |
| Chamado, verificação e justificativa presentes | sim | **sim** |
| Licença existe, não encerrada, vinculada, vigente | — | **sim** |
| Destino da mesma organização, não arquivado, sem licença | — | **sim** |
| Origem sem trabalho em andamento | — | **sim** |

## Regras

- **Auditoria.** O ato 5 guarda origem, destino, solicitante, responsável (o operador), justificativa e data; em `detail`,
  o chamado e a verificação da identidade. A trilha é só de acréscimo.
- **Fim do ciclo preservado.** Os períodos da licença não são tocados.
- **Sem uso simultâneo.** O vínculo de origem é encerrado e o de destino é aberto na mesma transação. Não há instante com
  as duas automações atendidas, e o índice único do banco impede dois vínculos abertos da mesma licença.
- **Trabalho em andamento.** A transferência é recusada (`license_transfer_work_in_progress`) enquanto a automação de
  origem tiver execução da Platform não terminada, execução ou sessão de monitoramento do Agent anterior, pedido pendente
  ao Agent anterior ou tentativa remota não terminada. Precisa terminar ou ter o cancelamento confirmado. A transferência
  não interrompe nem cancela nada.
- **Concorrência.** Roda sob a trava exclusiva do modelo da organização. Um início de trabalho em curso segura a
  transferência; depois dela, a origem já não tem direito e o início seguinte é recusado.
- **Licença vencida não é transferida.** Renova-se antes (`license_not_valid`).
- A conta do resumo não muda: a licença continua contratada e utilizada.

## Por que elevação

A função pertence ao dono das licenças (063). Para recusar com trabalho em andamento, esse dono passa a **ler** poucas
colunas de seis tabelas de outras áreas: a situação da automação e o estado dos cinco registros de trabalho. Não lê
nome, conteúdo, parâmetros, registros, resultados, pessoas nem sessões, e não escreve em nenhuma delas (conferido nos
invariantes da migration).

- No laboratório e nos testes, a 065 entra no padrão de `db/bootstrap/aplicar_como_migrador.sh`.
- Em homologação e produção ela **não** está em `ops/homologacao/implantacao/elevacao.txt`. Nada foi pedido nem executado.

Um ajuste na 062 veio junto: o gatilho do vínculo só prepara a conferência de permissão quando há "vinculado por". Sem
isso, o vínculo aberto pelo ato do operador era negado por falta de direito sobre uma função que ele nem usa.

## Testes (`LicenseOperatorIT.transferenciaPeloOperadorComSolicitanteChamadoEVerificacaoSemUsoSimultaneo`)

Pela API real, com a 065 aplicada no banco de teste:

- licença sem vínculo não transfere; o administrador do cliente não tem rota de transferência;
- sem chamado, sem verificação ou sem solicitante: 400;
- solicitante leitor, de outra organização ou inexistente: recusado;
- destino igual à origem, de outra organização (404), arquivado ou já licenciado: recusado;
- execução em andamento na origem: recusada, nada muda; cancelada, passa;
- concorrência: com um início de trabalho em curso na origem, a transferência espera e só conclui depois;
- resultado: vínculo de origem encerrado como transferida, destino aberto, nunca os dois; períodos idênticos;
- trilha com origem, destino, solicitante, responsável, justificativa, chamado, verificação e data;
- a origem perde o direito e o destino ganha (`LICENSE_REQUIRED` e `OK` no modelo por licença);
- o papel do serviço não transfere em nome de quem não é operador.

Não testado: transferência com tentativa remota ou sessão do Agent anterior em andamento (o teste usa a execução da
Platform; os outros três casos estão na mesma consulta do banco, sem teste próprio).

## Reversão

Comandos no cabeçalho da 065, em janela de elevação. Uma transferência já feita se desfaz com outra transferência.
