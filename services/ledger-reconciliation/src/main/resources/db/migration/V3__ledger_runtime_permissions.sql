GRANT USAGE ON SCHEMA public TO ledger_app;
GRANT SELECT, INSERT ON ledger_entries TO ledger_app;
GRANT SELECT, INSERT ON processed_events TO ledger_app;
GRANT SELECT, INSERT, UPDATE ON outbox TO ledger_app;
