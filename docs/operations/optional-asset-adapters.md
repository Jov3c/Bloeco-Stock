# 可选资产适配器

BlockStock 的公司和 IPO 不依赖第三方领地或商店插件。`blockstock-native` 原生资产始终可用；未安装外部插件时，服务器照常启动，玩家也可以创建公司、绑定原生资产并继续后续流程。

## 支持的可选提供方

BlockStock 在启动时只会检测下列已安装且已启用的插件，不会下载 JAR、不会加载其私有类，也不会读取或修改对方数据库：

| 提供方 | 检测的插件名 | 兼容适配器 ID |
| --- | --- | --- |
| Lands | `Lands`、`LandsFree` | `lands` |
| Residence | `Residence` | `residence` |
| QuickShop-Hikari | `QuickShop-Hikari`、`QuickShop` | `quickshop` |
| Shopkeepers | `Shopkeepers` | `shopkeepers` |

检测到提供方但未注册对应适配器时，控制台会输出中文说明。它不会出现在“可绑定资产”的 GUI 列表，也不能用手工 ID 绕过归属验证。这是故意的安全限制：资产绑定必须由针对该版本 API 的适配器完成所有权核验。

## 接入一个兼容适配器

第三方兼容模块应在自身启用后，通过服务器提供的 `CompanyAssetAdapterRegistry` 服务注册一个 `CompanyAssetAdapter`（如需 GUI 选择器，同时实现 `AssetCatalogAdapter`）。适配器必须：

1. 使用插件官方 API 验证资产实际属于请求玩家；验证失败时返回 `ownedByRequester=false`。
2. 为每个资产提供稳定、不含玩家输入拼接的 `externalKey`。
3. 只在其提供方插件已启用时注册，并在禁用时注销。
4. 将可能阻塞的数据库/API 查询安排在适配器允许的线程模型中；不要在异步线程调用 Bukkit 世界或实体 API。

BlockStock 本体不将 Lands、Residence、QuickShop 或 Shopkeepers 打进成品包。请由服务器所有者从相应官方渠道取得与服务端版本、许可相符的 JAR，再由经过版本验证的兼容模块注册适配器。这样外部插件缺失、升级或 API 变动都不会使证券账本或服务器启动失败。
