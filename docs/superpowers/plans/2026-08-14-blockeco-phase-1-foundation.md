# Blockeco Phase 1 Core Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a loadable Paper plugin that safely registers persistent player companies, funds their internal treasuries through Vault, records audit events, and exposes basic `/company` commands.

**Architecture:** Keep the company and money rules in a Bukkit-free domain module, expose repository/economy/clock ports in the application layer, and implement Paper, Vault, and SQLite at the edge. Company registration uses a persisted saga plus compensating refund because Vault and SQL cannot share one atomic transaction.

**Tech Stack:** Java 21, Paper API 1.21.4, Gradle Kotlin DSL, Shadow, VaultAPI 1.7, SQLite JDBC, HikariCP, JUnit 5, AssertJ, Mockito.

## Global Constraints

- The deliverable is a server-only Paper plugin; clients install nothing.
- Compile to Java 21 bytecode and use no NMS or CraftBukkit internals.
- Store all monetary values as signed `long` minor units; convert to `double` only inside the Vault adapter.
- Initial companies have exactly 1,000 shares and choose one immutable dividend rate: 3,000, 5,000, or 7,000 basis points.
- The founder cannot withdraw treasury funds directly.
- Every money-affecting action and state transition writes an audit event.
- Database work runs off the server thread; Bukkit and Vault calls run on the server thread.
- A failed registration must either refund the complete withdrawal or leave a visible `REFUND_REQUIRED` saga for administrator recovery.

---

## File Map

```text
settings.gradle.kts                         Gradle project identity
build.gradle.kts                            Java/Paper/dependency/test/shadow configuration
gradle.properties                           Reproducible Gradle defaults
src/main/resources/plugin.yml               Paper entry point, commands, permissions, Vault dependency
src/main/resources/config.yml               Currency scale, registration fee, minimum capital, database path
src/main/resources/db/migration/V001.sql    Company, saga, and audit schema
src/main/java/cn/blockeco/exchange/BlockecoPlugin.java
                                            Lifecycle and dependency composition only
src/main/java/cn/blockeco/exchange/domain/money/Money.java
                                            Integer money arithmetic and boundary validation
src/main/java/cn/blockeco/exchange/domain/company/Company.java
src/main/java/cn/blockeco/exchange/domain/company/CompanyId.java
src/main/java/cn/blockeco/exchange/domain/company/CompanyStatus.java
src/main/java/cn/blockeco/exchange/domain/company/DividendRate.java
                                            Company aggregate and immutable creation rules
src/main/java/cn/blockeco/exchange/domain/audit/AuditEvent.java
src/main/java/cn/blockeco/exchange/domain/registration/RegistrationSaga.java
src/main/java/cn/blockeco/exchange/domain/registration/RegistrationSagaState.java
                                            Durable registration and audit records
src/main/java/cn/blockeco/exchange/application/CompanyRegistrationService.java
src/main/java/cn/blockeco/exchange/application/CompanyQueryService.java
src/main/java/cn/blockeco/exchange/application/RegistrationRequest.java
src/main/java/cn/blockeco/exchange/application/RegistrationResult.java
                                            Use cases with no Bukkit types
src/main/java/cn/blockeco/exchange/ports/CompanyRepository.java
src/main/java/cn/blockeco/exchange/ports/RegistrationSagaRepository.java
src/main/java/cn/blockeco/exchange/ports/AuditLog.java
src/main/java/cn/blockeco/exchange/ports/EconomyGateway.java
src/main/java/cn/blockeco/exchange/ports/AppClock.java
src/main/java/cn/blockeco/exchange/ports/TransactionRunner.java
src/main/java/cn/blockeco/exchange/ports/MainThreadExecutor.java
                                            Application boundaries
src/main/java/cn/blockeco/exchange/infrastructure/sql/Database.java
src/main/java/cn/blockeco/exchange/infrastructure/sql/SqlCompanyRepository.java
src/main/java/cn/blockeco/exchange/infrastructure/sql/SqlRegistrationSagaRepository.java
src/main/java/cn/blockeco/exchange/infrastructure/sql/SqlAuditLog.java
                                            SQLite implementations
src/main/java/cn/blockeco/exchange/infrastructure/vault/VaultEconomyGateway.java
                                            Vault-only money conversion
src/main/java/cn/blockeco/exchange/paper/CompanyCommand.java
src/main/java/cn/blockeco/exchange/paper/PaperMainThread.java
src/main/java/cn/blockeco/exchange/paper/Messages.java
                                            Command parsing and server-thread bridge
src/test/java/cn/blockeco/exchange/domain/money/MoneyTest.java
src/test/java/cn/blockeco/exchange/domain/company/CompanyTest.java
src/test/java/cn/blockeco/exchange/application/CompanyRegistrationServiceTest.java
src/test/java/cn/blockeco/exchange/infrastructure/sql/SqlCompanyRepositoryTest.java
src/test/java/cn/blockeco/exchange/infrastructure/sql/MigrationTest.java
                                            Unit and SQLite integration tests
README.md                                   Installation, commands, config, recovery procedure
```

### Task 1: Build Skeleton and Money Domain

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`
- Create: `src/main/resources/plugin.yml`
- Create: `src/main/resources/config.yml`
- Create: `src/main/java/cn/blockeco/exchange/domain/money/Money.java`
- Test: `src/test/java/cn/blockeco/exchange/domain/money/MoneyTest.java`

**Interfaces:**
- Produces: `Money.ofMinor(long)`, `Money.zero()`, `Money.plus(Money)`, `Money.minus(Money)`, `Money.toMajor(int)`, and `Money.fromMajor(BigDecimal, int)`.

- [ ] **Step 1: Initialize version control and Gradle wrapper**

Run:

```powershell
git init
gradle wrapper --gradle-version 8.14.3
```

Expected: `.git/`, `gradlew`, `gradlew.bat`, and `gradle/wrapper/` exist.

- [ ] **Step 2: Configure the Java 21 Paper project**

Create `settings.gradle.kts`:

```kotlin
rootProject.name = "blockeco-exchange"
```

Create `build.gradle.kts` with `java`, `com.gradleup.shadow` version `8.3.8`, Maven Central, Paper's public repository, and JitPack. Pin `paper-api:1.21.4-R0.1-SNAPSHOT` and `VaultAPI:1.7` as `compileOnly`; add `sqlite-jdbc:3.50.3.0` and `HikariCP:6.3.0` as implementation dependencies; add JUnit Jupiter `5.13.4`, AssertJ `3.27.4`, and Mockito `5.18.0` for tests. Configure the Java toolchain and release to 21, `useJUnitPlatform()`, UTF-8 compilation, `processResources` expansion for the plugin version, and relocation of `com.zaxxer.hikari` and `org.sqlite` under `cn.blockeco.exchange.libs` in the shadow JAR. Use Paper's SLF4J API instead of shading or relocating a second copy.

Create `gradle.properties`:

```properties
org.gradle.configuration-cache=true
org.gradle.parallel=true
org.gradle.caching=true
```

- [ ] **Step 3: Declare plugin metadata and safe defaults**

Create `plugin.yml`:

```yaml
name: BlockecoExchange
version: '${version}'
main: cn.blockeco.exchange.BlockecoPlugin
api-version: '1.21'
softdepend: [Vault, VaultUnlocked]
commands:
  company:
    description: Manage Blockeco companies
    usage: /company <create|info|recovery>
permissions:
  blockeco.company.create:
    default: true
  blockeco.company.info:
    default: true
  blockeco.admin.recovery:
    default: op
```

Create `config.yml`:

```yaml
currency:
  scale: 2
company:
  registration-fee: '1000.00'
  minimum-capital: '10000.00'
  initial-shares: 1000
  allowed-dividend-percent: [30, 50, 70]
database:
  file: blockeco.db
  pool-size: 2
```

- [ ] **Step 4: Write failing money tests**

Test exact conversion, overflow, negative rejection for debits, and scale mismatch:

```java
@Test void converts_major_units_without_binary_rounding() {
    assertThat(Money.fromMajor(new BigDecimal("10.25"), 2).minorUnits()).isEqualTo(1025L);
}

@Test void rejects_more_fraction_digits_than_currency_scale() {
    assertThatThrownBy(() -> Money.fromMajor(new BigDecimal("1.001"), 2))
        .isInstanceOf(ArithmeticException.class);
}

@Test void detects_addition_overflow() {
    assertThatThrownBy(() -> Money.ofMinor(Long.MAX_VALUE).plus(Money.ofMinor(1)))
        .isInstanceOf(ArithmeticException.class);
}
```

- [ ] **Step 5: Run the test and verify failure**

Run: `./gradlew test --tests '*MoneyTest'`

Expected: compilation fails because `Money` does not exist.

- [ ] **Step 6: Implement immutable money arithmetic**

Implement `Money` as a record using `Math.addExact`, `Math.subtractExact`, `BigDecimal.movePointRight(scale).longValueExact()`, and `BigDecimal.valueOf(minorUnits, scale)`. Reject negative values in `requireNonNegative(String field)` rather than in the record constructor so subtraction tests and ledger deltas can represent signed values.

- [ ] **Step 7: Run tests and commit**

Run: `./gradlew test --tests '*MoneyTest'`

Expected: all `MoneyTest` tests pass.

```powershell
git add settings.gradle.kts build.gradle.kts gradle.properties gradlew gradlew.bat gradle src/main/resources src/main/java/cn/blockeco/exchange/domain/money src/test/java/cn/blockeco/exchange/domain/money
git commit -m "build: bootstrap Paper plugin and money domain"
```

### Task 2: Company Aggregate and Registration Rules

**Files:**
- Create: `src/main/java/cn/blockeco/exchange/domain/company/CompanyId.java`
- Create: `src/main/java/cn/blockeco/exchange/domain/company/CompanyStatus.java`
- Create: `src/main/java/cn/blockeco/exchange/domain/company/DividendRate.java`
- Create: `src/main/java/cn/blockeco/exchange/domain/company/Company.java`
- Test: `src/test/java/cn/blockeco/exchange/domain/company/CompanyTest.java`

**Interfaces:**
- Consumes: `Money` from Task 1.
- Produces: `Company.register(CompanyId, String, UUID, Money, DividendRate, Instant)`, `Company.normalizedName()`, `Company.treasury()`, and `CompanyStatus.PENDING_ASSET_BINDING`.

- [ ] **Step 1: Write failing aggregate tests**

Cover normalization, name length, treasury, fixed initial shares, dividend choices, and founder identity:

```java
@Test void registers_company_with_fixed_share_count_and_locked_dividend_rate() {
    Company company = Company.register(
        new CompanyId(UUID.randomUUID()), "  Red Stone 工业  ", FOUNDER,
        Money.ofMinor(1_000_000), DividendRate.FIFTY, NOW);

    assertThat(company.displayName()).isEqualTo("Red Stone 工业");
    assertThat(company.normalizedName()).isEqualTo("red stone 工业");
    assertThat(company.totalShares()).isEqualTo(1000L);
    assertThat(company.dividendRate().basisPoints()).isEqualTo(5000);
    assertThat(company.status()).isEqualTo(CompanyStatus.PENDING_ASSET_BINDING);
}
```

- [ ] **Step 2: Run the test and verify failure**

Run: `./gradlew test --tests '*CompanyTest'`

Expected: compilation fails because company domain types do not exist.

- [ ] **Step 3: Implement domain types**

Use a `CompanyId(UUID value)` record, a `CompanyStatus` enum containing `PENDING_ASSET_BINDING`, `LISTED`, `DELISTING`, `LIQUIDATING`, and `DELISTED`, and a `DividendRate` enum with `THIRTY(3000)`, `FIFTY(5000)`, `SEVENTY(7000)` plus `fromPercent(int)`. `Company.register` trims and collapses whitespace, lowercases with `Locale.ROOT`, permits 2--24 Unicode code points, requires nonzero founder UUID and nonnegative capital, sets `totalShares=1000`, and stores immutable creation time.

- [ ] **Step 4: Run all domain tests and commit**

Run: `./gradlew test --tests 'cn.blockeco.exchange.domain.*'`

Expected: money and company tests pass.

```powershell
git add src/main/java/cn/blockeco/exchange/domain/company src/test/java/cn/blockeco/exchange/domain/company
git commit -m "feat: define company registration rules"
```

### Task 3: SQLite Schema and Repositories

**Files:**
- Create: `src/main/resources/db/migration/V001.sql`
- Create: `src/main/java/cn/blockeco/exchange/ports/CompanyRepository.java`
- Create: `src/main/java/cn/blockeco/exchange/ports/RegistrationSagaRepository.java`
- Create: `src/main/java/cn/blockeco/exchange/ports/AuditLog.java`
- Create: `src/main/java/cn/blockeco/exchange/ports/TransactionRunner.java`
- Create: `src/main/java/cn/blockeco/exchange/domain/audit/AuditEvent.java`
- Create: `src/main/java/cn/blockeco/exchange/domain/registration/RegistrationSaga.java`
- Create: `src/main/java/cn/blockeco/exchange/domain/registration/RegistrationSagaState.java`
- Create: `src/main/java/cn/blockeco/exchange/infrastructure/sql/Database.java`
- Create: `src/main/java/cn/blockeco/exchange/infrastructure/sql/SqlCompanyRepository.java`
- Create: `src/main/java/cn/blockeco/exchange/infrastructure/sql/SqlRegistrationSagaRepository.java`
- Create: `src/main/java/cn/blockeco/exchange/infrastructure/sql/SqlAuditLog.java`
- Test: `src/test/java/cn/blockeco/exchange/infrastructure/sql/MigrationTest.java`
- Test: `src/test/java/cn/blockeco/exchange/infrastructure/sql/SqlCompanyRepositoryTest.java`

**Interfaces:**
- Consumes: `Company`, `CompanyId`, `Money`, `Instant`.
- Produces: `CompanyRepository.insert(Connection, Company)`, `findById(CompanyId)`, `findByNormalizedName(String)`, `RegistrationSagaRepository.transition(Connection, UUID, RegistrationSagaState, String)`, `AuditLog.append(Connection, AuditEvent)`, and `TransactionRunner.inTransaction(SqlWork<T>)`.

- [ ] **Step 1: Write the migration contract test**

Open a temporary SQLite database, run `Database.migrate()`, and assert that `schema_history`, `companies`, `registration_sagas`, and `audit_events` exist. Run migration twice and assert exactly one `V001` history row.

- [ ] **Step 2: Define the initial schema**

`V001.sql` creates:

```sql
CREATE TABLE companies (
  id TEXT PRIMARY KEY,
  normalized_name TEXT NOT NULL UNIQUE,
  display_name TEXT NOT NULL,
  founder_uuid TEXT NOT NULL,
  status TEXT NOT NULL,
  treasury_minor INTEGER NOT NULL CHECK (treasury_minor >= 0),
  total_shares INTEGER NOT NULL CHECK (total_shares >= 1000),
  dividend_basis_points INTEGER NOT NULL CHECK (dividend_basis_points IN (3000, 5000, 7000)),
  created_at TEXT NOT NULL,
  version INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE registration_sagas (
  id TEXT PRIMARY KEY,
  founder_uuid TEXT NOT NULL,
  company_normalized_name TEXT NOT NULL,
  total_withdrawal_minor INTEGER NOT NULL,
  state TEXT NOT NULL CHECK (state IN ('PREPARED','WITHDRAWN','COMPLETED','REFUND_REQUIRED','REFUNDED','AMBIGUOUS')),
  error_message TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE audit_events (
  sequence INTEGER PRIMARY KEY AUTOINCREMENT,
  event_id TEXT NOT NULL UNIQUE,
  company_id TEXT,
  actor_uuid TEXT,
  event_type TEXT NOT NULL,
  payload_json TEXT NOT NULL,
  occurred_at TEXT NOT NULL
);
```

The migrator reads classpath SQL, enables `PRAGMA foreign_keys=ON`, wraps each migration in a transaction, and records checksum plus version in `schema_history`.

- [ ] **Step 3: Run migration test and verify it passes**

Run: `./gradlew test --tests '*MigrationTest'`

Expected: the second migration is a no-op and the four application tables exist.

- [ ] **Step 4: Write repository round-trip and uniqueness tests**

Insert a company, read it by ID and normalized name, and assert every field matches. Insert a second company with the same normalized name and assert the repository returns a typed `DuplicateCompanyNameException` rather than leaking `SQLException`.

- [ ] **Step 5: Implement repositories and audit log**

Use prepared statements only. Map UUID and Instant as canonical strings, enums by stable names, and money as `long`. Define `AuditEvent` as a record containing event ID, optional company/actor IDs, event type, a `Map<String, Object>` payload, and time. Define `RegistrationSaga` with all columns from `registration_sagas`, and use a matching `RegistrationSagaState` enum. `SqlAuditLog.append(Connection, AuditEvent)` inserts JSON generated by a small deterministic encoder for string/number/boolean fields; do not add a general JSON library in this phase. `Database` implements `TransactionRunner`, commits on success, rolls back on every exception, and restores auto-commit before returning the connection.

- [ ] **Step 6: Run SQL tests and commit**

Run: `./gradlew test --tests '*sql*'`

Expected: migration, round-trip, duplicate-name, and audit append tests pass.

```powershell
git add src/main/resources/db src/main/java/cn/blockeco/exchange/ports src/main/java/cn/blockeco/exchange/infrastructure/sql src/test/java/cn/blockeco/exchange/infrastructure/sql
git commit -m "feat: persist companies and registration sagas"
```

### Task 4: Vault Gateway and Compensating Registration Saga

**Files:**
- Create: `src/main/java/cn/blockeco/exchange/ports/EconomyGateway.java`
- Create: `src/main/java/cn/blockeco/exchange/ports/AppClock.java`
- Create: `src/main/java/cn/blockeco/exchange/ports/MainThreadExecutor.java`
- Create: `src/main/java/cn/blockeco/exchange/application/RegistrationRequest.java`
- Create: `src/main/java/cn/blockeco/exchange/application/RegistrationResult.java`
- Create: `src/main/java/cn/blockeco/exchange/application/CompanyRegistrationService.java`
- Create: `src/main/java/cn/blockeco/exchange/infrastructure/vault/VaultEconomyGateway.java`
- Test: `src/test/java/cn/blockeco/exchange/application/CompanyRegistrationServiceTest.java`

**Interfaces:**
- Consumes: Task 2 aggregate and Task 3 repositories.
- Produces: `CompletionStage<RegistrationResult> register(RegistrationRequest)`, `EconomyGateway.withdraw(UUID, Money)`, `EconomyGateway.deposit(UUID, Money)`, and `MainThreadExecutor.submit(Supplier<T>)`.

- [ ] **Step 1: Write service failure-first tests**

Use a nested `RegistrationFixture` with in-memory repositories, direct executors, and a programmable economy fake to prove these exact cases. The success test must contain these assertions:

```java
@Test void successful_registration_puts_only_capital_in_treasury() {
    RegistrationFixture fixture = RegistrationFixture.standard();
    RegistrationResult result = fixture.register("Red Stone", 50);

    assertThat(result.status()).isEqualTo(RegistrationResult.Status.SUCCESS);
    assertThat(fixture.economy().withdrawn()).isEqualTo(Money.ofMinor(1_100_000));
    assertThat(fixture.savedCompany().treasury()).isEqualTo(Money.ofMinor(1_000_000));
    assertThat(fixture.savedSaga().state()).isEqualTo(RegistrationSagaState.COMPLETED);
}
```

The same fixture adds four complete tests: insufficient funds creates no company; duplicate name performs zero economy calls; SQL failure after withdrawal deposits the full 1,100,000 minor units; failed compensation ends in `REFUND_REQUIRED`. Define `RegistrationResult.Status` as `SUCCESS`, `DUPLICATE_NAME`, `INSUFFICIENT_FUNDS`, `PROVIDER_FAILURE`, `REFUNDED_AFTER_FAILURE`, or `RECOVERY_REQUIRED`, so every command outcome is explicit.

- [ ] **Step 2: Define explicit gateway outcomes**

`EconomyGateway.Result` contains `SUCCESS`, `INSUFFICIENT_FUNDS`, `PROVIDER_FAILURE`; each result carries the provider message. The Vault implementation rejects amounts that cannot round-trip at configured currency scale, converts using `money.toMajor(scale).doubleValue()`, calls the registered Vault provider with `OfflinePlayer`, and accepts only `EconomyResponse.transactionSuccess()`.

- [ ] **Step 3: Implement the saga state machine**

`CompanyRegistrationService.register` performs:

1. Validate request and check normalized-name uniqueness asynchronously.
2. Persist `PREPARED` saga and audit `COMPANY_REGISTRATION_PREPARED`.
3. Switch to Paper main thread and withdraw fee plus capital through Vault.
4. Persist `WITHDRAWN`; then in one SQL transaction insert the company, append `COMPANY_REGISTERED`, and mark `COMPLETED`.
5. If step 4 fails, switch to main thread and refund the full withdrawal. Mark `REFUNDED` on success or `REFUND_REQUIRED` on failure.
6. If the process terminates between the Vault call and persisted `WITHDRAWN`, startup recovery marks the stale saga `AMBIGUOUS`; it never guesses whether money moved.

- [ ] **Step 4: Run application tests**

Run: `./gradlew test --tests '*CompanyRegistrationServiceTest'`

Expected: all five money-safety scenarios pass.

- [ ] **Step 5: Implement and test Vault adapter mapping**

Mock Vault's `Economy` interface. Verify successful withdrawal/deposit, insufficient funds, provider error, a missing provider, and non-finite values are mapped to explicit outcomes without throwing into command handlers.

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/cn/blockeco/exchange/application src/main/java/cn/blockeco/exchange/ports src/main/java/cn/blockeco/exchange/infrastructure/vault src/test/java/cn/blockeco/exchange/application
git commit -m "feat: register funded companies with compensating refunds"
```

### Task 5: Paper Lifecycle and Company Commands

**Files:**
- Create: `src/main/java/cn/blockeco/exchange/BlockecoPlugin.java`
- Create: `src/main/java/cn/blockeco/exchange/application/CompanyQueryService.java`
- Create: `src/main/java/cn/blockeco/exchange/paper/PaperMainThread.java`
- Create: `src/main/java/cn/blockeco/exchange/paper/CompanyCommand.java`
- Create: `src/main/java/cn/blockeco/exchange/paper/Messages.java`
- Test: `src/test/java/cn/blockeco/exchange/paper/CompanyCommandTest.java`

**Interfaces:**
- Consumes: `CompanyRegistrationService`, `CompanyQueryService`, and saga recovery queries.
- Produces: `/company create <name> <30|50|70>`, `/company info <name>`, and `/company recovery list`.

- [ ] **Step 1: Write command parser tests without a server**

Separate parsing from Bukkit dispatch. Verify quoted/multiword names, invalid dividend values, missing permission, duplicate submissions from one player, and console rejection for player-only create.

- [ ] **Step 2: Implement lifecycle composition**

`BlockecoPlugin.onEnable` saves config, validates currency scale and positive fees, creates the plugin data folder, migrates SQLite, resolves the Vault provider, constructs repositories/services, registers the command, scans stale sagas, and logs one summary line. If migration or Vault resolution fails, disable the plugin with a precise error. `onDisable` rejects new registrations and closes Hikari within five seconds.

- [ ] **Step 3: Implement nonblocking command behavior**

`/company create` sends an immediate “registration processing” message, calls the async service, then returns to the main thread to display success, insufficient funds, duplicate name, refund completed, or administrator recovery required. Guard each player UUID with one in-flight request. Use Adventure components; keep all user strings in a `Messages` object loaded from config for later localization.

- [ ] **Step 4: Implement safe recovery visibility**

`/company recovery list` requires `blockeco.admin.recovery` and lists saga ID, player UUID, amount, state, time, and error. Phase 1 deliberately exposes no automatic force-refund command for `AMBIGUOUS` records because Vault cannot prove whether the interrupted withdrawal completed.

- [ ] **Step 5: Run tests and build the plugin JAR**

Run:

```powershell
./gradlew clean test shadowJar
```

Expected: tests pass and exactly one deployable JAR exists under `build/libs/` without `-plain` in the filename.

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/cn/blockeco/exchange/BlockecoPlugin.java src/main/java/cn/blockeco/exchange/paper src/main/java/cn/blockeco/exchange/application/CompanyQueryService.java src/test/java/cn/blockeco/exchange/paper
git commit -m "feat: expose persistent company commands on Paper"
```

### Task 6: Real-Server Smoke Test and Operations Guide

**Files:**
- Create: `README.md`
- Modify: `build.gradle.kts`
- Test: `src/test/java/cn/blockeco/exchange/architecture/DependencyRuleTest.java`

**Interfaces:**
- Consumes: completed Phase 1 plugin.
- Produces: repeatable test-server verification and operator recovery instructions.

- [ ] **Step 1: Add architecture boundary tests**

Scan compiled classes and fail if anything under `.domain.` or `.application.` references packages beginning `org.bukkit`, `io.papermc`, `net.milkbowl`, or `.infrastructure.`. This keeps later stock logic unit-testable without a Minecraft server.

- [ ] **Step 2: Add a Paper run task pinned to the compatibility baseline**

Configure `xyz.jpenilla.run-paper` version `3.0.2` with `minecraftVersion("1.21.4")`. Put Vault and a Vault-compatible test economy plugin in `run/plugins/` manually; do not commit third-party JARs.

- [ ] **Step 3: Perform the smoke-test script**

Run `./gradlew runServer`, then verify in the server console/player session:

1. Plugin enables with schema version V001 and detects the economy provider.
2. `/company create "Red Stone" 50` deducts fee plus capital once.
3. `/company info "Red Stone"` reports 1,000 shares, 50% dividend rate, capital-only treasury, and `PENDING_ASSET_BINDING`.
4. Repeating the name does not withdraw money.
5. Restarting the server preserves the company and audit data.
6. Stopping during a deliberately delayed registration leaves a visible recovery saga rather than silently losing the operation.

- [ ] **Step 4: Document installation and recovery**

README must state: Paper 1.21.4+ and Java 21+ baseline, Vault plus an economy provider requirement, install path, configuration keys, three commands, permission nodes, database backup instructions, and the difference between `REFUND_REQUIRED` and `AMBIGUOUS`. For ambiguous cases, instruct operators to inspect the audit log and economy-provider history before manually compensating; never tell them to auto-refund blindly.

- [ ] **Step 5: Run final verification**

Run:

```powershell
./gradlew clean test shadowJar
git status --short
```

Expected: all tests pass, one deployable shadow JAR is produced, and only intentional source/documentation files appear in Git status.

- [ ] **Step 6: Commit**

```powershell
git add build.gradle.kts README.md src/test/java/cn/blockeco/exchange/architecture
git commit -m "docs: add Paper smoke test and recovery runbook"
```

---

## Phase 1 Acceptance Checklist

- A clean Paper 1.21.4 server with Vault and an economy provider loads the plugin without client installation.
- A player can create and query one persistent company with an immutable dividend tier and 1,000 initial shares.
- Registration fee and capital are withdrawn exactly once in normal operation; only capital enters company treasury.
- Duplicate name, insufficient funds, provider failure, SQL failure, refund failure, restart, and ambiguous crash-window behavior are covered by tests or the smoke procedure.
- No company-treasury withdrawal command exists.
- Every registration transition has an audit record, and unresolved sagas are visible to administrators.
- `./gradlew clean test shadowJar` passes.
