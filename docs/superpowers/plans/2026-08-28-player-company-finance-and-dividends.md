# Player Company Finance and Dividends Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make verified operating events update a listed player company's financials, publish an idempotent monthly report, and expose both in the native BlockStock company GUI while retaining the existing fifteen-day dividend settlement.

**Architecture:** Add a narrow, provider-neutral operating-event source port and persist every accepted event behind a unique external-event key. `CompanyOperationsService` transforms verified external events into company cash, distributable retained earnings, accumulated losses and escrow-ledger entries inside one transaction. A separate report service snapshots prior-calendar-month events; the existing `DividendCycleService` remains the only dividend payer.

**Tech Stack:** Java 21, Paper 1.21.4, SQLite/HikariCP, CompletableFuture, JUnit 5, AssertJ, Adventure inventory GUIs.

**Spec:** `docs/superpowers/specs/2026-08-28-player-company-finance-and-dividend-design.md`

## Global Constraints

- Remain a Paper server plugin for vanilla clients; do not require a client mod.
- Retain the existing Asia/Shanghai server configuration and unified 15-day dividend cycle.
- Do not replace `SqlBluechipRepository.settleDueDividendRuns`; player dividends continue to use positive distributable retained earnings only.
- External provider calls are read-only and occur on Bukkit's main thread; database writes occur in a caller-owned transaction.
- Every accepted financial event must be idempotent, audited and reconciled in the `COMPANY_TREASURY` escrow ledger.
- Never infer revenue from ownership alone; bindings without a compatible event source show that no automatic revenue source is connected.
- Preserve unrelated dirty worktree files and stage only files owned by this plan.

---

## File structure

| File | Responsibility |
| --- | --- |
| `src/main/resources/db/migration/V015.sql` | Financial event/report tables and loss balance migration. |
| `src/main/java/.../domain/finance/OperatingEventKind.java` | Income/expense direction. |
| `src/main/java/.../domain/finance/VerifiedOperatingEvent.java` | Immutable validated payload supplied by an adapter. |
| `src/main/java/.../ports/CompanyOperatingEventSource.java` | Optional event-read protocol for compatible asset providers. |
| `src/main/java/.../ports/CompanyOperationsRepository.java` | Transactional event, balance and report persistence interface. |
| `src/main/java/.../infrastructure/sql/SqlCompanyOperationsRepository.java` | SQLite implementation, duplicate prevention and report queries. |
| `src/main/java/.../application/CompanyOperationsService.java` | Validation and idempotent event settlement. |
| `src/main/java/.../application/CompanyFinancialReportService.java` | Monthly snapshot scheduler service. |
| `src/main/java/.../paper/CompanyFinanceGui.java` | Read-only Chinese company finance/report inventory pages. |
| `src/main/java/.../BlockecoPlugin.java` | Service wiring and scheduler registration. |
| focused `src/test/java/...` tests | Migration, operations, reports, GUI and scheduler behaviour. |

### Task 1: Financial schema and transaction repository

**Files:**
- Modify: `src/main/java/cn/blockeco/exchange/infrastructure/sql/Database.java`
- Create: `src/main/resources/db/migration/V015.sql`
- Create: `src/main/java/cn/blockeco/exchange/domain/finance/OperatingEventKind.java`
- Create: `src/main/java/cn/blockeco/exchange/domain/finance/VerifiedOperatingEvent.java`
- Create: `src/main/java/cn/blockeco/exchange/ports/CompanyOperationsRepository.java`
- Create: `src/main/java/cn/blockeco/exchange/infrastructure/sql/SqlCompanyOperationsRepository.java`
- Test: `src/test/java/cn/blockeco/exchange/infrastructure/sql/SqlCompanyOperationsRepositoryTest.java`

**Interfaces:**
- Produces `CompanyOperationsRepository.record(Connection, AssetBinding, VerifiedOperatingEvent, Instant): RecordResult`, where `RecordResult` is `RECORDED` or `DUPLICATE`.
- Produces `CompanyOperationsRepository.FinancialSnapshot(companyId, cash, retainedEarnings, accumulatedLoss, income, expense)`.
- `VerifiedOperatingEvent` fields are `adapterId`, `externalEventKey`, `kind`, `amount`, `occurredAt`, and `displaySummary`; `amount` is positive.

- [ ] **Step 1: Write the failing repository tests**

```java
@Test void recordsIncomeOnceAndReconcilesCompanyTreasury() { /* migrated SQLite; active binding; record same event twice */ }
@Test void expenseConsumesEarningsThenCreatesAccumulatedLoss() { /* income 40, expense 65 => retained 0, loss 25 */ }
```

Assert that duplicate insertion leaves `company_operating_events`, `audit_events` and `escrow_ledger_entries` counts unchanged; assert income increases `cash_minor` and retained earnings, expense reduces cash, and only actual cash deltas appear in `COMPANY_TREASURY`.

- [ ] **Step 2: Run the focused test to verify RED**

Run: `$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Temp'; .\gradlew.bat test --tests cn.blockeco.exchange.infrastructure.sql.SqlCompanyOperationsRepositoryTest`

Expected: compilation failure because the financial-event repository and V015 migration do not exist.

- [ ] **Step 3: Implement the minimal schema and repository**

Add V015 to `Database.MIGRATIONS`. Create `company_operating_events` with `UNIQUE(adapter_id, external_event_key)`, `company_monthly_reports` keyed by `(company_id, period_start)`, and add non-negative `accumulated_loss_minor` to `company_cash_accounts`. Use `INSERT ... ON CONFLICT DO NOTHING` for the event claim; only after a successful claim update balances and append one `OPERATING_INCOME_RECORDED`/`OPERATING_EXPENSE_RECORDED` audit record and `COMPANY_TREASURY` ledger delta. Income first offsets accumulated loss; expenses use retained earnings then accumulated loss. Reject an expense when unreserved company cash is insufficient.

- [ ] **Step 4: Run the focused test to verify GREEN**

Run the command in Step 2. Expected: PASS.

- [ ] **Step 5: Commit owned files**

```powershell
git add src/main/java/cn/blockeco/exchange/infrastructure/sql/Database.java src/main/resources/db/migration/V015.sql src/main/java/cn/blockeco/exchange/domain/finance/OperatingEventKind.java src/main/java/cn/blockeco/exchange/domain/finance/VerifiedOperatingEvent.java src/main/java/cn/blockeco/exchange/ports/CompanyOperationsRepository.java src/main/java/cn/blockeco/exchange/infrastructure/sql/SqlCompanyOperationsRepository.java src/test/java/cn/blockeco/exchange/infrastructure/sql/SqlCompanyOperationsRepositoryTest.java
git commit -m "feat: persist player company operating events"
```

### Task 2: Verified event ingestion service and optional source contract

**Files:**
- Modify: `src/main/java/cn/blockeco/exchange/ports/AssetBindingRepository.java`
- Modify: `src/main/java/cn/blockeco/exchange/infrastructure/sql/SqlAssetBindingRepository.java`
- Create: `src/main/java/cn/blockeco/exchange/ports/CompanyOperatingEventSource.java`
- Create: `src/main/java/cn/blockeco/exchange/application/CompanyOperationsService.java`
- Test: `src/test/java/cn/blockeco/exchange/application/CompanyOperationsServiceTest.java`
- Test: `src/test/java/cn/blockeco/exchange/infrastructure/sql/SqlAssetBindingRepositoryTest.java`

**Interfaces:**
- Consumes Task 1 `CompanyOperationsRepository.record` and `VerifiedOperatingEvent`.
- Extends `AssetBindingRepository` with `List<AssetBinding> allActive()` and makes `SqlAssetBindingRepository` return every ACTIVE binding in deterministic `(created_at, id)` order.
- Produces `CompletionStage<IngestionResult> ingestDueEvents()` where `IngestionResult` exposes accepted, duplicate, rejected and source-failure counts.
- `CompanyOperatingEventSource.readSince(AssetBinding binding, Instant afterExclusive, Instant throughInclusive)` returns a list of source-verified events and must return only its own `adapterId()`.

- [ ] **Step 1: Write failing ingestion tests**

```java
@Test void acceptsOnlyEventsFromTheBindingAdapterAndDoesNotRepeatAnExternalKey() { }
@Test void oneFailingOptionalSourceDoesNotStopAnotherSource() { }
@Test void rejectsWrongAdapterNegativeAmountAndFutureEvent() { }
```

Use a real migrated SQLite repository plus small in-test source implementations. Assert the accepted source affects balance once and the failure counter increments without rolling back the other binding. Add a repository test proving `allActive()` excludes REVOKED/PENDING bindings and returns deterministic active bindings.

- [ ] **Step 2: Run the focused test to verify RED**

Run: `$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Temp'; .\gradlew.bat test --tests cn.blockeco.exchange.application.CompanyOperationsServiceTest`

Expected: compilation failure because `CompanyOperationsService` and the source port do not exist.

- [ ] **Step 3: Implement minimal ingestion**

Read active bindings through `AssetBindingRepository.allActive()`, select sources by exact adapter ID, invoke each source through the supplied `MainThreadExecutor` when one is configured, validate adapter/key/positive amount/not-future time, and persist each event transactionally. Catch a source exception per binding and return it as a source failure; do not let an unavailable optional plugin fail the whole cycle. Do not implement a synthetic source for `native-asset`.

- [ ] **Step 4: Run the focused test to verify GREEN**

Run the command in Step 2. Expected: PASS.

- [ ] **Step 5: Commit owned files**

```powershell
git add src/main/java/cn/blockeco/exchange/ports/AssetBindingRepository.java src/main/java/cn/blockeco/exchange/infrastructure/sql/SqlAssetBindingRepository.java src/main/java/cn/blockeco/exchange/ports/CompanyOperatingEventSource.java src/main/java/cn/blockeco/exchange/application/CompanyOperationsService.java src/test/java/cn/blockeco/exchange/application/CompanyOperationsServiceTest.java src/test/java/cn/blockeco/exchange/infrastructure/sql/SqlAssetBindingRepositoryTest.java
git commit -m "feat: ingest verified company operating events"
```

### Task 3: Monthly reports and company-finance GUI

**Files:**
- Modify: `src/main/java/cn/blockeco/exchange/ports/CompanyOperationsRepository.java`
- Modify: `src/main/java/cn/blockeco/exchange/infrastructure/sql/SqlCompanyOperationsRepository.java`
- Create: `src/main/java/cn/blockeco/exchange/application/CompanyFinancialReportService.java`
- Create: `src/main/java/cn/blockeco/exchange/paper/CompanyFinanceGui.java`
- Modify: `src/main/java/cn/blockeco/exchange/paper/CompanyGuiController.java`
- Test: `src/test/java/cn/blockeco/exchange/application/CompanyFinancialReportServiceTest.java`
- Test: `src/test/java/cn/blockeco/exchange/paper/CompanyFinanceGuiTest.java`

**Interfaces:**
- Produces `CompanyOperationsRepository.generateMonthlyReport(Connection, CompanyId, YearMonth, ZoneId, Instant)` and `recentReports(CompanyId, int)`.
- `CompanyFinanceGui.open(Player, Company)` renders read-only finance pages and returns to `CompanyGuiController.open` through an injected opener.

- [ ] **Step 1: Write failing report and GUI tests**

```java
@Test void closesPreviousServerTimezoneMonthOnceAndPublishesOneAnnouncement() { }
@Test void financeGuiLabelsPersonalSecuritiesAndCompanyMoneySeparately() { }
@Test void financeGuiStatesThatNativeBindingHasNoAutomaticRevenueSource() { }
```

Assert the same period generates one snapshot, one `MONTHLY_REPORT_PUBLISHED` audit and one `MONTHLY_REPORT:` announcement. Assert the GUI contains Chinese headings for company account, securities account and personal wallet and has a back action.

- [ ] **Step 2: Run the focused tests to verify RED**

Run: `$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Temp'; .\gradlew.bat test --tests cn.blockeco.exchange.application.CompanyFinancialReportServiceTest --tests cn.blockeco.exchange.paper.CompanyFinanceGuiTest`

Expected: compilation failure because report service and finance GUI do not exist.

- [ ] **Step 3: Implement reports and GUI**

Compute the immediately preceding `YearMonth` in the configured market zone, aggregate immutable operating events by event time, and make report insert/announcement/audit transactional and idempotent. The GUI main page shows available company cash, retained earnings, accumulated loss, current-month income/expense/net profit and next dividend time; the history page shows six reports. Add one `finance` action from company centre without changing unrelated company/asset/IPO paths.

- [ ] **Step 4: Run the focused tests to verify GREEN**

Run the command in Step 2. Expected: PASS.

- [ ] **Step 5: Commit owned files**

```powershell
git add src/main/java/cn/blockeco/exchange/ports/CompanyOperationsRepository.java src/main/java/cn/blockeco/exchange/infrastructure/sql/SqlCompanyOperationsRepository.java src/main/java/cn/blockeco/exchange/application/CompanyFinancialReportService.java src/main/java/cn/blockeco/exchange/paper/CompanyFinanceGui.java src/main/java/cn/blockeco/exchange/paper/CompanyGuiController.java src/test/java/cn/blockeco/exchange/application/CompanyFinancialReportServiceTest.java src/test/java/cn/blockeco/exchange/paper/CompanyFinanceGuiTest.java
git commit -m "feat: publish player company finance reports"
```

### Task 4: Plugin wiring, repeat-cycle and regression verification

**Files:**
- Modify: `src/main/java/cn/blockeco/exchange/BlockecoPlugin.java`
- Modify: `docs/operations/blockstock-requirements-traceability.md`
- Modify: `docs/operations/blockstock-formal-release-test-matrix.md`
- Test: `src/test/java/cn/blockeco/exchange/application/DividendCycleServiceTest.java`
- Test: `src/test/java/cn/blockeco/exchange/BlockecoPluginTest.java` (or the existing closest bootstrap test)

**Interfaces:**
- Consumes Tasks 1–3 services.
- Schedules operating ingestion at configurable five-minute intervals and report checks hourly without altering the existing dividend scheduler.

- [ ] **Step 1: Write failing bootstrap/regression tests**

```java
@Test void profitableOperatingIncomeFeedsExistingFifteenDayPlayerDividendOnce() { }
@Test void pluginRegistersOperationsAndMonthlyReportSchedulersAfterStartup() { }
```

The first test must ingest real verified income, advance a clock by fifteen days, invoke the existing dividend service twice, and verify exactly one shareholder credit and a reconciled ledger.

- [ ] **Step 2: Run the focused tests to verify RED**

Run: `$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Temp'; .\gradlew.bat test --tests cn.blockeco.exchange.application.DividendCycleServiceTest --tests cn.blockeco.exchange.BlockecoPluginTest`

Expected: failure because the services are not wired/scheduled.

- [ ] **Step 3: Wire only the new services**

Create the SQL repository once in `BlockecoPlugin.finishEnable`, construct the operations service with the asset adapter registry's compatible sources, schedule ingestion every five minutes and report checks hourly on the existing executor/lifecycle mechanism. Add no provider event source until its external plugin compatibility test exists. Update traceability to state GV-01 is implemented only for installed compatible event-source adapters; keep unimplemented governance requirements open.

- [ ] **Step 4: Run focused then full verification**

Run the Step 2 command, then:

```powershell
$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Temp'; .\gradlew.bat test
```

Expected: all focused tests and the full suite pass.

- [ ] **Step 5: Execute isolated Paper acceptance test and commit**

Deploy the shaded JAR only to `qa-server` on port 25566, restart only that QA server, then run a two-role Mineflayer script that creates/binds a compatible test source, records one income event, opens finance GUI, advances/invokes a dividend settlement and verifies one securities-account credit. Add its exact evidence to the release matrix. Commit only the Task 4 files and the committed QA script:

```powershell
git add src/main/java/cn/blockeco/exchange/BlockecoPlugin.java src/test/java/cn/blockeco/exchange/application/DividendCycleServiceTest.java docs/operations/blockstock-requirements-traceability.md docs/operations/blockstock-formal-release-test-matrix.md qa-server/mcp/company-finance-e2e.mjs
git commit -m "feat: schedule player company financial reporting"
```

## Plan self-review

- **Spec coverage:** Tasks 1–2 cover verified automatic financial events and safety boundaries; Task 3 covers monthly reports and Chinese GUI; Task 4 covers fifteen-day dividend integration, scheduler, docs and isolated Paper test. External plugin-specific collectors remain explicitly out of scope until their API-level compatibility work, as required by the specification.
- **Placeholder scan:** no `TODO`, `TBD`, “appropriate handling”, or cross-task implicit implementation references remain.
- **Type consistency:** Task 1 defines the exact event and repository contracts consumed by Task 2; Task 3 extends that repository for reports; Task 4 wires the services without changing the dividend service signature.
