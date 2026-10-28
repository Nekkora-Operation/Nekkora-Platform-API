# Suíte de banco da migration 025 (canal Platform–Agent 1.0)

Valida a `db/migrations/025_canal_agent.sql`, que é **candidata**. Contrato: `docs/relatorios/2026-10-28-platform-agent-contrato.md`.

Roda em dois clusters PostgreSQL 16 descartáveis e isolados:
- containers próprios, com `--network none`, sem porta publicada e sem volume nomeado;
- nunca toca a stack principal nem os ambientes de homologação.

```sh
db/tests/canal-agent-025/run.sh
TESTE_PROJETO=nekkora-c025b db/tests/canal-agent-025/run.sh
```

**Windows (Git Bash):**

```sh
MSYS2_ARG_CONV_EXCL="NEKKORA_;/tmp/;/nekkora;/docker-entrypoint-initdb.d;/flyway" db/tests/canal-agent-025/run.sh
```

## Modelo de aplicação

`db/bootstrap/aplicar_como_migrador.sh`: SUPERUSER em 011–013, 016–020, 022, 023 e 024; BYPASSRLS só na 014.

A **025 roda sem janela**: não cria papel, não troca dono e não cria função SECURITY DEFINER. As concessões novas são de
tabelas e funções do próprio migrador. A §19 da 016 roda de novo depois da 025, sem adaptação.

## Casos

| Caso | O que confere |
|---|---|
| A | instalação limpa 001–025; a 025 aplicada `elevada=nao`; invariantes de 012 a 025 depois da retirada. Adaptações declaradas: as da suíte da 024 e a contagem de tabelas de tenant da 017 e da 023 (43/44 → 53: as 9 do canal); marcador `versao=025` |
| B0 | a 025 sobre a base em 023, sem a 024: recusada pela pré-condição, sem efeito em dados e catálogo |
| B1 | a 025 falhando **no meio**: função homônima de uma que ela cria, criada por fora. Tabelas, RLS e funções anteriores já existiam quando ela falha, e nada fica (uma transação só). Retirada em 0\|0\|0 |
| B2 | base populada (Workers, Agents, execuções e eventos da 004–007, MFA, 016–024): **nenhum dado anterior muda** (instantâneo idêntico); tabelas novas vazias. O diff de catálogo é exatamente `esperado/B24_diff_esperado.txt` (609 linhas, **todas aditivas**: tabelas, colunas, restrições, índices, gatilhos, políticas, funções, ACL das tabelas novas e as concessões a `nekkora_ingestion` previstas no contrato). O catálogo fica igual ao da instalação limpa, e as invariantes passam |
| B3 | reaplicar a 025 é recusado pela pré-condição, sem efeito |
| C1 | separação das escritas com os papéis reais. A Platform não grava registro nem evento da máquina. A ingestão não cria código nem autorização, não muda o status do Agent, não lê e-mail e não apaga nem altera evento. O Portal não lê o canal |
| C2 | RLS: sem contexto, nada aparece; contexto de outra organização não vê nem grava (WITH CHECK) |
| C3 | gatilhos (cada um com a mensagem conferida). Recusados: código acima de 60 min; código criado ou cancelado sem autoridade; autorização com versão rascunho, com pacote diferente da impressão declarada na versão (inclusive o `sha256` dos bytes do artefato) ou sem `automation.execute`; registro divergente da autorização; transição proibida do supervisor; terminal alterado; contador que volta; `sequence` acima de 1.000.000; projeção incoerente com o envelope |
| C5 | contraprovas das invariantes da 025, cada uma em `BEGIN … ROLLBACK`. O controle passa. São recusados: DELETE concedido, papel de outro serviço com acesso, RLS sem FORCE, gatilho desligado, função SECURITY DEFINER, EXECUTE extra, escrita da Platform em fato da máquina e privilégio da ingestão além do canal |

Última rodada: 23 recusas e 5 controles positivos em C, e 8 contraprovas em C5.

As rotas são cobertas pelos testes Java:
- `AgentGatewayIT` e `AgentGatewayLimitsIT` (`nekkora-agent-gateway`);
- `AgentAdminIT` (`nekkora-platform`).

As saídas ficam em `saidas/` (não versionadas).
