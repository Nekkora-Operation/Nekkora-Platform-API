# AMBIENTE-PYTHON-001 — contrato (API, canal da máquina, Client e Web)

Decisões da titular de 24/11/2026: D1–D7 em `ambiente-python-001/PROPOSTA.md` (fora do repositório), consolidadas aqui.

## 0. Regras que valem para tudo
- **Modo padrão:** continua sendo o **Python preparado** (o de hoje). O modo **ambiente por execução** (MANAGED) só existe
  quando três coisas valem ao mesmo tempo:
  1. a chave `NEKKORA_AMBIENTE_GERENCIADO=true` está ligada na Platform **e** no agent-gateway (padrão `false`; desligada
     em produção);
  2. a máquina declara a capacidade `MANAGED_ENV_1_0`;
  3. há um **aceite vigente** para a versão e a máquina.
- **Nada é instalado no Python da máquina.** Um venv temporário é criado **a partir** de uma **instalação** Python do
  computador (D6) e apagado depois.
- **D1, lista completa antes do aceite:**
  - a máquina **resolve** a lista efetiva, com as dependências indiretas, só com wheels (D2), no índice público (D3), para o
    Python e o sistema dela;
  - a Platform guarda a lista completa (**lock**) com nome, versão, SHA-256 e arquivo;
  - a pessoa aceita **esse lock**, e a execução instala exatamente ele (`--no-deps --require-hashes`);
  - lock diferente exige aceite novo.
- **D3, origem:** só o PyPI público (`https://pypi.org/simple`). A declaração continua recusando índices, URLs, Git e
  caminhos (`RequirementsText`: REMOTE, LOCAL_PATH, OPTION e INCLUDE são problemas). O Client só aceita outro índice pela
  propriedade de laboratório `nekkora.client.managedIndexUrl`, e **só** quando a `apiOrigin` dele é `localhost` ou
  `127.0.0.1`.
- **Leitura das dependências pela versão pedida (correção A1):** a lista efetiva de um pedido de execução, de uma resolução
  ou de um aceite usa o manifesto **da versão indicada**, não o da última publicada.

## 1. Banco: migration `076_ambiente_gerenciado_por_execucao.sql` (candidata; renumera se outra entrar antes)
| Tabela | Quem grava | Conteúdo |
|---|---|---|
| `managed_environment_resolutions` | Platform insere; máquina responde (UPDATE de colunas) | pedido de resolução: org, workspace, automação, **versão**, máquina, `environment_id` base, `requirements_revision`, `dependencies` (lista efetiva da versão, congelada); resposta: `outcome` (1 RESOLVED, 2 INCOMPATIBLE, 3 ENVIRONMENT_GONE, 4 NOT_RESOLVED), `lock` (jsonb), `lock_sha256`, `python_version`, `pip_version`, `failure_requirement`, `failure_phase`, `failure_summary`; prazo `answer_valid_until` (≤ 30 min) |
| `managed_environment_authorizations` | Platform (insere e revoga) | aceite: versão + máquina + Worker + `environment_id` base + `requirements_revision` + `resolution_id` + `lock_sha256` + `checks` (verificações externas escolhidas) + quem e quando; **um vigente** por (versão, máquina) |
| `remote_attempt_environments` | Platform, ao criar a tentativa MANAGED | retrato da tentativa: `authorization_id`, `lock`, `lock_sha256`, `checks` |
| `remote_attempt_environment_stages` | máquina | `(attempt_id, stage)` com `reached_at`; `stage` 1 ENV_CREATING, 2 DEPS_INSTALLING |
| `remote_attempt_environment_reports` | máquina insere uma vez; Platform só apaga o registro do preparo (retenção) | `outcome` 1 READY, 2 FAILED, 3 INTERRUPTED; `python_version`, `pip_version`, `installed` (lista), `failure_requirement`, `failure_phase`, `failure_summary` (≤ 500), `create_ms`, `install_ms`, `preparation_log` (≤ 64 KiB, sem segredos), `log_expired_at`, `reported_at` |
| `remote_executions.environment_mode` | Platform | 1 PREPARED (padrão para todas as linhas existentes), 2 MANAGED |

- **Motivos novos de recusa da máquina** (CHECK de `remote_attempts.refusal_reason`): `ENVIRONMENT_SETUP_FAILED`,
  `DEPENDENCY_INSTALL_FAILED`, `ENVIRONMENT_SETUP_INTERRUPTED` e `EXTERNAL_PREREQUISITE_MISSING`.
- **Fases de falha:**
  - na resolução: `NO_COMPATIBLE_WHEEL`, `RESOLUTION_CONFLICT`, `NOT_FOUND`, `INDEX_UNREACHABLE`, `PIP_UNAVAILABLE`,
    `TIMEOUT` e `OTHER`;
  - no preparo: `VENV`, `DOWNLOAD`, `HASH_MISMATCH`, `NO_COMPATIBLE_WHEEL`, `INSTALL`, `VERIFY`, `PREREQUISITE`, `NO_SPACE`,
    `TIMEOUT` e `OTHER`.
- **RLS:** por organização, como nas demais. Privilégios mínimos (o invariante na própria migration confere): a máquina
  (`nekkora_ingestion`) nunca insere aceite e a Platform nunca escreve a resposta da máquina.
- **D7, retenção do registro do preparo:** **30 dias contados de `reported_at`**, com ou sem início da automação (vale
  também para o preparo que falhou e deixou a tentativa "não iniciada"). Ao vencer, `preparation_log` vira NULL e
  `log_expired_at` é gravado; o resumo estruturado da falha fica. Quem faz isso é o ciclo de retenção existente (057). Antes
  de gravar, o texto passa pela remoção de segredos da Platform (`LogRedaction`, mais credenciais em URL `://usuario:senha@`
  e cabeçalhos `Authorization`); o Client também remove antes de enviar.

## 2. Platform: rotas para pessoas (`/api/v1/o/{org}`)
Todas devolvem **404 `managed_environment_unavailable`** com a chave desligada.

### 2.1 `GET /automations/{a}/remote-machines/{agent}/managed-environment?versionId={v}`
Permissão `automation.read` no ambiente.
```json
{
  "available": true,
  "machineCompatible": true,
  "baseEligible": true,
  "basePython": {"environmentId": "…43…", "pythonVersion": "3.12.10", "kind": "INSTALLATION"},
  "requirements": {"revision": 3, "dependencies": ["requests==2.32.3"]},
  "resolution": {
    "resolutionId": "uuid", "state": "PENDING|ANSWERED|UNANSWERED", "outcome": "RESOLVED|INCOMPATIBLE|ENVIRONMENT_GONE|NOT_RESOLVED",
    "requestedAt": "…", "answerValidUntil": "…", "answeredAt": "…",
    "pythonVersion": "3.12.10", "pipVersion": "24.3.1",
    "lock": [{"name": "requests", "version": "2.32.3", "sha256": "…64…", "fileName": "requests-2.32.3-py3-none-any.whl"}],
    "lockSha256": "…64…",
    "failure": {"requirement": "pandas==2.2.3", "phase": "NO_COMPATIBLE_WHEEL", "summary": "…"},
    "current": true,
    "waitingForExecution": false
  },
  "externalPrerequisites": [
    {"code": "PLAYWRIGHT_BROWSER", "package": "playwright", "checks": ["PLAYWRIGHT_CHROMIUM", "PLAYWRIGHT_FIREFOX", "PLAYWRIGHT_WEBKIT"]},
    {"code": "ODBC_DRIVER", "package": "pyodbc", "checks": []}
  ],
  "authorization": {
    "authorizationId": "uuid", "status": "CURRENT|STALE", "staleReasons": ["REQUIREMENTS_CHANGED", "PYTHON_CHANGED"],
    "lockSha256": "…", "packages": 12, "checks": ["PLAYWRIGHT_CHROMIUM"], "grantedAt": "…", "requirementsRevision": 3,
    "diff": {"added": ["x==1"], "removed": ["y==2"], "changed": [{"name": "z", "from": "1.0", "to": "1.1"}]}
  }
}
```
- `resolution` e `authorization` são nulos quando não existem.
- `current` diz se a resolução ainda corresponde ao Python escolhido, à revisão vigente e à versão.
- `waitingForExecution` (só em PENDING): a máquina está com uma tentativa remota recebida e ainda não terminada (RECEIVED a RUNNING). Com uma tentativa no diário o aplicativo não consulta trabalho novo, então a lista só é gerada quando ela terminar, se ainda houver prazo. A tela diz isso.
- `diff` só aparece em STALE e compara o lock aceito com o da resolução corrente, quando houver.
- `externalPrerequisites` vem do catálogo da Platform aplicado ao lock (ou, sem lock, à lista efetiva).

### 2.2 `POST /automations/{a}/remote-machines/{agent}/managed-environment/resolutions` `{ "versionId": "…" }`
Permissão `automation.manage`; sem confirmação reforçada (não autoriza nada).

| Recusa | Quando |
|---|---|
| 409 `managed_environment_machine_incompatible` | sem `MANAGED_ENV_1_0` |
| 409 `managed_environment_base_not_installation` | o Python escolhido é um venv (D6) |
| 409 `remote_python_not_selected` / `remote_python_not_in_inventory` | sem Python escolhido, ou fora do inventário |
| 409 `remote_requirements_not_declared` / `remote_requirements_conflict` | dependências não declaradas, ou em conflito |
| 409 `managed_environment_resolution_pending` | já há um pedido em aberto para (automação, máquina) |

Devolve 201 com o mesmo objeto de 2.1.

### 2.3 `POST …/managed-environment/authorizations`
Corpo: `{ "versionId", "resolutionId", "lockSha256", "checks": ["PLAYWRIGHT_CHROMIUM"], "acknowledge": true }`.
Permissão `automation.manage`, com confirmação reforçada. Exige uma resolução RESOLVED **corrente** com o mesmo
`lockSha256`. Quando o lock tem `playwright`, exige ao menos uma verificação `PLAYWRIGHT_*`. Revoga o aceite anterior de
(versão, máquina) e grava o ato 13 na trilha da automação. Responde 201 com o objeto de 2.1.

| Recusa | Quando |
|---|---|
| 409 `managed_environment_resolution_not_current` | a resolução não é mais a corrente |
| 409 `managed_environment_lock_changed` | o `lockSha256` enviado não confere |
| 400 | `acknowledge` ausente |

### 2.4 `DELETE …/managed-environment/authorizations/current?versionId={v}`
Responde 204. Confirmação reforçada. Revoga o aceite e grava o ato 14: a máquina volta ao Python preparado.

### 2.5 Solicitar e repetir
- `POST /workspaces/{w}/remote-executions` aceita `environmentMode: "PREPARED"|"MANAGED"`; ausente vale PREPARED.
  MANAGED exige:
  - a chave ligada, a capacidade e o aceite vigente de (versão, máquina);
  - o aceite ainda correspondente: revisão vigente igual, Python escolhido igual ao base e lock da resolução aceita.
- Recusas da solicitação: 409 `managed_environment_not_authorized` e 409 `managed_environment_changed`.
- `…/retry` mantém o modo da solicitação. No modo MANAGED, usa o aceite da máquina **de destino**.
- `Execution` ganha `environmentMode`.
- `Attempt` ganha `environment`, nulo no modo PREPARED:
```json
{"lockSha256": "…", "packages": 12, "checks": ["PLAYWRIGHT_CHROMIUM"],
 "stages": [{"stage": "ENV_CREATING", "at": "…"}, {"stage": "DEPS_INSTALLING", "at": "…"}],
 "report": {"outcome": "READY|FAILED|INTERRUPTED", "pythonVersion": "…", "pipVersion": "…", "installed": [{"name": "…", "version": "…", "sha256": "…"}],
            "failure": {"requirement": "…", "phase": "…", "summary": "…"}, "createMs": 8000, "installMs": 41000,
            "logAvailable": true, "logExpiredAt": null, "reportedAt": "…"}}
```
- `GET /remote-executions/{x}/attempts/{t}/environment-log`, permissão `automation.read`: devolve `text/plain` com o
  registro do preparo, já sem segredos; 410 `managed_environment_log_expired` depois de 30 dias.
- `candidates` (lista de máquinas da versão) ganha, por máquina:
  `managedEnvironment: {"compatible": bool, "authorized": "NONE|CURRENT|STALE"}`.
- `GET /api/v1/me` ganha `managedEnvironmentAvailable` (a chave ligada e a execução remota disponível).

## 3. Canal da máquina (`/agent/api/v1`)
- **Presença.** A resposta ganha `managedEnvironments: true` só com a chave ligada. O Client **só declara**
  `MANAGED_ENV_1_0` depois de ver esse campo. Assim, um Client novo contra uma Platform antiga nunca manda uma capacidade
  desconhecida, e a capacidade passa a ser aceita sempre.
- **`GET /remote/next`** ganha `environmentResolution`, que pode vir junto ou sozinho:
  `{"resolutionId", "pythonEnvironmentId", "dependencies": ["a==1"], "index": "PYPI"}`.
- **`POST /remote/environment-resolutions/{id}/answer`**, só uma vez e dentro do prazo (repetir igual devolve o gravado):
```json
{"outcome": "RESOLVED", "pythonVersion": "3.12.10", "pipVersion": "24.3.1",
 "lock": [{"name": "requests", "version": "2.32.3", "sha256": "…", "fileName": "requests-2.32.3-py3-none-any.whl"}],
 "failure": null}
```
  - **RESOLVED:**
    - o lock tem de 0 a 400 itens, sem nomes repetidos (PEP 503);
    - cada item pedido aparece **com a mesma versão**;
    - `sha256` com 64 hex;
    - `fileName` termina em `.whl`, não tem `/` nem `\` e tem no máximo 200 caracteres.
  - **Outros resultados:** `failure` é obrigatório, exceto em ENVIRONMENT_GONE.
  - **Hash do lock:** `lock_sha256` = sha256 das linhas `nome-normalizado==versão sha256` ordenadas, separadas por `\n`. A
    Platform calcula; a máquina não manda.
- **Oferta (`attempt`):** ganha `environment`, ausente no modo PREPARED:
```json
{"mode": "MANAGED", "index": "PYPI", "lockSha256": "…", "lock": [{"name", "version", "sha256", "fileName"}], "checks": ["PLAYWRIGHT_CHROMIUM"]}
```
- **`POST /remote/attempts/{a}/stage`** `{"token", "epoch", "stage": "ENV_CREATING"|"DEPS_INSTALLING"}`:
  - só em PREPARING e só em tentativa MANAGED; idempotente; renova o prazo da comunicação;
  - responde com o `Status` de sempre: `proceed=false` diz para parar, por cancelamento ou porque a tentativa terminou.
- **`PUT /remote/attempts/{a}/environment-report`**, com token e época, aceito com a tentativa em PREPARING ou já terminada,
  inclusive REFUSED:
  `{"outcome": "READY|FAILED|INTERRUPTED", "pythonVersion", "pipVersion", "installed": [...], "failure": {...}, "createMs", "installMs", "log": "…"}`.
  É gravado uma vez: repetir igual devolve o mesmo, e diferente dá 409 `managed_environment_report_conflict`.
- **Revalidação** antes de entregar e antes de STARTING: aceite revogado dá REFUSED `AUTHORIZATION_REVOKED` (motivo que já
  existe).

## 4. Client: comportamento obrigatório
1. **Presença e comunicação nunca param.** Resolução e preparo rodam num executor próprio, um por vez. A presença continua
   a cada 60 s e a busca a cada 15 s; durante o preparo, o Client repete PREPARING (ou a etapa) a cada 30 s para renovar o
   prazo.
2. **Resolução.**
   - Cria um venv temporário: `<base> -I -m venv <remota\resolucoes\<id>\venv>`, com o pip do próprio Python.
   - Exige pip ≥ 22.2; se for menor, NOT_RESOLVED/PIP_UNAVAILABLE.
   - Roda `python -I -m pip install --isolated --no-input --disable-pip-version-check --dry-run --ignore-installed --only-binary=:all: --report <f> --index-url <pypi> (--cache-dir <cache> | --no-cache-dir) -r <reqs>`.
   - O lock sai do `--report` (`metadata.name`, `metadata.version`, `download_info.archive_info.hashes.sha256` e o nome do
     arquivo da URL). Apaga a pasta no fim.
3. **Preparo** (tentativa MANAGED), nesta ordem:
   - diário em `ENV_PREPARING`;
   - etapa ENV_CREATING: venv em `remota\tentativas\<a>\ambiente`;
   - etapa DEPS_INSTALLING: `pip install … --no-deps --require-hashes --only-binary=:all: -r lock.txt` (linhas
     `nome==versão --hash=sha256:…`);
   - conferência: as versões instaladas são exatamente as do lock, e a lista efetiva continua satisfeita;
   - `checks` (Playwright: o executável do navegador pedido existe, **sem instalar nada**);
   - relatório READY;
   - diário em STARTING e lançamento com `ambiente\Scripts\python.exe -E -s -B -u`, com o ambiente reduzido de hoje.
4. **Falha no preparo.** A automação **não** é iniciada. Primeiro vai o relatório FAILED (dependência, fase, resumo e
   registro sem segredos); depois, o estado REFUSED com o motivo.
5. **Reinício.**
   - Só a fase `ENV_PREPARING` vira REFUSED `ENVIRONMENT_SETUP_INTERRUPTED`, com relatório INTERRUPTED. É garantido: o
     lançamento só acontece **depois** de o diário gravar STARTING.
   - STARTING e RUNNING seguem a regra atual: um processo vivo é adotado, e STARTING sem prova vira ENDED_UNKNOWN (resultado
     incerto).
6. **Limpeza.**
   - `ambiente\` e `resolucoes\<id>\` são apagados ao fim, depois de falha e na varredura depois de reinício.
   - Nada é apagado com processo vivo, e a limpeza nunca segue junções nem links.
   - Resultados e registros não entregues seguem a regra de hoje.
7. **Cache (D4).**
   - Fica em `remota\cache-pip`, com limite padrão de 2048 MiB, configurável de 0 a 20480 (0 desativa: `--no-cache-dir`, e o
     cache é apagado com a máquina parada).
   - A poda roda depois de cada resolução ou preparo, só com o executor parado: apaga os arquivos mais antigos até ficar
     abaixo de 80% do limite.
   - O botão "Limpar cache" só funciona com a máquina parada.
8. **Arquitetura.** Uma camada nova, `env`, é a **única** que pode chamar `-m venv` e `pip`, e o `ArchitectureTest` passa a
   provar isso; `pack`, `run` e `nav` continuam proibidas.
9. **Ambiente do pip.** Sem `PIP_*` e sem configuração pessoal (`--isolated`); o proxy vem só da configuração do Client.
   O índice de laboratório segue a regra da seção 0.

## 5. Catálogo de dependências externas (Platform; D5)
| Código | Pacotes | Verificação na máquina | Instrução manual exibida |
|---|---|---|---|
| PLAYWRIGHT_BROWSER | playwright | `PLAYWRIGHT_CHROMIUM`, `PLAYWRIGHT_FIREFOX`, `PLAYWRIGHT_WEBKIT` | instalar os navegadores na máquina com a mesma versão do Playwright do lock; a Nekkora só confere |
| SELENIUM_DRIVER | selenium | — | navegador e driver na máquina; o selenium-manager pode baixar na hora |
| ODBC_DRIVER | pyodbc | — | driver ODBC do banco instalado |
| ORACLE_CLIENT | oracledb, cx-oracle | — | Oracle Instant Client, se usar o modo thick |
| TESSERACT | pytesseract | — | Tesseract OCR instalado e no PATH |
| POPPLER | pdf2image | — | Poppler instalado |
| WKHTMLTOPDF | pdfkit, imgkit | — | wkhtmltopdf instalado |
| JAVA_RUNTIME | tabula-py, jpype1 | — | Java instalado |
