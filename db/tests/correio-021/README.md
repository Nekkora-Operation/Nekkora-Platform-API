# Suíte de banco da migration 021 (fila persistente de e-mail)

Valida a `db/migrations/021_correio_fila.sql`, que é **candidata**. Relatório: `docs/relatorios/2026-10-22-identidade-002-email-real.md`.

Roda em quatro clusters PostgreSQL 16 descartáveis e isolados (o caso D usa também o Flyway da imagem do runner, no namespace de rede do próprio cluster):
- containers próprios, com `--network none`, sem porta publicada e sem volume nomeado;
- nunca toca a stack principal, o hml03 ou o ambiente preservado.

```sh
db/tests/correio-021/run.sh
TESTE_PROJETO=nekkora-m021b db/tests/correio-021/run.sh
```

## Modelo de aplicação

As janelas de SUPERUSER continuam só em 011–013 e 016–020, e BYPASSRLS só na 014. A **021 não precisa de janela**: a tabela, a trigger e a função de proteção pertencem ao migrador, que é dono do banco. A suíte confere `aplicada versao=021 elevada=nao`.

## A. Instalação limpa 001–021

Depois da retirada das janelas, a suíte roda as invariantes da 012, 013, 014 (adaptada), 016, 017 e 019 (as duas com as adaptações da 020), 020 e 021 (§5).

As da 021 conferem:
- só a Platform tem acesso **efetivo** à tabela: nenhuma concessão (na tabela ou em coluna, inclusive a PUBLIC) além do dono e da Platform, e nenhum papel que não seja superusuário participa de papel com acesso. A herança conta, inclusive a de papéis predefinidos do PostgreSQL. A única exceção é a identidade de backup declarada (`backup_nekkora`), só leitura e só por `pg_read_all_data` (revisão 3, caso C);
- a Platform não tem UPDATE da origem do trabalho nem da chave do evento, e só apaga dentro da retenção (a trigger recusa outros DELETEs);
- a tabela não tem `organization_id` (não é tabela de tenant; a contagem de 43 da 017 continua valendo);
- as duas triggers estão ligadas.

Marcador `versao=021`.

## B. Atualização de base populada 020 → 021

| Caso | O que confere |
|---|---|
| B2 | 021 aplicada **sem** janela. Dados idênticos (80 tabelas). Diff de catálogo **exatamente** `esperado/B22_diff_esperado.txt`: 113 linhas, todas acréscimos e todas de `mail_outbox` ou de sua função de proteção (colunas, CHECKs, chave do evento UNIQUE, índices, triggers, privilégios da Platform: SELECT e DELETE na tabela, 13 colunas de INSERT e 9 de UPDATE). Catálogo igual ao da instalação limpa. Invariantes. Marcador `versao=021` |
| B3 | Reaplicar a 021 é recusado pela pré-condição (a tabela já existe); nada muda |

## C. Identidade de backup e acesso efetivo (revisão 3)

A homologação tem `backup_nekkora` (LOGIN, BYPASSRLS) membro de `pg_read_all_data` (`ops/homologacao/IMPLANTACAO.md`, §1).

- **Revisão 1** (`2aeaf4b9…`): a invariante contava esse SELECT herdado como acesso, e a 021 foi recusada na implantação do `ensaio-api-v1-002`.
- **Revisão 2** (`bf85b95b…`): passou a conferir só a ACL e a participação no dono e na Platform. Com isso, deixava de ver a herança por outros papéis.
- **Revisão 3** (atual): volta ao acesso **efetivo** (`has_table_privilege` e `has_any_column_privilege`, que contam qualquer herança). Um papel é recusado quando participa (MEMBER: herda ou pode `SET ROLE`) de qualquer papel com acesso. A única exceção é declarada: a identidade de backup, só leitura e só por `pg_read_all_data`.

| Caso | O que confere |
|---|---|
| C0 | Reprodução: 001–020 com a identidade de backup e a **revisão 1** (lida de `bf977d7`, sha256 conferido) é recusada com `021: papel com acesso a mail_outbox alem da Platform: backup_nekkora`; nada muda (catálogo e migrador 0\|0\|0) |
| C1 | A revisão atual, na mesma base, aplica sem janela; o backup continua com a leitura herdada; invariantes depois da retirada; catálogo igual ao da instalação limpa |
| C2 | 16 contraprovas da invariante, cada uma em `BEGIN … ROLLBACK`, com os papéis que a recusa precisa nomear. Detalhe abaixo |
| C3 | A invariante da **revisão 2** (lida de `a1ab358`, sha256 conferido) **aceita** um papel qualquer membro de `pg_read_all_data`. É a lacuna que a revisão 3 fecha (C2 `heranca_pg_read_all_data`) |

As 16 contraprovas do C2:
- **controle:** passa;
- **concessão direta:** recusada para o backup (leitura e escrita), para o Portal (coluna) e para PUBLIC;
- **herança não autorizada, sem nenhuma entrada nova na ACL da tabela:** recusada para membro de `pg_read_all_data`, membro de `pg_write_all_data`, membro do backup, cadeia até papel predefinido (o grupo e o membro), cadeia até concessão de coluna (o grupo e o membro), membro da Platform, `SET ROLE` sem herança na Platform (`WITH INHERIT FALSE`) e membro do dono;
- **exceção estreita:** o backup é recusado se ganhar `pg_write_all_data`, se participar de outro papel com acesso ou se participar da Platform.

## D. Base com uma revisão anterior da 021 já aplicada

Uma base em que o Flyway registrou a 021 com outra revisão guarda no histórico o checksum daquela revisão. O caso usa o Flyway real (a imagem do runner), com o migrador como usuário e a configuração do runner (`validateOnMigrate=true`, sem baseline no migrate, sem clean).

| Caso | O que confere |
|---|---|
| D1 | A revisão 1 só aplica numa base **sem** a identidade de backup, fora da topologia suportada. O histórico fica `020` (baseline da base descartável) e `021` com o checksum da revisão 1 |
| D2 | O esquema resultante é **idêntico** ao da revisão atual (instalação limpa A), fora a tabela de histórico. As revisões só diferem na verificação |
| D3 | O Flyway com a revisão atual **recusa** a base por `checksum mismatch` da 021, tanto no `migrate` quanto no `validate`, antes de aplicar qualquer coisa. Histórico e catálogo ficam intactos. É o comportamento do runner, que para sem `repair` |
| D4 | A base da revisão 1 satisfaz as invariantes da revisão atual, sem e com `backup_nekkora` em `pg_read_all_data`. A invariante da revisão 1, na mesma base, recusa o backup |
| D5 | Demonstração, **só nesta base descartável**, do realinhamento revisado: `flyway repair` depois de D2 e D4, `validate` OK, só o checksum da 021 muda no histórico, esquema intacto. O runner nunca faz isso (`IMPLANTACAO.md`, item 3); usar numa base preservada é decisão da responsável |

O comportamento é coberto por `MailOutboxIT` (nekkora-platform), com o papel real `nekkora_platform`:
- intenção gravada junto do ato, deduplicação pela chave do evento e fila cifrada;
- tentativas e desfechos;
- posse vencida depois de queda;
- prazo vencido;
- despachos concorrentes;
- posse conferida antes de cada envio;
- proteções da trigger e retenção de 30 dias.

As saídas ficam em `saidas/` (não versionadas).

**Windows (Git Bash):** a suíte passa caminhos de container em `-e` e em `docker exec`. Sem exclusão, o Git Bash os converte (`/nekkora-migracoes` vira `C:/Program Files/Git/nekkora-migracoes`). Rode com:

```sh
MSYS2_ARG_CONV_EXCL="NEKKORA_;/tmp/;/nekkora;/docker-entrypoint-initdb.d;/flyway" db/tests/correio-021/run.sh
```
