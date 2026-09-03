# Bloeco-Stock 可选资产适配器

Bloeco-Stock 的公司和 IPO 可绑定原生资产，也可读取已安装插件暴露的领地或商店资产。外部插件均为可选依赖：未安装、未启用或版本不兼容时，Bloeco-Stock 不下载 JAR、不调用私有 API、不读写对方数据库，服务器仍可正常运行。

| 提供方 | 用途 | 适配标识 |
| --- | --- | --- |
| 原生资产 | 无外部依赖的基础绑定 | `blockstock-native` |
| GriefPrevention | 领地资产 | `griefprevention` |
| Lands | 领地资产 | `lands` |
| Residence | 领地资产 | `residence` |
| QuickShop-Hikari / QuickShop | 箱子商店资产 | `quickshop` |
| Shopkeepers | 店铺资产 | `shopkeepers` |

绑定只读取可验证的所有权和收入事件；Bloeco-Stock 不接管领地、商店或其经济结算。外部插件升级后，先在测试服验证资产发现和权限，再部署到生产服。
