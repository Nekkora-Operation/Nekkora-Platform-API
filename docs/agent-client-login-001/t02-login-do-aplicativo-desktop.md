# Contrato do login do Nekkora Agent (Client) pelo navegador

Missão AGENT-CLIENT-LOGIN-001 · T00. Versão do contrato: `desktop-login/1`.

## 1. O que existe hoje (inspeção em `dev`)

| Tema | Fato | Onde |
|---|---|---|
| Identidade das pessoas | "Entrada geral": senha e, se a conta já confirmou um fator, TOTP obrigatório. Sem fator: sessão só de senha (029) | `IdentityAuthController`, `IdentityLoginService`, migrations 017/029 |
| Sessão do navegador | Cookie `__Host-nekkora_id` (opaco, 43 caracteres; só o SHA-256 no banco), 12 h absolutas, sem renovação; conferida no banco a cada requisição | `Cookies`, `IdentityFilter`, `identity_sessions` |
| CSRF | `Origin` igual à origem pública + `X-CSRF-Token` do contexto (pré-autenticação no Redis, desafio ou sessão) | `CsrfFilter`, `Routes`, `PreAuthCsrf` |
| Revogação | Logout revoga uma sessão. Troca/redefinição de senha, troca/ativação de MFA e código de recuperação revogam as demais ou todas | funções da 017/020/029 |
| Organização | Nenhuma é escolhida na sessão de identidade; a sessão de organização é outra (`POST /sessions/organization`) | `IdentityOrganizationsController` |
| OAuth/OIDC | **Não existe** servidor de autorização, cliente registrado, JWT de sessão nem biblioteca OAuth no projeto | `api/pom.xml` |
| Máquinas | Canal próprio (`/agent/api/v1`, outro processo, outra credencial) | `nekkora-agent-gateway` |

## 2. Decisão

Não há servidor OAuth/OIDC para reaproveitar. Trazer um (Spring Authorization Server, provedor externo) seria um
componente novo de identidade, com chaves de assinatura, cadastro de clientes e mudança de operação. Fica fora desta
missão.

O que se implementa é **o fluxo Authorization Code com PKCE S256 e redirecionamento de loopback (RFC 6749 §4.1,
RFC 7636, RFC 8252 §7.3, com `iss` da RFC 9207)**, para **um** cliente público fixo, sobre os componentes de identidade
que já existem: login e MFA da Web, sessão de identidade, CSRF, Redis e auditoria. Não é um servidor OAuth genérico e
não é OIDC: não há `id_token`, descoberta, cadastro dinâmico de clientes nem `refresh_token`. O token de acesso é opaco
e só vale nas duas rotas da sessão do desktop.

## 3. Fluxo

```
Client                               Navegador (Web)                         API (Platform)
  | gera state, code_verifier, challenge=S256(verifier)
  | abre 127.0.0.1:<porta efêmera>/callback
  | abre o navegador em
  |   {origem}/entrar/aplicativo?response_type=code&client_id=nekkora-agent-client
  |     &redirect_uri=http://127.0.0.1:<porta>/callback&state=…&code_challenge=…
  |     &code_challenge_method=S256&scope=identity
  |                                      | POST /api/v1/desktop/authorizations ------> valida tudo, guarda o pedido
  |                                      |   (CSRF de pré-autenticação)               (Redis, 5 min) e devolve o
  |                                      | <----- cookie __Host-nekkora_desk --------  cookie do pedido
  |                                      | tira os parâmetros da URL
  |                                      | sem sessão: login e MFA normais da Web
  |                                      | GET /api/v1/desktop/authorization --------> conta que vai autorizar
  |                                      | pessoa confere a conta e clica "Autorizar"
  |                                      | POST …/authorization/approve -------------> exige sessão de identidade plena;
  |                                      |   (CSRF da sessão)                          consome o pedido; emite código
  |                                      | <----- { redirectTo } --------------------  (60 s, uso único)
  | <---- GET /callback?code=…&state=…&iss=… (navegador vai ao loopback)
  | confere caminho, método, state e iss
  | POST /api/v1/desktop/token (code, code_verifier, client_id, redirect_uri) ------> consome o código, confere PKCE,
  | <---- { access_token, expires_in=900, scope=identity } -------------------------  cliente, redirect e sessão de origem
  | GET /api/v1/desktop/session (Bearer) -------------------------------------------> { account.email, expiresIn }
  | só agora: tela inicial. A página do loopback mostra "Login concluído…"
```

## 4. Rotas novas (`/api/v1/desktop/…`, processo Platform)

| Rota | Quem chama | Autenticação e CSRF | Resultado |
|---|---|---|---|
| `POST /authorizations` | Web | pública; CSRF de pré-autenticação | `204` + cookie `__Host-nekkora_desk` (HttpOnly, Secure, SameSite=Strict, 5 min) |
| `GET /authorization` | Web | sessão de identidade + cookie do pedido | `{application, account:{email}, expiresIn}` |
| `POST /authorization/approve` | Web | sessão de identidade (não restrita) + CSRF da sessão | `{redirectTo}` (loopback com `code`, `state`, `iss`) |
| `POST /authorization/deny` | Web | pública; CSRF de pré-autenticação + cookie do pedido | `{redirectTo}` (loopback com `error=access_denied`, `state`, `iss`) |
| `POST /token` | Client | nenhuma credencial de navegador; `application/x-www-form-urlencoded` | `{access_token, token_type:"Bearer", expires_in, scope}` ou `{error}` da RFC 6749 §5.2 |
| `GET /session` | Client | `Authorization: Bearer` | `{account:{email}, scope, expiresIn}` |
| `POST /session/revoke` | Client | `Authorization: Bearer` | `204` (idempotente) |

- Tudo atrás de `nekkora.desktop-login.enabled` (padrão desligado; exige a entrada geral ligada). Desligado: `404`.
- Todas as respostas com `Cache-Control: no-store`.
- `/token` e `/session/revoke` não passam pelo CSRF do navegador (não há `Origin` num cliente nativo) **e ignoram
  cookies**: só valem o código com o verificador, ou o token Bearer.
- O token Bearer **não autentica nenhuma outra rota**. Os filtros de sessão só leem cookies; qualquer rota de
  organização, catálogo, máquina ou administração responde `401` a um Client, alterado ou não.

## 5. Validações no servidor

| Dado | Regra |
|---|---|
| `client_id` | exatamente `nekkora-agent-client` (cliente público, sem segredo) |
| `redirect_uri` | exatamente `http://127.0.0.1:<porta>/callback`, porta 1024–65535; sem usuário, consulta, fragmento, nome de host (`localhost` recusado) ou outro caminho. Só a porta varia (RFC 8252 §7.3) |
| `response_type` / `scope` | `code` / `identity` |
| `code_challenge_method` | só `S256` (`plain` e ausência recusados) |
| `code_challenge` | 43 caracteres base64url |
| `state` | 22–128 caracteres `[A-Za-z0-9_-]`; devolvido sem alteração |
| Pedido | guardado no servidor; o navegador só carrega o cookie opaco. Login e MFA não reenviam parâmetros |
| Aprovação | sessão de identidade válida agora, não restrita (código de recuperação pendente: `403`); consome o pedido |
| Código | 32 bytes aleatórios; só o SHA-256 no Redis; 60 s; consumido por `GETDEL` antes de qualquer conferência (erro de PKCE também o queima) |
| Troca | `client_id` e `redirect_uri` idênticos aos do pedido; `S256(code_verifier)` = desafio; a sessão do navegador que autorizou ainda vale |
| Reuso do código | segunda apresentação: `invalid_grant` **e** a sessão emitida na primeira é revogada |

## 6. Sessão do desktop

- Token opaco de 32 bytes; o servidor guarda só o SHA-256 (Redis, `nk:desk:sess:<hash>`), com a identidade e a sessão
  do navegador que a autorizou.
- **15 minutos, sem renovação e sem `refresh_token`.** O Client guarda o token só em memória.
- Escopo `identity`: ler o próprio e-mail e encerrar a própria sessão.
- **A cada uso, o servidor confere no banco a sessão de identidade de origem.** Assim, sem código novo de revogação:
  - logout no navegador (daquela sessão) encerra a sessão do desktop;
  - troca ou redefinição de senha, troca ou ativação de MFA, uso de código de recuperação e reset operacional, que já
    revogam sessões de identidade, encerram também a do desktop;
  - fechar a aba **não** encerra nada.
- **Limite de detecção:** o Client confere a sessão ao entrar, quando a janela volta a ficar ativa (no máximo uma vez
  por minuto) e ao vencer o prazo. Não há consulta contínua: uma revogação pode levar até a próxima conferência para
  aparecer na tela, e nunca passa de 15 minutos.
- Prazos: pedido 5 min; código 60 s; sessão 15 min. Nenhum prazo existente muda.

## 7. Callback no Client

- Escuta só em `127.0.0.1`, porta efêmera, aberta **antes** do navegador; fecha no sucesso, cancelamento, expiração,
  falha terminal e ao sair do aplicativo.
- Só `GET /callback`. Outro método ou caminho: `404/405`, sem efeito na tentativa.
- Parâmetro repetido, `state` ausente ou diferente (comparação em tempo constante), `iss` diferente da origem
  configurada: resposta de erro ao navegador, **a tentativa legítima continua esperando**. Mais de 20 requisições
  inválidas encerram a tentativa com "retorno inválido".
- Com `state` certo: `error=access_denied` → cancelado; `code` → troca com a API por TLS validado.
- A página só diz "Login concluído. Você pode voltar ao Nekkora Agent." **depois** de a API confirmar a troca e a
  sessão. Sem credenciais na página; `Cache-Control: no-store`; `Referrer-Policy: no-referrer`.
- Resultado incerto na troca (tempo esgotado, conexão caída): a tentativa termina; nada é reaproveitado.

## 8. Ameaças e respostas

| Ameaça | Resposta |
|---|---|
| Interceptação do código (outro programa na porta, histórico do navegador) | PKCE S256 obrigatório; código de 60 s e uso único; reuso revoga |
| CSRF / injeção de código no Client | `state` por tentativa; `iss`; callback só da tentativa atual |
| Tentativas concorrentes | cada uma tem `state`, verificador e porta próprios; o Client só mantém uma |
| Redirecionamento aberto | `redirect_uri` só loopback com caminho fixo; `redirectTo` é montado pelo servidor; a Web só navega para `http://127.0.0.1:<porta>/callback` |
| Login silencioso com a pessoa errada | a página de confirmação sempre mostra a conta e exige o clique; "Usar outra conta" e "Cancelar" |
| Pedido plantado por um site (a pessoa não abriu o Agent) | a confirmação é sempre exigida; o código só serve a quem tem o verificador e escuta naquela porta |
| Downgrade de PKCE | `plain` e ausência recusados no pedido; a troca sempre exige o verificador |
| Vazamento em logs | código e tokens têm o formato de 43 caracteres já mascarado pelo `logback`; o nginx registra só o caminho, sem a consulta; nada disso entra na auditoria |
| Client alterado | o token não vale fora de `/desktop/session*`; o servidor nega o resto |
| Programa malicioso do mesmo usuário do Windows | fora do alcance de um cliente público (RFC 8252 §8.5); o prazo curto limita o dano |
| Redis comprometido | poderia forjar uma sessão de desktop; o alcance é o e-mail da própria conta. Registrado como limite (seção 10) |

## 9. Banco, auditoria e limites

- **Migration 037**, sem elevação: só amplia o `CHECK` de `identity_events.event_type` para 21–23.
  - 21 = `DESKTOP_LOGIN_AUTHORIZED`; 22 = `DESKTOP_SESSION_ISSUED`; 23 = `DESKTOP_SESSION_REVOKED`.
  - Nenhuma tabela, função, papel ou política nova.
- Os eventos não levam código, verificador, token nem `state`.
- Limite de requisições: o das rotas de identidade por IP já existente.
- `IdentityTransactions` ganha a chave `SessaoDeOrigem(identidade, sessão)`: o contexto de RLS vem do estado que o
  próprio servidor gravou ao emitir o código, nunca de parâmetro da requisição.

## 10. Limites e decisões que ficam com você

1. **Estado no Redis, não no PostgreSQL.** Pedido, código e sessão do desktop vivem no máximo 15 minutos no Redis.
   Uma tabela própria com consulta por token exigiria funções `SECURITY DEFINER` do dono de identidade, ou seja,
   uma migration **com elevação**. Não fiz isso sem a sua decisão. Se o Redis cair, as sessões de desktop acabam
   (falha fechada).
2. **Logout no navegador encerra a sessão do desktop nascida daquela sessão.** É o mesmo vínculo que as sessões de
   organização já têm.
3. **Só `127.0.0.1`.** `::1` não é aceito nesta etapa.
4. **Ligar em produção** (`NEKKORA_LOGIN_DESKTOP`) é ato de implantação, fora desta missão.
5. **Persistência de login, renovação e escolha de organização** não existem nesta etapa.
