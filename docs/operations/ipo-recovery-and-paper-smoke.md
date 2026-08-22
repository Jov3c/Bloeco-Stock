# IPO 认购启动恢复与运维检查

启动时先通过 Vault 托管身份的前置检查，然后依次恢复历史公司资本化和 IPO 认购；任一 SQL 恢复失败时插件不会接受公司或 IPO 命令。

IPO 恢复从不发起 Vault 调用。`ESCROW_DEPOSITED` 只在 SQLite 内完成一次现金、持股、总股数及审计写入；`PREPARED`、`PLAYER_WITHDRAWN` 标记为 `AMBIGUOUS`，必须人工核对经济提供方历史。`AMBIGUOUS` 保留不动。使用 `/company recovery list` 查看注册、资本托管和 IPO 三类记录；该命令无退款、完成或重放路径，显示的 IPO 行不包含托管 correlation/escrow UUID。

本轮自动验证（2026-08-22）：真实 SQLite 覆盖 `ESCROW_DEPOSITED`、`PREPARED`、`PLAYER_WITHDRAWN` 的两次启动恢复、幂等现金/股份和 Vault 调用为零；覆盖管理员只读投影与启动门控顺序/失败拒绝。尚未进行 authenticated player 的真实 Paper/Vault 认购烟测。
