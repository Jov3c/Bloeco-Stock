# Task 10 — funding/bootstrap safety repair

## Root cause

`finishEnable` ran on Paper's primary thread and synchronously joined the SQL bootstrap future.  Bootstrap funding runs on the SQL executor, and schedules Vault work back to Paper's primary thread before joining it.  The resulting wait cycle was primary thread -> SQL executor -> primary thread.

The operator `fund CODE cash DELTA` path modified the securities-cash ledger directly without a matching Vault escrow transfer or durable external funding recovery record.

## TDD evidence

1. Added `fundingChainDoesNotSynchronouslyWaitForMainThreadWork`; the focused test initially failed to compile because `ensureEscrowFundedAsync` did not exist.
2. Added command and repository cash-adjustment rejection tests; the repository test initially failed because its error was `unknown bluechip`, not a cash-backing rejection.
3. Implemented a background funding entrypoint, deferred post-bootstrap player wiring until its completion on the main thread, and rejected cash adjustment at both command and repository boundaries.

## Verification

Focused green:

`./gradlew.bat test --tests cn.blockeco.exchange.application.BluechipBootstrapFundingServiceTest --tests cn.blockeco.exchange.paper.BluechipAdminCommandTest --no-daemon --console=plain`

Full green:

`./gradlew.bat test shadowJar --no-build-cache --no-daemon --console=plain`

No QA server or port 25565 was started, stopped, or modified.

## Crash/restart semantics

The existing funding state machine remains authoritative: each provider request is persisted before issuance, uncertain provider calls remain `AMBIGUOUS`/manual recovery, and a completed escrow deposit is applied to the internal liquidity ledger exactly once.  Startup does not expose player-facing runtime readiness until the async bootstrap continuation has run.  Administrative cash mutations are disabled because no operator-side external escrow workflow exists; share inventory adjustments remain audited.
