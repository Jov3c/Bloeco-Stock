# Task 2 completion report

- Implemented Chinese display names for the ten fixed bluechips while retaining ticker codes.
- Added immutable `StockGuiSession.ChartMode` state and clickable `chart:intraday` / `chart:daily` detail rerenders.
- Rebuilt the vanilla detail inventory as the selected Paper layout: black-glass header/footer, cards (10–16), five-level red/green book (19–25), colored chart raster (28–34), and controls (37–43).
- Tests: the requested Gradle command reached the known local failure `java.io.IOException: Unable to establish loopback connection` before test execution. Manual fallback compiled all production Java sources with `javac -proc:none -encoding UTF-8`, then ran the two focused JUnit classes through the JUnit Platform Launcher: `tests=14 failed=0`.
- Concern: no live Paper-client visual smoke test was possible in this environment; the inventory positions and action tags are covered by the implementation and focused chart/config assertions.

## Review remediation

- Detail now queries the current market row and owner portfolio for actual latest price, change, turnover, and holding totals. The title is localized as `公司名 · 代码` and the header strip is informative.
- Buy and sell depth are rendered independently with five visible red ask prices and five visible green bid prices. The daily raster derives red/green candle bars; the intraday raster derives blue/cyan line segments.
- Bottom controls now show turnover volume, compensation-fund guidance, buy, sell, help, and back; chart toggles remain directly clickable from the raster.
- Added focused assertions for localized identity/cards/actions, independent depth labels, and different chart rasters. Manual JUnit fallback: `tests=17 failed=0`; manual production compile passed.
- Remediation commit: `88bb640 fix: complete native exchange detail layout`.

## Review loop 2 remediation

- Commit: `effe59af0c27b84ac132ec5344976824acf03e2a` — `fix: wire native chart controls to detail sessions`.
- The detail renderer now consumes `DetailSlot` specifications for slot 15 (`CLOCK`, `chart:intraday`) and slot 16 (`ENCHANTED_BOOK`, `chart:daily`), so the visible dedicated controls no longer use `noop`.
- The production inventory-click route invokes the same owner- and session-bound `applyChartControl` seam. It atomically replaces the immutable detail session with the selected `ChartMode` and then reloads the detail view; stale inventory IDs cannot change the mode.
- The depth footprint is slots 19–25 (the fourth/fifth buy/sell levels remain visible in the depth-book lore), and the chart raster is slots 28–34.
- Regression coverage asserts the renderer's slot/material/action specs and the real controller action seam's immutable-session update/stale-ID rejection.
- Verification: the requested Gradle command remained blocked before test discovery with `java.io.IOException: Unable to establish loopback connection`. Java 21 manual compilation of `StockGuiSession`, `StockGuiController`, and `StockGuiControllerTest` succeeded; the JUnit Platform fallback reported `tests=12 failed=0`.

## Final review remediation

- `renderDetail` now consumes one canonical `DetailSlot` list for cards, controls, depth, chart, and bottom actions. The specification rejects duplicate slots before the inventory write loop, so a slot cannot silently overwrite another item.
- The book is rendered as five individual red asks, a center latest-price item, and five individual green bids. Eleven items cannot fit in the inclusive `18–27` ten-slot range while chart slots remain `28–34`; the non-overlapping contiguous band is therefore `17–27`. The center and help lore visibly document that one-slot expansion.
- The change card is now a real `Material.ARROW` with an up/down direction in its lore. Bottom slots visibly include volume (37), today turnover (38), compensation guidance (39), buy/sell/help/back (40–43).
- The regression test exercises the exact `DetailSlot` list used by `renderDetail`, asserting uniqueness and the material/action of controls, all depth items, chart entries, and bottom controls. It also proves a chart click replaces the owner-bound immutable session and a stale ID cannot route a second change.
- Verification: requested Gradle focused test remains pre-discovery blocked by `java.io.IOException: Unable to establish loopback connection`. Manual Java 21 compile of `MarketChart`, `StockGuiSession`, `StockGuiController`, and `StockGuiControllerTest` plus JUnit Platform fallback: `tests=13 failed=0`.
