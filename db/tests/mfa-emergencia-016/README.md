# Suíte de banco da migration 016 (recuperação emergencial de MFA)

Valida a `db/migrations/016_recuperacao_emergencial_mfa.sql`, que é **candidata**. Roda em dois clusters PostgreSQL 16 descartáveis e isolados:
- containers próprios, com `--network none`, sem porta publicada e sem volume nomeado;
- nunca toca a stack principal nem o hml03.

```sh
db/tests/mfa-emergencia-016/run.sh
TESTE_PROJETO=nekkora-mfa016b db/tests/mfa-emergencia-016/run.sh
```

Requisitos: Docker, Git Bash/Linux/macOS e Python 3. No sucesso, os containers são removidos. Na falha, ficam no ar e o comando de remoção é impresso.

## A. Instalação limpa 001–016

1. **Aplicação:** as 16 migrations pelo entrypoint, sem erro.
2. **Catálogo de `public`:** comparado com uma base 001–015 no mesmo cluster, usando a listagem da `portal-013`.
   - Só são aceitos os acréscimos previstos na matriz: colunas, restrições e índice de `mfa_events`, grants por coluna, 9 políticas `ops_function_context`, 37 funções `ops_*` com as ACLs (revisão 4: 29 + 8 novas - retorno pelo canal, entrada e ativação de versão, reivindicação, estado da tentativa, resultado da entrega, varredura e recuperação), o schema `ops` e a extensão `sslinfo`.
   - A **única** remoção aceita é o INSERT de tabela inteira da Platform em `mfa_events`, trocado por INSERT por coluna sem `ops_request_id`.
3. **Controles anteriores:** os blocos de invariantes da 012 (§15), 013 (§8) e 014 (§8) rodam de novo sobre 001–016, junto com o da 016 (§19).
   - A 014 tem **uma** adaptação, feita pelo runner e conferida (o trecho precisa existir exatamente uma vez): a contagem de "exatamente uma política" nas tabelas de MFA desconsidera a política nova `ops_function_context`, que é prevista pela matriz.
4. **Schema `ops`:** listagem guardada como evidência (`sql/c_listagem_ops.sql`), com 21 tabelas (revisão 4: 14 + 7 novas - estado das versões, entradas, baldes, retornos, fotografia dos considerados, histórico de resultados e tentativas da aprovação) e o USAGE só do dono operacional.

## B. Atualização de base populada 015 → 016

1. **Origem:**
   - 001–012 pelo entrypoint e massa da `atualizacao-populada` (`p01`);
   - 013–015 por `psql`;
   - massa da era MFA (`sql/p02_popula_mfa.sql`): fatores, códigos, sessões com marcador, eventos, e **resets administrativos históricos sem motivo**.
2. **Dados:**
   - instantâneo (md5 das linhas de cada tabela de `public`, com as colunas anteriores à 016);
   - aplicação da 016 por `psql`;
   - novo instantâneo, que precisa ser **idêntico**.
3. **Histórico e motivo:** os resets sem motivo continuam, porque o CHECK é NOT VALID, e um reset novo sem motivo é recusado pela restrição `mfa_events_reset_admin_exige_motivo`.
4. **Catálogo:** o de `public` e o do schema `ops` da base atualizada são iguais aos da instalação limpa.
5. **Invariantes:** os mesmos quatro blocos rodam na base atualizada.
6. **Restauração lógica:** `pg_dump -Fc` → `pg_restore --exit-on-error` da base atualizada, com contagens por tabela idênticas em `public` e `ops`.

## Fora desta suíte (Java, `api/nekkora-ops` e `api/nekkora-platform`)

O comportamento com os papéis reais é testado em Java, contra um PostgreSQL com TLS e autenticação por certificado:
- autoria, certificados, separação de funções, estados e prazos, avisos, concorrência, atomicidade, reconciliação, integridade e auditoria;
- mutações;
- fluxo completo pela API.

Ver o relatório `docs/relatorios/2026-09-27-mfa-emergencia-implementacao.md`.
