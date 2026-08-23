CREATE TABLE native_assets (
    id TEXT PRIMARY KEY,
    company_id TEXT NOT NULL,
    founder_uuid TEXT NOT NULL,
    name TEXT NOT NULL,
    created_at TEXT NOT NULL,
    UNIQUE (company_id, name),
    FOREIGN KEY (company_id) REFERENCES companies(id)
);
