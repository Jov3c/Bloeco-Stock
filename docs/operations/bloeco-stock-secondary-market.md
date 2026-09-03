# Bloeco-Stock 二级市场运维

## 开市前检查

1. Paper 1.21.11、Java 21、Vault、Bloeco 与 Bloeco-Stock 均已启用。
2. Vault 只显示 Bloeco 一个 Economy provider。
3. `plugins/Bloeco-Stock/` 已完成备份。
4. 蓝筹准备金已由 Bloeco 国库划拨，且不低于 `market.bluechip-reserve-total`。
5. 日志显示 `Bloeco-Stock ready; secondary trading=open`。

## 交易规则

- 市场在服务器时区每天 08:00–20:00 开放。
- 玩家通过 GUI 查看分时线、日 K、成交量、五档盘口、成交与持仓。
- 委托为限价单；提交、成交、撤单和资金结算均通过 Vault/Bloeco。
- 市场数据每秒刷新；系统蓝筹持续做市和交易，玩家离开 GUI 后市场仍继续运行。

## 维护与异常

当证券现金对账、待决资金操作或准备金状态不满足时，Bloeco-Stock 会关闭入金、出金、买卖和撤单，并保持只读行情。使用 `/stockadmin recovery cash` 读取状态；不要直接改数据库或通过第二个经济插件补余额。

## 停服

仅使用控制台 `stop` 正常停服，等待 Paper 保存完成后再备份或替换插件。测试服也必须使用独立的 Bloeco 数据库和准备金，不能连接生产账本。
