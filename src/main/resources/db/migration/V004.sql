-- Registration capital can be held in escrow before the company exists.  V003's
-- treasury operation foreign key cannot represent that pre-company window.
ALTER TABLE registration_sagas RENAME TO registration_sagas_v003;

CREATE TABLE registration_sagas (
  id TEXT PRIMARY KEY,
  founder_uuid TEXT NOT NULL,
  company_normalized_name TEXT NOT NULL,
  total_withdrawal_minor INTEGER NOT NULL,
  state TEXT NOT NULL CHECK (state IN ('PREPARED','WITHDRAWN','ESCROW_DEPOSITED','COMPLETED','REFUND_REQUIRED','REFUNDED','AMBIGUOUS','REJECTED')),
  error_message TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

INSERT INTO registration_sagas SELECT * FROM registration_sagas_v003;
DROP TABLE registration_sagas_v003;

CREATE UNIQUE INDEX active_registration_saga_name
ON registration_sagas(company_normalized_name)
WHERE state IN ('PREPARED','WITHDRAWN','ESCROW_DEPOSITED','REFUND_REQUIRED','AMBIGUOUS');
