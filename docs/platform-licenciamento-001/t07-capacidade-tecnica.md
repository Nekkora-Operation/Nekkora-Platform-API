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

| Limite | Valor inicial | O que conta |
|---|---|---|
| Workers cadastrados | 50 | Workers não arquivados da organização |
| Execuções simultâneas | 5 | tentativas remotas que a máquina recebeu e ainda não terminaram |
| Execuções pendentes | 50 | tentativas remotas na fila ou oferecidas, dentro do prazo da oferta |

- São **proteção contra abuso e sobrecarga**. Não são contratados e não têm relação com a quantidade de licenças.
- São **configuráveis**: ficam na tabela `technical_capacity_limits`. Mudar um valor é procedimento administrativo (o
  migrador grava o valor e quem autorizou). O serviço só lê; não há rota para alterar.
- O valor é um só para todas as organizações. Limite por organização não existe nesta versão.

## Capacidade não é licença

- A recusa por capacidade tem motivo próprio: `worker_limit_reached`, `capacity_concurrent_limit_reached`,
  `capacity_pending_limit_reached`. Nunca `license_required` nem `license_expired`.
- Bater num limite **não consome nem libera licença**: a conta de licenças não se mexe e a automação continua com direito.
- A leitura é separada: `GET /api/v1/o/{org}/capacity`, para quem administra a organização. A tela mostra capacidade em
  seção própria, fora do resumo de licenças.

```json
{ "model": "licensed",
  "workers": { "used": 3, "limit": 50 },
  "concurrentExecutions": { "used": 1, "limit": 5 },
  "pendingExecutions": { "used": 0, "limit": 50 },
  "asOf": "2026-11-19T12:00:00Z" }
```

No modelo anterior, `limit` vem nulo: a capacidade daquela organização é a da ativação.

## Onde o servidor confere

- **Cadastro de Worker** (direto ou pelo código de pareamento): gatilho do banco, sob a trava da organização.
- **Execução remota**: na solicitação e em cada tentativa nova, pela aplicação (para responder com o motivo) e por gatilho
  do banco na gravação, sob a trava da organização. Duas solicitações simultâneas não passam juntas do limite.

Um ajuste junto: o cadastro de Worker pelo código de pareamento passa a responder `worker_limit_reached` (409) quando o
limite impede, como o cadastro direto já fazia. Antes respondia uma recusa genérica.

## Limites desta etapa

- Os limites de simultâneas e de pendentes são conferidos **quando a tentativa é criada**. Uma tentativa pendente pode
  começar depois, então o número em andamento pode chegar, no pior caso, a simultâneas + pendentes. Segurar o início pela
  capacidade exigiria uma fila da organização, que a execução remota não tem.
- A execução da Platform (tabela `executions`) não tem rota de criação hoje e segue pelas regras antigas.
- O Agent anterior não é limitado por aqui: as execuções dele acontecem na máquina e só são relatadas.
- A execução local privada do Agent Client não passa pela Platform.

## Testes (`RemoteExecutionIT.capacidadeTecnicaSubstituiAAtivacaoNoModeloPorLicencaESeparadaDasLicencas`)

- modelo anterior: organização aguardando ativação não registra Worker; capacidade com limites nulos;
- modelo por licença: registra sem ativação; a organização continua sem ativação no banco;
- limites lidos: 50, 5 e 50; limite configurado para 2 recusa o terceiro Worker;
- outra organização não lê a capacidade desta;
- execução remota: pendentes no limite recusa com `capacity_pending_limit_reached`; em andamento no limite recusa com
  `capacity_concurrent_limit_reached`; nada gravado; terminou, volta a caber;
- com a capacidade esgotada, a conta de licenças é a mesma e a automação continua com direito;
- os valores decididos estão gravados; o papel do serviço não os altera.

## Reversão

Comandos no cabeçalho da 066, enquanto nenhuma organização estiver no modelo por licença. Depois disso, desligar a
exigência por organização devolve a ela a capacidade da ativação.
