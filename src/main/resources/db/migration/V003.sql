CREATE TABLE company_cash_accounts (
  company_id TEXT PRIMARY KEY REFERENCES companies(id),
  cash_minor INTEGER NOT NULL CHECK (cash_minor >= 0),
  paid_in_capital_minor INTEGER NOT NULL CHECK (paid_in_capital_minor >= 0),
  retained_earnings_minor INTEGER NOT NULL CHECK (retained_earnings_minor >= 0),
  reserved_minor INTEGER NOT NULL CHECK (reserved_minor >= 0 AND reserved_minor <= cash_minor)
);

CREATE TABLE share_holdings (
  company_id TEXT NOT NULL REFERENCES companies(id),
  holder_uuid TEXT NOT NULL,
  available_shares INTEGER NOT NULL CHECK (available_shares >= 0),
  reserved_shares INTEGER NOT NULL CHECK (reserved_shares >= 0),
  PRIMARY KEY (company_id, holder_uuid)
);

CREATE TABLE asset_bindings (
  id TEXT PRIMARY KEY,
  company_id TEXT NOT NULL REFERENCES companies(id),
  adapter_id TEXT NOT NULL,
  external_key TEXT NOT NULL,
  verified_owner_uuid TEXT NOT NULL,
  state TEXT NOT NULL CHECK (state IN ('PENDING','ACTIVE','REVOKED')),
  created_at TEXT NOT NULL,
  UNIQUE (adapter_id, external_key)
);

CREATE TABLE treasury_operations (
  id TEXT PRIMARY KEY,
  company_id TEXT NOT NULL REFERENCES companies(id),
  player_uuid TEXT NOT NULL,
  amount_minor INTEGER NOT NULL CHECK (amount_minor >= 0),
  provider_correlation_key TEXT NOT NULL UNIQUE,
  state TEXT NOT NULL CHECK (state IN ('PREPARED','PLAYER_WITHDRAWN','ESCROW_DEPOSITED','COMPLETED','REFUND_REQUIRED','REFUNDED','AMBIGUOUS')),
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE company_announcements (
  id TEXT PRIMARY KEY,
  company_id TEXT NOT NULL REFERENCES companies(id),
  offering_id TEXT REFERENCES primary_offerings(id),
  body TEXT NOT NULL,
  created_at TEXT NOT NULL
);

CREATE TABLE primary_offerings (
  id TEXT PRIMARY KEY,
  company_id TEXT NOT NULL REFERENCES companies(id),
  target_minor INTEGER NOT NULL CHECK (target_minor > 0),
  issue_price_minor INTEGER NOT NULL CHECK (issue_price_minor > 0),
  maximum_shares INTEGER NOT NULL CHECK (maximum_shares > 0 AND maximum_shares = target_minor / issue_price_minor),
  announced_at TEXT NOT NULL,
  opens_at TEXT NOT NULL,
  closes_at TEXT NOT NULL,
  state TEXT NOT NULL CHECK (state IN ('ANNOUNCED','OPEN','CLOSED'))
);

CREATE TABLE primary_subscriptions (
  id TEXT PRIMARY KEY,
  offering_id TEXT NOT NULL REFERENCES primary_offerings(id),
  company_id TEXT NOT NULL REFERENCES companies(id),
  subscriber_uuid TEXT NOT NULL,
  shares INTEGER NOT NULL CHECK (shares > 0),
  amount_minor INTEGER NOT NULL CHECK (amount_minor >= 0),
  provider_correlation_key TEXT NOT NULL UNIQUE,
  created_at TEXT NOT NULL
);
