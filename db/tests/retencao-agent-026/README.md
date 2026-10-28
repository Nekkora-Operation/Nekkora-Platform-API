# Suíte de banco da migration 026 (retenção e uso diário dos eventos do Agent)

Valida a `db/migrations/026_retencao_eventos_agent.sql`, que é **candidata**. Contrato: `docs/relatorios/2026-10-28-platform-agent-contrato.md`,
§8.1, §9 e §11. Decisão da titular: D-PA-6.

Roda em dois clusters PostgreSQL 16 descartáveis e isolados:
- containers próprios, com `--network none`, sem porta publicada e sem volume nomeado;
- nunca toca a stack principal nem os ambientes de homologação.

```sh
db/tests/retencao-agent-026/run.sh
TESTE_PROJETO=nekkora-c026b db/tests/retencao-agent-026/run.sh
```

**Windows (Git Bash):**

```sh
MSYS2_ARG_CONV_EXCL="NEKKORA_;/tmp/;/nekkora;/docker-entrypoint-initdb.d;/flyway" db/tests/retencao-agent-026/run.sh
```

## Modelo de aplicação

`db/bootstrap/aplicar_como_migrador.sh`: SUPERUSER em 011–013, 016–020, 022–024 e **026**; BYPASSRLS só na 014; a 025 sem janela.

**A 026 precisa da janela** (caso B0). Ela altera tabelas cujo dono é `nekkora_retention_tables_owner` (`organization_usage_daily` e
`organization_purge_runs`) e redefine as funções de manutenção cujo dono é `nekkora_retention_owner` (`close_usage_day`,
`reconsolidate_usage_day` e `purge_expired_records`). Ela não cria papel, dono, função SECURITY DEFINER nem concessão de EXECUTE.

## Casos

| Caso | O que confere |
|---|---|
| A | instalação limpa 001–026: a 025 `elevada=nao` e a 026 `elevada=SUPERUSER`; invariantes de 012 a 026 depois da retirada. Adaptações declaradas: as da suíte da 025 e, nas invariantes da 025, a política de contexto de função e o acesso de `nekkora_retention_owner` **só** em `agent_run_events`, 30 gatilhos no canal e a regra "tabelas vazias" (vale só na aplicação da 025) |
| B0 | a 026 **sem a janela**: recusada por privilégio (`must be owner of table organization_usage_daily`), sem efeito em dados e catálogo |
| B1 | função homônima da função de gatilho da 026: recusada pela pré-condição, sem efeito |
| B1b | a 026 falhando **no meio**: um gatilho homônimo do de redação faz a 026 falhar depois dos ALTER de catálogo, das colunas novas e do gatilho de `received_at`. Nada fica (uma transação) |
| B2 | base populada com a massa da 004–007, a MFA, 016–024 e o canal da 025 (registro e eventos): **nenhum dado anterior muda** (instantâneo das colunas anteriores idêntico); os eventos existentes ficam sem expurgo. O diff de catálogo é exatamente `esperado/B24_diff_esperado.txt` (40 linhas). O catálogo fica igual ao da instalação limpa, e as invariantes passam |
| B3 | reaplicar a 026 é recusado pela pré-condição, sem efeito |
| C1 | barreira: o evento inserido pela ingestão recebe `received_at` do servidor (o enviado é ignorado) e cria a linha-barreira do recurso 6 do dia (`OPEN`, sem consolidação). Evento não nasce expurgado. Outra organização não trava a barreira |
| C2 | fechamento do recurso 6 por `nekkora_retention_executor`: conta os eventos do Agent do dia e os bytes do envelope; dia de hoje recusado; ingestão sem EXECUTE. Reconsolidação do dia aberto. Guard de categoria única por transação |
| C3 | expurgo da categoria 3: nada com o dia aberto; com o dia fechado e a política de 1 dia vigente no recebimento, só os eventos daquele dia. **Lote respeitado** (2 de 3 e depois 1). Redige o envelope e os campos da etapa; a identidade, o `content_sha256`, a classificação e os instantes ficam; `agent_runs` não muda; auditoria em `organization_purge_runs`. Reenvio do mesmo `(run, sequence)` não duplica nem desfaz a redação; desfazer a redação é recusado |
| C4 | a redação é a única atualização: forma errada recusada mesmo para o dono das funções; `content_sha256` sem privilégio; dia aberto recusado; ingestão e Platform sem UPDATE; `nekkora_retention_executor` sem EXECUTE do expurgo |
| C5 | contraprovas das invariantes da 026, cada uma em `BEGIN … ROLLBACK`. O controle passa. São recusados: EXECUTE do expurgo concedido, EXECUTE de fechamento a outro papel, função de gatilho SECURITY DEFINER, política extra, UPDATE da Platform, privilégio do dono das funções além da redação, gatilho desligado e dono trocado de função de manutenção |

Última rodada: 13 recusas e 10 controles positivos em C, e 8 contraprovas em C5.

**Achado desta suíte (C3):** a forma da 011, `IN (SELECT … LIMIT n FOR UPDATE)`, passou do lote sob o plano de laço aninhado
que o planejador escolheu com a RLS. Foram 3 eventos expurgados com lote 2. A causa é a reexecução da subconsulta: as linhas já
alteradas pelo próprio comando são puladas pelo `LockRows`, e outras entram. A 026 seleciona o lote da categoria 3 uma vez
(CTE materializada). As categorias 1 e 2 mantêm o corpo da 012 e não têm EXECUTE concedido; a mesma correção fica recomendada
antes de qualquer ativação.

As rotas são cobertas pelos testes Java:
- `AgentGatewayIT.depoisDoExpurgoOReenvioContinuaDuplicadoOAckNaoVoltaEOutroConteudoEConflito` (`nekkora-agent-gateway`);
- `AgentAdminIT.expurgoDoServidorApareceComoRetencaoNuncaComoPerda` (`nekkora-platform`).
