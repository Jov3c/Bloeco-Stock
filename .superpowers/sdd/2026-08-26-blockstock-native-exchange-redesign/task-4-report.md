# Task 4 completion report — native exchange release QA

## Delivered

- Repaired the isolated original-client QA scripts so they use only `127.0.0.1:25566` (RCON `25567`); neither script can target the normal `25565` server.
- `bluechip-gui-e2e.mjs` now checks the localized `星铸工业` / `NOVA` market entry, real five-level detail slots, the actual clock (15) and enchanted-book (16) controls, and a material screen change from `分时线` to `日K线`.
- Both Mineflayer flows send two confirmation clicks and assert from SQLite that the action produced exactly one securities-cash operation or one NOVA order. The bluechip flow additionally checks the deposited amount changed once.
- The open-session bluechip flow waits up to 390 seconds (overridable by `QA_PARTICIPANT_WAIT_MS`) for a persisted trade between the configured participant UUID and the distinct maker UUID. It asserts positive persisted trade volume and, where the fill exposes both order sequence numbers, quote-before-participant ordering.
- The open phase now uses the existing QA-safe `stockadmin bluechip pause` control to cancel only maker quotes, then condition-polls the same isolated SQLite database until the still-scheduled participant has a real `OPEN` or `PARTIALLY_FILLED` ordinary order. This establishes the close-cancellation precondition without SQL mutation or a wall-clock sleep. It writes that one order's immutable `stock_orders.id` and `priority_sequence` to the short-lived `qa-server/mcp/bluechip-participant-close.qa.json` marker. OPEN first removes any stale marker; the stopped-server ledger check removes it in `finally`. POSTCLOSE reads the exact marker and asserts the exact row still belongs to the participant, retains its priority sequence, and has state `CANCELLED`; the ledger check repeats that same exact-row assertion alongside nonnegative company/securities cash, holdings, order/trade balances, issued-share conservation, escrow-ledger reconciliation including company cash, and zero stopped-Vault escrow difference.

## Verification actually run

| Check | Result |
|---|---|
| `node --check qa-server/mcp/bluechip-gui-e2e.mjs` | Exit 0 |
| Exact participant-close marker static acceptance (durable marker, exact-ID `CANCELLED` lookup, read-only DB/no `db.exec`, cleanup, and no `25565`) | Exit 0 (`STATIC_QA_MARKER_PASS`) |
| `node --check qa-server/mcp/market-gui-e2e.mjs` | Exit 0 |
| `git diff --check -- qa-server/mcp/bluechip-gui-e2e.mjs qa-server/mcp/market-gui-e2e.mjs` | Exit 0 |
| TCP probe `127.0.0.1:25566` | Not listening |
| TCP probe `127.0.0.1:25567` | Not listening |

The real Mineflayer acceptance, SQLite ledger assertions, and Gradle/shadow-JAR build were **not run** in this task. The isolated Paper server was unavailable, and the project has the recorded environment-specific Gradle loopback failure before test discovery (`java.io.IOException: Unable to establish loopback connection`). No green build or bot result is claimed.

## Reproducible isolated acceptance

After restoring an isolated Paper instance on 25566 and ensuring its copied `plugins/BlockStock/config.yml` has an open `market.time-zone`:

```powershell
./gradlew.bat test shadowJar --no-build-cache --no-daemon --console=plain
Copy-Item -Force .\build\libs\blockstock-0.1.0-SNAPSHOT-all.jar .\qa-server\plugins\BlockStock.jar
Push-Location .\qa-server\mcp
$env:QA_CLOCK_PHASE='OPEN'
node .\bluechip-gui-e2e.mjs
node .\market-gui-e2e.mjs
Pop-Location
```

For the complete pre-open/open/post-close/restart/candle/dividend sequence and stopped-Vault reconciliation, run the existing isolated-only harness:

```powershell
powershell -ExecutionPolicy Bypass -File .\qa-server\mcp\bluechip-qa-runner.ps1
```

It starts only the QA instance on 25566, advances its configured market-zone phases, and invokes `bluechip-gui-e2e.mjs --ledger` with `QA_ESCROW_MINOR` after the server stops. The existing runner already executes the required sequence: `OPEN` (which emits `BLUECHIP_QA_CLOSE_SEED` only after the real participant residual order exists), stops that same QA server, then starts `POSTCLOSE` against the same database. This updated full harness was not run for this remediation. Exact no-live result: the static marker acceptance, Node syntax check, and targeted whitespace check ran successfully; no Paper process, Mineflayer client, database fixture, Gradle build, or live-server port was started or contacted.
