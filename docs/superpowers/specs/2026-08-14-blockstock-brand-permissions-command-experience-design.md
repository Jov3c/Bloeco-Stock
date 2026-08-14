# BlockStock 品牌、权限与命令体验设计

## 目标

将插件正式命名为 BlockStock，并同时解决命令缺少输入提示的问题：空命令给出中文帮助、Tab 只显示发送者有权限的子命令、旧的 BlockecoExchange 数据目录在安全条件下迁移到新目录。

## 品牌与兼容性

插件展示名、Shadow JAR 基名、README 和本地运行目录名称使用 BlockStock。Java 包名 cn.blockeco.exchange、数据库表、审计事件、配置键、权限节点和命令名称保持不变，避免破坏既有实现或服主权限配置。

Paper 会按 plugin.yml 的 name 建立数据目录。启动时，若新目录 plugins/BlockStock 不存在且旧目录 plugins/BlockecoExchange 存在，插件将把整个旧目录移动到新目录，再执行 saveDefaultConfig 与数据库迁移。若新目录已存在，则绝不移动、覆盖或合并旧目录，并在控制台写出明确迁移跳过说明。移动失败会使插件安全停用而不是创建空数据库。

本地 run 服务端应保留相同的迁移路径，以验证现有 config.yml 和 blockeco.db 能被继续使用。

## 命令体验与权限

根命令 /company 不再返回 Paper 默认 usage；它根据发送者的具体权限给出中文帮助。帮助内容从运行时配置读取金额、股本和分红档位，避免服主调整经济参数后提示过期。创建解析、帮助和 Tab 补全使用同一个已验证的分红档位集合；默认仍为 30/50/70：

- 有创建权限：显示 /company create <公司名> <30|50|70>，并逐项说明当前创建费、最低注册资本、合计所需余额、初始发行股数、可选分红比例，以及创建者必须是玩家、插件须完成初始化、公司名不能重复。
- 有查询权限：显示 /company info <公司名>。
- 有恢复权限：显示 /company recovery list。
- 无任何公司权限：显示已有的中文无权限提示。

TabCompleter 在启动窗口和 ready 状态都注册，行为与原版 /gamemode 的候选菜单一致：玩家输入 `/company ` 并按 Tab 时可看到候选、用方向键选择或继续手输。它只返回常量候选值，不访问 SQLite：

- 根参数按照具体权限返回 create、info、recovery。
- create 在已有至少一个公司名参数后返回 30、50、70，并按当前前缀过滤。
- recovery 的第二参数只向恢复管理员返回 list。
- info、未知路径、无权限路径返回空列表，避免泄露公司名。

运行时命令继续逐项校验 blockeco.company.create、blockeco.company.info、blockeco.admin.recovery；plugin.yml 增加仅用于授予便利的 blockeco.player 和 blockeco.admin 父权限组，children 指向既有具体节点。

## 验证

- TDD 覆盖空命令帮助中的动态金额、股本、分红档位和创建条件；以及玩家/管理员根补全、恢复隔离、股息档位与前缀、多词名称、控制台和无权限发送者。
- TDD 覆盖旧目录迁移成功、新目录存在时不覆盖、移动失败停用，以及迁移后读取同一 SQLite 数据。
- 构建出的唯一 Shadow JAR 名为 blockstock，plugin.yml 显示 BlockStock。
- 本地 Paper 以 Aoozzz OP 启动，确认 /company 中文帮助、管理员 recovery/list 补全和现有数据目录迁移；未实际执行的玩家扣款流程保持明确记录。

## 非目标

- 不增加中文命令别名。
- 不在补全过程同步查询数据库。
- 不修改股票业务、Vault 经济调用或第三方 Paper/Vault/EssentialsX 日志。
