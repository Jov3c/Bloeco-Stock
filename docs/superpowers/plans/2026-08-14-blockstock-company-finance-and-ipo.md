# BlockStock 公司财务与首发募资实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为每家公司建立由 Vault 托管余额全额支撑的资金池、初始持股和资产绑定账本，并让满足资产要求的公司完成 12 小时公告后的 2 天首发认购。

**Architecture:** 公司现金、持股、资产、公告和首发认购均作为独立领域记录保存于新增 SQLite 迁移；Vault 只在 `TreasuryEscrowGateway` 基础设施边界出现。所有“玩家钱包 ↔ BlockStock 托管 ↔ 公司账本”的跨系统转移使用持久化 saga 和不可变审计，首发及后续交易可以在此基础上复用冻结/结算模型。

**Tech Stack:** Java 21、Paper 1.21.4 API、Vault 1.7 API、SQLite/HikariCP、JUnit 5、Mockito、AssertJ、Gradle Shadow、run-paper 3.0.2。

## Global Constraints

- 仅服务端安装；原版 Java 客户端无需模组、资源包或网页即可使用命令和库存菜单。
- 保持 Paper `1.21.4`、Java `21`、现有 `cn.blockeco.exchange` 包名、Money 精确金额模型和 Vault 软依赖；不得加入运行时第三方插件依赖、NMS 或 CraftBukkit。
- V001 与 V002 是冻结历史迁移；所有结构变化只新增 V003 及后续版本，`Database.MIGRATIONS` 按顺序追加。
- 个人钱包、BlockStock 托管余额、公司资金池严格分离；创始人没有直接提取公司资金的命令。
- 每笔资金、股份、首发、公告及状态变更都在同一 SQLite 事务中追加 audit event；Vault 调用使用持久化 saga，重试不能重复扣款、重复入账或重复发股。
- 创建注册费继续是服务器费用；实缴资本必须真实进入托管余额并等额贷记公司资金池。既有公司只可一次性补建其已扣最低资本。
- 首发要求至少一个 ACTIVE 资产，目标募资不超过累计实缴资本五倍，公告期固定 12 小时，认购窗口固定 2 天，只发行整数股，未售股不发行。
- 具体第三方资产适配器不在本计划实现；本计划只交付可审计的通用绑定模型与可测试的适配器端口。

---

## File Structure

- `src/main/resources/db/migration/V003.sql` — 公司现金、持股、资产绑定、资金操作、公告、首发轮次与认购表及约束。
- `src/main/java/cn/blockeco/exchange/domain/finance/*` — 公司资金池、资金操作、持股、资产绑定、公告和首发领域值对象/状态。
- `src/main/java/cn/blockeco/exchange/ports/*` — 财务、股本、资产、公告、首发仓储，以及托管经济端口。
- `src/main/java/cn/blockeco/exchange/infrastructure/sql/*` — 新领域 SQLite 仓储，所有写入复用 `TransactionRunner` 事务。
- `src/main/java/cn/blockeco/exchange/infrastructure/vault/VaultTreasuryEscrowGateway.java` — BlockStock 保留托管账户的 Vault 调用；不向玩家暴露该账户。
- `src/main/java/cn/blockeco/exchange/application/CompanyCapitalizationService.java` — 新公司实缴资本结算、旧公司补建和恢复。
- `src/main/java/cn/blockeco/exchange/application/PrimaryOfferingService.java` — 公告、开窗、整数股认购、到期关闭。
- `src/main/java/cn/blockeco/exchange/paper/CompanyCommand.java`、`CompanyTabCompleter.java`、`Messages.java` — 新资本输入、资产/首发命令、中文帮助和权限过滤补全。
- `src/main/java/cn/blockeco/exchange/BlockecoPlugin.java`、`config.yml`、`plugin.yml` — 启动前置检查、服务装配、配置、权限和软依赖。
- `src/test/java/...` — 领域、SQLite、服务和 Paper 命令的 TDD 覆盖；`MigrationTest` 扩展 V003 验证。

### Task 1: V003 财务、股本、资产和首发数据模型

**Files:**
- Create: `src/main/resources/db/migration/V003.sql`
- Create: `src/main/java/cn/blockeco/exchange/domain/finance/CompanyCashAccount.java`
- Create: `src/main/java/cn/blockeco/exchange/domain/finance/ShareHolding.java`
- Create: `src/main/java/cn/blockeco/exchange/domain/finance/AssetBinding.java`
- Create: `src/main/java/cn/blockeco/exchange/domain/finance/AssetBindingState.java`
- Create: `src/main/java/cn/blockeco/exchange/domain/finance/PrimaryOffering.java`
- Create: `src/main/java/cn/blockeco/exchange/domain/finance/PrimaryOfferingState.java`
- Create: `src/main/java/cn/blockeco/exchange/domain/finance/TreasuryOperation.java`
- Create: `src/main/java/cn/blockeco/exchange/domain/finance/TreasuryOperationState.java`
- Modify: `src/main/java/cn/blockeco/exchange/domain/company/Company.java`
- Modify: `src/main/java/cn/blockeco/exchange/infrastructure/sql/Database.java`
- Modify: `src/test/java/cn/blockeco/exchange/infrastructure/sql/MigrationTest.java`

**Interfaces:**
- Produces `CompanyCashAccount(CompanyId companyId, Money cash, Money paidInCapital, Money retainedEarnings, Money reserved)`; all fields are non-negative and `reserved <= cash`.
- Produces `ShareHolding(CompanyId companyId, UUID holderId, long availableShares, long reservedShares)`; both counts are non-negative.
- Produces `AssetBinding(UUID id, CompanyId companyId, String adapterId, String externalKey, UUID verifiedOwner, AssetBindingState state, Instant createdAt)`; `(adapterId, externalKey)` is globally unique.
- Produces `PrimaryOffering(UUID id, CompanyId companyId, Money target, Money issuePrice, long maximumShares, Instant announcedAt, Instant opensAt, Instant closesAt, PrimaryOfferingState state)`.

- [ ] **Step 1: Write failing migration and domain invariant tests**

```java
@Test void v003_creates_exactly_one_cash_account_and_founder_holding_per_company() { /* inspect SQLite metadata and uniqueness */ }
@Test void cash_account_rejects_reserved_amount_above_cash() {
    assertThatThrownBy(() -> new CompanyCashAccount(id, Money.ofMinor(10), Money.ofMinor(10), Money.zero(), Money.ofMinor(11)))
        .isInstanceOf(IllegalArgumentException.class);
}
@Test void offering_rounds_down_target_to_integer_shares() {
    assertThat(PrimaryOffering.plan(id, Money.ofMinor(501), Money.ofMinor(100), announcedAt)).extracting(PrimaryOffering::maximumShares).isEqualTo(5L);
}
```

- [ ] **Step 2: Run to verify RED**

Run: `./gradlew.bat test --tests cn.blockeco.exchange.infrastructure.sql.MigrationTest --tests cn.blockeco.exchange.domain.finance.*Test`

Expected: FAIL because V003 and finance domain types do not yet exist.

- [ ] **Step 3: Add V003 and immutable domain values**

Create tables `company_cash_accounts`, `share_holdings`, `asset_bindings`, `treasury_operations`, `company_announcements`, `primary_offerings`, and `primary_subscriptions`. Use integer minor amounts, UUID text primary keys, foreign keys to `companies`, non-negative checks, a unique active external asset key, one cash account per company, one holding per company/player, and unique provider correlation keys. Add `V003` after `V002` in `Database.MIGRATIONS`; do not modify old scripts. Implement the records/enums and factory validation used by the tests, and add a `Company.withTotalShares(long)` invariant-preserving transition for successful primary subscriptions.

- [ ] **Step 4: Run focused tests to verify GREEN**

Run: `./gradlew.bat test --tests cn.blockeco.exchange.infrastructure.sql.MigrationTest --tests cn.blockeco.exchange.domain.finance.*Test`

Expected: PASS; V001/V002 checksums remain unchanged and a new SQLite database contains all V003 tables/constraints.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/migration/V003.sql src/main/java/cn/blockeco/exchange/domain/finance src/main/java/cn/blockeco/exchange/infrastructure/sql/Database.java src/test/java/cn/blockeco/exchange/infrastructure/sql/MigrationTest.java src/test/java/cn/blockeco/exchange/domain/finance
git commit -m "feat: add company finance schema"
```

### Task 2: 托管结算端口与公司资本化 saga

**Files:**
- Create: `src/main/java/cn/blockeco/exchange/ports/TreasuryEscrowGateway.java`
- Create: `src/main/java/cn/blockeco/exchange/ports/CompanyFinanceRepository.java`
- Create: `src/main/java/cn/blockeco/exchange/infrastructure/sql/SqlCompanyFinanceRepository.java`
- Create: `src/main/java/cn/blockeco/exchange/infrastructure/vault/VaultTreasuryEscrowGateway.java`
- Create: `src/main/java/cn/blockeco/exchange/application/CompanyCapitalizationService.java`
- Create: `src/test/java/cn/blockeco/exchange/application/CompanyCapitalizationServiceTest.java`
- Create: `src/test/java/cn/blockeco/exchange/infrastructure/sql/SqlCompanyFinanceRepositoryTest.java`

**Interfaces:**
- Produces `TreasuryEscrowGateway.withdrawPlayer(UUID playerId, Money amount, UUID operationId)`, `depositEscrow(Money amount, UUID operationId)`, `withdrawEscrow(Money amount, UUID operationId)`, and `refundToPlayer(UUID playerId, Money amount, UUID operationId)`, each returning existing `EconomyGateway.Result`; production executes each external action on `MainThreadExecutor`.
- Produces `CompanyCapitalizationService.capitalize(Company company, UUID founder, Money capital, UUID registrationSagaId)` and `recoverPendingCapitalizations()`.
- Produces `CompanyFinanceRepository.createCapitalization(Connection, CompanyCashAccount, ShareHolding, TreasuryOperation, AuditEvent)` and `findUnsettledOperations()`.

- [ ] **Step 1: Write failing saga tests**

```java
@Test void capitalizes_only_after_player_withdrawal_and_escrow_deposit_succeed() { /* assert founder debited once, escrow credited once, cash/holding/audit written */ }
@Test void database_failure_after_escrow_deposit_refunds_founder_once_and_marks_recovery_when_refund_is_ambiguous() { /* assert no duplicate retry */ }
@Test void legacy_company_capitalization_is_idempotent() { /* second recovery sees completed operation and never deposits escrow again */ }
```

- [ ] **Step 2: Run to verify RED**

Run: `./gradlew.bat test --tests cn.blockeco.exchange.application.CompanyCapitalizationServiceTest --tests cn.blockeco.exchange.infrastructure.sql.SqlCompanyFinanceRepositoryTest`

Expected: FAIL because the treasury port, repository and service do not exist.

- [ ] **Step 3: Implement an escrow-backed settlement state machine**

Use a reserved BlockStock escrow identity configured under `treasury.escrow-account`; Task 2 validates it only in the gateway tests, while Task 5 performs runtime provider preflight and refuses accepting commands if it is missing, non-existent or unsafe. `VaultTreasuryEscrowGateway` exposes player withdrawal, escrow deposit, escrow withdrawal and player refund as separate exact-Money actions. Persist `PREPARED`, `PLAYER_WITHDRAWN`, `ESCROW_DEPOSITED`, `COMPLETED`, `REFUND_REQUIRED`, `REFUNDED`, and `AMBIGUOUS` with expected-from-state SQL updates and an audit event after every confirmed external action. If the JVM can stop between an external Vault call and its following state write, startup marks the prior state `AMBIGUOUS` and exposes the operation to administrator recovery; it never repeats that external call automatically. `CompanyCapitalizationService` creates the company cash account, credits paid-in capital, creates the founder's 1,000-share holding and records the operation in one SQLite transaction only after escrow funding succeeds. Do not wire startup recovery or legacy-company creation in Task 2: Task 3 must first make every new registration create the same finance operation; Task 5 then enables one-time legacy recovery keyed by company ID without altering V001/V002 rows.

- [ ] **Step 4: Run focused tests to verify GREEN**

Run: `./gradlew.bat test --tests cn.blockeco.exchange.application.CompanyCapitalizationServiceTest --tests cn.blockeco.exchange.infrastructure.sql.SqlCompanyFinanceRepositoryTest`

Expected: PASS, including failure/refund/ambiguous and duplicate-recovery cases.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/cn/blockeco/exchange/ports/TreasuryEscrowGateway.java src/main/java/cn/blockeco/exchange/ports/CompanyFinanceRepository.java src/main/java/cn/blockeco/exchange/infrastructure/sql/SqlCompanyFinanceRepository.java src/main/java/cn/blockeco/exchange/infrastructure/vault/VaultTreasuryEscrowGateway.java src/main/java/cn/blockeco/exchange/application/CompanyCapitalizationService.java src/test/java/cn/blockeco/exchange/application/CompanyCapitalizationServiceTest.java src/test/java/cn/blockeco/exchange/infrastructure/sql/SqlCompanyFinanceRepositoryTest.java
git commit -m "feat: back company capital with escrow"
```

### Task 3: 注册命令实缴资本与初始股份

**Files:**
- Modify: `src/main/java/cn/blockeco/exchange/application/RegistrationRequest.java`
- Modify: `src/main/java/cn/blockeco/exchange/application/CompanyRegistrationService.java`
- Modify: `src/main/java/cn/blockeco/exchange/paper/CompanyCreationRules.java`
- Modify: `src/main/java/cn/blockeco/exchange/paper/CompanyCommand.java`
- Modify: `src/main/java/cn/blockeco/exchange/paper/CompanyTabCompleter.java`
- Modify: `src/main/java/cn/blockeco/exchange/paper/Messages.java`
- Modify: `src/main/resources/config.yml`
- Modify: `src/main/resources/plugin.yml`
- Modify: `src/test/java/cn/blockeco/exchange/application/CompanyRegistrationServiceTest.java`
- Modify: `src/test/java/cn/blockeco/exchange/paper/CompanyCommandTest.java`

**Interfaces:**
- Changes `RegistrationRequest` to `RegistrationRequest(UUID founderId, String companyName, Money paidInCapital, int dividendPercent)`.
- Changes command syntax to `/company create <名称> <实缴资本> <分红比例>`; the last argument remains a configured dividend candidate and the penultimate argument is parsed with configured `currency.scale`.
- Consumes `CompanyCapitalizationService` after successful registration withdrawal; produces one founder holding of exactly `company.initial-shares`.

- [ ] **Step 1: Write failing registration and parser tests**

```java
@Test void parses_precise_paid_in_capital_before_dividend() {
    assertThat(CompanyCommand.parseCreate(founder, new String[]{"create", "红石", "100000.00", "50"}, rules))
        .contains(new RegistrationRequest(founder, "红石", Money.fromMajor(new BigDecimal("100000.00"), 2), 50));
}
@Test void rejects_capital_below_configured_minimum_without_vault_call() { /* no economy or capitalization interactions */ }
@Test void completed_registration_credits_capital_and_assigns_exactly_initial_shares() { /* fee not credited; founder holding is 1000 */ }
```

- [ ] **Step 2: Run to verify RED**

Run: `./gradlew.bat test --tests cn.blockeco.exchange.application.CompanyRegistrationServiceTest --tests cn.blockeco.exchange.paper.CompanyCommandTest`

Expected: FAIL because requests and parser still omit paid-in capital.

- [ ] **Step 3: Refactor registration without weakening the existing saga**

Parse the capital with `Money.fromMajor`, reject invalid scale/zero/negative/below-minimum values before any provider action, and update dynamic Chinese help to state the required capital argument. Replace the prior fixed minimum-capital treasury credit with the request's paid-in capital; retain exact `registrationFee + paidInCapital` withdrawal semantics. Delegate company account/holding creation to Task 2's capitalization service, preserving expected-state transitions and audits for every registration state. Update the configured root and Tab behavior so the dividend candidates appear only after name and capital have both been supplied.

- [ ] **Step 4: Run focused tests to verify GREEN**

Run: `./gradlew.bat test --tests cn.blockeco.exchange.application.CompanyRegistrationServiceTest --tests cn.blockeco.exchange.paper.CompanyCommandTest --tests cn.blockeco.exchange.paper.CompanyTabCompleterTest`

Expected: PASS; duplicate name, insufficient funds, refund and initialization behavior continue to pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/cn/blockeco/exchange/application/RegistrationRequest.java src/main/java/cn/blockeco/exchange/application/CompanyRegistrationService.java src/main/java/cn/blockeco/exchange/paper/CompanyCreationRules.java src/main/java/cn/blockeco/exchange/paper/CompanyCommand.java src/main/java/cn/blockeco/exchange/paper/CompanyTabCompleter.java src/main/java/cn/blockeco/exchange/paper/Messages.java src/main/resources/config.yml src/main/resources/plugin.yml src/test/java/cn/blockeco/exchange/application/CompanyRegistrationServiceTest.java src/test/java/cn/blockeco/exchange/paper/CompanyCommandTest.java
git commit -m "feat: accept paid-in company capital"
```

### Task 4: 通用资产绑定和首发公告/认购

**Files:**
- Create: `src/main/java/cn/blockeco/exchange/ports/CompanyAssetAdapter.java`
- Create: `src/main/java/cn/blockeco/exchange/ports/AssetBindingRepository.java`
- Create: `src/main/java/cn/blockeco/exchange/ports/PrimaryOfferingRepository.java`
- Create: `src/main/java/cn/blockeco/exchange/infrastructure/sql/SqlAssetBindingRepository.java`
- Create: `src/main/java/cn/blockeco/exchange/infrastructure/sql/SqlPrimaryOfferingRepository.java`
- Create: `src/main/java/cn/blockeco/exchange/application/AssetBindingService.java`
- Create: `src/main/java/cn/blockeco/exchange/application/PrimaryOfferingService.java`
- Modify: `src/main/java/cn/blockeco/exchange/application/CompanyQueryService.java`
- Modify: `src/main/java/cn/blockeco/exchange/paper/CompanyCommand.java`
- Modify: `src/main/java/cn/blockeco/exchange/paper/CompanyTabCompleter.java`
- Modify: `src/main/java/cn/blockeco/exchange/paper/Messages.java`
- Modify: `src/main/resources/config.yml`
- Modify: `src/main/resources/plugin.yml`
- Create: `src/test/java/cn/blockeco/exchange/application/AssetBindingServiceTest.java`
- Create: `src/test/java/cn/blockeco/exchange/application/PrimaryOfferingServiceTest.java`
- Create: `src/test/java/cn/blockeco/exchange/infrastructure/sql/SqlPrimaryOfferingRepositoryTest.java`

**Interfaces:**
- Produces `CompanyAssetAdapter.id()` and `CompanyAssetAdapter.verify(UUID requester, String externalKey): Verification`, where `Verification(boolean ownedByRequester, UUID ownerId, String diagnostic)` is a nested record; adapters missing at runtime are absent from the registry.
- Produces `AssetBindingService.bind(CompanyId, UUID requester, String adapterId, String externalKey)` and `activeCount(CompanyId)`.
- Produces `PrimaryOfferingService.announce(CompanyId, UUID founder, Money target, Money issuePrice)`, `subscribe(UUID subscriber, UUID offeringId, long shares)`, and `closeExpired(Instant now)`.

- [ ] **Step 1: Write failing binding and offering tests**

```java
@Test void rejects_offering_without_an_active_asset() { /* no announcement or money movement */ }
@Test void caps_target_at_five_times_paid_in_capital() { /* 100000 capital accepts 500000, rejects 500001 */ }
@Test void opens_only_after_twelve_hours_and_closes_after_two_days() { /* fixed AppClock */ }
@Test void subscription_issues_integer_shares_and_moves_exact_price_times_shares_to_company_cash() { /* audit and idempotency key asserted */ }
@Test void expiry_does_not_issue_unsold_shares() { /* actual subscriptions remain; offer closes */ }
```

- [ ] **Step 2: Run to verify RED**

Run: `./gradlew.bat test --tests cn.blockeco.exchange.application.AssetBindingServiceTest --tests cn.blockeco.exchange.application.PrimaryOfferingServiceTest --tests cn.blockeco.exchange.infrastructure.sql.SqlPrimaryOfferingRepositoryTest`

Expected: FAIL because binding/offer services and repositories do not exist.

- [ ] **Step 3: Implement provider-neutral binding and primary offering**

Implement adapter registration and ownership verification without hard-linking Lands, QuickShop, Slimefun or BetonQuest. Add founder-only `/company asset bind <adapter> <external-key>` and `/company ipo announce <目标金额> <发行价>` commands; both return Chinese, permission-checked errors. An offer is `ANNOUNCED` for 12 hours, then `OPEN` for exactly two days. Calculate `maximumShares = floor(target / issuePrice)` and require target `<= paidInCapital * 5`. Subscription freezes/withdraws exact `shares * issuePrice` through Task 2's escrow saga, credits company cash, creates/updates the subscriber holding, increments company total shares, and appends announcement/audit records in a transaction keyed by subscription ID. Closing at exhaustion or expiry marks the company `LISTED` only if at least one share was issued; otherwise leaves its prior non-listed state.

- [ ] **Step 4: Run focused tests to verify GREEN**

Run: `./gradlew.bat test --tests cn.blockeco.exchange.application.AssetBindingServiceTest --tests cn.blockeco.exchange.application.PrimaryOfferingServiceTest --tests cn.blockeco.exchange.infrastructure.sql.SqlPrimaryOfferingRepositoryTest --tests cn.blockeco.exchange.paper.CompanyCommandTest`

Expected: PASS; tests prove the 12-hour/2-day boundaries, cap, integer share calculation, ownership rejection, exact funds and idempotent subscription handling.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/cn/blockeco/exchange/ports src/main/java/cn/blockeco/exchange/infrastructure/sql/SqlAssetBindingRepository.java src/main/java/cn/blockeco/exchange/infrastructure/sql/SqlPrimaryOfferingRepository.java src/main/java/cn/blockeco/exchange/application/AssetBindingService.java src/main/java/cn/blockeco/exchange/application/PrimaryOfferingService.java src/main/java/cn/blockeco/exchange/application/CompanyQueryService.java src/main/java/cn/blockeco/exchange/paper/CompanyCommand.java src/main/java/cn/blockeco/exchange/paper/CompanyTabCompleter.java src/main/java/cn/blockeco/exchange/paper/Messages.java src/main/resources/config.yml src/main/resources/plugin.yml src/test/java/cn/blockeco/exchange/application/AssetBindingServiceTest.java src/test/java/cn/blockeco/exchange/application/PrimaryOfferingServiceTest.java src/test/java/cn/blockeco/exchange/infrastructure/sql/SqlPrimaryOfferingRepositoryTest.java
git commit -m "feat: add company asset bindings and ipo"
```

### Task 5: 安全启动、运维文档和真实服务端验证

**Files:**
- Modify: `src/main/java/cn/blockeco/exchange/BlockecoPlugin.java`
- Modify: `README.md`
- Modify: ignored local `run/plugins/BlockStock/config.yml`
- Create: `src/test/java/cn/blockeco/exchange/BlockecoPluginTest.java`
- Create: `.superpowers/sdd/2026-08-14-blockstock-company-finance-and-ipo/task-5-report.md` (ignored verification evidence)

**Interfaces:**
- Consumes Tasks 1–4 and existing Paper/Vault/EssentialsX startup lifecycle.
- Produces a startup refusal with a Chinese/administrative diagnostic if escrow preflight fails; otherwise performs legacy capitalization recovery before accepting company/IPO commands.

- [ ] **Step 1: Write failing startup-path tests**

```java
@Test void plugin_does_not_accept_company_commands_when_escrow_preflight_fails() { /* executor remains initializing and no database finance recovery begins */ }
@Test void startup_recovery_does_not_duplicate_legacy_company_capitalization() { /* invoke twice; one operation and one escrow deposit */ }
```

- [ ] **Step 2: Run to verify RED**

Run: `./gradlew.bat test --tests cn.blockeco.exchange.BlockecoPluginTest --tests cn.blockeco.exchange.application.CompanyCapitalizationServiceTest`

Expected: FAIL because preflight/recovery integration is absent.

- [ ] **Step 3: Wire startup and Chinese operations documentation**

Initialize the escrow gateway only after Vault provider resolution, validate it before setting the company executor accepting, then run one-time legacy capitalization recovery asynchronously on the SQL executor. Document exact money movement, reserved escrow identity, backup requirements, new creation syntax, asset/IPO commands, 12-hour notice, two-day window, five-times-capital cap, and every limitation: no third-party asset adapter is bundled yet, no player session claim without a real test. Update the ignored local config only through normal plugin config generation or a documented minimal local adjustment.

- [ ] **Step 4: Run complete automated verification**

Run: `./gradlew.bat clean test shadowJar --rerun-tasks --no-build-cache; git diff --check`

Expected: BUILD SUCCESSFUL, no whitespace errors, exactly one `build/libs/blockstock-0.1.0-SNAPSHOT-all.jar`.

- [ ] **Step 5: Run actual Paper/Vault/EssentialsX smoke test**

Gracefully stop the current local Paper process, start `gradlew.bat runServer --console=plain`, wait for BlockStock ready and Vault → Essentials hook, then verify the pre-existing BlockStock database migrates V003 without changing V001/V002 checksums. Use `Aoozzz` only after the user is informed of restart. Exercise `/company` help and a controlled creation/insufficient-funds test; do not claim third-party asset or IPO player acceptance without an installed verified adapter.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/cn/blockeco/exchange/BlockecoPlugin.java README.md
git commit -m "docs: document company finance and ipo operations"
```

## Self-review

- Spec coverage: Task 1 establishes the additive schema/domain invariants; Task 2 makes company cash externally backed and recoverable; Task 3 changes real capital/initial holdings; Task 4 covers provider-neutral asset binding and the 12-hour/2-day/five-times-capital primary offering; Task 5 covers startup, operations and real Paper verification. Trading, charts, monthly reports, dividends, specific external adapters and delisting are deliberately separate future plans from the approved spec.
- Placeholder scan: each task names files, interfaces, test cases, commands and expected output; no unspecified implementation/test step remains.
- Type consistency: Task 2 supplies the capitalization/escrow interfaces consumed by Task 3 and Task 4; Task 1 domain values and V003 schema underpin all repositories; Task 5 wires the same services without an alternate money path.
