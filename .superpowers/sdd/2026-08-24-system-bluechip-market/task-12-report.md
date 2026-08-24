# Task 12 — isolated real-Paper bluechip final QA

Status: **blocked by a fresh-install product defect.**  The normal server on
port 25565 was never contacted.  The QA Paper process on 127.0.0.1:25566 was
stopped after every attempt and is not running.

## Test-first harness correction

Task 8's `bluechip-gui-e2e.mjs --ledger` was first run unchanged.  It failed
on the prior QA database (`bluechip holdings must be conserved: 10 != 0`),
proving that it can detect a ledger violation, but its expected fresh-DB
`market_candles` and `dividend_runs` rows were also impossible: neither
lifecycle had been driven.  Its PREOPEN/OPEN/POSTCLOSE text was only a
comment, not an orchestration.

The replacement QA-only harness is committed under `qa-server/mcp/`:

- `bluechip-gui-e2e.mjs` is a real Mineflayer vanilla client fixed to loopback
  ports 25566/25567.  It asserts `/stock` -> market -> all ten bluechips ->
  NOVA detail -> five maker levels -> anvil GUI deposit -> GUI buy; it queries
  SQLite to assert queued orders outside session, real trade and holding on
  open, an administrator market event, and its visibility in the native news
  menu.
- `bluechip-qa-runner.ps1` backs up (moves, never deletes) an existing QA DB,
  copies the fresh Shadow JAR plus clean config, and drives market zones
  `Etc/GMT+10` (PREOPEN), `Etc/UTC` (OPEN), and `Etc/GMT-12` (POSTCLOSE).
  It waits for the real post-close scheduler to produce candles, then uses a
  stopped-server persistent `next_dividend_at` fixture to make the actual
  fifteen-day dividend scheduler run twice across a restart.  The stopped DB
  ledger asserts non-negative balances, share conservation, completed backing
  bootstrap, event persistence, ten candles, ten completed dividend runs, and
  candle/dividend idempotency.

The runner initially failed red because `$root` was computed one parent too
high and could not find the Shadow JAR.  It was corrected before product
execution.  A legacy QA config was also invalid YAML; the runner now always
copies the current default config before applying its QA-only zone fixture.

## Fresh-product reproduction

The complete runner was launched after copying the current fresh Shadow JAR
and moving the old QA database to a timestamped backup.  A direct control
startup was then repeated with an untouched byte-for-byte default config to
separate the runner's configuration mutation from the product.  The default
config parsed successfully, but BlockStock disabled itself before creating a
new SQLite database or accepting a GUI session:

```text
BlockStock 启动失败：java.lang.IllegalStateException:
bluechip bootstrap funding requires manual recovery: source credit
```

Root cause: the bootstrap funding handshake first calls Vault/Essentials to
credit the fixed system UUID.  Essentials rejects that UUID because it has
never been a player/account, so no finite backing fund can be established.
The subsequent fail-closed disable is correct, but the source-credit strategy
is not compatible with the real Vault provider.  Earlier fake-economy tests
did not cover this provider behavior.

No product source was changed to bypass this.  Required real assertions remain
pending after a product repair: all three session phases, GUI news, post-close
opening match, candle and 15-day dividend lifecycle, stopped-server escrow
liability reconciliation, and the requested final Gradle full build.
