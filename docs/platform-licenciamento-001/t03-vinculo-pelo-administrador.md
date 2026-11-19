# PLATFORM-LICENCIAMENTO-001 — T03: vínculo explícito da licença pelo administrador (D1)

Depende da PR do modelo e das leituras (migration 061). A exigência de licença continua inexistente: vincular não muda o
que a organização executa.

## O que entra

- **Migration 062** (sem elevação): o serviço passa a poder **inserir** vínculo e o ato de vínculo na trilha, só na
  organização da sessão. Continua sem alterar e sem apagar: abre vínculos, nunca os encerra.
- **Rota** `POST /api/v1/o/{org}/licenses/{licenseId}/assignment` com `{ "automationId": "…" }`.

## Regras

| Regra | Onde é garantida |
|---|---|
| Só administrador da organização (`org.manage`) | rota **e** banco (o gatilho reconfere a permissão de quem vincula) |
| Confirmação reforçada recente | rota |
| A licença está vigente e não encerrada | banco |
| A licença não atende outra automação | banco (índice único) → `license_already_assigned` |
| A automação não consome outra licença | banco (índice único) → `automation_already_licensed` |
| A automação não está arquivada | banco → `license_automation_archived` |
| O ato fica na trilha, com quem vinculou | mesma transação do vínculo |
| Licença e automação de outra organização não existem para esta | 404, e o banco recusa |

Depois de vinculada, a licença fica com a automação durante o ciclo: suspender, renomear ou arquivar a automação não a
devolve; renovar a mantém. Só a transferência pelo suporte (etapa futura) muda a automação atendida.

A tela (etapa da Web) mostra a licença disponível e o aviso de que ela fica vinculada durante o ciclo; a API já devolve
o que ela precisa (`licenses/summary` e `licenses`).

## Concorrência pelo caminho real da API

`LicenseAssignmentIT.duasTentativasSimultaneasPelaApiNuncaProduzemDoisVinculos`: duas sessões de administradores
diferentes disparam o vínculo no mesmo instante, em 6 rodadas, em dois cenários:

- a **mesma licença** para duas automações: uma resposta 200 e uma 409 `license_already_assigned`;
- **duas licenças** para a mesma automação: uma 200 e uma 409 `automation_already_licensed`.

Em toda rodada há exatamente um vínculo e um ato. No fim, a conta da API (18 contratadas, 12 utilizadas, 6 disponíveis)
fecha com o banco, e nenhuma licença tem dois vínculos nem automação tem duas licenças.

Como é garantido: o ato roda sob a trava da organização (as duas tentativas se enfileiram) e, independente disso, os
índices únicos do banco recusam a segunda.

## Testes

`LicenseAssignmentIT` (2 testes) e `LicenseReadsIT` (2 testes), em banco real com todas as migrations e o papel real do
serviço: confirmação reforçada, trilha, cada recusa com o seu motivo, pausa e arquivamento sem devolver a licença,
isolamento entre organizações, permissões, e o que o papel do serviço não consegue fazer direto no banco.

## Uma correção em relação à 061

O gatilho do vínculo travava a linha da licença. O serviço só tem leitura na tabela de licenças, e travar linha exige
privilégio de escrita; a 062 troca por uma trava pelo identificador da licença (o mesmo recurso da migration 033). Os
atos do operador que encerram ou renovam a licença pegam a mesma trava.

## Reversão

Comandos no cabeçalho da migration 062: retirar as duas políticas e o privilégio de inserção, e restaurar o gatilho da
061. Vínculos já feitos ficam (a trilha é só de acréscimo).
