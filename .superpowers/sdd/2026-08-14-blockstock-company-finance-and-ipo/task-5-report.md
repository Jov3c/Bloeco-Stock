# Task 5 verification report

## Implemented

- Vault provider resolution now preflights the configured reserved escrow UUID before company commands can become accepting. It checks for the non-player account and asks the provider to create an empty account when absent; provider refusal disables BlockStock with a Chinese administrator diagnostic.
- Legacy capitalization recovery runs on the SQL executor before `PluginRuntime.attachReady`, so company/asset/IPO commands stay initializing until the one-time recovery completes. The deterministic legacy operation ID makes repeat startup idempotent.
- Added a regression test for escrow-preflight refusal and retained the real SQLite double-recovery test for legacy capitalization idempotence.
- Updated Chinese operations documentation and made the minimal ignored local configuration addition: `company.treasury-escrow-uuid`.

## Automated evidence

- RED: `./gradlew.bat test --tests cn.blockeco.exchange.BlockecoPluginTest --tests cn.blockeco.exchange.application.CompanyCapitalizationServiceTest` failed at test compilation because `VaultProviderResolver.escrowPreflightFailure` did not exist.
- GREEN: the same focused command completed successfully after the implementation.
- Full: `./gradlew.bat clean test shadowJar --rerun-tasks --no-build-cache` completed successfully; `git diff --check` reported no whitespace errors; `build/libs` contained exactly one `blockstock-0.1.0-SNAPSHOT-all.jar`.

## Paper/Vault smoke evidence and remaining manual action

- Started `gradlew.bat runServer --console=plain` from this worktree. Paper 1.21.4 build 232 loaded BlockStock, Vault 1.7.3-b131, and Essentials 2.21.0; the log contains `Vault [Economy] Essentials Economy hooked` and listens at `127.0.0.1:25565`.
- The first launched artifact correctly refused startup because the legacy ignored local config had no escrow UUID/account: `BlockStock initialization failed: 托管账户启动前检查失败 ... has no Vault account`. This is the intended refusal path and occurred before command acceptance or capital recovery.
- The implementation was then improved to request creation of an empty reserved provider account. It has passed automated verification but cannot be smoke-restarted yet: the current Paper process was launched with redirected standard input, so this task has no safe console-control channel. **An operator must enter `/stop` in the active Paper console**, wait for shutdown, then start `./gradlew.bat runServer --console=plain` again and check `BlockStock ready; legacy capitalizations recovered=...` plus the Vault hook.
- Do not use Aoozzz/player creation, insufficient-funds, third-party asset binding, or IPO acceptance as evidence until the operator has been told about the restart and a real authenticated player session and verified asset adapter are available.

## Fix round 1/5 — finance recovery and startup gate

- RED: `./gradlew.bat test --tests cn.blockeco.exchange.BlockecoPluginTest --tests cn.blockeco.exchange.application.CompanyCapitalizationServiceTest` first failed because `StartupRecoveryGate` did not exist; the callback-observability RED then failed because the gate constructor was absent. The legacy SQLite assertion was changed to require exactly one real escrow deposit across two recoveries.
- GREEN: the same focused command passed after adding the production startup gate, Chinese Vault preflight diagnostics, and real legacy escrow settlement.
- Legacy money state machine: stable `legacy-capitalization:<company-id>` UUID → `PREPARED` → (historically confirmed, never replayed player debit) `PLAYER_WITHDRAWN` → exactly one `depositEscrow(amount, operationId)` → in one SQLite transaction `ESCROW_DEPOSITED`, cash account, 1,000 founder shares, `COMPLETED`, and audit events. A failed/throwing deposit becomes `AMBIGUOUS`; startup recovery never replays `PLAYER_WITHDRAWN` or `AMBIGUOUS` records. The SQLite tests assert player withdrawals remain 0, first and second recoveries total one escrow deposit, no duplicate cash/shares, and failure leaves no cash/shares plus `AMBIGUOUS`.
- Startup gate: a known/online/previously-played `OfflinePlayer` UUID is rejected before `hasAccount`/`createPlayerAccount`; only an unknown, never-played UUID reaches Vault account checks. Preflight rejection invokes the Chinese failure callback, does not invoke recovery, and leaves the gate non-ready/non-accepting. Successful preflight remains non-ready until recovery completes.
- README smoke status was corrected to say that the post-fix Paper restart has not been run; it no longer claims ready/restart success. The prescribed creation smoke command is `/company create "Red Stone" 10000.00 50`, and the ready log format includes `legacy capitalizations recovered=...`.

## Fix round 2/5 — Chinese startup diagnostics and command acceptance boundary

- RED: the focused `BlockecoPluginTest`/`CompanyCommandTest` command first failed to compile because no final startup diagnostic formatter existed. After adding the tests, the first global acceptance-gate implementation made three prior ready-path tests fail until those tests explicitly modelled `accepting=true`; this confirms the new default gate applies before dispatch rather than only to create.
- GREEN: `BlockecoPlugin.startupFailureMessage` produces the final administrator message `BlockStock 启动失败：Vault 经济提供方不可用`; the bootstrap failure prefix and the no-Vault-provider source diagnostic now use Chinese primary text. Exception/provider details may still appear only as details.
- Command boundary: `/company` with no arguments still renders help without database/Vault access. For every non-empty `/company ...` invocation while `accepting=false`, `CompanyCommand` immediately sends only `BlockStock 正在初始化，请稍后再试。` and returns before permission, registration, query, recovery, asset, or IPO work. The new `info` and `recovery list` RED/GREEN tests assert the query service has no interactions and the sender receives only that initializing message. Existing ready-path tests explicitly set `accepting=true` and continue to pass.

## Fix round 3/5 — remaining Chinese diagnostics and exhaustive command gate

- RED: the focused `BlockecoPluginTest`/`CompanyCommandTest` command failed at test compilation because `configurationFailureMessage` and `missingCompanyCommandMessage` did not exist.
- GREEN: `Invalid BlockStock configuration: ...` is now emitted as `BlockStock 配置无效（附加信息：...）`; both early and post-bootstrap missing-`company` command paths use `BlockStock 命令注册失败：未在 plugin.yml 中声明 company 命令`. These are Chinese administrator-facing primary diagnostics; validation/third-party detail remains only in parentheses.
- Exhaustive command boundary evidence: a single real `CompanyCommand` test invokes `create`, `info`, `recovery list`, `asset bind`, `ipo announce`, and an unknown non-empty command with `accepting=false`. Every invocation returns exactly the Chinese initializing message and has zero interactions with registration, query, asset, and IPO services. A companion zero-argument test confirms help is still readable and has zero service interactions.

## Fix round 4/5 — command-gate assertion strength

- RED/expectation strengthening: the existing six-branch initialization test was changed from a weak `containsOnly` message assertion to an assertion that each `onCommand` call returns `true` and that the sender receives **exactly six** initializing messages in branch order. This is intentionally a test-only change; a silent/false-return branch or a missing/extra message now fails deterministically.
- GREEN: the focused `CompanyCommandTest` passed immediately because the round-2 production gate already satisfies the strengthened contract. No production code was changed.
