# Suíte de banco da migration 020 (recuperação de senha da identidade por e-mail)

Valida a `db/migrations/020_identidade_recuperacao_senha.sql`, que é **candidata**. Relatório: `docs/relatorios/2026-10-22-identidade-002-recuperacao-senha.md`.

Roda em dois clusters PostgreSQL 16 descartáveis e isolados:
- containers próprios, com `--network none`, sem porta publicada e sem volume nomeado;
- nunca toca a stack principal, o hml03 ou o ambiente preservado.

```sh
db/tests/recuperacao-020/run.sh
TESTE_PROJETO=nekkora-p020b db/tests/recuperacao-020/run.sh
```

Requisitos: Docker, bash, Python 3. No sucesso, os containers são removidos. Na falha, ficam no ar e o comando de remoção é impresso.

## Modelo de aplicação

SUPERUSER só nas versões 011, 012, 013, 016, 017, 018, 019 e **020**; BYPASSRLS só na 014. Cada janela é retirada e conferida em `0|0|0`, e as invariantes rodam **depois** da retirada.

A 020 precisa da janela pelo mesmo motivo da 019: a função nova pertence a `nekkora_identity_owner`, que não tem membros. Sem a janela, a recusa medida é `must be able to SET ROLE "nekkora_identity_owner"` (B1).

## A. Instalação limpa 001–020

Oito janelas de SUPERUSER, cada uma com retirada `0|0|0`. Depois da retirada rodam as invariantes de:
- 012 (§15), 013 (§8) e 016 (§19);
- 014 (§8), com a adaptação da suíte da 017;
- 017 (§12) e 019 (§4), com duas adaptações:
  - a lista exata de funções executáveis pela Platform passa a ser a da 020, com `identity_password_reset(text,text)`;
  - a CHECK do evento passa a ser `1..17`;
- 020 (§4).

Marcador `versao=020`.

## B. Atualização de base populada 019 → 020

| Caso | O que confere |
|---|---|
| B1 | 020 **sem** a janela: recusada por privilégio; dados, catálogo e migrador inalterados |
| B2 | 020 com a janela:<ul><li>dados idênticos (80 tabelas);</li><li>diff de catálogo **exatamente** `esperado/B22_diff_esperado.txt`, 11 linhas: CHECK do propósito `1` → `1, 2`; CHECK do evento `1..15` → `1..17`; quatro privilégios por coluna do dono em `identity_email_tokens`; a função nova com EXECUTE do dono e da Platform;</li><li>catálogo igual ao da instalação limpa;</li><li>invariantes depois da retirada;</li><li>marcador `versao=020`.</li></ul> |
| B3 | reaplicar a 020 é recusado pela pré-condição (a função já existe); nada muda |

O comportamento é coberto por `IdentityPasswordRecoveryIT` (nekkora-platform), com o papel real `nekkora_platform`:
- pedido uniforme e piso de tempo;
- token de uso único (só o hash no banco);
- prazo reconferido depois da espera;
- concorrência;
- recusas cruzadas entre os tokens de cadastro e de recuperação;
- todas as sessões revogadas;
- MFA preservado.

As saídas ficam em `saidas/` (não versionadas).
