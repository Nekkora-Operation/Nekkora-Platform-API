-- MUTACAO m2 (teste do runner; NUNCA numa base real): remove a trigger que exige marcador de MFA e fator ativo
-- no INSERT de sessao. O t02 PRECISA falhar.
DROP TRIGGER trg_user_sessions_enforce_mfa ON user_sessions;
