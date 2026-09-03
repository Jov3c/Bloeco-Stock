# Task 1 report — player-company financial schema and transaction repository

Status: DONE_WITH_CONCERNS

Commit IDs:

- `afc108b46dfc5eb674b9973f91bfb1b976c1a220` — `feat: persist player company operating events`

Files changed:

- `src/main/java/cn/blockeco/exchange/infrastructure/sql/Database.java`
- `src/main/resources/db/migration/V015.sql`
- `src/main/java/cn/blockeco/exchange/domain/finance/OperatingEventKind.java`
- `src/main/java/cn/blockeco/exchange/domain/finance/VerifiedOperatingEvent.java`
- `src/main/java/cn/blockeco/exchange/ports/CompanyOperationsRepository.java`
- `src/main/java/cn/blockeco/exchange/infrastructure/sql/SqlCompanyOperationsRepository.java`
- `src/test/java/cn/blockeco/exchange/infrastructure/sql/SqlCompanyOperationsRepositoryTest.java`

## RED

Command:

```powershell
$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Temp'; .\gradlew.bat test --tests cn.blockeco.exchange.infrastructure.sql.SqlCompanyOperationsRepositoryTest
```

Result: failed during `:compileTestJava` with 24 expected missing-symbol errors for `OperatingEventKind`, `VerifiedOperatingEvent`, `CompanyOperationsRepository`, and `SqlCompanyOperationsRepository`. The build exited non-zero (`BUILD FAILED in 14s`).

## GREEN

Command:

```powershell
$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Temp'; .\gradlew.bat test --tests cn.blockeco.exchange.infrastructure.sql.SqlCompanyOperationsRepositoryTest
```

Result: `BUILD SUCCESSFUL in 4s` (exit code 0). Both focused repository tests passed: first-time income is idempotent and reconciles the treasury ledger; an expense consumes retained earnings before accumulating loss.

## Self-review and concerns

- Reviewed the staged owned-file diff with `git diff --cached --check`; it reported no whitespace errors. Only the seven Task 1 files were committed.
- The focused test command emits an existing Java unchecked-operations note from `BluechipBootstrapFundingServiceTest`; it is unrelated to this task.
- Only the requested focused test was run. The project has older tests/fixtures that use positional `INSERT INTO company_cash_accounts VALUES (...)`; because V015 adds a column, those tests may need to use explicit column lists when the full suite is later run. No unrelated test files were changed in this task.

## FIX ROUND 1

Review findings addressed:

- **P1 / V015 compatibility:** migrated legacy fixtures no longer use a positional `INSERT INTO company_cash_accounts VALUES (...)`; they name the five legacy columns explicitly so V015's `accumulated_loss_minor` default remains available.
- **P2 / durable duplicate claim:** `SqlCompanyOperationsRepository.record` now wraps its caller-owned transactional work in a JDBC savepoint. Any validation, balance, or append failure rolls back the repository's partial work before rethrowing, while successful and duplicate calls release the savepoint.

### RED

After extending `SqlCompanyOperationsRepositoryTest` to retain the caller transaction after a rejected record and then retry the same external event key, ran:

```powershell
$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Temp'; .\gradlew.bat test --tests cn.blockeco.exchange.infrastructure.sql.SqlCompanyOperationsRepositoryTest
```

Result: `BUILD FAILED in 3s`. `rejectedExpenseDoesNotLeaveAnEventClaimWhenCallerCommitsAfterHandlingTheError` failed at line 78 because the pre-fix repository inserted `company_operating_events` before checking unreserved cash, so the assertion for zero events failed after the caller committed the caught exception.

### GREEN

With the savepoint rollback in place, reran the focused command above.

Result: `BUILD SUCCESSFUL in 3s`; all four focused tests passed:

- income recording remains idempotent and reconciles `COMPANY_TREASURY`;
- expense uses retained earnings before accumulated loss;
- insufficient unreserved cash leaves zero operating events/audits/new treasury entries when the caller catches and commits, and the same event records successfully after cash is made sufficient;
- inactive binding leaves zero effects when caught and committed, and the same event records successfully after reactivation.

### Full-suite verification

Ran:

```powershell
$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Temp'; .\gradlew.bat test --rerun-tasks
```

The full suite executed; the generated results contain 58 JUnit XML reports and no `<failure>` or `<error>` elements. Compilation emitted the pre-existing unchecked-operations note and the JVM emitted its bootstrap-classpath sharing warning, but no test failures.

### Follow-up fixture compatibility commit

`13d8491220f1c58afecc44d89c50315d94bde3b2` updates the shared application `Fixtures.company` cash-account insert to name the five legacy columns explicitly, allowing V015 to supply `accumulated_loss_minor` from its default. Focused verification passed:

```powershell
$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Temp'; .\gradlew.bat test --tests cn.blockeco.exchange.application.AssetBindingServiceTest --tests cn.blockeco.exchange.application.PrimaryOfferingServiceTest
```

Result: `BUILD SUCCESSFUL in 7s`.
