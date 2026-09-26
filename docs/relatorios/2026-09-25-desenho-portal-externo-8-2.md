# Desenho seguro do portal externo (decisão 8.2)

**Data:** 2026-09-25
**Status:** revisão 2. As direções D1–D7 foram **aprovadas** em 2026-09-25 (§9), junto com três correções: a definição de atividade que renova a sessão (§4.3), a revalidação dos estados no próprio comando de leitura (§4.3, §6) e a proteção efetiva do token em logs de erro (§4.5). Foi registrado também o `USAGE` explícito no schema `public` (§4.1). **Nada foi implementado:** nenhuma migration, função, view, grant, credencial ou endpoint foi criado. A migration só será escrita depois de os testes da §7 passarem com os papéis reais, incluindo o teste negativo de troca de contexto. Os privilégios e objetos novos aparecem marcados como **[NOVO]**.
**Atualização 2026-09-26:** a 8.2 foi **fechada para a camada de banco** pelo responsável, com base em `2026-09-26-validacao-013-portal-externo.md`: instalação limpa e cenários da §7 passaram com os papéis reais, inclusive o teste negativo de troca de contexto (cobertura documentada, não exaustiva). A migration `013_portal_externo.sql` **não** está aprovada para merge nem produção (revisão direta dos arquivos pendente). O portal como produto **não** está completo (§10).
**Stack:** Java com Spring Boot, com o portal em processo separado da Platform interna.
**Base:** `2026-09-25-preparacao-autorizacao-api-nekkora.md` (8.2, 8.3/8.4, 8.5, 8.9 e §11.4/§11.5), migrations 002, 003, 006, 007 e 012, e `2026-09-25-seguranca-operacional-postgres.md` (C5, C10).

## 1. Resumo do desenho

1. **Alternativa aprovada (D1): entrada de banco autenticada por sessão (alternativa 2).**
   - O papel do portal (`nekkora_portal`) recebe `EXECUTE` **só** em quatro funções `SECURITY DEFINER`: entre as funções privilegiadas, são as únicas acessíveis a ele. (Precisão de 2026-09-26: as funções `SECURITY INVOKER` antigas de `public` (001–011) continuam executáveis por `PUBLIC`, portanto também pelo portal; rodam com os privilégios dele e não abrem acesso a tabela.)
   - Não tem nenhum privilégio de tabela nem de view.
2. **A credencial que chega ao banco é o token bruto da sessão**, lido do cookie. Não é um ID nem um hash.
   - A função calcula o hash **dentro do banco** e localiza a sessão.
   - A partir da sessão, deriva a conta, o vínculo e as concessões.
   - Nenhum ID de escopo vindo do chamador **escolhe** o escopo. Os IDs recebidos só servem de filtro adicional (sempre com `AND`) sobre o escopo já derivado.
3. **GUC deixa de ser barreira.** O contexto de RLS do owner (`nekkora.fn_organization_id`) é definido pela própria função.
   - Mesmo esse contexto só limita a organização: o escopo de cliente e de concessão vem das junções com a sessão.
   - O papel do portal pode definir qualquer GUC sem efeito, porque não lê nenhuma tabela diretamente.
4. **`last_access_at`: opção A.** O portal não atualiza esse campo na v1. Ele não é métrica de atividade do portal (§5).
5. **Inatividade do portal: 15 min** para contas externas (`account_type = 2`). As demais regras seguem a §11.4.
   - O toque em `last_seen_at` é feito pela função, como owner e restrito à sessão derivada.
   - O papel do portal continua sem nenhuma escrita (D3).
   - Conta como atividade toda requisição com sessão válida, mesmo que o recurso pedido não esteja autorizado (§4.3).
6. **Login e logout de contas externas não passam pelo processo do portal.** Ficam no fluxo de autenticação existente (§3.1 do relatório da API), sob `nekkora_platform`.
   - O processo do portal nunca lê credenciais e nunca cria nem revoga sessões (D4).

## 2. Fluxo de identidade

```
Navegador ──cookie de sessão (token bruto)──▶ processo do portal (Spring, papel nekkora_portal)
                                                │  rota: /o/{organization_id}/...  (alegação, 8.1)
                                                │  uma transação por requisição, uma chamada de função
                                                ▼
     SELECT * FROM public.portal_list_executions($org, $token, filtros...)   ← binds; nenhum GUC
                                                │
            ┌───────────────────────────────────┴──── SECURITY DEFINER, owner nekkora_portal_owner (NOLOGIN)
            │ 1. valida o formato do token; hash = sha256 no banco
            │ 2. salva e define nekkora.fn_organization_id = $org (alegação; RLS do owner)
            │ 3. user_sessions WHERE organization_id = $org AND session_token_hash = hash  FOR UPDATE
            │ 4. t := clock_timestamp()  (depois do lock, §11.4)
            │ 5. sessão ativa em t (revoked_at, expires_at 12 h, last_seen_at ≤ 15 min)
            │ 6. users: account_type = 2, status ACTIVE, e-mail verificado
            │    organizations.status = 1 · client_memberships (user_id) status = 2 · clients.status = 1
            │ 7. toque condicional em last_seen_at (≤ 1/min, §11.4): sessão válida = atividade
            │ 8. consulta de dados: revalida conta, vínculo, organização e cliente e faz a
            │    junção com client_workspace_grants DO VÍNCULO DERIVADO,
            │    válidos em t, no MESMO comando da leitura; projeção só com colunas do §11.5
            │ 9. restaura nekkora.fn_organization_id (sucesso e exceção)
            └──────────────────────────────────▶ linhas do catálogo v1 (ou erro NK401 / zero linhas)
```

**Por que uma alegação de organização errada não amplia nada:** `$org` só escolhe **onde procurar** a sessão. A sessão precisa existir **naquela** organização, com **aquele** hash. Uma organização errada resulta em sessão não encontrada, e a função responde com `NK401`. O resto do escopo sai das linhas encontradas, não de parâmetros.

**Alcance de um processo do portal comprometido:**
- Ele só consegue ler os dados das sessões cujo token bruto passa por ele, ou seja, dos usuários que está atendendo.
- Mesmo assim, só dentro das concessões válidas de cada vínculo.
- Não enumera clientes, não lê hashes de sessão e não aproveita sessões de contas internas.
- Pode manter uma sessão roubada viva por toques, mas só até o prazo absoluto de 12 h. Esse risco residual é aceito.

## 3. Comparação das alternativas

| # | Alternativa | Atende ao invariante? | Motivo |
|---|---|---|---|
| 1 | Views com contexto em GUC contendo **IDs** (cliente, vínculo, concessão) | **Não** | O papel do portal redefine o GUC com `set_config` e passa a ler outra concessão. É o caso exato que o teste 3 derruba |
| 1b | Views com GUC contendo o **token** e política `hash(GUC) = session_token_hash` | Em princípio, sim (a barreira seria o segredo, não o GUC) | Não recomendada. Espalha a lógica de sessão e tempo em políticas de ~10 tabelas, com `clock_timestamp()` volátil dentro de política. Não permite o toque atômico em `last_seen_at` (view não escreve), o que exigiria uma função à parte e, com ela, duas fontes de verdade. Além disso, o token passaria a morar em estado de sessão da conexão, o que depende do `DISCARD ALL` para não vazar (C5) |
| **2** | **Funções estreitas autenticadas por sessão** | **Sim** | Uma só implementação da cadeia sessão → concessão. Privilégios de tabela ficam com um owner `NOLOGIN`, com colunas restritas. O toque na sessão é atômico. Nada depende de estado de conexão |
| 3 | Papel de banco por cliente ou vínculo | Não, na prática | Para trocar de papel, o processo precisa da senha de todos os papéis, ou ser membro deles (e aí o `SET ROLE` anula a barreira). Não há como fazer pool entre papéis. O custo de criar e rotacionar credenciais cresce com o número de clientes e usuários. Também conflita com o invariante da 012 de nenhuma membership envolvendo papéis protegidos |
| 4 | Contexto assinado de curta duração (broker) | Redundante | Exige chave no banco (legível pelo owner), TTL e janela de replay. Como a 8.4 obriga revalidar tudo a cada requisição, o banco consultaria o estado de qualquer forma. A assinatura não acrescenta prova além do token de sessão, que já é um segredo verificado contra o estado atual |

## 4. Desenho aprovado

### 4.1 Papéis, owners e privilégios

| Papel | Tipo | Privilégios | Observação |
|---|---|---|---|
| `nekkora_portal` **[NOVO]** | `LOGIN`, sem superusuário, `BYPASSRLS`, `CREATEROLE`, `CREATEDB` ou `REPLICATION` | `USAGE` no schema `public` e **`EXECUTE`** nas 4 funções da §4.4. **Nenhum** `SELECT`, `INSERT`, `UPDATE` ou `DELETE` em tabela ou view. Sem `CREATE` em `public` nem no banco | Credencial própria, usada só pelo processo do portal (C1). Sem membership com nenhum outro papel. Parâmetros de log fixados por papel (§4.5) |
| `nekkora_portal_owner` **[NOVO]** | `NOLOGIN`, mesmos atributos negados | `USAGE` no schema `public`, `SELECT` **por coluna** (lista abaixo) e `UPDATE (last_seen_at)` em `user_sessions`. É o owner das 4 funções públicas e da função interna | Sem `INSERT` e sem `DELETE`. Nenhum `UPDATE` além de `last_seen_at`. `FOR UPDATE` exige `UPDATE` em ao menos uma coluna, e `last_seen_at` supre isso |

**`USAGE` no schema `public` [NOVO, explícito]:** chamar `public.portal_*` exige `USAGE` no schema, além de `EXECUTE` na função. Hoje esse `USAGE` chega a todos os papéis pelo `PUBLIC` (padrão do PostgreSQL 15+; nem a 012 nem as anteriores concedem ou revogam `USAGE`). A migration concede o `USAGE` **explicitamente** aos dois papéis novos, para que o portal não dependa de um padrão que um endurecimento futuro possa revogar do `PUBLIC`. `USAGE` só permite **localizar** objetos do schema: não concede acesso a tabela, view, sequência ou função. Cada acesso continua exigindo o privilégio do próprio objeto, o que o teste 5 confere tabela por tabela (§7).

**`SELECT` por coluna do owner [NOVO]**, limitado ao que a cadeia e o §11.5 usam:

| Tabela | Colunas |
|---|---|
| `user_sessions` | `session_id`, `organization_id`, `user_id`, `session_token_hash`, `created_at`, `expires_at`, `last_seen_at`, `revoked_at` |
| `users` | `user_id`, `organization_id`, `account_type`, `status`, `email_verified_at` (**sem** e-mail nem nome) |
| `organizations` | `organization_id`, `status` |
| `clients` | `client_id`, `organization_id`, `status` |
| `client_memberships` | `client_membership_id`, `organization_id`, `client_id`, `user_id`, `status` |
| `client_workspace_grants` | `grant_id`, `organization_id`, `client_id`, `client_membership_id`, `workspace_id`, `status`, `approved_at`, `starts_at`, `expires_at`, `next_review_at`, `revoked_at` |
| `workspaces` | `workspace_id`, `organization_id`, `client_id`, `name`, `status` |
| `automations` | `automation_id`, `organization_id`, `workspace_id`, `name` |
| `executions` | `execution_id`, `organization_id`, `workspace_id`, `automation_id`, `created_at`, `started_at`, `finished_at`, `status`, `status_reason` |
| `execution_events` | `organization_id`, `workspace_id`, `execution_id`, `sequence`, `event_type`, `received_at` |

Com essa lista, um erro de programação na função **não consegue** ler `payload`, `error_summary`, `exit_code`, `command_snapshot`, logs, IDs de pessoas, `description` e demais campos excluídos. O banco rejeita a consulta por privilégio (defesa em profundidade da allowlist do §11.5). Logs, secrets, Workers, Agents e agendamentos ficam totalmente fora.

**Os papéis existentes não mudam.** O `nekkora_platform` continua com seus privilégios. Ele é usado no login e logout externos (D4), não pelo processo do portal.

### 4.2 RLS: sob qual identidade as políticas se aplicam

- Dentro das funções, `current_user` é `nekkora_portal_owner`. As políticas que valem são as desse papel.
- **[NOVO] Políticas próprias, sem alterar as da 012:** `portal_function_context`, `AS PERMISSIVE`, `TO nekkora_portal_owner`, com `USING` e `WITH CHECK` sobre `organization_id = NULLIF(current_setting('nekkora.fn_organization_id', true), '')::uuid`, nas 10 tabelas acima.
  - `FOR SELECT` em todas.
  - Em `user_sessions`, também `FOR UPDATE`: o `SELECT ... FOR UPDATE` exige que a linha passe no `USING` das políticas de `UPDATE`, e o toque em `last_seen_at` exige `WITH CHECK`.
- Como `nekkora_portal_owner` não é membro de nenhum outro papel nem o contrário, essas políticas **não se somam** às de `nekkora_platform` ou dos outros owners. O invariante da 012 precisa ser estendido aos dois papéis novos (§8).
- **Papel do RLS aqui:** só limita a organização alegada. **Não** é ele que isola cliente, vínculo ou concessão. Esse isolamento vem das junções, no mesmo comando, com a sessão derivada. O teste 3 prova que redefinir `nekkora.fn_organization_id` antes da chamada não muda o resultado (a função sobrescreve o valor e o restaura depois).
- **Sem views na v1.** As funções consultam as tabelas qualificadas (`public.*`) e projetam só as colunas do §11.5. Se no futuro alguma view interna for usada dentro das funções, ela deve ser `security_invoker = true` (PostgreSQL 15+), para que as políticas continuem avaliadas como o owner do portal e não como o dono da view. Nenhuma view recebe privilégio do `nekkora_portal`.
- `ENABLE` e `FORCE ROW LEVEL SECURITY` já valem nessas 10 tabelas (012). Nada é desligado.

### 4.3 Derivação da identidade: a cadeia exigida

A função interna `portal_resolve_session(p_organization_id, p_session_token)` **[NOVO]** tem o mesmo owner e **não** tem `EXECUTE` para o portal. É chamada por cada uma das quatro funções públicas e devolve `(organization_id, user_id, client_id, client_membership_id, t)` ou levanta `NK401`:

1. **Token:** formato validado **antes** do hash, contra a expressão do contrato de token (D2). Fora do formato → `NK401`. O token nunca é convertido para um tipo cujo erro ecoe o valor.
2. **Hash:** `encode(pg_catalog.sha256(pg_catalog.convert_to(p_session_token, 'UTF8')), 'hex')`, sem extensão (`sha256` é nativa desde o PostgreSQL 11). Só é aceito se a Platform gravar `session_token_hash` exatamente nesse formato (D2).
3. **Sessão:** `SELECT ... FROM public.user_sessions WHERE organization_id = p_org AND session_token_hash = hash FOR UPDATE`.
4. **Instante:** `t := clock_timestamp()` **depois** do lock (§11.4).
5. **Sessão ativa em `t`:** `revoked_at IS NULL`, `expires_at > t` e `last_seen_at > t − 15 min`.
6. **Conta:** `account_type = 2`, `status = 2` e `email_verified_at IS NOT NULL`. Sessão de conta interna → `NK401`.
7. **Organização, vínculo e cliente:** `organizations.status = 1`; `client_memberships` pela `user_id` da conta (única por conta, 002) com `status = 2`; `clients.status = 1`.
8. **Toque:** `UPDATE public.user_sessions SET last_seen_at = t WHERE session_id = … AND last_seen_at <= t − 1 min`, na linha já travada. Segue a §11.4: não mexe em `expires_at`, não retrocede e não revive sessão.

**Atividade que renova a inatividade (decisão da revisão 2):** conta como atividade **toda requisição com sessão válida**, ou seja, que passou pelos passos 1–7, **mesmo que o recurso pedido esteja fora do escopo** ou a concessão tenha acabado de ser revogada. Nesses casos a função retorna 0 linhas e o toque é confirmado.

Isso é coerente com a §11.4, porque a sessão, a conta e o vínculo estavam válidos. Também não revela nada, porque o toque acontece igual com ou sem autorização para o recurso. Consequências:
- **Sem toque:** sessões inválidas (`NK401`) e requisições com parâmetro inválido (`22023`), porque a exceção desfaz a transação inteira.
- **Nunca muda:** `client_workspace_grants` (opção A, §5) e qualquer outra tabela além de `user_sessions.last_seen_at`.

**Comando de leitura: revalidação completa, no mesmo comando e com o mesmo `t`.** A resolução (passos 3–8) e a leitura de dados são comandos separados em `READ COMMITTED`, e cada comando enxerga um snapshot novo. Por isso o comando de leitura **não confia** no resultado da resolução para nada além dos IDs derivados. Ele reavalia no próprio `WHERE`:

```
-- conta, vínculo, organização e cliente (derivados, reconferidos no snapshot da leitura)
u.user_id = <derivado> AND u.account_type = 2 AND u.status = 2 AND u.email_verified_at IS NOT NULL
AND o.organization_id = <derivado> AND o.status = 1
AND m.client_membership_id = <derivado> AND m.user_id = u.user_id AND m.status = 2
AND c.client_id = m.client_id AND c.status = 1
-- concessão do próprio vínculo para o workspace do dado
AND g.client_membership_id = m.client_membership_id AND g.client_id = c.client_id AND g.workspace_id = e.workspace_id
AND g.status = 2 AND g.approved_at IS NOT NULL AND g.starts_at <= t
AND (g.expires_at IS NULL OR g.expires_at > t) AND g.next_review_at > t
AND w.workspace_id = g.workspace_id AND w.client_id = c.client_id AND w.status = 1
```

- A **sessão** não precisa ser reconferida na leitura: a linha está travada com `FOR UPDATE` desde o passo 3, e toda revogação (`UPDATE ... revoked_at`) espera o fim da requisição (§6).
- Uma suspensão de conta, vínculo, cliente ou organização, ou uma revogação de concessão, **confirmada antes do comando de leitura** faz a leitura retornar 0 linhas, mesmo que a resolução tenha passado.
- Concessões de outras pessoas do mesmo cliente nunca entram, porque o predicado usa o **vínculo** derivado, não o cliente.
- `w.status = 1` (workspace ativo) foi acrescentado ao filtro do §11.5 (D5, aprovada).

### 4.4 Contratos das funções candidatas [NOVO]

Todas as quatro seguem o mesmo padrão:
- `SECURITY DEFINER` com `SET search_path = pg_catalog, pg_temp` e objetos qualificados;
- `VOLATILE`, porque tocam `last_seen_at`;
- owner `nekkora_portal_owner`;
- `REVOKE ALL FROM PUBLIC` e `GRANT EXECUTE TO nekkora_portal`;
- salvam, definem e restauram `nekkora.fn_organization_id` no retorno normal e no `EXCEPTION` (padrão da 012).

Os IDs recebidos são **só filtros adicionais** sobre o escopo derivado.

| Função | Entrada além de `(p_organization_id uuid, p_session_token text)` | Saída | Sem acesso |
|---|---|---|---|
| `portal_list_workspaces` | — | `workspace_id`, `name`, só de workspaces com concessão válida do vínculo | Lista vazia |
| `portal_list_executions` | `p_workspace_id uuid` (opcional, interseção), `p_status smallint[]` (opcional), `p_from`/`p_to timestamptz` (opcionais), cursor `p_after_created_at timestamptz` + `p_after_execution_id uuid` (ambos ou nenhum), `p_limit int` | `execution_id`, `workspace_id`, `automation_id`, `automation_name`, `created_at`, `started_at`, `finished_at`, `duration` (calculada), `status`, `status_reason` | Lista vazia |
| `portal_get_execution` | `p_execution_id uuid` | Mesmas colunas, 0 ou 1 linha | 0 linhas (a API responde 404, sem revelar se existe) |
| `portal_list_execution_events` | `p_execution_id uuid` | `event_type`, `received_at`, ordenados por `sequence` | 0 linhas |

- **Ordenação e página:** `created_at DESC, execution_id DESC`, com keyset `(created_at, execution_id) < (p_after_created_at, p_after_execution_id)` e **sem `OFFSET`**. `p_limit` fora de `1..N` → `22023`, com mensagem genérica. **N** é o tamanho máximo de página, decidido na 8.7. O cursor só pagina e não amplia nada: continua sob o predicado de concessão.
- **Rótulos:** as funções devolvem os **códigos** de `status`, `status_reason` e `event_type`. A API faz o mapeamento para os rótulos aprovados no §11.5. Um código fora da tabela é tratado como erro interno, sem expor o valor.
- **`workspace_id` na resposta ao navegador:** a função sempre o devolve ao processo, e é a API que decide omiti-lo quando o vínculo tem um só workspace concedido (decisão 5 do §11.5).
- **Eventos:** o predicado de concessão é aplicado sobre a **execução** dona do evento. Um evento só aparece se a execução seria visível.

### 4.5 Credencial recebida e proteção do token

- **O que o banco recebe:** o **token bruto**, como parâmetro de bind `text`. Não recebe o hash: se o hash fosse aceito como entrada, um vazamento dos hashes guardados (por backup ou pelo papel da Platform) viraria credencial válida do portal.
- **Logs do PostgreSQL:**
  - **Dois parâmetros distintos:**
    - `log_parameter_max_length` controla os valores de bind nos logs de **comando** (`log_statement`, `log_min_duration_statement`) e só pode ser alterado por superusuário.
    - `log_parameter_max_length_on_error` controla os valores anexados às **mensagens de erro**, no `CONTEXT` do erro. Esse pode ser alterado pelo próprio usuário (`SET`). O padrão 0 **não é garantia**: o papel `nekkora_portal` pode aumentá-lo na própria sessão, e o PostgreSQL 16 não tem mecanismo que impeça um papel de alterar um parâmetro desse tipo.
  - **Proteção efetiva:** como a alteração não pode ser impedida, ela é tornada **inócua para o log do servidor**. Os parâmetros que decidem **o que vai para o log** também só podem ser alterados por superusuário e ficam fixados por papel **[NOVO]**:

    | Configuração do `nekkora_portal` (`ALTER ROLE ... SET`) | Efeito |
    |---|---|
    | `log_error_verbosity = terse` | O log do servidor não grava `DETAIL`, `HINT`, `QUERY` nem `CONTEXT` dos erros desse papel. Os valores de bind anexados por `log_parameter_max_length_on_error` ficam no `CONTEXT` e, por isso, não chegam ao log, qualquer que seja o valor que a sessão defina |
    | `log_min_error_statement = panic` | O texto do comando que falhou (linha `STATEMENT`) não é gravado |
    | `log_parameter_max_length = 0` | Logs de comando e de duração sem valores de bind |
    | `log_statement = none` e `log_min_duration_statement = -1` | Nenhum log de comando para esse papel, mesmo que o padrão do servidor mude |

    - O papel não consegue reverter essas configurações: `SET` e `RESET` desses parâmetros por quem não é superusuário são negados. O teste 9 (§7) prova isso com o papel real.
    - Custo aceito: os erros do portal chegam ao log do servidor só com severidade, SQLSTATE e mensagem, e o diagnóstico usa o log sanitizado da aplicação (C10).
    - Aplicar essas configurações exige a identidade de migration com permissão sobre parâmetros restritos ao superusuário (superusuário ou `GRANT SET ON PARAMETER`, §8).
  - Se o `auto_explain` for habilitado, `auto_explain.log_parameter_max_length` também fica em 0.
- **Lado do cliente:** um valor de `log_parameter_max_length_on_error` acima de 0 também faria o `CONTEXT` do erro, **enviado ao processo do portal**, conter o token. Nenhuma configuração do banco impede isso. O processo do portal:
  - fixa o parâmetro em 0 na conexão;
  - ao iniciar, confere que ele está em 0 e **não inicia** se estiver diferente;
  - registra erros do datasource do portal **só por SQLSTATE e classe da exceção**, nunca pelo texto da mensagem do servidor.
- **`pg_stat_statements` e `pg_stat_activity`:** com bind, o texto guardado mostra `$n`, não o valor. Por isso o portal **nunca** concatena o token no SQL.
- **Erros:** nenhuma função põe o token, o hash, o `session_id` ou IDs derivados em `RAISE`. O erro `NK401` tem mensagem fixa (“sessão do portal inválida”) e nenhum `DETAIL`.
- **Aplicação:**
  - O cookie nunca vai para log, métrica ou trace.
  - A captura de parâmetros JDBC fica **desligada** no datasource do portal (por exemplo, instrumentação OpenTelemetry ou proxy de log de SQL).
  - Os logs de acesso HTTP não registram cabeçalhos `Cookie`.
  - Esses pontos entram no C10.

### 4.6 Transação, conexão e erros

- **Uma requisição = uma transação `READ COMMITTED` de leitura e escrita = uma chamada de função.** Não pode ser `readOnly = true`: o toque em `last_seen_at` é escrita, e o `SET TRANSACTION READ ONLY` faria a função falhar. O portal não define **nenhum** GUC.
- **Pool próprio** do portal, com a credencial do `nekkora_portal`, em processo separado. `DISCARD ALL` na devolução continua obrigatório (C5), como defesa geral, mesmo sem nenhum GUC definido pela aplicação.
- **Erros e sua tradução pela API:**

| Situação | Banco | API |
|---|---|---|
| Sessão ausente, inválida, revogada, expirada ou inativa; conta, vínculo, cliente ou organização não ativos; conta interna | `NK401` (SQLSTATE próprio, fora das classes que o HikariCP trata como conexão morta) | 401 genérico, sem distinguir a causa |
| Recurso fora do escopo ou inexistente | 0 linhas | 404 no detalhe; lista vazia nas listagens |
| Parâmetro inválido (limite, cursor incompleto) | `22023` com mensagem fixa | 400 |
| Qualquer outro erro | Propagado | 500, com log sanitizado (C10) |

- Com `NK401` ou `22023`, a transação é desfeita e o toque também. Nenhuma sessão inválida tem `last_seen_at` alterado. Com sessão válida e 0 linhas (recurso fora do escopo), o toque é confirmado (§4.3).

### 4.7 Login, logout e inatividade (fecha a pendência da 8.3)

- **Login, logout, "encerrar todas" e troca ou recuperação de senha de contas externas:** ficam nos endpoints de autenticação sob `nekkora_platform`, como na §3.1 do relatório da API e na §11.4, com o limite de 10 sessões. O processo do portal não chama esses endpoints com credenciais próprias. Só o navegador os usa (D4). Rotas e domínio do cookie ficam para a 8.7.
- **Inatividade do portal: 15 min** para sessões de contas com `account_type = 2`, com a mesma tolerância de até 1 min da §11.4. O prazo absoluto de 12 h não muda. Qualquer código que valide sessão externa (as funções do portal e os endpoints de autenticação) usa essa janela.

## 5. `last_access_at` — opção A (D6, aprovada)

**Decisão: A.** O portal não atualiza `client_workspace_grants.last_access_at` na v1. O campo **não é métrica de atividade do portal**, e as revisões de concessão não devem contar com ele.

Motivos para não escolher B nem C agora:
- Todo `UPDATE` em `client_workspace_grants` dispara `trg_client_workspace_grants_set_updated_at`. Registrar acesso poluiria `updated_at`, que hoje marca alterações administrativas da concessão. Evitar isso exigiria mudar a trigger da 003.
- Uma escrita por leitura na linha da concessão cria disputa de lock com a revogação administrativa. Sem nenhum consumidor definido, esse custo não se justifica.
- B daria ao owner do portal um `UPDATE` em tabela de autorização. O ganho não compensa enquanto não houver um uso aprovado.

**Quando uma revisão de concessões precisar dessa informação:** reavaliar primeiro a opção C (evento de acesso consumido por processo interno, idempotente e com throttling). Ela exige migration e contrato próprios.

A renovação da sessão continua em `user_sessions.last_seen_at` (8.5), que é independente deste campo.

## 6. Semântica de revogação e concorrência

- **Concessão:** uma requisição já autorizada termina com o snapshot do comando que leu os dados, e a seguinte usa um snapshot novo e é negada. A consulta não trava as concessões: a revogação de uma concessão não espera o portal, e o portal não espera a revogação.
- **Conta, vínculo, cliente e organização:** a resolução (passos 3–8) e a leitura são comandos distintos. Uma suspensão pode ser confirmada **entre os dois**. Como o comando de leitura reconfere esses estados no próprio snapshot (§4.3), essa suspensão faz a leitura retornar 0 linhas. Uma suspensão confirmada **depois** do início do comando de leitura não afeta a requisição em curso. A seguinte é negada: `NK401` na resolução.
- **Sessão:** a revogação (logout, suspensão com revogação da §11.4, troca de senha) disputa o lock da linha da sessão. Se a função está com o lock, a revogação espera o fim da requisição em curso. Se a revogação fez commit antes, a função encontra `revoked_at` e responde `NK401`.
- Requisições simultâneas da mesma sessão se serializam no lock da linha, como já aceito na §11.4.

## 7. Roteiro de testes negativos (antes de fechar a 8.2 e antes da migration definitiva)

**Ambiente:** PostgreSQL 16 real, 001–012 mais a migration candidata, **só em base de teste isolada**. Conexão **como `nekkora_portal`** (LOGIN real, via `psql -U`), nunca como superusuário na prova de isolamento. O harness é o da `db/tests/seguranca-012/` (`pg_temp.afirma`, `pg_temp.espera_erro`, `ON_ERROR_STOP`), numa suíte nova sob `db/tests/`.

**Chamadas por bind real:** as chamadas passam o token como parâmetro de bind (`\bind ... \g` do `psql` 16), para exercitar o protocolo estendido que o pgJDBC usa. É nesse protocolo que existem os valores de parâmetro dos logs de erro (teste 9).

**Fixture:**
- Organização O1:
  - cliente C1 com as pessoas X e Y;
  - cliente C2 com a pessoa Z;
  - workspaces WS1 e WS2 (de C1), WS3 (de C2) e WSi (interno, sem cliente);
  - concessões X→WS1, Y→WS2 e Z→WS3;
  - execuções e eventos em todos os workspaces, com `payload` e `error_summary` preenchidos por marcadores rastreáveis.
- Organização O2: cliente C3 com a pessoa W.
- Tokens aleatórios gerados pelo harness. Só o hash vai para a fixture, e os tokens não vão para a saída nem para o Git.
- **Exceção:** os tokens-marcadores do teste 9, sem valor como credencial, que existem justamente para serem procurados no log.

| # | Teste | Afirmação |
|---|---|---|
| 1 | Mesmo cliente, vínculos distintos | X, com IDs válidos de WS2 e de execuções e eventos de WS2 (em `p_workspace_id`, `p_execution_id` e no cursor), recebe 0 linhas. `portal_list_workspaces` de X retorna só WS1 |
| 2 | Clientes diferentes e outra organização | A sessão de Z não vê nada de C1. O token de X com `p_org = O2` → `NK401`. O token de W com `p_org = O1` → `NK401` |
| 3 | **Troca de contexto (gate decisivo)** | Como `nekkora_portal`, define `nekkora.organization_id` e `nekkora.fn_organization_id` para O2 e para O1, e cria GUCs arbitrários (`nekkora.client_id` e outros). Depois chama as 4 funções com o token de X: o resultado é **idêntico** ao obtido sem GUC. Com os GUCs definidos, `SELECT` direto continua negado (`42501`) |
| 4 | Estados inválidos e mutação | **(a) Sessão inválida** (sem token, token fora do formato, token inexistente, sessão revogada, expirada em 12 h, inativa há mais de 15 min, de conta interna; conta, vínculo, cliente ou organização não ativos) → `NK401`, com `last_seen_at` **inalterado**. **(b) Sessão válida, recurso não autorizado** (concessão pendente, suspensa, revogada, expirada, antes de `starts_at`, com revisão vencida; workspace inativo; WSi; ID de outra concessão) → 0 linhas, **com** toque em `last_seen_at` pela regra de 1/min (§4.3). **(c) Em todos os casos:** `client_workspace_grants` idêntica antes e depois (comparação do conjunto completo de linhas); nas demais tabelas, a única diferença admitida é `user_sessions.last_seen_at` da sessão válida do caso (b); `22023` não toca nada |
| 5 | Privilégio direto e schema | `SELECT`, `INSERT`, `UPDATE` e `DELETE` do `nekkora_portal` em todas as tabelas de `public` (inventário completo, não amostra) → `42501`. `EXECUTE` em `portal_resolve_session` e nas funções privilegiadas da 012 → `42501`. `has_*_privilege` confere que, entre as funções `SECURITY DEFINER`, o portal executa **só** as 4 públicas do portal (não é "EXECUTE em apenas quatro funções" em sentido absoluto: as funções `SECURITY INVOKER` antigas continuam executáveis por `PUBLIC` e rodam com os privilégios do portal, que não tem acesso a tabela). `has_schema_privilege`: `USAGE` verdadeiro e `CREATE` falso em `public`, para os dois papéis novos. O `USAGE` não dá acesso a tabela nenhuma (as negativas acima valem com ele concedido) |
| 6 | Allowlist | A lista de colunas de cada função (catálogo: `pg_get_function_result`) é exatamente a da §4.4. Nenhum marcador de `payload`, `error_summary`, `exit_code` ou `description` aparece em nenhuma linha nem mensagem. Como owner (`SET ROLE` do superusuário, só para este caso), `SELECT payload FROM execution_events` → `42501`, o que prova o limite por coluna |
| 7 | Concorrência e revogação | **(a)** Uma transação em paralelo revoga a concessão de X e faz commit; a próxima chamada de X retorna 0 linhas. **(b)** Uma chamada de X segura o lock da sessão (harness com duas sessões, como os `conc*` da 012); o `revoked_at` concorrente espera, e a chamada seguinte → `NK401`. **(c)** Suspensão **entre a resolução e a leitura**, para cada um dos quatro estados (conta, vínculo, cliente, organização) e para a revogação da concessão: com a variante de teste descrita abaixo, a chamada de X para depois da resolução; outra sessão suspende e faz commit; a chamada é liberada e retorna **0 linhas**. **(d)** Toque de `last_seen_at`: duas chamadas no mesmo minuto geram uma escrita; depois de mais de 1 min, geram outra; nunca retrocede. **(e)** `last_access_at` nunca muda (opção A) |
| 8 | Robustez das funções | `fn_organization_id` definido como sentinela antes da chamada é restaurado depois dela, no sucesso e no `NK401`. `prosecdef`, `proconfig` com `search_path=pg_catalog, pg_temp`, owners e ACLs conferidos no catálogo. Uma função e um operador homônimos criados em `pg_temp` pelo `nekkora_portal` não alteram o resultado. Nenhuma membership envolve os dois papéis novos |
| 9 | Token fora dos logs (papel real) | Como `nekkora_portal`: **(a)** `SHOW` confere `log_error_verbosity = terse`, `log_min_error_statement = panic`, `log_parameter_max_length = 0`, `log_statement = none` e `log_min_duration_statement = -1`. **(b)** `SET` e `RESET` de cada um → `42501`. **(c)** `SET log_parameter_max_length_on_error = -1` é **aceito** (comprova que esse parâmetro não pode ser travado). Em seguida, uma chamada por bind com um token-marcador em formato válido, mas inexistente, gera `NK401`, e o marcador **não aparece** no log do servidor do container de teste. **(d) Controle positivo:** numa sessão de superusuário com `log_error_verbosity = default`, `log_min_error_statement = error` e `log_parameter_max_length_on_error = -1`, a mesma chamada com outro marcador **aparece** no log. Isso prova que a busca enxergaria um vazamento |

**Variante de teste para o 7(c):**
- É instalada **só na base de teste**. Tem o corpo idêntico ao da função da migration, com uma única linha a mais, entre a resolução e a leitura: `pg_advisory_xact_lock` numa chave do harness.
- O runner confere que o `prosrc` da variante, sem essa linha, é igual ao da função instalada. Assim, o que se testa é o comando de leitura real.
- A função da migration não tem ponto de pausa.

**Testes deliberados do runner (mutação)**, no padrão da 012. Cada variante propositalmente errada precisa fazer a suíte falhar:
- função que confia num GUC de vínculo (teste 3);
- comando de leitura sem a reconferência de conta, vínculo, cliente ou organização (teste 7c);
- toque em `last_seen_at` antes da validação da sessão (teste 4a);
- papel sem `log_error_verbosity = terse` (teste 9c).

## 8. Itens novos para a migration (aprovados na direção; migration só depois dos testes)

Todos são **[NOVO]**. Iriam numa migration própria (por exemplo, `013_portal_externo.sql`). A versão definitiva só entra em `db/migrations/` depois de os testes da §7 passarem, na base isolada, contra a versão candidata:

1. Papéis `nekkora_portal` (LOGIN) e `nekkora_portal_owner` (NOLOGIN), com atributos negados explicitamente.
2. `GRANT USAGE ON SCHEMA public` explícito aos dois papéis novos, sem `CREATE`.
3. `SELECT` por coluna e `UPDATE (last_seen_at)` para o owner (§4.1).
4. Políticas `portal_function_context` nas 10 tabelas (`FOR SELECT`; `FOR UPDATE` também em `user_sessions`).
5. Funções `portal_resolve_session` (interna) e as quatro públicas (§4.4), com `REVOKE ALL FROM PUBLIC` e `EXECUTE` só para o portal.
6. Configurações de log por papel do `nekkora_portal` (§4.5): `log_error_verbosity = terse`, `log_min_error_statement = panic`, `log_parameter_max_length = 0`, `log_statement = none` e `log_min_duration_statement = -1`. **Todas só podem ser alteradas por superusuário**, então a identidade que executa a migration precisa de permissão sobre elas (superusuário ou `GRANT SET ON PARAMETER`), o que entra no processo do C4.
7. Invariantes ao final da migration, no mesmo padrão da seção 15 da 012, cobrindo também os dois papéis novos:
   - atributos;
   - membership em qualquer sentido;
   - `CREATE` efetivo;
   - `SECURITY DEFINER` com `search_path`;
   - ausência de privilégio de tabela do `nekkora_portal`;
   - presença das configurações do item 6 em `pg_db_role_setting`.
8. Candidato de desempenho, a medir e não de segurança: índice `executions (organization_id, workspace_id, created_at DESC, execution_id DESC)` para o keyset.
9. Credencial do `nekkora_portal` fora do repositório (C1). A atualização do inventário de papéis no relatório operacional fica para quando a migration for aprovada.

## 9. Decisões (aprovadas em 2026-09-25)

| # | Decisão | Registro |
|---|---|---|
| D1 | Alternativa de desenho | **Aprovada: alternativa 2** (funções autenticadas por sessão). 1, 3 e 4 rejeitadas; 1b registrada como não recomendada |
| D2 | Contrato do token e do hash | **Aprovado:** token com 32 bytes aleatórios em base64url sem padding (43 caracteres); `session_token_hash` = SHA-256 em hex minúsculo dos bytes UTF-8 do token. **O mesmo contrato na Platform** (criação da sessão) **e no portal** (validação) |
| D3 | Toque em `last_seen_at` | **Aprovado** como manutenção interna da sessão, feita pelo owner e restrita à sessão derivada, sem escrita direta pelo papel do portal. Conta como atividade toda requisição com sessão válida, mesmo com recurso não autorizado (§4.3) |
| D4 | Login e logout externos | **Aprovado:** nos endpoints de autenticação da Platform (`nekkora_platform`). O processo do portal não lê credenciais e não cria nem revoga sessões. **Domínio e atributos do cookie: 8.7** |
| D5 | Workspace ativo no filtro | **Aprovado:** exigir `workspaces.status = 1`. O filtro do §11.5 do relatório da API foi atualizado |
| D6 | `last_access_at` | **Aprovada a opção A:** não é atualizado na v1 (§5) |
| D7 | Inatividade do portal (8.3) | **Aprovado:** 15 min para `account_type = 2`, tolerância de até 1 min, prazo absoluto de 12 h |

**Correções da revisão 2**, incorporadas acima:
1. Definição de atividade que renova a sessão, e teste 4 separado entre sessão inválida (sem toque) e recurso não autorizado (com toque e concessões intactas).
2. Reconferência de conta, vínculo, organização e cliente no mesmo comando da leitura, com os testes concorrentes 7(c).
3. Proteção efetiva do token em logs de erro por configurações que só o superusuário altera, com o teste 9 no papel real.
4. `USAGE` explícito no schema `public`.

## 10. Limites

- **O desenho está aprovado.** A 8.2 só fica **fechada** depois da execução dos testes da §7 com os papéis reais, com o teste 3 passando. A migration definitiva também espera esse resultado.
- **Fechamento (2026-09-26):** 8.2 fechada **para a camada de banco** (`2026-09-26-validacao-013-portal-externo.md`). Continuam abertos: revisão direta da migration e da suíte (aprovação para merge/produção); código Spring do portal; integração da Platform (proteção dos logs da aplicação, formato do hash gravado no login, reset explícito da conexão no pool); decisões 8.6, 8.7 e 8.10 antes das rotas de autenticação; atualização de uma base populada; limite definitivo de página.
- **Requisito separado, da implementação Spring Boot:** a filtragem dos detalhes das exceções no processo do portal (§4.5, lado do cliente). O parâmetro fica fixado em 0 e verificado na inicialização, e os erros são registrados só por SQLSTATE. Nenhuma configuração do banco cobre essa parte.
- Continuam fora: rotas, códigos HTTP, tamanho de página, domínio e atributos do cookie (8.7); limites e MFA (8.6); Argon2id (8.10); endurecimento de `user_sessions` por trigger e limpeza de sessões (C11).
