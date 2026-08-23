# BlockStock 原版 GUI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付中文、仅服务端安装、基于原版库存界面的 BlockStock 交易所。

**Architecture:** `paper` 层维护玩家 UUID 与随机会话 ID 绑定的 GUI 会话；库存操作通过 `MainThreadExecutor`，数据和交易委托给现有 application 服务。GUI 不保存余额或订单真相。

**Tech Stack:** Java 21、Paper 1.21.4 API、Adventure、JUnit 5、AssertJ、Mockito。

**Spec:** `docs/superpowers/specs/2026-08-23-blockstock-vanilla-gui-design.md`

## Global Constraints

- 支持 Paper `1.21.4-R0.1-SNAPSHOT` 与原版客户端；不加入客户端模组、资源包、ProtocolLib 或 GUI 库。
- Bukkit/Paper 类型不得进入 domain/application；保留已有 `/stock` 文字子命令和 `/stock help`。
- 异步回调必须回到 `MainThreadExecutor` 后才访问 Bukkit；只更新仍在线且会话 ID 一致的玩家。
- 只使用已有资金、撮合、查询、权限、初始化及 `mutationsOpen` 门禁；确认前零副作用。
- 不公开其他玩家持仓、委托、成交或订单 UUID；取消、关闭、过期会话无资金和订单变动。

---

### Task 1: 会话、草稿与可测试安全边界

**Files:**
- Create: `src/main/java/cn/blockeco/exchange/paper/StockGuiSession.java`
- Test: `src/test/java/cn/blockeco/exchange/paper/StockGuiSessionTest.java`

**Interfaces:** 产生不可变 `StockGuiSession(UUID id, UUID owner, Page page, int pageIndex, String stockCode, Draft draft)`、`open(UUID)`、`next(...)` 和 `belongsTo(UUID)`。`Draft` 仅承载现金转账、限价单、私有撤单的已验证值。

- [ ] **Step 1: Write the failing test**

```java
@Test void next_page_replaces_the_session_id_and_keeps_only_its_owner() {
    UUID owner = UUID.randomUUID();
    StockGuiSession initial = StockGuiSession.open(owner);
    StockGuiSession next = initial.next(StockGuiSession.Page.MARKET, 1, null, null);
    assertThat(next.id()).isNotEqualTo(initial.id());
    assertThat(next.belongsTo(owner)).isTrue();
    assertThat(next.belongsTo(UUID.randomUUID())).isFalse();
}
```

- [ ] **Step 2: Verify RED**

Run: `./gradlew.bat test --tests cn.blockeco.exchange.paper.StockGuiSessionTest`

Expected: FAIL because `StockGuiSession` is missing.

- [ ] **Step 3: Implement and verify GREEN**

Use a validated immutable record and a fresh `UUID.randomUUID()` for every `next` transition.

Run: `./gradlew.bat test --tests cn.blockeco.exchange.paper.StockGuiSessionTest`

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/cn/blockeco/exchange/paper/StockGuiSession.java src/test/java/cn/blockeco/exchange/paper/StockGuiSessionTest.java
git commit -m "feat: add stock gui session model"
```

### Task 2: 主菜单、库存标识与事件防护

**Files:**
- Create: `src/main/java/cn/blockeco/exchange/paper/StockGuiItemFactory.java`
- Create: `src/main/java/cn/blockeco/exchange/paper/StockGuiController.java`
- Test: `src/test/java/cn/blockeco/exchange/paper/StockGuiControllerTest.java`

**Interfaces:** `StockGuiController` 实现 `Listener`，暴露 `openHome(Player)` 与库存事件处理器。私有 holder 包含 owner、session ID、page；动作使用 `PersistentDataContainer` 标签而不以 lore 作为授权来源。

- [ ] **Step 1: Write failing main-menu/event tests**

```java
@Test void opening_home_creates_a_six_row_chinese_exchange_inventory() {
    controller.openHome(player);
    verify(player).openInventory(inventoryCaptor.capture());
    assertThat(inventoryCaptor.getValue().getSize()).isEqualTo(54);
}

@Test void click_drag_and_hotbar_moves_in_a_blockstock_inventory_are_cancelled() {
    controller.onInventoryClick(click); controller.onInventoryDrag(drag);
    assertThat(click.isCancelled()).isTrue(); assertThat(drag.isCancelled()).isTrue();
}
```

- [ ] **Step 2: Verify RED**

Run: `./gradlew.bat test --tests cn.blockeco.exchange.paper.StockGuiControllerTest`

Expected: FAIL because controller does not exist.

- [ ] **Step 3: Implement and verify GREEN**

Render a 54-slot Chinese menu with market, cash, portfolio, orders, trades, IPO guide, help and close. Cancel every click/drag/collect/hotbar event before routing only the owner's top inventory action. Closing only removes the matching session.

Run: `./gradlew.bat test --tests cn.blockeco.exchange.paper.StockGuiControllerTest`

Expected: PASS.

- [ ] **Step 4: Add owner-isolation RED/GREEN test and commit**

```java
@Test void another_player_cannot_activate_the_owner_session() {
    controller.openHome(owner);
    controller.onInventoryClick(clickFromOtherPlayerOnOwnersHolder());
    verifyNoInteractions(queries, cash, trading);
}
```

Run: `./gradlew.bat test --tests cn.blockeco.exchange.paper.StockGuiControllerTest`

```bash
git add src/main/java/cn/blockeco/exchange/paper/StockGuiItemFactory.java src/main/java/cn/blockeco/exchange/paper/StockGuiController.java src/test/java/cn/blockeco/exchange/paper/StockGuiControllerTest.java
git commit -m "feat: add protected stock gui home menu"
```

### Task 3: 市场分页与五档详情

**Files:**
- Modify: `src/main/java/cn/blockeco/exchange/paper/StockGuiController.java`
- Modify: `src/main/java/cn/blockeco/exchange/paper/StockGuiItemFactory.java`
- Modify: `src/test/java/cn/blockeco/exchange/paper/StockGuiControllerTest.java`

**Interfaces:** 消费 `SecondaryMarketQueryService.market()` 和 `book(String, 5)`，产生 `openMarket(Player,int)` 与 `openStockDetail(Player,String)`。每页最多 45 条 `PublicMarketRow`。

- [ ] **Step 1: Write failing stale-result and depth tests**

```java
@Test void stale_market_completion_never_reopens_a_replaced_session() {
    CompletableFuture<List<PublicMarketRow>> pending = new CompletableFuture<>();
    when(secondaryQueries.market()).thenReturn(pending);
    controller.openMarket(player, 0); controller.openHome(player);
    pending.complete(List.of(row("BS000001"))); runQueuedMainWork();
    verify(player, times(2)).openInventory(any(Inventory.class));
}

@Test void detail_requests_exactly_five_bid_and_ask_levels() {
    controller.openStockDetail(player, "BS000001");
    verify(secondaryQueries).book("BS000001", 5);
}
```

- [ ] **Step 2: Verify RED**

Run: `./gradlew.bat test --tests cn.blockeco.exchange.paper.StockGuiControllerTest`

Expected: FAIL because public pages are missing.

- [ ] **Step 3: Implement and verify GREEN**

Query asynchronously and render on main only if accepting, online and exact owner/session match. Show public market price/change/volume/capitalisation and anonymous five bid/five ask aggregate levels, plus pagination, refresh, back and buy/sell actions.

Run: `./gradlew.bat test --tests cn.blockeco.exchange.paper.StockGuiControllerTest`

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/cn/blockeco/exchange/paper/StockGuiController.java src/main/java/cn/blockeco/exchange/paper/StockGuiItemFactory.java src/test/java/cn/blockeco/exchange/paper/StockGuiControllerTest.java
git commit -m "feat: add stock market gui pages"
```

### Task 4: 私有页面、铁砧原版输入与确认交易

**Files:**
- Modify: `src/main/java/cn/blockeco/exchange/paper/StockGuiController.java`
- Modify: `src/main/java/cn/blockeco/exchange/paper/StockGuiItemFactory.java`
- Modify: `src/test/java/cn/blockeco/exchange/paper/StockGuiControllerTest.java`

**Interfaces:** 消费现金/交易/查询服务、接受与变更门禁、货币精度；流为 `openAnvilInput -> validated Draft -> confirmation -> existing service CompletionStage`。

- [ ] **Step 1: Write failing confirmation tests**

```java
@Test void order_draft_does_not_call_trading_until_green_confirmation() {
    controller.showConfirmation(player, orderDraft("BS000001", BUY, 12L, "3.45"));
    verifyNoInteractions(trading);
    controller.onInventoryClick(greenConfirmClick(player));
    verify(trading).placeBuy(player.getUniqueId(), "BS000001", 12L, Money.ofMinor(345));
}

@Test void closing_or_red_cancelling_a_draft_has_no_cash_or_order_side_effect() {
    controller.showConfirmation(player, cashDraft(true, "20.00"));
    controller.onInventoryClose(closeCurrent(player));
    verifyNoInteractions(cash, trading);
}
```

- [ ] **Step 2: Verify RED**

Run: `./gradlew.bat test --tests cn.blockeco.exchange.paper.StockGuiControllerTest`

Expected: FAIL because draft/confirmation flow is absent.

- [ ] **Step 3: Implement and verify GREEN**

Use a holder-tagged `InventoryType.ANVIL`; `PrepareAnvilEvent` adjusts only presentation. Parse positive money with `Money.fromMajor(new BigDecimal(input), scale)` and positive shares with `long`; retain stock code from detail page. Generate a new session for every transition. Green confirmation is the only point that calls `deposit`, `withdraw`, `placeBuy`, `placeSell` or private `cancel`.

Run: `./gradlew.bat test --tests cn.blockeco.exchange.paper.StockGuiControllerTest`

Expected: PASS.

- [ ] **Step 4: Add private/gate tests and commit**

```java
@Test void maintenance_allows_private_reads_but_blocks_every_gui_mutation() {
    controller = controllerWithMutationsOpen(false);
    controller.openCash(player); runQueuedMainWork();
    controller.confirmCashTransfer(player, Money.ofMinor(100), true);
    verify(secondaryQueries).portfolio(player.getUniqueId());
    verifyNoInteractions(cash, trading);
}
```

Run: `./gradlew.bat test --tests cn.blockeco.exchange.paper.StockGuiControllerTest`

```bash
git add src/main/java/cn/blockeco/exchange/paper/StockGuiController.java src/main/java/cn/blockeco/exchange/paper/StockGuiItemFactory.java src/test/java/cn/blockeco/exchange/paper/StockGuiControllerTest.java
git commit -m "feat: add confirmed gui trading workflows"
```

### Task 5: 命令、插件装配和正式验证

**Files:**
- Modify: `src/main/java/cn/blockeco/exchange/paper/StockCommand.java`
- Modify: `src/main/java/cn/blockeco/exchange/BlockecoPlugin.java`
- Modify: `src/main/java/cn/blockeco/exchange/paper/Messages.java`
- Modify: `src/main/resources/config.yml`
- Modify: `src/main/resources/plugin.yml`
- Modify: `src/test/java/cn/blockeco/exchange/paper/StockCommandTest.java`
- Create: `src/test/java/cn/blockeco/exchange/paper/StockGuiPluginWiringTest.java`

**Interfaces:** `StockCommand` 接收 `StockGuiController`，仅由玩家的 `/stock` 或 `/stock gui` 调用 `openHome`；`BlockecoPlugin` 注入现有服务并注册 listener。

- [ ] **Step 1: Write failing command-routing test**

```java
@Test void root_stock_and_gui_open_menu_but_help_remains_text() {
    StockGuiController gui = mock(StockGuiController.class);
    StockCommand command = secondaryWithGui(gui); Player player = player(true);
    command.onCommand(player, mock(Command.class), "stock", new String[0]);
    command.onCommand(player, mock(Command.class), "stock", new String[] {"gui"});
    command.onCommand(player, mock(Command.class), "stock", new String[] {"help"});
    verify(gui, times(2)).openHome(player);
    verify(player, atLeastOnce()).sendMessage(any(Component.class));
}
```

- [ ] **Step 2: Verify RED, wire and verify GREEN**

Run: `./gradlew.bat test --tests cn.blockeco.exchange.paper.StockCommandTest.root_stock_and_gui_open_menu_but_help_remains_text`

Expected before wiring: FAIL.

Add compatible constructor overloads, Chinese GUI configuration defaults/help text, plugin listener registration and command usage. Console gets `playersOnly`; not-ready GUI gets `initializing`.

Run: `./gradlew.bat test --tests cn.blockeco.exchange.paper.StockCommandTest --tests cn.blockeco.exchange.paper.StockGuiPluginWiringTest`

Expected: PASS.

- [ ] **Step 3: Full build and server smoke verification**

Run: `./gradlew.bat clean test shadowJar --no-build-cache`

Expected: exit 0 and `build/libs/blockstock-0.1.0-SNAPSHOT-all.jar` created.

Run: `./gradlew.bat runServer --no-build-cache`

Expected: Paper enables BlockStock and later logs `BlockStock ready` with no GUI configuration/listener exception.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/cn/blockeco/exchange/paper/StockCommand.java src/main/java/cn/blockeco/exchange/BlockecoPlugin.java src/main/java/cn/blockeco/exchange/paper/Messages.java src/main/resources/config.yml src/main/resources/plugin.yml src/test/java/cn/blockeco/exchange/paper/StockCommandTest.java src/test/java/cn/blockeco/exchange/paper/StockGuiPluginWiringTest.java
git commit -m "feat: expose blockstock vanilla gui"
```

