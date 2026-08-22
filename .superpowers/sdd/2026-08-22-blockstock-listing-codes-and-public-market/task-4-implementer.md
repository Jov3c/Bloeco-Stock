# Task 4 implementation record

## Red / green evidence

- RED: focused command/gate tests initially failed because `StockCommand`, `StockTabCompleter`, `CommandAcceptanceGate`, cache test seam and generic runtime attachment did not exist.
- RED follow-up: a second in-flight `/stock subscribe` dispatched the service twice; the added test failed with two invocations.
- RED follow-up: attaching gates after `PluginRuntime.stop()` did not explicitly close them; the added runtime test failed.
- RED follow-up: a numeric announcement limit beyond `Integer.MAX_VALUE` was treated as part of the company name; the boundary test failed before parsing numeric tokens separately.
- GREEN: the focused `/stock`, tab completion, runtime, plugin metadata and messages command passed after the minimal implementations.
- Final verification: `./gradlew.bat clean test shadowJar` passed and produced `build/libs/blockstock-0.1.0-SNAPSHOT-all.jar`.

## Threading and lifecycle

- `/stock` root/help is static while unready. All other subcommands check the acceptance gate before invoking public reads or the subscription saga.
- Public reads and all completion/error replies are routed through `MainThreadExecutor`; offline players are not messaged from an async completion.
- Startup leaves both gates closed through migration/recovery, builds the public query service and symbol cache, then chains `cache.refresh(...)` without `join`/`get`. Only a successful initial refresh attaches both gates. A refresh failure disables the plugin rather than marking it ready.
- Each successful IPO lifecycle tick refreshes the cache asynchronously as the periodic fallback. Disable stops that lifecycle before gates/database/executor shutdown.
- Stock tab completion has only `PublicStockSymbolCache`; it reads the immutable snapshot and performs no SQL or joins.
- Subscription validates player, permission and positive shares; it resolves only an OPEN offering and delegates to the pre-existing `PrimaryOfferingService` saga. An in-flight player guard prevents duplicate dispatch while the saga is pending.

## Documentation and smoke scope

- `README.md`, `plugin.yml`, `config.yml`, and `docs/operations/ipo-listing-and-stock-smoke.md` document the public Chinese command surface, default permission and reference-price wording.
- No shared server was restarted. Console empty-market and automated build are executable checks; authenticated-player IPO close/listing verification is explicitly marked **未执行** without captured server evidence.

## Commit

`feat: expose public stock command safely`

## Review follow-up

- Public monetary messages now use root `currency.scale` (default 2) and `Money.toMajor(scale).toPlainString()`; stock market/info and both stock/company IPO displays no longer expose minor units.
- Runtime shutdown closes acceptance before draining the SQL executor, then closes the database. The timeout path escalates with `shutdownNow` but completion guards still prevent post-stop ready/log work.
- `BlockecoPlugin.attachStockAfterInitialRefresh` is the production startup seam; its controlled-future test proves a pending refresh cannot open gates and a success opens both together.
- Company async player replies check online state, and duplicate `/stock subscribe` reports IPO processing rather than company-registration wording.
