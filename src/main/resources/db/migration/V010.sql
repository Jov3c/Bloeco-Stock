CREATE TABLE market_session_state (
  singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
  observed_day TEXT NOT NULL,
  accepts_matching INTEGER NOT NULL CHECK (accepts_matching IN (0, 1)),
  opening_catch_up_day TEXT NULL
);

INSERT INTO market_session_state (singleton, observed_day, accepts_matching, opening_catch_up_day)
VALUES (1, '1970-01-01', 0, NULL);
