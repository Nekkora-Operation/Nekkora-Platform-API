# Suíte de banco da migration 022 (ativação das organizações)

Valida a `db/migrations/022_ativacao_organizacoes.sql`, que é **candidata**. Relatório: `docs/relatorios/2026-10-23-api-v1-003-ativacao-organizacoes.md`.

Roda em dois clusters PostgreSQL 16 descartáveis e isolados:
- containers próprios, com `--network none`, sem porta publicada e sem volume nomeado;
- nunca toca a stack principal nem os ambientes de homologação.

```sh
db/tests/ativacao-022/run.sh
TESTE_PROJETO=nekkora-a022b db/tests/ativacao-022/run.sh
```

**Windows (Git Bash):** sem exclusão, o Git Bash converte os caminhos de container passados em `-e` e em `docker exec`. Rode com:

```sh
MSYS2_ARG_CONV_EXCL="NEKKORA_;/tmp/;/nekkora;/docker-entrypoint-initdb.d;/flyway" db/tests/ativacao-022/run.sh
```

## Modelo de aplicação

`db/bootstrap/aplicar_como_migrador.sh`: SUPERUSER em 011–013, 016–020 e **022**; BYPASSRLS só na 014; retirada conferida em 0|0|0. A §19 da 016 roda de novo depois de todas as versões, inclusive da 022, **sem adaptação**.

## Casos

| Caso | O que confere |
|---|---|
| A | instalação limpa 001–022; invariantes de 012 a 022 depois da retirada (014, 017 e 019 com as adaptações já usadas desde a 020); marcador `versao=022`; nenhum estado sem organização |
| B1 | base populada 001–021 (massa da atualização e da era MFA, duas organizações de controle e a identidade de backup em `pg_read_all_data`): a 022 sem janela é recusada por privilégio, sem efeito |
| B2 | com a janela: dados de `public` e `ops` idênticos; classificação das existentes (as 3 da massa, com limites UNLIMITED, ATIVADA; as de controle, AGUARDANDO_ATIVACAO; origem 2); diff de catálogo de `public` exatamente `esperado/B23_diff_esperado.txt` (21 linhas: as cinco funções `org_activation_*`, suas concessões, o EXECUTE de `provision_resource_limit` para o dono das funções, o schema e os dois gatilhos); catálogos de `public` e `ops_activation` iguais aos da instalação limpa; invariantes |
| B3 | reaplicar a 022 é recusado pela pré-condição |
| C1 | organização criada depois da 022 por `provision_organization` (papel real `nekkora_provisioning`) nasce AGUARDANDO_ATIVACAO, origem 1 |
| C2 | o provisionamento não abre capacidade de execução enquanto a organização aguarda (recursos 1 e 2, 42501); FINITE 0 e os recursos 4–5 passam. Com a organização **ATIVADA**, o provisionamento direto é recusado nos recursos 1 a 5 (42501): mudar limite depois da ativação só pela revisão autorizada. O INSERT direto do superusuário não é barrado |
| C3 | estado protegido: UPDATE direto (inclusive do superusuário), volta de ATIVADA, DELETE e TRUNCATE recusados |
| C4 | a ativação e a revisão chamadas sem certificado de cliente por um `op_*` do grupo: RECUSA `SEM_CERTIFICADO`, auditada com o tipo do ato (1 e 2), sem efeito |
| C5 | Platform, provisionamento e `op_*` fora do grupo não executam nenhum dos dois atos |
| C6 | a Platform lê o estado por `org_activation_states` e não lê as tabelas de `ops_activation` |
| C7 | contraprovas da invariante de **acesso efetivo** da 022, o mesmo critério da revisão 3 da 021, cada uma em `BEGIN … ROLLBACK`. O controle passa. São recusados: herança de `pg_read_all_data` e de `pg_write_all_data`, membro do backup, USAGE no schema para o Portal, concessão de coluna para a Platform e o backup com escrita |

Os atos autorizados com certificado real são cobertos por `OrgActivationIT` (nekkora-ops): ativação, repetição, concorrência, recusas, revisão auditada, `SEM_MUDANCA`, revisão concorrente e o vínculo da conta pela CLI. A jornada numa base populada atualizada de 021 para 022 é a da homologação (`ops/homologacao/testes/jornada_ativacao.sh`). A indicação na API é coberta por `IdentityOrganizationJourneysIT.organizacaoCriadaInformaAguardandoAtivacaoEDepoisAtivada` (nekkora-platform).

As saídas ficam em `saidas/` (não versionadas).
