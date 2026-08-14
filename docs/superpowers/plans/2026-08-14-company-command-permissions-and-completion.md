# 公司命令权限与 Tab 补全 Implementation Plan

> For agentic workers: REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task.

Goal: 按玩家/管理员权限隔离公司命令，并为有权限的发送者提供安全的 Tab 自动补全。

Architecture: 新建无状态 CompanyTabCompleter，只依赖 CommandSender、参数和常量候选值。BlockecoPlugin 在启动窗口注册它；CompanyCommand 继续逐项检查既有具体权限，plugin.yml 增加描述性权限组和 children。

Tech Stack: Java 21、Paper API 1.21.4、JUnit 5、Mockito、Gradle Shadow。

## Global Constraints

- 具体权限节点固定为 blockeco.company.create、blockeco.company.info、blockeco.admin.recovery；执行层必须继续检查它们。
- 玩家节点默认 true；管理员恢复节点默认 op；命令名称和参数不变。
- 补全不得同步访问数据库，不得泄露公司名，不得增加权限插件依赖。
- 启动窗口与 ready 状态都必须注册相同补全器；未授权候选必须为空。
- 先 RED 后最小 GREEN；最终运行 clean test shadowJar --rerun-tasks --no-build-cache。

---

### Task 1: 权限感知的 CompanyTabCompleter

Files:

- Create: src/main/java/cn/blockeco/exchange/paper/CompanyTabCompleter.java
- Create: src/test/java/cn/blockeco/exchange/paper/CompanyTabCompleterTest.java
- Modify: src/main/java/cn/blockeco/exchange/BlockecoPlugin.java
- Modify: src/main/resources/plugin.yml

Interfaces:

- Consumes: TabCompleter.onTabComplete(CommandSender, Command, String, String[]) 与三个既有具体权限节点。
- Produces: 可由 PluginCommand.setTabCompleter(TabCompleter) 注册的无状态完成器。

- [ ] Step 1: 写失败的权限与补全测试

    @Test void playerOnlySeesCreateAndInfoAtRoot() {
        CommandSender player = senderWith("blockeco.company.create", "blockeco.company.info");
        assertThat(completer.onTabComplete(player, command, "company", new String[]{""}))
                .containsExactly("create", "info");
    }
    @Test void adminSeesRecoveryAndItsListChildButPlayerDoesNot() {
        assertThat(completer.onTabComplete(admin, command, "company", new String[]{"rec"}))
                .containsExactly("recovery");
        assertThat(completer.onTabComplete(admin, command, "company", new String[]{"recovery", ""}))
                .containsExactly("list");
        assertThat(completer.onTabComplete(player, command, "company", new String[]{"recovery", ""})).isEmpty();
    }
    @Test void createSuggestsDividendTiersOnlyAfterANameAndFiltersPrefix() {
        assertThat(completer.onTabComplete(player, command, "company", new String[]{"create", "红石", ""}))
                .containsExactly("30", "50", "70");
        assertThat(completer.onTabComplete(player, command, "company", new String[]{"create", "红石", "5"}))
                .containsExactly("50");
    }

- [ ] Step 2: 运行失败测试

Run: ./gradlew.bat test --tests cn.blockeco.exchange.paper.CompanyTabCompleterTest

Expected: FAIL，因为完成器不存在。

- [ ] Step 3: 实现完成器并在两种生命周期阶段注册

在 installInitializingCommand 中调用 companyCommand.setTabCompleter(new CompanyTabCompleter())。完成器对第一个参数按 sender 的具体权限过滤 create、info、recovery；仅 recovery 管理员补 list；仅 create 已有名称参数后补 30/50/70；所有其他情况返回 List.of()。

- [ ] Step 4: 声明权限组并保持具体节点行为

在 plugin.yml 添加中文 description 与 blockeco.player、blockeco.admin 父节点；前者 children 为 create/info，后者 child 为 recovery。保留三个具体节点和 default 值。

- [ ] Step 5: 运行聚焦回归测试

Run: ./gradlew.bat test --tests cn.blockeco.exchange.paper.CompanyTabCompleterTest --tests cn.blockeco.exchange.paper.CompanyCommandTest --tests cn.blockeco.exchange.BlockecoPluginTest

Expected: PASS；命令执行仍在具体权限节点上拒绝未授权发送者，完成器不读数据库。

- [ ] Step 6: 提交任务

Run:
    git add src/main/java/cn/blockeco/exchange/paper/CompanyTabCompleter.java src/test/java/cn/blockeco/exchange/paper/CompanyTabCompleterTest.java src/main/java/cn/blockeco/exchange/BlockecoPlugin.java src/main/resources/plugin.yml
    git commit -m "feat: add permission-aware company tab completion"

### Task 2: 本地 Paper 权限与中文命令冒烟验证

Files:

- Inspect: run/ops.json
- Inspect: run/launcher-*.out.log
- Modify (ignored, local only): run/plugins/BlockecoExchange/config.yml

Interfaces:

- Consumes: Task 1 的 Shadow JAR、中文 messages 配置、已写入 Aoozzz 的 OP 记录。
- Produces: 本地服务器加载中文文案与权限补全，并提供实际管理员验证证据。

- [ ] Step 1: 验证本地配置与 OP 记录

Run: Select-String run/ops.json -Pattern '"name": "Aoozzz"'；Select-String run/plugins/BlockecoExchange/config.yml -Pattern '公司创建正在处理中'

Expected: OP 名称与中文文案均存在。

- [ ] Step 2: 优雅重启并启动最新 Shadow JAR

向 Paper 控制台发送 stop，等待 Saving players、Saving worlds 和 Hikari shutdown completed；再运行 ./gradlew.bat runServer --console=plain。

- [ ] Step 3: 以 Aoozzz 验证玩家与管理员路径

验证 /company 显示中文用法；普通玩家只能看到 create/info 补全；Aoozzz 可看到 recovery/list；/company recovery list 返回中文恢复结果或“没有需要恢复的注册记录”。记录实际观察，不能把未执行的创建扣款测试标为通过。

- [ ] Step 4: 完整构建与工作区检查

Run: ./gradlew.bat clean test shadowJar --rerun-tasks --no-build-cache；git diff --check

Expected: 测试全绿，唯一 deployable all.jar；run/ 未跟踪。
