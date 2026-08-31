CREATE TABLE bluechip_quant_risk (
  stock_code TEXT PRIMARY KEY,
  risk_level INTEGER NOT NULL CHECK (risk_level BETWEEN 0 AND 3),
  consecutive_losses INTEGER NOT NULL CHECK (consecutive_losses >= 0),
  cooldown_until TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE bluechip_quant_decisions (
  id TEXT PRIMARY KEY,
  stock_code TEXT NOT NULL,
  signal TEXT NOT NULL,
  confidence_bps INTEGER NOT NULL CHECK (confidence_bps BETWEEN 0 AND 10000),
  action TEXT NOT NULL,
  requested_shares INTEGER NOT NULL CHECK (requested_shares >= 0),
  filled_shares INTEGER NOT NULL CHECK (filled_shares BETWEEN 0 AND requested_shares),
  realized_pnl_minor INTEGER NOT NULL,
  risk_level INTEGER NOT NULL CHECK (risk_level BETWEEN 0 AND 3),
  decided_at TEXT NOT NULL
);

CREATE INDEX bluechip_quant_decisions_stock_time_idx
  ON bluechip_quant_decisions(stock_code, decided_at DESC, id DESC);
