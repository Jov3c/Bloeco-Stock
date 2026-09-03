# BlockStock Terminal Layout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Render the market and stock-detail views as a role-based BlockStock pixel trading terminal.

**Architecture:** Visual roles travel separately from item material/action. The Paper renderer assigns roles to market and detail slots; the resource pack contains one 16×16 model/texture per role. Existing actions and hover data remain unchanged.

**Tech Stack:** Java 21, Paper 1.21.4, JUnit 5, resource-pack format 46.

**Spec:** `docs/superpowers/specs/2026-08-31-terminal-layout-design.md`

## Global Constraints

- Vanilla client with server-supplied resource pack only.
- Never derive cosmetics from player-controlled display text.
- Preserve one-second in-place refresh.

---

### Task 1: Add explicit visual roles

**Files:**
- Modify: `StockGuiItemFactory.java`, `BlockStockTerminalTheme.java`
- Test: `BlockStockTerminalThemeTest.java`

- [ ] Write a failing test asserting `modelForRole("bid_level")` and `modelForRole("chart_up")` map to separate `blockstock:` keys.
- [ ] Run `gradlew.bat test --tests cn.blockeco.exchange.paper.BlockStockTerminalThemeTest` and observe compilation failure.
- [ ] Add the role mapping plus the item-factory overload that writes the role's `ItemMeta` model.
- [ ] Re-run the focused test and observe PASS.

### Task 2: Assign roles to market and detail layouts

**Files:**
- Modify: `StockGuiController.java`
- Test: `StockGuiControllerTest.java`

- [ ] Write failing assertions that canonical detail slots expose `chart_up`, `bid_level`, `ask_level`, `buy_action`, and `sell_action`.
- [ ] Run the focused controller test and observe failure.
- [ ] Add a visual-role field to `DetailSlot`, pass it through rendering, and assign market-card roles from quote direction.
- [ ] Re-run focused tests and observe PASS.

### Task 3: Create and deploy the terminal component pack

**Files:**
- Create: `resource-pack/assets/blockstock/items/*.json`
- Create: `resource-pack/assets/blockstock/models/item/*.json`
- Create: `resource-pack/assets/blockstock/textures/item/*.png`

- [ ] Generate a distinct pixel panel texture for header, quote, information, chart, volume, bid, ask, buy, sell, help, navigation and market direction roles.
- [ ] Package the resource pack, calculate SHA-1 and update the local server property URL.
- [ ] Run `gradlew.bat clean test shadowJar --no-configuration-cache --rerun-tasks`, deploy only the verified jar, and capture the running Minecraft window after the resource-pack reconnect.
