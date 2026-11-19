# PLATFORM-LICENCIAMENTO-001 — T01: decisões tomadas, decisões pendentes por extenso e esclarecimentos do contrato

Complementa o `t00-inventario-e-contrato.md`. Nenhum comportamento muda com este documento.

Sumário: 1. decisões tomadas · 2. as oito decisões pendentes, por extenso · 3. autorização por Worker: o que cada
etapa protege e como simplificar · 4. migração gradual: quem está em qual modelo e a prévia · 5. concessão pela
aplicação (D5) · 6. primeira organização e o perfil Cliente (D9) · 7. diagnóstico em homologação e produção (D14).

---

## 1. Decisões tomadas (19/11/2026)

| # | Decisão |
|---|---|
| D1 | A automação passa a consumir licença por **ato explícito do administrador da organização**. A tela mostra a licença disponível e avisa que ela fica vinculada à automação durante o ciclo. |
| D4 | O ciclo tem **início e fim registrados na concessão**. Renovar **mantém o vínculo** com a automação. Cada renovação fica no histórico; os períodos anteriores são preservados. |
| D5 | Quem concede é o **operador autorizado da Nekkora**, com autenticação forte e auditoria, **pela aplicação**, sem elevação no banco a cada operação. Migration privilegiada na instalação, se houver, é documentada e **não está autorizada** em homologação nem em produção. |
| D9 | Pessoa recém-cadastrada, fora do perfil restrito de Cliente, **cria a primeira organização direto** e vira administradora dela. Sem aprovação externa, sem licença automática. Convidados já vinculados como membros pedem ao administrador responsável. |
| D13 | **Desativar** a organização vazia; a desativada deixa de contar no limite. A interface diz "Desativar organização". Reativar confere de novo o limite de três vazias. Dados e histórico preservados. |
| D14 | Preparar o procedimento somente leitura, as consultas e o formato do relatório. Executar em homologação e produção só com autorização específica. |

Nesta etapa: modelo e leituras, com a exigência de licença **desligada por padrão**; nenhuma licença criada
automaticamente; nenhum controle antigo retirado.

---

## 2. As oito decisões pendentes, por extenso

Para cada uma: como é hoje, o que mudaria, quem sente a mudança, e o que acontece se nada for decidido.

### D2 — Ativação da organização e limites de capacidade

**Hoje.** Toda organização nasce "aguardando ativação". Enquanto não é ativada por um operador da Nekkora, os limites
de Workers registrados, execuções simultâneas e execuções pendentes valem zero: o administrador não consegue registrar
nenhuma máquina. Na ativação, o operador informa os três números, que só ele pode mudar depois.

**O que mudaria.** Para organizações com a exigência de licença ligada:
- registrar Worker deixa de depender da ativação;
- o número de Workers deixa de ser contratado; passa a existir só um teto técnico, igual para todos, contra abuso;
- execuções simultâneas e pendentes passam a ter um valor padrão técnico, igual para todos.

**Quem sente.** O administrador de uma organização nova: hoje espera a Nekkora ativar antes de registrar a primeira
máquina; passaria a registrar máquinas sem esperar e a depender só da licença para executar e monitorar. A operação da
Nekkora: deixa de fazer o ato de ativação e de negociar três números por cliente.

**Preciso de:** sim ou não para a mudança, e os três valores do teto técnico (sugestão: 50 Workers, 5 execuções
simultâneas, 50 pendentes por organização).

**Sem decisão:** a ativação e os limites continuam como hoje, também para quem já exige licença. A licença vira um
segundo controle, somado ao primeiro, e a simplificação não acontece.

### D3 — Validade da autorização de execução local

**Hoje.** Para um pacote rodar numa máquina, alguém com permissão autoriza aquela **versão** naquele **Worker**, com
validade escolhida de até 366 dias. Vencida, a máquina para de poder executar aquela versão até alguém autorizar de
novo. Vale para o Agent anterior e para a execução remota do Agent Client.

**O que mudaria.** Para organizações com a exigência de licença ligada, a autorização passaria a valer **até ser
revogada**, sem data de vencimento. Continuaria sendo por versão e por máquina, revogável a qualquer momento.

**Quem sente.** O administrador: deixa de renovar autorizações que venceram sem que nada tenha mudado. O risco: uma
autorização esquecida não expira mais sozinha.

**Atenção a um ponto técnico** (seção 3): o Agent anterior guarda as autorizações para uso sem conexão **até a data de
validade**. Para ele, a validade é também o limite do uso sem contato com a Platform. Por isso a recomendação é
diferente por fluxo: tirar o vencimento só da execução remota do Agent Client, que é conferida no servidor a cada
solicitação; manter no Agent anterior.

**Sem decisão:** a validade continua para todos.

### D6 — Como o pedido de transferência chega ao suporte

**Hoje.** Não existe transferência.

**O que mudaria.** Duas formas possíveis:
- (a) fora da Platform: o administrador pede pelo canal de suporte; o operador registra o ato informando quem pediu;
- (b) dentro da Platform: um botão "solicitar transferência" que grava o pedido (origem, destino, justificativa) e o
  operador vê os pedidos em aberto.

**Quem sente.** Em (a), o administrador sai da Platform para pedir, e a prova de que foi ele quem pediu é o registro do
operador. Em (b), o pedido fica provado pela sessão do administrador, mas cria-se uma fila de pedidos com estados, que
é o começo de um sistema de chamados (fora do escopo declarado).

**Recomendo (a)** nesta missão, com o ato exigindo o administrador solicitante identificado e conferido como
administrador da organização no momento do ato.

**Sem decisão:** implemento a transferência como ato do operador e não crio pedido pela Platform.

### D7 — Organizações novas já nascem exigindo licença?

**Hoje.** Não há licença.

**O que mudaria.** A partir de uma data definida por você, toda organização **criada depois** nasceria no modelo novo:
só executa e monitora automação com licença. As organizações que já existem continuam no modelo antigo até serem
regularizadas uma a uma.

**Quem sente.** Quem criar uma organização depois dessa data: sem licença concedida, cadastra automações e máquinas, mas
não executa nem monitora pela Platform.

**Recomendo** sim, mas só depois que a concessão pela aplicação (seção 5) e a tela do administrador estiverem
entregues; até lá, organizações novas nascem no modelo antigo, como hoje.

**Sem decisão:** organizações novas continuam nascendo no modelo antigo.

### D8 — Agent anterior com a licença vencida

**Hoje.** O Agent anterior busca as autorizações da máquina, guarda-as e executa o pacote localmente; depois registra
a execução na Platform e envia eventos e registros. Ele consegue executar **sem conexão**, com a autorização guardada,
até a validade dela. (Conferido no código: `AuthorizationCache` guarda as autorizações "para uso OFFLINE dentro do
validUntil".)

**O que mudaria.** Com a licença da automação vencida, a Platform:
- deixaria de **entregar** a autorização daquela automação à máquina;
- recusaria o **registro de novas execuções** e de novas sessões de monitoramento daquela automação;
- continuaria aceitando eventos e registros das execuções registradas enquanto a licença valia.

**Limite que precisa ficar claro.** A Platform não consegue impedir a execução que acontece sem conexão com uma
autorização já guardada na máquina: ela só descobre depois. O que ela pode fazer é não renovar a autorização e não
aceitar o registro. Se a máquina ficar sem contato, o Agent anterior pode continuar executando até a validade guardada.

**Quem sente.** Clientes que usam o Agent anterior: com a licença vencida, a máquina deixa de receber a autorização e
as execuções novas não aparecem mais na Platform.

**Alternativa.** Tratar o Agent anterior como "execução local independente": ele continua executando e a Platform só
deixa de receber a monitoração. Não recomendo: nesse fluxo é a Platform que autoriza o pacote.

**Sem decisão:** a conferência de licença não é aplicada aos fluxos do Agent anterior; só à execução remota do Agent
Client e à execução da Platform.

### D10 — Quem é "desenvolvedor"

**Hoje.** Os papéis são administrador da organização, administrador de workspace, operador e leitor. Não existe
"desenvolvedor".

**O que mudaria.** Nada no catálogo de papéis. Só define quem pode **solicitar** a criação de uma organização: proposta,
qualquer membro interno ativo da organização que não seja administrador dela (administrador de workspace, operador ou
leitor).

**Quem sente.** Leitores e operadores passam a poder pedir a criação; quem decide é sempre o administrador.

**Alternativa.** Restringir o pedido a administrador de workspace e operador, deixando o leitor de fora.

**Sem decisão:** uso "qualquer membro interno que não é administrador".

### D11 — Pessoa com perfis diferentes em organizações diferentes

**Hoje.** A mesma pessoa (mesma identidade) pode ser Cliente na organização A e membro interno ou administradora na B.
O tipo Cliente é da conta naquela organização, não da pessoa.

**O que mudaria.** Duas leituras possíveis da regra "Cliente não cria nem solicita":
- (a) vale o perfil da organização a partir da qual a pessoa age: como Cliente de A ela não pede nada a A; como
  administradora de B ela cria direto;
- (b) ser Cliente em qualquer organização bloqueia a pessoa inteira.

**Quem sente.** Em (b), um administrador legítimo perde a criação porque um fornecedor o cadastrou como Cliente. Em
(a), um Cliente que também seja administrador de outra organização cria por ela — o que já é direito dele.

**Recomendo (a).** O perfil restrito de Cliente (seção 6) fica sendo: a identidade cujas contas são **todas** do tipo
Cliente.

**Sem decisão:** uso (a).

### D12 — Responsável pela nova organização quando um membro solicita

**Hoje.** Quem cria vira a primeira administradora.

**O que mudaria.** Na aprovação de um pedido, a nova organização precisa de uma primeira administradora. Opções:
- (a) o administrador que aprova;
- (b) o membro que solicitou;
- (c) quem aprova escolhe entre os dois na hora.

**Quem sente.** Em (b), um operador vira administrador de uma organização (e conta para o limite de vazias dele). Em
(a), o administrador acumula organizações e o solicitante não entra nela a menos que seja convidado depois.

**Recomendo (c), com (a) como padrão:** a responsabilidade e o limite de vazias ficam com quem tem autoridade para
aprovar, e ele pode entregar ao solicitante quando fizer sentido. Em qualquer opção, ninguém mais ganha acesso.

**Sem decisão:** não implemento a aprovação de pedidos (a etapa E7 fica parada nesse ponto).

---

## 3. Autorização por Worker: o que cada etapa protege e como simplificar

Hoje, para uma automação rodar numa máquina, são estes os atos manuais:

| # | Ato | Quem | Risco que protege | Proposta |
|---|---|---|---|---|
| 1 | Registrar o Worker | quem gerencia Workers | organização registrar máquinas além do contratado | o limite contratado sai (D2); o ato continua, é o cadastro da máquina |
| 2 | Vincular a máquina: código criado na Platform com confirmação reforçada, resgatado na máquina com segredo gerado nela | quem gerencia Agents + quem está na máquina | máquina de terceiro se passar por máquina da organização; alguém vincular uma máquina sem o dono dela saber | **permanece inteiro**: é o consentimento do vínculo |
| 3 | Habilitar a máquina para execução remota (código marcado) | quem cria o código + a máquina | a Platform passar a mandar código para uma máquina que só foi vinculada para ser monitorada | **permanece**: é consentimento da máquina para receber execução |
| 4 | Autorizar o Worker no workspace | quem autoriza Workers | máquina de um workspace receber automações de outro | **permanece**: é controle de acesso |
| 5 | Autorizar a **versão** naquele Worker, com validade | quem autoriza execuções, com confirmação reforçada | (i) uma versão nova chegar à máquina sem que alguém responsável pela máquina a aceite; (ii) uso prolongado sem contato com a Platform (Agent anterior) | o consentimento por versão **permanece**; a renovação por vencimento é a parte redundante (D3) |
| 6 | Escolher o Python e conferir dependências | quem gerencia a automação | execução falhar ou rodar no ambiente errado | **permanece**: é técnico |
| 7 | Repetir o ato 5 a cada versão publicada, em cada Worker | quem autoriza execuções | o mesmo de (i) | é onde está o atrito; ver abaixo |

O que **não** é proteção de acesso e pode sair sem perda: o limite contratado de Workers (ato 1) e o vencimento que
obriga a refazer o ato 5 sem que nada tenha mudado.

Simplificações possíveis para o ato 5 e o 7, da mais conservadora à mais ampla:

- **S1. Sem vencimento na execução remota (é a D3).** Não remove nenhuma conferência: o servidor continua exigindo a
  autorização daquela versão naquela máquina a cada solicitação. Remove só o prazo.
- **S2. Autorizar no mesmo passo de solicitar.** Quem tem as duas permissões (autorizar e executar) e solicita a
  execução de uma versão ainda não autorizada naquela máquina recebe, no mesmo diálogo, a pergunta "autorizar esta versão
  nesta máquina?", com a mesma confirmação reforçada. O ato e o registro continuam existindo; some a ida a outra tela.
  Quem só tem permissão de executar continua precisando de alguém que autorize.
- **S3. Autorizar a automação naquela máquina, valendo para as versões futuras.** Remove o ato 7. **Tem custo de
  controle:** quem publica uma versão nova passa a conseguir levá-la à máquina sem que quem responde pela máquina a
  aceite. Só é equivalente ao controle de hoje quando quem publica e quem autoriza são a mesma pessoa.

**Recomendo S1 e S2.** S3 só como opção marcada explicitamente por máquina e automação, e só se você quiser; não a
incluo sem decisão. Nenhuma das três mexe nos atos 2, 3 e 4.

---

## 4. Migração gradual: quem está em qual modelo, e a prévia

### 4.1 O modelo de cada organização é um dado visível

Cada organização tem um **modelo de habilitação**, registrado com data e responsável:

- **Anterior**: vale a ativação e os limites de hoje. Licenças concedidas a ela aparecem, mas não são exigidas.
- **Por licença**: executar e monitorar exige licença vigente vinculada à automação.

Onde aparece:
- para o **administrador**, na tela "Execução e monitoração": "Esta organização ainda não exige licença" ou "Esta
  organização exige licença desde dd/mm/aaaa";
- para o **operador**, na lista de organizações: modelo, desde quando, quem ligou, licenças contratadas e utilizadas;
- na **API**, o resumo de licenças traz o campo do modelo; nunca fica implícito.

Toda organização existente fica em "Anterior" na implantação. Nada muda de modelo sozinho.

### 4.2 Ligar a exigência tem prévia obrigatória

Antes de ligar, o operador pede a **prévia** da organização. Ela lista, sem alterar nada:

- as automações **com uso** e **sem licença vigente** — as que ficariam impedidas de iniciar: nome, workspace, e o
  motivo de ser considerada em uso (autorização vigente em alguma máquina; execução ou monitoramento nos últimos 30
  dias; trabalho em andamento);
- as automações com licença vigente (seguem normais);
- as licenças disponíveis sem automação;
- o trabalho em andamento, que **não é interrompido** ao ligar (já foi admitido).

O ato de ligar exige a confirmação do número de automações que ficariam impedidas ("ligar mesmo com N automações sem
licença") e grava no registro o retrato da prévia. Se o conjunto mudou entre a prévia e o ato, o ato é recusado e a
prévia tem de ser refeita. Desligar volta ao modelo anterior, também com registro.

Assim a migração é sempre uma decisão informada por organização, e nunca uma regra comercial embutida na implantação.

---

## 5. Concessão pela aplicação (D5)

### 5.1 Quem é o operador

- A organização interna da Nekkora (criada no procedimento inicial de cada base) é indicada na configuração do serviço.
- **Operador de licenças** é um membro ativo dessa organização que consta de um cadastro próprio de operadores, com
  quem o incluiu e quando. Ser administrador da organização interna não basta.
- **Autenticação forte:** sessão com segundo fator e confirmação reforçada **por código do autenticador** nos últimos
  minutos, a cada ato (senha não serve para estes atos).
- **Auditoria:** todo ato grava operador, organização alvo, o que mudou, justificativa obrigatória e instante, em
  registro só de acréscimo. O administrador da organização alvo vê os atos que a afetaram.

### 5.2 Por que a instalação precisa de uma migration privilegiada, e por que o uso diário não

O serviço da Platform só enxerga e grava linhas da organização da sessão (isolamento por organização no banco). O
operador está na organização da Nekkora e precisa gravar licenças de **outra** organização. Há duas formas de permitir
isso:

- abrir o isolamento das tabelas de licença para o serviço inteiro — descartado: um defeito em qualquer rota passaria a
  alcançar licenças de todas as organizações;
- funções específicas no banco (conceder, renovar, encerrar, transferir, ligar e desligar a exigência, prévia) que
  **reconferem dentro do banco** que quem chama é operador cadastrado e ativo, e só então gravam na organização alvo.

A segunda é a escolhida. Essas funções têm dono privilegiado, e criar função com esse dono exige a janela de elevação
do migrador **uma vez, na instalação** (como a 022 e a 039). Depois de instaladas, cada concessão é uma chamada comum
da aplicação: **nenhuma elevação por operação**.

Procedimento da instalação (para quando for autorizado): aplicar a migration pelo `db/bootstrap/aplicar_como_migrador.sh`
com a janela registrada em `ops/homologacao/implantacao/elevacao.txt`; a suíte de banco confere dono, privilégios e que
nenhum outro papel executa as funções. **Nada disso foi nem será executado em homologação ou produção sem autorização
específica.**

### 5.3 Divisão em duas migrations

- **061, sem elevação:** tabelas de licença, períodos, vínculos, atos e modelo da organização; isolamento; índices que
  impedem consumo duplicado. É o que as leituras do administrador usam. Entra nesta etapa.
- **062, com elevação:** cadastro de operadores e as funções dos atos. Entra na etapa seguinte, com a suíte própria.

---

## 6. Primeira organização e o perfil Cliente (D9)

A decisão é tomada **no servidor**, na criação e na solicitação, olhando as contas ativas da identidade:

| Situação da pessoa | Como é reconhecida | Regra |
|---|---|---|
| Recém-cadastrada | identidade sem nenhuma conta em nenhuma organização | cria a primeira organização direto e vira administradora |
| Administradora | tem conta interna ativa com papel de administrador em alguma organização | cria direto (sujeita ao limite de vazias) |
| Membro convidado | tem conta interna ativa, em nenhuma é administradora | não cria; solicita ao administrador de uma organização em que é membro |
| Cliente (perfil restrito) | tem conta, e **todas** as contas ativas são do tipo Cliente | não cria e não solicita |
| Cliente em uma, interna em outra | cai em "Administradora" ou "Membro convidado" pela conta interna (D11) | conforme a conta interna |

Como um Cliente **não** contorna:

- o perfil vem das contas gravadas no servidor, não de algo que a pessoa informe; a interface só reflete;
- a rota de criação e a de solicitação recusam mesmo por chamada direta;
- aceitar um convite de Cliente transforma a pessoa "recém-cadastrada" em Cliente a partir daquele momento: a regra é
  reavaliada a cada tentativa, não uma vez no cadastro.

O que a Platform **não consegue** impedir, e convém saber: a mesma pessoa física criar **outra identidade com outro
e-mail**. Para o sistema é outra pessoa, recém-cadastrada. O que limita o efeito: a organização criada assim não dá
acesso nenhum à organização em que ela é Cliente, não recebe licença, nasce sem capacidade de executar e conta no
limite de três vazias. Se isso não for aceitável, a alternativa é exigir aprovação da Nekkora para toda primeira
organização — o contrário da D9.

Um caso que peço confirmar: a pessoa que tem **convite pendente** e ainda nenhuma conta é tratada como recém-cadastrada
(pode criar a primeira organização). Proponho manter assim.

---

## 7. Diagnóstico em homologação e produção (D14)

### 7.1 Procedimento (somente leitura)

1. Autorização específica registrada, com ambiente, data e responsável.
2. Executar `db/diagnosticos/licenciamento_001_inventario.sql` com o papel de leitura do migrador, numa réplica quando
   houver. O script abre `BEGIN TRANSACTION READ ONLY` e termina em `ROLLBACK`: qualquer tentativa de escrita falha.
3. Saída gravada em arquivo no host de operação; conferência de que a saída **não** contém e-mail, nome de pessoa,
   token, hash ou chave (o script não seleciona nenhuma dessas colunas; a conferência é uma busca por `@` e pelos nomes
   das colunas sensíveis).
4. Entrega do relatório no formato abaixo. A saída bruta fica no ambiente.

### 7.2 O que as consultas trazem

Só identificadores de organização e de automação, nomes de organização e de automação, códigos de situação e
contagens. A consulta 4 traz o identificador interno da identidade criadora (não é e-mail nem dá acesso a nada); no
relatório ele é trocado por "Responsável 1", "Responsável 2"…

### 7.3 Formato do relatório

```
Ambiente, data e hora, revisão da API e migration aplicada, quem executou, autorização

1. Organizações
   total | ativas | suspensas | desativadas | ativadas | aguardando ativação
2. Organizações com uso de execução ou monitoração (uma linha por organização)
   organização | automações | com autorização vigente | com monitoramento vigente |
   com execução remota | com execução relatada | última execução remota
3. Automações candidatas a licença (uma linha por automação)
   organização | automação | situação | autorização vigente | execuções remotas | execuções relatadas
4. Limites vigentes das organizações com uso
   organização | Workers | simultâneas | pendentes
5. Organizações vazias por responsável
   responsável (apelido) | criadas | vazias | acima de 3?
6. Organizações sem autoria registrada
7. Contas do tipo Cliente por organização (contagem)
8. Trabalho em andamento no momento da leitura
9. Proposta de concessão inicial (preenchida depois, pela titular): organização | licenças | automações | ciclo
```

A seção 9 é a única que decide alguma coisa, e não é preenchida por mim.

---

## 8. Decisões de 19/11/2026, segunda rodada

| Tema | Decisão | Efeito nas pendentes |
|---|---|---|
| Agent anterior | A validade das autorizações usadas sem conexão é **mantida**. Com licença, uma autorização nova não pode valer além do fim da licença. As já emitidas aparecem na prévia como limitação; não se promete bloqueio imediato de máquina desconectada. | resolve a D3 para o Agent anterior e a parte central da D8 |
| Agent Client remoto | A renovação periódica da autorização **sai**. Ficam a revogação e a conferência da licença antes de autorizar o início. Execuções já autorizadas continuam entregando registros e resultados. | resolve a D3 para a execução remota |
| Autorização por Worker | Aprovado reunir a autorização da versão e a solicitação de execução no mesmo fluxo, para quem tem as duas permissões, com aceite explícito e auditado (S2). **Não** aprovada a autorização automática de versões futuras (S3). | S1 e S2 aprovadas; S3 recusada |
| Convite pendente | Quem não tem vínculo aceito cria a primeira organização. O convite pendente não concede acesso nem impõe papel. A organização nova é independente, sem licença automática, sujeita ao limite de vazias. | fecha o caso em aberto da seção 6 |

O que continua pendente: D2, D6, D7, D10, D11, D12, e da D8 só o ponto do registro (ver abaixo).

Consequências para a implementação (etapa da conferência nos fluxos, ainda não iniciada):

- **Teto da autorização nova pelo fim da licença.** Em organização que exige licença, criar uma autorização de execução
  local para uma automação licenciada limita `valid_until` ao fim do último período da licença; sem licença vigente, a
  autorização nova é recusada. Autorizações antigas não são alteradas.
- **Execução remota sem vencimento.** Em organização que exige licença, a autorização da versão para o Worker deixa de
  vencer para efeito da execução remota; continua revogável, e cada solicitação confere a licença.
- **D8, o que resta decidir:** com a licença vencida, a Platform recusa também o **registro** de execuções novas do
  Agent anterior, ou aceita o registro e apenas deixa de entregar autorizações novas? A orientação recebida fecha a
  entrega de autorizações; o registro ficou sem resposta explícita.

---

## 9. Decisões de 19/11/2026, terceira rodada

Com esta rodada, as oito pendentes da seção 2 estão decididas. Onde este texto diverge das seções anteriores, vale este.

| # | Decisão | Onde entra |
|---|---|---|
| D2 | Sai a ativação comercial das organizações que usam licenciamento. Limites **técnicos**, configuráveis, iniciais de 50 Workers, 5 execuções simultâneas e 50 pendentes por organização, apresentados separados das licenças. Atingir um limite de capacidade não consome outra licença. | etapa de capacidade |
| D3 | Prazo mantido no Agent anterior; renovação periódica retirada só na execução remota do Agent Client. A validade da autorização offline **nunca atravessa uma lacuna entre períodos de licença**; não basta o fim do último período cadastrado. | T05 (064) |
| D6 | Transferência pelo atendimento externo. O operador registra o administrador solicitante, a **referência do chamado**, a justificativa e a **verificação da identidade**. Informar só o identificador de um administrador não comprova o pedido. Sem central de chamados nesta missão. | etapa da transferência |
| D7 | Organizações novas nascem exigindo licença a partir da **ativação controlada** da nova versão, depois de fluxos e interface validados. Sem data fixa. As existentes seguem com migração explícita e auditada. | etapa de organizações novas |
| D8 | Aceitar registros e entregas de execuções cobertas por autorização válida para aquele início, inclusive quando os dados chegam depois do vencimento. Organização, automação, versão, Worker e autorização conferidos pelos registros do servidor. Autorizações anteriores à migração têm tratamento de transição registrado, limitado à validade já emitida, sem renovação automática. Documentar o limite da comprovação do horário de início sem conexão. | T05 (064) |
| D10 | Sem papel novo. Qualquer membro interno ativo pode solicitar uma organização, inclusive leitor e operador; a aprovação é do administrador. Cliente fica excluído. | etapa de criação de organizações |
| D11 | Vale o contexto da organização em que a pessoa atua. Ser Cliente em A não elimina a administração legítima de B; o perfil de B não permite criar ou solicitar em nome de A. Quem só tem vínculos ativos como Cliente não usa a criação independente para contornar a restrição. | etapa de criação de organizações |
| D12 | Quem autoriza escolhe entre si e o solicitante; o administrador aprovador é o padrão. A tela explica que a escolha concede a administração da nova organização e define de quem conta o limite de vazias. O limite é revalidado na aprovação, a escolha é registrada e ninguém mais ganha acesso. | etapa de criação de organizações |
| S1, S2 | Aprovadas. | T05 (064) |
| S3 | Fora. | — |

### Correção à seção 8

A seção 8 dizia que a autorização nova teria a validade limitada "ao fim do último período da licença". Isso está
**errado** quando há intervalo entre períodos: a autorização atravessaria a lacuna. A regra é o fim da **cobertura sem
lacuna**: o período que cobre agora, estendido só pelos períodos seguintes que começam exatamente onde o anterior termina.
É o que a 064 implementa e o que o teste confere (T05).

### Ajuste da prévia

Ao ligar a exigência, o servidor valida que o **conjunto** continua igual ao apresentado: a identidade de cada automação
que ficaria impedida e, de cada coberta, a licença que a cobre. Mesma quantidade com outro conjunto exige nova prévia.
A conferência e a ativação acontecem sob trava exclusiva do modelo da organização; vínculo, concessão, renovação,
encerramento e todo início de trabalho pegam a mesma trava em modo compartilhado. Detalhe em T04 e T05.

### D8, por extenso

- **Aceito:** o registro cuja autorização (a do servidor) valia no início informado, com licença vigente naquele início.
  Se os dados chegam depois do vencimento, o registro é aceito do mesmo jeito e marcado.
- **Transição:** autorização emitida antes de a organização passar ao modelo por licença vale até a validade já emitida.
  Cada registro feito com ela fica marcado como transição. Não é renovada automaticamente.
- **Recusado:** início sem licença e sem transição, ou autorização que não valia naquele início.
- **Do servidor:** organização, automação, versão, Worker e autorização. **Da máquina:** só o instante do início.
- **Limite:** sem conexão, o instante do início é o relógio da máquina. O servidor o restringe ao intervalo entre a emissão
  da autorização e agora, mas não o comprova.
