CREATE TABLE stock_code_sequence (
  singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
  last_value INTEGER NOT NULL CHECK (last_value BETWEEN 0 AND 999999)
);

INSERT INTO stock_code_sequence (singleton, last_value) VALUES (1, 0);

CREATE TABLE stock_listings (
  company_id TEXT PRIMARY KEY REFERENCES companies(id),
  stock_code TEXT NOT NULL UNIQUE CHECK (
    stock_code GLOB 'BS[0-9][0-9][0-9][0-9][0-9][0-9]'
    AND CAST(SUBSTR(stock_code, 3) AS INTEGER) BETWEEN 1 AND 999999
  ),
  issue_reference_price_minor INTEGER NOT NULL CHECK (issue_reference_price_minor > 0),
  issued_shares INTEGER NOT NULL CHECK (issued_shares > 0),
  listed_at TEXT NOT NULL
);

CREATE INDEX stock_listings_listed_at ON stock_listings(listed_at, company_id);

WITH successful_offerings AS (
  SELECT po.company_id, po.id AS offering_id, po.issue_price_minor, po.closes_at, po.announced_at,
         c.created_at, c.total_shares AS issued_shares,
         ROW_NUMBER() OVER (PARTITION BY po.company_id ORDER BY po.closes_at DESC, po.announced_at DESC, po.id DESC) AS source_rank
  FROM primary_offerings po
  JOIN companies c ON c.id = po.company_id AND c.status = 'LISTED'
  JOIN primary_subscriptions ps ON ps.offering_id = po.id
  JOIN treasury_operations t ON t.id = ps.id
  WHERE po.state = 'CLOSED'
  GROUP BY po.id
  HAVING SUM(CASE WHEN t.state = 'COMPLETED' THEN ps.shares ELSE 0 END) > 0
), selected_sources AS (
  SELECT company_id, offering_id, issue_price_minor, closes_at, issued_shares,
         ROW_NUMBER() OVER (ORDER BY created_at ASC, company_id ASC) AS code_number
  FROM successful_offerings
  WHERE source_rank = 1
)
INSERT INTO stock_listings (company_id, stock_code, issue_reference_price_minor, issued_shares, listed_at)
SELECT company_id, printf('BS%06d', code_number), issue_price_minor, issued_shares, closes_at
FROM selected_sources;

UPDATE stock_code_sequence
SET last_value = (SELECT COUNT(*) FROM stock_listings)
WHERE singleton = 1;

WITH successful_offerings AS (
  SELECT po.company_id, po.id AS offering_id, po.issue_price_minor, po.closes_at, po.announced_at,
         c.created_at, c.total_shares AS issued_shares,
         ROW_NUMBER() OVER (PARTITION BY po.company_id ORDER BY po.closes_at DESC, po.announced_at DESC, po.id DESC) AS source_rank
  FROM primary_offerings po
  JOIN companies c ON c.id = po.company_id AND c.status = 'LISTED'
  JOIN primary_subscriptions ps ON ps.offering_id = po.id
  JOIN treasury_operations t ON t.id = ps.id
  WHERE po.state = 'CLOSED'
  GROUP BY po.id
  HAVING SUM(CASE WHEN t.state = 'COMPLETED' THEN ps.shares ELSE 0 END) > 0
), selected_sources AS (
  SELECT company_id, offering_id, issue_price_minor, closes_at, issued_shares,
         ROW_NUMBER() OVER (ORDER BY created_at ASC, company_id ASC) AS code_number
  FROM successful_offerings
  WHERE source_rank = 1
)
INSERT INTO audit_events (event_id, company_id, actor_uuid, event_type, payload_json, occurred_at)
SELECT company_id || ':IPO_LISTING_BACKFILLED:' || offering_id,
       company_id,
       NULL,
       'IPO_LISTING_BACKFILLED',
       json_object('companyId', company_id, 'offeringId', offering_id,
                   'stockCode', printf('BS%06d', code_number),
                   'issueReferencePriceMinor', issue_price_minor,
                   'issuedShares', issued_shares, 'source', 'BACKFILL'),
       closes_at
FROM selected_sources;
