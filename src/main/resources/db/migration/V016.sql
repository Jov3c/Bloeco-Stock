CREATE TABLE issuance_proposals (
  id TEXT PRIMARY KEY,
  company_id TEXT NOT NULL REFERENCES companies(id),
  proposer_uuid TEXT NOT NULL,
  new_shares INTEGER NOT NULL CHECK (new_shares > 0),
  issue_price_minor INTEGER NOT NULL CHECK (issue_price_minor > 0),
  announced_at TEXT NOT NULL,
  state TEXT NOT NULL CHECK (state IN ('ANNOUNCED','VOTING','REJECTED','APPROVED','SUBSCRIBING','CLOSED','CANCELLED'))
);

CREATE TABLE issuance_record_snapshots (
  proposal_id TEXT NOT NULL REFERENCES issuance_proposals(id),
  voter_uuid TEXT NOT NULL,
  shares INTEGER NOT NULL CHECK (shares > 0),
  PRIMARY KEY (proposal_id, voter_uuid)
);

CREATE TABLE issuance_votes (
  proposal_id TEXT NOT NULL REFERENCES issuance_proposals(id),
  voter_uuid TEXT NOT NULL,
  choice TEXT NOT NULL CHECK (choice IN ('YES','NO','ABSTAIN')),
  snapshot_shares INTEGER NOT NULL CHECK (snapshot_shares > 0),
  voted_at TEXT NOT NULL,
  PRIMARY KEY (proposal_id, voter_uuid),
  FOREIGN KEY (proposal_id, voter_uuid) REFERENCES issuance_record_snapshots(proposal_id, voter_uuid)
);

CREATE TABLE issuance_subscriptions (
  id TEXT PRIMARY KEY,
  proposal_id TEXT NOT NULL REFERENCES issuance_proposals(id),
  subscriber_uuid TEXT NOT NULL,
  shares INTEGER NOT NULL CHECK (shares > 0),
  reserved_cash_minor INTEGER NOT NULL CHECK (reserved_cash_minor >= 0),
  created_at TEXT NOT NULL,
  UNIQUE (proposal_id, subscriber_uuid, id)
);

CREATE TABLE issuance_subscription_settlements (
  subscription_id TEXT PRIMARY KEY REFERENCES issuance_subscriptions(id),
  settled_shares INTEGER NOT NULL CHECK (settled_shares >= 0),
  settled_at TEXT NOT NULL
);
