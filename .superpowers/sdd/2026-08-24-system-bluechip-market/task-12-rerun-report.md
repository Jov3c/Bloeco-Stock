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

## Task 15 fresh acceptance — market-maker blocker

Task 15 corrected the configured ticker contract.  A new fresh Shadow JAR and
fresh QA DB confirmed all ten configured symbols in the native market GUI and
opened `BlockStock NOVA`.  GUI cash deposit and the two Anvil inputs succeeded
under an OPEN session.  But a real `NOVA` GUI buy of ten shares at `12.00`
returned `待成交`; stopped/readonly SQLite inspection showed no new
`stock_trades` row.

This is a product failure of the required system maker/open matching path.
The original harness only tested that five quote *slots* were non-null, which
is insufficient because the GUI's filler panes occupy every empty slot.  It
now requires the actual custom data-component labels `卖1`..`卖5` and
`买1`..`买5`, in addition to the real trade/holding assertions.  This stricter
test failed before any downstream lifecycle assertion could be honestly run.
QA was stopped; no 25565 connection was made.

## Final fresh rerun — share issuance conservation blocker

After accepting partial fills as the intended five one-share maker levels, the
fresh QA runner completed its real session/lifecycle sequence and stopped the
server for SQLite acceptance.  The hard share-conservation query failed for
all ten bluechips:

```text
stock_listings.issued_shares = 1,000,000
sum(share_holdings.available_shares + reserved_shares) = 100,000
```

`initial-fund-shares` is seeded into the system holder while `total-shares` is
recorded as the listing's issued quantity.  The remaining 900,000 shares per
company have no holder, so the securities ledger is not conserved.  This is a
product accounting invariant failure, independent of partial matching; the
QA assertion remains strict.  Fix either the issued quantity semantics or
allocate every issued share through a tracked holder before claiming final
acceptance.

## Task 16 fresh rerun — opening queue ordering blocker

Task 16's issued-share repair was built into a new Shadow JAR and the runner
again created a fresh SQLite and isolated Vault fixture.  The PREOPEN vanilla
bot submitted and durably queued its NOVA buy.  On the following OPEN restart,
the required opening-fill assertion found zero trades for that queued order.

The likely ordering is that `MarketSessionService` claims and matches the
opening queue before the maker's first quote refresh creates the system five
levels.  The subsequent quote refresh does not re-match existing customer
orders, so only a later new taker could cross.  The script deliberately stops
before the administrator event and later lifecycle checks when this mandatory
PREOPEN -> OPEN contract fails; the resulting lack of a persisted event is
therefore consequential, not an event assertion defect.

## Task 17 fresh rerun — maker replenishment blocker

Task 17 (`564f670`) fixes the opening ordering.  The current fresh isolated
Paper run proves it: the PREOPEN NOVA order became `PARTIALLY_FILLED` with
five actual one-share trades at 1200 minor units immediately after the OPEN
startup.  The runner now uses a bounded 15-second read-only SQLite poll for
that required transition, rather than treating one instantaneous observation
of an asynchronous opening task as conclusive.  The asserted condition itself
is unchanged: at least one actual PREOPEN-to-OPEN trade is mandatory.

The next unchanged strict acceptance caught a new product failure.  Those five
trades consume the maker's five offers (1003, 1006, 1009, 1012, and 1015), but
the maker does not replenish them.  The stopped fresh DB retains all five as
`FILLED` with zero remaining shares and only the five bid orders as `OPEN`
(997, 994, 991, 988, and 985).  Consequently the genuine vanilla NOVA detail
GUI has no `卖1` through `卖5`, and the exact real-five-level assertion fails
at `卖1`.  The server was stopped immediately, with `qa-server/logs/latest.log`
and the fresh database retained as evidence.  No test condition was relaxed,
no product source was changed, and no connection to port 25565 was made.

The remaining event/news, POSTCLOSE, candle, dividend/idempotency, escrow
reconciliation, and final Gradle acceptance must remain pending until the
maker maintains five executable levels after an opening fill.

## Task 20 fresh rerun — refill callback lost during the opening quote pass

Task 20 (`f9f1359`) was first rebuilt with a fresh `shadowJar` successfully
before the isolated run.  The same full strict runner then again passed the
PREOPEN native GUI queue and proved the five actual OPEN trades, but failed at
the unchanged NOVA GUI `卖1` level assertion.  The exact fresh timestamps
exclude a rendering race: the server became ready at 20:55:29; five trades
committed at 12:55:29.913--.994Z; and the OPEN bot did not reach the detail GUI
until 20:55:43.  SQLite again contains all five system sells as `FILLED` and
only the five system bids as `OPEN`.

The product-level reason is specific.  The opening `refreshOpenQuotes()` holds
`systemQuotePassActive=true` through its queued-order matcher.  A match invokes
Task 20's detached `scheduleAfterMatchedOrders()`, whose asynchronous callback
immediately calls `replenishAfterMatch()`.  That method sees the still-true
flag and returns an already-completed no-op.  The first quote pass later clears
the flag but never schedules another refill.  Therefore the opening trade
permanently consumes the five offers.  QA stopped at this mandatory user-GUI
contract; later stages and the final full Gradle command were not claimed.

The harness also now contains a stopped-server hard assertion for the required
Vault escrow reconciliation: the QA Essentials system balance, expressed in
minor units, must equal `escrow_ledger_entries` liabilities, and those must
equal securities cash accounts plus the compensation fund.  It cannot be
executed in this fresh run because the prior mandatory GUI invariant blocks
the lifecycle stages.
