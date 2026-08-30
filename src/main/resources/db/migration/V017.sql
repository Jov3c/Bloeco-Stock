CREATE TABLE company_governance_actions (
  id TEXT PRIMARY KEY,
  company_id TEXT NOT NULL REFERENCES companies(id),
  actor_uuid TEXT NOT NULL,
  action_type TEXT NOT NULL CHECK (action_type IN ('BUYBACK','FOUNDER_CASH_OUT','VOLUNTARY_DELIST','FORCED_DELIST')),
  amount_minor INTEGER NOT NULL CHECK (amount_minor >= 0),
  price_per_share_minor INTEGER NOT NULL CHECK (price_per_share_minor >= 0),
  announced_at TEXT NOT NULL,
  executable_at TEXT NOT NULL,
  state TEXT NOT NULL CHECK (state IN ('ANNOUNCED','EXECUTION_READY','EXECUTING','EXECUTED','CANCELLED')),
  correlation_key TEXT NOT NULL UNIQUE,
  payload_json TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  CHECK (executable_at >= announced_at)
);

CREATE INDEX company_governance_actions_company_state ON company_governance_actions(company_id, state, executable_at, id);

CREATE TRIGGER company_governance_actions_immutable_payload
BEFORE UPDATE OF company_id, actor_uuid, action_type, amount_minor, price_per_share_minor, announced_at, executable_at, correlation_key, payload_json ON company_governance_actions
BEGIN
  SELECT RAISE(ABORT, 'governance action announcement is immutable');
END;

CREATE TABLE company_payout_operations (
  id TEXT PRIMARY KEY,
  company_id TEXT NOT NULL REFERENCES companies(id),
  governance_action_id TEXT NOT NULL REFERENCES company_governance_actions(id),
  recipient_uuid TEXT NOT NULL,
  amount_minor INTEGER NOT NULL CHECK (amount_minor > 0),
  correlation_key TEXT NOT NULL UNIQUE,
  state TEXT NOT NULL CHECK (state IN ('PREPARED','EXTERNAL_DEBIT_CONFIRMED','COMPLETED','FAILED','AMBIGUOUS','CANCELLED')),
  detail TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE INDEX company_payout_operations_recovery ON company_payout_operations(state, updated_at, id);

CREATE TABLE company_exit_snapshots (
  governance_action_id TEXT NOT NULL REFERENCES company_governance_actions(id),
  company_id TEXT NOT NULL REFERENCES companies(id),
  holder_uuid TEXT NOT NULL,
  available_shares INTEGER NOT NULL CHECK (available_shares >= 0),
  reserved_shares INTEGER NOT NULL CHECK (reserved_shares >= 0),
  snapshotted_at TEXT NOT NULL,
  PRIMARY KEY (governance_action_id, holder_uuid)
);

CREATE TRIGGER company_exit_snapshots_immutable_update
BEFORE UPDATE ON company_exit_snapshots
BEGIN
  SELECT RAISE(ABORT, 'company exit snapshots are immutable');
END;

CREATE TRIGGER company_exit_snapshots_immutable_delete
BEFORE DELETE ON company_exit_snapshots
BEGIN
  SELECT RAISE(ABORT, 'company exit snapshots are immutable');
END;

CREATE TABLE company_liquidation_claims (
  governance_action_id TEXT NOT NULL REFERENCES company_governance_actions(id),
  holder_uuid TEXT NOT NULL,
  shares INTEGER NOT NULL CHECK (shares >= 0),
  entitlement_minor INTEGER NOT NULL CHECK (entitlement_minor >= 0),
  company_contribution_minor INTEGER NOT NULL CHECK (company_contribution_minor >= 0),
  fund_contribution_minor INTEGER NOT NULL CHECK (fund_contribution_minor >= 0),
  state TEXT NOT NULL CHECK (state IN ('PENDING','CREDITED','FAILED')),
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  PRIMARY KEY (governance_action_id, holder_uuid),
  CHECK (company_contribution_minor + fund_contribution_minor = entitlement_minor)
);

CREATE TABLE company_order_release_progress (
  governance_action_id TEXT PRIMARY KEY REFERENCES company_governance_actions(id),
  last_released_order_id TEXT REFERENCES stock_orders(id),
  released_orders INTEGER NOT NULL CHECK (released_orders >= 0),
  complete INTEGER NOT NULL CHECK (complete IN (0, 1)),
  updated_at TEXT NOT NULL
);
