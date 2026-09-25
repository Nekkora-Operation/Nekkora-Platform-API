# Regressão da renomeação Radiant → Nekkora (baseline 001–011)

Testes reproduzíveis da missão de regressão de 2026-09-25. O relatório completo está em `relatorios/2026-09-25-regressao-renomeacao-nekkora.md`, fora deste repositório.

## Como rodar

```bash
db/tests/regressao-renomeacao/run.sh
# ou, se o projeto/volume padrao ja existir:
REGRESSAO_PROJETO=nekkora-regressao-2 REGRESSAO_PORTA=55433 db/tests/regressao-renomeacao/run.sh
```

Requisitos: Docker com Compose e um shell POSIX (Git Bash no Windows). Não é preciso ter `psql` local; tudo roda via `docker exec`.

O `run.sh`:
- sobe uma stack **nova e isolada**: projeto `REGRESSAO_PROJETO`, container `<projeto>-postgres`, volume `<projeto>_nekkora_postgres_data` e porta `REGRESSAO_PORTA`. Usa o `docker-compose.yml` da raiz sem alteração, mais o `compose.regressao.yml`;
- **recusa** rodar se o volume ou o container já existirem, se o projeto for `nekkora-platform` ou se a porta for 5432;
- nunca executa `down -v` nem limpeza global. A stack de teste fica no ar ao final, e removê-la é decisão de quem rodou;
- grava cada saída em `saidas/<timestamp-utc>_<projeto>/`.

Não rode perto de 00:00 UTC: vários cenários dependem de "hoje" em UTC.

## Scripts, na ordem de execução

| Arquivo | Papel | Teste |
|---|---|---|
| `00_t01_identidade.sql` | `nekkora` | T01: identidade da conexão. O `run.sh` também confere as 11 migrations no log do entrypoint e o healthcheck |
| `01_t02_catalogo.sql` | `nekkora` | T02: nome antigo em definições instaladas. O `run.sh` varre arquivos e objetos Docker |
| `02_t03_privilegios.sql` | `nekkora` | T03: papéis, memberships, owners, ACLs de tabela, coluna e função, e literais |
| `03_t04_fluxo.sql` | `nekkora` | T04: organização + 5 limites, identidade, workspace, Worker/Agent, associações, automação publicada, execução até RUNNING. Também exercita o GUC `nekkora.actor_membership_id` |
| `04_t02_guc_antigo.sql` | `nekkora` | T02: chamador com o GUC antigo `radiant.actor_membership_id` **falha fechado** |
| `05_t06a_ingestao_running.sql` | `nekkora_app` | T06: ingestão durante RUNNING. O `received_at` enviado pelo chamador é ignorado |
| `06_t04b_finalizar.sql` | `nekkora` | T04: SUCCEEDED, terminal congelado, transição inválida, cancelamento; encerra a associação Worker–Agent |
| `07_t06b_atrasada_reenvio.sql` | `nekkora_app` | T06: entrega atrasada, reenvio idêntico e divergente, colisão de `sequence`, origem inválida |
| `08_t05_agendamento.sql` | `nekkora` | T05: ocorrência processada e reprocessada |
| `09_t05b_suspensao.sql` | `nekkora` | T05: suspensão com ocorrência pendente e retomada |
| `10_t07_negativos_app.sql` | `nekkora_app` | T07: UPDATE/DELETE/TRUNCATE e outras escritas indevidas |
| `11_t07b_camada_trigger_admin.sql` | `nekkora` | T07, informativo: segunda camada (triggers), tudo em `ROLLBACK` |
| `12_t09_prep_org_b.sql` | `nekkora` | fixture: segunda organização |
| `13_t09_t10_executor.sql` | `nekkora_retention_executor` | T09 (guard, mesma conexão do início ao fim) e T10 (expurgo negado) |
| `14_t08_prep_1_execucao.sql` | `nekkora` | fixture: execução dedicada, pelo caminho normal |
| `15_t08_prep_2_historico.sql` | `nekkora` | **preparação artificial de dados históricos** (ver abaixo) |
| `16_t08_prep_3_verifica.sql` | `nekkora` | triggers reabilitadas, origem dos registros históricos conferida, valores esperados |
| `17_t08_fechamento.sql` | `nekkora_retention_executor` | T08: fecha D = 2026-09-24 |
| `18_t08_congelado_app.sql` | `nekkora_app` | T08: dia fechado imutável; ingestão nova cai em hoje |
| `19_t08_congelado_admin.sql` | `nekkora` | T08: imutável também para o superusuário; comparação agregado × persistidos |
| `conc_a_ingestao.sql` / `conc_b_fechamento.sql` / `conc_c_observador.sql` | `nekkora_app` / `nekkora_retention_executor` / `nekkora` | Concorrência: A segura `FOR SHARE` na barreira de D2 = 2026-09-23, B chama `close_usage_day` e espera, C observa `pg_stat_activity`/`pg_blocking_pids` durante a espera |
| `20_resumo.sql` | `nekkora` | Resumo: agregados × persistidos, execuções, `purge_runs`, ACL do expurgo, triggers |

Papéis restritos sempre conectam diretamente (`psql -U <papel>`), nunca via `SET ROLE` a partir do superusuário. T04 e T05 dependem do papel administrativo porque nenhum papel restrito da baseline tem privilégio nessas tabelas. Isso valida fluxo, não isolamento nem RLS.

## Preparação artificial de dados históricos (`15_t08_prep_2_historico.sql`)

A ingestão normal carimba `received_at` com o horário atual, e `close_usage_day` só fecha dias estritamente passados. Para ter dias passados com registros, o script faz tudo numa única transação:
1. cria as linhas-barreira OPEN de D e D2;
2. desabilita **só** `trg_execution_events_enforce_agent_origin` e `trg_logs_enforce_historical_origin`;
3. insere registros com `received_at` histórico;
4. roda `SET CONSTRAINTS ALL IMMEDIATE`, sem o qual o `ENABLE` falha com "pending trigger events" por causa da FK de expurgo adiada;
5. reabilita as duas triggers e só então faz o commit.

Limites dessa preparação:
- essas duas triggers também validam a origem (agent da alocação histórica, associação da execução, proibição de nascer expurgado). Durante a inserção, essas validações **não rodaram**. O `16_t08_prep_3_verifica.sql` confere por consulta que os registros as satisfazem; isso não é evidência de que elas executaram;
- **trava:** o script aborta se `cluster_name` do servidor não for `nekkora-regressao-isolada`, valor definido só pelo `compose.regressao.yml`. Na base principal ele recusa antes de qualquer escrita;
- as datas D e D2 são fixas (2026-09-24 e 2026-09-23). Em execuções futuras continuam sendo dias passados.

## Ocorrências intencionais do nome antigo nesta pasta

- `04_t02_guc_antigo.sql` usa `radiant.actor_membership_id` de propósito, para provar a falha fechada;
- `01_t02_catalogo.sql` e o `run.sh` procuram o termo;
- este README e as saídas o citam.

Nenhuma dessas é dependência operacional.

## O que estes testes não cobrem

- inclusão de uma inserção **ainda não confirmada** quando o fechamento começa (a sessão A só trava a linha, não insere);
- o laço de retentativa na virada real do dia;
- isolamento de privilégios de 001–010 e RLS (migration de segurança);
- ativação de expurgo.

## Saídas

- `saidas/2026-09-25_execucao-original/`: saídas brutas da execução original da missão, com os scripts ainda no scratchpad. Incluem as duas tentativas que falharam por fixture (`t05.txt`: suspensão com ocorrências no mesmo segundo; `t08_prep2.txt`: `ENABLE TRIGGER` com eventos pendentes) e as repetições corrigidas (`t05b.txt`, `t08_prep2_b.txt`, `t08_prep3_b.txt`). T01–T03 foram rodados inline nessa execução e não têm arquivo ali.
- `saidas/<timestamp>_<projeto>/`: execuções do `run.sh`.
