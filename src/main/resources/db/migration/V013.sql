-- Bluechips use reserved readable tickers (for example NOVA). Player listings
-- remain allocated as BS000001.. and are guarded by application-level sequence checks.
CREATE TABLE stock_listings_rebuilt (
  company_id TEXT PRIMARY KEY REFERENCES companies(id),
  stock_code TEXT NOT NULL UNIQUE CHECK (
    length(stock_code) BETWEEN 2 AND 12
    AND stock_code GLOB '[A-Z]*'
    AND stock_code NOT GLOB '*[^A-Z0-9]*'
  ),
  issue_reference_price_minor INTEGER NOT NULL CHECK (issue_reference_price_minor > 0),
  issued_shares INTEGER NOT NULL CHECK (issued_shares > 0),
  listed_at TEXT NOT NULL
);

INSERT INTO stock_listings_rebuilt (company_id, stock_code, issue_reference_price_minor, issued_shares, listed_at)
SELECT company_id, stock_code, issue_reference_price_minor, issued_shares, listed_at FROM stock_listings;

DROP TABLE stock_listings;
ALTER TABLE stock_listings_rebuilt RENAME TO stock_listings;
CREATE INDEX stock_listings_listed_at ON stock_listings(listed_at, company_id);
CREATE UNIQUE INDEX stock_listings_company_stock_code ON stock_listings(company_id, stock_code);
