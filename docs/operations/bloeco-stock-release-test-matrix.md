# Bloeco-Stock 发布测试矩阵

每次发布必须在隔离 QA 服或明确的本地 QA 目录执行，并保存构建日志、测试结果和 Paper 启动日志。生产服不用于故障注入。

| 范围 | 必测项 | 通过条件 |
| --- | --- | --- |
| 构建 | `clean test shadowJar` | 全部单元/集成测试通过，生成唯一 Shadow JAR |
| 启动 | Paper + Vault + Bloeco + Bloeco-Stock | Bloeco 是唯一 Vault provider；Bloeco-Stock ready |
| 准备金 | 不足/满足两种状态 | 不足不开市；满足后 `secondary trading=open` |
| 资金 | 证券入金、出金、下单、成交、撤单 | 全部通过 Vault/Bloeco 且不重复结算 |
| 公司 | 创建、绑定、IPO、认购、上市 | 状态、股份与公告一致 |
| 治理 | 分红、增发投票、回购、退市 | 权限、公告和状态机一致 |
| GUI | 总览、行情、日 K、盘口、买卖确认 | 页面可达、返回可用、确认只能消费一次 |
| 恢复 | 正常重启与模拟未决记录 | 无重复 Vault 调用；歧义记录冻结并可诊断 |

发布前还应确认：插件名为 `Bloeco-Stock`、数据目录迁移不覆盖现有目录、没有第二个 Economy provider，也没有向 GitHub 提交运行时数据库、Paper JAR、日志或测试世界。
