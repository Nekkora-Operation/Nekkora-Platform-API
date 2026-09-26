# Suíte de validação da 013 (portal externo, decisão 8.2)

Valida `db/migrations/013_portal_externo.sql` com os **papéis reais**, numa stack PostgreSQL 16 **isolada**, a partir de instalação limpa (001–013 pelo entrypoint). Base: `docs/relatorios/2026-09-25-desenho-portal-externo-8-2.md` (§7) e a missão de 2026-09-26.

```
db/tests/portal-013/run.sh
TESTE_PROJETO=nekkora-portal-013b TESTE_PORTA=55515 db/tests/portal-013/run.sh   # outro nome/porta
```

Requisitos: Docker com Compose, Git Bash (Windows) ou bash (Linux/macOS). Não rode perto de 00:00 UTC.

## Isolamento

- Projeto, container, volume, rede e porta próprios (padrão `nekkora-portal-013`, porta 55513). O runner **aborta** se algum já existir, se a porta estiver em uso ou se o nome for o da stack principal (`nekkora-platform`, `nekkora-postgres`, porta 5432).
- Nunca executa `down -v`, `docker system prune` nem nada sobre recursos que não criou. A stack fica no ar no fim, e o comando de remoção é impresso.
- `cluster_name = nekkora-portal-013-isolada`. Os scripts de preparação administrativa recusam rodar sem ele.

## Papéis

Cada script declara o seu papel e confere `current_user`/`session_user`. As chamadas do portal usam **`nekkora_portal` com LOGIN real** (`psql -U nekkora_portal` pelo socket do container), sem superusuário e sem `SET ROLE`, e sempre **por bind** (`\bind … \gset`, protocolo estendido como o pgJDBC). O token vai só como parâmetro.

| Papel | Uso |
|---|---|
| `nekkora` (superusuário de teste) | Catálogo, base de referência, fixture administrativa (bootstrap, ajuste de `last_seen_at`), verificações de estado, coordenador/observador de concorrência, controle positivo de log, mutações. **Nunca** é usado como evidência de isolamento do portal |
| `nekkora_provisioning` | Organizações da fixture (`provision_organization`) |
| `nekkora_platform` | Fixture de produto; revogações e suspensões dos testes de estado e concorrência |
| `nekkora_ingestion` | Eventos e logs da fixture (barreira real) |
| `nekkora_portal` | Todos os testes de autorização, isolamento, troca de contexto, sessão, allowlist, paginação e logs |
| `nekkora_portal_owner` | Só em `t05b_owner.sql`, via `SET ROLE` declarado (é NOLOGIN). Prova o limite por coluna do owner, não o isolamento do portal |

## Ordem

1. **Instalação:** entrypoint com as 13 migrations, sem ERROR. Desde 2026-09-26, 001–013 são **escolhidas explicitamente** e montadas a partir de `saidas/<execução>/migracoes_montadas` (`TESTE_MIGRACOES`); a 014 e as posteriores não entram. As asserções não mudaram.
2. **Catálogo:**
   - `t00_catalogo` confere o contrato exato;
   - `nekkora_ref` recebe 001–012 no mesmo cluster, e o diff de catálogo contra a base com a 013 precisa ter **só acréscimos relacionados ao portal**;
   - os blocos de invariantes (012 §15 e 013 §8) são reexecutados.
3. **Fixture:** bootstrap, `f01`–`f04`.
4. **Bloco só do portal**, entre dois snapshots de conteúdo de todas as tabelas (`s_estado`, sem `user_sessions.last_seen_at`), que precisam ser **idênticos**:
   - `t05` privilégio mínimo;
   - `t05b` owner;
   - `t01` isolamento e escopo;
   - `t03` troca de contexto;
   - `t06` allowlist e paginação;
   - `t08` robustez;
   - `t04*` estados, sessão e toque.
5. **Logs:**
   - `t09a`/`t09b`: configurações travadas;
   - PGOPTIONS na conexão;
   - `t09c`: token-marcador **ausente** do log do servidor;
   - `t09d`: controle positivo **presente**.
6. **Reativação:** `t10a`–`t10c`.
7. **Concorrência:**
   - 7a: revogação de concessão contra uma transação aberta;
   - 7b: revogação da sessão espera a requisição;
   - 7b-reverso;
   - 7c: variante de pausa instalada só na base de teste; controle mais 6 mudanças de estado entre a resolução e a leitura.
8. **Mutações** (testes do próprio runner, saídas em `mutacoes/`). Cada variante errada é aplicada, o teste correspondente **precisa falhar** com a mensagem esperada, a função original é restaurada (impressão digital conferida) e o teste é repetido com sucesso:
   - `m1a`: resolução confia em GUC de usuário → `t03`;
   - `m1b`: função usa o `fn_organization_id` do chamador → `t03`;
   - `m2`: leitura sem reconferência de conta/vínculo/cliente/organização → 7c;
   - `m3`: toque antes da validação → `t04a`;
   - `m4`: papel sem `log_error_verbosity = terse` → busca no log.
9. **Final:**
   - catálogo idêntico ao da instalação (variantes removidas);
   - invariantes;
   - varredura de erros;
   - **nenhum token de sessão** em nenhuma saída nem no log do servidor.

## Limites conhecidos

- A restauração de `nekkora.fn_organization_id` no ramo `EXCEPTION` das funções não é observável isoladamente de fora: o rollback da (sub)transação que recebe o erro também a desfaz. O teste prova o efeito observável.
- As asserções usam funções em `pg_temp` (privilégio `TEMP` de `PUBLIC`), como a suíte da 012. Se `TEMP` for revogado, o harness precisa de outro meio.
- ~~Com a 013 em `db/migrations/`, a suíte histórica `db/tests/seguranca-012/` deixa de passar neste checkout.~~ Resolvido em 2026-09-26: as duas suítes montam uma lista explícita de migrations, sem mudança nas asserções, e voltaram a passar (execuções oficiais registradas em `docs/relatorios/2026-09-26-validacao-api-v1.md`).
- A suíte SQL não prova nada sobre o processo Spring (log de exceções, `log_parameter_max_length_on_error` na conexão, cookies). `t09c` mostra que o CONTEXT **enviado ao cliente** contém o valor de bind quando o próprio papel liga o parâmetro.
