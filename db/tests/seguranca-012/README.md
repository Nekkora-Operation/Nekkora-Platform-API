# Validação da 012 — segurança do PostgreSQL e RLS

Testes reproduzíveis de `db/migrations/012_security_rls.sql`, executados com os papéis reais. O relatório está em `relatorios/2026-09-25-validacao-012-security-rls.md`, fora deste repositório.

## Como rodar

```bash
db/tests/seguranca-012/run.sh
# se o projeto/volume padrao ja existir:
TESTE_PROJETO=nekkora-seg-2 TESTE_PORTA=55451 db/tests/seguranca-012/run.sh
```

Requisitos: Docker com Compose e um shell POSIX (Git Bash no Windows). Não é preciso `psql` local.

O `run.sh`:
- sobe uma stack **nova e isolada** (projeto, container, volume e porta próprios), com `cluster_name=nekkora-regressao-isolada`, e aplica 001–012 pelo entrypoint. Desde 2026-09-26 as 12 migrations são **escolhidas explicitamente** e copiadas para `saidas/<execução>/migracoes_montadas` (variável `TESTE_MIGRACOES`), e esse diretório é montado no entrypoint; migrations posteriores do repositório (013, 014…) não entram. As asserções não mudaram. Aborta se não forem exatamente 12 migrations ou se houver `ERROR`/`FATAL`;
- **recusa** a stack principal (`nekkora-platform`), a porta 5432 e volume ou container já existentes;
- nunca executa `down -v` nem limpeza global. A stack fica no ar ao final, e o comando de remoção é impresso;
- roda cada script com o papel indicado abaixo, conectando **diretamente** com ele (nunca por `SET ROLE` a partir do superusuário, exceto no informativo `11c`);
- **aborta diante de qualquer resultado inesperado.** Todo script recebe, na mesma sessão, `sql/_asserts.inc.sql`, que liga `ON_ERROR_STOP` e define:
  - `pg_temp.afirma(condição, descrição)`: contagens, igualdades e estados;
  - `pg_temp.espera_erro(sql, SQLSTATE, trecho)`: uma operação proibida precisa **falhar** com aquele SQLSTATE e aquele trecho de mensagem. Se tiver sucesso, ou falhar por outro motivo, o script aborta.
  
  O erro esperado é registrado como `NOTICE` (`rejeitado como esperado […]`).
- **concorrência:** as duas sessões de cada corrida são aguardadas e verificadas **separadamente**. O observador afirma que a sessão certa estava em espera de lock, bloqueada pela outra. Os ids devolvidos a A e B são comparados.
- rede de segurança final: nenhuma saída pode conter erro de sintaxe, variável não substituída ou `FALHA DE ASSERCAO`.

Não rode perto de 00:00 UTC.

## Scripts, na ordem

| Arquivo | Papel | O que valida |
|---|---|---|
| `00_catalogo_vs_matriz.sql` | `nekkora` | Diferença simétrica catálogo × matriz aprovada: privilégios de tabela (145), de coluna (15) e de função (12 funções). Também atributos dos papéis, RLS por tabela, políticas, `search_path` e privilégios padrão |
| `db/bootstrap/001_organizacao_interna_nekkora.sql` (saída `01_bootstrap.txt`) | `nekkora` | **Procedimento inicial**, o único passo administrativo de dados: organização interna + primeiro usuário staff + cinco limites explícitos, numa transação. Não podem nascer por `provision_organization`, porque o responsável precisa existir antes. O runner roda três vezes: sem variáveis (deve falhar), real, repetição (deve ser recusada) |
| `02_provisionamento.sql` | `nekkora_provisioning` | Validação do array (9 casos), atomicidade, criação de A e B com 5 limites, duplicidade, limite/retenção avulsos, acesso direto negado |
| `03_verifica_provisionamento.sql` | `nekkora` | Efeito real do 02, visto sem RLS |
| `04_platform_setup.inc.sql` (A e B) | `nekkora_platform` | Fluxos de produto sob RLS: identidade, credencial, workspace, parâmetro/secret (auditoria), Worker (capacidade), Agent, associações, automação publicada, execução até RUNNING, agendamento/revisão |
| `05b_bootstrap_desvio_platform.sql` | `nekkora_platform` | Bootstrap com responsável nulo fora da função: rejeitado pela checagem de `current_user` |
| `05_bootstrap_org_admin.sql` | `nekkora_provisioning` | `provision_first_org_admin`: vínculo de outra organização, atomicidade, caminho autorizado, segundo bootstrap |
| `05c_verifica_bootstrap.sql` | `nekkora` | Uma atribuição e um evento por organização |
| `06_platform_isolamento.sql` | `nekkora_platform` (uma conexão) | A × B, `WITH CHECK`, contexto ausente/vazio/malformado, reutilização de conexão, `SET` de sessão (informativo), `fn_organization_id` falsificado, operações negadas, `set_schedule_status`, desvio por tabela temporária |
| `07_ingestao.sql` | `nekkora_ingestion` | Ingestão A/B, sem contexto, organização divergente, barreira direta, restauração do contexto de função, operações negadas |
| `08_scheduler.sql` | `nekkora_scheduler` | Acesso direto negado, materialização idempotente, referências cruzadas, processamento pelo ponto de entrada de 3 parâmetros, organização divergente |
| `09a/09b/09c` | `platform` / `scheduler` / `platform` | Suspensão com ocorrência pendente (o id é passado pelo `run.sh`), processamento pós-suspensão, retomada |
| `10_retencao.sql` | `nekkora_retention_executor` (uma conexão) | Reconsolidação sob RLS, guard de categoria entre funções com `fn_organization_id` mudando, reset, save/restore do contexto, regras, operações negadas |
| `11a_fixture_historica.sql` | `nekkora` | **Preparação artificial de dados históricos** (mesmo procedimento da regressão da renomeação, com trava de `cluster_name`) |
| `11b_fechamento_historico.sql` | `nekkora_retention_executor` | `close_usage_day` sob RLS com registros das duas organizações no mesmo dia |
| `11c_risco_sem_contexto.sql` | `nekkora` → `SET ROLE nekkora_retention_owner` | Informativo: sem `fn_organization_id`, o owner vê 0 linhas sem erro. É o motivo do contexto de função |
| `conc1_*` + `conc_observador.sql` | `nekkora_scheduler` ×2 + `nekkora` | Materialização concorrente da mesma identidade |
| `conc2_*` + `conc_observador.sql` | `nekkora_ingestion` + `nekkora_retention_executor` + `nekkora` | Reconsolidação × inserção ainda não confirmada |
| `99_resumo.sql` | `nekkora` | Agregados × persistidos, execuções, ocorrências, `purge_runs`, triggers, `nekkora_app` |

## Saídas

- `saidas/2026-09-25T184124Z_nekkora-seg-012_INVALIDA-fixture-s02/`: primeira execução, **inválida**. O `scheduled_for` truncado ao segundo caiu antes do `resumed_at`, S05/S06 falharam por variável não definida, e o runner da época não detectou. Mantida como evidência do defeito que levou às travas atuais.
- `saidas/<timestamp>_<projeto>/`: execuções válidas.
  - `2026-09-25T223250Z_nekkora-seg-012c`: **referência atual**, com asserções que abortam (após a revisão);
  - `2026-09-25T191906Z_nekkora-seg-012b` e `2026-09-25T184518Z_nekkora-seg-012`: anteriores à revisão, com resultados só impressos, conferidos manualmente.
- `testes-do-runner/saidas/<timestamp>/`: os 7 casos de defeito deliberado, cada um com o log do runner, as saídas da suíte até a falha e, nos casos m4, a saída do bloco de invariantes da seção 15.

## Verificação de instalação limpa (`instalacao-limpa/`)

`verifica.sh <container> <org_interna_uuid> <staff_uuid>` roda, numa base **recém-instalada** (001–012 pelo entrypoint e procedimento inicial de `db/bootstrap` já aplicado), uma verificação curta com os papéis reais: catálogo × matriz, `provision_organization`, fluxo permitido do `platform`, isolamento frente à organização interna, contexto ausente e bootstrap de ORG_ADMIN. **Cria dados** (uma "Organização de Verificação"): use só em base nova, nunca com dados reais.

`instalacao-limpa/saidas/`: recriação da base principal local em 2026-09-25 (identificação do projeto e do volume antes e depois, bootstrap, verificação). Valida **instalação limpa**; a atualização de uma base populada não foi exercitada.

## Testes do próprio runner (`testes-do-runner/`)

`testes-do-runner/executa.sh` introduz defeitos **de propósito**, cada um numa stack nova e isolada (removida ao final do caso), e **exige** que a suíte falhe com a mensagem esperada. Nos casos de concessão indevida, exige também que o bloco de invariantes da seção 15 da 012, extraído da própria migration, aborte na base mutada.

| Caso | Defeito introduzido | Onde precisa falhar |
|---|---|---|
| m1 | `GRANT SELECT ON organization_usage_daily TO nekkora_platform` | `00`: privilégio de tabela além da matriz |
| m2 | trigger de bootstrap desabilitada (não muda o catálogo de privilégios) | `05b`: a operação negativa teve sucesso |
| m3 | só a sessão A da concorrência 1 falha (`TESTE_FORCAR_FALHA_A=1`) | runner: "sessão A falhou" |
| m4a | `GRANT nekkora_scheduler_owner TO outro_servico` (papel fora do prefixo) | `00` e seção 15: membership |
| m4b | `GRANT papel_qualquer TO nekkora_platform` (sentido inverso) | `00` e seção 15: membership |
| m4c | `GRANT CREATE ON SCHEMA public TO nekkora_platform` | `00` e seção 15: `CREATE` efetivo |
| m4d | `GRANT CREATE ON DATABASE nekkora TO nekkora_retention_owner` | `00` e seção 15: `CREATE` efetivo |

Os ganchos `TESTE_MUTACAO` e `TESTE_FORCAR_FALHA_A` do `run.sh` existem só para esses testes e nunca são usados numa execução normal.

**Proteção operacional do `executa.sh`:** os nomes das stacks são fixos (`nekkora-mut-<caso>`).
- Se **qualquer** container, volume ou rede com esses nomes já existir, o script aborta antes de executar qualquer caso (exit 2) e não cria, altera nem remove nada. A mesma checagem é repetida antes de cada caso.
- Verificação de invariantes e limpeza só atuam sobre recursos **comprovadamente criados pelo caso atual**: rótulo `com.docker.compose.project` do caso **e** criação posterior ao início do caso. Qualquer outro recurso é preservado e reportado.
- Teste direcionado: `testes-do-runner/saidas-protecao/`.
- **Limitação:** execução única. Rótulo + data de criação não provam autoria exclusiva se dois `executa.sh` rodarem ao mesmo tempo com os mesmos nomes; paralelismo exigirá um identificador exclusivo por execução.
