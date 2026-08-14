# BlockStock

BlockStock 是 Minecraft 经济与公司交易所的**仅服务端** Paper 插件基础。在 Phase 1 中，玩家无需安装客户端模组或资源包。本构建可安全地创建持久化公司、通过 Vault 扣除注册资金，并让管理员查看恢复信息。交易、图表、土地/商店/生产适配器、分红、发行与退市将在后续阶段提供。

## 环境要求

- Paper `1.21.4+`；已测试的基线为 Paper `1.21.4` build `232`。
- 已测试基线使用 Java `21+`。新版 Paper 可能提高 Java 要求；请根据所选服务器版本遵循 [Paper 文档](https://docs.papermc.io/paper/getting-started)。
- [Vault](https://github.com/MilkBowl/Vault/releases) 和**恰好一个**已启用、与 Vault 兼容的经济提供方。烟测环境使用 [EssentialsX](https://github.com/EssentialsX/Essentials/releases) Economy。

插件仅使用公开的 Bukkit/Paper 与 Vault API，不使用 NMS/CraftBukkit 内部接口。

## 构建与安装

使用 Java 21 构建：

```powershell
.\gradlew.bat clean test shadowJar
```

将 `build/libs/` 中唯一的 `*-all.jar` 安装到服务器的 `plugins/` 文件夹；并从官方发布页取得 Vault 与经济提供方的 JAR，一同放入服务器。启动服务器一次后，BlockStock 会创建：

```text
plugins/BlockStock/config.yml
plugins/BlockStock/blockeco.db
```

不要在客户端放置 Paper API、Vault API 或客户端模组。Vault 本身和所选经济提供方必须作为服务端插件安装。

进行本地集成测试时，`runServer` 固定使用 Paper 1.21.4：

```powershell
.\gradlew.bat runServer
```

`run/` 被 Git 有意忽略。运行前，请在 `run/eula.txt` 接受 Mojang EULA，并手动将 Vault 和经济提供方放进 `run/plugins/`。Gradle 的 run-paper 任务会自动使用 Shadow JAR。

## 配置

下列为 `plugins/BlockStock/config.yml` 的全部默认配置。货币值以十进制字符串保存，并通过 `currency.scale` 精确转换为带符号 `long` 最小货币单位。

| 配置键 | 默认值 | 说明 |
| --- | --- | --- |
| `currency.scale` | `2` | 经济适配器接受的小数位数；有效范围为 0–8。 |
| `company.registration-fee` | `'1000.00'` | 成功注册时从流通中移除的正数费用。 |
| `company.minimum-capital` | `'10000.00'` | 转入新公司内部金库的正数资本。 |
| `company.initial-shares` | `1000` | 保留的 Phase 1/IPO 设置。初始公司固定使用 1,000 股。 |
| `company.allowed-dividend-percent` | `[30, 50, 70]` | 三个不可变注册选项的保留配置镜像。 |
| `database.file` | `blockeco.db` | 相对于 `plugins/BlockStock/` 的 SQLite 文件名。 |
| `database.pool-size` | `2` | 保留的连接池大小设置；本 Phase 1 构建使用固定的小型 Hikari 配置。 |
| `messages.*` | 见生成的文件 | 所有面向玩家的命令消息，包括处理中、恢复和查询文本。请在适用处保留 `%name%`、`%state%`、`%id%`、`%player%`、`%amount%`、`%time%` 与 `%error%` 占位符。 |

修改配置后请重启。插件会拒绝无效的小数位，以及非正数费用/资本，不会静默取整。

## 命令与权限

| 命令 | 权限 | 说明 |
| --- | --- | --- |
| `/company create <name> <30\|50\|70>` | `blockeco.company.create`（默认：true） | 仅玩家可用。名称可以包含空格，也接受引号名称。该命令启动异步注册。 |
| `/company info <name>` | `blockeco.company.info`（默认：true） | 显示已保存的公司名称与当前生命周期状态。 |
| `/company recovery list` | `blockeco.admin.recovery`（默认：op） | 列出未解决/可见的注册记录；绝不强制退款。 |

正常路径中，创建公司会**恰好一次**扣除**注册费用 + 最低资本**。仅最低资本进入公司金库。公司初始状态为 `PENDING_ASSET_BINDING`，拥有 1,000 股及所选、不可变的 30%、50% 或 70% 分红档位。创始人没有金库提款命令。

## 备份、恢复与故障处理

Phase 1 的权威数据是 `plugins/BlockStock/blockeco.db` 中的 SQLite 数据库。

1. 正常停止 Paper（`stop`），并等待关服完成。
2. 将 `blockeco.db` 复制到带时间戳的备份位置。
3. 再次启动 Paper；待服务器健康检查完成前保留该备份。

**不要**在复制在线 SQLite 数据库时遗漏对应的 `-wal` 与 `-shm` 文件。在线备份应使用 SQLite 一致性备份方法/工具；最安全的日常操作是干净地停止服务器后再复制。

每次影响资金的注册都会写入审计事件并遵循持久化 saga。使用 `/company recovery list` 检查未解决记录：

- `REFUND_REQUIRED`：已知扣款完成，但后续持久化步骤失败，且补偿性入账失败。请检查 Vault/经济提供方历史；确认尚未退款后，才仅补偿记录中的完整扣款额。
- `AMBIGUOUS`：服务器可能在提供方调用与持久化 `WITHDRAWN` 状态之间停止。BlockStock 有意**不会**猜测资金是否移动。任何手动补偿前，请检查 BlockStock 审计日志与经济提供方的交易/历史数据。绝不可对模糊记录盲目自动退款。

其他正常终态为 `COMPLETED`、`REFUNDED` 与 `REJECTED`。中断后 `PREPARED` 和 `WITHDRAWN` 可能可见，启动恢复时会保守处理。

## 真实服务器烟测清单

使用干净且被忽略的 `run/` 文件夹，然后：

1. 将官方 Vault 与经济提供方 JAR 放入 `run/plugins/`，并将 `run/eula.txt` 设置为 `eula=true`。
2. 使用 Java 21 运行 `.\gradlew.bat runServer`，确认 Paper、Vault、经济提供方和 BlockStock 均已加载。
3. 确认日志包含 `BlockStock ready; stale registration records scanned=...`，且 Vault 报告已挂钩经济提供方。
4. 以余额已知的测试玩家加入；执行 `/company create "Red Stone" 50`。通过数据库/审计数据确认恰好一次扣除费用 + 资本、状态为 `PENDING_ASSET_BINDING`、拥有 1,000 股、50% 档位以及仅资本进入金库。
5. 执行 `/company info "Red Stone"`，再重复创建命令；确认同名拒绝且不会再次扣款。
6. 使用 `stop` 停服、重启，并确认公司/审计数据仍保留。
7. 进行恢复演练时，只能在受控测试环境中、故意延迟注册期间停止；确认记录可通过 `/company recovery list` 查看。绝不可将生产资金用于该演练。

### 已执行的验证矩阵（2026-08-14）

| 检查项 | 结果 | 证据 |
| --- | --- | --- |
| Gradle run 任务语法 | 通过 | `help --task runServer` 报告 `xyz.jpenilla.runpaper.task.RunServer`。 |
| 首次 Paper 启动 | 通过 | Java 21.0.11 上的 Paper 1.21.4 build 232 已加载 BlockStock、Vault 1.7.3-b131 和 Essentials 2.21.0。 |
| Vault 提供方解析 | 通过 | Vault 报告 `Essentials Economy hooked`；BlockStock 记录其 ready 行。 |
| 模式迁移/数据库创建 | 通过 | 已创建 `plugins/BlockStock/blockeco.db`，且 Hikari 成功启动。 |
| 干净的 BlockStock 关闭 | 通过 | Vault 关闭前，Hikari 记录了 shutdown initiated/completed。 |
| 重启 | 通过 | 第二次启动复用了同一 `run/` 数据库，并再次达到 BlockStock ready 行。 |
| 已认证玩家的创建/查询/重复扣款 | 未执行 | 本地烟测环境没有可用的自动化、已认证 Minecraft 客户端/玩家会话。请在管理员测试服务器上执行步骤 4–5。 |
| 故意中断注册 | 未执行 | 需要受控的延迟提供方调用和玩家会话；仅保留为管理员测试。 |

本地烟测使用的快速管道 `stop`，在 BlockStock 已干净关闭后触发了 EssentialsX 更新检查器的关闭警告。这是 EssentialsX 的异步任务警告，不是 BlockStock 堆栈跟踪；评估提供方日志时，应让测试服务器正常空闲一段时间后再停止。

## 兼容性来源

- Gradle Plugin Portal 上的 [run-paper 3.0.2](https://plugins.gradle.org/plugin/xyz.jpenilla.run-paper/3.0.2) 以及 [官方 run-task 用法](https://github.com/jpenilla/run-task) 说明了 `runServer { minecraftVersion(...) }` 配置。
- [Vault 官方发布页](https://github.com/MilkBowl/Vault/releases) 提供 Vault 1.7.3。
- [EssentialsX 官方发布页](https://github.com/EssentialsX/Essentials/releases/tag/2.21.0) 提供烟测使用的经济提供方。
