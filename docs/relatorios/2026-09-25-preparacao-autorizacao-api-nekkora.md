# Mini relatório — preparação da autenticação e autorização da API Nekkora

**Data:** 2026-09-25  
**Status:** regras (Seções 2–7) e fluxo de dois contextos **aprovados** em 2026-09-25; decisões da Seção 8 registradas no §11 (parte fechada, parte pendente). O desenho da 8.2 foi aprovado em 2026-09-25; em 2026-09-26 a 8.2 foi **fechada para a camada de banco** (`2026-09-26-validacao-013-portal-externo.md`), sem aprovação da migration para merge/produção. Nenhuma API foi implementada nesta etapa.  
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

O desenho de contexto de busca e autorização já está aprovado (§11.1). A parte do portal externo (item 5) segue o desenho aprovado da 8.2 (`2026-09-25-desenho-portal-externo-8-2.md`) e não começa antes de a suíte desse desenho passar, com o teste negativo de troca de contexto. **Situação 2026-09-26:** a suíte passou (`2026-09-26-validacao-013-portal-externo.md`); a implementação Spring do portal começa depois da revisão da migration e da suíte.

## 10. Ordem de trabalho

1. ~~Aprovar as regras das Seções 2–7 e o fluxo de dois contextos da Seção 3~~: **concluído em 2026-09-25** (§11.1).
2. Fechar as decisões ainda pendentes da Seção 8 (§11.2): limites e MFA (8.6); rotas, códigos, tamanho de página e cookie (8.7); parâmetros do Argon2id (8.10). Os testes do desenho da 8.2 foram executados e passaram (2026-09-26); falta revisar a migration e a suíte. Já decididos: seletor de organização (8.1), sessão inclusive portal (8.3), catálogo externo v1 (8.9, §11.5) e portal (8.2, fechada para a camada de banco em 2026-09-26).
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
| 8.2 Papel do portal externo | **Fechada para a camada de banco (2026-09-26)**; migration não aprovada para merge/produção; portal incompleto | Identidade **e processo** próprios para o portal. O processo do portal **não** tem as credenciais do `nekkora_platform`. Desenho em `2026-09-25-desenho-portal-externo-8-2.md` (D1–D7 aprovadas em 2026-09-25). Resumo: o papel `nekkora_portal` recebe só `USAGE` em `public` e `EXECUTE` em quatro funções `SECURITY DEFINER` (owner `nekkora_portal_owner`, `NOLOGIN`, com `SELECT` por coluna) — as únicas funções privilegiadas acessíveis a ele; as `SECURITY INVOKER` antigas seguem executáveis por `PUBLIC`, sem acesso a tabela —, sem privilégio de tabela ou view; a função recebe o **token bruto** da sessão, calcula o hash no banco e deriva conta, vínculo e concessões, sem aceitar IDs de escopo do chamador; o comando de leitura reconfere conta, vínculo, organização, cliente, concessão e workspace no mesmo snapshot; nenhum GUC funciona como barreira, porque um contexto em parâmetro customizado pode ser redefinido pelo próprio papel. **Registro de acesso:** `last_access_at` **não** é atualizado na v1 (opção A). **Critério para fechar:** a suíte do §7 do desenho, com o **teste negativo de troca de contexto**, passando com os papéis reais. A migration definitiva espera esse resultado. **Fechamento (2026-09-26):** critério atendido (`2026-09-26-validacao-013-portal-externo.md`), com a cobertura documentada, não exaustiva. Pendentes: revisão direta da migration e da suíte; código Spring do portal; integração da Platform (proteção dos logs da aplicação, formato do hash gravado no login, reset explícito da conexão no pool); decisões 8.6, 8.7 e 8.10 antes das rotas de autenticação; atualização de uma base populada; limite definitivo de página |
| 8.3 Sessão | **Fechada** (área interna e portal) | Formato: token opaco aleatório de **32 bytes em base64url** (43 caracteres), guardado como **SHA-256 em hex minúsculo** em `user_sessions`, com o mesmo contrato na Platform e no portal (D2 do desenho da 8.2). Enviado em cookie `Secure`, `HttpOnly`, `SameSite=Lax`, com proteção CSRF nas operações que alteram estado; domínio e atributos finais do cookie ficam na 8.7. Política (detalhada em **§11.4**): prazo absoluto de **12 h**; inatividade de **30 min** (interna) e **15 min** (portal, contas externas); até **10 sessões** ativas por conta; logout, "encerrar todas" e revogação na troca de senha, na recuperação e na suspensão. Login e logout externos ficam nos endpoints da Platform (D4) |
| 8.4 Revogação contínua | **Fechada** | Revalidar sessão, conta, vínculo e concessão a cada requisição. Token autocontido sem consulta ao estado atual não atende |
| 8.5 `last_seen_at` | **Fechada** | No máximo **1 escrita por minuto** por sessão, por `UPDATE` condicional e atômico. **Tolerância registrada:** a sessão pode ser encerrada por inatividade **até 1 minuto antes** do limite exato de 30 min. Detalhe em **§11.4** |
| 8.6 Proteção contra abuso | **Base fechada; limites pendentes** | Rate limiting e limite de tentativas **obrigatórios**. **Sem bloqueio permanente acionável só por tentativas anônimas**, para evitar negação de serviço contra contas. **Pendente:** limites exatos e MFA |
| 8.7 Contrato HTTP | **Base fechada; rotas pendentes** | Cookie seguro; CSRF para escrita; CORS com origens permitidas explicitamente, **sem wildcard com credenciais**. **Pendente:** rotas e códigos exatos |
| 8.8 Auditoria de negativas | **Fechada** | Log estruturado e sanitizado da aplicação (C10), **sem tabela nova** por enquanto. Registra decisão, ação e correlação; nunca senha, token, payload ou valores de secrets |
| 8.9 Conteúdo externo | **Fechada (catálogo v1)** | Catálogo mínimo em **§11.5**: só nome do workspace, nome e id da automação, execuções com horários, duração e rótulos de status/motivo, e eventos por tipo e `received_at`. Sem `exit_code`, mensagens livres, IDs de pessoas, Worker/Agent, comandos, parâmetros, payloads ou logs brutos. Escopo efetivo: **vínculo externo × workspace da concessão**. É entrada do desenho da 8.2. **Pendente (8.7):** tamanho máximo da página |
| 8.10 Hash de senha | **Direção fechada; detalhes pendentes** | Argon2id como direção aprovada: `docs/relatorios/Radiant-Relatorio-Decisoes-Aprovadas-2026-09-24.md`, §13 ("hash irreversível e salt, direção Argon2id"). **Pendente:** parâmetros, formato versionado do hash e política de re-hash |

### 11.3 O que isso destrava e o que ainda bloqueia

- **Destravado:** revisão detalhada do fluxo de autenticação e sessão (8.1, 8.3 inclusive portal, 8.4, 8.5, 8.7 base, 8.8); desenho do portal externo (8.2, aprovado).
- **Ainda bloqueia partes da implementação:**
  - 8.6 limites: rate limiting;
  - 8.7 rotas: contrato HTTP, tamanho de página, domínio e atributos do cookie;
  - 8.10 parâmetros: hash fictício e login;
  - portal do cliente: revisão da migration 013 e da suíte, código Spring e integração da Platform (os testes da 8.2 passaram em 2026-09-26);
  - atualização de uma base populada para a 013.
- **Migration do portal (8.2):** o desenho está aprovado, mas a migration definitiva só é escrita depois de a suíte do desenho passar, com o teste negativo de troca de contexto, numa base de teste isolada.
  - **2026-09-26:** a candidata `db/migrations/013_portal_externo.sql` passou a suíte (`2026-09-26-validacao-013-portal-externo.md`); a 8.2 está fechada para a camada de banco; a migration **não** está aprovada para merge nem produção até a revisão direta dos arquivos.

### 11.4 Política de sessão e `last_seen_at` (8.3/8.5, aprovada em 2026-09-25)

**Valores:**

| Item | Decisão |
|---|---|
| Prazo absoluto | **12 horas**, gravado em `expires_at` na criação e **nunca renovado** |
| Inatividade (área interna) | **30 minutos**. Só atividade autenticada válida renova esta janela, e nunca além do prazo absoluto |
| Inatividade (portal externo) | **15 minutos** para contas externas (`account_type = 2`), com a mesma tolerância de até 1 min e o mesmo prazo absoluto. Conta como atividade toda requisição com sessão válida, mesmo que o recurso pedido não esteja autorizado. O toque em `last_seen_at` é feito pela função do portal, como owner (desenho da 8.2, D3/D7) |
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

### 11.5 Catálogo de conteúdo externo v1 (8.9, aprovado em 2026-09-25)

**Princípio:** minimização. O portal recebe só o necessário para acompanhar o serviço contratado. Conteúdo sem classificação explícita **não é publicado**. IDs aparecem como referência opaca e **nunca** autorizam nada.

**Escopo efetivo:** **vínculo externo × workspace da concessão** (`client_workspace_grants` liga `client_membership_id` a `workspace_id`), não o cliente sozinho. Duas pessoas do mesmo cliente podem ter workspaces diferentes.

**Filtro de autorização comum a todas as linhas** (revalidado a cada requisição, no servidor): conta externa, sessão e `client_membership` ativos; organização ativa; `clients.status = 1`; workspace pertencente ao cliente **e ativo** (`workspaces.status = 1`, acrescentado pela D5 do desenho da 8.2); concessão do **próprio vínculo** para **aquele workspace** com `status = 2` e `approved_at` preenchido, `starts_at` alcançado, `expires_at` ausente ou futuro e `next_review_at` futuro. Filtrar só por organização ou só por cliente não basta.

| Recurso | Campos retornados | Finalidade | Filtro adicional |
|---|---|---|---|
| Workspace concedido | `workspaces.name` | Identificar o contexto do serviço | Só workspaces com concessão válida do vínculo |
| Automação (referência na execução) | `automations.name`, `automation_id` | Identificar qual automação rodou. **Visíveis deliberadamente:** o nome é o nome interno, pois o schema não tem nome de exibição | Só automações de workspace concedido, e só como atributo das execuções listadas |
| Lista de execuções | `execution_id`; `workspace_id` **só quando necessário** para escolher entre workspaces concedidos; referência da automação (acima); `created_at` (solicitada), `started_at` (início, nulo se não iniciou), `finished_at` (término); duração calculada no servidor; **rótulos** de `status` e `status_reason` | Acompanhar execuções e resultado | Execuções de workspace concedido. Filtros: workspace (dentre os concedidos), status e período. Ordenação `created_at DESC`, desempate por `execution_id`. Paginação **por cursor** (keyset), **sem `OFFSET`**. Tamanho máximo da página: 8.7 |
| Detalhe da execução | Os mesmos campos da lista | Ver uma execução específica | Mesmo filtro. Um id de outra concessão não revela existência |
| Eventos da execução | Rótulo de `event_type` e `received_at` | Linha do tempo do ciclo de vida | Eventos de execução visível. Ordenação por `sequence`. **`received_at`** (servidor), **não** `occurred_at` (relógio do Agent) |

**Rótulos publicados:**
- **`status`:** pendente, iniciando, em execução, concluída, falhou, cancelada.
- **`status_reason`:** processo concluído, falha ao iniciar, falha no processo, cancelada antes do envio, cancelamento confirmado, prazo de fila excedido.
- **`event_type`:** processo iniciado, concluído, falhou, falha ao iniciar, cancelado.

**Fora da resposta, sempre:** `exit_code`; mensagens livres (`executions.error_summary` e `error_summary`/`error_code` de payload); qualquer campo de `payload`; logs (os três canais); `organization_id`, `client_id` e `client_membership_id`; IDs de pessoas (`requested_by_membership_id`, `authorized_by_membership_id`, `cancel_requested_by_membership_id`); `worker_id`, `agent_id` e associações; `pid`, `request_key`, `request_fingerprint`, `fingerprint`, `command_snapshot`, `automation_version_id`, `retry_of_execution_id`, `origin`, `schedule_occurrence_id`; `occurred_at`; `workspaces.description`; agendamentos (cron, fuso, revisões, ocorrências); Workers/Agents e credenciais; referências de secrets.

**Decisões registradas:**
1. Resultado: status mais motivo padronizado, **sem `exit_code`**.
2. **Nenhuma mensagem livre na v1.** Publicar mensagem exigiria campo próprio, com origem controlada e sanitização definida.
3. Eventos: os cinco tipos da 007, com rótulo e `received_at`, sem campo de payload.
4. Logs: **nenhum canal** na v1. Evidência operacional futura seria um relatório sanitizado separado, com catálogo próprio.
5. IDs: `execution_id` e `automation_id`; `workspace_id` só quando o vínculo tem mais de um workspace concedido.
6. Agendamentos e metadados do workspace além do nome: excluídos.
7. **Toda concessão ativa libera o mesmo catálogo v1.** Não há capacidades por concessão, e nenhuma mudança de schema é necessária antes da 8.2.
8. **Redação:** como nenhum payload é publicado na v1, a redação de payload (011) **não altera** o que o portal mostra; `event_type` e `received_at` continuam visíveis. Se algum campo de payload entrar no catálogo no futuro, esta regra é revista.

**Testes que este catálogo habilita (entram no desenho da 8.2):**
- Mesmo cliente, duas pessoas: X com concessão no WS1, Y no WS2. **X não lê o WS2** (nem execução, nem evento, nem nome).
- Clientes diferentes na mesma organização: a sessão do cliente A não lê nenhum registro nem campo exclusivo do cliente B.
- Tentativa direta de trocar o contexto de cliente no banco: continua sem acesso (critério da 8.2).
- Workspace interno; concessão pendente, suspensa, revogada, expirada ou com revisão vencida; cliente ou organização suspensos: nenhuma linha elegível.
- Cada consulta retorna **exatamente** as colunas acima; campos proibidos não aparecem nem em mensagens de erro.
- Evento com payload sensível, desconhecido ou redigido: nada do payload chega ao portal.
- IDs válidos de outra concessão em rota ou query não mudam o escopo nem revelam se o recurso existe.

**Pendências ligadas, fora da 8.9:** tamanho máximo da página (8.7). A identidade do portal e o registro de acesso foram decididos no desenho da 8.2: funções autenticadas por sessão, sem views, e `last_access_at` não atualizado na v1. Os testes desse desenho passaram em 2026-09-26 (`2026-09-26-validacao-013-portal-externo.md`).
