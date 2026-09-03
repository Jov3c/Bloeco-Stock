# Bloeco-Stock

Bloeco-Stock 是 Minecraft Paper 服务端的公司与证券交易所插件。它提供玩家公司、资产绑定、IPO、限价委托、公开行情、五档盘口、公司财务、定期分红、增发投票、回购与退市，以及系统蓝筹和市场事件。

原版客户端可以直接加入服务器：不需要安装客户端模组或资源包。

## 产品边界

Bloeco-Stock **只处理证券业务**：公司、股份、订单、成交、行情、公告和证券托管规则。

Bloeco 是全服唯一的经济基础和 Vault Economy provider：它管理玩家余额、国库、税费和总账。Bloeco-Stock 不发行货币、不维护第二套可结算余额，也不会绕过 Vault 直接改写玩家资金。每一笔需要结算的证券资金移动都通过 Vault/Bloeco 完成。

| 组件 | 职责 |
| --- | --- |
| Bloeco | 货币账本、国库、税费、Vault Economy provider |
| Vault | Bloeco-Stock 与 Bloeco 之间的标准经济接口 |
| Bloeco-Stock | 公司、证券、市场、托管、行情与交易规则 |

## 运行环境

- Paper `1.21.11`
- Java `21`
- Vault
- Bloeco（必须是唯一启用的 Vault Economy provider）

可选适配：GriefPrevention、Lands、Residence、QuickShop-Hikari、QuickShop、Shopkeepers。未安装这些插件时，Bloeco-Stock 仍可使用原生资产绑定，不会阻止服务器启动。

## 构建与安装

```powershell
.\gradlew.bat clean test shadowJar
```

将 `build/libs/bloeco-stock-*-all.jar`、Vault 和 Bloeco 的 JAR 放入 Paper 的 `plugins/` 目录。启动顺序、旧数据目录迁移、准备金和回退步骤见 [部署说明](docs/operations/bloeco-stock-deployment.md)。

首次启动后，数据位于：

```text
plugins/Bloeco-Stock/config.yml
plugins/Bloeco-Stock/blockeco.db
```

## 主要功能

- 原版箱子 GUI：市场总览、个股行情、分时/日 K、成交量、五档盘口、买卖、公司和 IPO。
- 玩家公司：创建、资产绑定、财报、15 天分红、回购、创始人套现公告、增发投票与退市。
- 证券市场：限价委托、撮合、撤单、持仓、成交记录、公开公告和公司市值。
- 蓝筹市场：10 家系统公司、受限做市准备金、随机行业/大盘事件和持续交易。
- 可审计恢复：资金操作采用持久化状态与恢复门禁，无法证明外部结果时停止而不猜测。

## 配置和权限

常用设置位于 `plugins/Bloeco-Stock/config.yml`：

- `company.minimum-capital`：最低注册资本；管理员可用 `/stockadmin config min-capital <金额>` 调整。
- `market.time-zone`：交易时区；交易时间为服务器时区每天 08:00–20:00。
- `market.bluechip-reserve-total`：系统蓝筹市场需要的受限准备金总额。
- `company.treasury-escrow-uuid`：仅供 Bloeco-Stock 使用的非玩家 Vault 托管身份；不能填玩家 UUID。

玩家从 `/stock` 打开交易所，从 `/company` 打开公司中心。管理员权限使用 `blockeco.admin`；玩家权限使用 `blockeco.player`。完整命令与验收点见 [需求追溯清单](docs/operations/bloeco-stock-requirements.md)。

## 文档

- [部署、Bloeco 集成与迁移](docs/operations/bloeco-stock-deployment.md)
- [资产适配器](docs/operations/bloeco-stock-asset-adapters.md)
- [IPO 与上市运维](docs/operations/bloeco-stock-ipo.md)
- [二级市场与恢复](docs/operations/bloeco-stock-secondary-market.md)
- [资金恢复与对账](docs/operations/bloeco-stock-recovery.md)
- [测试矩阵](docs/operations/bloeco-stock-release-test-matrix.md)
- [需求追溯](docs/operations/bloeco-stock-requirements.md)

## 开发验证

当前源码回归执行 `clean test shadowJar`，并使用 Paper 1.21.11、Vault、Bloeco 与 Bloeco-Stock 完成实服启动联调。验证内容包括：Bloeco 注册为唯一 Vault Economy provider，QuickShop 使用 Bloeco，Bloeco-Stock 在准备金满足时记录 `secondary trading=open`。

## 旧目录迁移

首次改名部署时，若 `plugins/Bloeco-Stock/` 不存在，插件会依次识别 `plugins/BlockStock/` 与 `plugins/BlockecoExchange/` 并迁移整个目录。若新目录已存在，则绝不覆盖；应保留现有目录并从备份核验后再处理。
