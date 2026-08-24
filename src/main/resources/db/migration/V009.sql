CREATE TABLE bluechip_companies (
  company_id TEXT PRIMARY KEY REFERENCES companies(id), industry TEXT NOT NULL,
  system_account_uuid TEXT NOT NULL, lower_price_minor INTEGER NOT NULL CHECK (lower_price_minor > 0),
  upper_price_minor INTEGER NOT NULL CHECK (upper_price_minor > lower_price_minor),
  model_price_minor INTEGER NOT NULL CHECK (model_price_minor > lower_price_minor AND model_price_minor < upper_price_minor),
  spread_bps INTEGER NOT NULL CHECK (spread_bps BETWEEN 0 AND 10000), event_sensitivity_bps INTEGER NOT NULL CHECK (event_sensitivity_bps BETWEEN 0 AND 10000),
  payout_bps INTEGER NOT NULL CHECK (payout_bps BETWEEN 0 AND 10000), quotes_paused INTEGER NOT NULL DEFAULT 0 CHECK (quotes_paused IN (0, 1)),
  next_event_at TEXT NOT NULL, next_dividend_at TEXT NOT NULL
);

CREATE TABLE bluechip_events (
  id TEXT PRIMARY KEY, scope TEXT NOT NULL, company_id TEXT NULL REFERENCES companies(id),
  industry TEXT NULL, headline TEXT NOT NULL, body TEXT NOT NULL,
  price_impact_bps INTEGER NOT NULL, profit_impact_bps INTEGER NOT NULL,
  starts_at TEXT NOT NULL, ends_at TEXT NOT NULL, state TEXT NOT NULL,
  CHECK (ends_at > starts_at)
);

CREATE TABLE market_candles (
  company_id TEXT NOT NULL REFERENCES companies(id), trading_day TEXT NOT NULL, open_minor INTEGER NOT NULL,
  high_minor INTEGER NOT NULL, low_minor INTEGER NOT NULL, close_minor INTEGER NOT NULL,
  volume_shares INTEGER NOT NULL CHECK (volume_shares >= 0), PRIMARY KEY(company_id, trading_day),
  CHECK (low_minor <= open_minor AND low_minor <= close_minor AND high_minor >= open_minor AND high_minor >= close_minor)
);

CREATE TABLE company_industry (
  company_id TEXT PRIMARY KEY REFERENCES companies(id), industry TEXT NOT NULL
);

CREATE TABLE bluechip_fund_audit (
  id TEXT PRIMARY KEY, company_id TEXT NOT NULL REFERENCES companies(id), operation TEXT NOT NULL,
  cash_delta_minor INTEGER NOT NULL, shares_delta INTEGER NOT NULL, occurred_at TEXT NOT NULL
);

CREATE TRIGGER bluechip_fund_audit_no_update
BEFORE UPDATE ON bluechip_fund_audit
BEGIN
  SELECT RAISE(ABORT, 'bluechip_fund_audit is append-only');
END;

CREATE TRIGGER bluechip_fund_audit_no_delete
BEFORE DELETE ON bluechip_fund_audit
BEGIN
  SELECT RAISE(ABORT, 'bluechip_fund_audit is append-only');
END;

CREATE TABLE dividend_runs (
  company_id TEXT NOT NULL REFERENCES companies(id), dividend_at TEXT NOT NULL, state TEXT NOT NULL,
  total_payout_minor INTEGER NOT NULL DEFAULT 0 CHECK (total_payout_minor >= 0), created_at TEXT NOT NULL,
  completed_at TEXT NULL, PRIMARY KEY(company_id, dividend_at)
);
