-- A durable marker makes the finite, internal participant allocation restart-safe.
CREATE TABLE bluechip_system_participant_allocations (
  participant_uuid TEXT PRIMARY KEY,
  maker_uuid TEXT NOT NULL,
  cash_minor INTEGER NOT NULL CHECK (cash_minor > 0),
  shares_per_company INTEGER NOT NULL CHECK (shares_per_company > 0)
);
