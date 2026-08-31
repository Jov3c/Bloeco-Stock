# Quant Market Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a finite-capital, high-activity bluechip quant market with fictional events, risk controls, one-second visible data refresh, and durable audit metrics.

**Architecture:** Extend the existing ordinary system participant rather than add a privileged settlement path. A pure signal/risk policy picks at most one finite order per strategy tick; a serialized coordinator records every decision and calls the existing market service. Existing event persistence/model boundaries remain the source of all price impact. Tunable thresholds live under `market.quant`, not alongside the `bluechips` list, so the fixed ten-company roster continues to load unchanged.

**Tech Stack:** Java 21, Paper 1.21.4, SQLite/Flyway, JUnit 5/AssertJ, existing Vault escrow.

**Spec:** `docs/superpowers/specs/2026-08-31-quant-market-engine-design.md`

## Global Constraints

- `Asia/Shanghai` 08:00–20:00 only; close stops all strategy orders.
- Use existing `SecondaryMarketService`, finite cash and finite inventory only.
- Never use or change `compensation_fund` from the strategy.
- Do not call Vault from strategy code or fabricate SQL trades.
- Every scheduler callback is serialized and bounded.
- `market.quant.target-confidence-bps=6500` expresses a long-run game target; it is not a guaranteed per-order win rate.

## Configuration Contract

Add this documented, validated block below `market` in `config.yml`:

```yaml
market:
  quant:
    target-confidence-bps: 6500
    maximum-order-bps: 200
    loss-cooldown-seconds: 120
```

`BluechipQuantConfig` must reject a confidence outside `5000..9000`, an order cap outside `1..500`, or a cooldown outside `1..3600`. These bounds preserve the intended active market while preventing an operator typo from creating an unlimited strategy.

---

### Task 1: Persist quant risk and decisions

**Files:**
- Create: `src/main/resources/db/migration/V021.sql`
- Create: `src/main/java/cn/blockeco/exchange/domain/bluechip/QuantRiskState.java`
- Create: `src/main/java/cn/blockeco/exchange/domain/bluechip/QuantDecision.java`
- Modify: `src/main/java/cn/blockeco/exchange/ports/BluechipRepository.java`
- Modify: `src/main/java/cn/blockeco/exchange/infrastructure/sql/SqlBluechipRepository.java`
- Test: `src/test/java/cn/blockeco/exchange/infrastructure/sql/SqlBluechipRepositoryTest.java`

**Interfaces:** `loadQuantRisk(String): Optional<QuantRiskState>`, `saveQuantRisk(Connection, QuantRiskState)`, `recordQuantDecision(Connection, QuantDecision)`, and `activeEventImpactBps(String stockCode, String industry, Instant now): int`. Risk state may upsert; decisions are append-only; the event aggregate includes only unexpired market/industry/company events that apply to the selected code.

- [ ] **Step 1: Write the failing persistence test**

```java
f.repository.saveQuantRisk(f.connection(), new QuantRiskState("NOVA", 2, 3, NOW.plusSeconds(30), NOW));
f.repository.recordQuantDecision(f.connection(), decision("NOVA", "IMBALANCE", 6500, "BUY"));
assertThat(f.repository.loadQuantRisk("NOVA")).isPresent();
assertThat(f.decisionCount("NOVA")).isEqualTo(1);
assertThat(f.repository.activeEventImpactBps("NOVA", "Manufacturing", NOW)).isEqualTo(125);
```

- [ ] **Step 2: Verify RED**

Run: `$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Temp\BlockStock-JavaSockets'; .\gradlew.bat test --tests cn.blockeco.exchange.infrastructure.sql.SqlBluechipRepositoryTest --rerun-tasks`

Expected: FAIL because quant tables and methods are absent.

- [ ] **Step 3: Implement V021 and repository methods**

```sql
CREATE TABLE bluechip_quant_risk (stock_code TEXT PRIMARY KEY, risk_level INTEGER NOT NULL CHECK(risk_level BETWEEN 0 AND 3), consecutive_losses INTEGER NOT NULL CHECK(consecutive_losses >= 0), cooldown_until TEXT NOT NULL, updated_at TEXT NOT NULL);
CREATE TABLE bluechip_quant_decisions (id TEXT PRIMARY KEY, stock_code TEXT NOT NULL, signal TEXT NOT NULL, confidence_bps INTEGER NOT NULL, action TEXT NOT NULL, requested_shares INTEGER NOT NULL, filled_shares INTEGER NOT NULL, realized_pnl_minor INTEGER NOT NULL, risk_level INTEGER NOT NULL, decided_at TEXT NOT NULL);
```

- [ ] **Step 4: Verify GREEN and commit**

Run the Step 2 command; then:

```powershell
git add src/main/resources/db/migration/V021.sql src/main/java/cn/blockeco/exchange/domain/bluechip src/main/java/cn/blockeco/exchange/ports/BluechipRepository.java src/main/java/cn/blockeco/exchange/infrastructure/sql/SqlBluechipRepository.java src/test/java/cn/blockeco/exchange/infrastructure/sql/SqlBluechipRepositoryTest.java
git commit -m "feat: persist bluechip quant risk and decisions"
```

### Task 2: Testable 65% target signal and loss protection

**Files:**
- Create: `src/main/java/cn/blockeco/exchange/paper/BluechipQuantConfig.java`
- Modify: `src/main/resources/config.yml`
- Create: `src/main/java/cn/blockeco/exchange/application/QuantSignalPolicy.java`
- Create: `src/main/java/cn/blockeco/exchange/application/QuantRiskPolicy.java`
- Test: `src/test/java/cn/blockeco/exchange/paper/BluechipQuantConfigTest.java`
- Test: `src/test/java/cn/blockeco/exchange/application/QuantSignalPolicyTest.java`
- Test: `src/test/java/cn/blockeco/exchange/application/QuantRiskPolicyTest.java`

**Interfaces:** `BluechipQuantConfig.load(FileConfiguration)` returns the validated threshold/order/cooldown record. `evaluate(OrderBook, Money last, Money model, int eventBps): Signal`; `orderShares(long desired, long cash, long shares, Money price, QuantRiskState risk): long`; `afterResult(QuantRiskState, long pnl, Instant): QuantRiskState`.

- [ ] **Step 1: Write failing policy tests**

```java
assertThatThrownBy(() -> BluechipQuantConfig.load(configWith("market.quant.target-confidence-bps", 4999)))
        .isInstanceOf(IllegalArgumentException.class);
assertThat(policy.evaluate(bookWithBidPressure(), Money.ofMinor(1000), Money.ofMinor(970), 120).confidenceBps()).isBetween(6500, 10000);
assertThat(policy.evaluate(balancedBook(), Money.ofMinor(1000), Money.ofMinor(1000), 0).confidenceBps()).isLessThan(6500);
assertThat(risk.afterResult(levelZero(), -1, NOW).riskLevel()).isEqualTo(1);
```

- [ ] **Step 2: Verify RED**

Run: `$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Temp\BlockStock-JavaSockets'; .\gradlew.bat test --tests cn.blockeco.exchange.application.QuantSignalPolicyTest --tests cn.blockeco.exchange.application.QuantRiskPolicyTest --rerun-tasks`

Expected: FAIL because policies do not exist.

- [ ] **Step 3: Implement minimal bounded policies**

Load and validate `market.quant` during plugin configuration validation. Combine five-level imbalance, model deviation and active event bps into a clamped confidence score. Trade at the configured threshold only. Risk levels 0/1/2 allow at most 2%/1%/0.5% of available cash or inventory; level 3 returns zero. Three losses raise a level; profit and cooldown lower it. This is a 65% long-run game target, never a per-trade guarantee.

- [ ] **Step 4: Verify GREEN and commit**

Run the Step 2 command; commit with `feat: add bounded bluechip quant policies`.

### Task 3: Serialized coordinator using ordinary orders

**Files:**
- Create: `src/main/java/cn/blockeco/exchange/application/BluechipQuantStrategyService.java`
- Modify: `src/main/java/cn/blockeco/exchange/BlockecoPlugin.java`
- Test: `src/test/java/cn/blockeco/exchange/application/BluechipQuantStrategyServiceTest.java`

**Interfaces:** `tick(): CompletionStage<Integer>` returns 0 or 1 and writes one `QuantDecision` even for `NO_TRADE`.

- [ ] **Step 1: Write failing coordinator tests**

```java
assertThat(List.of(service.tick(), service.tick()).stream().mapToInt(s -> s.toCompletableFuture().join()).sum()).isLessThanOrEqualTo(1);
assertThat(fixture.compensationFund()).isEqualTo(fixture.initialCompensationFund());
assertThat(fixture.quantDecisionCount()).isGreaterThan(0);
```

- [ ] **Step 2: Verify RED**

Run: `$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Temp\BlockStock-JavaSockets'; .\gradlew.bat test --tests cn.blockeco.exchange.application.BluechipQuantStrategyServiceTest --rerun-tasks`

Expected: FAIL because the coordinator is absent.

- [ ] **Step 3: Implement and wire the 8-second callback**

Serialize ticks through a completion-stage tail. Select one eligible code from the current eight-second step; obtain the five-level book from `SecondaryTradingRepository`, model data and active-event aggregate from `BluechipRepository`, evaluate policy, apply risk state, and invoke `SecondaryMarketService.placeBuy/placeSell` only for finite cash/inventory. Use the existing system participant UUID. Replace the existing participant scheduler callback with `quantStrategy.tick()`; keep the independent one-second quote scheduler. All synchronous repository reads run on the SQL executor, never on Paper's main thread.

- [ ] **Step 4: Verify GREEN and commit**

Run the Step 2 command and `BluechipSystemParticipantServiceTest`; commit with `feat: run finite bluechip quant strategy`.

### Task 4: Frequent fictional event templates

**Files:**
- Modify: `src/main/java/cn/blockeco/exchange/application/MarketEventService.java`
- Test: `src/test/java/cn/blockeco/exchange/application/MarketEventServiceTest.java`

- [ ] **Step 1: Write the failing event test**

```java
BluechipEvent event = service.triggerDueEvents().toCompletableFuture().join().getFirst();
assertThat(Math.abs(event.priceImpactBps())).isBetween(25, 120);
assertThat(event.headline()).doesNotContain("物流挑战");
```

- [ ] **Step 2: Verify RED**

Run: `$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Temp\BlockStock-JavaSockets'; .\gradlew.bat test --tests cn.blockeco.exchange.application.MarketEventServiceTest --rerun-tasks`

Expected: FAIL because the template and impact range are fixed.

- [ ] **Step 3: Implement fixed fictional template pools**

Select company/industry/market templates only from game-fiction text. Schedule the next event in 5–20 minutes, use 25–120 bps absolute impact, and retain existing expiry, announcements, model bounds and decay.

- [ ] **Step 4: Verify GREEN and commit**

Run the Step 2 command; commit with `feat: add frequent fictional bluechip events`.

### Task 5: One-second refresh, read-only metrics, and local observation

**Files:**
- Modify: `src/main/java/cn/blockeco/exchange/paper/StockGuiController.java`
- Modify: `src/main/java/cn/blockeco/exchange/paper/BluechipAdminCommand.java`
- Modify: `src/main/java/cn/blockeco/exchange/BlockecoPlugin.java`
- Test: `src/test/java/cn/blockeco/exchange/paper/StockGuiControllerTest.java`
- Test: `src/test/java/cn/blockeco/exchange/paper/BluechipAdminCommandTest.java`
- Create: `qa-server/mcp/quant-observation.mjs`

- [ ] **Step 1: Write failing UI/admin tests**

```java
assertThat(StockGuiController.liveRefreshPeriodTicks()).isEqualTo(20L);
assertThat(command.complete(admin, new String[]{"bluechip", "quant", ""})).contains("status");
```

- [ ] **Step 2: Verify RED**

Run: `$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Temp\BlockStock-JavaSockets'; .\gradlew.bat test --tests cn.blockeco.exchange.paper.StockGuiControllerTest --tests cn.blockeco.exchange.paper.BluechipAdminCommandTest --rerun-tasks`

Expected: FAIL because refresh is 40 ticks and quant status is absent.

- [ ] **Step 3: Implement UI and read-only diagnostics**

Change the existing session-owned refresh chain to 20 ticks; retain identity/page guards. Change the quote scheduler from two seconds to one second. Add only `/stockadmin bluechip quant status [CODE]`, permission-gated and read-only, showing cash, inventory, decisions, recent win rate, risk level, cooldown and no-trade count. No command may fund the strategy.

- [ ] **Step 4: Package, deploy, and observe local QA**

Build with `$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Temp\BlockStock-JavaSockets'; .\gradlew.bat clean test shadowJar --no-configuration-cache --rerun-tasks`. Stop only local QA `127.0.0.1:25566`, move its jar to a timestamp backup, deploy the shaded jar and start with both Java socket temp properties. During an open session, `quant-observation.mjs` samples for ten minutes and asserts decision growth, no more than one strategy order per eight-second bucket, nonnegative cash/shares, unchanged compensation-fund balance and risk level at most 3.

- [ ] **Step 5: Commit**

Commit metrics, refresh and QA script with `feat: expose quant metrics and one second market refresh`.

### Task 6: Full regression and release evidence

**Files:**
- Modify: `docs/superpowers/plans/2026-08-31-quant-market-engine.md` (mark completed tasks and record command outputs)

- [ ] **Step 1: Run the complete test suite without test filters**

Run: `$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Temp\BlockStock-JavaSockets'; .\gradlew.bat clean test shadowJar --no-configuration-cache --rerun-tasks`

Expected: `BUILD SUCCESSFUL`; inspect every `build/test-results/test/*.xml` and report zero `<failure>` and `<error>` elements.

- [ ] **Step 2: Check the packaged artifact and QA deployment boundary**

Verify the shaded jar contains `plugin.yml`, Flyway migration `V021.sql`, and the quant classes. Deploy only to the existing local QA server at `127.0.0.1:25566`; do not touch a player-facing server, its worlds, or its database.

- [ ] **Step 3: Record outcomes and commit**

Record build, migration, GUI-refresh, admin-status, and open-session observation evidence in this plan. Commit with `test: verify quant market release candidate`.
