CREATE TABLE companies (
  id TEXT PRIMARY KEY,
  normalized_name TEXT NOT NULL UNIQUE,
  display_name TEXT NOT NULL,
  founder_uuid TEXT NOT NULL,
  status TEXT NOT NULL,
  treasury_minor INTEGER NOT NULL CHECK (treasury_minor >= 0),
  total_shares INTEGER NOT NULL CHECK (total_shares >= 1000),
  dividend_basis_points INTEGER NOT NULL CHECK (dividend_basis_points IN (3000, 5000, 7000)),
  created_at TEXT NOT NULL,
  version INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE registration_sagas (
  id TEXT PRIMARY KEY,
  founder_uuid TEXT NOT NULL,
  company_normalized_name TEXT NOT NULL,
  total_withdrawal_minor INTEGER NOT NULL,
  state TEXT NOT NULL CHECK (state IN ('PREPARED','WITHDRAWN','COMPLETED','REFUND_REQUIRED','REFUNDED','AMBIGUOUS')),
  error_message TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);


CREATE TABLE audit_events (
  sequence INTEGER PRIMARY KEY AUTOINCREMENT,
  event_id TEXT NOT NULL UNIQUE,
  company_id TEXT,
  actor_uuid TEXT,
  event_type TEXT NOT NULL,
  payload_json TEXT NOT NULL,
  occurred_at TEXT NOT NULL
);
