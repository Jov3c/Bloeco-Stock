# Task 11 report — second-stage bootstrap failure handling

## Scope

Owned files only:

- `src/main/java/cn/blockeco/exchange/BlockecoPlugin.java`
- `src/test/java/cn/blockeco/exchange/BlockecoPluginTest.java`

## Root cause

The first `finishEnable` call is executed by `BootstrapCoordinator`, which fails closed for exceptions and a `false` return.  The bluechip bootstrap continuation invoked the second `finishEnable(db)` directly in a main-thread callback, outside that coordinator.  Consequently, an exception or `false` result could leave the plugin only partly wired without calling `failEnable`.

## TDD evidence

Added three focused regression tests before the production change:

1. Exception from the second-stage wiring is reported while the runtime is live.
2. `false` from the second-stage wiring is reported while the runtime is live.
3. A stopped runtime neither runs nor reports the second-stage failure.

Red run:

```text
BlockecoPluginTest > second_bluechip_bootstrap_wiring_false_is_reported_while_runtime_is_live() FAILED
BlockecoPluginTest > second_bluechip_bootstrap_does_not_report_failure_after_runtime_stops() FAILED
BlockecoPluginTest > second_bluechip_bootstrap_wiring_exception_is_reported_while_runtime_is_live() FAILED
java.lang.NoSuchMethodException at BlockecoPluginTest.java:62
```

## Fix

Added the small fail-closed continuation wrapper.  It checks the runtime shutdown state before proceeding, catches any second-stage wiring failure, treats `false` as `bootstrap wiring failed`, and routes each live-runtime failure through the existing `startupFailureMessage(...)` / `failEnable(...)` path.

## Verification

```text
.\\gradlew.bat test --tests cn.blockeco.exchange.BlockecoPluginTest --tests cn.blockeco.exchange.BootstrapCoordinatorTest --no-daemon --console=plain
BUILD SUCCESSFUL in 18s
```

No QA or live server was started, stopped, or modified.
