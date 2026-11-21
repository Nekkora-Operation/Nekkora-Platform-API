# Migration 072: resumo do dashboard por estado

Arquivo: `db/migrations/072_resumo_do_dashboard_por_estado.sql`
sha256: `c3c8a67cd2bb6f90eebd9ab8f34ffa8c5046b78be48eea1899421cabb02e1c82`
Origem: PLATFORM-FLUXO-002 (Clientes, API #115, mesclada em dev em 21/11/2026 no merge `e2e2ab33f61baea0688eee8c8d53cee8d52bfe68`).

## Propósito

A área do cliente mostra, por automação, um resumo das execuções do período. Na 071 a função `portal_client_summary` somava num único
campo `other` dois estados diferentes:

- **resultado desconhecido** (`unconfirmed`): a Platform não sabe como a execução terminou;
- **terminou sem confirmação de sucesso** (`no_error_signaled`): a execução terminou com código de saída zero, mas sem confirmação de
  sucesso.

Nenhum dos dois conta como "Concluída". A titular pediu que o cliente consiga distingui-los. A 072 recria a função devolvendo
`unconfirmed` e `no_error_signaled` no lugar de `other`. As demais colunas não mudam (`total`, `succeeded`, `failed`, `in_progress`,
`not_started`, `cancelled`, `last_requested_at` e `last_outcome`).

**Nenhum dado é alterado.** A migration só troca a definição de uma função de leitura.

## O que a migration faz

Tudo numa única transação:

1. Confere que a 071 está aplicada, pela existência de `public.client_share_requests`. Se não estiver, aborta.
2. `DROP FUNCTION public.portal_client_summary(...)` e `CREATE FUNCTION`. O tipo de retorno muda, então não basta `CREATE OR REPLACE`.
3. Restaura o dono (`nekkora_portal_owner`) e os privilégios: `REVOKE ALL ... FROM PUBLIC` e `GRANT EXECUTE ... TO nekkora_portal`.
4. Confere que não sobrou `EXECUTE` público. Se sobrou, aborta.

A função continua `SECURITY DEFINER`, com `search_path` fixo. As regras de escopo não mudam: o mesmo `portal_client_resolve`, o
`portal_client_require(..., 'dashboard')` e o mesmo limite de 400 dias no período.

## Requisitos de aplicação

| Requisito | Detalhe |
|---|---|
| Ordem | Depois da 070 e da 071, na mesma janela ou depois delas. Ela mesma recusa ser aplicada sem a 071. |
| Privilégio | **Janela de SUPERUSER do migrador** (`migrador_nekkora`), como a 070 e a 071. `ALTER FUNCTION ... OWNER TO nekkora_portal_owner` exige a elevação. No script `db/bootstrap/aplicar_como_migrador.sh` ela está em `N_ELEVAR`. |
| Ambientes | **Laboratório e testes.** Em homologação e produção ela **não está autorizada**: não consta de `ops/homologacao/implantacao/elevacao.txt` (como a 070 e a 071). Exige autorização específica da titular e o registro da versão com o sha256 acima. |
| Código junto | A API que lê o resumo (`nekkora-portal`, `PortalClientService`) lê `unconfirmed` e `no_error_signaled`. A **API de dev a partir do merge da #115 exige a 072**. Uma API anterior com a 072 aplicada falharia ao ler `other`, e a API nova sem a 072 falharia ao ler as colunas novas. A migration e a API sobem juntas. |
| Bloqueio | Rápida: só o `DROP/CREATE` de uma função. Durante a transação, chamadas ao resumo esperam o bloqueio. Não há reescrita de tabela. |
| Repetição | Aplicar de novo só recria a mesma função (o `DROP` exige que ela exista; sem a 071, aborta). O script de aplicação não registra versões aplicadas: quem aplica define o intervalo (`NEKKORA_DESDE`/`NEKKORA_ATE`), por exemplo `NEKKORA_DESDE=072 NEKKORA_ATE=072` num banco já em 071. |

## Conferência depois de aplicar

```sql
-- colunas novas no retorno
SELECT pg_get_function_result('public.portal_client_summary(uuid, text, uuid, timestamptz, timestamptz)'::regprocedure);
-- dono e privilégios
SELECT proowner::regrole, proacl FROM pg_proc WHERE oid = 'public.portal_client_summary(uuid, text, uuid, timestamptz, timestamptz)'::regprocedure;
SELECT has_function_privilege('public', 'public.portal_client_summary(uuid, text, uuid, timestamptz, timestamptz)', 'EXECUTE'); -- false
```

Valores esperados: o resultado contém `unconfirmed bigint, no_error_signaled bigint` e não contém `other`; o dono é
`nekkora_portal_owner`; só `nekkora_portal` tem `EXECUTE`.

## Reversão

Recriar a função com o corpo da 071 (`db/migrations/071_compartilhamento_por_pessoa.sql`, bloco `CREATE FUNCTION
public.portal_client_summary`, com o mesmo `ALTER ... OWNER`, `REVOKE` e `GRANT`), na mesma janela de SUPERUSER, **junto com** a volta
da API para a versão anterior à #115. Nenhum dado precisa ser restaurado.

## Evidência

- Testes: `ClientAccessIT` ("estados do dashboard") confere os totais por estado com execuções das quatro origens.
- Laboratório `frontlab-fluxo002`: banco em 072 desde 21/11. O roteiro real `fluxo-002-clientes` (seção 4b) passou com "Concluídas"
  igual só aos sucessos confirmados, e `unconfirmed`/`no_error_signaled` separados.
