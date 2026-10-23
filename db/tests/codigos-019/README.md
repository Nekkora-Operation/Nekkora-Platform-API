# Suíte de banco da migration 019 (regeneração dos códigos de recuperação da identidade)

Valida a `db/migrations/019_identidade_regeneracao_codigos.sql`, que é **candidata**. Relatório: `docs/relatorios/2026-10-22-identidade-002-credenciais.md`.

Roda em dois clusters PostgreSQL 16 descartáveis e isolados:
- containers próprios, com `--network none`, sem porta publicada e sem volume nomeado;
- nunca toca a stack principal, o hml03 ou o ambiente preservado.

```sh
db/tests/codigos-019/run.sh
TESTE_PROJETO=nekkora-c019b db/tests/codigos-019/run.sh
```

Requisitos: Docker, bash, Python 3. No sucesso, os containers são removidos. Na falha, ficam no ar e o comando de remoção é impresso. Duração típica: cerca de 1 minuto.

## Modelo de aplicação

`migrador_nekkora` tem SUPERUSER só nas versões 011, 012, 013, 016, 017, 018 e **019**, e BYPASSRLS só na 014. Cada janela é retirada e conferida em `0|0|0`. As invariantes rodam **depois** da retirada.

A 019 precisa da janela porque a função nova pertence a `nekkora_identity_owner`, papel sem membership de ninguém. Sem a janela, a recusa medida é `must be able to SET ROLE "nekkora_identity_owner"` (B1).

## A. Instalação limpa 001–019

- 19 migrations pelo entrypoint, sete janelas de SUPERUSER com retirada `0|0|0`.
- Invariantes depois da retirada:
  - 012 (§15), 013 (§8) e 016 (§19);
  - 014 (§8), com a adaptação já usada na suíte da 017;
  - 017 (§12), com a invariante 3 adaptada: a lista exata ganha `identity_recovery_codes_regenerate(text,text[])`. A §4 da 019 a substitui;
  - 019 (§4).
- Marcador `nekkora-inicializacao-completa versao=019`.

## B. Atualização de base populada 018 → 019

Massa: `atualizacao-populada/p01` em 001–012 e `mfa-emergencia-016/p02` em 015, depois 016–018.

| Caso | O que confere |
|---|---|
| B1 | 019 **sem** a janela: recusada por privilégio; dados, catálogo e migrador inalterados (a migration é uma transação) |
| B2 | 019 com a janela: dados idênticos (80 tabelas de `public` e `ops`); diff de catálogo **exatamente** `esperado/B22_diff_esperado.txt` (CHECK do evento de 14 para 15, função nova, EXECUTE do dono e da Platform); catálogo igual ao da instalação limpa; invariantes depois da retirada; marcador `versao=019` |
| B3 | reaplicar a 019 é recusado pela pré-condição (a função já existe); nada muda |

O comportamento é coberto por `IdentityPasswordAndCodesIT` (nekkora-platform), com o papel real `nekkora_platform`:
- regeneração com step-up;
- recusa sem step-up e na sessão restrita;
- step-up vencido durante a espera;
- concorrência.

As saídas ficam em `saidas/` (não versionadas).
