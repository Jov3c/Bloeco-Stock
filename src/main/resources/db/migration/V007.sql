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
  updated_at TEXT NOT NULL,
  CHECK ((direction = 'DEPOSIT' AND (
      (state IN ('PREPARED','FAILED') AND last_confirmed_external_stage IS NULL)
      OR (state = 'PLAYER_WITHDRAWN' AND last_confirmed_external_stage = 'PLAYER_WITHDRAWN')
      OR (state IN ('ESCROW_DEPOSITED','COMPLETED') AND last_confirmed_external_stage = 'ESCROW_DEPOSITED')
      OR (state = 'AMBIGUOUS' AND (last_confirmed_external_stage IS NULL
          OR last_confirmed_external_stage IN ('PLAYER_WITHDRAWN','ESCROW_DEPOSITED')))
  )) OR (direction = 'WITHDRAW' AND (
      (state IN ('PREPARED','FAILED') AND last_confirmed_external_stage IS NULL)
      OR (state = 'ESCROW_WITHDRAWN' AND last_confirmed_external_stage = 'ESCROW_WITHDRAWN')
      OR (state IN ('PLAYER_DEPOSITED','COMPLETED') AND last_confirmed_external_stage = 'PLAYER_DEPOSITED')
      OR (state = 'AMBIGUOUS' AND (last_confirmed_external_stage IS NULL
          OR last_confirmed_external_stage IN ('ESCROW_WITHDRAWN','PLAYER_DEPOSITED')))
  )))
);

CREATE UNIQUE INDEX securities_cash_operations_player_active
  ON securities_cash_operations(player_uuid)
  WHERE state NOT IN ('COMPLETED', 'FAILED');

CREATE TABLE stock_order_sequence (
  singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
  last_value INTEGER NOT NULL CHECK (last_value >= 0)
);

INSERT INTO stock_order_sequence (singleton, last_value) VALUES (1, 0);

CREATE UNIQUE INDEX stock_listings_company_stock_code ON stock_listings(company_id, stock_code);

CREATE TABLE stock_orders (
  id TEXT PRIMARY KEY,
  company_id TEXT NOT NULL,
  stock_code TEXT NOT NULL,
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
  FOREIGN KEY (company_id, stock_code) REFERENCES stock_listings(company_id, stock_code),
  CHECK ((state = 'OPEN' AND remaining_shares = original_shares)
      OR (state = 'PARTIALLY_FILLED' AND remaining_shares BETWEEN 1 AND original_shares - 1)
      OR (state = 'FILLED' AND remaining_shares = 0)
      OR (state IN ('CANCELLED','SELF_TRADE_PREVENTED') AND remaining_shares > 0)),
  CHECK ((side = 'BUY' AND
          (((state IN ('OPEN','PARTIALLY_FILLED')) AND reserved_cash_minor > 0)
            OR (state IN ('FILLED','CANCELLED','SELF_TRADE_PREVENTED') AND reserved_cash_minor = 0))
          AND ((remaining_shares = original_shares AND filled_notional_minor = 0)
            OR (remaining_shares < original_shares AND filled_notional_minor > 0)))
      OR (side = 'SELL' AND reserved_cash_minor = 0 AND filled_notional_minor = 0
          AND fee_charged_minor = 0 AND fee_bps = 0))
);

CREATE INDEX stock_orders_book
  ON stock_orders(company_id, state, side, limit_price_minor, priority_sequence);
CREATE INDEX stock_orders_player ON stock_orders(player_uuid, accepted_at DESC, id);

CREATE TRIGGER stock_orders_validate_insert
BEFORE INSERT ON stock_orders
WHEN NEW.side = 'BUY'
BEGIN
  SELECT RAISE(ABORT, 'buy order notional overflow')
  WHERE NEW.limit_price_minor > 0
    AND NEW.remaining_shares > 9223372036854775807 / NEW.limit_price_minor;
  SELECT RAISE(ABORT, 'buy order worst case notional overflow')
  WHERE NEW.limit_price_minor > 0
    AND NEW.remaining_shares <= 9223372036854775807 / NEW.limit_price_minor
    AND NEW.filled_notional_minor > 9223372036854775807 - NEW.remaining_shares * NEW.limit_price_minor;
  SELECT RAISE(ABORT, 'buy order fee does not match filled notional')
  WHERE NEW.fee_charged_minor != ((NEW.filled_notional_minor / 10000) * NEW.fee_bps
    + (((NEW.filled_notional_minor % 10000) * NEW.fee_bps + 9999) / 10000));
  SELECT RAISE(ABORT, 'buy order reserve overflow')
  WHERE NEW.limit_price_minor > 0
    AND NEW.remaining_shares <= 9223372036854775807 / NEW.limit_price_minor
    AND NEW.filled_notional_minor <= 9223372036854775807 - NEW.remaining_shares * NEW.limit_price_minor
    AND NEW.remaining_shares * NEW.limit_price_minor > 9223372036854775807 - (
      (((NEW.filled_notional_minor + NEW.remaining_shares * NEW.limit_price_minor) / 10000) * NEW.fee_bps
        + ((((NEW.filled_notional_minor + NEW.remaining_shares * NEW.limit_price_minor) % 10000) * NEW.fee_bps + 9999) / 10000))
      - NEW.fee_charged_minor);
  SELECT RAISE(ABORT, 'buy order reserve does not match worst case')
  WHERE NEW.state IN ('OPEN', 'PARTIALLY_FILLED')
    AND NEW.reserved_cash_minor != NEW.remaining_shares * NEW.limit_price_minor + (
      (((NEW.filled_notional_minor + NEW.remaining_shares * NEW.limit_price_minor) / 10000) * NEW.fee_bps
        + ((((NEW.filled_notional_minor + NEW.remaining_shares * NEW.limit_price_minor) % 10000) * NEW.fee_bps + 9999) / 10000))
      - NEW.fee_charged_minor);
END;

CREATE TRIGGER stock_orders_validate_update
BEFORE UPDATE ON stock_orders
WHEN NEW.side = 'BUY'
BEGIN
  SELECT RAISE(ABORT, 'buy order notional overflow')
  WHERE NEW.limit_price_minor > 0
    AND NEW.remaining_shares > 9223372036854775807 / NEW.limit_price_minor;
  SELECT RAISE(ABORT, 'buy order worst case notional overflow')
  WHERE NEW.limit_price_minor > 0
    AND NEW.remaining_shares <= 9223372036854775807 / NEW.limit_price_minor
    AND NEW.filled_notional_minor > 9223372036854775807 - NEW.remaining_shares * NEW.limit_price_minor;
  SELECT RAISE(ABORT, 'buy order fee does not match filled notional')
  WHERE NEW.fee_charged_minor != ((NEW.filled_notional_minor / 10000) * NEW.fee_bps
    + (((NEW.filled_notional_minor % 10000) * NEW.fee_bps + 9999) / 10000));
  SELECT RAISE(ABORT, 'buy order reserve overflow')
  WHERE NEW.limit_price_minor > 0
    AND NEW.remaining_shares <= 9223372036854775807 / NEW.limit_price_minor
    AND NEW.filled_notional_minor <= 9223372036854775807 - NEW.remaining_shares * NEW.limit_price_minor
    AND NEW.remaining_shares * NEW.limit_price_minor > 9223372036854775807 - (
      (((NEW.filled_notional_minor + NEW.remaining_shares * NEW.limit_price_minor) / 10000) * NEW.fee_bps
        + ((((NEW.filled_notional_minor + NEW.remaining_shares * NEW.limit_price_minor) % 10000) * NEW.fee_bps + 9999) / 10000))
      - NEW.fee_charged_minor);
  SELECT RAISE(ABORT, 'buy order reserve does not match worst case')
  WHERE NEW.state IN ('OPEN', 'PARTIALLY_FILLED')
    AND NEW.reserved_cash_minor != NEW.remaining_shares * NEW.limit_price_minor + (
      (((NEW.filled_notional_minor + NEW.remaining_shares * NEW.limit_price_minor) / 10000) * NEW.fee_bps
        + ((((NEW.filled_notional_minor + NEW.remaining_shares * NEW.limit_price_minor) % 10000) * NEW.fee_bps + 9999) / 10000))
      - NEW.fee_charged_minor);
END;

CREATE TABLE stock_trades (
  id TEXT PRIMARY KEY,
  company_id TEXT NOT NULL,
  stock_code TEXT NOT NULL,
  buy_order_id TEXT NOT NULL REFERENCES stock_orders(id),
  sell_order_id TEXT NOT NULL REFERENCES stock_orders(id),
  shares INTEGER NOT NULL CHECK (shares > 0),
  price_minor INTEGER NOT NULL CHECK (price_minor > 0),
  notional_minor INTEGER NOT NULL CHECK (notional_minor > 0),
  buyer_fee_minor INTEGER NOT NULL CHECK (buyer_fee_minor >= 0),
  occurred_at TEXT NOT NULL,
  FOREIGN KEY (company_id, stock_code) REFERENCES stock_listings(company_id, stock_code),
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

CREATE TRIGGER stock_trades_no_delete
BEFORE DELETE ON stock_trades
BEGIN
  SELECT RAISE(ABORT, 'stock trades are immutable');
END;

CREATE TRIGGER stock_trades_validate_orders
BEFORE INSERT ON stock_trades
BEGIN
  SELECT RAISE(ABORT, 'invalid buy order for trade') WHERE NOT EXISTS (
    SELECT 1 FROM stock_orders
    WHERE id = NEW.buy_order_id AND side = 'BUY'
      AND company_id = NEW.company_id AND stock_code = NEW.stock_code
  );
  SELECT RAISE(ABORT, 'invalid sell order for trade') WHERE NOT EXISTS (
    SELECT 1 FROM stock_orders
    WHERE id = NEW.sell_order_id AND side = 'SELL'
      AND company_id = NEW.company_id AND stock_code = NEW.stock_code
  );
END;

CREATE TRIGGER stock_trades_validate_amounts
BEFORE INSERT ON stock_trades
BEGIN
  SELECT RAISE(ABORT, 'trade shares and price must be positive')
  WHERE NEW.shares <= 0 OR NEW.price_minor <= 0;
  SELECT RAISE(ABORT, 'trade notional overflow')
  WHERE NEW.shares > 9223372036854775807 / NEW.price_minor;
  SELECT RAISE(ABORT, 'trade notional does not match price and shares')
  WHERE NEW.notional_minor != NEW.price_minor * NEW.shares;
  SELECT RAISE(ABORT, 'trade buyer fee exceeds notional')
  WHERE NEW.buyer_fee_minor > NEW.notional_minor;
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
SELECT 'company-opening:' || ca.company_id, 'COMPANY_TREASURY', ca.company_id, NULL, ca.cash_minor, NULL, NULL, c.created_at
FROM company_cash_accounts ca
JOIN companies c ON c.id = ca.company_id
WHERE ca.cash_minor <> 0;
