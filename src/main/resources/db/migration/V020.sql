-- Exit snapshots and claim economics are facts. Only the claim processing state
-- may change, and it may never be moved backwards by a retry.
CREATE TRIGGER company_exit_snapshot_action_company_match
BEFORE INSERT ON company_exit_snapshots
BEGIN
  SELECT RAISE(ABORT, 'exit snapshot company/action mismatch')
  WHERE NOT EXISTS (
    SELECT 1 FROM company_governance_actions
    WHERE id = NEW.governance_action_id
      AND company_id = NEW.company_id
      AND action_type IN ('VOLUNTARY_DELIST', 'FORCED_DELIST')
  );
END;

CREATE TRIGGER company_liquidation_claim_action_match
BEFORE INSERT ON company_liquidation_claims
BEGIN
  SELECT RAISE(ABORT, 'liquidation claim action mismatch')
  WHERE NOT EXISTS (
    SELECT 1 FROM company_exit_snapshots s
    WHERE s.governance_action_id = NEW.governance_action_id
      AND s.holder_uuid = NEW.holder_uuid
  );
END;

CREATE TRIGGER company_liquidation_claims_immutable_facts
BEFORE UPDATE OF governance_action_id, holder_uuid, shares, entitlement_minor,
    company_contribution_minor, fund_contribution_minor, created_at ON company_liquidation_claims
BEGIN
  SELECT RAISE(ABORT, 'liquidation claim facts are immutable');
END;

CREATE TRIGGER company_liquidation_claims_no_delete
BEFORE DELETE ON company_liquidation_claims
BEGIN
  SELECT RAISE(ABORT, 'liquidation claims are immutable');
END;
