# 二级市场上线与烟测

本清单仅用于测试服。二级市场会调用真实 Vault 经济账户；不要在未备份的生产经济服上模拟停服边界。

## 上线前

1. 使用 Java 21 执行 `./gradlew.bat clean test shadowJar`，并确认 `build/libs/` 只有一个 `bloeco-stock-*-all.jar`。
2. 确认 Paper 1.21.4、Vault 与唯一经济提供方已在服务端加载；客户端不安装模组或插件。
3. 在关闭 Paper 后备份 `plugins/Bloeco-Stock/blockeco.db`，同时保留在线 SQLite 的 `-wal` 与 `-shm` 文件（若存在）。
4. 检查 `market.fee-bps` 在 0–10000，`market.time-zone` 是有效 Java `ZoneId`；重启以应用配置。

## 启动检查

1. 启动后先执行 `/stock market` 和 `/stock book <代码>`；公开只读行情应可用。
2. 以 OP 执行 `/stockadmin recovery cash`（或 `/stockadmin reconcile`）。记录物理托管、最终负债、已确认差额与不确定金额。
3. 只有诊断显示“变更=可用”时，才允许 `/stock deposit`、`withdraw`、`buy`、`sell`、`cancel`。若显示“已关闭”，不要手动改数据库或重试资金动作；保留该报告并核对 Vault 经济记录。

## 玩家交易测试

1. 两名测试玩家向证券账户入金；检查 `/stock cash` 的可用/冻结余额与个人钱包分离。
2. 卖方提交 `/stock sell <代码> <股数> <限价>`，买方提交可成交的 `/stock buy`。检查 `/stock book` 固定显示买卖各五档、`/stock orders`、`/stock trades`、`/stock portfolio` 与 `/stock market` 的最新价/成交量一致。
3. 提交一笔不成交委托后执行 `/stock cancel <订单UUID>`，确认冻结资金或股份释放一次且只释放一次。
4. 在受控测试中完成一笔资金操作后立即正常 `stop` 并重启；不要人为杀进程。重启后再次检查 `/stockadmin recovery cash`，确认没有重复 Vault 调用或自动消歧。

## 未执行即未验证

本清单不代表生产真实玩家、外部资产适配器、K 线、分红、增发投票、回购或退市已经验证。只有记录到具体服务器日志、命令输出和 Vault 账目后，才能把对应步骤标记为已执行。
