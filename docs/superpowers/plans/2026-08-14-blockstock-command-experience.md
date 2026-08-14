# BlockStock 命令体验实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将现有 BlockecoExchange 安全更名为 BlockStock，并提供按权限过滤、可用原版 Tab 菜单选择的 `/company` 详细中文帮助与补全。

**Architecture:** `PluginDataDirectoryMigrator` 在 Paper 创建新默认配置前迁移整个旧插件目录，确保 SQLite、配置和 WAL 文件作为一个目录保留。`CompanyCommand` 负责逐项鉴权和发送动态帮助，独立的 `CompanyTabCompleter` 只用命令参数与权限生成常量候选，不读取数据库；`Messages` 集中生成中文文案。

**Tech Stack:** Java 21、Paper 1.21.4 API、Vault、SQLite/HikariCP、JUnit 5、Mockito、AssertJ、Gradle Shadow、run-paper 3.0.2。

## Global Constraints

- 插件必须保持纯服务端：原版 Java 客户端可进入，不要求安装客户端模组或插件。
- 插件展示名和唯一 Shadow JAR 基名为 `BlockStock`/`blockstock`；Java 包名 `cn.blockeco.exchange`、`/company`、既有数据库表、审计事件、配置键和具体权限节点不改。
- Paper 版本固定 `1.21.4`，Java toolchain/release 固定 `21`；不新增运行时依赖。
- 仅当 `plugins/BlockStock` 不存在且 `plugins/BlockecoExchange` 存在时，才在 `saveDefaultConfig()` 之前移动完整旧目录；目标存在时绝不覆盖、合并或删除旧目录；移动失败安全停用。
- 根命令与 Tab 候选必须按实际权限过滤；补全不得查询 SQLite 或暴露公司名称。
- 帮助中的创建费、最低注册资本、合计、初始股数和分红档位必须从当前 `config.yml` 构造，不能写死默认数值；创建解析和 Tab 必须使用同一个已验证分红档位集合。
- 所有新增行为先写 RED 测试，再做最小 GREEN 实现；每个任务后独立审查与提交。

---

## File Structure

- `settings.gradle.kts` — 将归档项目名改为 `blockstock`。
- `src/main/resources/plugin.yml` — 插件名、中文描述、详细静态 usage 和权限组声明。
- `src/main/java/cn/blockeco/exchange/paper/PluginDataDirectoryMigrator.java` — 无 Bukkit 依赖的旧目录迁移决策与可测试的目录移动端口。
- `src/main/java/cn/blockeco/exchange/BlockecoPlugin.java` — 在加载默认配置前迁移目录、注册补全器、将当前配置规则传入命令帮助。
- `src/main/java/cn/blockeco/exchange/paper/CompanyCreationRules.java` — 不可变的创建费、注册资本、货币精度、初始股数、分红档位快照，供帮助展示。
- `src/main/java/cn/blockeco/exchange/paper/CompanyTabCompleter.java` — 无数据库的层级候选、权限筛选与前缀筛选。
- `src/main/java/cn/blockeco/exchange/paper/CompanyCommand.java` — 空命令和不完整子命令的详细帮助、运行时鉴权。
- `src/main/java/cn/blockeco/exchange/paper/Messages.java`、`src/main/resources/config.yml` — 可配置中文帮助行与默认中文 fallback。
- `src/test/java/cn/blockeco/exchange/paper/PluginDataDirectoryMigratorTest.java` — 文件系统迁移和失败语义。
- `src/test/java/cn/blockeco/exchange/paper/CompanyTabCompleterTest.java` — Paper Tab 菜单行为、权限隔离与无 SQL 依赖。
- `src/test/java/cn/blockeco/exchange/paper/CompanyCommandTest.java`、`MessagesTest.java` — 动态中文帮助与不完整命令提示。
- `README.md`、`run/server.properties`（忽略的本地文件）— BlockStock 运维名称和本地 MOTD。

### Task 1: BlockStock 品牌与安全数据目录迁移

**Files:**
- Create: `src/main/java/cn/blockeco/exchange/paper/PluginDataDirectoryMigrator.java`
- Create: `src/test/java/cn/blockeco/exchange/paper/PluginDataDirectoryMigratorTest.java`
- Modify: `settings.gradle.kts`
- Modify: `src/main/resources/plugin.yml`
- Modify: `src/main/java/cn/blockeco/exchange/BlockecoPlugin.java`
- Modify: `README.md`

**Interfaces:**
- Produces: `PluginDataDirectoryMigrator.migrate(Path pluginsDirectory): MigrationResult`, where `MigrationResult` is `MIGRATED`, `NO_LEGACY_DIRECTORY`, or `SKIPPED_TARGET_EXISTS`.
- Produces: `PluginDataDirectoryMigrator(DirectoryMover mover)`, where `DirectoryMover.move(Path source, Path target) throws IOException`; production passes `Files::move` and tests can simulate failure.
- Consumes: `JavaPlugin.getDataFolder()` only to derive its parent, before `saveDefaultConfig()`.

- [ ] **Step 1: Write failing migration tests**

```java
@TempDir Path plugins;

@Test void moves_complete_legacy_directory_when_new_directory_absent() throws Exception {
    Path legacy = Files.createDirectories(plugins.resolve("BlockecoExchange"));
    Files.writeString(legacy.resolve("blockeco.db"), "sqlite-data");
    assertThat(new PluginDataDirectoryMigrator(Files::move).migrate(plugins))
        .isEqualTo(MigrationResult.MIGRATED);
    assertThat(plugins.resolve("BlockStock/blockeco.db")).hasContent("sqlite-data");
    assertThat(legacy).doesNotExist();
}

@Test void preserves_both_directories_when_target_already_exists() throws Exception { /* assert SKIPPED_TARGET_EXISTS and both sentinel files remain */ }
@Test void propagates_directory_move_failure_without_creating_target() { /* injected mover throws IOException; assert IOException and absent target */ }
```

- [ ] **Step 2: Run migration test to verify RED**

Run: `./gradlew.bat test --tests cn.blockeco.exchange.paper.PluginDataDirectoryMigratorTest`

Expected: FAIL because `PluginDataDirectoryMigrator` and `MigrationResult` do not exist.

- [ ] **Step 3: Implement the smallest migration boundary**

```java
public MigrationResult migrate(Path pluginsDirectory) throws IOException {
    Path target = pluginsDirectory.resolve("BlockStock");
    if (Files.exists(target)) return MigrationResult.SKIPPED_TARGET_EXISTS;
    Path legacy = pluginsDirectory.resolve("BlockecoExchange");
    if (!Files.exists(legacy)) return MigrationResult.NO_LEGACY_DIRECTORY;
    mover.move(legacy, target);
    return MigrationResult.MIGRATED;
}
```

In `BlockecoPlugin.onEnable()`, call `migrator.migrate(getDataFolder().toPath().getParent())` before `saveDefaultConfig()`. Log `MIGRATED` and `SKIPPED_TARGET_EXISTS`; catch `IOException`, call `failEnable("BlockStock 数据目录迁移失败: ...")`, and return before any configuration or database work. Change `rootProject.name` to `blockstock`, `plugin.yml` `name` to `BlockStock`, and all user-facing Blockeco product strings and README branding to BlockStock without renaming Java packages, `/company`, table names, event names, config keys, or concrete permission nodes.

- [ ] **Step 4: Run focused tests to verify GREEN**

Run: `./gradlew.bat test --tests cn.blockeco.exchange.paper.PluginDataDirectoryMigratorTest --tests cn.blockeco.exchange.DependencyRuleTest`

Expected: PASS. `build/libs` contains no output yet because this is a test-only verification.

- [ ] **Step 5: Build the deployable JAR and inspect metadata**

Run: `./gradlew.bat shadowJar; jar tf build/libs/blockstock-0.1.0-SNAPSHOT-all.jar | Select-String 'plugin.yml'`

Expected: one `blockstock-0.1.0-SNAPSHOT-all.jar`; extracted `plugin.yml` identifies `name: BlockStock`.

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts README.md src/main/resources/plugin.yml src/main/java/cn/blockeco/exchange/BlockecoPlugin.java src/main/java/cn/blockeco/exchange/paper/PluginDataDirectoryMigrator.java src/test/java/cn/blockeco/exchange/paper/PluginDataDirectoryMigratorTest.java
git commit -m "feat: rename plugin to BlockStock safely"
```

### Task 2: 动态详细帮助与原版式权限 Tab 补全

**Files:**
- Create: `src/main/java/cn/blockeco/exchange/paper/CompanyCreationRules.java`
- Create: `src/main/java/cn/blockeco/exchange/paper/CompanyTabCompleter.java`
- Create: `src/test/java/cn/blockeco/exchange/paper/CompanyTabCompleterTest.java`
- Modify: `src/main/java/cn/blockeco/exchange/paper/CompanyCommand.java`
- Modify: `src/main/java/cn/blockeco/exchange/paper/Messages.java`
- Modify: `src/main/java/cn/blockeco/exchange/BlockecoPlugin.java`
- Modify: `src/main/resources/config.yml`
- Modify: `src/main/resources/plugin.yml`
- Modify: `src/test/java/cn/blockeco/exchange/paper/CompanyCommandTest.java`
- Modify: `src/test/java/cn/blockeco/exchange/paper/MessagesTest.java`

**Interfaces:**
- Produces: `CompanyCreationRules(Money registrationFee, Money minimumCapital, int scale, int initialShares, List<Integer> allowedDividendPercent)` with `totalRequired()` and formatted major-unit values; its constructor rejects empty, duplicated or non-`1..100` dividend values.
- Produces: `CompanyTabCompleter implements TabCompleter`; `onTabComplete(CommandSender, Command, String, String[])` always returns a non-null immutable candidate list.
- Consumes: `CompanyCommand(CompanyRegistrationService, CompanyQueryService, Messages, MainThreadExecutor, CompanyCreationRules)` and existing concrete permissions `blockeco.company.create`, `blockeco.company.info`, `blockeco.admin.recovery`.

- [ ] **Step 1: Write failing command help and completion tests**

```java
@Test void root_help_includes_live_create_rules_for_authorized_player() {
    Player player = permittedPlayer("blockeco.company.create", "blockeco.company.info");
    command.onCommand(player, bukkitCommand, "company", new String[0]);
    assertThat(allPlainMessages(player)).contains(
        "/company create <名称> <30|50|70>",
        "创建费：1000.00", "最低注册资本：10000.00", "合计余额：11000.00",
        "初始发行：1000 股", "必须是玩家", "插件完成初始化", "公司名不能重复");
}

@Test void root_completion_shows_only_sender_permitted_commands() {
    assertThat(tab.complete(playerWithCreateAndInfo(), new String[] {""})).containsExactly("create", "info");
    assertThat(tab.complete(opWithAllPermissions(), new String[] {""})).containsExactly("create", "info", "recovery");
}

@Test void dividend_and_recovery_candidates_are_permission_scoped_and_prefix_filtered() {
    assertThat(tab.complete(playerWithCreate(), new String[] {"create", "红石", "5"})).containsExactly("50");
    assertThat(tab.complete(opWithAllPermissions(), new String[] {"recovery", ""})).containsExactly("list");
    assertThat(tab.complete(noPermissions(), new String[] {""})).isEmpty();
}
```

Also assert console does not receive `create`, info does not reveal names, unknown paths return empty lists, `create <name>` is not completed with unrelated values, and a missing `info` name sends the explicit usage instead of returning `false`.

- [ ] **Step 2: Run focused tests to verify RED**

Run: `./gradlew.bat test --tests cn.blockeco.exchange.paper.CompanyCommandTest --tests cn.blockeco.exchange.paper.CompanyTabCompleterTest --tests cn.blockeco.exchange.paper.MessagesTest`

Expected: FAIL because the dynamic rule record/completer/help methods are absent and empty `/company` still returns `false`.

- [ ] **Step 3: Implement the minimal dynamic help model and messages**

```java
public record CompanyCreationRules(Money registrationFee, Money minimumCapital, int scale,
                                   int initialShares, List<Integer> allowedDividendPercent) {
    public Money totalRequired() { return registrationFee.plus(minimumCapital); }
}

public List<Component> companyHelp(boolean canCreate, boolean canInfo, boolean canRecovery,
                                   CompanyCreationRules rules) { /* role-filtered Chinese lines */ }
```

Create the rules snapshot from `currency.scale`, `company.registration-fee`, `company.minimum-capital`, `company.initial-shares`, and `company.allowed-dividend-percent` in `BlockecoPlugin`. Format values using `Money.toMajor(scale).toPlainString()`. Replace the hard-coded `30/50/70` acceptance in `CompanyCommand.parseCreate` with `rules.allowedDividendPercent().contains(percent)`, and add configuration validation that rejects an empty, duplicate or outside-`1..100` dividend list during startup. Add default Chinese config templates for the root, create-rule, info and recovery help lines. `CompanyCommand.onCommand` sends role-filtered help for no arguments, sends targeted usage for incomplete `info` and `recovery`, and never sends administrator text to a sender without recovery permission.

- [ ] **Step 4: Implement the stateless completer and registration**

```java
if (args.length == 1) return filter(List.of("create", "info", "recovery"), args[0], senderPermissions);
if (isCreate(sender, args) && args.length >= 3) return filter(List.of("30", "50", "70"), args[args.length - 1]);
if (isRecoveryAdmin(sender, args) && args.length == 2) return filter(List.of("list"), args[1]);
return List.of();
```

Use `CompanyCreationRules.allowedDividendPercent()` rather than literals in the production completer. Only a `Player` with create permission gets `create`; all candidates are case-insensitive prefix filtered and return `List.copyOf`. Register the same `CompanyTabCompleter` from `installInitializingCommand()` before migration and retain it when `finishEnable()` swaps the executor, so Tab works during startup and ready state. Add `blockeco.player` and `blockeco.admin` parent groups with Chinese descriptions and children in `plugin.yml`, without changing defaults of the concrete permissions.

- [ ] **Step 5: Run focused tests to verify GREEN**

Run: `./gradlew.bat test --tests cn.blockeco.exchange.paper.CompanyCommandTest --tests cn.blockeco.exchange.paper.CompanyTabCompleterTest --tests cn.blockeco.exchange.paper.MessagesTest`

Expected: PASS. Verify all returned candidates are non-null, permission-filtered and database-independent.

- [ ] **Step 6: Run the complete build**

Run: `./gradlew.bat clean test shadowJar --rerun-tasks --no-build-cache`

Expected: BUILD SUCCESSFUL, all tests pass, and exactly one deployable `build/libs/blockstock-0.1.0-SNAPSHOT-all.jar` exists.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/cn/blockeco/exchange/BlockecoPlugin.java src/main/java/cn/blockeco/exchange/paper/CompanyCreationRules.java src/main/java/cn/blockeco/exchange/paper/CompanyTabCompleter.java src/main/java/cn/blockeco/exchange/paper/CompanyCommand.java src/main/java/cn/blockeco/exchange/paper/Messages.java src/main/resources/config.yml src/main/resources/plugin.yml src/test/java/cn/blockeco/exchange/paper/CompanyCommandTest.java src/test/java/cn/blockeco/exchange/paper/CompanyTabCompleterTest.java src/test/java/cn/blockeco/exchange/paper/MessagesTest.java
git commit -m "feat: add guided company command help"
```

### Task 3: 本地 BlockStock 迁移与玩家命令验收

**Files:**
- Modify: `README.md`
- Modify: ignored local `run/server.properties`
- Modify: ignored local `run/plugins/BlockecoExchange/` only through the plugin's one-time directory migration
- Create: `.superpowers/sdd/2026-08-14-blockstock-command-experience/task-3-report.md` (ignored evidence report)

**Interfaces:**
- Consumes: Task 1 `blockstock-0.1.0-SNAPSHOT-all.jar`, automatic `runServer` plugin deployment, existing Vault 1.7.3 and EssentialsX 2.21.0 local run plugins.
- Produces: a running Paper 1.21.4 server on `127.0.0.1:25565` with `plugins/BlockStock` containing the formerly `BlockecoExchange` database/configuration.

- [ ] **Step 1: Record the pre-migration sentinels**

Run: `Get-ChildItem -Force run/plugins/BlockecoExchange; Get-FileHash run/plugins/BlockecoExchange/blockeco.db`

Expected: record whether the existing config/database exists and its hash; do not copy, edit, or delete it manually.

- [ ] **Step 2: Stop the existing local Paper server gracefully**

Use the `cmd.exe` launcher that owns `gradlew.bat runServer`, send `stop`, and wait for `Stopping server`, Hikari shutdown, `Saving players`, and `Saving worlds`. Do not kill the Paper JVM or start a second server on port 25565.

- [ ] **Step 3: Start BlockStock and verify migration**

Run: `Start-Process cmd.exe -ArgumentList '/d','/c','gradlew.bat runServer --console=plain'` from the worktree, redirect stdout/stderr to timestamped ignored log files, then wait for `BlockStock ready`.

Expected: `run/plugins/BlockStock` exists with the previous config/database content, `run/plugins/BlockecoExchange` is absent, Vault hooks Essentials Economy, port `127.0.0.1:25565` is listening, and no BlockStock `ERROR`, `Exception`, or `SEVERE` appears.

- [ ] **Step 4: Perform authenticated player acceptance**

With player `Aoozzz` (OP) joined from an unmodded client, execute `/company` and capture the detailed Chinese rules. Type `/company ` then press Tab: confirm the root candidates include `create`, `info`, `recovery`; type `/company create 红石工坊 ` then Tab and confirm `30`, `50`, `70`; type `/company recovery ` then Tab and confirm `list`. Switch to a non-admin/non-create permission test account if available; otherwise record this limitation rather than claiming it passed.

- [ ] **Step 5: Update operational guide and write evidence report**

Document the BlockStock directory name, one-time migration rules, detailed `/company` help, exact Tab behavior, and the exact local evidence/limitations in README and the ignored task report. Leave economic creation/withdrawal testing explicitly unclaimed unless it was actually performed.

- [ ] **Step 6: Final verification and commit**

Run: `./gradlew.bat clean test shadowJar --rerun-tasks --no-build-cache; git diff --check; git status --short`

Expected: BUILD SUCCESSFUL, one BlockStock all JAR, no whitespace errors, no tracked server data/logs, and the server remains running unless a verified graceful shutdown was explicitly requested.

```bash
git add README.md
git commit -m "docs: document BlockStock command experience"
```

## Self-review

- Spec coverage: Task 1 covers the exact new name, JAR, directory migration precedence and safe failure. Task 2 covers dynamic configuration-backed Chinese help, original-style candidate menus, all stated command paths and permission boundaries. Task 3 covers the real Paper/Vault/Essentials migration and Aoozzz client checks.
- Placeholder scan: every implementation and test step names concrete files, behavior, commands, and expected outcomes.
- Type consistency: Task 1 defines the migration constructor/result consumed by `BlockecoPlugin`; Task 2 defines `CompanyCreationRules` passed to both command and completer; Task 3 consumes the Task 1 JAR and Task 2 command behavior.
