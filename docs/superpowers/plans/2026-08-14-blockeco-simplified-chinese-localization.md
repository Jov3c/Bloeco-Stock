# BlockecoExchange 简体中文本地化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 BlockecoExchange Phase 1 的玩家交互、默认配置、插件元数据和 README 本地化为简体中文，并让本地 Paper 测试服加载该配置。

**Architecture:** `Messages` 是唯一的玩家文案边界；中文默认值来自 `config.yml`，Java 兜底也改为中文。状态枚举仅在 `Messages` 输出前映射为中文显示名，数据库、审计、权限和命令协议继续使用稳定英文标识。

**Tech Stack:** Java 21、Paper API 1.21.4、Adventure Component、JUnit 5、Gradle Shadow、本地 Paper `run/` 服务端。

## Global Constraints

- 命令保持 `/company create`、`/company info`、`/company recovery list`，不新增中文别名。
- `CompanyStatus`、`RegistrationSagaState`、审计事件、数据库列值、配置键和权限节点保持英文稳定标识。
- Paper、Vault、EssentialsX 的第三方日志和 JAR 不修改。
- `run/` 是 Git 忽略目录；其中的本地配置可更新但不得提交。
- 所有源码采用 UTF-8；先 RED、后最小 GREEN；最终运行 `clean test shadowJar --rerun-tasks --no-build-cache`。

---

### Task 1: 中文消息与状态显示边界

**Files:**

- Modify: `src/main/java/cn/blockeco/exchange/paper/Messages.java`
- Modify: `src/test/java/cn/blockeco/exchange/paper/CompanyCommandTest.java`
- Create: `src/test/java/cn/blockeco/exchange/paper/MessagesTest.java`

**Interfaces:**

- Consumes: `CompanyStatus`、`RegistrationSagaState`、Adventure `Component`。
- Produces: `Messages.companyInfo(String, Object)` 与 `Messages.recoveryRecord(...)` 在收到状态枚举时显示中文名称；所有 `Messages` 无配置兜底文本为中文。

- [ ] **Step 1: 写失败的中文显示测试**

```java
@Test void localizes_company_and_recovery_states_without_localizing_storage_values() {
    Messages messages = new Messages(null);
    assertThat(plain(messages.companyInfo("红石工坊", CompanyStatus.PENDING_ASSET_BINDING)))
            .isEqualTo("红石工坊 — 待绑定资产");
    assertThat(plain(messages.recoveryRecord("id", "玩家", 1100000L,
            RegistrationSagaState.REFUND_REQUIRED, "时间", "原因")))
            .contains("状态=待人工退款");
    assertThat(RegistrationSagaState.REFUND_REQUIRED.name()).isEqualTo("REFUND_REQUIRED");
}
```

- [ ] **Step 2: 运行失败测试**

Run: `./gradlew.bat test --tests cn.blockeco.exchange.paper.MessagesTest`

Expected: FAIL，因为当前输出为英文枚举名且没有 `MessagesTest`。

- [ ] **Step 3: 实现最小中文显示映射和中文兜底**

```java
private String displayState(Object state) {
    return switch (String.valueOf(state)) {
        case "PENDING_ASSET_BINDING" -> "待绑定资产";
        case "REFUND_REQUIRED" -> "待人工退款";
        case "AMBIGUOUS" -> "待人工核对";
        default -> String.valueOf(state);
    };
}
```

逐一映射剩余 `CompanyStatus` 与 `RegistrationSagaState`，并将 `companyInfo`、`recoveryRecord` 的 `%state%` 改用 `displayState(state)`；每个无配置 fallback 改为对应中文短句。

- [ ] **Step 4: 运行消息与命令回归测试**

Run: `./gradlew.bat test --tests cn.blockeco.exchange.paper.MessagesTest --tests cn.blockeco.exchange.paper.CompanyCommandTest`

Expected: PASS；命令解析、权限和异步调度不变，所有 `Messages(null)` 玩家可见文本为中文。

- [ ] **Step 5: 提交任务**

```powershell
git add src/main/java/cn/blockeco/exchange/paper/Messages.java src/test/java/cn/blockeco/exchange/paper/MessagesTest.java src/test/java/cn/blockeco/exchange/paper/CompanyCommandTest.java
git commit -m "feat: localize player messages to simplified Chinese"
```

### Task 2: 中文默认配置、插件元数据、README 与本地部署

**Files:**

- Modify: `src/main/resources/config.yml`
- Modify: `src/main/resources/plugin.yml`
- Modify: `README.md`
- Modify (ignored, local only): `run/plugins/BlockecoExchange/config.yml`

**Interfaces:**

- Consumes: Task 1 的 `Messages` 键与 `%name%`、`%state%`、`%id%`、`%player%`、`%amount%`、`%time%`、`%error%` 占位符。
- Produces: 新安装和现有本地服务器使用完整中文玩家文案；README 为中文运维说明；命令、权限和配置键不变。

- [ ] **Step 1: 写默认资源完整性失败测试**

```java
@Test void default_config_exposes_chinese_messages_and_preserves_placeholders() throws Exception {
    String config = new String(getClass().getResourceAsStream("/config.yml").readAllBytes(), UTF_8);
    assertThat(config).contains("公司创建正在处理中", "%name%", "%state%", "%id%");
    assertThat(config).doesNotContain("Company registration is processing.");
}
```

- [ ] **Step 2: 运行失败测试**

Run: `./gradlew.bat test --tests cn.blockeco.exchange.paper.MessagesTest`

Expected: FAIL，因为默认 `config.yml` 仍含英文消息。

- [ ] **Step 3: 本地化资源与文档**

将 `messages.*` 翻译为自然、短句的简体中文。保留 `company-info: '%name% — %state%'` 的结构；`recovery-record` 改为 `编号=%id% 玩家=%player% 金额=%amount% 状态=%state% 时间=%time% 原因=%error%`。

将 `plugin.yml` 的 `company.description` 和 `usage` 改为中文，但不改命令名称或权限节点。将 README 全文翻译为中文，保留版本、命令、路径、配置键、恢复状态和安全告警的准确性。同步编辑忽略的本地 `run/plugins/BlockecoExchange/config.yml`。

- [ ] **Step 4: 验证资源、构建与本地服务器配置**

Run: `./gradlew.bat clean test shadowJar --rerun-tasks --no-build-cache`，再执行 `git diff --check`。

Expected: 全部测试通过，唯一 `*-all.jar` 产物存在；新 JAR 的 `config.yml` 含中文；本地 `run` 配置保留所有占位符。

- [ ] **Step 5: 提交任务**

```powershell
git add src/main/resources/config.yml src/main/resources/plugin.yml README.md src/test/java/cn/blockeco/exchange/paper/MessagesTest.java
git commit -m "docs: localize plugin defaults and operations guide"
```

### Task 3: 本地 Paper 中文冒烟验证

**Files:**

- Modify (ignored, local only): `run/plugins/BlockecoExchange/config.yml`
- Inspect: `run/launcher-*.out.log`

**Interfaces:**

- Consumes: Task 2 构建出的 `build/libs/blockeco-exchange-0.1.0-SNAPSHOT-all.jar` 与本地 Paper 1.21.4 服务端。
- Produces: 已重启的本地服务器加载最新 JAR 和中文配置；管理员可用 `/company` 验证中文反馈。

- [ ] **Step 1: 重启前确认本地配置**

Run: `Select-String run/plugins/BlockecoExchange/config.yml -Pattern "公司创建正在处理中", "状态=%state%"`

Expected: 命中中文配置和全部占位符。

- [ ] **Step 2: 优雅重启 Paper**

向运行中的服务器控制台发送 `stop`，等待 `Saving players`、`Saving worlds`、Hikari shutdown completed；再运行 `./gradlew.bat runServer --console=plain`。

- [ ] **Step 3: 验证 ready 与中文玩家路径**

检查日志包含 `Blockeco ready` 且无 Blockeco exception。以 `Aoozzz` 登录，用 `/company` 触发用法提示或 `/company info 不存在的公司`，确认聊天反馈为中文；若需要注册测试，先用 Essentials 提供测试资金。

- [ ] **Step 4: 记录验证结果**

只提交源码、资源和 README；不提交 `run/`、世界、数据库、第三方 JAR 或日志。记录已执行的玩家命令与观察到的中文文本。
