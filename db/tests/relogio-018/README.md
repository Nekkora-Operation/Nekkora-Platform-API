# Suíte de banco da migration 018 (instantes terminais com o relógio recuado)

Valida a `db/migrations/018_instantes_terminais_relogio.sql`, que é **candidata**. Relatório: `docs/relatorios/2026-10-21-recuo-do-relogio-018.md`.

Roda em dois clusters PostgreSQL 16 descartáveis e isolados:
- containers próprios, com `--network none`, sem porta publicada e sem volume nomeado;
- nunca toca a stack principal, o hml03 ou o ambiente preservado.

```sh
db/tests/relogio-018/run.sh
TESTE_PROJETO=nekkora-r018b db/tests/relogio-018/run.sh
```

Requisitos: Docker, bash, Python 3. No sucesso, os containers são removidos. Na falha, ficam no ar e o comando de remoção é impresso. Duração típica: menos de 1 minuto.

## Modelo de aplicação

É o da 016 (revisão 5): `migrador_nekkora` é dono do banco, com SUPERUSER só nas versões 011, 012, 013, 016, 017 e **018**, e BYPASSRLS só na 014. Cada janela é retirada e conferida em `0|0|0`. As invariantes rodam **depois** da retirada.

A 018 precisa da janela porque substitui funções de `nekkora_ops_mfa_owner` e `nekkora_identity_owner`, papéis sem membership de ninguém. Sem a janela, a recusa é de privilégio (B1).

## A. Instalação limpa 001–018

- 18 migrations pelo entrypoint, seis janelas de SUPERUSER com retirada `0|0|0`.
- Invariantes da 012 (§15), 013 (§8), 014 (§8, com a adaptação já usada na suíte da 017), 016 (§19), 017 (§12) e 018 (§4), depois da retirada.
- Marcador `nekkora-inicializacao-completa versao=018`.

## B. Atualização de base populada 017 → 018

Massa: `atualizacao-populada/p01` em 001–012 e `mfa-emergencia-016/p02` em 015, depois 016 e 017. Instantâneo de dados de `public` e `ops` (md5 por tabela) e catálogo de `public`.

| Caso | O que confere |
|---|---|
| B1 | 018 **sem** a janela de SUPERUSER: recusada por privilégio (42501); dados, catálogo e estado do migrador inalterados |
| B2 | 018 com a janela: dados idênticos; diff de catálogo 017 → 018 **exatamente** os corpos de `ops_mfa_execute`, `ops_operator_revoke`, `ops_operator_disable` e `identity_mfa_replace` (dono, `SECURITY DEFINER`, volatilidade e configuração iguais) e `SELECT (created_at)` de `nekkora_ops_mfa_owner` em `user_mfa_factors` e `user_mfa_recovery_codes`; catálogo igual ao da instalação limpa; invariantes depois da retirada; sessões, fatores e códigos vivos inalterados; marcador `versao=018` |
| B3 | reaplicar a 018 é recusado pela pré-condição (corpos diferentes dos da 016/017); nada muda |

## Comportamento

O recuo do relógio e os controles negativos são cobertos pelos testes Java, nas bases 001–018 montadas pelo mesmo modelo:
- `RecuoDoRelogioIT` (nekkora-ops): execução da recuperação emergencial com códigos, fator ou sessões à frente do relógio; retirada de habilitação e desativação de operador com habilitações à frente do relógio; execução não aprovada e retirada sem habilitação continuam recusadas;
- `RecuoDoRelogioIdentidadeIT` e `RecuoDoRelogioOrgIT` (nekkora-platform).

As saídas ficam em `saidas/` (não versionadas).
