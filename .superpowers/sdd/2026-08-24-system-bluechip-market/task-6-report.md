# Task 6 report: unified fifteen-day dividends

## Delivered

- Added `DividendCycleService.settleDueRuns()` and its immutable `DividendRunResult`.
- Added a transaction-owned `BluechipRepository.settleDueDividendRuns(...)` implementation for both listed player companies and system bluechips.
- Uses the existing `dividend_runs(company_id, dividend_at)` unique key as the retry barrier before any holder credit.
- Credits eligible share holders' securities balances, debits only the company cash / bluechip fund source, and records completion, audit, and announcement in the same SQL transaction.
- Bluechip profit is configured base simulation plus active company, industry, or market event profit impacts. Player-company profit is the existing cash-account retained earnings.
- Positive distributions emit `DIVIDEND_PAID`; no available distribution (including zero or negative profit) emits a `NO_DIVIDEND:` report.
- Added `market.dividend-base-profit` to the default configuration for scheduler wiring in Task 7.

## TDD evidence

1. Added `DividendCycleServiceTest` before the service existed.
2. Ran `./gradlew.bat test --tests '*DividendCycleServiceTest' --no-daemon --console=plain`.
   It failed at compilation because `DividendCycleService` did not exist.
3. Implemented the smallest transaction boundary and SQL settlement path.
4. Focused suite passed after implementation, including the added player-company retained-earnings case.

## Verification

- `./gradlew.bat test --tests '*DividendCycleServiceTest' --rerun-tasks --no-daemon --console=plain` — passed (3 tests).
- `./gradlew.bat test --no-daemon --console=plain` — passed.
- `git diff --check` — passed.

One intervening full rerun encountered a transient Windows lock on Gradle's generated `build/test-results/test/binary/output.bin`; no source or database behavior failed. A subsequent full Gradle run completed successfully without changing sources.

## Fix round 1: persistent retry semantics

Review found that `DividendCycleService` retained a process-local last result. That made a same-process retry differ from a fresh service instance after restart, despite no new `dividend_runs` row being due.

1. **RED:** changed the bluechip retry test to require both a second call on the same service and a call through a new service instance to return an empty list. The focused command failed at `DividendCycleServiceTest` line 35 because the old cache returned prior runs.
2. **GREEN:** removed the `lastResults` field and return only rows settled by the current transaction.
3. **Focused verification:** `./gradlew.bat test --tests '*DividendCycleServiceTest' --no-daemon --console=plain` passed.
4. **Full verification:** `./gradlew.bat test --no-daemon --console=plain` passed.
