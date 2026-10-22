# Suíte de banco da migration 021 (fila persistente de e-mail)

Valida a `db/migrations/021_correio_fila.sql`, que é **candidata**. Relatório: `docs/relatorios/2026-10-22-identidade-002-email-real.md`.

Roda em dois clusters PostgreSQL 16 descartáveis e isolados:
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
- só a Platform tem acesso à tabela; os papéis predefinidos `pg_*` ficam de fora;
- a Platform não tem DELETE nem UPDATE da origem do trabalho;
- a tabela não tem `organization_id` (não é tabela de tenant; a contagem de 43 da 017 continua valendo);
- as duas triggers estão ligadas.

Marcador `versao=021`.

## B. Atualização de base populada 020 → 021

| Caso | O que confere |
|---|---|
| B2 | 021 aplicada **sem** janela. Dados idênticos (80 tabelas). Diff de catálogo **exatamente** `esperado/B22_diff_esperado.txt`: 87 linhas, todas acréscimos e todas de `mail_outbox` ou de sua função de proteção (colunas, CHECKs, índices, triggers, privilégios por coluna da Platform: 11 de INSERT e 9 de UPDATE, mais o SELECT). Catálogo igual ao da instalação limpa. Invariantes. Marcador `versao=021` |
| B3 | Reaplicar a 021 é recusado pela pré-condição (a tabela já existe); nada muda |

O comportamento é coberto por `MailOutboxIT` (nekkora-platform), com o papel real `nekkora_platform`:
- fila cifrada;
- tentativas e desfechos;
- posse vencida depois de queda;
- prazo vencido;
- despachos concorrentes;
- proteções da trigger e ausência de DELETE.

As saídas ficam em `saidas/` (não versionadas).
