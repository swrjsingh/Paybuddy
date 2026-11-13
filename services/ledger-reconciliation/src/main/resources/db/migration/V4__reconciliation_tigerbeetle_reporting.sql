CREATE TABLE tigerbeetle_postings (
  id TEXT PRIMARY KEY,
  payment_intent_id TEXT NOT NULL UNIQUE,
  order_id TEXT NOT NULL,
  merchant_id TEXT NOT NULL,
  provider_id TEXT NOT NULL,
  amount_minor BIGINT NOT NULL CHECK(amount_minor>0),
  currency TEXT NOT NULL,
  transfer_id_hi BIGINT NOT NULL,
  transfer_id_lo BIGINT NOT NULL,
  debit_account_id BIGINT NOT NULL,
  credit_account_id BIGINT NOT NULL,
  status TEXT NOT NULL DEFAULT 'PENDING',
  attempts INT NOT NULL DEFAULT 0,
  last_error TEXT,
  posted_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE settlement_reports (
  id TEXT PRIMARY KEY,
  report_id TEXT NOT NULL,
  merchant_id TEXT NOT NULL,
  anomaly_mode TEXT NOT NULL DEFAULT 'CLEAN',
  content_hash TEXT NOT NULL,
  row_count INT NOT NULL,
  imported_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(merchant_id, report_id)
);

CREATE TABLE settlement_rows (
  id TEXT PRIMARY KEY,
  settlement_report_id TEXT NOT NULL REFERENCES settlement_reports(id),
  report_id TEXT NOT NULL,
  merchant_id TEXT NOT NULL,
  provider_id TEXT NOT NULL,
  provider_transaction_id TEXT NOT NULL,
  attempt_id TEXT NOT NULL,
  currency TEXT NOT NULL,
  gross_amount_minor BIGINT NOT NULL,
  fee_amount_minor BIGINT NOT NULL,
  net_amount_minor BIGINT NOT NULL,
  settlement_date TEXT NOT NULL,
  status TEXT NOT NULL,
  row_index INT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE reconciliation_runs (
  id TEXT PRIMARY KEY,
  merchant_id TEXT NOT NULL,
  settlement_report_id TEXT NOT NULL REFERENCES settlement_reports(id),
  report_id TEXT NOT NULL,
  matched_count INT NOT NULL,
  pending_count INT NOT NULL,
  exception_count INT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE reconciliation_items (
  id TEXT PRIMARY KEY,
  run_id TEXT NOT NULL REFERENCES reconciliation_runs(id),
  merchant_id TEXT NOT NULL,
  payment_intent_id TEXT,
  provider_id TEXT NOT NULL,
  provider_transaction_id TEXT NOT NULL,
  classification TEXT NOT NULL,
  expected_amount_minor BIGINT,
  settled_gross_minor BIGINT,
  settled_fee_minor BIGINT,
  settled_net_minor BIGINT,
  currency TEXT NOT NULL,
  detail TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE reconciliation_exceptions (
  id TEXT PRIMARY KEY,
  run_id TEXT NOT NULL REFERENCES reconciliation_runs(id),
  merchant_id TEXT NOT NULL,
  payment_intent_id TEXT,
  provider_id TEXT NOT NULL,
  provider_transaction_id TEXT NOT NULL,
  classification TEXT NOT NULL,
  expected_amount_minor BIGINT,
  settled_amount_minor BIGINT,
  currency TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'OPEN',
  detail TEXT NOT NULL,
  resolution_action TEXT,
  resolution_note TEXT,
  resolved_by TEXT,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE reconciliation_audit_log (
  id TEXT PRIMARY KEY,
  exception_id TEXT NOT NULL REFERENCES reconciliation_exceptions(id),
  actor TEXT NOT NULL,
  previous_status TEXT NOT NULL,
  next_status TEXT NOT NULL,
  action TEXT NOT NULL,
  note TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE reporting_projections (
  merchant_id TEXT PRIMARY KEY,
  confirmed_volume_minor BIGINT NOT NULL DEFAULT 0,
  succeeded_count INT NOT NULL DEFAULT 0,
  upi_volume_minor BIGINT NOT NULL DEFAULT 0,
  generic_volume_minor BIGINT NOT NULL DEFAULT 0,
  last_intent_id TEXT,
  last_event_occurred_at TIMESTAMPTZ,
  last_projected_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_lag_ms BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE reporting_controls (
  id TEXT PRIMARY KEY,
  paused BOOLEAN NOT NULL DEFAULT FALSE,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO reporting_controls(id, paused) VALUES ('global', FALSE) ON CONFLICT DO NOTHING;

GRANT SELECT, INSERT, UPDATE ON tigerbeetle_postings TO ledger_app;
GRANT SELECT, INSERT, UPDATE ON settlement_reports TO ledger_app;
GRANT SELECT, INSERT, UPDATE ON settlement_rows TO ledger_app;
GRANT SELECT, INSERT, UPDATE ON reconciliation_runs TO ledger_app;
GRANT SELECT, INSERT, UPDATE ON reconciliation_items TO ledger_app;
GRANT SELECT, INSERT, UPDATE ON reconciliation_exceptions TO ledger_app;
GRANT SELECT, INSERT, UPDATE ON reconciliation_audit_log TO ledger_app;
GRANT SELECT, INSERT, UPDATE ON reporting_projections TO ledger_app;
GRANT SELECT, INSERT, UPDATE ON reporting_controls TO ledger_app;
