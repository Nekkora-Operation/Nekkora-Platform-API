# API v1 — Platform e portal externo (Spring Boot)

Implementação do contrato fechado em `docs/relatorios/2026-09-26-decisoes-8-6-8-7-8-10.md` (revisão 4.1). Relatório de validação: `docs/relatorios/2026-09-26-validacao-api-v1.md`.

**Não está pronta para produção.** Os gates da §7 do registro de decisões continuam abertos.

## Módulos

| Módulo | Processo | Papel no banco | Conteúdo |
|---|---|---|---|
| `nekkora-core` | — | — | Contexto por transação (`set_config` por bind), reset de conexão (`DISCARD ALL`), erros HTTP, IP do proxy confiável, limitador Redis, máscara de log |
| `nekkora-platform` | Platform (porta 8081) | `nekkora_platform` | Login, MFA TOTP, sessões, CSRF, senha, administração |
| `nekkora-portal` | Portal (porta 8082) | `nekkora_portal` | Só `GET`; só as funções da 013 |

## Requisitos

- Java 21. O Maven vem pelo wrapper (`./mvnw`, 3.9.16).
- Docker, para os testes: Testcontainers sobe PostgreSQL 16 e Redis descartáveis.
- Git Bash (Windows), Linux ou macOS para os scripts `run.sh`.

Nenhum teste usa a stack principal (`nekkora-postgres` / porta 5432).

## Comandos

```sh
cd api
./mvnw test                     # unitários + integração HTTP/PostgreSQL/Redis com papéis reais (*Test, *IT)
e2e/run.sh                      # stack entre processos: nginx (TLS, origem única) -> Platform + Portal -> PG 16 + Redis
bench-argon2/run.sh             # Argon2id no container com perfil declarado (BENCH_CPUS, BENCH_MEMORIA, BENCH_PERFIS)
```

Suítes de banco (a partir da raiz do repositório):

```sh
db/tests/seguranca-012/run.sh        # 001-012 montadas explicitamente
db/tests/portal-013/run.sh           # 001-013 montadas explicitamente
db/tests/mfa-014/run.sh              # 001-014
db/tests/restauracao-015/run.sh      # backup logico antigo x novo; restauracao completa e procedimento para backup antigo
db/tests/atualizacao-populada/run.sh # 001-012 populada -> Flyway (baseline manual) -> 013 -> 014 -> 015
```

Cada `run.sh` usa projeto, rede e porta próprios, e recusa rodar se esses recursos já existirem. Todos aceitam `TESTE_PROJETO`/`TESTE_PORTA` (ou `E2E_*`/`ENSAIO_*`) para rodar ao lado de outra execução. As saídas ficam em `saidas/`, fora do versionamento na API.

## Configuração (só por ambiente; nada versionado)

**Platform:**
- `NEKKORA_DB_URL`, `NEKKORA_DB_SENHA` (papel `nekkora_platform`);
- `NEKKORA_REDIS_HOST`/`PORT`;
- `NEKKORA_ORIGEM_PUBLICA`;
- `NEKKORA_PROXIES_CONFIAVEIS` (CIDRs);
- `NEKKORA_MFA_CHAVE_ID` e `NEKKORA_MFA_CHAVES` (`k1:<base64 de 32 bytes>`, lista separada por vírgula);
- `NEKKORA_HMAC_ID` e `NEKKORA_HMAC_CHAVE`;
- opcionais `NEKKORA_ARGON2_M`/`T`.

**Portal:**
- `NEKKORA_PORTAL_DB_URL`, `NEKKORA_PORTAL_DB_SENHA` (papel `nekkora_portal`);
- `NEKKORA_REDIS_HOST`/`PORT`;
- `NEKKORA_PROXIES_CONFIAVEIS`.

O portal **não recebe** nenhuma variável da Platform. O `e2e/run.sh` confere isso no ambiente dos containers.

Os limites (R1–R7), a sessão e o Argon2 estão em `application.yml`. São a **configuração inicial de teste** aprovada, sujeita a calibração.

## Nomes

Classes, métodos, campos e testes estão em inglês (`SessionFilter`, `AccountRepository`, `CrossProcessE2E`…). As chaves de configuração também (`nekkora.database.password`, `nekkora.limits.r1-failures`…). Continuam em português, por decisão pendente, as **variáveis de ambiente** (`NEKKORA_DB_SENHA`, `NEKKORA_ORIGEM_PUBLICA`…), os comentários, as mensagens de log e os scripts das suítes de banco.
