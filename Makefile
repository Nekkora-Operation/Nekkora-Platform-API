# Atalhos de desenvolvimento do Nekkora Platform. So encapsula os comandos ja documentados no README.md e no
# api/README.md; os scripts continuam sendo a fonte de verdade (cada um mantem as proprias guardas).
#
# Requisitos: GNU make, bash (Git Bash no Windows), Java 21 e Docker. No Windows: `scoop install make` e rodar o make
# a partir do Git Bash. Uso: `make` ou `make help`.

ifeq ($(OS),Windows_NT)
SHELL := bash.exe
else
SHELL := /bin/bash
endif

.DEFAULT_GOAL := help

API      := api
MVNW     := ./mvnw
VERSAO   := 0.1.0-SNAPSHOT
JAR_PLATFORM := $(API)/nekkora-platform/target/nekkora-platform-$(VERSAO).jar
JAR_PORTAL   := $(API)/nekkora-portal/target/nekkora-portal-$(VERSAO).jar

# --- ambiente de desenvolvimento no host (Opcao 2 do README) -------------------------------------------------------
# Migrations montadas: 001 ate DEV_MIG_ATE (padrao 015; a 016 em diante sao candidatas e ficam de fora).
DEV_MIG_ATE    ?= 015
DEV_PG         ?= nekkora-dev-postgres
DEV_REDIS      ?= nekkora-dev-redis
DEV_PG_PORTA   ?= 55432
DEV_REDIS_PORTA ?= 56379
# Senhas SO de desenvolvimento local (as mesmas do README). Nunca use fora da maquina local.
DEV_SUPER_SENHA    ?= dev-super
DEV_PLATFORM_SENHA ?= dev-platform
DEV_PORTAL_SENHA   ?= dev-portal

# Suite de banco para `make db-test S=<nome>` (pasta em db/tests/)
S ?=
# Modulo Maven para `make test-modulo M=<modulo>`
M ?=

.PHONY: help build test test-modulo verify clean e2e bench \
        db-tests db-test db-tests-lista \
        dev-up dev-senhas dev-platform dev-portal dev-status dev-down \
        lab-subir lab-front lab-estado lab-caixa lab-link lab-convite lab-parar \
        stack-up stack-down stack-logs validar-016

help: ## Lista os alvos
	@grep -hE '^[a-zA-Z0-9_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}'

# --- API (Maven) ---------------------------------------------------------------------------------------------------

build: ## Empacota os jars sem rodar testes
	cd $(API) && $(MVNW) -q -DskipTests package

test: ## Unitarios e integracao (Testcontainers), como no CI
	cd $(API) && $(MVNW) -B -ntp test

test-modulo: ## Testes de um modulo: make test-modulo M=nekkora-platform
	@[ -n "$(M)" ] || { echo "ERRO: informe M=<modulo> (nekkora-core, nekkora-ops, nekkora-platform, nekkora-portal)" >&2; exit 2; }
	cd $(API) && $(MVNW) -B -ntp -pl $(M) -am test

verify: ## mvn verify em todos os modulos
	cd $(API) && $(MVNW) -B -ntp verify

clean: ## Remove os target/ do Maven
	cd $(API) && $(MVNW) -q clean

e2e: ## Stack E2E descartavel (nginx + Platform + Portal + PG + Redis); aceita E2E_PROJETO, E2E_PORTA, E2E_PG_PORTA
	$(API)/e2e/run.sh

bench: ## Benchmark do Argon2id; aceita BENCH_CPUS, BENCH_MEMORIA, BENCH_PERFIS
	$(API)/bench-argon2/run.sh

# --- suites de banco (db/tests) ------------------------------------------------------------------------------------

db-tests-lista: ## Lista as suites de banco
	@for f in db/tests/*/run.sh; do echo "  $$(basename "$$(dirname "$$f")")"; done

db-test: ## Roda uma suite: make db-test S=acessos-023
	@[ -n "$(S)" ] || { echo "ERRO: informe S=<suite> (veja make db-tests-lista)" >&2; exit 2; }
	@[ -f "db/tests/$(S)/run.sh" ] || { echo "ERRO: db/tests/$(S)/run.sh nao existe" >&2; exit 2; }
	db/tests/$(S)/run.sh

db-tests: ## Roda TODAS as suites de banco em sequencia e resume o resultado (demorado)
	@falhas=""; \
	for f in db/tests/*/run.sh; do \
	  s="$$(basename "$$(dirname "$$f")")"; \
	  echo "==> $$s"; \
	  if "$$f"; then echo "<== $$s: OK"; else echo "<== $$s: FALHOU"; falhas="$$falhas $$s"; fi; \
	done; \
	if [ -n "$$falhas" ]; then echo "Suites com falha:$$falhas" >&2; exit 1; fi; \
	echo "Todas as suites passaram."

# --- desenvolvimento no host (Opcao 2 do README) -------------------------------------------------------------------

dev-up: ## Sobe PG 16 (migrations 001..DEV_MIG_ATE) e Redis descartaveis e define as senhas dos papeis
	@if docker ps -a --format '{{.Names}}' | grep -qxE '$(DEV_PG)|$(DEV_REDIS)'; then \
	  echo "ERRO: $(DEV_PG) ou $(DEV_REDIS) ja existe; rode make dev-down antes" >&2; exit 1; fi
	MSYS_NO_PATHCONV=1 docker create --name $(DEV_PG) \
	  -e POSTGRES_DB=nekkora -e POSTGRES_USER=nekkora -e POSTGRES_PASSWORD=$(DEV_SUPER_SENHA) \
	  -p 127.0.0.1:$(DEV_PG_PORTA):5432 postgres:16-alpine >/dev/null
	@# Modelo do migrador (como o api/e2e/run.sh): as migrations NAO vao para o initdb.d (seriam aplicadas pelo
	@# superusuario, e a 016 em diante recusa); o 000_ chama db/bootstrap/aplicar_como_migrador.sh sobre /nekkora/migracoes.
	@set -e; ate=$$((10#$(DEV_MIG_ATE))); t="$$(mktemp -d)"; trap 'rm -rf "$$t"' EXIT; \
	mkdir -p "$$t/nekkora/migracoes" "$$t/init"; \
	for f in db/migrations/[0-9][0-9][0-9]_*.sql; do \
	  n="$$(basename "$$f" | cut -c1-3)"; \
	  [ "$$((10#$$n))" -le "$$ate" ] || continue; \
	  cp "$$f" "$$t/nekkora/migracoes/"; echo "  montada $$(basename "$$f")"; \
	done; \
	cp db/bootstrap/aplicar_como_migrador.sh "$$t/nekkora/"; \
	printf '#!/bin/sh\nset -e\nNEKKORA_MIGRACOES=/nekkora/migracoes sh /nekkora/aplicar_como_migrador.sh\n' > "$$t/init/000_aplicar_como_migrador.sh"; \
	chmod 0755 "$$t/init/000_aplicar_como_migrador.sh"; \
	hp="$$(cygpath -m "$$t" 2>/dev/null || echo "$$t")"; \
	MSYS_NO_PATHCONV=1 docker cp "$$hp/nekkora/." "$(DEV_PG):/nekkora"; \
	MSYS_NO_PATHCONV=1 docker cp "$$hp/init/." "$(DEV_PG):/docker-entrypoint-initdb.d"
	docker start $(DEV_PG) >/dev/null
	docker run -d --name $(DEV_REDIS) -p 127.0.0.1:$(DEV_REDIS_PORTA):6379 redis:7-alpine \
	  redis-server --save "" --appendonly no >/dev/null
	@echo "Aguardando o PostgreSQL concluir a inicializacao..."; \
	for i in $$(seq 1 120); do \
	  if [ "$$(docker logs $(DEV_PG) 2>&1 | grep -c 'database system is ready to accept connections')" -ge 2 ]; then break; fi; \
	  if [ "$$(docker inspect -f '{{.State.Running}}' $(DEV_PG))" != true ]; then docker logs --tail 40 $(DEV_PG) >&2; echo "ERRO: o PostgreSQL parou" >&2; exit 1; fi; \
	  sleep 1; \
	  [ "$$i" -lt 120 ] || { echo "ERRO: tempo esgotado esperando o PostgreSQL" >&2; exit 1; }; \
	done; \
	reg="$$(docker logs $(DEV_PG) 2>&1 | grep 'aplicar_como_migrador' || true)"; \
	if echo "$$reg" | grep -q 'aplicar_como_migrador: FALHA' || ! echo "$$reg" | grep -Eq 'aplicar_como_migrador: fim db=nekkora aplicadas=[0-9]+ estado=0\|0\|0'; then \
	  echo "$$reg" | tail -20 >&2; echo "ERRO: o migrador nao terminou em 0|0|0 (docker logs $(DEV_PG))" >&2; exit 1; fi; \
	echo "$$reg" | grep 'aplicar_como_migrador: fim db='
	@$(MAKE) --no-print-directory dev-senhas
	@echo "Pronto: PG em 127.0.0.1:$(DEV_PG_PORTA), Redis em 127.0.0.1:$(DEV_REDIS_PORTA). Agora: make dev-platform / make dev-portal"

dev-senhas: ## Define as senhas de desenvolvimento dos papeis nekkora_platform e nekkora_portal
	docker exec -i $(DEV_PG) psql -X -q -v ON_ERROR_STOP=1 -U nekkora -d nekkora \
	  -c "ALTER ROLE nekkora_platform PASSWORD '$(DEV_PLATFORM_SENHA)'" \
	  -c "ALTER ROLE nekkora_portal PASSWORD '$(DEV_PORTAL_SENHA)'"

dev-platform: $(JAR_PLATFORM) ## Roda a Platform no host (porta 8081) contra o dev-up; chaves MFA/HMAC aleatorias se nao vierem do ambiente
	NEKKORA_DB_URL=jdbc:postgresql://localhost:$(DEV_PG_PORTA)/nekkora NEKKORA_DB_SENHA=$(DEV_PLATFORM_SENHA) \
	NEKKORA_REDIS_HOST=localhost NEKKORA_REDIS_PORT=$(DEV_REDIS_PORTA) \
	NEKKORA_ORIGEM_PUBLICA="$${NEKKORA_ORIGEM_PUBLICA:-http://localhost:8081}" \
	NEKKORA_MFA_CHAVE_ID="$${NEKKORA_MFA_CHAVE_ID:-k1}" \
	NEKKORA_MFA_CHAVES="$${NEKKORA_MFA_CHAVES:-k1:$$(head -c 32 /dev/urandom | base64)}" \
	NEKKORA_HMAC_ID="$${NEKKORA_HMAC_ID:-h1}" \
	NEKKORA_HMAC_CHAVE="$${NEKKORA_HMAC_CHAVE:-$$(head -c 32 /dev/urandom | base64)}" \
	java -jar $(JAR_PLATFORM)

dev-portal: $(JAR_PORTAL) ## Roda o Portal no host (porta 8082) contra o dev-up
	NEKKORA_PORTAL_DB_URL=jdbc:postgresql://localhost:$(DEV_PG_PORTA)/nekkora NEKKORA_PORTAL_DB_SENHA=$(DEV_PORTAL_SENHA) \
	NEKKORA_REDIS_HOST=localhost NEKKORA_REDIS_PORT=$(DEV_REDIS_PORTA) \
	java -jar $(JAR_PORTAL)

$(JAR_PLATFORM) $(JAR_PORTAL):
	cd $(API) && $(MVNW) -q -DskipTests package

dev-status: ## Mostra os containers de desenvolvimento e confere as rotas basicas
	@docker ps -a --filter name=$(DEV_PG) --filter name=$(DEV_REDIS) --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'
	@printf 'Platform /api/v1/csrf: '; curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8081/api/v1/csrf || echo 'fora do ar'
	@printf 'Portal (sem sessao, espera 401): '; curl -s -o /dev/null -w '%{http_code}\n' \
	  http://localhost:8082/portal/api/v1/o/00000000-0000-4000-8000-000000000000/workspaces || echo 'fora do ar'

dev-down: ## Remove SO os containers do dev-up (nekkora-dev-postgres e nekkora-dev-redis)
	-docker rm -f $(DEV_PG) $(DEV_REDIS)

# --- laboratorio local completo (frontend + API real), do repositorio Nekkora-Platform-Web ---------------------------
# Camada fina sobre tools/lab/local.sh do frontend (guia: $(WEB)/docs/USO-LOCAL.md): PG com todas as migrations pelo
# migrador, Redis, Platform, Portal, canal do Agent, nginx com TLS e caixa de e-mail local (nada sai da maquina).

WEB ?= ../Nekkora-Platform-Web
LAB := cd $(WEB) && bash tools/lab/local.sh
E ?=

lab-subir: ## Laboratorio: sobe o ambiente ja instalado (dados preservados)
	@[ -f "$(WEB)/tools/lab/local.sh" ] || { echo "ERRO: frontend nao encontrado em $(WEB) (use WEB=<pasta>)" >&2; exit 2; }
	$(LAB) subir

lab-front: ## Laboratorio: frontend em http://localhost:5173 (Ctrl+C encerra)
	$(LAB) front

lab-estado: ## Laboratorio: containers, resposta da API e certificado
	$(LAB) estado

lab-caixa: ## Laboratorio: lista as mensagens da caixa de e-mail local (sem links)
	$(LAB) caixa

lab-link: ## Laboratorio: link da ultima confirmacao de cadastro: make lab-link E=voce@local.test
	@[ -n "$(E)" ] || { echo "ERRO: informe E=<e-mail>" >&2; exit 2; }
	$(LAB) link "$(E)"

lab-convite: ## Laboratorio: link do ultimo convite: make lab-convite E=pessoa@local.test
	@[ -n "$(E)" ] || { echo "ERRO: informe E=<e-mail>" >&2; exit 2; }
	$(LAB) convite "$(E)"

lab-parar: ## Laboratorio: para tudo SEM apagar dados
	$(LAB) parar

# --- stack principal (docker-compose.yml, volume PERSISTENTE) ------------------------------------------------------

stack-up: ## Sobe o nekkora-postgres persistente (ATENCAO: volume vazio aplica TODAS as migrations, inclusive candidatas)
	@echo "ATENCAO: num volume vazio, o docker-compose.yml aplica todas as migrations de db/migrations/, inclusive as candidatas."
	docker compose up -d

stack-down: ## Para a stack principal (mantem o volume)
	docker compose down

stack-logs: ## Logs da stack principal
	docker compose logs -f

# --- operacao ------------------------------------------------------------------------------------------------------

validar-016: ## Validacao completa da 016 (suite de banco, Maven, mutacoes, varredura)
	ops/mfa-emergencia/validar.sh
