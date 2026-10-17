# 017 R5: eventos de step-up e consulta de criação de organização

**Data:** 2026-10-14. **Origem:** parecer da responsável sobre os contratos de step-up, criação e vinculação (`2026-10-13-contratos-step-up-criacao-vinculacao.md`). Ela aprovou os eventos 13 e 14 e pôs no escopo a função `identity_org_creation_allowed`, porque ela sustenta o significado de `canCreateOrganization`. **Nenhum código Java usa a R5 ainda**: P1, P2, P4 e P3 entram depois, nessa ordem.

## 1. O que muda na 017

| Peça | Mudança |
|---|---|
| `identity_events.event_type` | `CHECK (event_type BETWEEN 1 AND 14)`; comentário com `13=STEP_UP` (P1) e `14=ORG_STEP_UP` (P4) |
| `identity_org_creation_allowed(p_session_token TEXT) → BOOLEAN` | nova; `LANGUAGE sql`, `STABLE`, `SECURITY DEFINER`, `search_path` fixo, dona `nekkora_identity_owner`, `EXECUTE` revogado de `PUBLIC` e concedido **só** a `nekkora_platform` |
| invariante 3 da 017 | a lista das funções de identidade executáveis pela Platform ganha a função nova |
| cabeçalho | nota da R5 |

**Semântica da função.** Devolve verdadeiro só se, ao mesmo tempo:
- a sessão de identidade do token existe, não foi revogada, está no prazo e tem MFA;
- a sessão **não é restrita** à recuperação (DC-17.13);
- a identidade está ACTIVE e com e-mail verificado;
- a identidade tem **menos de 10** vínculos `cadastro_web` (o teto de `identity_create_organization`).

Token nulo, fora do formato ou desconhecido devolve falso, sem erro. A função **não exige step-up**, porque o step-up é pedido na hora da criação. Ela não trava nada e não escreve nada.

**É só indicação para a interface.** A criação continua reconferindo sessão, step-up, identidade e teto dentro de `identity_create_organization`, sob o lock da identidade, qualquer que seja o valor mostrado. Os testes T-7 e T-8 provam isso.

**Nada mais muda:** nenhum privilégio de tabela, nenhuma trigger, nenhuma função existente. Diff R4b → R5 no pacote. Hashes: R4b `102b9f07…`; R5 `90b8ddbc…`.

## 2. Testes (suíte da 017)

**Grupo T, novo** (`t_t_r5.sql`, papel real `nekkora_platform`):

| Cenário | Situação | Resultado |
|---|---|---|
| T-1 | sessão comum, identidade ativa, nenhuma organização criada | verdadeiro |
| T-2 | token nulo, fora do formato, desconhecido | falso, sem erro |
| T-3 | sessão **expirada** (prazo de 1 s, vencido) | falso |
| T-4 | sessão **revogada**; antes da revogação, verdadeiro; outra sessão da mesma identidade continua verdadeira | falso |
| T-5 | sessão **restrita**, mesmo com step-up gravado | falso |
| T-6 | identidade suspensa com a sessão ainda ativa | falso |
| T-7 | dez criações reais; a consulta é verdadeira antes de cada uma | depois da 10ª, falso; a 11ª criação é recusada pela própria função de escrita |
| T-8 | consulta verdadeira, sessão sem step-up | a criação recusa: a consulta não dispensa nada |
| T-9 | dentro de transação READ ONLY; catálogo | responde; `STABLE` e `SECURITY DEFINER` |
| T-E | eventos 13 e 14 (este com a organização alvo) | gravados (contagem conferida pelo superusuário, porque a Platform só tem `INSERT` em `identity_events`); 0 e 15 recusados pelo CHECK (`23514`) |

**Grupo M (matriz de privilégios):** a matriz esperada ganhou exatamente duas linhas, `fn|nekkora_identity_owner|identity_org_creation_allowed(text)` e `fn|nekkora_platform|identity_org_creation_allowed(text)`. A matriz real coincide.

**Mutações novas, detectadas e restauradas:**
- **M11:** função sem a recusa da sessão restrita. T-5 passa a responder verdadeiro.
- **M12:** função sem o teto. T-7 passa a responder verdadeiro com dez organizações.

Depois das doze mutações, o catálogo volta idêntico à instalação.

## 3. Execução

**Ensaio:** antes de mexer na 017 do repositório, a suíte rodou numa cópia de `db/` com a R5, enquanto a regressão Java do bloco 3 r2 usava a R4b. A primeira tentativa reprovou no grupo T por um erro do próprio teste: a Platform não tem `SELECT` em `identity_events`, o que está correto. A contagem dos eventos 13 e 14 passou para uma verificação do superusuário no `run.sh`. A segunda tentativa passou inteira.

**Execução oficial,** no repositório, com a 017 R5 (`saidas/2026-10-14T034716Z_nekkora-id017`): `rc=0`, nas três bases.

| Parte | Resultado |
|---|---|
| A, catálogo | 2 remoções previstas, 833 acréscimos, **0 fora do previsto** |
| A, migrador | 4 janelas SUPERUSER e 1 BYPASSRLS, todas retiradas em `0|0|0`; invariantes 012/013/014/017 depois da retirada, no início e no fim |
| A, grupos | A, B, C′, D, G, H, I, J, L, **T** (`t_t_r5.txt`: 20 conferências impressas, mais as 20 do laço de T-7, que interrompem na primeira falha) e os de concorrência: 41 verificações de estado final (inclui T-E), 34 esperas de lock observadas |
| A, E | entrada local viva conferida em 105 pontos, todos `0|0|0|0` |
| A, M | 360 privilégios efetivos = matriz esperada (`M02_diff.txt` vazio) |
| A, mutações | **12 de 12** detectadas (M11 e M12 novas), restauradas; catálogo final = instalação (`MUT_diff_catalogo.txt` vazio) |
| B, base populada 015 → 017 | instantâneo idêntico, sessões ativas preservadas, catálogo = instalação limpa, invariantes, ramo 1 e restauração lógica |
| C/D, com a 016 | 001-017, invariantes 012/013/014/016/017; atos da 016 pelos caminhos reais antes e depois da 017; catálogo = base limpa |

**Catálogo instalado, R4b × R5** (a última execução oficial da R4b, `2026-10-13T162230Z`, com a 017 `102b9f07…`, contra esta): exatamente **quatro** linhas diferem. São o CHECK de `event_type` (12 → 14), a função nova (`secdef=t`, `vol=s`, `lang=sql`, `search_path` fixo) e as duas concessões de `EXECUTE` (dona e `nekkora_platform`).

O rótulo final do `run.sh` ("grupos A, B, C′, D, G, H, I, J, L") não cita o T. O grupo roda e é conferido como os demais (`roda` interrompe na primeira falha). Não alterei o rótulo depois da execução, para que o log corresponda ao script entregue.

## 4. Java com a R5

- **Regressão Java completa** com a 017 R5 aplicada antes do início, com os relatórios limpos: **68 classes, 414 testes**, só as 5 falhas conhecidas do `FixtureRunnerIT` (bash 3.2). Comparada por classe, é **idêntica** à do bloco 3 r2, que rodou sobre a R4b.
- **E2E entre processos** (`api/e2e/run.sh`, migrations pelo modelo do migrador): `CrossProcessE2E` 6/6, `E2E OK`. Antes de remover a stack descartável, uma consulta de leitura no banco dela confirmou a função e o CHECK `1..14`. A stack foi removida com o comando que o runner imprime (`down`, sem `-v`).

## 5. O que fica para depois

- Usar a função em `GET /api/v1/me/organizations` (`canCreateOrganization`) entra com **P2**, junto da rota de criação, sob a chave própria. Até lá o campo continua `false` (decisão D2 do bloco 3).
- Os eventos 13 e 14 passam a ser gravados com **P1** e **P4**.
- A capacidade inicial de execução continua decisão de produto pendente. O zero do bootstrap **não** vira regra aprovada por esta revisão.
- As semeaduras (`semear_f6a.sql`, `semear_frontend.sql`, `test_semear_f6a_banco.sh`) continuam a adaptar antes de implantar a 017. Ambiente preservado, Microsoft 365 e commit/push continuam fora.
