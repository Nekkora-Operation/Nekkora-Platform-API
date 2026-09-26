# Mini relatório — preparação da autenticação e autorização da API Nekkora

**Data:** 2026-09-25  
**Status:** regras (Seções 2–7) e fluxo de dois contextos **aprovados** em 2026-09-25; decisões da Seção 8 registradas no §11 (parte fechada, parte pendente, incluindo o desenho da 8.2). Nenhuma API foi implementada nesta etapa.  
**Stack definida:** Java com Spring Boot, conforme a direção aprovada para a Platform API (`Radiant-Relatorio-Decisoes-Aprovadas-2026-09-24.md`, §2).  
**Base:** migrations 001–012 e decisões aprovadas sobre identidade, RBAC, concessões externas e isolamento por organização.

## 1. Objetivo e limite

Definir como a API identifica a conta, estabelece o contexto da organização e decide o acesso a cada recurso e operação. A regra central é **negar por padrão**: autenticar uma conta ou conhecer um UUID não autoriza acesso.

A migration 012 aplica RLS e privilégios mínimos como camada de isolamento por organização. Ela não implementa autenticação HTTP nem substitui a autorização por usuário, papel ou concessão. Essas verificações pertencem à API.

A implementação prevista é em **Java e Spring Boot**. Esta missão cobre o contrato de autenticação/autorização e sua integração transacional com PostgreSQL. Não propõe trocar a stack, criar uma API Node ou antecipar uma migration.

## 2. Regras aprovadas que a API deve preservar

- Uma conta interna pertence a uma organização. A mesma pessoa pode ter contas separadas em organizações diferentes; não há compartilhamento automático de identidade ou acesso.
- Contas externas são distintas de membros internos e ficam vinculadas a um cliente específico. Uma conta externa não recebe papéis internos.
- Não coletar CPF para deduplicar pessoas. E-mail verificado e domínio autorizado não substituem aprovação do vínculo.
- O contexto da organização precisa ser inequívoco **antes de consultar credenciais**. Domínio do e-mail não seleciona a organização sozinho, inclusive porque pode ser usado por organizações diferentes.
- O e-mail é comparado por lower(email), com unicidade por organização; pontos e sufixos são preservados. Verificação de e-mail é independente do status da conta.
- Conta, sessão e vínculo ativos são condições necessárias, mas não concedem acesso por si sós.
- A API verifica autorização em cada requisição. IDs em rota, corpo ou resposta não funcionam como segredo nem como prova de autorização.
- Acesso interno usa os papéis e permissões de 003. Acesso externo usa concessões próprias, não o catálogo de papéis internos.
- Revogação, suspensão, expiração e revisão vencida precisam bloquear novas consultas, mesmo que a conta tenha uma sessão previamente autenticada.
- O front-end só recebe campos explicitamente permitidos para aquela conta e operação.

## 3. Fluxo de autenticação e os dois usos do contexto

A RLS da 012 filtra tabelas tenant pelo contexto de organização; sem contexto, o papel nekkora_platform não encontra linhas. Por isso, algumas leituras necessárias à autenticação também precisam de contexto. O fluxo distingue **contexto de busca** de **identidade autorizada**, sem abrir uma consulta global.

### 3.1 Login

1. A requisição informa um seletor explícito de organização. Ele é uma alegação de escopo para a busca, não uma credencial nem autorização. Na v1, o seletor é o `organization_id` na rota (decisão 8.1, §11.2).
2. A aplicação Spring Boot inicia uma **transação de busca, somente leitura**, e define `nekkora.organization_id` a partir desse seletor (mecanismo da Seção 6).
3. Sob esse escopo, busca a conta e a credencial pelo par organização + e-mail normalizado. Não faz lookup global por e-mail.
4. Valida o hash da senha, status ACTIVE e e-mail verificado. Depois da validação, usa a organização lida da conta como identidade autorizada e confere que corresponde ao seletor usado na busca.
5. Só após autenticação bem-sucedida cria a sessão, em uma **nova transação de leitura e escrita** que redefine o contexto a partir da organização da conta autenticada. A transação de busca não escreve nada: no Spring, `@Transactional(readOnly = true)` vira `SET TRANSACTION READ ONLY` no PostgreSQL, e um `INSERT` nela falharia. Não ocorre mutação antes da credencial ser validada.

Uma alegação de organização incorreta apenas escopa a leitura para outra organização; a RLS não retorna a conta de um tenant diferente. Ainda assim, a resposta não pode revelar se determinada conta existe.

### 3.2 Requisições autenticadas

1. O seletor de organização acompanha a sessão ou a rota de forma inequívoca.
2. A aplicação inicia transação, estabelece o contexto de busca e localiza user_sessions pelo hash do token dentro daquele tenant.
3. Confere que a sessão encontrada pertence à organização indicada, e revalida a sessão, a conta, o vínculo e a autorização atual. A identidade efetiva vem dos registros encontrados, não do seletor enviado pelo cliente.
4. Após essa validação, executa a autorização da ação e do recurso. Nenhuma leitura ou escrita de negócio ocorre antes dela.
5. Atualiza `last_seen_at` no máximo uma vez por minuto, com o instante capturado por `clock_timestamp()` depois do lock da sessão (decisão 8.5, §11.4).
6. Encerra a transação. Depois do `COMMIT` ou `ROLLBACK`, fora da transação, a conexão é limpa com `DISCARD ALL` antes de voltar ao pool (C5 do relatório operacional). Isso **não acontece sozinho**: o HikariCP, pool padrão do Spring Boot, não tem gancho para executar SQL na devolução da conexão. É preciso um mecanismo explícito, como um wrapper de `DataSource`/`Connection` que execute `DISCARD ALL` na devolução ou um pooler externo com consulta de reset. Como o `DISCARD ALL` também desaloca os *prepared statements* do servidor, que o pgJDBC passa a usar depois do `prepareThreshold`, a convivência dos dois precisa ser provada em teste de integração (item 9.7).

### 3.3 Verificação de e-mail, convite e redefinição de senha

Os tokens dessas operações também são consultados em tabelas tenant sob RLS. O fluxo precisa chegar à organização antes da consulta ao hash do token. O seletor acompanha a rota/link ou o contexto equivalente; depois de localizar e validar o token, a API confirma a organização registrada e só então consome o token ou realiza a alteração permitida.

Não se presume lookup global. Uma função SECURITY DEFINER para resolver token/hash entre tenants exigiria mudança de segurança e migration própria; fica fora desta proposta, salvo se o formato de entrada não puder carregar o contexto.

### 3.4 Respostas sem enumeração de contas

Conta inexistente, senha incorreta, vínculo ausente e sessão inválida não devem gerar respostas que revelem qual condição ocorreu. Para conta inexistente, a aplicação também calcula a verificação de senha contra um hash fictício com o **mesmo algoritmo e parâmetros** dos hashes reais (Argon2id é a direção aprovada, §11; o schema não fixa algoritmo, porque `user_credentials.password_hash` só exige texto não vazio), para evitar que a ausência da conta seja distinguida pelo tempo de resposta. Respostas genéricas, verificação de hash fictício e limites de tentativas são controles complementares; não prometem tempo matematicamente idêntico.

## 4. Regras de autorização interna

A API deve consultar a matriz vigente da 003, sem duplicar permissões em regras informais de rota:

| Papel | Limite de escopo |
|---|---|
| ORG_ADMIN | Administração da própria organização e acesso organizacional definido pela matriz |
| WORKSPACE_ADMIN | Administração dos workspaces atribuídos; delega OPERATOR e VIEWER dentro do próprio workspace, sem atribuir WORKSPACE_ADMIN ou administrar concessões externas |
| OPERATOR | Operações permitidas pela matriz no workspace atribuído |
| VIEWER | Leitura permitida pela matriz no workspace atribuído |

Para cada operação, a API verifica conta e vínculo interno ativos, organização ativa, workspace ativo quando aplicável, papel vigente e permissão específica. Papéis de workspace não se estendem a outros workspaces. ORG_ADMIN não recebe acesso a outra organização.

A matriz de permissões existente permanece a fonte de verdade. A API não deve inferir permissões por nomes de papéis, esconder botões no front-end ou aceitar que um papel delegue mais autoridade do que a regra aprovada.

## 5. Regras de autorização para clientes

A área de cliente é um contexto externo separado. A conta externa só pode consultar os dados explicitamente publicados para o cliente e workspace correspondentes; não pode editar, excluir, executar, cancelar, administrar usuários ou conceder acessos.

Antes de responder, a API valida em conjunto:

- conta externa, sessão e client_membership ativos;
- organização ativa e client.status = 1 (ACTIVE);
- cliente e workspace coerentes, e workspace ainda pertencente àquele cliente;
- concessão aprovada, com status = 2 (ACTIVE) e approved_at preenchido;
- starts_at alcançado;
- expires_at ausente ou ainda futuro;
- revisão não vencida (next_review_at futuro).

Concessão revogada, suspensa, expirada ou com revisão vencida não autoriza acesso. Reativar uma conta não restaura automaticamente concessões revogadas. next_review_at é obrigatório para concessão ativa, inclusive sem data de expiração.

A resposta externa usa uma lista permitida de campos e conteúdo publicado. Logs brutos, comandos, payloads operacionais completos e valores de secrets não são incluídos. O conjunto concreto de telas, consultas e campos publicados ainda precisa ser fechado antes de implementar as rotas do portal.

## 6. Integração com PostgreSQL, RLS e auditoria

- A API conecta como o papel de serviço previsto na 012; não usa superusuário nem owner de função.
- RLS limita linhas ao organization_id usado como contexto. As permissões da API continuam responsáveis por distinguir usuários, papéis, workspaces, clientes e ações.
- Na fase de busca, o contexto limita uma leitura ao tenant alegado. Depois de validar sessão/credencial/token, a autorização usa a organização da identidade encontrada e confere a igualdade com o seletor. Mutação ou acesso a recurso de negócio não ocorre antes disso.
- O contexto é definido somente no escopo da transação, com `SELECT set_config('nekkora.organization_id', ?, true)`: o valor vai como **parâmetro de bind** e o `true` limita o efeito à transação, como `SET LOCAL`. O `SET LOCAL` não aceita parâmetro de bind, e montá-lo por concatenação abriria injeção. A chamada roda **na mesma conexão e na mesma transação** das consultas, logo depois do início, por exemplo num aspecto ou `TransactionSynchronization` ligado ao `@Transactional`, e nunca fora dela. A API nunca usa `SET` de sessão para contexto de usuário.
- Consultas e mutações usam parâmetros SQL; IDs fornecidos pelo cliente são novamente escopados e validados no servidor.
- A auditoria existente cobre alterações e decisões persistidas em algumas áreas (por exemplo, configuração, bootstrap, atribuições e aprovações/revisões de concessões). **Não existe hoje uma trilha no banco para toda negativa de autorização.** Negativas de acesso só podem ser critério de teste depois de escolhido um mecanismo adicional, como log sanitizado da aplicação conforme C10; não registrar senha, token, payload ou query com dados sensíveis.
- O papel nekkora_platform tem acesso amplo dentro da organização. RLS não separa o portal externo dos dados internos; essa separação fica tratada como decisão própria na Seção 8.

## 7. Testes de aceitação previstos

- Mesmo e-mail em organizações diferentes: login exige seletor explícito; domínio não escolhe o tenant.
- Seletor de tenant incorreto não encontra a conta/sessão/token de outro tenant e não causa mutação.
- Conta pendente, suspensa, encerrada, não verificada ou sem vínculo ativo: nenhuma ação protegida é autorizada.
- Conta inexistente e senha incorreta têm resposta indistinguível e executam verificação de senha comparável, com hash fictício no caso inexistente.
- Troca de organization_id, workspace_id, client_id ou IDs de recursos em rota/corpo não atravessa o escopo.
- WORKSPACE_ADMIN não administra outro workspace, não atribui o próprio papel nem cria concessões externas.
- OPERATOR e VIEWER só executam operações permitidas pela matriz da 003.
- Conta cliente não lê outro cliente, workspace interno ou conteúdo não publicado, mesmo na mesma organização.
- Cliente ou organização suspensos, concessão não ativa, approved_at ausente, acesso antes de starts_at, vencimento, revogação ou revisão vencida negam acesso.
- Revogação ou suspensão confirmada nega requisições subsequentes, mesmo com sessão ainda válida.
- Ausência de contexto e contexto malformado falham fechados; contexto não vaza para a próxima conexão reutilizada.
- A limpeza da conexão (`DISCARD ALL` na devolução ao pool) acontece de fato e não quebra consultas seguintes que usam *prepared statements* do servidor (pgJDBC depois do `prepareThreshold`).
- A criação de sessão no login ocorre só na transação de leitura e escrita posterior à validação; a transação de busca não escreve.
- last_seen_at segue a frequência definida; sessão inativa expira segundo a política fechada.
- Respostas não incluem senhas, hashes, tokens, valores de secrets ou campos fora da lista autorizada.
- Negativas de acesso são verificáveis no mecanismo de observabilidade decidido, sem copiar dados sensíveis para logs.

## 8. Decisões técnicas ainda necessárias

> As decisões tomadas em 2026-09-25 sobre os itens abaixo estão registradas no **§11**, que prevalece sobre as opções descritas aqui.

1. **Seletor da organização:** formato público e como ele chega ao backend para login, sessão, convite, verificação e redefinição. Precisa mapear inequivocamente a organization_id antes da busca tenant-scoped. Domínio de e-mail não serve como seletor; slug/domínio legível exige resolução inequívoca sem lookup global de credenciais.
2. **Papel do portal externo:** usar nekkora_platform e aceitar que a separação cliente/interno dependa da API, ou criar uma identidade de banco distinta, somente leitura e limitada a views dos dados publicados. Recomendação: papel separado com privilégios apenas sobre views apropriadas; por ser novo privilégio/RLS, exigiria migration e validação próprias. Um segundo pool dentro do mesmo processo Spring Boot, sozinho, não protege contra comprometimento daquele processo se ele puder usar ambas as credenciais.
3. **Formato e duração da sessão:** cookie seguro ou outro mecanismo; duração, inatividade, renovação, sessões simultâneas e encerramento.
4. **Revogação e validação contínua:** bloqueio em requisições seguintes exige revalidar sessão, conta, vínculo e concessão em cada requisição. Token autocontido de longa duração sem consulta ao estado atual não atende; TTL curto sem revalidação apenas limita o atraso, não dá revogação imediata.
5. **Frequência de last_seen_at:** atualizar em toda requisição ou aplicar uma janela de throttling. A escolha afeta a precisão da expiração por inatividade e a quantidade de escritas.
6. **Proteção contra abuso:** limite de tentativas, rate limiting, política de bloqueio e exigência (ou não) de MFA.
7. **Contrato HTTP e navegador:** rotas, códigos de erro, cookies HttpOnly/Secure/SameSite, CSRF, CORS e respostas sem enumeração de contas.
8. **Auditoria de negativas:** log sanitizado da aplicação conforme C10 ou mecanismo dedicado; definir campos, acesso e retenção sem registrar segredos ou payloads.
9. **Conteúdo externo:** catálogo exato de dados publicados para clientes e campos que cada concessão permite consultar.
10. **Hash de senha:** algoritmo e parâmetros (proposta: Argon2id), política de re-hash quando os parâmetros mudarem, e o hash fictício da Seção 3.4 com os mesmos parâmetros.

## 9. Missão de implementação em Java com Spring Boot

Após aprovar as regras e fechar as decisões da Seção 8, a implementação deve ocorrer em **Java com Spring Boot**, na Platform API. O trabalho deve cobrir:

1. Camada de autenticação para login, sessão, logout e fluxos de token existentes.
2. Filtro/cadeia de segurança Spring que resolve o seletor, valida a sessão e estabelece um principal autenticado. **O principal e as autoridades são reconstruídos a cada requisição a partir do banco** (sessão, conta, vínculo, concessão). Nenhuma autorização é guardada na `HttpSession`, porque o armazenamento padrão do `SecurityContext` reaproveitaria autoridades entre requisições e anularia a revogação da Seção 2.
3. Serviço de autorização que aplica a matriz RBAC e as regras de concessão, sem depender da interface.
4. Limite transacional que define o contexto com `set_config('nekkora.organization_id', ?, true)` (Seção 6) antes das consultas tenant-scoped, impede operações de negócio antes da autenticação e limpa a conexão com `DISCARD ALL` por mecanismo explícito do pool (Seção 3.2, passo 6). As rotas autenticadas usam **Spring MVC bloqueante**, com todo acesso ao banco dentro da transação que estabeleceu o contexto. `@Async`, `CompletableFuture` ou código reativo rodariam fora dela: falhariam fechados (0 linhas) ou operariam sem o contexto certo.
5. DTOs/consultas específicos para o portal externo, aplicando allowlist de campos e a decisão de identidade de banco que for aprovada.
6. Auditoria/observabilidade de decisões negadas conforme a escolha da Seção 8.
7. Testes automatizados e de integração com PostgreSQL real, RLS habilitada e os papéis aprovados — incluindo cross-tenant, revogação, timing de login e pool reuse.

O desenho de contexto de busca e autorização já está aprovado (§11.1). A parte do portal externo (item 5) não começa antes de fechar o desenho da 8.2, com o teste negativo de troca de contexto.

## 10. Ordem de trabalho

1. ~~Aprovar as regras das Seções 2–7 e o fluxo de dois contextos da Seção 3~~: **concluído em 2026-09-25** (§11.1).
2. Fechar as decisões ainda pendentes da Seção 8 (§11.2): desenho da 8.2 com o teste negativo de troca de contexto, que também fecha a inatividade do portal; limites e MFA (8.6); rotas e códigos (8.7); catálogo externo (8.9); parâmetros do Argon2id (8.10). O seletor de organização (8.1) já está decidido.
3. Montar a matriz endpoint/operação × papel ou concessão × escopo de recurso e conteúdo publicado.
4. Implementar autenticação/autorização na Platform API em Java com Spring Boot.
5. Validar a integração com RLS usando papéis reais e provar que revogações são respeitadas nas requisições subsequentes.

Nenhuma migration ou configuração de produção é alterada por este relatório. Os testes pendentes da 011 e os controles operacionais antes de produção permanecem em trilhas separadas; o expurgo segue desativado até o contrato de fingerprint e retenção ser fechado.

## 11. Registro de aprovação e decisões (2026-09-25)

### 11.1 Aprovado

- **Regras das Seções 2–7**, com uma precisão na §3.4: a resposta de login deve ser **indistinguível**, e o tempo de resposta deve ser **mitigado e testado**, sem promessa de tempos matematicamente iguais.
- **Fluxo de dois contextos (§3):**
  - na fase de busca, o seletor de organização **apenas limita a leitura sob RLS**. Não autoriza acesso, e essa fase **não lê dados de negócio nem grava**;
  - depois de validar a credencial ou a sessão, a API confirma a organização registrada e só então autoriza a operação;
  - o login separa a busca, somente leitura, da criação da sessão, feita em nova transação de leitura e escrita que **revalida a conta antes do `INSERT`**.
- **Integração Spring Boot/PostgreSQL:**
  - `set_config('nekkora.organization_id', ?, true)` parametrizado;
  - principal e permissões reconstruídos a cada requisição;
  - Spring MVC bloqueante;
  - `DISCARD ALL` com **implementação explícita** e **teste de compatibilidade** com os *prepared statements* do pgJDBC.

### 11.2 Decisões da Seção 8

| Item | Situação | Decisão |
|---|---|---|
| 8.1 Seletor da organização | **Fechada (v1)** | `organization_id` como identificador público na rota. UUID não é segredo. Slug ou subdomínio fica para depois, porque exigiria resolver o identificador sem busca global sob RLS. Links de convite, verificação e redefinição também carregam esse contexto |
| 8.2 Papel do portal externo | **Direção escolhida (b); desenho pendente** | Identidade **e processo** próprios para o portal, com papel somente de leitura e acesso a *views* do conteúdo publicado. Exige migration e testes próprios. As views restringem **por cliente e concessão ativa**; filtrar só por organização não basta. O processo do portal **não** tem as credenciais do `nekkora_platform`. **Por que continua pendente:** na aplicação, o fluxo é validar a sessão no banco e derivar o contexto do vínculo encontrado, nunca de um ID do navegador. Mas um contexto de cliente guardado num parâmetro customizado **não é barreira independente no banco**: o PostgreSQL aceita parâmetros de dois componentes, e `set_config` permite a qualquer papel defini-los durante a transação. Então o papel do portal **não pode** conseguir ler dados de outro cliente só por definir outro contexto. **Critério para fechar:** o desenho das views precisa incluir um **teste negativo direto**: mesmo tentando trocar o contexto de cliente, a identidade do portal não consegue ler dados de outra concessão |
| 8.3 Sessão | **Fechada para a área interna; duração do portal pendente** | Formato: token opaco aleatório, guardado como hash em `user_sessions`, enviado em cookie `Secure`, `HttpOnly`, `SameSite=Lax`, com proteção CSRF nas operações que alteram estado. Política (detalhada em **§11.4**): prazo absoluto de **12 h**; inatividade de **30 min** (interna); até **10 sessões** ativas por conta; logout, "encerrar todas" e revogação na troca de senha, na recuperação e na suspensão. **Pendente:** inatividade do portal externo (proposta: 15 min), a fechar junto com o desenho da 8.2 |
| 8.4 Revogação contínua | **Fechada** | Revalidar sessão, conta, vínculo e concessão a cada requisição. Token autocontido sem consulta ao estado atual não atende |
| 8.5 `last_seen_at` | **Fechada** | No máximo **1 escrita por minuto** por sessão, por `UPDATE` condicional e atômico. **Tolerância registrada:** a sessão pode ser encerrada por inatividade **até 1 minuto antes** do limite exato de 30 min. Detalhe em **§11.4** |
| 8.6 Proteção contra abuso | **Base fechada; limites pendentes** | Rate limiting e limite de tentativas **obrigatórios**. **Sem bloqueio permanente acionável só por tentativas anônimas**, para evitar negação de serviço contra contas. **Pendente:** limites exatos e MFA |
| 8.7 Contrato HTTP | **Base fechada; rotas pendentes** | Cookie seguro; CSRF para escrita; CORS com origens permitidas explicitamente, **sem wildcard com credenciais**. **Pendente:** rotas e códigos exatos |
| 8.8 Auditoria de negativas | **Fechada** | Log estruturado e sanitizado da aplicação (C10), **sem tabela nova** por enquanto. Registra decisão, ação e correlação; nunca senha, token, payload ou valores de secrets |
| 8.9 Conteúdo externo | **Pendente** | Aguarda o catálogo de dados publicados para clientes (também pré-requisito das views da 8.2) |
| 8.10 Hash de senha | **Direção fechada; detalhes pendentes** | Argon2id como direção aprovada: `docs/relatorios/Radiant-Relatorio-Decisoes-Aprovadas-2026-09-24.md`, §13 ("hash irreversível e salt, direção Argon2id"). **Pendente:** parâmetros, formato versionado do hash e política de re-hash |

### 11.3 O que isso destrava e o que ainda bloqueia

- **Destravado:** revisão detalhada do fluxo de autenticação e sessão (8.1, 8.3 para a área interna, 8.4, 8.5, 8.7 base, 8.8).
- **Ainda bloqueia partes da implementação:**
  - 8.3 inatividade do portal externo: fecha junto com a 8.2;
  - 8.6 limites: rate limiting;
  - 8.7 rotas: contrato HTTP;
  - 8.10 parâmetros: hash fictício e login;
  - 8.9 e o pré-requisito da 8.2: portal do cliente.
- **Migration do portal (8.2):** só deve ser desenhada depois de definir como as views identificam o cliente autorizado sem confiar em IDs do navegador **e** sem depender de um parâmetro que o próprio papel do portal possa redefinir. O desenho só é aceito com o teste negativo de troca de contexto.

### 11.4 Política de sessão e `last_seen_at` (8.3/8.5, aprovada em 2026-09-25)

**Valores:**

| Item | Decisão |
|---|---|
| Prazo absoluto | **12 horas**, gravado em `expires_at` na criação e **nunca renovado** |
| Inatividade (área interna) | **30 minutos**. Só atividade autenticada válida renova esta janela, e nunca além do prazo absoluto |
| Inatividade (portal externo) | **Pendente**, com proposta de 15 min, a fechar com o desenho da 8.2 |
| Sessões simultâneas | Até **10 ativas por conta**. Ao exceder, revoga-se a **sessão ativa mais antiga**; a entrada nova não é rejeitada. Escopo por conta, porque o schema não tem coluna de dispositivo |
| `last_seen_at` | No máximo **uma escrita por minuto** por sessão. Pode encerrar a sessão por inatividade **até 1 minuto antes** do limite exato |

**Sessão ativa** é a que satisfaz, ao mesmo tempo: `revoked_at IS NULL`, `expires_at` posterior ao instante de referência, e `last_seen_at` dentro da janela de inatividade em relação a esse instante.

**Relógio:** as verificações **não** usam `now()`. No PostgreSQL, `now()` é o início da transação, e uma requisição que esperou por um lock validaria a sessão com um horário antigo. **A validação e o toque em `last_seen_at` usam um único instante, capturado com `clock_timestamp()` depois de obtido o lock da linha da sessão.** Todos os tempos são durações avaliadas com o relógio do banco, nunca o do navegador nem o da JVM. **Custo aceito do desenho:** requisições simultâneas da mesma sessão se serializam brevemente nesse lock. **O desempenho dessa serialização deve ser medido nos testes de integração.**

**Atualização de `last_seen_at`:** um `UPDATE` condicional na linha já travada. Ele só avança `last_seen_at` para o instante capturado quando a sessão está ativa nesse instante **e** a última escrita tem mais de 1 minuto. Não altera `expires_at`, nunca faz `last_seen_at` retroceder e não "revive" uma sessão já inativa, expirada ou revogada. Duas requisições da mesma sessão ficam serializadas pelo lock; a segunda vê o valor já atualizado e não escreve de novo.

**Limite de 10 sessões:** o login obtém primeiro um **lock da conta**, que serializa logins concorrentes da mesma conta. Só depois, **em outro comando** sob `READ COMMITTED`, conta as sessões ativas: não revogadas, não expiradas e dentro da janela de inatividade, pelo instante capturado depois do lock. Se a nova sessão excederia o limite, revoga-se a ativa mais antiga na mesma transação.

**Revogação:**

| Evento | Efeito |
|---|---|
| Logout | Revoga a sessão atual |
| "Encerrar todas as sessões" | Revoga todas as sessões ativas da conta |
| Troca de senha autenticada | Revoga **todas as outras** sessões; a atual pode continuar |
| Redefinição por recuperação | Revoga **todas** as sessões |
| Suspensão da conta ou do vínculo | Revoga as sessões da conta. A revalidação (8.4) já bloqueia o acesso durante a suspensão, mas sem revogação uma sessão antiga poderia voltar a funcionar depois da reativação |

A revogação grava `revoked_at` só onde ele ainda é nulo (idempotente). Nenhum caminho da API volta `revoked_at` para nulo. Reativar uma conta ou um vínculo não restaura sessões revogadas.

**Garantia da API e endurecimento futuro:** hoje o banco **não impede** alterações indevidas em `user_sessions`. Não há trigger: o `nekkora_platform` tem `UPDATE` em todas as colunas e poderia aumentar `expires_at`, limpar `revoked_at` ou fazer `last_seen_at` retroceder. Essas regras são **garantias da API**. Uma trigger que as imponha no banco fica registrada como **endurecimento futuro**, que exigiria migration própria e **não bloqueia** esta etapa.

**Pendência relacionada, fora desta decisão:** retenção e limpeza das sessões expiradas ou revogadas. O `nekkora_platform` não tem `DELETE` em `user_sessions`; ver o C11 do relatório operacional.
