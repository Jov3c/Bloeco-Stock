# BlockStock 原版交易所重做 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付符合左侧预览图的原版交易所 GUI，并修复重复确认、中文名称、系统成交量与日 K 切换。

**Architecture:** `StockGuiController` 仍是 Paper 展示层，增加确认会话消费锁和图表模式。做市账户保留有限报价；新系统参与者账户通过既有订单撮合产生真实成交。行情、成交量和 K 线只读取 `stock_trades`。

**Tech Stack:** Java 21、Paper 1.21.4、Vault/Essentials、SQLite/Hikari、JUnit 5、AssertJ、Mineflayer。

**Spec:** `docs/superpowers/specs/2026-08-26-blockstock-native-exchange-redesign-design.md`

## Global Constraints

* 仅 Paper 原版库存 GUI；不要求资源包、模组或客户端改动。
* 撮合窗口固定为配置时区的 `[08:00, 20:00)`。
* 所有玩家和系统资金、股份变化必须经过现有订单、结算、托管账本和恢复路径。
* 系统量必须是实际 `stock_trades`，不可伪造成交、成交量、蜡烛或余额。
* 保留英文代码与公司 ID；仅显示名称改为中文。
* 不修改无关的脏工作树文件。

---

### Task 1: 一次性确认与结果页

**Files:**
- Modify: `src/main/java/cn/blockeco/exchange/paper/StockGuiSession.java`
- Modify: `src/main/java/cn/blockeco/exchange/paper/StockGuiController.java`
- Test: `src/test/java/cn/blockeco/exchange/paper/StockGuiControllerTest.java`

**Interfaces:**
- Consumes: `StockGuiSession.next(...)`、玩家 UUID 的 `mutationsInFlight`、现金与订单异步服务。
- Produces: `Page.SUBMITTING`、`Page.RESULT` 和 `boolean beginSubmission(UUID, UUID)`。

- [ ] **Step 1: Write the failing test**

```java
@Test void confirmation_is_consumed_before_its_async_operation_completes() {
    var gui = StockGuiController.forSessionTests(); UUID player = UUID.randomUUID();
    StockGuiSession confirm = gui.openSession(player, Page.CONFIRM, 0, null,
        new StockGuiSession.CashTransfer(true, Money.ofMinor(100)));
    assertThat(gui.beginSubmission(player, confirm.id())).isTrue();
    assertThat(gui.beginSubmission(player, confirm.id())).isFalse();
    assertThat(gui.currentPage(player)).isEqualTo(Page.SUBMITTING);
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew.bat test --tests cn.blockeco.exchange.paper.StockGuiControllerTest --no-daemon --console=plain`

Expected: `beginSubmission` and `SUBMITTING` do not yet exist.

- [ ] **Step 3: Write minimal implementation**

Add `SUBMITTING` and `RESULT` pages. `beginSubmission` must atomically replace the exact `CONFIRM` session with a fresh `SUBMITTING` session using `sessions.replace`; stale or second clicks return false. In `confirm`, invoke it before cash/order methods and immediately render a clock-only `正在提交，请勿重复操作` inventory. Async callback renders one result inventory with `返回详情` and `返回市场`; it must never restore the old confirmation inventory.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew.bat test --tests cn.blockeco.exchange.paper.StockGuiControllerTest --no-daemon --console=plain`

Expected: new duplicate-confirm tests and old session tests pass.

- [ ] **Step 5: Commit**

Run: `git add src/main/java/cn/blockeco/exchange/paper/StockGuiSession.java src/main/java/cn/blockeco/exchange/paper/StockGuiController.java src/test/java/cn/blockeco/exchange/paper/StockGuiControllerTest.java && git commit -m "fix: consume stock GUI confirmations once"`

### Task 2: 中文蓝筹、像素布局和图表切换

**Files:**
- Modify: `src/main/resources/config.yml`
- Modify: `src/main/java/cn/blockeco/exchange/paper/StockGuiSession.java`
- Modify: `src/main/java/cn/blockeco/exchange/paper/StockGuiController.java`
- Test: `src/test/java/cn/blockeco/exchange/paper/BluechipConfigTest.java`
- Test: `src/test/java/cn/blockeco/exchange/paper/StockGuiControllerTest.java`

**Interfaces:**
- Consumes: `MarketChart`、详情会话、五档订单簿。
- Produces: `ChartMode { INTRADAY, DAILY }`、`chart:intraday`、`chart:daily` 和 `chartLore(chart, scale, mode)`。

- [ ] **Step 1: Write the failing tests**

```java
@Test void default_bluechips_keep_codes_but_use_chinese_names() {
    BluechipConfig config = configFromResource();
    assertThat(config.definitions()).extracting(BluechipDefinition::code, BluechipDefinition::displayName)
        .contains(tuple("NOVA", "星铸工业"), tuple("AURORA", "极光电网"));
}
@Test void daily_and_intraday_modes_render_different_chart_content() {
    MarketChart chart = chartWithFiveCandlesAndThreePoints();
    assertThat(StockGuiController.chartLore(chart, 2, ChartMode.DAILY)).contains("日K线", "量99");
    assertThat(StockGuiController.chartLore(chart, 2, ChartMode.INTRADAY)).contains("分时线", "08:00", "走势");
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew.bat test --tests cn.blockeco.exchange.paper.BluechipConfigTest --tests cn.blockeco.exchange.paper.StockGuiControllerTest --no-daemon --console=plain`

Expected: English defaults and no chart mode API cause the expected red state.

- [ ] **Step 3: Write minimal implementation**

Set names to 星铸工业、极光电网、泰拉丰收、天际物流、铁林制造、流明医疗、河湾金融、轨道系统、余烬材料、青翠置业. Store `ChartMode` in immutable GUI sessions. Make clock and enchanted book switch the detail session, not `noop`. Render preview slots: 0–8/45–53 black-glass border, 10–16 top cards, 19–25 red/green five-depth order book, 28–34 colored-pane chart raster, 37–43 volume/buy/sell/back controls.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew.bat test --tests cn.blockeco.exchange.paper.BluechipConfigTest --tests cn.blockeco.exchange.paper.StockGuiControllerTest --no-daemon --console=plain`

Expected: localized names and both chart modes pass.

- [ ] **Step 5: Commit**

Run: `git add src/main/resources/config.yml src/main/java/cn/blockeco/exchange/paper/StockGuiSession.java src/main/java/cn/blockeco/exchange/paper/StockGuiController.java src/test/java/cn/blockeco/exchange/paper/BluechipConfigTest.java src/test/java/cn/blockeco/exchange/paper/StockGuiControllerTest.java && git commit -m "feat: localize bluechips and add native chart modes"`

### Task 3: 有限系统参与者与真实成交

**Files:**
- Modify: `src/main/resources/config.yml`
- Modify: `src/main/java/cn/blockeco/exchange/BlockecoPlugin.java`
- Create: `src/main/java/cn/blockeco/exchange/application/BluechipSystemParticipantService.java`
- Modify: `src/main/java/cn/blockeco/exchange/application/BluechipBootstrapService.java`
- Modify: `src/main/java/cn/blockeco/exchange/application/BluechipSchedulers.java`
- Test: `src/test/java/cn/blockeco/exchange/application/BluechipSystemParticipantServiceTest.java`
- Test: `src/test/java/cn/blockeco/exchange/application/BluechipBootstrapServiceTest.java`

**Interfaces:**
- Consumes: `BluechipRepository.all()`、`SecondaryMarketService.placeBuy/placeSell/cancelOpenOrders`、最佳买卖盘与 `MarketSession`。
- Produces: `tick(): CompletionStage<Integer>`、`close(): CompletionStage<Integer>` 和 `market.participant-account-uuid`。

- [ ] **Step 1: Write the failing tests**

```java
@Test void open_tick_places_one_bounded_order_from_distinct_participant() {
    Fixture f = fixture(openSession(), Money.ofMinor(10_000), 100);
    assertThat(f.participant.tick().toCompletableFuture().join()).isEqualTo(1);
    assertThat(f.market.placedOwners()).containsExactly(f.participantId);
    assertThat(f.market.placedOrders().getFirst().shares()).isBetween(1L, 10L);
}
@Test void closed_or_unfunded_participant_places_nothing() {
    Fixture f = fixture(closedSession(), Money.zero(), 0);
    assertThat(f.participant.tick().toCompletableFuture().join()).isZero();
    assertThat(f.market.placedOrders()).isEmpty();
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew.bat test --tests cn.blockeco.exchange.application.BluechipSystemParticipantServiceTest --no-daemon --console=plain`

Expected: service and test fixture API are absent.

- [ ] **Step 3: Write minimal implementation**

Configure a nonzero participant UUID distinct from maker and treasury UUIDs. Bootstrap finite cash and stock holdings idempotently using existing transactional cash/holding infrastructure. `tick()` returns zero outside market hours; inside, chooses one code from epoch minute, only runs that code every 1–5 minutes, submits exactly 1–10 shares with ordinary market methods, and checks cash plus fee or shares first. Alternate buy/sell by minute; do not self-trade, insert SQL trades, overdraft, or mint shares. `close()` cancels participant orders. Add one-minute scheduler tick after quote refresh.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew.bat test --tests cn.blockeco.exchange.application.BluechipSystemParticipantServiceTest --tests cn.blockeco.exchange.application.BluechipBootstrapServiceTest --tests cn.blockeco.exchange.application.BluechipMarketMakerServiceTest --no-daemon --console=plain`

Expected: bounded real orders, idempotent bootstrap, and existing maker tests pass.

- [ ] **Step 5: Commit**

Run: `git add src/main/resources/config.yml src/main/java/cn/blockeco/exchange/BlockecoPlugin.java src/main/java/cn/blockeco/exchange/application/BluechipSystemParticipantService.java src/main/java/cn/blockeco/exchange/application/BluechipBootstrapService.java src/main/java/cn/blockeco/exchange/application/BluechipSchedulers.java src/test/java/cn/blockeco/exchange/application/BluechipSystemParticipantServiceTest.java src/test/java/cn/blockeco/exchange/application/BluechipBootstrapServiceTest.java && git commit -m "feat: add finite bluechip system participant"`

### Task 4: 原版 Bot 验收与发布

**Files:**
- Modify: `qa-server/mcp/bluechip-gui-e2e.mjs`
- Modify: `qa-server/mcp/market-gui-e2e.mjs`

**Interfaces:**
- Consumes: Shadow JAR、25566 隔离 Paper、Mineflayer、`/stock` GUI。
- Produces: 可重跑 QA，证明一次确认、中文名称、图表切换、真实系统成交和账本一致。

- [ ] **Step 1: Write failing bot assertions**

Add assertions for: repeated confirm produces one cash operation/order; market contains `星铸工业` and `NOVA`; clock then enchanted-book clicks change `分时线` to `日K线`; a scheduler tick creates a real trade owned by participant; public market volume becomes positive.

- [ ] **Step 2: Run red QA**

Run: `node qa-server/mcp/bluechip-gui-e2e.mjs`

Expected: current artifact fails repeated-confirm, English-name, daily-chart, and participant-volume assertions.

- [ ] **Step 3: Build and run isolated green QA**

Run: `./gradlew.bat test shadowJar --no-build-cache --no-daemon --console=plain`

Expected: all JUnit tests pass and shadow JAR is regenerated. Copy only that JAR to isolated 25566 QA server, run both Mineflayer scripts, and use read-only SQLite queries to verify nonnegative balances, share conservation, positive persisted volume, and zero escrow difference.

- [ ] **Step 4: Run final acceptance**

Run: `node qa-server/mcp/bluechip-gui-e2e.mjs; node qa-server/mcp/market-gui-e2e.mjs`

Expected: both exit 0 and all above assertions hold.

- [ ] **Step 5: Commit and deploy**

Run: `git add qa-server/mcp/bluechip-gui-e2e.mjs qa-server/mcp/market-gui-e2e.mjs && git commit -m "test: cover native exchange release flow"`

After fresh green results, copy `build/libs/blockstock-0.1.0-SNAPSHOT-all.jar` to `run/plugins/BlockStock.jar`, gracefully restart formal Paper, and require `BlockStock ready; secondary trading=open` before reporting delivery.
