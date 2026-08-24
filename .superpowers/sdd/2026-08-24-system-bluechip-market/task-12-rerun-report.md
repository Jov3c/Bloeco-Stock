# Task 12 rerun — Task 13 bootstrap repair

Status: **blocked at a second fresh-product recovery gate.**  Only the
isolated QA Paper service on `127.0.0.1:25566` was started; port 25565 was not
contacted.  The QA service was stopped after evidence collection.

## What now passes

After Task 13 commit `a0c4c64`, a fresh `shadowJar` build succeeded and the
runner moved the old QA database before starting from a new one.  The former
Essentials unknown-system-UUID failure is fixed: the fresh database contains
one `bluechip_bootstrap_funding` row in `COMPLETED`, a system securities cash
account of `115000000` minor units, and matching `SECURITIES_CASH` ledger
entries.  The QA Essentials escrow userdata recorded `money: '1150000.0'`.

Mineflayer reached the genuine `/stock` home inventory:

```text
BLUECHIP_WINDOW|{"type":"string","value":"BlockStock 交易所"}
```

The acceptance script's first real GUI run also exposed and corrected a
harness defect: confirmation is asynchronous and deliberately leaves the
confirmation inventory open, so it must wait for the resulting chat/ledger
effect rather than await another `BlockStock` window.

## New blocker

Despite the above, fresh startup logs:

```text
BlockStock ready; secondary trading=maintenance
```

The bot can open the GUI but a GUI cash-deposit confirmation returns:

```text
证券交易功能暂不可用，请稍后再试。
```

`StockGuiController` checks `secondaryTradingGate.mutationsOpen` before both
cash and order mutations.  It was false because the startup recovery snapshot
reported `mutationsBlocked`.  This is inconsistent with the observed fresh
funding row, local securities ledger/cash total, and Essentials escrow value;
the product must diagnose the physical-balance/reconciliation path before
real PREOPEN/OPEN/POSTCLOSE trading can proceed.

No product code was changed to bypass the gate.  The remaining full acceptance
plan (queued-to-opening fill, 5-level maker trade, event/news, post-close
queue, K-line, 15-day dividend idempotency, stopped ledger and final full
Gradle test) remains intact and must be rerun after the gate repair.

## Task 14 rerun correction and ticker blocker

The apparent Task 14 maintenance state was a QA-fixture error, not a product
failure: the runner moved the old SQLite DB but had retained the previous
isolated Essentials escrow balance.  A new bluechip bootstrap therefore made
the real escrow balance twice the fresh local liability, and recovery
correctly kept mutations closed.  The runner now makes the external escrow
fixture fresh too, retaining a timestamped backup before resetting it.

With that fixture correction, fresh startup reported `secondary trading=open`.
The vanilla bot completed GUI deposit and reached the market page.  It found
exactly ten entries, but they were `BS000001 Nova Forge` through `BS000010
Verdant Homes`, not the configured tickers.  Clicking Nova Forge opened
`BlockStock BS000001`, while the configuration and acceptance contract require
`NOVA` (and likewise AURORA, TERRAN, SKYLINE, IRONWOOD, LUMEN, RIVERMINT,
ORBITAL, CINDER, VERDANT).

The cause is `BluechipBootstrapService.seed()` using the generic
`StockListingRepository.allocate(...)` path, which auto-generates `BS` codes
instead of persisting `BluechipDefinition.code`.  The QA script now reads the
actual 1.21.4 item data-components and strictly asserts each configured ticker
and the selectable `NOVA` detail title; it intentionally does not accept a
generic BS symbol.  QA was stopped and no later acceptance stage was skipped
or weakened pending the product-side configured-ticker repair.
