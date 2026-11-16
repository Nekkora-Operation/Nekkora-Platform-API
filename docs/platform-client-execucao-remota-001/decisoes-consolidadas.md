# Execução remota — decisões consolidadas

Este documento reúne as decisões **finais** da titular sobre a execução remota e **prevalece** sobre o que os documentos
anteriores desta pasta e o plano T00 disserem de diferente. Quando uma regra ainda não está implementada, isso é dito na
coluna "Estado".

Última atualização: 16/11/2026.

## 1. O que substitui o plano T00

| O plano T00 dizia | Decisão final | Onde |
|---|---|---|
| Prazo da comunicação vencido vira "comunicação perdida" e a tentativa não é encerrada pelo servidor | Depois da espera configurável (padrão 120 minutos) o servidor **consolida** "Resultado não confirmado", com a causa. Não inventa sucesso, falha, cancelamento nem código de saída | `encerramento-sem-confirmacao.md` |
| O estado é corrigido quando a máquina volta ou alguém consulta | Há também um **ciclo periódico** do servidor: o estado não depende de alguém abrir a tela | `reconciliacao-periodica.md` |
| O que a máquina guardou é entregue depois de uma nova habilitação | **Não.** Vínculo novo não reenvia nem herda o conteúdo do anterior. Uma recuperação autorizada fica para o futuro | Client, `docs/execucao-remota.md` |
| Retenção do servidor: proposta de 30 dias, sem implementação | Implementada: 30 dias configuráveis, do fim da entrega; histórico e auditoria fora | `retencao-no-servidor.md` |
| Verificação sob demanda com limites fixos | Parâmetros iniciais configuráveis; limite por máquina e automação | `verificacao-sob-demanda.md` |

## 2. Ocupação da máquina

| Regra | Estado |
|---|---|
| Uma máquina executa uma tentativa por vez; a reserva é atômica | implementado |
| A máquina é a última autoridade: recusa "ocupada" o que chegar com um processo rodando nela | implementado |
| Pausar novos trabalhos não interrompe a execução atual; desabilitar com execução ativa é recusado | implementado |
| **Um novo contato não torna a máquina livre.** Se ela ainda executa a tentativa anterior, aparece **ocupada**. Se não consegue confirmar que o processo terminou, a ocupação fica **incerta** | **a implementar** (hoje: livre no primeiro contato depois do encerramento) |
| **Encerrar o vínculo impede novos trabalhos, mas não comprova que o processo terminou** | **a implementar** nos textos e na regra de repetição |
| Tentativa que o servidor nunca autorizou a iniciar não ocupa a máquina | implementado |

## 3. Repetição da execução

| Regra | Estado |
|---|---|
| Nunca há repetição automática nem troca automática de máquina | implementado |
| Repetir exige permissão de executar, confirmação reforçada, a tentativa anterior terminada e a autorização vigente do Worker de destino | implementado |
| **A repetição fica bloqueada enquanto não houver confirmação de que a execução anterior terminou e de que não há processo ativo** — qualquer que seja a causa: perda de comunicação, revogação ou reinício do aplicativo. Reconhecer o risco **não** basta: isso ainda permite duplicidade | **a implementar** (hoje: reconhecimento explícito, e só depois de comunicação perdida) |
| Depois da reconciliação, repetir mantém a confirmação sobre os possíveis efeitos parciais da execução anterior | implementado para "resultado não confirmado"; a rever junto com a regra acima |

**Ponto em aberto, para decisão:** quando a máquina **não pode mais falar** com a Platform (vínculo revogado ou encerrado,
computador que não volta), não existe hoje quem confirme o término. Com a regra acima, a repetição daquela solicitação fica
bloqueada sem prazo. Falta decidir se haverá um ato de reconciliação feito por uma pessoa, com registro, e quem pode fazê-lo.

## 4. Entrega de registros e resultados

| Regra | Estado |
|---|---|
| Informar o desfecho não encerra a entrega: os envios continuam até a confirmação, o prazo de guarda ou um impedimento dito | implementado |
| Só a confirmação da Platform apaga o que está no computador | implementado |
| Impedimento de autorização é dito e não é contornado | implementado |
| Vínculo novo não herda nem reenvia o que o anterior deixou | implementado |
| O que passa do limite é contado; a leitura separa as linhas da automação dos avisos do aplicativo | implementado |
| Registro ou resultado que falta nunca muda o resultado da automação | implementado |
| O envio do que ficou guardado é em fatias por consulta e cede a vez à tentativa ativa | implementado |
| Evidência posterior ao encerramento pelo servidor é aceita e fica ao lado | implementado |

## 5. Limpeza

| Regra | Estado |
|---|---|
| No computador: o não confirmado fica guardado até o prazo de guarda (7 dias, configurável), com aviso; a remoção deixa registro da perda e avisa a Platform quando possível | implementado |
| No servidor: 30 dias configuráveis do fim da entrega; sai o conteúdo, fica o histórico | implementado |
| **Teto para entrega que não se conclui:** 30 dias configuráveis **sem progresso de entrega**, só quando **não houver execução ativa nem incerta**. A presença da máquina **não** renova o prazo. Ao vencer: registrar **entrega incompleta** e aplicar a retenção, **sem indicar recebimento** | **a implementar** (hoje: entrega aberta não vence) |
| A retenção do servidor fica desligada por padrão; ligar é decisão de cada ambiente | implementado |

## 6. Migrations

- 042 a 057, em sequência, nenhuma com elevação.
- A 054 e a 056 ficam **separadas** (decisão de 16/11).
- A 048 só existe na forma final: a forma da primeira PR nunca é aplicada sozinha.
- A sequência completa é validada pelo migrador normal, em banco limpo e na atualização a partir da referência.

## 7. Integração e promoção

- Validação em seis blocos (pareamento; preparação; execução e resultados; logs; recuperação; retenção e interface), sempre
  sobre as branches de integração inteiras. Cada rodada registra os três SHAs, a versão do banco e o hash do executável.
- **Promoção única** para a `dev`, depois dos seis blocos, em ordem coordenada entre os repositórios (API, Web, Client),
  preservando as PRs e as dependências. Não há promoção por trecho.
- Depois, o aceite integrado é repetido com o conteúdo efetivo da `dev`, antes de qualquer promoção para `main`.
- As PRs de rascunho das branches de integração servem só para rodar os checks: não são mescladas e não substituem as PRs por
  tarefa.
- O passo "Abrir arquivo" é executado manualmente pela titular. Nenhum programa é aberto na sessão dela por automação.
