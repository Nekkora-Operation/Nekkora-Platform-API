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
- sobe uma stack **nova e isolada** (projeto, container, volume e porta próprios), com `cluster_name=nekkora-regressao-isolada`, e aplica 001–012 pelo entrypoint. Aborta se não forem exatamente 12 migrations ou se houver `ERROR`/`FATAL`;
- **recusa** a stack principal (`nekkora-platform`), a porta 5432 e volume ou container já existentes;
- nunca executa `down -v` nem limpeza global. A stack fica no ar ao final, e o comando de remoção é impresso;
- roda cada script com o papel indicado abaixo, conectando **diretamente** com ele (nunca por `SET ROLE` a partir do superusuário, exceto no informativo `11c`);
- **aborta** se um passo positivo falhar (esses passos ligam `ON_ERROR_STOP`) ou se qualquer saída tiver erro de sintaxe ou variável psql não substituída. Os cenários negativos rodam sem `ON_ERROR_STOP`, porque o erro esperado é a evidência.

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

## Verificação de instalação limpa (`instalacao-limpa/`)

`verifica.sh <container> <org_interna_uuid> <staff_uuid>` roda, numa base **recém-instalada** (001–012 pelo entrypoint e procedimento inicial de `db/bootstrap` já aplicado), uma verificação curta com os papéis reais: catálogo × matriz, `provision_organization`, fluxo permitido do `platform`, isolamento frente à organização interna, contexto ausente e bootstrap de ORG_ADMIN. **Cria dados** (uma "Organização de Verificação"): use só em base nova, nunca com dados reais.

`instalacao-limpa/saidas/`: recriação da base principal local em 2026-09-25 (identificação do projeto e do volume antes e depois, bootstrap, verificação). Valida **instalação limpa**; a atualização de uma base populada não foi exercitada.
