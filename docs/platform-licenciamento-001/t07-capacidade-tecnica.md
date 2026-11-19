# PLATFORM-LICENCIAMENTO-001 — T07: capacidade técnica no lugar da ativação comercial (D2)

Depende das PRs 061 a 065. Migration **066, sem elevação**.

**Só vale para organização no modelo por licença.** No modelo anterior, a ativação pelo operador e os limites por
organização continuam exatamente como estão.

## O que muda para quem usa licenciamento

| Antes | Com licenciamento |
|---|---|
| A organização nasce "aguardando ativação" e não registra máquina até um operador da Nekkora ativá-la | Registra máquina sem esperar ninguém |
| Workers, execuções simultâneas e pendentes são números negociados por organização | São limites **técnicos**, iguais para todas |
| O que impede executar é a ativação | O que impede executar é a **licença** (T05) |

A ativação não é apagada nem alterada: fica guardada, sem efeito, para a organização que passa ao modelo por licença, e
volta a valer se a exigência for desligada.

## Os limites

| Limite | Valor inicial | O que acontece no limite |
|---|---|---|
| Workers cadastrados | 50 | o cadastro seguinte é recusado (`worker_limit_reached`) |
| Execuções simultâneas | 5 | **nada é recusado**: o trabalho seguinte espera na fila até haver vaga |
| Execuções pendentes | 50 | a solicitação seguinte é recusada (`capacity_pending_limit_reached`) |

- São **proteção contra abuso e sobrecarga**. Não são contratados e não têm relação com a quantidade de licenças.
- São **configuráveis**: ficam na tabela `technical_capacity_limits`. Mudar um valor é procedimento administrativo (o
  migrador grava o valor e quem autorizou). O serviço só lê; não há rota para alterar.
- O valor é um só para todas as organizações. Limite por organização não existe nesta versão.

## A vaga de execução

O limite de simultâneas é **um só para a organização** e é dividido por todos os fluxos de execução.

**A vaga é reservada no momento em que o trabalho é autorizado a iniciar**, sob uma trava da organização, e fica ocupada
até o trabalho terminar. Com limite de cinco, a sexta execução permanece pendente.

| Fluxo | Quando a vaga é reservada | Sem vaga |
|---|---|---|
| Execução remota (Agent Client) | quando o servidor **entrega** a tentativa à máquina | a tentativa não é entregue; continua na fila até haver vaga ou vencer o prazo da oferta |
| Pedido ao Agent anterior | quando o servidor **entrega** o pedido à máquina | o pedido não é entregue; continua pendente até haver vaga ou vencer |
| Execução da Platform | na reserva (pendente → iniciando) | continua pendente |

O que ocupa vaga:

- tentativa remota entregue, recebida, preparando, iniciando ou em execução. A oferta que venceu sem resposta ocupa até o
  servidor encerrá-la (a máquina ainda poderia confirmar o recebimento);
- tentativa remota com **ocupação incerta**: terminou sem confirmação, ou terminou com processo ainda vivo ou sem a máquina
  saber dizer. Ocupa até a máquina confirmar que não há processo ou um administrador declarar a reconciliação. Tempo e novo
  contato não liberam;
- execução da Platform iniciando ou em execução;
- pedido ao Agent anterior entregue (no prazo) ou aceito;
- sessão do Agent anterior pronta, iniciando ou em execução.

**O que o servidor não consegue segurar.** A sessão que o Agent anterior inicia na própria máquina, sem pedido, já
começou quando chega ao servidor. O registro é aceito e passa a ocupar vaga; por isso a ocupação pode passar do limite.
Enquanto passar, nada novo é entregue em nenhum fluxo.

**Riscos conhecidos.**

- Uma sessão do Agent anterior que fica "em execução" sem o Agent informar o fim ocupa vaga indefinidamente. O servidor não
  tem como encerrá-la. A saída hoje é o Agent voltar e informar, ou o limite ser ajustado por procedimento.
- Uma organização que passa ao modelo por licença com tentativas remotas antigas "não confirmadas" começa com essas vagas
  ocupadas, até a reconciliação de cada uma.
- A fila não tem ordem garantida: havendo vaga, recebe a máquina que perguntar primeiro.
- A tentativa remota que espera vaga continua tendo o prazo da oferta; vencido, expira sem ter iniciado.

## Capacidade não é licença

- A recusa por capacidade tem motivo próprio: `worker_limit_reached` e `capacity_pending_limit_reached`. Nunca
  `license_required` nem `license_expired`.
- Atingir um limite **não consome nem libera licença**: a conta de licenças não se mexe e a automação continua com direito.
- A leitura é separada: `GET /api/v1/o/{org}/capacity`, para quem administra a organização. A tela mostra capacidade em
  seção própria, fora do resumo de licenças.

```json
{ "model": "licensed",
  "workers": { "used": 3, "limit": 50 },
  "concurrentExecutions": { "used": 5, "limit": 5,
    "breakdown": { "remote": 3, "unconfirmed": 1, "platform": 0, "previousAgent": 1 } },
  "pendingExecutions": { "used": 2, "limit": 50 },
  "asOf": "2026-11-19T12:00:00Z" }
```

`breakdown` diz de que a ocupação é feita; a soma é `used`. No modelo anterior, `limit` vem nulo: a capacidade daquela
organização é a da ativação.

## Onde o servidor confere

- **Cadastro de Worker** (direto ou pelo código de pareamento): gatilho do banco, sob a trava da linha da organização.
- **Fila**: na solicitação remota, em cada nova tentativa e no pedido ao Agent anterior — pela aplicação (para responder com o
  motivo) e por gatilho do banco na gravação.
- **Vaga**: na entrega pelo canal das máquinas — a aplicação pergunta ao banco (`capacity_reserve`) e só então entrega; um
  gatilho do banco recusa a mesma transição feita sem vaga, nos três fluxos.
- Tudo sob a mesma trava da organização (`capacity_lock`): dois inícios simultâneos não passam juntos do limite.

Um ajuste junto: o cadastro de Worker pelo código de pareamento passa a responder `worker_limit_reached` (409) quando o
limite impede, como o cadastro direto já fazia. Antes respondia uma recusa genérica.

## Limites desta etapa

- A execução da Platform (tabela `executions`) não tem rota de criação hoje. Ela divide a vaga, mas continua passando
  **também** pelos limites antigos da organização.
- A execução local privada do Agent Client não passa pela Platform e não conta.
- A tela das execuções não diz "aguardando vaga": a tentativa aparece como na fila. A ocupação está na leitura de capacidade.

## Testes

`CapacityReservationIT` (canal real das máquinas, banco real):

- **inícios concorrentes**: seis máquinas com tentativa na fila perguntam ao mesmo tempo; cinco recebem, a sexta não.
  A sexta não é recusada nem encerrada. Perguntar de novo reentrega a mesma tentativa sem ocupar outra vaga;
- as cinco executam e a sexta continua pendente; uma termina e a sexta é entregue;
- uma vaga e duas pendentes perguntando ao mesmo tempo: só uma recebe;
- o banco recusa a entrega direta sem vaga; nenhuma tentativa é recusada; a licença continua válida;
- **fluxos compartilhados**: sessão local do Agent anterior, pedido entregue, duas tentativas remotas e uma com ocupação
  incerta somam cinco; nada novo é entregue em nenhum fluxo; o pedido não entregue continua pendente;
- a sessão iniciada na máquina é registrada mesmo sem vaga (ocupação 6 de 5);
- a reconciliação e o fim de uma sessão devolvem vagas; a vaga vai para quem perguntar primeiro;
- organização do modelo anterior: sem vaga a reservar.

`RemoteExecutionIT.capacidadeTecnicaSubstituiAAtivacaoNoModeloPorLicencaESeparadaDasLicencas` (API das pessoas):

- modelo anterior: organização aguardando ativação não registra Worker; capacidade com limites nulos;
- modelo por licença: registra sem ativação; limites lidos 50, 5 e 50; limite configurado para 2 recusa o terceiro Worker;
- outra organização não lê a capacidade desta;
- fila cheia recusa com `capacity_pending_limit_reached`, nada gravado;
- simultâneas no limite: a solicitação **nasce pendente**; o banco não deixa entregá-la; a execução da Platform também espera;
- a leitura traz a composição da ocupação;
- com a capacidade esgotada, a conta de licenças é a mesma e a automação continua com direito;
- os valores decididos estão gravados; o papel do serviço não os altera.

Não testado: a entrega segurada vista pelo aplicativo real (Agent Client ou Agent anterior). Para os dois, "sem vaga" é a
mesma resposta de "nada a fazer", que eles já tratam.

## Reversão

Comandos no cabeçalho da 066, enquanto nenhuma organização estiver no modelo por licença. Depois disso, desligar a
exigência por organização devolve a ela a capacidade da ativação.
