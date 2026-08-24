# Task 9 — Bluechip bootstrap liquidity and escrow integrity

## Root cause

`SqlBluechipRepository.insertInitial()` directly credited `securities_cash_accounts` for the configured bluechip fund cash.  It did not transfer any Vault money into the treasury escrow account and did not append the matching `SECURITIES_CASH` ledger liability.  Therefore a clean database had system cash but an empty ledger; `SqlSecuritiesCashRepository.reconcile()` correctly rejected startup.

## Mechanism and crash semantics

V012 adds `bluechip_bootstrap_funding`.  The configured finite total is injected through the configured non-player system account, then withdrawn from that account, then deposited to the configured Vault escrow account.  Each external leg is preceded by a committed `*_REQUESTED` state.  A successful result is committed as its next durable state before the next leg.  After the escrow deposit is confirmed, one SQLite transaction creates the ten bluechips (when missing), applies the system securities cash, writes its escrow-ledger liability, and marks funding completed.

Any provider failure, timeout/throw, or failure to durably record a post-call result becomes `AMBIGUOUS`.  Startup reports the durable detail and refuses to repeat that external request; it does not silently mint/credit again.  A pre-existing database created by the earlier defect is repaired without a second local cash credit: it receives the real external funding and the missing ledger entry in the final local transaction.

## TDD evidence

RED test added first: `BluechipBootstrapServiceTest.initialSystemLiquidityIsBackedByTheEscrowLedger`.

RED command:

`./gradlew.bat test --tests cn.blockeco.exchange.application.BluechipBootstrapServiceTest.initialSystemLiquidityIsBackedByTheEscrowLedger --no-daemon --console=plain`

Observed expected failure: `IllegalStateException` from `SqlSecuritiesCashRepository.reconcile` because authoritative system cash had no matching escrow ledger row.

GREEN focused command:

`./gradlew.bat test --tests cn.blockeco.exchange.application.BluechipBootstrapFundingServiceTest --tests cn.blockeco.exchange.application.BluechipBootstrapServiceTest --tests cn.blockeco.exchange.infrastructure.sql.SqlBluechipRepositoryTest --tests cn.blockeco.exchange.infrastructure.sql.MigrationTest.v012_creates_durable_bluechip_bootstrap_funding_states --no-daemon --console=plain`

Result: `BUILD SUCCESSFUL`.

The added funding-service persistence tests prove that a provider outcome after an attempted source credit becomes `AMBIGUOUS`, and that either an ambiguous record or a durable `*_REQUESTED` record is never issued again after restart.

## Full verification

`./gradlew.bat test shadowJar --no-build-cache --no-daemon --console=plain`

Result: completed successfully; XML result aggregation reports `tests=363 failures=0 errors=0`; `build/libs/blockstock-0.1.0-SNAPSHOT-all.jar` was generated (15,291,830 bytes).

No 25565 server or QA-25566 server/database was started or changed.
