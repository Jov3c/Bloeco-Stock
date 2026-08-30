CREATE TABLE company_buyback_acceptances (
  governance_action_id TEXT NOT NULL REFERENCES company_governance_actions(id),
  shareholder_uuid TEXT NOT NULL,
  shares INTEGER NOT NULL CHECK (shares > 0),
  amount_minor INTEGER NOT NULL CHECK (amount_minor > 0),
  correlation_key TEXT NOT NULL,
  accepted_at TEXT NOT NULL,
  PRIMARY KEY (governance_action_id, shareholder_uuid, correlation_key),
  UNIQUE (governance_action_id, correlation_key)
);

CREATE TRIGGER company_buyback_acceptances_immutable_update
BEFORE UPDATE ON company_buyback_acceptances
BEGIN
  SELECT RAISE(ABORT, 'buyback acceptance is immutable');
END;

CREATE TRIGGER company_buyback_acceptances_immutable_delete
BEFORE DELETE ON company_buyback_acceptances
BEGIN
  SELECT RAISE(ABORT, 'buyback acceptance is immutable');
END;
