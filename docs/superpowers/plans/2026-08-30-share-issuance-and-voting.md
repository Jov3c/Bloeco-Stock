# Share Issuance and Voting Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add founder-proposed, shareholder-voted secondary share issuance for listed player companies using securities-account reservations, with no change to the existing IPO path.

**Architecture:** Introduce a dedicated issuance proposal state machine and immutable record-date holding snapshots. Proposal/vote/subscription actions use a new transactional repository; settlement reserves/releases securities cash and changes company cash, shares, company total shares, public issued-share projection, audits, announcements and escrow ledger atomically. A dedicated GUI/controller and lifecycle scheduler are wired after the domain flow is proven.

**Tech Stack:** Java 21, Paper 1.21.4, SQLite/HikariCP, JUnit 5, AssertJ, Adventure native inventories.

**Spec:** `docs/superpowers/specs/2026-08-28-share-issuance-and-voting-design.md`

## Global Constraints

- Only LISTED non-bluechip player companies may issue; never reuse IPO tables or Vault wallet escrow.
- Announcement is 12 hours; vote is 2 days; a vote's weight is record-date available plus reserved shares.
- Proposal requires yes/effective votes >= 50% and effective/snapshot participation >= 30%.
- Securities cash reservation, issuance settlement, company cash, total shares, stock-listing issued-share projection, holdings, escrow ledger, audit and announcement are transactional and idempotent.
- Failed/expired/rejected proposals never change cash, stock supply, holdings or locked securities cash.
- Existing dirty worktree files are not owned by this plan; stage only plan changes.

---

### Task 1: Issuance schema, value objects and SQL repository

**Files:**
- Modify: `src/main/java/cn/blockeco/exchange/infrastructure/sql/Database.java`
- Create: `src/main/resources/db/migration/V016.sql`
- Create: `src/main/java/cn/blockeco/exchange/domain/governance/IssuanceProposal.java`
- Create: `src/main/java/cn/blockeco/exchange/domain/governance/IssuanceProposalState.java`
- Create: `src/main/java/cn/blockeco/exchange/domain/governance/VoteChoice.java`
- Create: `src/main/java/cn/blockeco/exchange/ports/ShareIssuanceRepository.java`
- Create: `src/main/java/cn/blockeco/exchange/infrastructure/sql/SqlShareIssuanceRepository.java`
- Test: `src/test/java/cn/blockeco/exchange/infrastructure/sql/SqlShareIssuanceRepositoryTest.java`

**Interfaces:**
- `createProposal(Connection, IssuanceProposal, Instant)` inserts the proposal, announcement, record-date snapshot and `ISSUANCE_PROPOSED` audit in one transaction.
- `castVote(Connection, UUID proposalId, UUID voter, VoteChoice choice, Instant)` returns the snapshot weight and replaces the same voter's prior choice.
- `tally(UUID proposalId)` returns snapshot, effective, yes, no and abstain share totals.
- Proposal states: `ANNOUNCED, VOTING, REJECTED, APPROVED, SUBSCRIBING, CLOSED, CANCELLED`.

- [ ] **Step 1: Write failing SQL repository tests**

```java
@Test void proposalSnapshotsAvailableAndReservedSharesAtRecordDate() { }
@Test void laterVoteReplacesEarlierChoiceWithoutChangingSnapshotWeight() { }
@Test void bluechipAndUnlistedCompanyCannotCreateProposal() { }
```

Use migrated SQLite data with a listed player company and holdings. Assert one announcement/audit, immutable snapshot, unique proposal/voter vote record, and explicit rejection of bluechip/unlisted IDs.

- [ ] **Step 2: Verify RED**

Run: `$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Temp'; .\gradlew.bat test --tests cn.blockeco.exchange.infrastructure.sql.SqlShareIssuanceRepositoryTest`

Expected: compilation fails because governance types/repository/V016 do not exist.

- [ ] **Step 3: Implement minimal schema and repository**

Create proposal, snapshot, vote and subscription tables with foreign keys, state checks and unique keys. V016 adds no wallet columns. Capture holdings in `issuance_record_snapshots` as `available_shares + reserved_shares`; exclude `bluechip_companies`. Use deterministic proposal announcement IDs and structured audit IDs.

- [ ] **Step 4: Verify GREEN and commit**

Run the Step 2 command. Then:

```powershell
git add src/main/java/cn/blockeco/exchange/infrastructure/sql/Database.java src/main/resources/db/migration/V016.sql src/main/java/cn/blockeco/exchange/domain/governance src/main/java/cn/blockeco/exchange/ports/ShareIssuanceRepository.java src/main/java/cn/blockeco/exchange/infrastructure/sql/SqlShareIssuanceRepository.java src/test/java/cn/blockeco/exchange/infrastructure/sql/SqlShareIssuanceRepositoryTest.java
git commit -m "feat: persist share issuance proposals"
```

### Task 2: Proposal lifecycle and shareholder voting service

**Files:**
- Create: `src/main/java/cn/blockeco/exchange/application/ShareIssuanceService.java`
- Test: `src/test/java/cn/blockeco/exchange/application/ShareIssuanceServiceTest.java`

**Interfaces:**
- `propose(UUID founder, CompanyId company, long shares, Money price)` creates only founder-owned listed-company proposals with announcement/vote deadline.
- `vote(UUID voter, UUID proposalId, VoteChoice choice)` requires a positive snapshot weight.
- `advanceDueProposals()` makes conditional state transitions and returns changed proposal IDs.

- [ ] **Step 1: Write failing lifecycle tests**

```java
@Test void onlyFounderMayProposeAndOnlySnapshotHolderMayVote() { }
@Test void proposalBecomesApprovedOnlyAtBothVoteThresholds() { }
@Test void rejectedProposalLeavesCompanyCashHoldingsAndTotalSharesUnchanged() { }
```

Use a mutable clock. Assert 12-hour announcement then 2-day vote schedule, both thresholds, conditional repeat advance, and no financial side effects before subscription settlement.

- [ ] **Step 2: Verify RED**

Run: `$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Temp'; .\gradlew.bat test --tests cn.blockeco.exchange.application.ShareIssuanceServiceTest`

Expected: missing `ShareIssuanceService`.

- [ ] **Step 3: Implement minimal lifecycle service**

Validate overflow of shares × price, state deadlines and founder ownership. Snapshot creation occurs on proposal. At vote cutoff tally shares; transition to `APPROVED` only when both thresholds hold, otherwise `REJECTED`; all updates use expected-state conditions.

- [ ] **Step 4: Verify GREEN and commit**

Run the Step 2 command. Then:

```powershell
git add src/main/java/cn/blockeco/exchange/application/ShareIssuanceService.java src/test/java/cn/blockeco/exchange/application/ShareIssuanceServiceTest.java
git commit -m "feat: add shareholder issuance voting"
```

### Task 3: Securities-cash subscriptions and atomic issuance settlement

**Files:**
- Modify: `src/main/java/cn/blockeco/exchange/ports/ShareIssuanceRepository.java`
- Modify: `src/main/java/cn/blockeco/exchange/infrastructure/sql/SqlShareIssuanceRepository.java`
- Modify: `src/main/java/cn/blockeco/exchange/application/ShareIssuanceService.java`
- Test: `src/test/java/cn/blockeco/exchange/application/ShareIssuanceServiceTest.java`
- Test: `src/test/java/cn/blockeco/exchange/infrastructure/sql/SqlShareIssuanceRepositoryTest.java`

**Interfaces:**
- `subscribe(UUID holder, UUID proposalId, long shares, String key)` reserves exact securities cash and creates one subscription per correlation key.
- `closeSubscription(UUID proposalId)` fills subscriptions in confirmation order to issued-share capacity; releases all unfilled cash.
- `settleSubscription(Connection,...)` updates `securities_cash_accounts`, company cash, `companies.total_shares`, `stock_listings.issued_shares`, holder shares and COMPANY_TREASURY/SECURITIES_CASH ledger entries atomically.

- [ ] **Step 1: Write failing settlement tests**

```java
@Test void subscriptionReservesSecuritiesCashAndDuplicateKeyDoesNotDoubleReserve() { }
@Test void closeSettlesCapacityThenReleasesUnfilledCashAndReconcilesLedgers() { }
@Test void closingProposalUpdatesPublicIssuedSharesWithCompanyTotalShares() { }
```

Assert no Vault gateway call, exact cash reservation/release, total holdings equal new total shares, public issued count is fresh, and retries do not change money/shares twice.

- [ ] **Step 2: Verify RED**

Run: `$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Temp'; .\gradlew.bat test --tests cn.blockeco.exchange.application.ShareIssuanceServiceTest --tests cn.blockeco.exchange.infrastructure.sql.SqlShareIssuanceRepositoryTest`

Expected: missing subscription/settlement methods.

- [ ] **Step 3: Implement reservation and settlement**

Reserve via `SecuritiesCashRepository` in caller transactions. Never call `SecuritiesCashService` or `TreasuryEscrowGateway`. On close, settle only capacity, release leftovers and atomically append audits/announcements/ledgers. Update both company and listing share projections with checked arithmetic.

- [ ] **Step 4: Verify GREEN and commit**

Run the Step 2 command. Then:

```powershell
git add src/main/java/cn/blockeco/exchange/ports/ShareIssuanceRepository.java src/main/java/cn/blockeco/exchange/infrastructure/sql/SqlShareIssuanceRepository.java src/main/java/cn/blockeco/exchange/application/ShareIssuanceService.java src/test/java/cn/blockeco/exchange/application/ShareIssuanceServiceTest.java src/test/java/cn/blockeco/exchange/infrastructure/sql/SqlShareIssuanceRepositoryTest.java
git commit -m "feat: settle shareholder-approved share issuance"
```

### Task 4: Native GUI, lifecycle wiring and acceptance evidence

**Files:**
- Create: `src/main/java/cn/blockeco/exchange/paper/IssuanceGuiController.java`
- Modify: `src/main/java/cn/blockeco/exchange/paper/CompanyGuiController.java` (selective governance-only hunks)
- Modify: `src/main/java/cn/blockeco/exchange/BlockecoPlugin.java`
- Modify: `docs/operations/blockstock-requirements-traceability.md`
- Modify: `docs/operations/blockstock-formal-release-test-matrix.md`
- Test: `src/test/java/cn/blockeco/exchange/paper/IssuanceGuiControllerTest.java`
- Test: `src/test/java/cn/blockeco/exchange/BlockecoPluginTest.java`

**Interfaces:**
- Founder GUI creates proposal; shareholder GUI votes; public GUI subscribes only in `SUBSCRIBING`.
- Plugin schedules `advanceDueProposals` every minute and cancels it on disable.

- [ ] **Step 1: Write failing GUI/lifecycle tests**

```java
@Test void proposalVoteAndSubscriptionPagesUseChineseCopyAndBackNavigation() { }
@Test void pluginStartsAndStopsIssuanceLifecycleScheduler() { }
```

Assert actions close/reload confirmation only once, account copy says “证券账户”, and scheduler cancellation occurs on shutdown.

- [ ] **Step 2: Verify RED, implement, then GREEN**

Run: `$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Temp'; .\gradlew.bat test --tests cn.blockeco.exchange.paper.IssuanceGuiControllerTest --tests cn.blockeco.exchange.BlockecoPluginTest`

Implement controller registration/wiring, use existing `StockGuiItemFactory`, keep UI Bukkit calls on main thread, run the same command until green.

- [ ] **Step 3: Full verification, QA and commit**

Run full Gradle + shadow JAR, deploy only to QA 25566, and run a multi-role bot test only if the QA server starts. If unavailable, record the exact blocker; never fabricate evidence.

```powershell
$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Temp'; .\gradlew.bat test shadowJar --rerun-tasks
git add src/main/java/cn/blockeco/exchange/paper/IssuanceGuiController.java src/main/java/cn/blockeco/exchange/paper/CompanyGuiController.java src/main/java/cn/blockeco/exchange/BlockecoPlugin.java src/test/java/cn/blockeco/exchange/paper/IssuanceGuiControllerTest.java src/test/java/cn/blockeco/exchange/BlockecoPluginTest.java docs/operations/blockstock-requirements-traceability.md docs/operations/blockstock-formal-release-test-matrix.md
git commit -m "feat: expose share issuance voting gui"
```

## Plan self-review

- **Spec coverage:** Tasks 1–2 deliver immutable snapshot/vote governance; Task 3 delivers securities-cash reservation and atomic dilution settlement; Task 4 delivers Chinese GUI, lifecycle and honest acceptance evidence.
- **Placeholder scan:** no deferred implementation labels or unstated interfaces remain.
- **Type consistency:** Task 1 defines proposal/repository types, Task 2 owns lifecycle, Task 3 extends it for subscriptions, and Task 4 only wires the completed application API.

