-- restauracao das triggers de user_sessions (identicas a 014)
CREATE TRIGGER trg_user_sessions_enforce_mfa BEFORE INSERT ON user_sessions FOR EACH ROW EXECUTE FUNCTION enforce_user_session_mfa_on_insert();
CREATE TRIGGER trg_user_sessions_prevent_reactivation BEFORE UPDATE ON user_sessions FOR EACH ROW EXECUTE FUNCTION prevent_user_session_reactivation();
