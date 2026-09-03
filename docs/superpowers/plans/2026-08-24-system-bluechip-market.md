# System Bluechip Market Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add ten server-operated bluechip companies with finite liquidity-fund market making, event-driven mean-reverting prices, 08:00–20:00 trading, and a 15-day dividend cycle.

**Architecture:** Keep bluechips in the existing company/listing/order/holding tables so they use the same matching and ledger path as players. Add bluechip metadata, a dedicated system UUID cash/holding account, market-session policy, persisted events/candles/dividend runs, and schedulers that create/cancel only system orders; player orders remain normal GTC orders.

**Tech Stack:** Java 21, Paper 1.21.4, SQLite migrations, Vault economy, JUnit 5, existing Paper inventory GUI, Mineflayer QA harness.

**Spec:** `docs/superpowers/specs/2026-08-24-system-bluechip-market-design.md`

## Global Constraints

- Support unmodified vanilla Minecraft clients; all user interaction remains Paper inventory GUI or existing commands.
- Use server `market.time-zone`; trading opens at 08:00 and closes at 20:00 every day.
- Player orders may be entered/cancelled outside market hours but must not match until the next opening.
- Create exactly the ten configured fictional companies idempotently; never overwrite player companies or their records.
- Bluechip liquidity is finite: no Vault payout or un-audited currency creation is permitted.
- All companies use a 15-day dividend cadence; non-positive profit produces a no-dividend report.
- Use integer shares and `Money` minor units; all state-changing operations are transactional and audited.
- Do not use real-world company names, trademarks, logos, or fabricated real news.

---

## File Structure

- `src/main/resources/db/migration/V009.sql`: bluechip, event, candle, dividend-run and industry persistence.
- `src/main/resources/config.yml`: timezone, session, bluechip defaults and the ten fictional company definitions.
- `src/main/java/.../domain/market/MarketSession.java`: immutable open/closed decision in a `ZoneId`.
- `src/main/java/.../domain/bluechip/*.java`: immutable bluechip definition, event and fund value records.
- `src/main/java/.../ports/BluechipRepository.java`: transactional persistence contract for seed, fund, events, candles and system orders.
- `src/main/java/.../infrastructure/sql/SqlBluechipRepository.java`: SQLite implementation and audit writes.
- `src/main/java/.../application/BluechipBootstrapService.java`: idempotent creation of companies, listings, system holdings and fund cash.
- `src/main/java/.../application/MarketSessionService.java`: order admission and opening catch-up matching.
- `src/main/java/.../application/BluechipMarketMakerService.java`: finite five-level system quotes and protection-mode changes.
- `src/main/java/.../application/MarketEventService.java`: persisted random single-company, industry and market event selection.
- `src/main/java/.../application/DividendCycleService.java`: 15-day idempotent report/dividend settlement.
- `src/main/java/.../application/BluechipSchedulers.java`: Paper repeating jobs for session transitions, quotes, events and dividend checks.
- `src/main/java/.../paper/BluechipAdminCommand.java`: protected initialization, pause, fund and event administration.
- `src/main/java/.../paper/StockGuiController.java` and `StockGuiItemFactory.java`: market-news/listing-detail rendering.
- `src/main/java/.../application/SecondaryMarketService.java` and `ports/SecondaryTradingRepository.java`: session-aware deferred matching plus opening batch matching.
- Tests live alongside their production packages; `qa-server/mcp/bluechip-gui-e2e.mjs` is the real Paper GUI verification.

## Task 1: Persist and validate bluechip configuration

**Files:**
- Create: `src/main/resources/db/migration/V009.sql`
- Modify: `src/main/resources/config.yml`
- Create: `src/main/java/cn/blockeco/exchange/domain/bluechip/BluechipDefinition.java`
- Create: `src/main/java/cn/blockeco/exchange/domain/bluechip/BluechipEvent.java`
- Create: `src/main/java/cn/blockeco/exchange/domain/market/MarketSession.java`
- Create: `src/main/java/cn/blockeco/exchange/paper/BluechipConfig.java`
- Test: `src/test/java/cn/blockeco/exchange/domain/market/MarketSessionTest.java`
- Test: `src/test/java/cn/blockeco/exchange/paper/BluechipConfigTest.java`
- Test: `src/test/java/cn/blockeco/exchange/infrastructure/sql/MigrationTest.java`

**Interfaces:**
- Produces `MarketSession.at(Instant now, ZoneId zone): MarketSession` and `boolean acceptsMatching()`.
- Produces `BluechipConfig.load(FileConfiguration config, int scale): BluechipConfig` with exactly ten `BluechipDefinition` values.
- Later tasks consume `BluechipDefinition(code, displayName, industry, referencePrice, lowerBound, upperBound, totalShares, initialFundCash, initialFundShares, spreadBps, eventSensitivityBps, dividendPayoutBps)`.

- [ ] **Step 1: Write failing session and configuration tests**

```java
@Test void sessionIsOpenAtEightAndClosedAtTwentyInConfiguredZone() {
    ZoneId zone = ZoneId.of("Asia/Shanghai");
    assertTrue(MarketSession.at(Instant.parse("2026-08-24T00:00:00Z"), zone).acceptsMatching());
    assertFalse(MarketSession.at(Instant.parse("2026-08-24T12:00:00Z"), zone).acceptsMatching());
}

@Test void rejectsNonFictionalOrNonTenBluechipConfiguration() {
    assertThrows(IllegalArgumentException.class, () -> BluechipConfig.load(configWithNineEntries(), 2));
}
```

- [ ] **Step 2: Run the new tests to verify they fail**

Run: `./gradlew.bat test --tests '*MarketSessionTest' --tests '*BluechipConfigTest' --no-daemon --console=plain`

Expected: compilation failure because the new types do not yet exist.

- [ ] **Step 3: Add V009 schema and value objects**

```sql
CREATE TABLE bluechip_companies (
  company_id TEXT PRIMARY KEY REFERENCES companies(id), industry TEXT NOT NULL,
  system_account_uuid TEXT NOT NULL, lower_price_minor INTEGER NOT NULL,
  upper_price_minor INTEGER NOT NULL, model_price_minor INTEGER NOT NULL,
  spread_bps INTEGER NOT NULL, event_sensitivity_bps INTEGER NOT NULL,
  payout_bps INTEGER NOT NULL, quotes_paused INTEGER NOT NULL DEFAULT 0,
  next_event_at TEXT NOT NULL, next_dividend_at TEXT NOT NULL
);
CREATE TABLE bluechip_events (
  id TEXT PRIMARY KEY, scope TEXT NOT NULL, company_id TEXT NULL,
  industry TEXT NULL, headline TEXT NOT NULL, body TEXT NOT NULL,
  price_impact_bps INTEGER NOT NULL, profit_impact_bps INTEGER NOT NULL,
  starts_at TEXT NOT NULL, ends_at TEXT NOT NULL, state TEXT NOT NULL
);
CREATE TABLE market_candles (
  company_id TEXT NOT NULL, trading_day TEXT NOT NULL, open_minor INTEGER NOT NULL,
  high_minor INTEGER NOT NULL, low_minor INTEGER NOT NULL, close_minor INTEGER NOT NULL,
  volume_shares INTEGER NOT NULL, PRIMARY KEY(company_id, trading_day)
);
```

Add `company_industry` and `bluechip_fund_audit` tables in the same migration. Add all ten names, codes, industries, bands and fund defaults to `config.yml`; validate positive bounds, lower `<` reference `<` upper, valid code format, unique names/codes, and exactly ten entries.

- [ ] **Step 4: Run focused tests to verify they pass**

Run: `./gradlew.bat test --tests '*MarketSessionTest' --tests '*BluechipConfigTest' --tests '*MigrationTest' --no-daemon --console=plain`

Expected: PASS; migration asserts all V009 tables and constraints exist.

- [ ] **Step 5: Commit the isolated deliverable**

```powershell
git add src/main/resources/db/migration/V009.sql src/main/resources/config.yml src/main/java/cn/blockeco/exchange/domain src/main/java/cn/blockeco/exchange/paper/BluechipConfig.java src/test/java
git commit -m "feat: add bluechip market configuration"
```

## Task 2: Bootstrap ten system companies and finite fund accounts

**Files:**
- Create: `src/main/java/cn/blockeco/exchange/ports/BluechipRepository.java`
- Create: `src/main/java/cn/blockeco/exchange/infrastructure/sql/SqlBluechipRepository.java`
- Create: `src/main/java/cn/blockeco/exchange/application/BluechipBootstrapService.java`
- Modify: `src/main/java/cn/blockeco/exchange/BlockecoPlugin.java`
- Test: `src/test/java/cn/blockeco/exchange/application/BluechipBootstrapServiceTest.java`
- Test: `src/test/java/cn/blockeco/exchange/infrastructure/sql/SqlBluechipRepositoryTest.java`

**Interfaces:**
- Consumes `BluechipConfig`, `CompanyRepository`, `StockListingRepository`, `TransactionRunner` and `AppClock`.
- Produces `CompletionStage<BluechipBootstrapResult> initializeMissing()` and `BluechipRepository.findByStockCode(String)`.

- [ ] **Step 1: Write failing bootstrap tests**

```java
@Test void initializesExactlyTenListingsWithFiniteFundCashAndInventory() {
    BluechipBootstrapResult result = service.initializeMissing().toCompletableFuture().join();
    assertEquals(10, result.createdCompanies());
    assertEquals(10, repository.all().size());
    assertEquals(config.definitions().getFirst().initialFundShares(), repository.fundShares(firstCompany));
}

@Test void secondInitializationCreatesNothingAndDoesNotTouchPlayerCompany() {
    service.initializeMissing().toCompletableFuture().join();
    assertEquals(0, service.initializeMissing().toCompletableFuture().join().createdCompanies());
    assertEquals("玩家矿业", playerCompany.displayName());
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew.bat test --tests '*BluechipBootstrapServiceTest' --tests '*SqlBluechipRepositoryTest' --no-daemon --console=plain`

Expected: FAIL because bootstrap service/repository are absent.

- [ ] **Step 3: Implement atomic, idempotent bootstrap**

Use one fixed non-player UUID from config for all system fund holdings/cash and one deterministic UUID per configured stock code for the company. In a single transaction per missing definition: insert `Company` as `LISTED`, insert `stock_listings`, insert system `share_holdings`, create the system `securities_cash_accounts` balance from configured fund cash, insert `bluechip_companies`, and append `BLUECHIP_INITIALIZED` audit/fund-audit records. Reject any collision where an existing company or listing is not the matching system record.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew.bat test --tests '*BluechipBootstrapServiceTest' --tests '*SqlBluechipRepositoryTest' --no-daemon --console=plain`

Expected: PASS with repeated initialization preserving all balances and player records.

- [ ] **Step 5: Commit the isolated deliverable**

```powershell
git add src/main/java/cn/blockeco/exchange/{application,ports,infrastructure/sql}/ src/main/java/cn/blockeco/exchange/BlockecoPlugin.java src/test/java
git commit -m "feat: bootstrap system bluechip companies"
```

## Task 3: Enforce trading sessions while preserving GTC priority

**Files:**
- Modify: `src/main/java/cn/blockeco/exchange/application/SecondaryMarketService.java`
- Modify: `src/main/java/cn/blockeco/exchange/ports/SecondaryTradingRepository.java`
- Modify: `src/main/java/cn/blockeco/exchange/infrastructure/sql/SqlSecondaryTradingRepository.java`
- Create: `src/main/java/cn/blockeco/exchange/application/MarketSessionService.java`
- Test: `src/test/java/cn/blockeco/exchange/application/SecondaryMarketServiceTest.java`
- Test: `src/test/java/cn/blockeco/exchange/application/MarketSessionServiceTest.java`

**Interfaces:**
- Consumes `Supplier<MarketSession>`.
- Produces `SecondaryMarketService.matchQueuedOrders(): CompletionStage<Integer>` and `MarketSessionService.onSessionTransition(): CompletionStage<Integer>`.

- [ ] **Step 1: Write failing behavior tests**

```java
@Test void closedSessionReservesOrderButDoesNotMatchUntilOpening() {
    serviceWithClosedSession.placeBuy(buyer, "RDT", 10, money("100")).toCompletableFuture().join();
    assertEquals(0, repository.trades().size());
    serviceWithOpenSession.matchQueuedOrders().toCompletableFuture().join();
    assertEquals(1, repository.trades().size());
}

@Test void openingMatchesExistingOrdersByPriceThenAcceptedTime() { /* assert earliest equal-price sell fills first */ }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew.bat test --tests '*SecondaryMarketServiceTest' --tests '*MarketSessionServiceTest' --no-daemon --console=plain`

Expected: FAIL because placement always matches immediately and no queued match API exists.

- [ ] **Step 3: Implement session-aware matching**

Reserve valid player orders at all times. Only enter the matching loop when `session.acceptsMatching()` is true. Add a repository query that returns open/partially-filled orders in deterministic `priority_sequence` order, then feed each into the existing settlement loop on the first open tick. Keep cancellation available regardless of session. Persist the last observed session date/state so a restart at 08:00 does one opening catch-up rather than duplicate it.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew.bat test --tests '*SecondaryMarketServiceTest' --tests '*MarketSessionServiceTest' --no-daemon --console=plain`

Expected: PASS; closed-session orders reserve funds/shares, never settle until opening, and retain priority.

- [ ] **Step 5: Commit the isolated deliverable**

```powershell
git add src/main/java/cn/blockeco/exchange/application src/main/java/cn/blockeco/exchange/ports src/main/java/cn/blockeco/exchange/infrastructure/sql src/test/java
git commit -m "feat: enforce stock trading sessions"
```

## Task 4: Quote bluechips through the existing order book

**Files:**
- Create: `src/main/java/cn/blockeco/exchange/application/BluechipMarketMakerService.java`
- Modify: `src/main/java/cn/blockeco/exchange/ports/BluechipRepository.java`
- Modify: `src/main/java/cn/blockeco/exchange/ports/SecondaryTradingRepository.java`
- Modify: `src/main/java/cn/blockeco/exchange/infrastructure/sql/SqlBluechipRepository.java`
- Modify: `src/main/java/cn/blockeco/exchange/infrastructure/sql/SqlSecondaryTradingRepository.java`
- Test: `src/test/java/cn/blockeco/exchange/application/BluechipMarketMakerServiceTest.java`

**Interfaces:**
- Consumes `BluechipRepository`, `SecondaryMarketService`, `MarketSession`, `AppClock`.
- Produces `CompletionStage<QuoteRefreshResult> refreshQuotes()` and `CompletionStage<Integer> cancelSystemQuotesAtClose()`.

- [ ] **Step 1: Write failing market-maker tests**

```java
@Test void openSessionCreatesAtMostFiveBidAndFiveAskOrdersPerBluechip() {
    QuoteRefreshResult result = maker.refreshQuotes().toCompletableFuture().join();
    assertEquals(5, repository.openSystemBids("RDT").size());
    assertEquals(5, repository.openSystemAsks("RDT").size());
}

@Test void emptyFundCashRemovesBidsWithoutCreatingMoney() {
    repository.setFundCash("RDT", Money.zero());
    maker.refreshQuotes().toCompletableFuture().join();
    assertTrue(repository.openSystemBids("RDT").isEmpty());
    assertEquals(Money.zero(), repository.fundCash("RDT"));
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew.bat test --tests '*BluechipMarketMakerServiceTest' --no-daemon --console=plain`

Expected: FAIL because no market maker exists.

- [ ] **Step 3: Implement finite quotes and protection mode**

At each refresh, cancel only the current system UUID's remaining quote orders for a bluechip, calculate a bounded five-level bid/ask ladder around `model_price_minor`, and use the existing secondary service to place orders. Quote quantity cannot exceed system available cash including fees for bids or available holdings for asks. If either side cannot fund a level, omit it; record `BLUECHIP_LIQUIDITY_DEGRADED` once per state change and surface a status flag. At close cancel system orders only; never cancel player orders.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew.bat test --tests '*BluechipMarketMakerServiceTest' --no-daemon --console=plain`

Expected: PASS; quotes are finite, player orders survive refresh/close, and low-fund protection is auditable.

- [ ] **Step 5: Commit the isolated deliverable**

```powershell
git add src/main/java/cn/blockeco/exchange/application/BluechipMarketMakerService.java src/main/java/cn/blockeco/exchange/{ports,infrastructure/sql} src/test/java
git commit -m "feat: add finite bluechip market making"
```

## Task 5: Persist events, price model, industry effects and candles

**Files:**
- Create: `src/main/java/cn/blockeco/exchange/application/MarketEventService.java`
- Create: `src/main/java/cn/blockeco/exchange/application/MarketCandleService.java`
- Modify: `src/main/java/cn/blockeco/exchange/ports/BluechipRepository.java`
- Modify: `src/main/java/cn/blockeco/exchange/infrastructure/sql/SqlBluechipRepository.java`
- Modify: `src/main/java/cn/blockeco/exchange/application/PublicStockQueryService.java`
- Test: `src/test/java/cn/blockeco/exchange/application/MarketEventServiceTest.java`
- Test: `src/test/java/cn/blockeco/exchange/application/MarketCandleServiceTest.java`

**Interfaces:**
- Produces `MarketEventService.triggerDueEvents(): CompletionStage<List<BluechipEvent>>`, `triggerTestEvent(...)`, and `MarketCandleService.closeTradingDay(LocalDate)`.
- Query output adds `List<MarketNewsItem> recentNews(int limit)` and per-stock event/liquidity fields.

- [ ] **Step 1: Write failing event and candle tests**

```java
@Test void singleCompanyEventMovesModelThenDecaysTowardConfiguredCenter() {
    eventService.triggerTestEvent("RDT", 800).toCompletableFuture().join();
    assertTrue(repository.modelPrice("RDT").minorUnits() > repository.referencePrice("RDT").minorUnits());
    advanceClock(Duration.ofDays(3));
    eventService.applyDecay().toCompletableFuture().join();
    assertTrue(repository.modelPrice("RDT").minorUnits() < firstMovedPrice.minorUnits());
}

@Test void industryEventOnlyChangesBluechipsAndPlayerCompaniesWithSameIndustry() { /* assert unrelated industry unchanged */ }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew.bat test --tests '*MarketEventServiceTest' --tests '*MarketCandleServiceTest' --no-daemon --console=plain`

Expected: FAIL because no event/candle services or query types exist.

- [ ] **Step 3: Implement deterministic persisted event processing**

Use injected `Random`/seeded test RNG. Select one due company event every configured 6–12 hours and one market/industry event every configured 1–3 days. Store headline, game-safe body, impact, start/end, and state before model changes; write company announcements and audit events in the same transaction. Clamp models inside each configured price band and apply a fixed decay step toward reference price. On close, upsert OHLCV from actual trades or the current model value with zero volume.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew.bat test --tests '*MarketEventServiceTest' --tests '*MarketCandleServiceTest' --no-daemon --console=plain`

Expected: PASS; events survive reload, respect industry scope, have bounded impact/decay, and candles are idempotent per company/day.

- [ ] **Step 5: Commit the isolated deliverable**

```powershell
git add src/main/java/cn/blockeco/exchange/{application,ports,infrastructure/sql} src/test/java
git commit -m "feat: add bluechip events and market candles"
```

## Task 6: Settle the shared fifteen-day dividend cycle

**Files:**
- Create: `src/main/java/cn/blockeco/exchange/application/DividendCycleService.java`
- Modify: `src/main/java/cn/blockeco/exchange/ports/BluechipRepository.java`
- Modify: `src/main/java/cn/blockeco/exchange/infrastructure/sql/SqlBluechipRepository.java`
- Modify: `src/main/resources/config.yml`
- Test: `src/test/java/cn/blockeco/exchange/application/DividendCycleServiceTest.java`

**Interfaces:**
- Produces `CompletionStage<DividendRunResult> settleDueRuns()` with company ID, profit, distributable amount, payment count and idempotency key.

- [ ] **Step 1: Write failing settlement tests**

```java
@Test void profitableBluechipCreditsHoldersExactlyOnceAfterFifteenDays() {
    advanceClock(Duration.ofDays(15));
    DividendRunResult first = service.settleDueRuns().toCompletableFuture().join().getFirst();
    assertTrue(first.distributed().isPositive());
    assertEquals(first, service.settleDueRuns().toCompletableFuture().join().getFirst());
}

@Test void lossPublishesReportAndDoesNotCreditDividend() { /* assert zero security-account credit and NO_DIVIDEND announcement */ }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew.bat test --tests '*DividendCycleServiceTest' --no-daemon --console=plain`

Expected: FAIL because dividend cycle service is absent.

- [ ] **Step 3: Implement a single persisted cycle for both company kinds**

Derive bluechip profit from base simulation plus active event profit impacts. Resolve player company profit through the existing finance/asset path. Insert one `dividend_runs(company_id, cycle_started_at)` row before crediting any `share_holdings`; the unique key is the retry barrier. Credit eligible holders' securities cash in the same transaction, debit only the company/fund distributable amount, write `DIVIDEND_PAID` audit entries and company announcements. For profit `<= 0`, insert the completed run with zero distribution and write a clear no-dividend report.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew.bat test --tests '*DividendCycleServiceTest' --no-daemon --console=plain`

Expected: PASS; no duplicate credits on retry/restart and zero/negative profit never pays.

- [ ] **Step 5: Commit the isolated deliverable**

```powershell
git add src/main/java/cn/blockeco/exchange/application/DividendCycleService.java src/main/java/cn/blockeco/exchange/{ports,infrastructure/sql} src/main/resources/config.yml src/test/java
git commit -m "feat: settle fifteen day stock dividends"
```

## Task 7: Wire scheduling, GUI market news and protected admin controls

**Files:**
- Create: `src/main/java/cn/blockeco/exchange/application/BluechipSchedulers.java`
- Create: `src/main/java/cn/blockeco/exchange/paper/BluechipAdminCommand.java`
- Modify: `src/main/java/cn/blockeco/exchange/BlockecoPlugin.java`
- Modify: `src/main/java/cn/blockeco/exchange/paper/StockGuiController.java`
- Modify: `src/main/java/cn/blockeco/exchange/paper/StockGuiItemFactory.java`
- Modify: `src/main/resources/plugin.yml`
- Modify: `src/main/resources/config.yml`
- Test: `src/test/java/cn/blockeco/exchange/application/BluechipSchedulersTest.java`
- Test: `src/test/java/cn/blockeco/exchange/paper/BluechipAdminCommandTest.java`
- Test: `src/test/java/cn/blockeco/exchange/paper/StockGuiControllerTest.java`

**Interfaces:**
- `BluechipSchedulers.start()` installs async jobs and `stop()` cancels all jobs.
- `/stockadmin bluechip init|pause|resume|fund <code> <cash|shares> <value>|event <code|market|industry> <impact>` requires `blockeco.admin.bluechip`.

- [ ] **Step 1: Write failing wiring/UI tests**

```java
@Test void stockHomeShowsFiveMostRecentMarketNewsItems() {
    controller.openHome(player);
    assertEquals("市场快讯", renderedInventory.getItem(33).getItemMeta().displayName().content());
}

@Test void bluechipAdminRejectsPlayerWithoutPermission() {
    assertFalse(command.onCommand(playerWithoutPermission, command, "stockadmin", new String[]{"bluechip", "init"}));
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew.bat test --tests '*BluechipSchedulersTest' --tests '*BluechipAdminCommandTest' --tests '*StockGuiControllerTest' --no-daemon --console=plain`

Expected: FAIL because scheduler/admin/news action are absent.

- [ ] **Step 3: Implement production wiring**

After existing recovery has completed, initialize missing bluechips once, refresh symbols, then start schedulers. Run session checks at least once per minute, quote refresh only during open hours, event checks on their persisted due times, close candles after 20:00, and dividend checks daily. Stop all jobs before database shutdown. Add a GUI news page containing five newest event/dividend/system-liquidity announcements, a bluechip badge/industry/current event/liquidity state to listing detail, and a back button. Add admin subcommands with exact Chinese usage/errors and tab completion; fund adjustments write an audit record and cannot make a balance negative.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew.bat test --tests '*BluechipSchedulersTest' --tests '*BluechipAdminCommandTest' --tests '*StockGuiControllerTest' --no-daemon --console=plain`

Expected: PASS; only authorized administrators can mutate bluechip state and all GUI paths remain vanilla inventory views.

- [ ] **Step 5: Commit the isolated deliverable**

```powershell
git add src/main/java/cn/blockeco/exchange src/main/java/cn/blockeco/exchange/paper src/main/resources src/test/java
git commit -m "feat: expose bluechip market controls and news"
```

## Task 8: Run full regression and real Paper QA acceptance

**Files:**
- Create: `qa-server/mcp/bluechip-gui-e2e.mjs`
- Modify: `qa-server/server.properties` only if a QA-only test port/timezone needs change
- Test: all existing `src/test/java` tests

**Interfaces:**
- Uses the local-only, offline QA instance at `127.0.0.1:25566`; it must never modify the live `25565` database or config.

- [ ] **Step 1: Write the failing QA assertions before final production changes**

```js
await rcon('stockadmin bluechip init')
await assertMarketHasExactlyTenBluechips(bot)
await setQaClockTo('07:59')
await assertQueuedOrderDoesNotTrade(bot)
await setQaClockTo('08:00')
await assertQueuedOrderTrades(bot)
await assertNewsShowsEvent(bot, '大盘')
await assertDividendIsCreditedOnceAfterFifteenDays(bot)
```

- [ ] **Step 2: Run QA script and confirm it fails before missing final behavior is implemented**

Run: `node qa-server/mcp/bluechip-gui-e2e.mjs`

Expected: FAIL until all system bluechip wiring is complete.

- [ ] **Step 3: Execute the complete automated verification suite**

Run: `./gradlew.bat test shadowJar --no-build-cache --no-daemon --console=plain`

Expected: BUILD SUCCESSFUL with all unit, SQL migration, application and Paper controller tests passing.

- [ ] **Step 4: Deploy only to QA and run two-bot acceptance**

Copy the new shadow JAR to `qa-server/plugins/BlockStock.jar`, start the QA instance, run `bluechip-gui-e2e.mjs`, then inspect `qa-server/plugins/BlockStock/blockeco.db` while QA is stopped. Verify exactly ten `bluechip_companies`, player/system holdings sum to issued shares, no negative fund/holdings/cash, event/candle/dividend rows exist once, and system order cancellation leaves player GTC orders intact.

- [ ] **Step 5: Commit QA harness and implementation verification changes**

```powershell
git add qa-server/mcp/bluechip-gui-e2e.mjs
git commit -m "test: verify bluechip market end to end"
```

## Plan Self-Review

- Spec coverage: Tasks 1–2 cover ten companies, fictitious naming, configuration, funds and idempotency; Task 3 covers 08:00–20:00 and queued orders; Task 4 covers finite five-level liquidity; Task 5 covers company/industry/market events, bounds, decay, candles and news query; Task 6 covers unified 15-day dividends; Task 7 covers GUI, permissions, scheduling and admin controls; Task 8 covers Paper and ledger acceptance.
- Placeholder scan: no deferred implementation markers or unspecified error handling are present; each task specifies concrete persistence, service interfaces, tests, commands and verification commands.
- Type consistency: `BluechipDefinition`, `BluechipRepository`, `MarketSession`, `BluechipBootstrapService`, `BluechipMarketMakerService`, `MarketEventService`, `DividendCycleService` and `BluechipSchedulers` are introduced before later tasks consume them.
