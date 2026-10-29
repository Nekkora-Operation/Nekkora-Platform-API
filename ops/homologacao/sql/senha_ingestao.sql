-- Senha do papel do CANAL DO AGENT (nekkora_ingestion, criado pela 012 com LOGIN e sem senha; usado pelo processo
-- nekkora-agent-gateway da 025) - papel: admin_cluster. Usado SO por `homologacao.sh agente <id> ligar` (E-AP-1).
-- Recebe -v verif_ingestion: VERIFICADOR SCRAM (a senha em texto claro nunca vai ao servidor). Nenhum outro atributo muda.
\set ON_ERROR_STOP on
ALTER ROLE nekkora_ingestion PASSWORD :'verif_ingestion';
