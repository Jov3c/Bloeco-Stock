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

## Not performed

No real Paper/Vault server operation was performed. In particular, live console/player permission behavior, config file persistence, and asynchronous SQLite audit writes still require a controlled Paper server verification with Vault available.
