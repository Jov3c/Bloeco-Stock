# Bloeco-Stock 恢复与资金安全

Bloeco-Stock 将资金操作写入持久化状态后才继续下一步。它通过 Vault 调用 Bloeco，但不会在恢复时盲目重放外部扣款或入账。

| 情况 | 行为 |
| --- | --- |
| 已证明 Vault/Bloeco 结果 | 仅完成缺失的本地投影，保证幂等 |
| 无法证明资金是否移动 | 标记为 `AMBIGUOUS`，冻结相关操作 |
| 已知退款应做但未完成 | 记录为待补偿，由管理员核对 Bloeco 总账后处理 |

排障顺序：正常停服、备份 SQLite 和 Bloeco 总账、查看恢复命令输出和日志 correlation ID、再到 Bloeco 账本核对。不得直接修改 SQLite 余额，也不得以玩家 UUID 充当托管账户。
