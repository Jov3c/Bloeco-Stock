# Bloeco-Stock 部署与命名迁移

Bloeco-Stock 是 Bloeco 体系中的证券插件；Bloeco（CentralEconomy）是服务器唯一的货币账本。Bloeco-Stock 只通过 Vault 进行扣款、入账和托管，不维护第二套可结算货币。

## 安装顺序

1. 停止 Paper，并完整备份旧插件目录与 SQLite 的 `blockeco.db`、`-wal`、`-shm` 文件。
2. 安装 Vault、Bloeco（CentralEconomy）和 `bloeco-stock-*-all.jar`；确保 Essentials 等旧 Economy provider 不再注册为 Vault Economy。
3. 先启动 Bloeco，确认它是 Vault 的唯一 Economy provider；再启动 Bloeco-Stock。
4. 蓝筹市场需由管理员先将既有国库余额划拨到受限准备金；余额不足时不得开放市场。

## 从旧名称迁移

插件在首次以 `Bloeco-Stock` 启动时，会在新目录不存在的前提下依次迁移：

- `plugins/BlockStock/`
- `plugins/BlockecoExchange/`

目标始终是 `plugins/Bloeco-Stock/`。如果目标已存在，插件不会覆盖或合并任何文件；保留所有目录，人工核对备份后再处理。

命令 `/stock`、`/company` 与 `/stockadmin` 以及现有权限节点保持兼容。本次改名只改变公开插件名、GUI 标题、日志和默认中文文案。
