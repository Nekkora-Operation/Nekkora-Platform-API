# Suíte da migration 015 (restauração lógica)

Valida `db/migrations/015_restauracao_logica.sql`: um backup lógico (`pg_dump`) gerado depois da correção restaura por completo em outra base. Contexto e decisão: registro de decisões 8.6/8.7/8.10, revisão 4.1 (§1 e §6.3).

```sh
db/tests/restauracao-015/run.sh
TESTE_PROJETO=nekkora-restauracao-b ENSAIO_N_EXEC=3000 db/tests/restauracao-015/run.sh
```

## Por que a 015

O `pg_restore` executa o `COPY` com `search_path` vazio. O CHECK de `automation_versions` (versão publicada) e o de `executions.command_snapshot` chamam `is_valid_command_contract` (005). Essa função chama `is_valid_relative_entrypoint` sem schema e, com o `search_path` vazio, não a encontra.

`sql/c_funcoes_no_restore.sql` lista as funções que o PostgreSQL executa durante uma restauração: as chamadas em CHECK, expressões de índice e colunas geradas, com o fechamento transitivo pelo corpo. Das cinco listadas, só `is_valid_command_contract` chama outra função do schema sem qualificar. A 015 fixa o `search_path` dessa função e não mexe em mais nada.

## Etapas

Três clusters isolados (`postgres:16-alpine`), sem porta publicada:

1. **A:**
   - 001–012 pelo entrypoint e massa sintética (`db/tests/atualizacao-populada/sql/p01_popula.sql`: 3 organizações, 4 500 execuções, 2 250 eventos, 660 sessões), depois 013 e 014;
   - gera o **backup antigo** (`pg_dump -Fc --create`, antes da 015);
   - **controle negativo:** o backup antigo **precisa falhar** na restauração, com a mensagem de `is_valid_relative_entrypoint`;
   - aplica a 015;
   - **mutação:** sem o `search_path`, o bloco de invariantes da 015 **precisa** falhar (em transação revertida);
   - gera o **backup novo**.
2. **B:**
   - papéis restaurados (`pg_dumpall --roles-only`, sem o superusuário);
   - **restauração completa** do backup novo (`pg_restore --create --exit-on-error`).
   - Comparações A × B:
     - catálogo (`db/tests/portal-013/sql/c_listagem_catalogo.sql`);
     - papéis (atributos, `rolconfig`, pertinências, configurações por base);
     - dados linha a linha de **todas** as tabelas.
   - Verificações com os papéis reais:
     - `v01` (`nekkora_platform`): triggers da 014 e sessão nova com fator;
     - `v02` (`nekkora_portal`): sessão revogada → NK401; sessão nova lê a concessão e as execuções restauradas;
     - o CHECK corrigido depois da restauração: publicar uma versão com entrypoint válido passa (1 linha), e com `../fora.py` é recusado por `automation_versions_check3`.
3. **C:** procedimento para **backup antigo**, abaixo. O catálogo e os dados precisam ficar idênticos aos de A com a 015.

Tokens e CSRF são gerados por execução. O runner confere que nenhum deles aparece nas saídas nem nos logs dos três clusters.

## Procedimento para backup anterior à 015

Aplicar a 015 depois de uma restauração que já falhou não a conserta. O backup antigo precisa ser restaurado em seções, com a correção aplicada entre a estrutura e os dados:

```sh
pg_dumpall --roles-only ...                                      # papéis no cluster de destino (sem o superusuário)
pg_restore -d postgres --create --section=pre-data --exit-on-error backup_antigo.dump
psql -d nekkora -v ON_ERROR_STOP=1 -f db/migrations/015_restauracao_logica.sql
pg_restore -d nekkora --section=data      --exit-on-error backup_antigo.dump
pg_restore -d nekkora --section=post-data --exit-on-error backup_antigo.dump
```

A 015 é idempotente. Se o backup trouxer `flyway_schema_history` sem a 015, o próximo `flyway migrate` a registra, e o `ALTER` não muda nada.

Os containers ficam no ar ao final; o comando de remoção é impresso. Os instantâneos de dados são apagados; os resumos ficam em `saidas/`.
