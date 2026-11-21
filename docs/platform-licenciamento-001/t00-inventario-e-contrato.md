# PLATFORM-LICENCIAMENTO-001 — T00: inventário, contrato proposto, dados existentes e etapas

Primeira entrega da missão. **Nenhum comportamento muda com este documento.** Ele parte da `dev` da API em
`86591bb2835e315c8968e55dd3a5c35330762086` (depois da PLATFORM-INICIO-001) e termina com as decisões que faltam para
começar a implementar. Onde digo "proposta", é proposta: só vira regra depois da decisão da titular.

Sumário: 1. o que existe hoje · 2. classificação dos controles · 3. contrato proposto de licenças · 4. criação de
organizações e limite de vazias · 5. dados existentes e migração · 6. etapas e PRs · 7. decisões necessárias.

---

## 1. O que existe hoje

### 1.1 Caminhos de execução e de monitoração

| # | Fluxo | Onde fica registrado | Quem inicia |
|---|---|---|---|
| F1 | Execução da Platform (manual; o agendamento tem modelo no banco, sem rota nem agendador) | `executions` (006) | pessoa, pela Platform |
| F2 | Execução local com pacote pelo Agent anterior, **relatada** à Platform (eventos e registros) | `local_run_authorizations` origem 1 e 2, `agent_runs`, `agent_run_events` (025, 030) | a máquina, com autorização vigente |
| F3 | **Monitoramento** de processo externo pelo Agent anterior (sem pacote) | `local_run_authorizations` origem 3, `agent_runs` (030) | a máquina, com autorização vigente |
| F4 | Pedido da Platform ao Agent anterior | `run_requests` (030) | pessoa, pela Platform |
| F5 | Execução remota pelo Agent Client: solicitação, tentativas, registros e resultados | `remote_executions`, `remote_attempts`, `remote_attempt_log_lines`, `remote_attempt_results` (046–060) | pessoa, pela Platform |
| F6 | Execução local privada do Agent Client | **não chega à Platform** | pessoa, na máquina |

F6 fica fora de qualquer regra desta missão: a Platform não a vê hoje e continua sem ver.

### 1.2 Controles que hoje condicionam execução e monitoração

| Controle | Onde | O que exige |
|---|---|---|
| Situação da organização (ativa, suspensa, desativada) | `organizations.status` (001) | organização ativa |
| **Ativação da organização** (aguardando ativação → ativada) | `ops_activation.organization_states` (022) | ato de operador da Nekkora, com certificado, motivo e limites explícitos |
| **Limites de capacidade** 1 Workers registrados, 2 execuções simultâneas, 3 execuções pendentes | `organization_resource_limit_revisions` (010, 022) | sem ativação valem 0; só o ato do operador muda |
| Limites 4 e 5 (bytes diários de registros e eventos) e retenção | 010, 057 | proteção de volume e prazo de guarda |
| Papéis e permissões (`automation.execute`, `logs.read`, `worker.manage`, `agent.manage`…) | 003, 012 | papel no workspace ou na organização |
| Isolamento por organização (RLS) e leitura por workspace | 012, `WorkspaceAccess` | sempre |
| Confirmação reforçada (step-up) nas ações sensíveis | 014, 029 | senha ou código recente |
| Worker autorizado no workspace | `worker_workspace_assignments` (004) | autorização ativa |
| Vínculo da máquina (pareamento com código e segredo gerado na máquina) | `agent_enrollments`, `agent_credentials` (025, 043) | consentimento de quem cria o código e de quem resgata na máquina |
| **Autorização de execução local** por Worker + versão (ou Worker + automação, no monitoramento), com validade de até 366 dias, uma vigente por escopo | `local_run_authorizations` (025, 030, 033) | F2, F3, F4 e também F5 (a execução remota exige a autorização vigente da versão naquela máquina) |
| **Habilitação da máquina para execução remota** (código de pareamento marcado) | `agent_remote_enablements` (042) | F5 |
| Chave geral da execução remota | configuração `NEKKORA_EXECUCAO_REMOTA` | F5 (desligada por padrão) |
| Versão publicada, pacote registrado e íntegro | 028, 048 | F1, F2, F4, F5 |
| Python escolhido e presente no inventário; dependências declaradas e sem conflito | 044, 045, 047, 054 | F5 |
| Máquina apta: compatível, não pausada, não ocupada, sem tentativa anterior pendente | 042, 046, 053, 058, 060 | F5 |
| Teto de 10 organizações criadas pelo cadastro por identidade | `identity_create_organization` (017) | criação de organização |
| Nome único entre as organizações criadas pela mesma identidade | `organization_authorship` (034) | criação de organização |

Dois fatos que importam para a missão:

- **A execução remota (F5) e o canal da máquina (F2, F3) não consultam a ativação nem os limites 2 e 3.** Eles só são
  alcançados de forma indireta: sem ativação o limite de Workers é 0, então nenhum Worker é registrado e nada roda. Os
  limites 2 e 3 só valem para F1 (`executions`).
- **Não existe exclusão** de automação nem de organização. Automação tem ativa, suspensa e arquivada (reversível);
  organização tem ativa, suspensa e desativada. Onde a missão fala em "excluir", hoje o mais próximo é arquivar
  (automação) e não há equivalente para organização.

---

## 2. Classificação dos controles

| Controle | Classe | Proposta |
|---|---|---|
| Ativação da organização (022) | **Comercial** | Substituir pela licença como condição de execução e monitoração. O ato de ativação deixa de ser pré-requisito para registrar Worker (ver D2). |
| Limite 1, Workers registrados | **Comercial** hoje (é a "capacidade") | A licença não conta Worker. Proposta: deixar de limitar por contrato e manter um teto técnico alto e configurável, contra abuso (ver D2). |
| Limites 2 e 3, execuções simultâneas e pendentes | Comercial hoje, com efeito **técnico** | Manter como proteção técnica com valor padrão; deixam de ser negociados por organização (ver D2). |
| Limites 4 e 5 e retenção | **Técnico** | Permanecem. |
| Situação da organização (suspensa, desativada) | **Segurança / administrativo** | Permanece. Licença válida não reativa organização suspensa. |
| Papéis, permissões, RLS, leitura por workspace, step-up | **Segurança / acesso** | Permanecem sem mudança. |
| Worker autorizado no workspace | **Segurança / acesso** | Permanece. |
| Vínculo da máquina e habilitação para execução remota (042) | **Segurança** (consentimento da máquina) | Permanecem. |
| Autorização de execução local por Worker + versão (025, 033) | **Segurança** (qual pacote pode rodar em qual máquina), com uma parte **redundante** | O consentimento por versão e máquina permanece. A **validade com renovação** é a parte candidata a sair: com a licença dando o prazo comercial, renovar a autorização a cada vencimento vira retrabalho (ver D3). |
| Chave geral da execução remota | **Técnico** (liberação gradual) | Permanece. |
| Versão publicada, pacote, Python, dependências, máquina apta | **Técnico** | Permanecem. |
| Teto de 10 organizações por identidade | Técnico (abuso) | Rever junto com o limite de vazias (ver D9). |

Nada é removido nesta etapa. A remoção de cada controle comercial só entra depois da licença estar valendo, na etapa
indicada na seção 6.

---

## 3. Contrato proposto de licenças

### 3.1 Modelo

- **Licença**: pertence a uma organização; tem início e fim de ciclo; atende **uma automação** por vez. Uma linha por
  licença (não um contador), para que cada uma tenha a sua validade, o seu vínculo e a sua história.
- **Vínculo licença → automação**: no máximo um vínculo vigente por licença e no máximo uma licença vigente por
  automação, garantidos por índice único no banco. Ativar acontece sob trava da organização: duas ativações
  simultâneas nunca consomem a mesma licença nem ultrapassam o contratado.
- **A unidade é a automação.** Versões, Workers, máquinas e pessoas não entram na chave: não multiplicam consumo.
- **O vínculo dura o ciclo.** Suspender, renomear ou arquivar a automação não desfaz o vínculo nem devolve a licença.
  Só a transferência pelo suporte (3.4) muda a automação atendida.
- **Atos de licença** (conceder, renovar, transferir, encerrar): registro próprio, só de acréscimo, com responsável,
  justificativa e instante. É a auditoria.
- Referência comercial (R$ 75 por automação por mês) fica **na documentação**, não no banco: a missão não inclui
  cobrança, e preço em tabela viraria regra comercial silenciosa.

### 3.2 Quem concede e renova

Hoje a única autoridade da Nekkora dentro da Platform é o **operador** da ativação (022): pessoa da operação,
autenticada por certificado de curta duração, com conta provada e motivo obrigatório. Proposta: os atos de licença
seguem esse mesmo caminho (`org_license_grant`, `org_license_renew`, `org_license_transfer`, `org_license_end`), no
mesmo grupo ou em um grupo próprio (D5). Não existe "administrador da Nekkora" na Web; criá-lo é outra superfície de
ataque e outra missão.

Consequência: as migrations desses atos **exigem elevação** (funções com dono privilegiado), como a 022 e a 039. Em
laboratório aplico; em homologação e produção só com autorização específica.

### 3.3 Onde o servidor confere

| Fluxo | Ponto de conferência (novo trabalho) | Continua aceito sem licença válida |
|---|---|---|
| F5 execução remota | criar a solicitação; criar nova tentativa (repetir em outra máquina) | receber, iniciar e concluir a tentativa já oferecida; registros, resultados, relato tardio e reconciliação das tentativas criadas na validade |
| F4 pedido ao Agent anterior | criar o pedido | o registro e os eventos da execução nascida de pedido aceito na validade |
| F2 e F3 relatadas pelo Agent anterior | **registrar uma nova execução ou sessão de monitoramento** no canal da máquina | eventos, registros, relatório do supervisor e lacunas das execuções registradas na validade |
| F1 execução da Platform | criar a execução | acompanhamento e eventos |
| Leitura de históricos | — | sempre, pelas regras de acesso e retenção de hoje |
| F6 local privada do Client | — | não passa pela Platform |

Regras:

- A conferência é feita **no servidor**, na mesma transação que admite o trabalho, e responde com um motivo próprio
  (`license_required`, `license_expired`). A interface só reflete.
- "Autorizada durante a validade" = o trabalho foi **admitido** enquanto a licença valia. A entrega posterior não
  reconfere a licença. Os prazos técnicos de hoje continuam sendo o teto: oferta da tentativa (15 min), encerramento
  sem comunicação (120 min), teto de entrega sem progresso (059) e retenção (057). O vencimento da licença não estica
  nem encurta nenhum deles.
- Comunicação interrompida: a tentativa segue o caminho de hoje (sem confirmação → reconciliação). O relato tardio de
  uma tentativa admitida na validade é aceito mesmo depois do vencimento.
- Vencimento não apaga nada e não suspende a organização.

### 3.4 Transferência

Ato do operador, a pedido de administrador da organização. Registra origem, destino, solicitante (o administrador),
responsável (o operador), justificativa e data. Mantém o fim do ciclo. Na mesma transação encerra o vínculo com a
origem e cria o do destino, então não há instante com as duas automações atendidas. É recusada enquanto a automação de
origem tiver trabalho em andamento em qualquer fluxo (F1 a F5): precisa terminar ou ter o cancelamento confirmado.
Como o pedido chega ao suporte (D6) fica fora da Platform nesta missão, salvo decisão contrária.

### 3.5 O que o administrador vê

Uma rota de resumo e uma de lista, só para quem administra a organização:

- resumo: contratadas, utilizadas, disponíveis, automações com licença ativa — **agregado no banco**, sem depender de
  página;
- lista: automação, ambiente de trabalho, situação da licença (ativa, vencida), validade, e à parte se há execução em
  andamento. "Licença ativa" e "em execução" são campos distintos.

---

## 4. Criação de organizações e limite de vazias

### 4.1 Hoje

Qualquer identidade ativa e verificada cria organização direto (com confirmação reforçada), até 10 no total. Quem cria
vira a primeira administradora. A organização nasce "aguardando ativação". Não há vínculo entre organizações, não há
solicitação nem aprovação, e a conta do tipo Cliente (`users.account_type = 2`) é por organização.

Os papéis que existem são ORG_ADMIN, WORKSPACE_ADMIN, OPERATOR e VIEWER. **"Desenvolvedor" não é um papel hoje.**

### 4.2 Proposta

- **Solicitação de criação**: registro com a organização que encaminha, o solicitante, o administrador competente (um
  ORG_ADMIN dessa organização), o nome pedido, o responsável pela nova organização, a decisão, quem decidiu, quando e a
  justificativa. A organização só nasce na aprovação, na mesma transação.
- **Responsável pela nova organização**: declarado na solicitação e confirmado por quem aprova. Proposta: por padrão o
  administrador que aprova; o solicitante só entra na nova organização se quem aprova marcar isso. Nenhum outro acesso
  cruza de uma organização para a outra.
- **Cliente**: a rota de criação e a de solicitação recusam, e a interface não oferece.
- **Operador da Nekkora**: aprova em caráter excepcional, regulariza ou bloqueia, pelo mesmo caminho de atos auditados
  da seção 3.2.
- **Limite de vazias**: três por administrador responsável, configurável. Vazia = sem nenhuma automação (workspaces não
  contam). A contagem e a criação acontecem sob trava do responsável, então criações e aprovações simultâneas não
  passam do limite. Organizações existentes acima do limite continuam como estão; só novas criações são recusadas, com
  a lista das vazias na resposta.
- Criar organização não concede licença nem gera cobrança.

---

## 5. Dados existentes e migração

### 5.1 O que precisa ser medido antes

`db/diagnosticos/licenciamento_001_inventario.sql` (somente leitura, transação `READ ONLY` com `ROLLBACK`) responde:

1. organizações por situação e ativação, com automações e uso de execução e monitoração;
2. automações com uso (autorização vigente, execução remota, execução relatada) — as candidatas a licença;
3. limites vigentes por organização;
4. organizações vazias por identidade criadora;
5. organizações sem autoria registrada;
6. contas do tipo Cliente por organização;
7. trabalho em andamento.

**Rodei só no ambiente local** (banco de ensaio): 4 organizações, 1 ativada, 1 automação com autorização vigente,
nenhuma identidade acima de 3 vazias, nenhuma conta Cliente, nada em andamento. Isso valida o script, **não descreve
produção nem homologação**. Não rodei nesses ambientes: precisa de autorização.

### 5.2 Proposta de migração (nada concedido nem bloqueado por suposição)

1. **As tabelas novas nascem vazias.** Nenhuma licença é criada pela migration.
2. **A exigência de licença é ligada por organização**, por ato do operador, com data. Enquanto não for ligada, a
   organização segue exatamente pelas regras de hoje (ativação e limites). Assim nenhum cliente existente é bloqueado no
   dia da implantação.
3. Para cada organização existente com uso, a titular decide, com o resultado do diagnóstico: quantas licenças
   conceder, para quais automações e com que ciclo. O operador concede, vincula e só então liga a exigência.
4. Organizações novas, criadas depois da implantação, podem nascer já com a exigência ligada (D7).
5. Os controles comerciais antigos (ativação como pré-requisito, limite de Workers por contrato) só são retirados
   depois de todas as organizações com uso estarem regularizadas, em PR próprio.

### 5.3 Reversão

- Etapas de modelo e leitura: aditivas; revertem por `DROP` das tabelas e funções novas enquanto não houver ato
  gravado. Depois do primeiro ato, a auditoria é só de acréscimo e a reversão passa a ser desligar a exigência.
- Exigência: desligar por organização (ato) ou por configuração geral volta às regras de hoje sem tocar em dados.
- Retirada dos controles antigos: é a única etapa que não volta só com configuração; por isso fica por último e em PR
  separado, com a migration inversa descrita.

---

## 6. Etapas e PRs

| Etapa | Repositório | Conteúdo | Depende de | Muda comportamento? |
|---|---|---|---|---|
| E0 | API | este documento e o diagnóstico | — | não |
| E1 | API | modelo de licenças, vínculos e atos; atos do operador (conceder, renovar, vincular, encerrar); testes de banco de isolamento e concorrência | D1, D4, D5 | não (nada confere ainda) |
| E2 | API | rotas de resumo e lista para o administrador; contrato OpenAPI | E1 | não (só leitura nova) |
| E3 | API | conferência no servidor em F1 a F5, ligada por organização; motivos de recusa; vencimento | E1, D2, D3, D7, D8 | **sim**, só onde a exigência for ligada |
| E4 | API | transferência auditada | E3, D6 | sim |
| E5 | Web | tela "Execução e monitoração" do administrador; mensagens de recusa nas telas de execução | E2, E3 | sim |
| E6 | Agent Client | mensagens para as recusas novas (sem mudar a execução local privada) | E3 | sim |
| E7 | API + Web | solicitação e aprovação de organização; bloqueio do Cliente; responsável | D9 a D12 | sim |
| E8 | API + Web | limite de organizações vazias sob concorrência | E7, D13 | sim |
| E9 | API | retirada dos controles comerciais antigos | todas, e regularização dos dados | sim, sem volta por configuração |
| E10 | todos | roteiro integrado, matriz requisito → teste → evidência, plano de migração e reversão final | todas | não |

Ordem de integração: E0 → E1 → E2 → E3 → E4 → (E5, E6) → E7 → E8 → E9 → E10. E7 e E8 não dependem de E1 a E6 e podem
andar em paralelo se as decisões delas fecharem antes.

---

## 7. Decisões necessárias

Sem elas eu estaria escolhendo regra de produto. Cada uma traz a minha recomendação.

**Licenças**

- **D1. Como uma automação passa a consumir licença.** (a) ato explícito do administrador ("usar uma licença nesta
  automação"), com aviso de que vale pelo ciclo; ou (b) automático no primeiro pedido de execução ou monitoração.
  Recomendo (a): o consumo dura o ciclo e não deve acontecer por um clique em "executar".
- **D2. O que acontece com a ativação e com os limites de capacidade.** Recomendo: com a exigência ligada, a ativação
  deixa de ser pré-requisito; Workers passam a ter só um teto técnico; os limites 2 e 3 viram padrão técnico. Preciso
  dos valores (sugestão inicial: 50 Workers, 5 simultâneas, 50 pendentes por organização).
- **D3. Validade da autorização de execução local.** Manter a validade de até 366 dias, ou a autorização passa a valer
  até ser revogada? Recomendo manter o consentimento por versão e máquina e **tirar o vencimento** para organizações com
  a exigência de licença ligada. É mudança de regra de segurança: só com a sua decisão.
- **D4. Ciclo.** Mensal a partir da data da concessão? A renovação mantém o vínculo com a mesma automação
  automaticamente? Recomendo: ciclo com início e fim informados no ato; renovar estende o fim e mantém o vínculo.
- **D5. Quem concede.** Operador da Nekkora por ato com certificado, como na ativação (recomendado), em grupo próprio
  de permissão. Isso implica migration com elevação.
- **D6. Como o pedido de transferência chega ao suporte.** Fora da Platform (e-mail ou canal de suporte, e o operador
  registra o solicitante no ato), ou um botão "solicitar transferência" que só registra o pedido? Recomendo fora da
  Platform nesta missão, para não começar um sistema de chamados.
- **D7. Organizações novas** nascem com a exigência de licença já ligada?
- **D8. Agent anterior com licença vencida.** F2 e F3 registram a execução na Platform. Com a licença vencida, a
  proposta recusa esse registro. Não conferi no código do Agent anterior (que esta missão não altera) se, sem o
  registro, ele deixa de rodar o pacote ou roda sem relatar; isso será verificado antes da E3. Recusar é o esperado, ou
  esse fluxo conta como "execução local independente" e deve continuar, só sem monitoração? Recomendo recusar: é
  execução autorizada e monitorada pela Platform. A execução local privada do Client (F6) não é afetada em nenhum caso.

**Organizações**

- **D9. A primeira organização de quem acabou de se cadastrar.** A regra nova diz que só administrador cria e que os
  demais solicitam a um administrador. Quem ainda não participa de nenhuma organização não tem a quem solicitar. Ela
  cria a primeira direto (como hoje), solicita à Nekkora, ou só entra por convite? E o teto atual de 10 por identidade
  continua?
- **D10. Quem é "desenvolvedor".** Não existe esse papel. Vale como "qualquer membro interno que não é ORG_ADMIN"
  (WORKSPACE_ADMIN, OPERATOR, VIEWER)?
- **D11. Pessoa com perfis diferentes em organizações diferentes.** O tipo Cliente é por organização. Quem é Cliente em
  uma e administrador em outra: vale o perfil da organização a partir da qual age (recomendado), ou ser Cliente em
  qualquer uma bloqueia sempre?
- **D12. Responsável pela nova organização** quando um membro solicita: o administrador que aprova (recomendado), o
  solicitante, ou escolhido na aprovação?
- **D13. "Excluir" organização vazia.** Não existe exclusão de organização. Para orientar a pessoa a "utilizar ou
  excluir", preciso de uma destas: (a) desativar a organização vazia (existe o estado; falta a ação) e a desativada
  deixar de contar no limite — recomendado; (b) exclusão de verdade, que é trabalho maior por causa das auditorias só
  de acréscimo; (c) só orientar a reutilizar.

**Operação**

- **D14. Diagnóstico em homologação e produção.** Autoriza rodar o script somente leitura nesses ambientes? Sem ele não
  há como propor a concessão inicial com números reais.

O que posso começar assim que as respectivas decisões fecharem: E1 e E2 dependem só de D1, D4 e D5; E7 e E8 dependem de
D9 a D13.
