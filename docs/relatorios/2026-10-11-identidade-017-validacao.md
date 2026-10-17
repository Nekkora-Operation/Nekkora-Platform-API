# Migration 017 (identidade, cadastro web e entrada geral): implementação local e validação da suíte de banco

**Data:** 2026-10-11. **Estado:** candidata, validada localmente. Fora desta etapa: Java, ambiente preservado, Microsoft 365, commit e push. Nenhum deles foi tocado.

**Desenho:** `2026-10-11-cadastro-entrada-geral-desenho.md`, na versão final. A §13 lista os ajustes surgidos na implementação; quatro deles são decisões que aguardam conferência.

**Arquivos desta entrega:**

| Arquivo | O que é |
|---|---|
| `db/migrations/017_identidade_cadastro.sql` | migration, uma transação, com o bloco de invariantes (§12) |
| `db/tests/identidade-017/` | suíte: `run.sh`, `README.md` e `sql/`, com a matriz `m_esperado.txt` |
| `db/tests/identidade-017/saidas/2026-10-11T144726Z_nekkora-id017/` | evidência da rodada verde: 127 arquivos |
| `db/bootstrap/aplicar_como_migrador.sh` | **alterado**: o padrão de `NEKKORA_ELEVAR` inclui 017. Sem isso, o bootstrap local aplicaria a 017 sem a janela de SUPERUSER e falharia |

## 1. Resultado

A rodada verde foi feita do zero, com PostgreSQL 16.14 e o modelo do migrador. As três bases foram removidas no fim.

| Parte | Resultado |
|---|---|
| A. Instalação limpa 001–015 + 017 | 16 migrations aplicadas. 4 janelas de SUPERUSER (011, 012, 013, 017) e 1 de BYPASSRLS, todas com retirada `0\|0\|0` |
| A. Catálogo × 001–015 | 768 acréscimos, todos de identidade, e 0 fora do previsto. **2 remoções, as únicas previstas**: as versões antigas de `enforce_user_session_mfa_on_insert()` e `prevent_user_session_reactivation()`. 10 tabelas novas e 11 políticas `identity_function_context` |
| A. Invariantes da 012, 013, 014 e 017 | verdes depois da retirada, no início e no fim da suíte. A 014 teve uma adaptação, conferida |
| A. Grupos A, B, C′, D, G, H, I, J, L | verdes: 137 asserções positivas e 94 rejeições com SQLSTATE e mensagem conferidos. Os grupos C e K somam mais 12 rejeições, num total de 106 |
| A. Grupo L | todas as funções sem a sua prova devolveram zero linhas, NULL ou `false`. Instantâneo md5 das **59 tabelas** de `public` idêntico antes e depois |
| A. Grupos C e K | 16 verificações de estado final. **17 esperas de lock observadas** em `pg_stat_activity`: a transação concorrente estava mesmo bloqueada, não só teve sorte de ordem |
| A. Invariantes E | E-1..E-3 e "entrada local viva" conferidos em **64 pontos**, depois de cada cenário, todos `0\|0\|0\|0` |
| A. Grupo M | 259 privilégios efetivos, iguais à matriz completa revisada |
| A. Mutações | **2 de 2 detectadas** (M1: trigger sem `FOR UPDATE`; M2: auxiliar sem a recusa da sessão restrita). Restauradas, com o catálogo final idêntico ao da instalação |
| B. Base populada 015 → 017 | 303 contas, 60 fatores, 60 sessões ativas. Instantâneo das **49 tabelas** idêntico; nenhuma sessão revogada; catálogo igual ao da instalação limpa; invariantes verdes. Conta atual com fator abre sessão e conta sem fator continua recusada. Restauração lógica com contagens idênticas |
| C. 001–017 com a 016 | instalação conjunta e invariantes da 012, 013, 014, 016 e 017 verdes |

## 2. O que a suíte demonstra, nos termos pedidos

**O lock é do banco.** Nos grupos C e K, a transação concorrente roda com o papel real `nekkora_platform`, por SQL direto, sem `AuthService` e sem `AccountRepository.lock`. O observador a vê esperando lock, e o desfecho de cada intercalamento é conferido no estado final.

A mutação M1 tira o `FOR UPDATE` da trigger. Com ela, a escrita crua **passa**, e a suíte reprova. É a prova de que o resultado verde depende do lock no banco, e não de ordem casual.

C-9 cobre `REPEATABLE READ`: a transação com instantâneo anterior à vinculação recebe `40001`. C-10 mostra a vinculação recusando rodar fora de `READ COMMITTED`.

**O papel da aplicação não contorna.** O grupo C′ confere:
- `42501` em `SELECT`, `INSERT`, `UPDATE`, `DELETE` e `TRUNCATE` sobre `identity_users`;
- `must be owner` ao tentar `DISABLE TRIGGER` em 12 tabelas;
- recusa de `session_replication_role` e de `SET ROLE nekkora_identity_owner`;
- funções internas inexecutáveis.

**A sessão restrita é recusada no banco.** G-1 tenta a sessão de organização com o fator antigo ainda confirmado, e o banco recusa. G-3 mostra que step-up não levanta a restrição. G-2 mostra que, depois de T1, a sessão nasce. A mutação M2 reprova a suíte.

**`xmin` não prova criação.** I-1 força um `xmin` desta transação na conta antiga e tenta todos os caminhos: escrita direta (`42501`), vinculação sem prova (`false`) e criação de organização, que cria uma organização **nova**. No fim, a conta antiga não está em `identity_users`.

**A troca de fator é exclusiva da função autorizada.** O grupo H testa as tentativas por SQL direto:
- primeiro fator e troca por escrita direta: `42501`;
- a sequência apontada pela responsável falha nos dois passos;
- como dono e como superusuário, confirmação irreversível, campos imutáveis e `DELETE` recusado;
- a condição "nunca teve fator confirmado" se mantém mesmo depois de uma troca (H-11).

**As auxiliares falham fechado.** J-3 usa, na mesma chamada, uma conta de outra organização vinculada e outra não vinculada. As duas recebem a **mesma** falha `42501`.

## 3. Defeitos encontrados durante a implementação, já corrigidos

1. **Impasse entre duas trocas de fator concorrentes da mesma pessoa** (§13, item 3). Cada uma travava a própria sessão e depois a identidade, e `identity_mfa_replace` revoga as demais sessões. A ordem passou a ser identidade → sessão de identidade → organização → conta. K-2 e K-3 exercitam o caso.
2. **Marca de step-up ou de recuperação no futuro** (§13, item 8). A Platform conseguia manter aberta para sempre a janela da DC-17.13. O banco agora recusa; D-9 testa.
3. **Contagem de tabelas de tenant** (§13, item 1): são 43, não 39. Erro do desenho, apanhado pela invariante na primeira aplicação.

## 4. Decisões que aguardam conferência (§13 do desenho)

| Item | Decisão |
|---|---|
| 4 | `UPDATE` para estado terminal não passa pela trigger de recusa. É o que permite o fechamento da DC-17.12 e o reset da 016 em conta vinculada |
| 5 | `identity_create_organization` é um **segundo caminho** de criação de organização, além do `provision_organization`. Reaproveita `provision_resource_limit` e `provision_first_org_admin` com `EXECUTE` novo para o dono |
| 5a | **Limites da organização criada por cadastro**: os do bootstrap interno (1–3 finitos em zero, 4–5 sem limiar). É decisão de **produto** e está provisória |
| 9 | A Platform tem `SELECT` nas tabelas de identidade e escrita por coluna. A matriz completa é a `m_esperado.txt` |

## 5. Consequências e pendências declaradas

- **Fixtures Java**: inserir em `user_sessions` sem `nekkora.organization_id`, mesmo como superusuário, passa a falhar. As bases de teste Java podem precisar de ajuste quando a camada Java entrar.
- **`ops/homologacao/implantacao/elevacao.txt`** não inclui a 017. Acrescentá-la é da etapa de ambiente.
- **Ordem 016 × 017** em base persistente continua em aberto. As duas instalam juntas.
- **DMARC** (`dmarc@nekkora.com`) fica para a etapa de configuração do e-mail, como combinado.

## 6. Como reproduzir

```sh
db/tests/identidade-017/run.sh
```

Cerca de 4 minutos. A saída fica em `db/tests/identidade-017/saidas/<data>_nekkora-id017/`, e o resumo em `resumo.txt`.
