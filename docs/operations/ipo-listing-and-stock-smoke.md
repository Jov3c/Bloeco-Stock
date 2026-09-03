# IPO 上市与 `/stock` 烟测

## 可自动验证

在本工作树执行：

```powershell
.\gradlew.bat clean test shadowJar
```

该命令覆盖迁移、公开读取、缓存、命令 gate 和现有认购 saga 的自动化测试，并产出 `build/libs/bloeco-stock-*-all.jar`。

服务器启动成功且没有上市公司时，控制台可执行：`/stock market`。预期中文结果为“当前暂无已上市股票。”这是公开空市场检查，不等同于真实 IPO 验收。

## 2026-08-22 本地服实测

- Paper `1.21.4-232`、Java 21、Vault `1.7.3-b131`、EssentialsX `2.21.0`，原版客户端兼容的纯服务端插件。
- 部署前已确认 0 人在线，并备份 `run/plugins/Bloeco-Stock` 到带时间戳的备份目录。
- 无缓存构建：28 个测试套件、211 个测试，0 failure、0 error、0 skipped；唯一产物为 `blockstock-0.1.0-SNAPSHOT-all.jar`。
- 启动日志达到 `BlockStock ready`，资本化恢复、IPO 托管恢复、歧义记录和过期注册扫描均为 0。
- 只读数据库核验：`schema_history=V001..V006`、`stock_listings=0`、`stock_code_sequence.last_value=0`。
- 控制台已实际执行 `/stock`、`/stock market`、`/stock ipo 10`、`/stock info 不存在公司`、`/stock announcements 不存在公司 5`、`/stock subscribe 不存在公司 1`、`/company`；分别得到中文帮助、空市场、空 IPO、未找到、空公告、仅玩家可认购和旧公司帮助。
- 正常 `stop` 时 BlockStock 先排空 SQL executor，Hikari 连接池完整关闭；随后再次启动到 ready，并保持服务器在线。

## 真实玩家 IPO（本次未执行）

真实关闭 IPO 需要已认证玩家、可用 Vault 经济提供方、可验证的公司资产和实际资金。未捕获这些真实环境证据时，不得将创建公司、公告 IPO、等待开放、玩家认购、关闭上市、获得代码，或后续 `/stock` 查询标为通过。

本次已记录自动化构建、真实重启和 console 空市场检查；真实玩家 IPO 烟测仍为**未执行**，由已认证玩家在受控服务器上完成。
