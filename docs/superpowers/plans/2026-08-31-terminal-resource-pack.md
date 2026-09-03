# BlockStock Terminal Resource Pack Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give BlockStock's market terminal a server-supplied pixel-terminal skin and keep live market refreshes in the existing window.

**Architecture:** The Paper plugin assigns a stable `blockstock:*` item model to each visual role. A 1.21.4 resource pack supplies the matching item models and textures, while stock values remain hover text and server-side GUI routing data. Refresh renders into the active inventory rather than reopening it.

**Tech Stack:** Java 21, Paper 1.21.4 API, JUnit 5, Minecraft resource-pack format 46, PowerShell local HTTP hosting.

**Spec:** User-approved server-supplied resource pack; match the BlockStock pixel trading-terminal preview as closely as vanilla inventory rendering permits; 1-second market refresh without GUI flicker.

## Global Constraints

- Minecraft client remains unmodded; it only downloads the server resource pack after player acceptance.
- Keep all trade/permission routing in persistent item data; an item model is cosmetic only.
- Use `blockstock:` item model names and resource pack format `46` for Minecraft 1.21.4.
- Do not replace inventories during recurring market/detail refresh.

---

### Task 1: Assign deterministic visual models to terminal items

**Files:**
- Create: `src/test/java/cn/blockeco/exchange/paper/BlockStockTerminalThemeTest.java`
- Modify: `src/main/java/cn/blockeco/exchange/paper/BlockStockTerminalTheme.java`
- Modify: `src/main/java/cn/blockeco/exchange/paper/StockGuiItemFactory.java`

**Interfaces:**
- Produces: `BlockStockTerminalTheme.itemModelFor(Material, String): NamespacedKey`
- Consumes: `StockGuiItemFactory.action(Material, String, Component, List<Component>)`

- [ ] **Step 1: Write the failing test**

```java
assertThat(BlockStockTerminalTheme.itemModelFor(Material.LIME_WOOL, "detail:buy"))
        .isEqualTo(new NamespacedKey("blockstock", "buy_button"));
assertThat(BlockStockTerminalTheme.itemModelFor(Material.BLACK_STAINED_GLASS_PANE, "noop"))
        .isEqualTo(new NamespacedKey("blockstock", "slot_dark"));
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradlew.bat test --tests cn.blockeco.exchange.paper.BlockStockTerminalThemeTest`

Expected: compilation failure because `itemModelFor` does not yet exist.

- [ ] **Step 3: Write minimal implementation**

```java
static NamespacedKey itemModelFor(Material material, String action) {
    if ("detail:buy".equals(action)) return new NamespacedKey("blockstock", "buy_button");
    if (material == Material.BLACK_STAINED_GLASS_PANE) return new NamespacedKey("blockstock", "slot_dark");
    return new NamespacedKey("blockstock", "terminal_tile");
}
```

Apply the returned key through `ItemMeta.setItemModel` in the item factory.

- [ ] **Step 4: Run test to verify it passes**

Run: `gradlew.bat test --tests cn.blockeco.exchange.paper.BlockStockTerminalThemeTest`

Expected: PASS.

### Task 2: Package terminal art and configure server delivery

**Files:**
- Create: `resource-pack/pack.mcmeta`
- Create: `resource-pack/assets/blockstock/items/*.json`
- Create: `resource-pack/assets/blockstock/models/item/*.json`
- Create: `resource-pack/assets/blockstock/textures/item/*.png`
- Modify: `run/server.properties`

**Interfaces:**
- Consumes: exact `blockstock:*` model names emitted by Task 1.
- Produces: `run/resource-pack/BlockStock-Terminal.zip` and server resource-pack URL/SHA-1 settings.

- [ ] **Step 1: Create a local pack verification test**

```java
assertThat(BlockStockTerminalTheme.resourcePackModelNames())
        .contains("slot_dark", "terminal_tile", "buy_button", "sell_button", "bid_level", "ask_level");
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradlew.bat test --tests cn.blockeco.exchange.paper.BlockStockTerminalThemeTest`

Expected: compilation failure because the model-name manifest does not yet exist.

- [ ] **Step 3: Implement the manifest and pack assets**

Use matching item definitions and generated 16×16 pixel textures. Package them in a zip; calculate its SHA-1; set a loopback URL and a Chinese resource-pack prompt.

- [ ] **Step 4: Verify package structure and test**

Run: `gradlew.bat test --tests cn.blockeco.exchange.paper.BlockStockTerminalThemeTest` and inspect the zip entries with `tar -tf`.

Expected: PASS; zip contains `pack.mcmeta`, every `assets/blockstock/items/<name>.json`, model, and texture.

### Task 3: Build, deploy, and visually verify against the running client

**Files:**
- Modify: deployed `run/plugins/BlockStock.jar`

**Interfaces:**
- Consumes: tested plugin jar and resource pack zip.
- Produces: restartable local server with automatic pack prompt.

- [ ] **Step 1: Run full regression build**

Run: `gradlew.bat clean test shadowJar --no-configuration-cache --rerun-tasks`

Expected: exit code 0 with no test failures.

- [ ] **Step 2: Deploy safely**

Back up the exact deployed BlockStock jar, replace it with the verified shadow jar, start the loopback pack host, then restart only the known local Paper process listening on port 25565.

- [ ] **Step 3: Verify server and client outcome**

Check port 25565, scan latest server log for `BlockStock ready`, then capture the Minecraft window once the client has accepted the pack and opened the market terminal.
