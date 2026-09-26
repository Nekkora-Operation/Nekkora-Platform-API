# Ensaio de atualização populada: 001–012 → 013 → 014 → 015 (Flyway, sem baseline automático)

Fase 3 da missão da API v1. Segue o procedimento do registro de decisões 8.6/8.7/8.10, §6.2.

```sh
db/tests/atualizacao-populada/run.sh
ENSAIO_PROJETO=nekkora-atualizacao-b ENSAIO_N_EXEC=3000 db/tests/atualizacao-populada/run.sh
```

Requisitos: Docker, Git Bash/Linux/macOS e Python 3. As imagens `postgres:16-alpine` e `flyway/flyway:11-alpine` são baixadas se faltarem.

## Etapas

1. **Origem:**
   - 001–012 pelo entrypoint (o mesmo caminho da stack principal);
   - massa gerada por `sql/p01_popula.sql` (superusuário; declarada como preparação), com os triggers de 001–012 valendo;
   - conteúdo: 3 organizações, 303 usuários, 660 sessões (300 ativas, 180 expiradas sem revogação, 180 revogadas), 120 concessões, 4 500 execuções em todos os estados terminais e 2 250 eventos.
2. **Cópia física:** `pg_basebackup` local na origem, levado ao `PGDATA` de outro container, o equivalente a copiar o volume. O runner confere que dados e catálogo são idênticos aos da origem.
   - A restauração **lógica** (`pg_dump`/`pg_restore`) também é tentada, num banco descartável, e o resultado vai para `03b_restauracao_logica.txt`.
   - Esse backup é **anterior à 015** e falha, como esperado: o `pg_restore` esvazia o `search_path`, e `is_valid_command_contract` (005) chama `is_valid_relative_entrypoint` sem schema. Foi o achado que motivou a 015; a correção e o procedimento para backups antigos estão em `db/tests/restauracao-015`.
3. **Antes do baseline:**
   - o catálogo da cópia é comparado com uma instalação limpa 001–012 no mesmo cluster, e o diff precisa estar vazio;
   - não pode haver histórico de migration;
   - a aprovação é registrada. Neste ensaio quem aprova é o runner, porque a cópia é descartável; em produção a aprovação é humana.
4. **Flyway:**
   - `migrate` sem baseline **precisa falhar** ("non-empty schema"), sem deixar histórico nem mudar o catálogo;
   - depois vêm `baseline -baselineVersion=012`, `migrate` (013, 014, 015), `validate` e `info`;
   - configuração: `sqlMigrationPrefix` vazio, separador `_` (os arquivos são `NNN_nome.sql`), `executeInTransaction=false` (cada migration já é atômica pelo próprio `BEGIN`/`COMMIT`), `postgresql.transactional.lock=false`, `baselineOnMigrate=false`.
5. **Depois da atualização:**
   - **catálogo** igual ao de uma instalação limpa 001–015, exceto a tabela `flyway_schema_history`. De 012 para 015, a única linha de 001–012 alterada tem que ser o `search_path` de `is_valid_command_contract` (asserção);
   - **dados:** cada linha anterior permanece idêntica, exceto o previsto pela 014 (a permissão `identity.mfa.reset`, a concessão dela ao ORG_ADMIN e `revoked_at` preenchido nas sessões que o tinham nulo). A comparação de `user_sessions` é linha a linha: só `revoked_at` muda, e só de nulo para preenchido;
   - **papéis reais:** `v01` (`nekkora_platform`) confirma que a 014 recusa sessão sem marcador e reativação de sessão revogada, pela mensagem da própria trigger, e cadastra fator e sessão nova para uma conta externa pré-existente. `v02` (`nekkora_portal`) confirma que a sessão pré-014 recebe NK401 e que a sessão nova enxerga a concessão e as execuções que já existiam;
   - **segredos:** nenhum token ou CSRF nas saídas nem no log do servidor;
   - **restauração lógica da cópia atualizada** (backup gerado depois da 015): `pg_restore --exit-on-error` passa, com contagens por tabela idênticas;
   - **origem intacta:** mesmos dados, mesmo catálogo, sem histórico do Flyway.

## Credencial

Neste ensaio o Flyway usa o superusuário do cluster descartável. A credencial de migration dedicada é um gate de produção.

Os instantâneos de dados são apagados ao final; os resumos ficam em `saidas/`. Os containers ficam no ar e o comando de remoção é impresso.
