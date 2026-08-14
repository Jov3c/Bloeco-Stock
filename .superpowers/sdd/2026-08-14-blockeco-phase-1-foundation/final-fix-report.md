# Phase 1 final review fix report

## RED

- Added compile-time tests for the shared company-name normalization API, deterministic saga clock constructor, and SQLite-aware migration splitter. The focused Gradle run failed because `Company.normalizeName`, `Database.splitStatements`, and the clock-injected `SqlRegistrationSagaRepository` constructor did not exist.
- Added real SQLite registration audit tests for success, compensation, recovery, and audit-write rollback; before the state/audit helper these requirements were absent from the production path.

## GREEN

- Every registration saga persistence transition is now paired in the same `TransactionRunner` transaction with an immutable `COMPANY_REGISTRATION_<STATE>` audit event. Payloads include saga id, from/to state, withdrawal minor amount, stable reason string, and founder actor. `COMPANY_REGISTERED` remains in the completion transaction.
- Success event order: PREPARED, WITHDRAWN, COMPANY_REGISTERED, COMPLETED. SQL completion failure/refund success: PREPARED, WITHDRAWN, REFUND_REQUIRED, REFUNDED. Startup recovery emits AMBIGUOUS and stale-WITHDRAWN REFUND_REQUIRED events.
- The real SQLite rollback test proves an audit write failure leaves no PREPARED saga row. External Vault debit/deposit is deliberately not claimed atomic with SQL; only state plus audit is atomic.
- Query normalization delegates to `Company.normalizeName`, sharing Unicode whitespace collapse, validation, and `Locale.ROOT` casing with registration. Tests cover 2/24-code-point bounds and Unicode whitespace query lookup.
- `SqlRegistrationSagaRepository` uses injected `AppClock`; Paper wiring shares one clock for the service and repository.
- Legacy upgrades apply a frozen published `V001-published.sql` test fixture, assert byte identity with current V001, and use the SQLite-aware splitter.
- `Database.splitStatements` handles quoted semicolons/escaped quotes, line and block comments, and trigger `BEGIN ... END` bodies. V001/V002 migration tests remain green.

## Commands

- `./gradlew.bat test --tests 'cn.blockeco.exchange.domain.company.CompanyTest' --tests 'cn.blockeco.exchange.infrastructure.sql.MigrationTest' --tests 'cn.blockeco.exchange.application.CompanyRegistrationServiceTest'` — GREEN.
- `./gradlew.bat test --tests 'cn.blockeco.exchange.application.CompanyRegistrationServiceTest'` — GREEN.
- `git diff 077314a -- src/main/resources/db/migration/V001.sql` — no output.

Full clean build, artifact and whitespace verification are run immediately before commit.
