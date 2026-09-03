# Bloeco-Stock 部署、经济集成与迁移

## 责任边界

Bloeco 是服务器唯一货币账本和 Vault Economy provider。Bloeco-Stock 仅通过 Vault 结算证券相关资金：它不铸币、不保存第二套玩家现金余额、不绕过 Bloeco 总账。

公司资本、IPO 认购、证券入金/出金和成交结算都有 Bloeco 余额变动与 Bloeco-Stock SQLite 操作记录。外部结果不能被证明时，Bloeco-Stock 会关闭受影响操作并要求管理员核对，而不是自动猜测或退款。

## 安装顺序

1. 正常停止 Paper，并备份 `plugins/Bloeco-Stock/` 的 `blockeco.db`、`blockeco.db-wal`、`blockeco.db-shm`（若存在）。
2. 安装 Paper 1.21.11、Java 21、Vault、Bloeco 和 `bloeco-stock-*-all.jar`。
3. 禁用其他 Vault Economy provider；启动 Bloeco，确认日志显示它已注册为 Vault 唯一 Economy provider。
4. 启动 Bloeco-Stock，确认日志出现 `Bloeco-Stock ready`。准备金满足时还应出现 `secondary trading=open`。

不要将 Vault、Bloeco 或 Bloeco-Stock 安装到客户端。

## 系统蓝筹准备金

系统蓝筹的初始流动性是 Bloeco 国库划拨的**受限做市准备金**，不是 Bloeco-Stock 发行的新钱。管理员在 Bloeco 中先完成国库发行/既有余额准备，再将不低于 `market.bluechip-reserve-total` 的金额划拨到 Bloeco-Stock 配置的准备金托管身份。

准备金不足时，Bloeco-Stock 必须保持二级市场关闭；不得通过修改数据库、模拟玩家余额或 Bloeco-Stock 内部余额绕过该限制。具体发行与 `reserve fund` 命令由 Bloeco 管理，详见 Bloeco 的运维文档。

## 旧目录迁移

目标目录是 `plugins/Bloeco-Stock/`。若其不存在，插件会按顺序识别：

1. `plugins/BlockStock/`
2. `plugins/BlockecoExchange/`

迁移会整体移动旧目录，以保留配置、SQLite 数据库和关联 WAL/SHM 文件。若新目录已存在，插件绝不覆盖，管理员必须先从备份确认数据。迁移前后都应使用 Paper 控制台的 `stop` 正常停服。

## 回退

回退前停止 Paper，备份当前 Bloeco-Stock 数据库及 Bloeco 总账。只回退 JAR 而不回退数据库可能造成未兼容的模式或配置；应在测试服完成同版本恢复验证后再处理生产服。
