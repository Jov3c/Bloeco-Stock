# Requirements-Driven Release Validation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Validate every confirmed BlockStock requirement against a reproducible test and repair every defect found in implemented scope.

**Architecture:** `blockstock-requirements-traceability.md` is the requirement source of truth. JUnit exercises domain invariants; Mineflayer bots exercise unmodified-client Paper GUI flows; SQLite is read-only during assertions. Each production correction begins with a focused red test and finishes with a full Gradle regression.

**Tech Stack:** Java 21, Paper 1.21.4, Gradle, JUnit 5, SQLite, Vault, EssentialsX, Mineflayer/Node.js.

**Spec:** `docs/operations/blockstock-requirements-traceability.md`

## Global Constraints

- Use only the isolated QA Paper server at `127.0.0.1:25566`; never control the player server on port `25565`.
- Assert money in minor units from Vault and read-only SQLite; no test may fabricate balances, holdings, orders or trades by SQL write.
- Keep all player workflows compatible with an unmodified vanilla client.
- Treat external asset plugins as optional; their absence must not break the native asset flow.
- Requirements marked `未实现` must remain release blockers until business logic, migration, GUI, recovery and tests are all present.

---

### Task 1: Commit the requirement baseline

**Files:**
- Create: `docs/operations/blockstock-requirements-traceability.md`
- Create: `docs/superpowers/plans/2026-08-28-requirements-driven-release-validation.md`

**Interfaces:**
- Consumes: confirmed product requirements and existing release matrix.
- Produces: `PL-*`, `CO-*`, `IPO-*`, `EX-*`, `BC-*`, `GV-*` and `SF-*` IDs for evidence.

- [ ] **Step 1: Verify all unimplemented governance IDs are explicit.**

Run: `rg -n "GV-0[1-4].*未实现" docs/operations/blockstock-requirements-traceability.md`

Expected: four governance requirements appear, preventing a false formal-release claim.

- [ ] **Step 2: Commit the baseline.**

Run: `git add -- docs/operations/blockstock-requirements-traceability.md docs/superpowers/plans/2026-08-28-requirements-driven-release-validation.md; git commit -m "docs: trace confirmed BlockStock requirements"`

Expected: one documentation-only commit.

### Task 2: Cover all currently implemented company, IPO and exchange P0 flows

**Files:**
- Modify: `qa-server/mcp/founder-gui-e2e.mjs`
- Modify: `qa-server/mcp/multirole-market-e2e.mjs`
- Create: `qa-server/mcp/ipo-lifecycle-e2e.mjs`
- Modify: `docs/operations/blockstock-formal-release-test-matrix.md`

**Interfaces:**
- Consumes: native Paper GUI, RCON-only QA grants, read-only `blockeco.db` assertions.
- Produces: explicit `*_E2E_PASS` evidence for company creation, asset ownership, IPO success/refund, cash, order, cancellation and two-player matching.

- [ ] **Step 1: Add one next uncovered P0 Bot assertion and run it to show the exact gap.**

```javascript
await waitDb('duplicate company confirmation', db =>
  one(db, 'SELECT COUNT(*) FROM companies WHERE founder_uuid=?', founderId) === before + 1)
```

Run: `node .\founder-gui-e2e.mjs`

Expected: either a named `*_PASS` stage or one precise failing requirement ID.

- [ ] **Step 2: If it is a product defect, write a focused JUnit red test before changing Java code.**

```java
@Test void duplicateConfirmationCreatesExactlyOneCompany() { /* real service fixture */ }
```

Run: `gradlew test --tests <FocusedTest> --no-daemon --console=plain`

Expected: failure demonstrates the missing behavior, not a fixture error.

- [ ] **Step 3: Apply the smallest root-cause fix and make the focused test green.**

Run: `gradlew test --tests <FocusedTest> --no-daemon --console=plain`

Expected: PASS.

- [ ] **Step 4: Run two-investor IPO success, sold-out and refund cases.**

Run: `node .\ipo-lifecycle-e2e.mjs`

Expected: `IPO_LIFECYCLE_E2E_PASS` with share allocation, company cash, refunds and listing code read from SQLite.

- [ ] **Step 5: Commit each independently passing vertical slice.**

Run: `git add -- src/main/java src/test/java qa-server/mcp docs/operations/blockstock-formal-release-test-matrix.md; git commit -m "test: verify company ipo exchange flows"`

Expected: staged paths are inspected first and exclude unrelated user changes.

### Task 3: Validate market safety, restart recovery and GUI behavior

**Files:**
- Create: `qa-server/mcp/security-recovery-e2e.mjs`
- Modify: `src/test/java/cn/blockeco/exchange/application/SecondaryMarketRecoveryServiceTest.java`
- Modify: `docs/operations/blockstock-formal-release-test-matrix.md`

**Interfaces:**
- Consumes: `SecondaryMarketService`, `SecondaryMarketRecoveryService`, cash accounts, share holdings, orders and trades.
- Produces: proof that cancel, partial fill, duplicate request and restart preserve non-negative balances and reservations.

- [ ] **Step 1: Write the next recovery invariant as a focused red test.**

```java
@Test void restartedPartiallyFilledSellKeepsOnlyRemainingSharesReserved() { /* real fixture */ }
```

Run: `gradlew test --tests <RecoveryTest> --no-daemon --console=plain`

Expected: failure identifies reservation or recovery mismatch.

- [ ] **Step 2: Implement the single root-cause correction and verify focused green.**

Run: `gradlew test --tests <RecoveryTest> --no-daemon --console=plain`

Expected: PASS.

- [ ] **Step 3: Run the multi-player restart scenario.**

Run: `node .\security-recovery-e2e.mjs`

Expected: `SECURITY_RECOVERY_E2E_PASS`; no duplicate trade, negative amount or unreleased reservation.

- [ ] **Step 4: Commit the safety slice.**

Run: `git add -- src/main/java src/test/java qa-server/mcp/security-recovery-e2e.mjs docs/operations/blockstock-formal-release-test-matrix.md; git commit -m "test: verify market recovery invariants"`

Expected: the release matrix records actual runner output.

### Task 4: Validate optional adapter boundary and release gate

**Files:**
- Create: `qa-server/mcp/compatibility-paper-e2e.ps1`
- Modify: `docs/operations/optional-asset-adapters.md`
- Modify: `docs/operations/blockstock-formal-release-test-matrix.md`

**Interfaces:**
- Consumes: Paper plugin discovery and `OptionalAssetAdapterLoader`.
- Produces: versioned outcomes for no optional adapter and every locally installed adapter JAR.

- [ ] **Step 1: Run the no-optional-plugin Paper startup and native asset flow.**

Run: `.\compatibility-paper-e2e.ps1 -Mode NoOptionalAdapters`

Expected: `BlockStock ready` appears and native binding remains available.

- [ ] **Step 2: Run an installed-adapter scenario for each locally available JAR.**

Run: `.\compatibility-paper-e2e.ps1 -Mode InstalledAdapters`

Expected: each adapter has either a positive versioned result or an explicit `外部兼容待验` reason.

- [ ] **Step 3: Execute the final build gate and update factual statuses.**

Run: `$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Temp'; .\gradlew.bat test shadowJar --rerun-tasks --no-daemon --console=plain`

Expected: zero test failures and a fresh shaded JAR; governance IDs remain blockers unless actually implemented.

## Self-Review

- **Spec coverage:** PL-01..04, CO-01..05, IPO-01..05, EX-01..05, BC-01..05, GV-01..04 and SF-01..03 appear in the traceability file and have an execution disposition.
- **Placeholder scan:** All tasks name exact files, commands and expected behavior; the governance gap is explicit rather than silently skipped.
- **Type consistency:** Java tests name existing services; bots use existing Paper GUI and read-only SQLite only.

## Execution Handoff

The user explicitly requested continued execution. Execute inline task-by-task, reviewing evidence after every reproduced defect and release-gate run.
