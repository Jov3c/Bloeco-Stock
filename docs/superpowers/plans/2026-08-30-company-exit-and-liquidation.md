# Company Exit and Liquidation Implementation Plan

> **For agentic workers:** Implement each task with tests before production code. Work in the shared worktree: preserve unrelated dirty changes and stage only files owned by the task.

**Goal:** Deliver Chinese native-GUI company buyback, founder cash-out, voluntary/forced delisting, recoverable liquidation and bounded compensation-fund support without mixing company cash, securities cash, and Vault wallets.

**Architecture:** Persist every governance request and every external-payment attempt as an idempotent state machine. Delisting first freezes entry and releases open-order reservations in batches; it then snapshots ownership and credits liquidation proceeds to securities accounts internally. Company cash is spent first and the compensation fund only contributes its recorded, non-negative balance.

**Spec:** `docs/superpowers/specs/2026-08-30-company-exit-and-liquidation-design.md`

## Global invariants

- Player companies only; bluechips never enter player-company governance or liquidation.
- A 12-hour announcement is required before buyback, founder cash-out or voluntary delisting can execute.
- Company cash, securities cash and personal Vault balances remain distinct. No internal accounting row may be fabricated from a wallet balance.
- An external Vault payment is never retried automatically after an unknown result. It remains recoverable through an administrator-only action with the original correlation key.
- Delisting admits no new orders or subscriptions, releases all existing reservations exactly once, and only then creates the immutable holder snapshot.
- Fund assistance is bounded by the recorded compensation-fund balance, company shortfall and holder entitlement; it cannot make the fund negative.

## Task 1: Schema and repositories for governance exits and recoverable payouts

**Files:**
- Modify: `src/main/java/cn/blockeco/exchange/infrastructure/sql/Database.java`
- Create: `src/main/resources/db/migration/V017.sql`
- Create: exit/payout domain records and enums under `src/main/java/cn/blockeco/exchange/domain/`
- Create/modify: SQL repository interfaces and implementations under `ports` and `infrastructure/sql`
- Test: SQL migration/repository tests

**Steps:**
1. Add failing tests for immutable announcement data, expected-state transitions, correlation-key uniqueness and payout recovery rows.
2. Add `company_governance_actions`, `company_payout_operations`, `company_exit_snapshots`, `company_liquidation_claims` and an order-release progress table. Use foreign keys, checks and unique correlation keys.
3. Expose conditional transitions and paged open-order retrieval/release helpers. Persist structured audit/announcement rows together with each action.
4. Run focused SQL tests and commit `feat: persist company exit governance`.

## Task 2: Buyback and founder cash-out application services

**Files:**
- Create: `src/main/java/cn/blockeco/exchange/application/CompanyCapitalActionService.java`
- Create: payout recovery service
- Modify: company-cash/ledger repositories only where required
- Test: application tests

**Steps:**
1. Write failing tests covering: founder authorization; 12-hour gate; protected company cash; shareholder-voluntary buyback; double-submit idempotency; successful Vault payout; failed/unknown payout recovery.
2. Implement proposal/announcement creation and due-action execution with expected state conditions.
3. For founder cash-out: reserve company cash and persist a payout operation before calling Vault. On success append ledger/audit/announcement; on failure or timeout keep a recoverable operation and do not issue a second payment.
4. For buyback: reserve company cash and accept only voluntary shareholder sales at the advertised price; reduce circulating holder shares without taking arbitrary holdings.
5. Run focused tests and commit `feat: add governed company capital actions`.

## Task 3: Delisting freeze, cancellation and deterministic liquidation service

**Files:**
- Create: `src/main/java/cn/blockeco/exchange/application/CompanyExitService.java`
- Modify: market/order repositories for safe batch cancellation
- Test: application and repository tests

**Steps:**
1. Write failing tests for voluntary announcement gating, admin forced-delisting authorization, no new trading during DELISTING, batch reservation release, immutable holder snapshots and retry safety.
2. Implement `LISTED -> DELISTING -> LIQUIDATING -> DELISTED` with expected-state updates. Block new order/subscription paths for the intermediate states.
3. Cancel open orders in bounded batches and use existing order cancellation/reservation logic, never direct balance edits. Progress is persisted so restart/retry is safe.
4. Snapshot `available + reserved` shares only after releases complete. Calculate claims deterministically by UUID, retaining indivisible minor-unit remainder in company cash.
5. Internally credit each claim to the holder’s securities account and debit company cash/ledger atomically; persist claim state before/after every operation.
6. Run focused tests and commit `feat: liquidate delisted player companies`.

## Task 4: Bounded compensation fund and operational recovery

**Files:**
- Modify: compensation-fund repository/service
- Modify: `CompanyExitService`
- Test: compensation and reconciliation tests

**Steps:**
1. Add failing tests proving company cash is consumed first, fund use is capped, no fund balance can become negative, and a repeated run cannot pay twice.
2. Add a transactional fund contribution method restricted to liquidation claims; append `COMPENSATION_FUND` ledger and liquidation audit rows.
3. Reconcile: sum holder credits equals company debit plus fund debit; no negative cash, securities balance or fund balance.
4. Run focused tests and commit `feat: fund bounded delisting compensation`.

## Task 5: Chinese GUI, wiring and real QA acceptance

**Files:**
- Create/modify the native Paper GUI controllers for company centre, market and administrator recovery
- Modify: plugin lifecycle wiring/configuration
- Create: isolated multi-role Mineflayer exit-flow test
- Modify: requirements traceability and formal-release test matrix

**Steps:**
1. Add GUI tests for clear Chinese rules, 12-hour countdown, confirmation pages, back navigation, disabled states, and administrator-only recovery.
2. Wire company/market pages: founders propose; shareholders voluntarily accept buyback; all holders can see delisting/liquidation status; admins can force delist or resolve a payout operation. All confirmation actions close/refresh once and reject duplicate clicks.
3. Run full Gradle tests plus `shadowJar`, deploy only to isolated `qa-server` port 25566, and execute real bots for founder, shareholder and administrator roles. Use fresh QA data per suite or an explicit fixture reset to avoid cross-suite pollution.
4. Record only observed results; do not call the release formal until this acceptance suite is green. Commit `feat: expose company exit and liquidation gui`.

## Plan review

- The sequence creates persistence and recovery semantics before any money-moving UI.
- Every transition has a conditional/idempotent test and every monetary path has a reconciliation assertion.
- QA is explicitly isolated and repeatable, with no claim of acceptance before real original-client protocol tests pass.
