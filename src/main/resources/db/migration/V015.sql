ALTER TABLE company_cash_accounts ADD COLUMN accumulated_loss_minor INTEGER NOT NULL DEFAULT 0 CHECK (accumulated_loss_minor >= 0);

CREATE TABLE company_operating_events (
  id TEXT PRIMARY KEY,
  company_id TEXT NOT NULL REFERENCES companies(id),
  binding_id TEXT NOT NULL REFERENCES asset_bindings(id),
  adapter_id TEXT NOT NULL,
  external_event_key TEXT NOT NULL,
  kind TEXT NOT NULL CHECK (kind IN ('INCOME', 'EXPENSE')),
  amount_minor INTEGER NOT NULL CHECK (amount_minor > 0),
  occurred_at TEXT NOT NULL,
  recorded_at TEXT NOT NULL,
  metadata_json TEXT NOT NULL,
  UNIQUE (adapter_id, external_event_key)
);

CREATE INDEX company_operating_events_company_time ON company_operating_events(company_id, occurred_at, id);

CREATE TABLE company_monthly_reports (
  company_id TEXT NOT NULL REFERENCES companies(id),
  period_start TEXT NOT NULL,
  period_end TEXT NOT NULL,
  income_minor INTEGER NOT NULL CHECK (income_minor >= 0),
  expense_minor INTEGER NOT NULL CHECK (expense_minor >= 0),
  net_profit_minor INTEGER NOT NULL,
  retained_earnings_minor INTEGER NOT NULL CHECK (retained_earnings_minor >= 0),
  cash_minor INTEGER NOT NULL CHECK (cash_minor >= 0),
  generated_at TEXT NOT NULL,
  PRIMARY KEY (company_id, period_start)
);
