# PLATFORM-LICENCIAMENTO-001 — T08: criação de organizações por perfil, responsável, vazias e desativação (D7, D9 a D13)

Depende das PRs 061 a 066. Migration **067, com elevação** — só laboratório e testes até autorização específica.

## O que muda

| Hoje | Com a 067 |
|---|---|
| Qualquer pessoa com conta confirmada cria organização, até 10 | Quem cria depende do **perfil**, decidido no servidor a cada tentativa |
| Não há pedido nem aprovação | O membro pede; um administrador da organização dele aprova e escolhe o responsável |
| Não há limite de organizações sem uso | Três organizações **vazias** por responsável |
| A organização não pode ser desativada por quem a criou | O responsável desativa e reativa |
| Toda organização nasce no modelo anterior | Uma chave, **desligada**, faz as novas nascerem no modelo por licença (D7) |

**Não há teto fixo de organizações criadas.** O teto de 10 da 017 foi retirado nesta migration (função de limite e a indicação `canCreateOrganization`): o que limita é só o limite configurável de vazias. O limite de frequência da rota de criação (três por dia por pessoa, R11) continua: é proteção contra abuso, não quantidade de organizações.

## Perfil de quem cria

| Perfil | Como o servidor reconhece | Regra |
|---|---|---|
| Primeira organização | nenhuma conta ativa em nenhuma organização | cria direto |
| Administradora | conta interna ativa com papel de administrador em alguma organização ativa | cria direto |
| Membro | conta interna ativa, em nenhuma é administradora (leitor, operador, administrador de ambiente) | não cria; **solicita** |
| Cliente | não tem conta interna ativa e existe conta do tipo Cliente ativa com o **mesmo e-mail verificado** | não cria nem solicita |

- O perfil é reavaliado a cada tentativa, com o que está gravado no servidor. A interface só reflete.
- **Ser Cliente em A não tira a administração de B**: a conta interna decide. O perfil de B não dá nada em A — a pessoa só
  pede numa organização em que tem conta interna.
- A rota recusa com o motivo; a função do banco recusa a mesma coisa por chamada direta.

**Como o Cliente é reconhecido, e o limite disso.** A conta do tipo Cliente é local da organização e não fica ligada à
conta geral da pessoa. O reconhecimento é pelo e-mail verificado igual. Quem usar **outro e-mail** é, para o sistema, outra
pessoa, recém-cadastrada. O que limita o efeito: a organização criada assim não dá acesso à organização em que ela é
Cliente, não recebe licença e conta no limite de vazias.

## Responsável pela organização

Toda organização criada daqui em diante tem um **responsável**: quem a criou, ou quem a aprovação escolheu. O responsável é
de quem se conta o limite de vazias e quem pode desativar e reativar. Não é propriedade e, sozinho, não concede permissão.

As organizações existentes recebem como responsável o autor registrado quando foram criadas pelo cadastro. As provisionadas
pela operação ficam sem responsável: não podem ser desativadas por esta ação e não contam para ninguém.

## Limite de vazias

- **Vazia** = organização ativa sem nenhuma automação. Ambientes, máquinas e pessoas não contam.
- Três por responsável, configurável por procedimento administrativo.
- Conferido na criação, na aprovação de um pedido e na reativação, sob a trava do responsável: duas criações ao mesmo tempo
  não passam juntas.
- Organizações existentes acima do limite continuam como estão; só a criação seguinte é recusada.
- A resposta diz quais são as vazias.

## Pedido e aprovação

1. O membro pede, com a confirmação reforçada, indicando **em qual organização** pede (uma em que é membro interno ativo)
   e o nome. Até três pedidos pendentes por pessoa.
2. Um administrador **daquela** organização vê o pedido e decide, com confirmação reforçada. Ninguém decide o próprio pedido.
3. Na aprovação ele **escolhe o responsável**: ele mesmo (padrão) ou o solicitante. A escolha:
   - concede a administração da nova organização ao escolhido;
   - decide de quem é contado o limite de vazias, que é **reconferido nesse momento**;
   - fica registrada no pedido, com quem decidiu e quando.
4. A organização nasce na mesma transação. **Só o responsável recebe acesso.** Se o administrador ficou responsável, o
   solicitante não entra na nova organização; se entregou ao solicitante, o administrador não entra.
5. A recusa exige motivo, que o solicitante lê.

Quem aprova com uma conta antiga, sem conta geral, não pode ser o responsável; pode entregar ao solicitante.

## Desativar e reativar

**Desativar**: só o responsável que ainda administra a organização, com confirmação reforçada.

- Recusado enquanto houver, ou ainda puder haver, trabalho em andamento em qualquer automação (mesma regra da transferência,
  incluindo ocupação incerta).
- A organização fica parada: ninguém entra, as sessões abertas caem e ela sai da lista de quem entra.
- **Nada é apagado nem liberado.** Histórico, licenças, períodos, vínculos e entregas ficam como estão. A licença continua
  vinculada à mesma automação: desativar **não** libera licença para outra automação nem muda o ciclo.
- A desativada deixa de contar no limite de vazias.

**Reativar**: o responsável, pela lista das organizações dele.

- O limite de vazias é reconferido.
- Só desfaz a desativação feita por esta ação.

**Bloqueio da Nekkora.** A reativação é recusada quando:

- a organização foi parada por outro caminho (a operação da Nekkora mudou a situação, sem desativação desta ação);
- ou há um bloqueio da Nekkora registrado, mesmo que o responsável também a tenha desativado.

O bloqueio é registrado por procedimento administrativo (tabela `organization_nekkora_holds`), junto da mudança de situação.

## D7 — organizações novas no modelo por licença

- Uma chave (`license_rollout`), que **nasce desligada**. Não há data.
- Ligá-la é procedimento administrativo, com quem autorizou, depois de os fluxos e a interface estarem validados.
- Ligada, toda organização criada dali em diante nasce no modelo por licença, com um ato próprio na trilha (ato 8) dizendo
  quem autorizou a chave. As existentes não mudam: continuam por migração explícita e auditada.
- A organização que nasce assim registra máquinas sem ativação (T07) e só executa e monitora com licença concedida.

## Por que elevação

As funções de criação pertencem ao dono da identidade (017) e uma auxiliar, ao dono das licenças (063). A 067 dá a eles:

- **dono da identidade**: ler contas do tipo Cliente (organização, situação, e-mail) e os vínculos de cliente (situação),
  para reconhecer o perfil; ler a existência de automações das organizações de que a pessoa é responsável; e **alterar a
  situação** da organização, só dentro das duas funções de desativar e reativar;
- **dono das licenças**: nenhuma leitura nova; grava o ato 8 e o modelo da organização recém-criada.

No laboratório e nos testes a 067 entra no padrão de `db/bootstrap/aplicar_como_migrador.sh`. Em homologação e produção ela
**não** está em `ops/homologacao/implantacao/elevacao.txt`. Nada foi pedido nem executado.

## Rotas

| Rota | Quem | O que faz |
|---|---|---|
| `GET /api/v1/me/organization-creation` | a pessoa | perfil, limites, organizações de que é responsável |
| `POST /api/v1/organizations` | a pessoa | criação direta (passa a recusar pelo perfil e pelo limite) |
| `POST /api/v1/organization-requests` | o membro | pede |
| `GET /api/v1/me/organization-requests` | o membro | os pedidos dele |
| `GET /api/v1/o/{org}/organization-requests` | administrador | os pedidos feitos à organização |
| `POST …/organization-requests/{id}/approve` | administrador | aprova, escolhendo o responsável |
| `POST …/organization-requests/{id}/refuse` | administrador | recusa, com motivo |
| `POST /api/v1/organization-deactivations` | o responsável | desativa |
| `POST /api/v1/organization-reactivations` | o responsável | reativa |

Todas abrem e fecham com a chave que já controla a criação de organização nesta instalação.

## O que não está nesta etapa

- A criação excepcional pelo administrador da Nekkora, em nome de alguém: sem definição de quem vira administrador.
- Cancelar o próprio pedido: quem decide é o administrador (aprova ou recusa).
- Trocar o responsável de uma organização depois de criada.
- A máquina já pareada continua autenticando depois de a organização parar (isso já era assim na suspensão). Não há
  trabalho novo pela Platform, porque ninguém entra; o Agent anterior, que executa na máquina, continua podendo registrar.

## Testes (`OrganizationProfilesIT`, API real)

- primeira organização direta; responsável registrado; limite de três vazias pela rota e pelo banco; a lista das vazias;
  organização com automação deixa de contar; duas criações simultâneas com uma vaga: uma nasce; limite configurável;
- Cliente: não cria nem solicita, pela rota e pelo banco; nada gravado; conta de Cliente suspensa deixa de definir o perfil;
  a mesma pessoa administradora de outra organização cria; não pede em nome da organização em que é Cliente;
- membro: não cria; pede só onde é membro e com confirmação; quarto pedido pendente recusado; pedir não cria nada;
- o membro não lê nem aprova pedidos; aprovar exige confirmação e escolha explícita;
- aprovação com o administrador responsável: organização dele, uma conta só, o solicitante sem acesso;
- aprovação entregando ao solicitante: ele administra, quem aprovou sem acesso;
- limite de vazias do responsável reconferido na aprovação: pedido continua pendente;
- recusa com motivo; o banco não aceita decisão em nome de quem não administra;
- aprovador sem conta geral: não pode ser o responsável, pode entregar ao solicitante;
- desativar: outro administrador não desativa; sem confirmação não; com trabalho em andamento não; licença, período e
  vínculo idênticos depois; histórico preservado; sessão aberta cai;
- reativar: só o responsável; organização parada pela operação não reativa (situações 2 e 3); com bloqueio registrado não;
  bloqueio levantado, reativa; limite de vazias reconferido;
- D7: com a chave ligada a organização nova nasce no modelo por licença, com o ato 8; a existente não muda; desligada,
  nada.

Não testado: a 067 em banco com dados reais (só no banco de teste, criado do zero).

## Reversão

Descrita no cabeçalho da 067, em janela de elevação, enquanto não houver pedidos, desativações nem organizações nascidas
pelo ato 8.
