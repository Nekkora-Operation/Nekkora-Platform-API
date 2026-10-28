# Suíte de banco da migration 027 (lote do expurgo das categorias 1 e 2, D-RET-2)

Valida a `db/migrations/027_lote_expurgo_categorias.sql`, que é **candidata**. Decisão da titular (28/10): corrigir em PR
próprio, com reprodução dirigida e controles de limite do lote. O expurgo continua **desligado** fora do laboratório (D-RET-1):
a 027 não concede EXECUTE de `purge_expired_records`.

```sh
MSYS2_ARG_CONV_EXCL="NEKKORA_;/tmp/;/nekkora;/docker-entrypoint-initdb.d;/flyway" db/tests/lote-expurgo-027/run.sh
```

## Defeito

A seleção da 011/012, `WHERE id IN (SELECT … ORDER BY … LIMIT n FOR UPDATE)`, pode expurgar **mais de n** registros. Isso
acontece quando o planejador escolhe o semi-join com a subconsulta reexecutada a cada linha externa (sem `Materialize` nem
`Unique`). A cada reexecução, o `LockRows` pula as linhas que o próprio comando já alterou e devolve outras. O teto de 1000
por chamada deixa de valer. O defeito foi achado na suíte da 026, na categoria 3, e corrigido lá. A 027 aplica a mesma
correção às categorias 1 e 2: o lote é selecionado uma vez numa CTE materializada.

## Casos

| Caso | O que confere |
|---|---|
| A | instalação limpa 001–027, com a 027 aplicada sob a janela; invariantes da 026 e da 027 depois da retirada |
| R | **reprodução dirigida antes da 027**, em `BEGIN … ROLLBACK`. Massa real da 006/007 e logs do canal AGENT pelas regras da ingestão: 5 eventos e 5 logs recebidos num dia fechado pelo fechamento real, com política de 1 dia. Com o plano forçado na sessão (`enable_hashjoin`, `enable_mergejoin`, `enable_hashagg`, `enable_material` e `enable_sort` desligados), lote 2 expurga mais de 2 logs. Isso é **exigido** na categoria 2. A categoria 1 e o plano natural são **registrados**, porque o planejador às vezes executa a subconsulta uma vez nos eventos. Nenhuma estatística nem dado é manipulado |
| B0 | a 027 **sem a janela**: recusada por privilégio (`must be owner of function purge_expired_records`), sem efeito em dados e catálogo |
| B1 | a 027 aplicada: nenhum dado muda; o diff de catálogo é só o corpo de `purge_expired_records`; o catálogo fica igual ao da instalação limpa; invariantes |
| C | **depois da 027, com o mesmo plano forçado**: lote 2 dá exatamente `2, 2, 1, 0` nas categorias 1 e 2. Total igual ao elegível; nada fora do dia fechado; a redação mantém o fingerprint; a auditoria registra o número real de cada chamada; lotes 0, 1001 e nulo recusados |
| B3 | reaplicar a 027 é recusado pela pré-condição, sem efeito |

Última rodada (28/10):
- reprodução, com lote 2: forçado 5/5 nas duas categorias, natural 2/2;
- depois da 027: `2|2 2|2 1|1 0|0` nas duas categorias;
- controles: os três lotes inválidos recusados.
