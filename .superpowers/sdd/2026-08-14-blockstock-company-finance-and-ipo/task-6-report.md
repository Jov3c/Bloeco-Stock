# Task 6 report — runtime minimum company capital

## RED

Added the administrator command, live-rule, command/help/completion, and service pre-Vault boundary tests. The initial focused command failed at test compilation because `MutableCompanyCreationRules`, `StockAdminConfigCommand`, and the configuration completion path did not exist.

Command used:

```powershell
.\gradlew.bat test --tests cn.blockeco.exchange.paper.StockAdminConfigCommandTest --tests cn.blockeco.exchange.paper.CompanyCommandTest --tests cn.blockeco.exchange.paper.CompanyTabCompleterTest
```

Observed failure: missing symbols for the planned mutable rule source and `/stockadmin` command.

## GREEN / implementation

- Added one atomic `MutableCompanyCreationRules` source. Company parsing, Chinese root help, tab completion, and the registration service use its current rule.
- The service snapshots its live minimum at the start of `register`, before any Vault call. Raised thresholds reject before Vault; lowering applies to the next registration; an in-flight request retains its snapshot.
- Added `/stockadmin config` and `/stockadmin config min-capital <金额>`, guarded exactly by `blockstock.admin.config` (default OP). Amounts must be positive and exact for `currency.scale`.
- The command saves `company.minimum-capital` before publishing the live replacement. Failed saves do not publish or create a success audit event.
- Successful saves queue an SQL transaction writing `ADMIN_MINIMUM_CAPITAL_CHANGED` with old/new minor amounts, actor, source, and time. Console payloads use `actor=CONSOLE`; audit failures send a Chinese warning without claiming the audit succeeded.
- Updated `plugin.yml`, default messages, and README operator guidance.

## Verification

Focused tests passed:

```powershell
.\gradlew.bat test --tests cn.blockeco.exchange.application.CompanyRegistrationServiceTest --tests cn.blockeco.exchange.paper.StockAdminConfigCommandTest --tests cn.blockeco.exchange.paper.CompanyCommandTest --tests cn.blockeco.exchange.paper.CompanyTabCompleterTest
```

Full suite passed:

```powershell
.\gradlew.bat test --rerun-tasks --no-build-cache
# BUILD SUCCESSFUL in 1m 38s
```

`git diff --check` completed without whitespace errors.

## Release Paper smoke evidence

A controlled real Paper/Vault smoke was subsequently completed. Fresh `clean test shadowJar --rerun-tasks --no-build-cache` was `BUILD SUCCESSFUL` with 24 suites / 161 tests / 0 failure,error,skip and one Shadow JAR. Java 21.0.11 Paper 1.21.4 build 232 loaded Vault 1.7.3-b131 and EssentialsX 2.21.0, with `Essentials Economy hooked` in the log.

The console verified `/stockadmin config` at `10000.00`, saved and immediately read `10000.01`, then restored `10000.00`. Read-only SQLite verification found `ADMIN_MINIMUM_CAPITAL_CHANGED` sequence 19 (`actor=CONSOLE`, `oldMinor=1000000`, `newMinor=1000001`) and sequence 20 with reversed values. After a graceful stop/restart, the console still read `10000.00`; `1.234` was rejected with the Chinese precision message and did not change the value. The server also recorded first post-fix `legacy capitalizations recovered=2` and second-start `recovered=0`.

Not performed: authenticated-player company creation/insufficient-funds/client completion, a real third-party asset adapter, and IPO subscription. A host-pause `Can't keep up`/clock-leap warning is documented as an environment event rather than plugin pass evidence; the subsequent restart reached ready normally.

## Fix round 1 — failure-aware config persistence

### RED

`FileConfigStoreTest` was added for successful exact persistence with unrelated keys retained, plus injected save and move failures retaining both the target file and live `FileConfiguration` value. `StockAdminConfigCommandTest` now makes `ConfigStore.persistMinimumCapital` fail and verifies there is no live-rule publication, audit, or success message.

The first run failed because the production persistence type and single failure-reporting method did not exist. A subsequent test run exposed that copying nested Bukkit configuration sections into the staged config shared mutable section objects and changed live memory before saving.

### GREEN / design

- `ConfigStore` is now one checked-failure operation: `persistMinimumCapital(String)`.
- `FileConfigStore` stages by serializing and reloading the complete live configuration into a separate `YamlConfiguration`, modifies only `company.minimum-capital`, saves to a sibling temporary file using `FileConfiguration.save(File)`, then uses `ATOMIC_MOVE + REPLACE_EXISTING`.
- If the filesystem does not support atomic moves, it explicitly falls back to a replace move. On save/move failure, `finally` removes the temporary file and neither the target file nor the live `FileConfiguration` value changes.
- Only after replacement does it update live memory. The command then publishes the mutable creation rule, sends success, and queues audit. `JavaPlugin.saveConfig()` is not used for this path.
- Basis recorded for review: Paper `JavaPlugin.saveConfig()` catches `IOException`; Bukkit `FileConfiguration.save(File)` propagates `IOException`.

Focused verification for this round:

```powershell
.\gradlew.bat test --tests cn.blockeco.exchange.paper.FileConfigStoreTest --tests cn.blockeco.exchange.paper.StockAdminConfigCommandTest
.\gradlew.bat test --tests cn.blockeco.exchange.application.CompanyRegistrationServiceTest --tests cn.blockeco.exchange.paper.FileConfigStoreTest --tests cn.blockeco.exchange.paper.StockAdminConfigCommandTest --tests cn.blockeco.exchange.paper.CompanyCommandTest --tests cn.blockeco.exchange.paper.CompanyTabCompleterTest
```

## Fix round 2 — atomic replacement required

### RED/GREEN

Added an injected `AtomicMoveNotSupportedException` test whose non-atomic fallback operation would succeed. It initially failed because the store treated that fallback as a successful persistence. The store now propagates the atomic-move exception, deletes the temporary file, and leaves both target file and live configuration unchanged.

The production `FileOperations` contract no longer exposes non-atomic `replace`; the only publish path is sibling temporary save followed by `ATOMIC_MOVE + REPLACE_EXISTING`. Filesystems that cannot supply that operation cause the command persistence contract to fail, so the command cannot publish rules, success output, or audit.
