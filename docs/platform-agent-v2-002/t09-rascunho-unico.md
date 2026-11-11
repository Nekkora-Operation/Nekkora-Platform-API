# PLATFORM-AGENT-V2-002 · T09: um único rascunho por automação

## Regra
- Uma automação tem **no máximo um rascunho** (versão `DRAFT`). A migration 032 garante isso no banco, com o índice único parcial `ux_automation_versions_one_draft`. Versões publicadas não participam.
- O rascunho **só passa a existir quando o servidor aceita o ZIP**. Abrir "Nova versão", escolher um arquivo ou desistir não grava nada e não consome número.
- No instante em que o ZIP é aceito, o rascunho já está salvo, com o pacote, o runtime e os argumentos. Se a pessoa voltar, fechar a aba ou perder a resposta, ele continua lá.
- O ciclo de vida da automação-pai não muda; a regra vale para a versão.

## Rotas

### `PUT /api/v1/o/{org}/automations/{automationId}/draft/artifact`
Rota nova; o corpo é `application/zip`.

| Situação | Resposta |
|---|---|
| Não há rascunho e o pedido não traz expectativa | **201**: cria o rascunho com o número seguinte e o artefato, na mesma transação. `runtime` e `argument` valem para esse rascunho |
| O rascunho existe e a expectativa confere (`expectedDraftVersionId` e o `expectedSha256` do pacote atual; sem `expectedSha256` se ele ainda não tem pacote) | **200**: troca o pacote na **mesma** versão |
| A expectativa está desatualizada: outra aba ou pessoa enviou outro pacote, ou o rascunho esperado foi publicado | **409** `conflict` + `reason: draft_changed`. Nada é sobrescrito; a Web relê o rascunho e mostra qual pacote ficou |
| O rascunho já tem exatamente estes bytes (repetição depois de uma resposta perdida) | **200** com o mesmo rascunho |
| ZIP inválido | **422** `package_rejected`. Nada é criado e o pacote anterior continua valendo |

### `POST /api/v1/o/{org}/automations/{automationId}/versions`
Rota antiga, usada pela Web V1.
- Se a automação já tem rascunho, responde **409** `conflict` + `reason: draft_exists` e não cria nada.
- Se não tem, **ainda cria um rascunho vazio**, e só um. É a limitação transitória da Web V1: ela continua podendo deixar um rascunho sem pacote, nunca dois.

A publicação continua explícita e não muda: exige confirmação, congela os bytes conferidos e não executa nada.

## Web V1 (`3311dac`) com a API nova
- **Nova versão:**
  - sem rascunho, cria o rascunho vazio, como antes;
  - com rascunho, a V1 mostra a recusa comum de conflito ("nada foi feito"). O rascunho existente continua na lista de versões.
- **Envio do pacote por versão** (`PUT /automation-versions/{id}/artifact`): não muda. Não há verificação de expectativa nessa rota, então duas abas da V1 ainda podem trocar o pacote uma da outra, como hoje, mas sem criar outra versão.
- **Rascunho vazio da V1 na Web nova:** aparece como "Continuar rascunho" (expectativa = o ID dele, sem sha).

## Dados existentes
- **Antes da 032**, rodar o diagnóstico, que é só leitura:

  ```
  psql -X -v ON_ERROR_STOP=1 -d nekkora -f db/diagnosticos/032_rascunhos_por_automacao.sql
  ```
  - Seção 1: automações com mais de um rascunho. **Impedem** a 032.
  - Seção 2: rascunhos legados sem pacote. É informativa e não impede.
- **Se houver repetição,** a 032 para antes de alterar qualquer coisa e lista os registros. A decisão é humana: nada é apagado, publicado nem renumerado automaticamente.
- **Suíte de banco:** `db/tests/rascunho-unico-032/run.sh`, com três cenários: A (instalação limpa), B (base populada, com um segundo rascunho recusado pelo banco) e C (repetições recusadas, depois uma decisão humana simulada e a aplicação).

## Numeração e ordem
- **Por que 032:** é a próxima livre, conferida nas branches remotas. As ferramentas do laboratório e da homologação exigem numeração contígua. A T02 da PLATFORM-V2-001 (sessão) não tem migration escrita: quando tiver, usa o próximo número livre.
- **Ordem de atualização:**
  1. Rodar o diagnóstico.
  2. Aplicar a API (032).
  3. Aplicar a Web nova.

  Com a API nova no ar, a Web V1 continua funcionando dentro da limitação acima. A Web nova exige a API nova, porque usa a rota do rascunho.
