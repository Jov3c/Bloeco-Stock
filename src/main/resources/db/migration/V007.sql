CREATE TABLE securities_cash_accounts (
  player_uuid TEXT PRIMARY KEY,
  available_minor INTEGER NOT NULL CHECK (available_minor >= 0),
  reserved_minor INTEGER NOT NULL CHECK (reserved_minor >= 0)
);

CREATE TABLE compensation_fund (
  singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
  balance_minor INTEGER NOT NULL CHECK (balance_minor >= 0)
);

INSERT INTO compensation_fund (singleton, balance_minor) VALUES (1, 0);

CREATE TABLE securities_cash_operations (
  id TEXT PRIMARY KEY,
  player_uuid TEXT NOT NULL,
  amount_minor INTEGER NOT NULL CHECK (amount_minor > 0),
  direction TEXT NOT NULL CHECK (direction IN ('DEPOSIT','WITHDRAW')),
  state TEXT NOT NULL CHECK (state IN ('PREPARED','PLAYER_WITHDRAWN','ESCROW_DEPOSITED','ESCROW_WITHDRAWN','PLAYER_DEPOSITED','COMPLETED','FAILED','AMBIGUOUS')),
  last_confirmed_external_stage TEXT CHECK (last_confirmed_external_stage IS NULL OR last_confirmed_external_stage IN ('PLAYER_WITHDRAWN','ESCROW_DEPOSITED','ESCROW_WITHDRAWN','PLAYER_DEPOSITED')),
  detail TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE UNIQUE INDEX securities_cash_operations_player_active
  ON securities_cash_operations(player_uuid)
  WHERE state NOT IN ('COMPLETED', 'FAILED');

CREATE TABLE stock_order_sequence (
  singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
  last_value INTEGER NOT NULL CHECK (last_value >= 0)
);

INSERT INTO stock_order_sequence (singleton, last_value) VALUES (1, 0);

CREATE TABLE stock_orders (
  id TEXT PRIMARY KEY,
  company_id TEXT NOT NULL REFERENCES companies(id),
  stock_code TEXT NOT NULL REFERENCES stock_listings(stock_code),
  player_uuid TEXT NOT NULL,
  side TEXT NOT NULL CHECK (side IN ('BUY','SELL')),
  limit_price_minor INTEGER NOT NULL CHECK (limit_price_minor > 0),
  original_shares INTEGER NOT NULL CHECK (original_shares > 0),
  remaining_shares INTEGER NOT NULL CHECK (remaining_shares >= 0 AND remaining_shares <= original_shares),
  priority_sequence INTEGER NOT NULL UNIQUE CHECK (priority_sequence > 0),
  reserved_cash_minor INTEGER NOT NULL CHECK (reserved_cash_minor >= 0),
  filled_notional_minor INTEGER NOT NULL CHECK (filled_notional_minor >= 0),
  fee_charged_minor INTEGER NOT NULL CHECK (fee_charged_minor >= 0),
  fee_bps INTEGER NOT NULL CHECK (fee_bps BETWEEN 0 AND 10000),
  accepted_at TEXT NOT NULL,
  state TEXT NOT NULL CHECK (state IN ('OPEN','PARTIALLY_FILLED','FILLED','CANCELLED','SELF_TRADE_PREVENTED')),
  CHECK ((state IN ('OPEN','PARTIALLY_FILLED') AND remaining_shares > 0)
      OR (state IN ('FILLED','CANCELLED','SELF_TRADE_PREVENTED') AND remaining_shares >= 0)),
  CHECK ((side = 'BUY') OR (reserved_cash_minor = 0 AND fee_bps = 0))
);

CREATE INDEX stock_orders_book
  ON stock_orders(company_id, state, side, limit_price_minor, priority_sequence);
CREATE INDEX stock_orders_player ON stock_orders(player_uuid, accepted_at DESC, id);

CREATE TABLE stock_trades (
  id TEXT PRIMARY KEY,
  company_id TEXT NOT NULL REFERENCES companies(id),
  stock_code TEXT NOT NULL REFERENCES stock_listings(stock_code),
  buy_order_id TEXT NOT NULL REFERENCES stock_orders(id),
  sell_order_id TEXT NOT NULL REFERENCES stock_orders(id),
  shares INTEGER NOT NULL CHECK (shares > 0),
  price_minor INTEGER NOT NULL CHECK (price_minor > 0),
  notional_minor INTEGER NOT NULL CHECK (notional_minor > 0),
  buyer_fee_minor INTEGER NOT NULL CHECK (buyer_fee_minor >= 0),
  occurred_at TEXT NOT NULL,
  CHECK (buy_order_id <> sell_order_id)
);

CREATE INDEX stock_trades_time ON stock_trades(company_id, occurred_at DESC, id);
CREATE INDEX stock_trades_buy_order ON stock_trades(buy_order_id, occurred_at DESC);
CREATE INDEX stock_trades_sell_order ON stock_trades(sell_order_id, occurred_at DESC);

CREATE TRIGGER stock_trades_immutable
BEFORE UPDATE ON stock_trades
BEGIN
  SELECT RAISE(ABORT, 'stock trades are immutable');
END;

CREATE TABLE escrow_ledger_entries (
  id TEXT PRIMARY KEY,
  liability_kind TEXT NOT NULL CHECK (liability_kind IN ('COMPANY_TREASURY','SECURITIES_CASH','COMPENSATION_FUND')),
  company_id TEXT REFERENCES companies(id),
  player_uuid TEXT,
  amount_minor INTEGER NOT NULL CHECK (amount_minor <> 0),
  operation_id TEXT REFERENCES securities_cash_operations(id),
  trade_id TEXT REFERENCES stock_trades(id),
  occurred_at TEXT NOT NULL,
  CHECK ((liability_kind = 'COMPANY_TREASURY' AND company_id IS NOT NULL AND player_uuid IS NULL)
      OR (liability_kind = 'SECURITIES_CASH' AND company_id IS NULL AND player_uuid IS NOT NULL)
      OR (liability_kind = 'COMPENSATION_FUND' AND company_id IS NULL AND player_uuid IS NULL)),
  CHECK (NOT (operation_id IS NOT NULL AND trade_id IS NOT NULL))
);

CREATE INDEX escrow_ledger_entries_company ON escrow_ledger_entries(company_id, occurred_at, id);
CREATE INDEX escrow_ledger_entries_player ON escrow_ledger_entries(player_uuid, occurred_at, id);

CREATE TRIGGER escrow_ledger_entries_no_update
BEFORE UPDATE ON escrow_ledger_entries
BEGIN
  SELECT RAISE(ABORT, 'escrow ledger is append-only');
END;

CREATE TRIGGER escrow_ledger_entries_no_delete
BEFORE DELETE ON escrow_ledger_entries
BEGIN
  SELECT RAISE(ABORT, 'escrow ledger is append-only');
END;

INSERT INTO escrow_ledger_entries (id, liability_kind, company_id, player_uuid, amount_minor, operation_id, trade_id, occurred_at)
SELECT 'company-opening:' || company_id, 'COMPANY_TREASURY', company_id, NULL, cash_minor, NULL, NULL, '2026-08-22T00:00:00Z'
FROM company_cash_accounts
WHERE cash_minor <> 0;
