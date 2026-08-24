CREATE TABLE market_event_schedule (
  singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
  next_company_event_at TEXT NOT NULL,
  next_market_event_at TEXT NOT NULL
);

INSERT INTO market_event_schedule (singleton, next_company_event_at, next_market_event_at)
VALUES (1, '1970-01-01T00:00:00Z', '1970-01-01T00:00:00Z');

CREATE TABLE company_market_expectations (
  company_id TEXT PRIMARY KEY REFERENCES companies(id),
  profit_impact_bps INTEGER NOT NULL DEFAULT 0
);
