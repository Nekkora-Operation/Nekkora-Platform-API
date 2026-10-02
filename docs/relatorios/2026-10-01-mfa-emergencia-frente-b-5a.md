# Frente B, etapa 5a: levantamento somente leitura de sessões ativas sem marcador de MFA

**ESTADO: RESULTADO OPERACIONAL APROVADO, com ajustes de redação no §7 (Adendo).**
- O §7 prevalece sobre as formulações que ele cita nos §1, §3 e §4. O texto original foi mantido como entregue no pacote `homologacao-016-frenteb5a-20261001T204222Z.zip` (`efc4ccd9…1251`), que fica preservado.
- O P4 continua não aprovado até a validação integrada.
- Ficam fora: 5b (`hml03` parado), R2b, P5, ativação operacional da 014 e commit/push.

**Data:** 2026-10-01, das 20:36Z às 20:50Z.

**Evidências:** `ops/homologacao/saidas/p4r2acB-20261001T203625Z/`, fora do Git:
- `sql/`: consultas;
- `bases/`: saída por base;
- `ensaio-016a_registros/`: cópias dos registros de implantação, com sha256 da origem;
- inventário B12 de início e fim; estado dos containers antes e depois.

## 1. Resultado

| Base (container / banco) | 014 aplicada? | Sessões ativas sem marcador | Antes da 014 | Classificação |
|---|---|---|---|---|
| principal (`nekkora-postgres` / `nekkora`) | **Não** | n/a: coluna inexistente. 0 sessões no total | n/a | **Não afetada** (014 não aplicada) |
| portal-dev (`nekkora-portal-dev-postgres` / `nekkora`) | **Não** | n/a: coluna inexistente. 20 com `revoked_at` nulo, todas expiradas | n/a | **Não afetada** (014 não aplicada) |
| portal-013 (`nekkora-portal-013-postgres` / `nekkora`) | **Não** | n/a: coluna inexistente. 27 com `revoked_at` nulo, todas expiradas | n/a | **Não afetada** (014 não aplicada) |
| portal-013 (`nekkora-portal-013-postgres` / `nekkora_ref`) | **Não** | n/a: coluna inexistente. 0 sessões | n/a | **Não afetada** (014 não aplicada) |
| `ensaio-016a` (exceção de leitura) | **Sim** | **0**: 3 sessões, 2 ativas, ambas com marcador; 1 revogada | **0**: nenhuma sessão criada antes da 014 | **Não afetada**: a condição do defeito estava presente, sem efeito (§3) |

- **Nenhuma base no ar está afetada.** Não há sessão a corrigir nas bases consultadas.
- O cluster de portal-013 tem dois bancos (`nekkora` e `nekkora_ref`). Os dois foram consultados e são informados separadamente.

**Definições:**
- **Ativa:** `revoked_at IS NULL`. É o critério da invariante da própria 014 (`014_mfa_sessoes.sql:379`).
- **Utilizável:** ativa e com `expires_at > now()`.

## 2. Método

- **Identidade:** em todas as bases, superusuário do cluster.
  - `nekkora` nos três clusters de desenvolvimento.
  - `admin_cluster` no `ensaio-016a`, via `leitura.sh` da P4.
  - Registrado em cada saída: `super=true bypassrls=true`. Enxerga todas as linhas apesar do `FORCE ROW LEVEL SECURITY` de `user_sessions`, presente nas cinco bases.
- **Transações:**
  - Toda consulta roda em `BEGIN TRANSACTION READ ONLY … ROLLBACK`. Cada saída registra `transacao_somente_leitura=on`.
  - Limites: `statement_timeout` de 10 a 15 s, `lock_timeout` 1 s, `idle_in_transaction_session_timeout` 30 s.
  - `psql -X -v ON_ERROR_STOP=1`.
  - Sem objetos temporários.
- **Dados:** só catálogo, histórico Flyway, contagens e horários agregados.
  - Nenhuma consulta lê `session_token_hash`, `csrf_token`, `session_id`, `user_id` ou `organization_id`.
  - Nada foi exportado além das contagens.
- **Erro de redação nas consultas, corrigido antes de qualquer conclusão:**
  - A primeira versão da fase 1 parou num erro de tipo (`text || "char"`), sem efeito por ser só leitura.
  - Duas consultas da fase 2 não devolviam linha quando não havia sessões, por causa do `GROUP BY`. Foram reescritas para sempre emitir uma linha.
  - As saídas finais são as das versões corrigidas, em `sql/`.
- **Evidência da 014 (fase 1), em cada base:**
  - presença de `flyway_schema_history`;
  - coluna `user_sessions.mfa_verified_at`;
  - tabelas `user_mfa_factors` e `auth_challenges`;
  - triggers `trg_user_sessions_enforce_mfa` e `trg_user_sessions_prevent_reactivation`;
  - papel `migrador_nekkora`;
  - assinatura de catálogo de 010 a 016, com um objeto característico de cada migration.
- **Fase 2 (só onde a 014 está aplicada):**
  - histórico Flyway completo;
  - contagens por estado;
  - divisão das ativas sem marcador por `created_at` em relação ao `installed_on` da 014, e por ordem de transação (`xmin` da linha contra o `xmin` da coluna);
  - vestígio da ativação: lotes de revogação no mesmo instante.
- **Fase 2b (bases sem 014):** contagens de contexto.

## 3. Evidência por base

### principal, portal-dev, portal-013 (`nekkora` e `nekkora_ref`)

O que as quatro bases têm em comum:
- Sem `flyway_schema_history`.
- Sem coluna `mfa_verified_at`, sem `user_mfa_factors` e sem as triggers da 014.
- `migrador_nekkora` ausente.
- Dono do banco: `nekkora`.
- Sem marcador de inicialização.

Assinatura de catálogo por base:

| Base | Assinatura | Nível aparente |
|---|---|---|
| principal | `010:S 011:S 012:S 013:N 014:N 016:N` | até a 012 |
| portal-dev | `010–013:S 014:N 016:N` | até a 013 |
| portal-013 | `010–013:S 014:N 016:N` | até a 013 |
| `nekkora_ref` | `010–012:S 013:N 014:N 016:N` | até a 012 |

- **Conclusão:** a 014 nunca foi aplicada. O defeito F-R2a-1 (ativação vazia sob FORCE RLS sem BYPASSRLS) não pode ter ocorrido nessas bases.
- **Contexto para quando a 014 for aplicada:**
  - portal-dev e portal-013 têm 20 e 27 sessões com `revoked_at` nulo, todas expiradas.
  - A ativação da 014 as revogará, desde que rode com a janela BYPASSRLS já prevista (R2a-C) ou como superusuário.
  - Não há ação nesta etapa.

### `ensaio-016a`

- **Aplicação da 014, segundo o catálogo:**
  - `assinatura 010–016:S`;
  - coluna `mfa_verified_at`;
  - triggers presentes e habilitadas (`O`);
  - RLS habilitada e forçada.
- **Aplicação da 014, segundo o histórico Flyway:**
  - 16 registros, todos com `success=true`.
  - A 014 aparece uma única vez, `installed_on=2026-09-29T22:58:57.927982Z`, `installed_by=migrador_nekkora`.
- **Privilégios na execução da 014.** A prova vem dos registros do executor, não de `installed_by` nem dos atributos atuais. Ver `ensaio-016a_registros/`, cópias com sha256 da origem no clone de lab preservado.
  - `janelas_de_elevacao.txt` (`6983fe97…`) registra janelas SUPERUSER **só para 011, 012 e 013**. A da 013 fecha às 22:58:55.693Z com estado `0|0|0`.
  - Não há janela para a 014, registrada 2,2 s depois.
  - `implantacao.txt` (`95e18893…`) traz `014_mfa_sessoes.sql (executeInTransaction=false)` aplicada e o estado final `0 atributos elevados, 0 memberships, 0 sessoes`.
  - **Conclusão:** a 014 rodou como `migrador_nekkora` sem BYPASSRLS. É exatamente a condição do defeito F-R2a-1.
- **Efeito: nenhum.**
  - 0 sessões criadas antes do registro da 014. A sessão mais antiga é de 2026-09-29T23:06:03Z, depois da 014 e também da 016 (23:03:56Z).
  - 0 sessões revogadas até o registro da 014.
  - 0 lotes de revogação.
  - 0 ativas sem marcador, em ambos os critérios (`created_at` e `xmin`).
  - 0 sessões com marcador anteriores à 014.
  - A ativação não tinha o que revogar. As 3 sessões existentes nasceram sob as triggers da 014; as 2 ativas têm marcador.

## 4. Limitações registradas

1. **Bases de desenvolvimento sem histórico:** sem `flyway_schema_history`, o nível de migration é inferido só pela assinatura de catálogo. Não há registro de horário nem de privilégios das execuções.
   - Isso não afeta a conclusão: a ausência dos objetos da 014 basta para "não aplicada".
   - O nível exato de cada base (até 012 ou até 013) é aparente, não comprovado.
2. **`installed_on` como referência temporal no `ensaio-016a`:**
   - É o relógio do servidor no registro do Flyway, o mesmo relógio do `default now()` de `created_at`.
   - Um `created_at` informado explicitamente pela aplicação não seria detectado como inconsistente.
   - A referência foi cruzada com a ordem de transação (`xmin`): resultado 0 nos dois critérios.
   - `track_commit_timestamp=off` nas cinco bases, então não há horário de commit por linha.
3. **Sessões apagadas antes de hoje não são detectáveis:**
   - As estatísticas de tabela não servem, porque o `ensaio-016a` passou por recuperação de queda em 2026-10-01.
   - Isso não afeta a contagem de sessões **ativas hoje**, que é o risco em questão.
4. **Prova de privilégios do `ensaio-016a`:**
   - Vem de arquivos do executor guardados no clone de lab, não do banco.
   - A ausência de janela para a 014 é coerente com a lista de elevação operacional da época (sha256 `5a4e90f5…`, sem linha da 014) e com o achado F-R2a-1.
   - `installed_by=migrador_nekkora` e os atributos atuais (`super=false bypassrls=false`) **não** foram usados como prova.

## 5. Escopo cumprido

- **Containers e bancos:**
  - Nenhum container iniciado, parado ou recriado. `containers_antes.txt` e `containers_depois.txt` são idênticos, com o mesmo `StartedAt` e 0 reinícios.
  - `hml03` continua `exited`, aguardando o 5b.
- **Nada alterado:** nenhuma sessão corrigida, nenhuma migration reaplicada, nenhum marcador escrito, nenhum privilégio alterado.
- **Inventário B12** do `ensaio-016a`, início × fim: **OK, código 0**, com protegidos idênticos por ID.
- **Repositório:** sem stage, commit ou push. Na árvore de trabalho, só este relatório é novo; as evidências ficam fora do Git.

## 6. Pendências (inalteradas)

- **5b:** iniciar `hml03` para o mesmo levantamento. É decisão separada.
- **R2b:** provar o H02 e o `testar-016` completos.
- **Falha intermitente do transporte em Windows:** aberta.
- **P5, ativação operacional da 014 e commit/push:** separados.

## 7. Adendo (revisão de 2026-10-01): ajustes de redação

O revisor conferiu o SHA-256, o manifesto (32/32), as consultas e as saídas por base, sem reexecutar consultas nos bancos. O resultado operacional foi aprovado.

Os ajustes abaixo não exigiram nova consulta e não alteram nenhuma contagem.

1. **Bases sem histórico (principal, portal-dev, portal-013 `nekkora` e `nekkora_ref`).**
   - Onde o texto diz "a 014 nunca foi aplicada" (§1 e §3), ler: **"o catálogo atual não contém os objetos característicos da 014; estado compatível com base anterior à migration"**.
   - Isso não comprova todo o passado da base.
   - A coluna "014 aplicada? Não" do §1 e a classificação "Não afetada (014 não aplicada)" passam a ter esse sentido.
2. **`xmin` não prova a época de criação de uma sessão.**
   - O `xmin` identifica a transação que criou a **versão atual** da linha; um UPDATE gera outra versão.
   - O mesmo vale para a linha de `pg_attribute` usada como referência da coluna.
   - Fica retirado o uso de `xmin` como prova independente de criação anterior ou posterior à 014: no §2, no §3 ("em ambos os critérios (`created_at` e `xmin`)") e no §4, item 2 ("cruzada com a ordem de transação").
   - Além disso, a consulta de `xmin` examinou só as sessões ativas sem marcador, um conjunto vazio. Ela não informou nada sobre as demais sessões.
3. **`ensaio-016a`: conclusão limitada ao observado.** No §3, "a ativação não tinha o que revogar" e "Efeito: nenhum" ficam substituídos por:
   - as sessões atualmente presentes têm `created_at` posterior ao registro da 014;
   - não existem sessões não revogadas sem marcador.
   - A limitação sobre registros apagados (§4, item 3) impede afirmar ausência absoluta de efeito histórico.
   - Continua válida a evidência de que a 014 rodou sem BYPASSRLS, tirada do registro de janelas do executor.

**Conclusão revisada:** nas cinco bases consultadas, não foi identificado resíduo atual que demande correção pelo defeito F-R2a-1. O levantamento não comprova ausência de impacto histórico. `hml03` e produção não foram avaliados.

**Próximos passos:**
- **R2b (preparação liberada):**
  - novo ensaio (`ensaio-016b`) com janela BYPASSRLS para a 014;
  - autorização da 016 vinculada ao hash atual e ao `ensaio-016b`;
  - H02 completo;
  - `testar-016`.
- **5b:** continua separado e não bloqueia essa preparação.
