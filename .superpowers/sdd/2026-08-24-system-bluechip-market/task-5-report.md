# Task 5 Report — Persisted bluechip events and candles

## Delivered

- Added deterministic, injected-RNG event creation for fictional BlockStock company and industry news.
- Persists event records, company announcements, and audit entries atomically before bounded model-price changes.
- Uses configured lower/upper bands and sensitivity; decay moves each model price back toward its immutable listing reference.
- Schedules company events at 6–12 hour intervals and industry events no more often than once per day.
- Adds idempotent daily OHLCV upserts from trades, falling back to the model price and zero volume.
- Exposes fictional market news through `PublicStockQueryService.recentNews(int)`.

## Tests

- Added `MarketEventServiceTest` for persistence, price movement/decay, and industry isolation.
- Added `MarketCandleServiceTest` for no-trade model candles and idempotent close.
- Focused test command passed.
- Full `./gradlew.bat test --no-daemon --console=plain` passed.

## Notes

- Event copy explicitly identifies itself as simulated BlockStock game content; it is not presented as real-world news.
- The existing schema has no standalone market-event schedule column, so the market/industry cadence is derived from the latest persisted market event.

## Fix round 1

### RED

- Added failing coverage for one global company cadence across restart, 1–3 day market-event duration, persistent player-company industry expectation, and a single 1100 trade with a 1000 model price.
- The new API/behavior tests failed before implementation because the durable schedule and player expectation representation did not exist.

### GREEN

- Added `V011` durable market schedule and company profit-expectation tables.
- Company events now share one persisted 6–12 hour due timestamp; market/industry events persist their 1–3 day end/next due timestamp.
- Industry effects persist `profit_impact_bps` for every matching `company_industry` company, leaving unrelated industries untouched.
- Trade candles initialize OHLC from the first actual trade; model fallback is now only used when the day has no trades.
- Added `PublicMarketState` to stock detail output, including model price, degraded liquidity state, and local company/industry event; global `recentNews` remains available.

### Verification

- Focused: `./gradlew.bat test --tests '*MarketEventServiceTest' --tests '*MarketCandleServiceTest' --no-daemon --console=plain`
- Full: `./gradlew.bat test --no-daemon --console=plain`
