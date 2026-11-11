# PLATFORM-AGENT-V2-003 · T10: organizações identificáveis e nome único por identidade criadora

## Problema
A mesma pessoa conseguia criar várias organizações com o mesmo nome, e a lista "Suas organizações" mostrava essas organizações sem nada que as distinguisse. O ID só aparece abreviado e não há identificador legível.

## Diagnóstico
- **Não existe "proprietária" no modelo.** Não há coluna de dono nem papel de proprietário. `ORG_ADMIN` pode ser várias pessoas, e a primeira atribuição (bootstrap) pode ser revogada como as outras.
- **O único registro de autoria** é quem criou a organização pelo cadastro: o vínculo `identity_users.link_channel = 'cadastro_web'`, que `identity_create_organization_impl` (017) insere só para a conta que acabou de criar, e o evento `identity_provisioning_events` com `channel = 'cadastro_web'`.
- **Não existem** renomeação, transferência nem identificador legível de organização.

## Regra (aprovada em 11/11)
- **Nome único entre as organizações criadas pela mesma identidade pelo cadastro.**
  - É **autoria**, não propriedade, e não concede permissão nenhuma.
  - A identidade é registrada pelo seu identificador persistente (`identities.identity_id`), nunca pelo e-mail nem pela sessão.
- **Participações por convite** não entram na comparação, mesmo como `ORG_ADMIN`.
- **Organizações provisionadas pela operação** ficam fora da restrição, mas recebem identificador legível.
- **Normalização:**
  - qualquer sequência de espaços vira um espaço, contando os do locale e os separadores Unicode que a API já recusa nas pontas: U+00A0, U+2000–U+200A, U+202F, U+205F e U+3000;
  - os espaços das pontas saem;
  - a comparação não distingue maiúsculas e minúsculas;
  - acentos e pontuação **não** são removidos, o que mudaria a regra: "Clínica Ágata" e "Clinica Agata" são nomes diferentes;
  - o nome de apresentação fica como foi digitado.
- **Na interface:** o papel real ("Administração da organização") e nunca "Proprietária". Sem renomeação nem transferência nesta missão.

## Migration 034 (sem elevação; a 017 não é alterada)
- **`organization_authorship`:** organização → identidade criadora, com a chave do nome.
  - Gravada por um gatilho no vínculo de cadastro (`identity_users` com `cadastro_web`), na mesma transação da criação.
  - Imutável: só a chave do nome acompanha uma mudança de nome. Não há rota para mudar o nome, mas, se o papel da Platform mudar, a regra continua valendo.
  - O índice único `ux_organization_authorship_creator_name` usa `(creator_identity_id, md5(name_key))`.
  - Criações simultâneas da mesma identidade: a 017 já trava a identidade, e o índice garante a regra.
- **`organization_handles`:** o identificador legível, único e **estável**.
  - É gerado do nome na criação, para qualquer caminho (cadastro ou operação): letras sem acento, minúsculas, dígitos e hífens, até 40 caracteres; "organizacao" se nada sobrar.
  - Em colisão recebe sufixo `-2`, `-3`… (`organization_handle_allocate` trava cada candidato).
  - Renomear não muda o identificador. Ele não é credencial e não dá acesso: nenhuma rota aceita o identificador no lugar do ID.
- **RLS forçada nas duas tabelas:**
  - a Platform lê no contexto da organização, no padrão da 012;
  - os papéis que criam organizações (`nekkora_identity_owner` no cadastro, `nekkora_provisioning_owner` na operação) veem todas as linhas, porque precisam disso para alocar e gravar a autoria;
  - `EXECUTE` das funções auxiliares fica só para quem precisa.
- **Leitura pela API:** em `GET /api/v1/me/organizations`, para cada organização que `identity_organizations` (017) devolveu, a API lê o identificador numa transação curta **no contexto daquela organização** (`inIdentityAndOrganization`). Não há função `SECURITY DEFINER` nova.

## Dados existentes
1. **Identificador:** atribuído a **todas** as organizações, na ordem de criação. IDs, contas, vínculos, workspaces e histórico não mudam (suíte B: dados idênticos).
2. **Autoria:** preenchida **só com evidência inequívoca**, quando duas provas independentes concordam: exatamente **um** vínculo `cadastro_web` de conta da organização e o **evento de provisionamento** `cadastro_web` dessa mesma conta. Nunca é deduzida de quem é administradora hoje. Sem as duas provas, a organização fica sem autoria (fora da restrição) e aparece no diagnóstico.
3. **Diagnóstico** (somente leitura), antes da 034:
   ```
   psql -X -v ON_ERROR_STOP=1 -d nekkora -f db/diagnosticos/034_organizacoes_por_criadora.sql
   ```
   - **Seção 1:** mesma criadora e mesmo nome. Isso **impede** a 034.
   - **Seção 2:** organizações sem autoria inequívoca.
   - **Seção 3:** identificadores que vão colidir e receber sufixo.
4. **Se houver repetição,** a 034 **para antes de qualquer alteração** e lista as organizações. Nada é excluído, fundido nem renomeado automaticamente; quem decide (renomear ou encerrar uma delas) é uma pessoa.

## Contrato
- **`POST /api/v1/organizations`:** responde 409 `conflict` + `reason: organization_name_taken` e não cria nada. A reserva de R11 é devolvida.
- **`GET /api/v1/me/organizations`:** cada item ganha `handle`, o identificador legível.
- **`Error.reason`:** ganha o valor novo.

## Testes
| Requisito | Teste |
|---|---|
| Duplicação exata, por maiúsculas/minúsculas e por espaços internos (inclusive U+00A0) recusada (409), sem criar nada; espaço nas pontas continua 400; acento distingue; nome de apresentação preservado; autoria pelo `identity_id` | `OrganizacaoNomeUnicoIT.mesmaCriadoraNaoRepeteONomeAposNormalizacao` |
| Duas criações simultâneas da mesma identidade: uma criada, uma 409, sem vazar dados | `criacoesSimultaneasDaMesmaCriadoraGeramUmaSo` |
| Criadoras diferentes com o mesmo nome; quem é `ORG_ADMIN` por convite de uma homônima cria a sua; a lista mostra nome, identificador diferente e papel; cada uma abre a organização certa; sem e-mail ou dado da outra pessoa | `criadorasDiferentesEConvidadaDistingueEAbreCadaUma` |
| Identificador estável depois de renomear; mudar o nome para o de outra organização da mesma criadora é recusado pelo banco; identificador imutável; usado no lugar do ID, não dá acesso | `identificadorEstavelENaoDaAcesso` |
| Organização provisionada pela operação: tem identificador e não tem autoria | `provisionadaPelaOperacaoTemIdentificadorESemAutoria` |
| Migração em base limpa; em base populada (dados idênticos, identificadores com sufixo na ordem de criação, autoria só onde há evidência); com repetição (para sem alterar nada e aplica depois da decisão) | `db/tests/organizacoes-034/run.sh` (A, B e C) |
| Testes anteriores da criação e da lista | `OrganizationCreationIT` (o R11 sob concorrência passa a usar nomes distintos: o objetivo dele é a atomicidade da reserva), `IdentityOrgSessionIT` (a lista exata ganha `handle`) |

## Dependência e limites
- **Numeração:** a 034 vem depois da 033 (T12). Esta branch parte da branch da T12, e o PR da API da T10 deve ser integrado **depois** do PR da API da T12.
- **Sem autoria registrada** (provisionadas ou ambíguas): a organização fica fora da restrição. A decisão sobre os casos ambíguos fica com uma pessoa.
- **Lista de organizações:** custa uma transação curta a mais por organização, com no máximo 10 criadas pela identidade, mais as de convite.
