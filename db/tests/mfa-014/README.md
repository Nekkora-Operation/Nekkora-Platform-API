# Suíte da migration 014 (MFA e sessões)

Valida `db/migrations/014_mfa_sessoes.sql` com os papéis reais, numa stack PostgreSQL 16 isolada, a partir de instalação limpa (001–014 montadas explicitamente e aplicadas pelo entrypoint). Contrato de base: `docs/relatorios/2026-09-26-decisoes-8-6-8-7-8-10.md` (revisão 4).

```sh
db/tests/mfa-014/run.sh
TESTE_PROJETO=nekkora-mfa-014b TESTE_PORTA=55522 db/tests/mfa-014/run.sh
```

## O que a suíte faz

1. **Catálogo:**
   - lista exata do que a 014 cria (`t01`);
   - diff contra uma base 001–013 no mesmo cluster, que só pode ter acréscimos esperados.
2. **Invariantes, triggers e RLS com `nekkora_platform` (`t02`):**
   - sessão nova sem marcador de MFA, sem CSRF ou sem fator ativo: recusada;
   - revogada não reabilita;
   - identidade, marcador e CSRF imutáveis;
   - prazo absoluto não se estende;
   - step-up só avança;
   - passo TOTP só aumenta;
   - desafio consumido imutável;
   - `mfa_events` append-only.
3. **013 intacta (`t03`):** `nekkora_portal` lê com sessões que carregam o marcador de MFA, e o toque de sessão continua funcionando (`t04`).
4. **Concorrência (`conc_*`):** duas sessões disputam o consumo do mesmo desafio, do mesmo passo TOTP e do mesmo código de recuperação; cada lado é verificado separadamente.
   - Limite: aqui as duas sessões usam o **mesmo** desafio, então a segunda fica presa no lock do desafio e nem chega ao passo TOTP nem ao código. O consumo exclusivo do mesmo passo TOTP e do mesmo código de recuperação por desafios **diferentes**, e as corridas entre conclusão do MFA e troca de senha, suspensão e reset, estão cobertos pela API real em `api/nekkora-platform/.../MfaConcurrencyIT.java`.
5. **Mutações (`mutacoes/`):** cada trigger é removida de propósito; `t02` **precisa** falhar e passa de novo após a restauração (impressão digital conferida).

A **ativação sobre base populada** (revogação das sessões pré-014) é validada em `db/tests/atualizacao-populada`, e não aqui.

A stack fica no ar ao final e o comando de remoção é impresso.
