# Mini relatório — segurança operacional do PostgreSQL Nekkora

**Data:** 2026-09-25
**Status:** proposta revisada. A baseline 001–012 está integrada na `main` e foi validada em instalação limpa, com papéis reais (`docs/relatorios/2026-09-25-validacao-012-security-rls.md`). Isso confirma os cenários registrados, mas **não** comprova prontidão de produção nem a atualização de uma base já populada.
**Base:** `2026-09-25-preparacao-seguranca-postgres.md` (§6, §6.1, §8), `2026-09-25-validacao-012-security-rls.md` (§4.4, §7, §9, §10).

## 1. O que já temos

- **Papéis separados por serviço** (`platform`, `ingestion`, `scheduler`, `provisioning`, `retention_executor`) e proprietários de função `NOLOGIN`, sem superusuário, `BYPASSRLS`, `CREATEROLE`, `CREATEDB` ou `REPLICATION`.
- **RLS** com `ENABLE` + `FORCE` nas 42 tabelas de tenant (39 diretas e 3 derivadas). O catálogo global fica sem RLS.
- **Verificação em duas camadas:**
  - a **012 verifica invariantes de segurança ao final** e aborta se algum falhar: nenhum atributo privilegiado nos papéis protegidos, nenhuma membership envolvendo esses papéis em qualquer sentido, nenhum `CREATE` efetivo no schema `public` ou no banco, `SECURITY DEFINER` com `search_path` fixo e sem `EXECUTE` público;
  - a **suíte** (`db/tests/seguranca-012/`) **confronta o catálogo instalado com a matriz aprovada**: privilégios de tabela, coluna e função (qualquer beneficiário) e as 81 políticas, com asserções que abortam.
- **Testes com papéis reais:** isolamento entre organizações, contexto ausente ou malformado, reutilização de conexão, restauração do contexto de função, atomicidade, concorrência e testes deliberados do próprio runner.
- **Secrets:** os valores reais não ficam nas tabelas. `workspace_secrets` guarda só a referência ao provedor (`provider_key`, `provider_reference`).
- **Expurgo desativado:** o fingerprint e o prazo dos dados preservados para deduplicação ainda precisam de contrato próprio. `purge_expired_records` não tem `EXECUTE` para nenhum papel.

## 2. Estado atual do ambiente local: desenvolvimento, não produção

Conferido na base principal local (`nekkora-platform`) em 2026-09-25. **A instalação limpa validada não equivale à configuração de produção.**

| Item | Estado atual (desenvolvimento) | Observação |
|---|---|---|
| TLS | `ssl = off` | Tráfego em texto puro |
| Autenticação local | `pg_hba`: `trust` para socket local e `127.0.0.1`/`::1`, inclusive `replication`; `scram-sha-256` para as demais origens | A suíte de testes depende do `trust` local para conectar como cada papel sem senha |
| Exposição de rede | Porta publicada em `0.0.0.0:5432` | Alcançável fora do host, conforme o firewall local |
| Superusuário | Fallback `${POSTGRES_PASSWORD:-nekkora}` no `docker-compose.yml` (o `.env.example` já está vazio) | Senha trivial se `POSTGRES_PASSWORD` não for definido |
| Papéis de serviço | Criados **sem senha** | Pela rede, não autenticam (o `scram` exige senha), o que é fail-closed. Localmente, entram por `trust` |
| `TEMP` | Concedido a `PUBLIC` no banco | O desvio por objeto temporário está neutralizado pelo `search_path` fixo. O harness de asserções usa `TEMP` |

## 3. Controles operacionais necessários antes de produção

| # | Área | Diretriz | Estado |
|---|---|---|---|
| C1 | Credenciais de serviço | Uma credencial independente para cada papel com login, guardada num gerenciador de secrets. Nenhuma senha em SQL, Git ou logs. Remover o fallback de senha do Compose para qualquer ambiente não local. Rotação definida (procedimento em C9) | Pendente |
| C2 | Rede e conexões | Acesso ao banco só pela rede privada e pelos serviços necessários. `pg_hba.conf` por origem e papel, **só `hostssl`**, `scram-sha-256` (`password_encryption = scram-sha-256`) e **nenhuma entrada `trust`**. O cliente usar `sslmode=verify-full` não impede o servidor de aceitar texto puro: a recusa é responsabilidade do `pg_hba` | Pendente |
| C3 | Criptografia | Conexões em trânsito (TLS, com validação de certificado e hostname) e volumes/backups em repouso. Gestão e rotação de chaves definidas. UUIDs e ids expostos pelo front não são segredo | Pendente |
| C4 | Migrations | Processo de implantação controlado, com credencial separada dos papéis da aplicação. O `docker-entrypoint-initdb.d` só serve ao bootstrap de volume vazio e não atualiza uma base existente. O procedimento inicial (`db/bootstrap/001_organizacao_interna_nekkora.sql`) faz parte desse processo. **Riscos conhecidos da atualização de uma base populada para a 012 (não exercitada):** (a) `DROP OWNED BY nekkora_app` remove objetos e privilégios desse papel **no banco corrente** e também revoga privilégios em objetos **compartilhados** do cluster (bancos, tablespaces). Objetos ou privilégios **locais de outros bancos** continuam lá e fazem o `DROP ROLE` falhar, abortando a 012 inteira; precisariam de `DROP OWNED`/`REASSIGN OWNED` em cada banco afetado antes. Além disso, o serviço que ainda usa `nekkora_app` precisa ser desligado antes; o comportamento com sessões ativas desse papel durante a migration não foi testado e deve ser confirmado no ensaio de atualização; (b) toda organização existente precisa das cinco revisões de limite, senão fica fail-closed para Workers, execuções e agendamentos | Pendente |
| C5 | **Contexto de sessão e reset do pool** | O backend define o contexto **só com `SET LOCAL`**, no início de cada transação. Ao devolver cada conexão, o pool executa **`DISCARD ALL`**, o reset completo recomendado: parâmetros de sessão, tabelas temporárias, planos e cursores. Ele **não pode rodar dentro de uma transação**, então só vale depois que a transação da requisição terminou (`COMMIT` ou `ROLLBACK`). `RESET ALL` restaura **apenas os parâmetros de configuração** (inclusive `nekkora.organization_id` definido por `SET` de sessão), sem limpar o resto do estado da conexão. Serve só quando o objetivo é especificamente limpar parâmetros, também depois de encerrar a transação. A validação da 012 (I09) mostrou que um `SET` de sessão vaza para a transação seguinte da mesma conexão, e o banco não distingue `SET` de `SET LOCAL` | Pendente |
| C6 | Backups | Protegidos e criptografados, com retenção definida e cópia fora do host principal. Um backup só conta como validado depois de um teste de restauração | Pendente |
| C7 | **Restauração isolada antes de liberar dados** | Toda restauração acontece **primeiro em ambiente isolado**, não voltado ao produto. Ali se reaplica o expurgo que já deveria valer na data da restauração, recalculado a partir do estado do backup, e **só então** o banco é liberado ao produto. Para eventos e logs, o expurgo aprovado é **redação por `UPDATE`**: `payload` vira `NULL`, e a linha, a identidade e o fingerprint são preservados. **Nenhuma linha é apagada.** "Exclusões" fica reservado a eventuais remoções de linha que venham a ser definidas no futuro para outras categorias de dado (por exemplo, pedidos de titular); hoje não há nenhuma aprovada, e elas exigirão contrato próprio antes de entrar neste procedimento. Um backup anterior a um expurgo contém `payload` já redigido; restaurá-lo direto reintroduziria o dado removido | Pendente |
| C8 | **Identidade de backup** | Escolha **explícita** e registrada, com papel dedicado, separado dos serviços. Opção física (`pg_basebackup`/WAL): papel com `REPLICATION` e entrada própria no `pg_hba`. Opção lógica (`pg_dump`): papel de leitura ampla, que sob RLS só exporta tudo com `BYPASSRLS`, uma decisão de segurança a registrar como exceção nomeada. Nenhum dos dois pode ser um papel de serviço | Pendente — decisão |
| C9 | Operação | Limites de conexão, monitoramento, alertas e procedimentos para falhas e rotação de credenciais. `statement_timeout` e `idle_in_transaction_session_timeout` por papel são **mitigação, não garantia**: o próprio papel pode sobrescrevê-los com `SET`. Uma ingestão presa numa transação aberta segura o `FOR SHARE` da barreira e trava o fechamento do dia sem erro visível; a garantia real depende da aplicação e do pool | Pendente |
| C10 | Observabilidade sem vazamento | Registrar falhas e ações administrativas sem gravar payloads, tokens ou senhas. `log_statement` restrito não basta: `DETAIL`/`CONTEXT` de erros, `log_min_duration_statement` (texto da consulta) e `pg_stat_statements` também podem expor valores | Pendente |
| C11 | Jobs de dados | Implementar e operar a consolidação (`reconsolidate_usage_day`) e o fechamento diário (`close_usage_day`) com `nekkora_retention_executor`. Ativar o expurgo **somente** depois de fechar e validar o contrato de fingerprint e retenção | Pendente |

## 4. Critérios para considerar a etapa operacional validada

1. Credenciais configuradas fora do repositório e testadas por serviço; nenhum fallback de senha fora do ambiente local.
2. Conexões aceitas apenas das origens autorizadas, **só com TLS** (`hostssl`) e `scram-sha-256`, sem nenhuma entrada `trust`.
3. Uma **instalação** e uma **atualização de base populada** exercitadas pelo processo de migration escolhido, incluindo os riscos do C4.
4. **Reset do pool** comprovado: depois de um `SET` de sessão, a conexão devolvida e reutilizada não carrega contexto organizacional.
5. **Backup restaurado em ambiente isolado**, com a redação de eventos e logs reaplicada antes da liberação (C7) e o resultado conferido.
6. **Identidade de backup escolhida e registrada**, com os privilégios correspondentes e sem reutilizar papel de serviço.
7. Jobs, alertas, timeouts e retenções definidos; expurgo desligado até a revisão do fingerprint.

## 5. Limites deste relatório

A autorização efetiva de usuários e clientes ainda será implementada na API. RLS e privilégios do banco são uma camada de isolamento sobre um contexto que a API já validou; não substituem esse controle. Este relatório organiza os próximos controles e **não** declara conformidade com a LGPD nem prontidão para produção.
