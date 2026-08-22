# BlockStock

BlockStock 是 Minecraft 经济与公司交易所的**仅服务端** Paper 插件基础。在 Phase 1 中，玩家无需安装客户端模组或资源包。本构建可安全地创建持久化公司、通过 Vault 扣除注册资金，并让管理员查看恢复信息。交易、图表、土地/商店/生产适配器、分红与退市将在后续阶段提供。

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

### 从 BlockecoExchange 一次性迁移

BlockStock 启动时会检查 `plugins/BlockecoExchange/`。仅当新目录
`plugins/BlockStock/` 尚不存在时，插件才会将**整个目录**迁移为
`plugins/BlockStock/`，从而保留原有的 `config.yml`、`blockeco.db` 以及 SQLite
关联文件。迁移通过文件系统的目录移动完成；实现并未承诺原子移动。不要手工复制、移动、合并或删除旧目录；先正常执行 `stop` 并等待
Paper 完成保存，再让 BlockStock 的下一次启动完成迁移。

如果 `plugins/BlockStock/` 已存在，插件会保守地跳过迁移，绝不覆盖新目录中的
数据。此时应保留两个目录并先人工核对备份，而不是删除任一目录。一次成功迁移后，
`plugins/BlockecoExchange/` 应不再存在。

## 配置

下列为 `plugins/BlockStock/config.yml` 的全部默认配置。货币值以十进制字符串保存，并通过 `currency.scale` 精确转换为带符号 `long` 最小货币单位。

| 配置键 | 默认值 | 说明 |
| --- | --- | --- |
| `currency.scale` | `2` | 经济适配器接受的小数位数；有效范围为 0–8。 |
| `company.registration-fee` | `'1000.00'` | 成功注册时从流通中移除的正数费用。 |
| `company.minimum-capital` | `'10000.00'` | 转入新公司内部金库的正数资本。 |
| `company.initial-shares` | `1000` | 保留的 Phase 1/IPO 设置。初始公司固定使用 1,000 股。 |
| `company.treasury-escrow-uuid` | 非零 UUID | BlockStock 专用、非玩家的 Vault 托管账户。启动时会验证；账户不存在时会请求 Vault 经济提供方创建它。若提供方拒绝，失败时公司和 IPO 命令不会接受请求。绝不可填写真实玩家 UUID。 |
| `company.allowed-dividend-percent` | `[30, 50, 70]` | 三个不可变注册选项的保留配置镜像。 |
| `database.file` | `blockeco.db` | 相对于 `plugins/BlockStock/` 的 SQLite 文件名。 |
| `database.pool-size` | `2` | 保留的连接池大小设置；本 Phase 1 构建使用固定的小型 Hikari 配置。 |
| `messages.*` | 见生成的文件 | 所有面向玩家的命令消息，包括处理中、恢复和查询文本。请在适用处保留 `%name%`、`%state%`、`%id%`、`%player%`、`%amount%`、`%time%` 与 `%error%` 占位符。 |

修改其他配置后请重启。插件会拒绝无效的小数位，以及非正数费用/资本，不会静默取整。OP 可通过 `/stockadmin config min-capital <金额>` 在运行时调整最低注册资本：成功保存 `config.yml` 后，下一笔开始的注册、中文帮助和补全立即使用新值；已存在公司及已经开始的注册不受影响。先备份 `config.yml`。数据库不可用时配置保存仍可成功，但管理员会收到中文审计失败告警；不得将该次审计视为已写入。

## 命令与权限

| 命令 | 权限 | 说明 |
| --- | --- | --- |
| `/company create <name> <paid-in-capital> <30\|50\|70>` | `blockeco.company.create`（默认：true） | 仅玩家可用。名称可以包含空格，也接受引号名称。该命令启动异步注册。 |
| `/company info <name>` | `blockeco.company.info`（默认：true） | 显示已保存的公司名称与当前生命周期状态。 |
| `/company recovery list` | `blockeco.admin.recovery`（默认：op） | 列出未解决/可见的注册记录；绝不强制退款。 |
| `/company asset bind <adapter> <external-key>` | `blockeco.company.asset.bind`（默认：true） | 将创始人的公司绑定到外部资产；没有已验证适配器时会拒绝。 |
| `/company ipo announce <目标金额> <发行价>` | `blockeco.company.ipo.announce`（默认：true） | 为已绑定资产的公司公告首次发行；公告 12 小时后才开放认购。 |
| `/company ipo list` | 无需权限 | 列出全服公开 IPO；显示发行编号、状态、目标、发行价、股数容量和开闭时间。 |
| `/company ipo info <发行UUID>` | 无需权限 | 显示一个公开 IPO 的完整只读条目；发行编号可直接用于认购命令。 |
| `/company ipo subscribe <发行UUID> <整数股>` | `blockeco.company.ipo.subscribe`（默认：true） | 仅玩家可用，认购公开 IPO。 |
| `/stockadmin config` | `blockstock.admin.config`（默认：op） | 显示当前最低注册资本。 |
| `/stockadmin config min-capital <金额>` | `blockstock.admin.config`（默认：op） | 保存并立即发布下一笔公司注册使用的最低注册资本。金额必须为正数，且小数位必须恰好符合 `currency.scale`。 |

直接执行 `/company` 会按调用者权限显示中文指引：可创建的玩家会看到“BlockStock
公司命令：”、`/company create <名称> <实缴资本> <DIVIDENDS>`，以及创建费、最低注册资本、
合计余额、初始发行股数、必须为玩家、插件完成初始化和公司名不可重复等规则；拥有
查询或恢复权限时也会分别看到 `/company info <名称>` 与
`/company recovery list`。不具备某项权限时，该项不会出现在根帮助中。

原版客户端的 Tab 补全按权限过滤：在 `/company ` 后，具备相应权限的玩家会获得
`create`、`info`、`recovery`；在 `/company create 红石工坊 ` 后会获得配置的
`30`、`50`、`70`；在 `/company recovery ` 后，管理员会获得 `list`。在 `/company ipo ` 后，任何调用者都会获得
`list`、`info`；玩家还会按各自权限获得 `announce`、`subscribe`。这些候选项不替代实际命令权限检查。

### 本次本地验收状态（2026-08-14）

发布前已在本工作区完成一次 fresh `clean test shadowJar --rerun-tasks --no-build-cache`：24 个测试套件、161 个测试、0 failure/error/skip，且 `build/libs/` 仅产出 `blockstock-0.1.0-SNAPSHOT-all.jar`。随后使用 Java 21.0.11 的 Paper 1.21.4 build 232 进行了真实服务器烟测；Vault 1.7.3-b131 已挂接 EssentialsX 2.21.0 Economy。

首次 post-fix 启动记录 `BlockStock ready; legacy capitalizations recovered=2; stale registration records scanned=0`。控制台已验证 `/company` 的中文帮助、`/company info Aoozzz` 显示“待绑定资产”、`/company recovery list` 显示“没有恢复记录”，以及 console `/company create ...` 返回“此命令只能由玩家执行”。优雅停止并重启后，ready 行为 `legacy capitalizations recovered=0`，证明遗留资本化恢复没有重复入账。

OP 控制台已验证 `/stockadmin config` 显示 `10000.00`，改为 `10000.01` 后立即查询到新值，再恢复为 `10000.00`；停止、重启后仍为 `10000.00`。只读 SQLite 查询确认审计 `sequence=19` 记录 `oldMinor=1000000`、`newMinor=1000001`，`sequence=20` 记录反向变更，二者 `actor=CONSOLE`。`/stockadmin config min-capital 1.234` 被中文精度错误拒绝，数值保持 `10000.00`。

本轮仍**未**验证真实 authenticated player 的公司创建、余额不足分支或客户端 Tab 补全；未配置真实资产 adapter，亦未进行 IPO 认购。日志中的 `Can't keep up` 与 Hikari `clock leap` 来自宿主暂停造成的一次时钟跳变，不作为插件通过证据；重启后服务器正常达到 ready。

正常路径中，`/company create <名称> <实缴资本> <30|50|70>` 会**恰好一次**扣除**注册费用 + 实缴资本**，且实缴资本不得低于 `company.minimum-capital`。注册费从流通中移除；仅实缴资本会进入 BlockStock 的公司现金账，并在 Vault 的保留托管账户中等额留存。公司初始状态为 `PENDING_ASSET_BINDING`，拥有 1,000 股及所选、不可变的 30%、50% 或 70% 分红档位。创始人没有金库提款命令。

## 公司财务与 IPO 运维

BlockStock 在 Vault 中只使用 `company.treasury-escrow-uuid` 指向的非玩家托管身份。先从创始人账户扣除注册费和实缴资本；只有实缴资本再存入该托管身份，并在 SQLite 中创建同额公司现金/实缴资本记录。托管身份不是玩家钱包，也不是可由公司命令取现的账户。每次调用有持久化操作 ID 和审计事件；重启恢复绝不重放无法证明结果的 Vault 调用。

首次部署前，确认 Vault 报告唯一经济提供方。启动前置检查会检查这个保留 UUID 对应的账户；账户不存在时仅请求提供方创建空账户，不会发生资金转移。若提供方拒绝，日志会给出中文管理员诊断，`/company` 继续显示“正在初始化”，不会开始资本恢复或接受公司/资产/IPO 操作。请先修复 Vault/账户配置再重启；不要通过改用玩家 UUID 规避检查。

启动验证通过后，BlockStock 会在 SQL 线程执行一次遗留公司资本化恢复，再将公司命令切换为可接受状态。旧公司按其保存的金库余额创建一次、稳定 ID 的资本化记录；重复启动不会重复建立现金记录、股份或 Vault 入账。处于无法证明外部转账结果的操作会标记为 `AMBIGUOUS`，需要管理员查 Vault 历史与审计记录，不能自动补偿。

资产绑定依赖第三方 `CompanyAssetAdapter`。本发行包**不包含**土地、商店、生产或任何第三方资产适配器；所以未安装并验证适配器时，不能声称资产绑定成功。IPO 公告需要已绑定资产。公告后固定等待 12 小时才可认购，开放窗口为两天，募资目标不得超过公司实缴资本的五倍。没有已安装、已验证的适配器及真实玩家会话时，不得宣称资产归属验证、玩家认购或资金结算已经通过。

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
3. 确认日志包含 `BlockStock ready; legacy capitalizations recovered=...; stale registration records scanned=...`，且 Vault 报告已挂钩经济提供方。
4. 以余额已知的测试玩家加入；执行 `/company create "Red Stone" 10000.00 50`。通过数据库/审计数据确认恰好一次扣除费用 + 资本、状态为 `PENDING_ASSET_BINDING`、拥有 1,000 股、50% 档位以及仅资本进入金库。
5. 执行 `/company info "Red Stone"`，再重复创建命令；确认同名拒绝且不会再次扣款。
6. 使用 `stop` 停服、重启，并确认公司/审计数据仍保留。
7. 进行恢复演练时，只能在受控测试环境中、故意延迟注册期间停止；确认记录可通过 `/company recovery list` 查看。绝不可将生产资金用于该演练。

### 已执行的验证矩阵（2026-08-14）

| 检查项 | 结果 | 证据 |
| --- | --- | --- |
| fresh 发布构建 | 通过 | `clean test shadowJar --rerun-tasks --no-build-cache` 成功；24 suites、161 tests、0 failure/error/skip，且仅一个 Shadow JAR。 |
| 首次 post-fix Paper 启动 | 通过 | Java 21.0.11、Paper 1.21.4 build 232，首次 ready 为 `legacy capitalizations recovered=2; stale registration records scanned=0`。 |
| Vault 提供方解析 | 通过 | Vault 1.7.3-b131 记录 `Essentials Economy hooked`，EssentialsX 2.21.0 已加载。 |
| 控制台公司命令 | 通过 | `/company` 中文帮助、`info Aoozzz` 的“待绑定资产”、无恢复记录，以及 console create 的玩家限定提示均已记录。 |
| 最低注册资本运行时配置 | 通过 | `10000.00 → 10000.01 → 10000.00` 即时查询正确；SQLite sequence 19/20 审计与优雅重启后的持久化均已核实。 |
| 非法精度 | 通过 | `1.234` 返回中文拒绝消息，当前值仍为 `10000.00`。 |
| 重启幂等性 | 通过 | 优雅 stop/restart 后第二次 ready 为 `legacy capitalizations recovered=0`。 |
| 已认证玩家的创建/余额不足/客户端补全 | 未执行 | 没有可用于本轮验证的真实 authenticated player 客户端会话。 |
| 真实资产 adapter 与 IPO 认购 | 未执行 | 未配置和验证第三方资产适配器，未进行 IPO 玩家认购。 |

本地烟测使用的快速管道 `stop`，在 BlockStock 已干净关闭后触发了 EssentialsX 更新检查器的关闭警告。这是 EssentialsX 的异步任务警告，不是 BlockStock 堆栈跟踪；评估提供方日志时，应让测试服务器正常空闲一段时间后再停止。

## 兼容性来源

- Gradle Plugin Portal 上的 [run-paper 3.0.2](https://plugins.gradle.org/plugin/xyz.jpenilla.run-paper/3.0.2) 以及 [官方 run-task 用法](https://github.com/jpenilla/run-task) 说明了 `runServer { minecraftVersion(...) }` 配置。
- [Vault 官方发布页](https://github.com/MilkBowl/Vault/releases) 提供 Vault 1.7.3。
- [EssentialsX 官方发布页](https://github.com/EssentialsX/Essentials/releases/tag/2.21.0) 提供烟测使用的经济提供方。
