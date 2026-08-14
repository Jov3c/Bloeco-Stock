-- Existing WITHDRAWN rows use the legacy compensation flow.  Escrow-backed
-- registration withdrawals must instead become ambiguous after a crash.
ALTER TABLE registration_sagas ADD COLUMN requires_escrow INTEGER NOT NULL DEFAULT 0 CHECK (requires_escrow IN (0, 1));
