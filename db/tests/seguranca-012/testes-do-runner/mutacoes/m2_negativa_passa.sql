-- M2: operacao negativa passa a ter sucesso sem mudar o catalogo de privilegios: a trigger de
-- bootstrap e desabilitada, entao o INSERT com responsavel nulo pelo platform (05b/B06) e aceito.
ALTER TABLE organization_role_assignments DISABLE TRIGGER trg_org_role_assignments_bootstrap;
