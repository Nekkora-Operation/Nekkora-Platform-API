# PLATFORM-LICENCIAMENTO-001 — T04: atos do operador da Nekkora (D5)

Depende das PRs do modelo (061) e do vínculo (062). **A migration 063 exige elevação e está restrita ao laboratório.**
A exigência de licença continua sem efeito sobre execução e monitoração: nenhum fluxo a consulta.

## O que entra

- **Migration 063** (com elevação): papel dono `nekkora_licensing_owner`, cadastro de operadores e seis funções.
- **Rotas do operador**, na organização interna da Nekkora:

| Rota (`/api/v1/o/{org}/license-operations/organizations/{alvo}` + …) | Ato |
|---|---|
| `GET /enforcement-preview` | prévia do que ligar a exigência faria; não altera nada |
| `POST /licenses` | concede N licenças com início, fim e justificativa |
| `POST /licenses/{id}/renewal` | renova: acrescenta período, mantém o vínculo |
| `POST /licenses/{id}/end` | encerra a licença e o vínculo dela |
| `POST /enforcement` | liga ou desliga a exigência, com a prévia confirmada |

A transferência para outra automação **não** está aqui: é etapa própria, depois da conferência nos fluxos (precisa
recusar enquanto houver trabalho em andamento).

## Quem é operador

1. A organização interna da Nekkora é a da configuração `NEKKORA_LICENCAS_ORGANIZACAO_OPERADORA`. **Vazia por padrão:**
   sem ela, todas as rotas do operador respondem 404 e ninguém concede licença nem liga a exigência.
2. Sessão dessa organização, conta interna, **com segundo fator** (sessão sem segundo fator não opera).
3. A conta está no **cadastro de operadores** e não foi revogada. Ser administrador da organização interna não basta.
4. Cada ato exige **confirmação reforçada recente** — nessa sessão, sempre o código do autenticador.
5. O alvo é outra organização: o operador não concede à própria.

Incluir e revogar operador é procedimento administrativo, feito pelo migrador direto na tabela `license_operators`, com
o campo de quem autorizou. Não há rota para isso, de propósito: não é ato do dia a dia.

## O que a aplicação confere e o que o banco reconfere

| Conferência | Aplicação | Banco |
|---|---|---|
| Sessão válida da organização interna | sim | não |
| Segundo fator e confirmação reforçada | sim | não |
| Operador no cadastro e ativo | sim | **sim**, em todo ato |
| Operador não é da organização alvo | sim | **sim** |
| Justificativa presente (10 a 1000 caracteres) | sim | **sim** |
| Regras do ato (período, licença encerrada, modelo igual) | — | **sim** |

O que isso significa na prática: o serviço **não tem** permissão de escrita em licenças, períodos nem no modelo da
organização. Um defeito em qualquer outra rota não concede licença. Só as funções gravam, e elas recusam quem não está
no cadastro. O que o banco não vê é a sessão e o código do autenticador; isso é responsabilidade da aplicação.

## Por que elevação na instalação e não no uso

As funções pertencem a um dono com acesso às tabelas de licença de todas as organizações. Criar o papel e as funções com
esse dono exige a janela de elevação do migrador **uma vez**. Depois, conceder, renovar, encerrar e ligar a exigência
são chamadas comuns da aplicação.

- No laboratório e nos testes, a 063 entra no padrão de `db/bootstrap/aplicar_como_migrador.sh`.
- Em homologação e produção ela **não** está em `ops/homologacao/implantacao/elevacao.txt`. Sem essa autorização o
  executor recusa elevá-la. Nada foi pedido nem executado nesses ambientes.
- O dono novo não tem atributos e não alcança organizações, usuários, automações, sessões nem execuções (conferido nos
  invariantes da migration).

## Prévia e migração gradual

A prévia de uma organização mostra:

- `blockedAutomations`: automações **em uso** e **sem licença vigente** — as que ficariam impedidas de iniciar trabalho
  novo. Em uso = autorização vigente em alguma máquina, execução ou monitoramento nos últimos 30 dias, ou trabalho em
  andamento. Cada uma traz o motivo.
- `licensedAutomations`: as que seguem normais.
- `offlineAuthorizations` e `offlineAuthorizationsValidUntil`: **limitação declarada**. Autorizações já entregues a
  máquinas valem sem conexão até a validade. Ligar a exigência não bloqueia de imediato uma máquina desconectada; a
  prévia diz quantas autorizações existem e até quando a mais longa vale.
- `workInProgress`: trabalho em andamento, que não é interrompido.

Ligar exige `confirmPreview` igual ao `previewToken` da prévia conferida. O resumo é calculado sobre o **conjunto**: o
modelo atual, a identidade de cada automação que ficaria impedida e, de cada automação coberta, a licença que a cobre.
Mesma quantidade com outra automação, ou a mesma automação coberta por outra licença, dá outro resumo.

A prévia é refeita na mesma transação do ato, sob **trava exclusiva do modelo da organização**. Vínculo, concessão,
renovação e encerramento pegam a mesma trava em modo compartilhado: o que já confirmou aparece na prévia refeita; o que
ainda não confirmou espera o ato terminar. Se o conjunto mudou, o ato é recusado (`license_preview_changed`), nada é
gravado e é preciso nova prévia. O retrato, com as identidades e o resumo, vai para a trilha.

Limite: o uso que **começa** entre a prévia e o ato (uma autorização nova, uma execução nova) só passa a esperar a trava
na etapa da conferência nos fluxos, que é quem consulta o modelo. Até lá, ele aparece na prévia refeita se já confirmou.

Quem está em qual modelo aparece em `licenses/summary` (`model` e `modelSince`), para o administrador, e na própria
prévia, para o operador. Toda organização começa em `legacy`.

## Trilha

Cada licença concedida, cada renovação, cada encerramento e cada mudança de modelo grava um ato com o operador, a
organização, a justificativa e o instante. A trilha é só de acréscimo. O administrador da organização alcança esses
atos pela leitura das tabelas da própria organização (a tela vem na etapa da Web).

## Testes (`LicenseOperatorIT`)

Pela API real, com a 063 aplicada no banco de teste:

- administrador da organização interna fora do cadastro, administrador do cliente e chamada sem sessão não operam;
- o cliente não tem a rota na própria organização (404): não concede a si mesmo;
- conceder não vincula e não liga a exigência;
- prévia com automação impedida, automação licenciada e a limitação das autorizações já emitidas;
- ligar sem confirmar a prévia (400), com número errado (409, nada gravado), com o número certo (grava o retrato);
- renovar preserva o período anterior e mantém o vínculo; período sobreposto é recusado;
- encerrar encerra o vínculo com registro; licença encerrada não renova;
- sem confirmação reforçada o ato é recusado; operador revogado deixa de operar;
- o papel do serviço não concede em nome de quem não é operador, não lê o cadastro e não escreve direto nas tabelas.

Limite: o teste não prova, por execução, que a exigência ligada não afeta os fluxos. A prova é estrutural: só três
classes citam as tabelas de licença (`LicenseReadController`, `LicenseService`, `LicenseOperatorService`), e nenhuma
delas participa de execução ou monitoração.

## Reversão

Comandos no cabeçalho da 063, em janela de elevação, enquanto não houver ato do operador gravado. Depois disso, a
reversão operacional é desligar a exigência por organização e esvaziar a configuração da organização operadora (as
rotas voltam a responder 404).
