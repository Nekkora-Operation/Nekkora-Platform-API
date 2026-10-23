# Suíte de banco da migration 021 (fila persistente de e-mail)

Valida a `db/migrations/021_correio_fila.sql`, que é **candidata**. Relatório: `docs/relatorios/2026-10-22-identidade-002-email-real.md`.

Roda em três clusters PostgreSQL 16 descartáveis e isolados:
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
- só a Platform tem acesso à tabela: nenhuma concessão (na tabela ou em coluna, inclusive a PUBLIC) além do dono e da Platform, e nenhum papel que não seja superusuário participa do dono ou da Platform. O acesso que vem de papéis predefinidos do PostgreSQL (`pg_read_all_data` do backup lógico) fica de fora: é decisão da topologia de implantação (revisão 2, caso C);
- a Platform não tem UPDATE da origem do trabalho nem da chave do evento, e só apaga dentro da retenção (a trigger recusa outros DELETEs);
- a tabela não tem `organization_id` (não é tabela de tenant; a contagem de 43 da 017 continua valendo);
- as duas triggers estão ligadas.

Marcador `versao=021`.

## B. Atualização de base populada 020 → 021

| Caso | O que confere |
|---|---|
| B2 | 021 aplicada **sem** janela. Dados idênticos (80 tabelas). Diff de catálogo **exatamente** `esperado/B22_diff_esperado.txt`: 113 linhas, todas acréscimos e todas de `mail_outbox` ou de sua função de proteção (colunas, CHECKs, chave do evento UNIQUE, índices, triggers, privilégios da Platform: SELECT e DELETE na tabela, 13 colunas de INSERT e 9 de UPDATE). Catálogo igual ao da instalação limpa. Invariantes. Marcador `versao=021` |
| B3 | Reaplicar a 021 é recusado pela pré-condição (a tabela já existe); nada muda |

## C. Identidade de backup da topologia de implantação (revisão 2)

A homologação tem `backup_nekkora` (LOGIN, BYPASSRLS) membro de `pg_read_all_data` (`ops/homologacao/IMPLANTACAO.md`, §1). Na revisão 1 (`2aeaf4b9…`), a invariante contava esse SELECT herdado como acesso, e a 021 foi recusada na implantação do `ensaio-api-v1-002`.

| Caso | O que confere |
|---|---|
| C0 | Reprodução: 001–020 com a identidade de backup e a **revisão 1** (lida de `bf977d7`, sha256 conferido) é recusada com `021: papel com acesso a mail_outbox alem da Platform: backup_nekkora`; nada muda (catálogo e migrador 0\|0\|0) |
| C1 | A revisão atual, na mesma base, aplica sem janela; o backup continua com a leitura herdada; invariantes depois da retirada; catálogo igual ao da instalação limpa |
| C2 | Contraprovas da invariante (cada uma em `BEGIN … ROLLBACK`): controle passa; recusam concessão explícita ao backup (leitura e escrita), concessão de coluna ao Portal, concessão a PUBLIC, papel membro da Platform e papel membro do dono |

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
MSYS2_ARG_CONV_EXCL="NEKKORA_;/tmp/;/nekkora;/docker-entrypoint-initdb.d" db/tests/correio-021/run.sh
```
