-- The legacy escrow ledger can reference only securities_cash_operations.  Keep its
-- immutable rows unchanged and link company payout rows through a dedicated table.
CREATE TABLE company_payout_ledger_links (
  payout_operation_id TEXT NOT NULL REFERENCES company_payout_operations(id),
  ledger_entry_id TEXT NOT NULL REFERENCES escrow_ledger_entries(id),
  PRIMARY KEY (payout_operation_id, ledger_entry_id),
  UNIQUE (ledger_entry_id)
);

CREATE TRIGGER company_payout_ledger_links_no_update
BEFORE UPDATE ON company_payout_ledger_links
BEGIN
  SELECT RAISE(ABORT, 'company payout ledger links are immutable');
END;

CREATE TRIGGER company_payout_ledger_links_no_delete
BEFORE DELETE ON company_payout_ledger_links
BEGIN
  SELECT RAISE(ABORT, 'company payout ledger links are immutable');
END;
