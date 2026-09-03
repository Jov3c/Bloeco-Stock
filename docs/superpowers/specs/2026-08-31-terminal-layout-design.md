# BlockStock Terminal Layout Design

## Goal

Replace the generic dark-item resource-pack skin with a recognizable BlockStock pixel trading terminal that mirrors the approved preview's hierarchy: header, quote cards, chart field, bid/ask depth, volume and action bar.

## Client Boundary

The client remains vanilla and downloads a server resource pack. Custom item models can render a distinct pixel graphic in each inventory slot, but vanilla inventory cannot place live text directly onto a slot face. Dynamic price, company name, volume and holding values remain in the item title and hover text; visual colour and structure update live.

## Layout

- Global frame: black/teal metal top and bottom rails; a distinct BlockStock emblem in the title slot.
- Market: a stock-card tile per listed stock, colour-coded by movement, plus visible navigation and home controls.
- Detail: slots 1–6 quote/header cards; slots 9/18/27/36 information rail; slots 10–41 cyan/purple chart grid; 42 volume; 7/16/25/34/43 green five-level bids; 8/17/26/35/44 red five-level asks; 46–49 action bar.
- The same dark frame is shared by all remaining stock screens; they retain existing permissions and click actions.

## Rendering Interface

`StockGuiItemFactory` accepts an optional visual role separate from its material and click action. `BlockStockTerminalTheme` converts roles to `blockstock:*` item model keys. This prevents translated display text or routing actions from controlling the artwork.

## Acceptance

1. The stock detail page has no generic terminal tiles in the chart/depth/action areas.
2. Bid/ask/action/volume models are distinguishable at a glance in a direct Minecraft-window capture.
3. Market cards use up/down roles, no default Paper appearance.
4. Refreshing for at least four seconds keeps the same inventory open.
5. Full build and resource-pack zip validation pass; client capture is taken from the Minecraft window handle.
