# BlockStock Secondary Trading Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不混同个人钱包、个人证券现金和公司资金的前提下，交付可恢复、可审计的 BlockStock 二级限价交易核心：现金入/出金、冻结、撮合、成交、盘口与中文 `/stock` 查询。

**Architecture:** 所有 SQLite 读写、状态转换和撮合均串行进入现有 `BlockStock-SQL` 单线程 executor；领域服务只编排 immutable intent、交易事务与外部 Vault saga。Vault/Bukkit/玩家消息只在 Paper 主线程执行，Vault 调用的返回结果再投回 SQL executor 落状态；若外部调用后进程崩溃，操作是 `AMBIGUOUS`，启动时只报告/对账，绝不自动重试。V007 新增个人证券现金账户、补偿基金、append-only 托管分录、现金操作、委托和成交事实；公司现金账户继续作为公司负债的唯一余额事实，二级卖出款只贷记卖方的个人证券现金账户。真实托管余额与三类内部负债在独立启动步骤中对账，异常只关闭资金/交易写命令，不关闭公开只读行情和管理员诊断。

**Tech Stack:** Java 21, Paper 1.21.4, Vault API 1.7, SQLite/HikariCP, JUnit 5, AssertJ, Mockito, 原版客户端。

**Spec:** `docs/superpowers/specs/2026-08-14-blockstock-exchange-and-company-finance-design.md`（本计划将其第二阶段缩减为固定范围的交易核心）。

## Global Constraints

- 仅新增 V007；V001–V006、已有注册/实缴/IPO saga 和上市代码分配冻结。严格排除 K 线、月报、分红、治理、回购、退市、外部资产适配器。
- Java 21、Paper 1.21.4、Vault、SQLite；金额使用 `Money`/`long minor`，价格 tick 固定为 `1 minor`，整数股、仅 GTC 限价单；无 double、杠杆、融券或做空。
- 个人 Vault 钱包、个人证券现金账户和公司账户严格隔离。受控 `treasury-escrow-uuid` 的实际 Vault 余额必须等于 `SUM(company_cash_accounts.cash_minor) + SUM(securities_cash_accounts.available_minor + reserved_minor) + compensation_fund.balance_minor`；append-only 托管分录还要分别与三类余额核对。补偿基金只累计手续费，不实现赔付。
- `/stock cash`、`/stock deposit <金额>`、`/stock withdraw <金额>` 是唯一的个人证券现金进出边界。下单从个人证券现金冻结；卖单成交收入留在该账户，绝不直接进入 Vault 或公司账户；公司资金表绝不被个人二级交易更新。
- Vault 不是事务性/幂等 API：充值和提现都各含两次外部调用。每一步都先有持久化状态，再在主线程调用，结果回 SQL executor 推进；任一 provider 调用开始后的失败/null/异常/超时或落库失败都标为 `AMBIGUOUS`，保留冻结且禁止自动重试/自动退款。只有能证明 provider 尚未被调用的本地拒绝/停服取消（包括调用前 `has=false`）可以 `FAILED` 并释放冻结。管理员只获得只读恢复清单和人工对账。
- 买方单独承担手续费，卖方收到完整成交额。买单初始冻结 `limitPrice × shares + ceil(limitPrice × shares × feeBps / 10000)`；订单固定其 `fee_bps`，手续费按该买单**累计实际成交额**计算增量：`feeDelta = ceil((oldFilledNotional + fillNotional) × bps / 10000) - oldFeeCharged`。因此拆成多笔成交不会多收费，预留也不会不足；每次成交立即释放价格改善与不再需要的最坏手续费。卖单冻结股份。所有乘加与 ceil 分子使用 exact arithmetic。
- 每个代码簿按价格优先、同价 `priority_sequence` 单调序列严格时间优先；`accepted_at` 只展示审计。支持部分成交/撤单。若 taker 遇到同一玩家的最优可成交 maker，原 maker 保持不动、确定性取消 taker 剩余并释放其冻结资产，防止自成交。
- 最新价/涨跌/成交量/成交额、五档和用户 portfolio/orders/trades/book 来自持久化成交/委托事实；不泄露订单或成交对手方身份。交易全天 24/7；日涨跌基准是 `market.time-zone` 当日开始前最后一笔成交价，无历史成交则用 IPO 参考价，查询本身不得写入或重置市场事实。
- Bukkit、Vault、`sendMessage`、`getOfflinePlayer` 必须回主线程；tab completion 只读不可变内存快照且不阻塞。runtime epoch/`accepting` 阻止新命令、新 Vault 调用和晚到 Bukkit 消息，但已经发生外部资金动作的 saga 必须继续落成确定状态或 `AMBIGUOUS`，不得因停服静默丢弃。

## Sources / compatibility constraints

- Paper 调度与主线程边界：[Paper scheduler documentation](https://docs.papermc.io/paper/dev/scheduler/)；任何异步任务不得触碰 Bukkit API，结果用 `runTask`/`PaperMainThread` 回主线程。
- 命令与补全契约：[Paper 1.21.4 `TabExecutor`](https://jd.papermc.io/paper/1.21.4/org/bukkit/command/TabExecutor.html)；命令执行与补全不等待 SQL/Vault future。
- Vault 外部经济接口：[Economy](https://github.com/MilkBowl/VaultAPI/blob/master/src/main/java/net/milkbowl/vault/economy/Economy.java) 和 [AbstractEconomy](https://github.com/MilkBowl/VaultAPI/blob/master/src/main/java/net/milkbowl/vault/economy/AbstractEconomy.java)；只根据一次返回值判定可证明成功/失败，不假设 provider 支持 idempotency key。
- 人工联调版本下限：[EssentialsX 2.21.0](https://hangar.papermc.io/EssentialsX/Essentials/versions/2.21.0)。未具备真实已认证玩家与 Vault provider 的情况必须在 smoke 文档中记为“未执行”。

---

## File Structure

- `V007.sql` / `Database.java`：交易账户、补偿基金、append-only 托管分录、双外部步骤入出金 saga、订单、成交、单调优先序列和审计约束，并显式注册 V007。
- `domain/finance/*Securities*`, `domain/trading/*`：账户、订单、成交和确定性价格/费用不变量；不依赖 Bukkit/Vault。
- `ports/SecondaryTradingRepository.java`, `ports/SecuritiesCashRepository.java`, `ports/SecuritiesCashGateway.java` 与 SQL/Vault 实现：单一连接内的冻结、撮合、结算、取消和读模型；异步网关不以 `join()` 阻塞主线程。
- `application/SecuritiesCashService.java`, `SecondaryMarketService.java`, `SecondaryMarketRecoveryService.java`：持久化 intent、Vault saga、串行撮合调度、恢复只读视图。
- `paper/StockCommand.java`, `StockTabCompleter.java`, `Messages.java`, `BlockecoPlugin.java`, `PluginRuntime.java`：命令、缓存、主线程回复、生命周期/disable epoch。

### Task 1: V007 schema, account domain and reconciliation invariants

**Files:**
- Create: `src/main/resources/db/migration/V007.sql`
- Modify: `src/main/java/cn/blockeco/exchange/infrastructure/sql/Database.java`
- Create: `src/main/java/cn/blockeco/exchange/domain/finance/SecuritiesCashAccount.java`
- Create: `src/main/java/cn/blockeco/exchange/domain/finance/SecuritiesCashOperation.java`
- Create: `src/main/java/cn/blockeco/exchange/domain/finance/SecuritiesCashOperationState.java`
- Create: `src/main/java/cn/blockeco/exchange/domain/finance/SecuritiesCashDirection.java`
- Create: `src/main/java/cn/blockeco/exchange/domain/finance/EscrowLiabilityKind.java`
- Create: `src/main/java/cn/blockeco/exchange/domain/finance/EscrowReconciliation.java`
- Create: `src/main/java/cn/blockeco/exchange/domain/trading/LimitOrder.java`
- Create: `src/main/java/cn/blockeco/exchange/domain/trading/Trade.java`
- Create: `src/main/java/cn/blockeco/exchange/domain/trading/FeePolicy.java`
- Modify: `src/test/java/cn/blockeco/exchange/domain/finance/FinanceValuesTest.java`
- Modify: `src/test/java/cn/blockeco/exchange/infrastructure/sql/MigrationTest.java`

**Interfaces / invariants:** `SecuritiesCashAccount(UUID playerId, Money available, Money reserved)` has only a player owner and rejects negative components. `LimitOrder` contains `id, companyId/stockCode, playerId, side, limitPrice, originalShares, remainingShares, prioritySequence, reservedCash, filledNotional, feeCharged, feeBps, acceptedAt, state`; its GTC remaining shares are positive while OPEN/PARTIALLY_FILLED. `EscrowReconciliation` exposes physical balance, three final liabilities, proven inbound-not-yet-liability, proven outbound-still-liability, confirmed expected balance/difference and separately uncertain external amounts. Confirmed expectation is `liabilities + provenInbound - provenOutbound`; even a zero difference never resolves an AMBIGUOUS row. V007 creates `securities_cash_accounts`, singleton `compensation_fund`, append-only `escrow_ledger_entries`, `securities_cash_operations` (including direction, state and last confirmed external stage), monotonic `stock_order_sequence`, `stock_orders`, and `stock_trades`; CHECK/PK/FK/indexes enforce one player account, positive integer quantities, `OPEN|PARTIALLY_FILLED|FILLED|CANCELLED|SELF_TRADE_PREVENTED`, and immutable trade IDs.

- [ ] **RED tests:** add exact tests that reject a negative cash reserve and an overfilled order; upgrade a real V006 fixture and assert `Database.MIGRATIONS` executes V007 exactly once, creates every table/sequence/index, initializes a zero compensation fund, backfills one `COMPANY_TREASURY` opening ledger entry equal to `SUM(company_cash_accounts.cash_minor)`, and leaves V001–V006 checksums unchanged. UUID has no player/company type at SQLite level, so do not claim a CHECK can distinguish them; only player-facing cash service may create securities accounts.

- [ ] **Run RED:** `./gradlew.bat test --tests cn.blockeco.exchange.domain.finance.FinanceValuesTest --tests cn.blockeco.exchange.infrastructure.sql.MigrationTest` — expected failure: V007/domain types absent.

- [ ] **Implementation:** write V007 and append it to `Database.MIGRATIONS`; freeze V001–V006. Keep `company_cash_accounts` and `treasury_operations` as authoritative existing company balances/operations; V007 adds auditable opening/current deltas rather than a redundant mutable company total. Allocate `priority_sequence` by conditional increment of a singleton row in the same transaction as reserve+order insert. Add book indexes `(company_id,state,side,limit_price_minor,priority_sequence)`, player order keys and trade time keys. Persist `reserved_cash_minor`, `filled_notional_minor`, `fee_charged_minor` and immutable `fee_bps` on every buy order so restart/cancel never re-derive money from live config.

- [ ] **GREEN:** rerun the RED command; expected pass proves clean upgrade, exact reconciliation arithmetic and schema constraints.

- [ ] **Commit:** `git add src/main/resources/db/migration/V007.sql src/main/java/cn/blockeco/exchange/infrastructure/sql/Database.java src/main/java/cn/blockeco/exchange/domain/finance src/main/java/cn/blockeco/exchange/domain/trading src/test/java/cn/blockeco/exchange/domain/finance/FinanceValuesTest.java src/test/java/cn/blockeco/exchange/infrastructure/sql/MigrationTest.java && git commit -m "feat: add secondary market schema"`

### Task 2: SQL repository for segregated balances, reservations and atomic settlement

**Files:**
- Create: `src/main/java/cn/blockeco/exchange/ports/SecuritiesCashRepository.java`
- Create: `src/main/java/cn/blockeco/exchange/ports/SecondaryTradingRepository.java`
- Create: `src/main/java/cn/blockeco/exchange/infrastructure/sql/SqlSecuritiesCashRepository.java`
- Create: `src/main/java/cn/blockeco/exchange/infrastructure/sql/SqlSecondaryTradingRepository.java`
- Modify: `src/main/java/cn/blockeco/exchange/infrastructure/sql/SqlCompanyFinanceRepository.java`
- Modify: `src/main/java/cn/blockeco/exchange/infrastructure/sql/SqlPrimaryOfferingRepository.java`
- Create: `src/test/java/cn/blockeco/exchange/infrastructure/sql/SqlSecondaryTradingRepositoryTest.java`

**Interfaces / invariants:** all mutators receive the caller-owned `Connection`; `reserveBuy`, `reserveSell`, `settleTrade`, `releaseOrder`, and `cancelTakerForSelfTrade` execute no separate connection. `FeePolicy.cumulativeFee(notional,bps) = ceil(notional*bps/10000)` with bps 0..10,000; a fill charges only the delta from the buy order's prior cumulative fee, so fill partitioning never changes total fees. Every balance update is conditional on expected reserved/available values. `settleTrade` atomically inserts a `Trade`, decrements both reservations, credits buyer available shares, credits seller **personal securities cash**, credits compensation by the buyer fee, releases exact price/fee improvement, appends escrow/audit entries, and returns residual amounts. `reconcile()` derives personal/company/compensation from their authoritative balance tables and separately verifies append-only ledger sums; no redundant mutable aggregate may mask drift.

- [ ] **RED tests:** seed two holders, buyer cash and a listed code. Assert a 100-share buy at limit 12 with 100 bps reserves 1,212; a maker sale at 10 settles 1,000 plus buyer fee 10, gives seller 1,000, compensation 10, buyer shares 100, and releases 202. Split an order into multiple one-share fills and assert cumulative fee equals one unsplit fill, never exceeds initial reserve, and restart/reload preserves exact reserve. Assert failed conditional reservation changes neither account/order nor liability, duplicate trade/cancel is idempotently rejected, a trade cannot mutate `company_cash_accounts`, and reconciliation catches a deliberately altered balance or ledger. Assert capitalization and IPO completion each append the correct COMPANY_TREASURY delta in the same connection, while secondary trading appends none.

- [ ] **Run RED:** `./gradlew.bat test --tests cn.blockeco.exchange.infrastructure.sql.SqlSecondaryTradingRepositoryTest` — expected failure: repository contract absent.

- [ ] **Implementation:** use prepared statements only and never calculate funds in SQL floating point. Lock the relevant SQLite write transaction through the existing transaction runner, load/update exact buyer/seller holding and cash rows, then write trade/audit/escrow-ledger facts before commit. After a fill compute `newFilledNotional`, `newFeeCharged`, and the remaining worst-case reserve `remaining*limit + (ceil((newFilledNotional + remaining*limit)*bps/10000) - newFeeCharged)`; release the exact difference. Modify both capitalization and IPO-completion company-cash paths to append COMPANY_TREASURY deltas in their same connection. Return typed `InsufficientCash`, `InsufficientShares`, arithmetic-overflow and optimistic-state failures rather than partial writes.

- [ ] **GREEN:** rerun the repository test; expected pass includes partial arithmetic, no company leakage, rollback, exact ceil fee and reconciliation.

- [ ] **Commit:** `git add src/main/java/cn/blockeco/exchange/ports/SecuritiesCashRepository.java src/main/java/cn/blockeco/exchange/ports/SecondaryTradingRepository.java src/main/java/cn/blockeco/exchange/infrastructure/sql/SqlSecuritiesCashRepository.java src/main/java/cn/blockeco/exchange/infrastructure/sql/SqlSecondaryTradingRepository.java src/main/java/cn/blockeco/exchange/infrastructure/sql/SqlCompanyFinanceRepository.java src/main/java/cn/blockeco/exchange/infrastructure/sql/SqlPrimaryOfferingRepository.java src/test/java/cn/blockeco/exchange/infrastructure/sql/SqlSecondaryTradingRepositoryTest.java && git commit -m "feat: persist segregated trading settlement"`

### Task 3: Cash deposit/withdraw intent saga and non-retrying recovery

**Files:**
- Create: `src/main/java/cn/blockeco/exchange/application/SecuritiesCashService.java`
- Create: `src/main/java/cn/blockeco/exchange/application/SecuritiesCashResult.java`
- Create: `src/main/java/cn/blockeco/exchange/application/SecuritiesCashRecoveryRecord.java`
- Create: `src/main/java/cn/blockeco/exchange/ports/SecuritiesCashGateway.java`
- Modify: `src/main/java/cn/blockeco/exchange/ports/SecuritiesCashRepository.java`
- Modify: `src/main/java/cn/blockeco/exchange/infrastructure/sql/SqlSecuritiesCashRepository.java`
- Create: `src/main/java/cn/blockeco/exchange/infrastructure/vault/VaultSecuritiesCashGateway.java`
- Modify: `src/main/java/cn/blockeco/exchange/ports/EconomyGateway.java`
- Modify: `src/main/java/cn/blockeco/exchange/infrastructure/vault/VaultEconomyGateway.java`
- Create: `src/test/java/cn/blockeco/exchange/application/SecuritiesCashServiceTest.java`
- Modify: `src/test/java/cn/blockeco/exchange/infrastructure/vault/VaultEconomyGatewayTest.java`

**Interfaces / invariants:** `deposit(UUID player, Money amount)` and `withdraw(UUID player, Money amount)` return `CompletionStage<SecuritiesCashResult>`; operation IDs are fresh UUIDs, never provider retry keys, and a partial unique index permits only one nonterminal cash operation per player. Deposit transitions `PREPARED → PLAYER_WITHDRAWN → ESCROW_DEPOSITED → COMPLETED`. Withdraw atomically moves securities `available → reserved` at `PREPARED`, then transitions `ESCROW_WITHDRAWN → PLAYER_DEPOSITED → COMPLETED`; only completion removes the reserved personal liability. `FAILED` is legal only when local validation/cancellation proves the provider was not called, such as a pre-call `has=false`; once a provider method is invoked, any non-success/null/throw/timeout or persistence failure enters `AMBIGUOUS(lastConfirmedStage,reason)` and retains reserve. `SecuritiesCashGateway` returns stages for player withdraw, escrow deposit, escrow withdraw, player deposit and escrow balance; the adapter schedules actual Economy calls on Paper main and never calls `join()` from main. Startup may idempotently finish **local SQL only** from durable `ESCROW_DEPOSITED` (credit deposit) or `PLAYER_DEPOSITED` (finish withdrawal), without any Vault call; every other ambiguous/incomplete external path remains read-only and blocked.

- [ ] **RED tests:** for deposit verify PREPARED exists before player withdrawal, PLAYER_WITHDRAWN is durable before escrow deposit, both external calls occur exactly once on `PaperMainThread`, and only ESCROW_DEPOSITED→COMPLETED credits personal available/liability. For withdraw verify PREPARED atomically reserves available cash before any external call, escrow withdrawal is durable before player deposit, both calls occur exactly once, and only PLAYER_DEPOSITED→COMPLETED consumes reserve/liability. Cover crash/throw/null/provider-failure/SQL failure after **each invoked** external leg: mark/leave AMBIGUOUS, retain withdrawal reserve, and never auto-call either leg again. Cover provider-not-called local rejection releasing reserve, duplicate in-flight request rejection, startup local-only completion of durable final external stages with zero Economy interactions, other ambiguous rows unchanged, confirmed in-flight reconciliation adjustments, and exact finite/round-trip-safe Vault double conversion.

- [ ] **Run RED:** `./gradlew.bat test --tests cn.blockeco.exchange.application.SecuritiesCashServiceTest --tests cn.blockeco.exchange.infrastructure.vault.VaultEconomyGatewayTest` — expected failure: service/result/recovery API absent.

- [ ] **Implementation:** serialize each SQL phase on `sqlExecutor`; let `VaultSecuritiesCashGateway` marshal each Economy call to main and return without blocking Paper. Deposit is player wallet withdrawal, durable step, escrow deposit, durable step, then personal credit. Withdrawal reserves internal cash, withdraws escrow, durably records it, deposits player, durably records it, then removes the reserved liability. Treat only provider-not-called outcomes as FAILED; after invocation every result except a proved SUCCESS is AMBIGUOUS. Persist provider response summaries and last confirmed stage without assuming idempotency. Add idempotent startup local completion for durable `ESCROW_DEPOSITED`/`PLAYER_DEPOSITED`; this is not an external retry. Use only `OfflinePlayer` UUID overloads and never `createPlayerAccount`; append immutable intent/result/ambiguity escrow and audit entries; do not reuse IPO recovery.

- [ ] **GREEN:** rerun the RED command; expected pass proves intent-first ordering, exactly one invocation per external leg (two on a fully successful transfer), account segregation and no automatic AMBIGUOUS retry.

- [ ] **Commit:** `git add src/main/java/cn/blockeco/exchange/application/SecuritiesCashService.java src/main/java/cn/blockeco/exchange/application/SecuritiesCashResult.java src/main/java/cn/blockeco/exchange/application/SecuritiesCashRecoveryRecord.java src/main/java/cn/blockeco/exchange/ports/SecuritiesCashGateway.java src/main/java/cn/blockeco/exchange/ports/SecuritiesCashRepository.java src/main/java/cn/blockeco/exchange/ports/EconomyGateway.java src/main/java/cn/blockeco/exchange/infrastructure/sql/SqlSecuritiesCashRepository.java src/main/java/cn/blockeco/exchange/infrastructure/vault/VaultSecuritiesCashGateway.java src/main/java/cn/blockeco/exchange/infrastructure/vault/VaultEconomyGateway.java src/test/java/cn/blockeco/exchange/application/SecuritiesCashServiceTest.java src/test/java/cn/blockeco/exchange/infrastructure/vault/VaultEconomyGatewayTest.java && git commit -m "feat: add recoverable securities cash saga"`

### Task 4: Deterministic single-executor order matching and self-trade prevention

**Files:**
- Create: `src/main/java/cn/blockeco/exchange/application/SecondaryMarketService.java`
- Create: `src/main/java/cn/blockeco/exchange/application/OrderPlacementResult.java`
- Modify: `src/main/java/cn/blockeco/exchange/ports/SecondaryTradingRepository.java`
- Modify: `src/main/java/cn/blockeco/exchange/infrastructure/sql/SqlSecondaryTradingRepository.java`
- Create: `src/test/java/cn/blockeco/exchange/application/SecondaryMarketServiceTest.java`
- Modify: `src/test/java/cn/blockeco/exchange/infrastructure/sql/SqlSecondaryTradingRepositoryTest.java`

**Interfaces / invariants:** `placeBuy(UUID, String, long shares, Money limit)` and `placeSell(...)` return `CompletionStage<OrderPlacementResult>`; `cancel(UUID player, UUID orderId)` returns a terminal order result. Every call, including matching loop, is one FIFO runnable on the single SQL executor. Freeze, monotonic `priority_sequence` allocation and order insert share one transaction. `nextCrossingMaker(Connection,taker)` orders sell makers by `price ASC, priority_sequence ASC`, buy makers by `price DESC, priority_sequence ASC`; price is maker limit. A matching order is not externally visible until reserve/order/trades are committed.

- [ ] **RED tests:** give equal timestamps and reverse-sorted UUIDs, then assert lower allocated priority sequence fills first; assert sequence allocation is unique under concurrent submission. Assert a 10-share maker can fill two takers; one taker sweeps two price levels and receives exact cumulative-fee/price-improvement release; cancellation and repeated cancellation release only once and only the persisted remaining reserve; a crossing own-maker cancels only taker remaining with `SELF_TRADE_PREVENTED`, leaves maker OPEN, and makes no trade; concurrent submissions serialize to one deterministic book/trade sequence.

- [ ] **Run RED:** `./gradlew.bat test --tests cn.blockeco.exchange.application.SecondaryMarketServiceTest --tests cn.blockeco.exchange.infrastructure.sql.SqlSecondaryTradingRepositoryTest` — expected failure: matcher and order API absent.

- [ ] **Implementation:** validate stock is LISTED, amount/tick/fee config, then reserve assets and insert the taker in one transaction. Loop inside that transaction: find one best crossing maker; on same player invoke `cancelTakerForSelfTrade` and stop; otherwise settle `min(remaining)` at maker price. On every fill apply fee/release math from Task 2 and update terminal/partial states. If no cross, retain GTC residual. Cancel requires owner and OPEN/PARTIALLY_FILLED state. No Vault call occurs during placing, matching, cancellation or trade settlement.

- [ ] **GREEN:** rerun the RED command; expected pass proves price-time priority, partial fills, GTC, freeze/release correctness, deterministic self-trade prevention and serial concurrency.

- [ ] **Commit:** `git add src/main/java/cn/blockeco/exchange/application/SecondaryMarketService.java src/main/java/cn/blockeco/exchange/application/OrderPlacementResult.java src/main/java/cn/blockeco/exchange/ports/SecondaryTradingRepository.java src/main/java/cn/blockeco/exchange/infrastructure/sql/SqlSecondaryTradingRepository.java src/test/java/cn/blockeco/exchange/application/SecondaryMarketServiceTest.java src/test/java/cn/blockeco/exchange/infrastructure/sql/SqlSecondaryTradingRepositoryTest.java && git commit -m "feat: match GTC limit orders deterministically"`

### Task 5: Privacy-safe portfolio, order/trade, book and market read models

**Files:**
- Create: `src/main/java/cn/blockeco/exchange/application/SecondaryMarketQueryService.java`
- Create: `src/main/java/cn/blockeco/exchange/application/PortfolioView.java`
- Create: `src/main/java/cn/blockeco/exchange/application/OrderView.java`
- Create: `src/main/java/cn/blockeco/exchange/application/TradeView.java`
- Create: `src/main/java/cn/blockeco/exchange/application/OrderBookLevel.java`
- Create: `src/main/java/cn/blockeco/exchange/application/SecondaryMarketRow.java`
- Modify: `src/main/java/cn/blockeco/exchange/application/PublicStockQueryService.java`
- Modify: `src/main/java/cn/blockeco/exchange/application/PublicMarketRow.java`
- Modify: `src/main/java/cn/blockeco/exchange/ports/SecondaryTradingRepository.java`
- Modify: `src/main/java/cn/blockeco/exchange/infrastructure/sql/SqlSecondaryTradingRepository.java`
- Modify: `src/main/java/cn/blockeco/exchange/infrastructure/sql/SqlPublicStockRepository.java`
- Modify: `src/test/java/cn/blockeco/exchange/application/PublicStockQueryServiceTest.java`
- Create: `src/test/java/cn/blockeco/exchange/application/SecondaryMarketQueryServiceTest.java`

**Interfaces / invariants:** all query methods return `CompletionStage` and run on the SQL executor: `portfolio(player)`, `orders(player,limit)`, `trades(player,limit)`, `book(code,depth)`, and `market()`. `book` returns at most five aggregated bid and ask levels with no player/order IDs. `market` derives latest from the latest settled trade else issue reference; change compares latest with the last trade strictly before the configured local day start, falling back to issue reference; volume/turnover include trades in `[dayStart,nextDayStart)`. These are read-only projections over persisted facts and never initialize/reset state on query.

- [ ] **RED tests:** assert a player sees their available/reserved cash and holdings but another player’s order IDs/identity never occur in portfolio/book/market output; personal trades expose own side/fee but no counterparty. Assert bids sort high-to-low, asks low-to-high, exactly five levels maximum, and aggregation sums remaining shares. Assert no-trade market uses IPO reference, zero change/volume/turnover; today's first trade compares against yesterday's last close (or IPO reference), later trades update latest/volume/turnover, and crossing a timezone day boundary changes only the query window without writing database state.

- [ ] **Run RED:** `./gradlew.bat test --tests cn.blockeco.exchange.application.SecondaryMarketQueryServiceTest --tests cn.blockeco.exchange.application.PublicStockQueryServiceTest` — expected failure: secondary views and SQL projections absent.

- [ ] **Implementation:** add projection SQL with explicit selected columns (never `SELECT *` into user-facing records), bounded 1..50 personal histories and 1..5 grouped book queries. Use injected `Clock/ZoneId` to derive exact day bounds and prior close. Extend existing `PublicMarketRow`/`SqlPublicStockRepository`/`PublicStockQueryService` so `/stock market` shows current aggregate values while preserving no-trade IPO reference behavior. No repository method may call Bukkit, Vault, join/get, write on query, or obtain a new executor.

- [ ] **GREEN:** rerun the RED command; expected pass proves private ownership boundaries, aggregate-only book, day math and asynchronous dispatch.

- [ ] **Commit:** `git add src/main/java/cn/blockeco/exchange/application/SecondaryMarketQueryService.java src/main/java/cn/blockeco/exchange/application/PortfolioView.java src/main/java/cn/blockeco/exchange/application/OrderView.java src/main/java/cn/blockeco/exchange/application/TradeView.java src/main/java/cn/blockeco/exchange/application/OrderBookLevel.java src/main/java/cn/blockeco/exchange/application/SecondaryMarketRow.java src/main/java/cn/blockeco/exchange/application/PublicStockQueryService.java src/main/java/cn/blockeco/exchange/application/PublicMarketRow.java src/main/java/cn/blockeco/exchange/ports/SecondaryTradingRepository.java src/main/java/cn/blockeco/exchange/infrastructure/sql/SqlSecondaryTradingRepository.java src/main/java/cn/blockeco/exchange/infrastructure/sql/SqlPublicStockRepository.java src/test/java/cn/blockeco/exchange/application/PublicStockQueryServiceTest.java src/test/java/cn/blockeco/exchange/application/SecondaryMarketQueryServiceTest.java && git commit -m "feat: add private portfolio and public market views"`

### Task 6: `/stock` command, Chinese messages and memory-only completions

**Files:**
- Modify: `src/main/java/cn/blockeco/exchange/paper/StockCommand.java`
- Modify: `src/main/java/cn/blockeco/exchange/paper/StockTabCompleter.java`
- Modify: `src/main/java/cn/blockeco/exchange/paper/PublicStockSymbolCache.java`
- Modify: `src/main/java/cn/blockeco/exchange/paper/Messages.java`
- Modify: `src/main/resources/plugin.yml`
- Modify: `src/main/resources/config.yml`
- Modify: `src/test/java/cn/blockeco/exchange/paper/StockCommandTest.java`
- Modify: `src/test/java/cn/blockeco/exchange/paper/StockTabCompleterTest.java`
- Modify: `src/test/java/cn/blockeco/exchange/paper/MessagesTest.java`

**Interfaces / invariants:** retain existing public IPO commands and add `/stock cash`, `/stock deposit <金额>`, `/stock withdraw <金额>`, `/stock buy <代码> <股数> <限价>`, `sell ...`, `cancel <订单ID>`, `portfolio`, `orders [数量]`, `trades [数量]`, `book <代码>`, and aggregate `market`. Trading/cash permissions are explicit, help is Chinese and static. A completion snapshot contains only permission-filtered command literals and public code/name symbols; it never holds private account/order IDs and never invokes a service. Returning no candidate means a non-null empty list, avoiding Bukkit's default player-name fallback.

- [ ] **RED tests:** assert non-player and permission denial produce Chinese messages without service invocation; submit/queries return immediately and all later `sendMessage` calls pass through `MainThreadExecutor`; malformed money/nonpositive shares and list limits are rejected before service; `/stock book` is fixed at five public levels; help lists every new command. Assert completer filters by permission, has no SQL/Vault/service/private-order fields, returns symbols from a replaced immutable snapshot only, and returns non-null empty results rather than leaking default player names.

- [ ] **Run RED:** `./gradlew.bat test --tests cn.blockeco.exchange.paper.StockCommandTest --tests cn.blockeco.exchange.paper.StockTabCompleterTest --tests cn.blockeco.exchange.paper.MessagesTest` — expected failure: command grammar/messages absent.

- [ ] **Implementation:** parse `Money` using configured scale, `long` shares and UUID cancellation IDs without blocking. Wire each CompletionStage with `accepting`; Task 7 adds epoch hardening before release. Before main-thread message delivery check plugin acceptance and online sender. Add `market.fee-bps` (0..10000, snapshotted into each order) and `market.time-zone` (validated `ZoneId`) and declare narrow `blockeco.stock.cash`, `.trade`, `.portfolio`, `.orders`, `.trades` permissions in `plugin.yml`, with player defaults separated from admin recovery permission. Preserve `PublicStockSymbolCache` `AtomicReference<List<...>>`; refresh asynchronously and never call `get/join` in command/tab paths.

- [ ] **GREEN:** rerun the RED command; expected pass proves Chinese UX, permissions, no blocking completion and main-thread-only output.

- [ ] **Commit:** `git add src/main/java/cn/blockeco/exchange/paper/StockCommand.java src/main/java/cn/blockeco/exchange/paper/StockTabCompleter.java src/main/java/cn/blockeco/exchange/paper/PublicStockSymbolCache.java src/main/java/cn/blockeco/exchange/paper/Messages.java src/main/resources/plugin.yml src/main/resources/config.yml src/test/java/cn/blockeco/exchange/paper/StockCommandTest.java src/test/java/cn/blockeco/exchange/paper/StockTabCompleterTest.java src/test/java/cn/blockeco/exchange/paper/MessagesTest.java && git commit -m "feat: expose secondary market commands"`

### Task 7: Bootstrap, disable epoch, recovery observability and end-to-end verification

**Files:**
- Create: `src/main/java/cn/blockeco/exchange/application/SecondaryMarketRecoveryService.java`
- Create: `src/main/java/cn/blockeco/exchange/paper/SecondaryTradingGate.java`
- Modify: `src/main/java/cn/blockeco/exchange/application/SecuritiesCashService.java`
- Modify: `src/main/java/cn/blockeco/exchange/BlockecoPlugin.java`
- Modify: `src/main/java/cn/blockeco/exchange/PluginRuntime.java`
- Modify: `src/main/java/cn/blockeco/exchange/StartupRecoveryGate.java`
- Modify: `src/main/java/cn/blockeco/exchange/paper/StockCommand.java`
- Modify: `src/main/java/cn/blockeco/exchange/paper/StockAdminConfigCommand.java`
- Modify: `src/test/java/cn/blockeco/exchange/BlockecoPluginTest.java`
- Modify: `src/test/java/cn/blockeco/exchange/PluginRuntimeTest.java`
- Modify: `src/test/java/cn/blockeco/exchange/BootstrapCoordinatorTest.java`
- Modify: `src/test/java/cn/blockeco/exchange/paper/StockCommandTest.java`
- Create: `src/test/java/cn/blockeco/exchange/application/SecondaryMarketRecoveryServiceTest.java`
- Create: `docs/operations/secondary-market-smoke.md`
- Modify: `README.md`

**Interfaces / invariants:** `PluginRuntime` owns monotonically increasing `long epoch`; `captureEpoch()`/`isAccepting(epoch)` govern new work and user-visible callbacks, not mandatory financial persistence. Startup first invokes idempotent local-only completion for states that durably prove the final external leg (`ESCROW_DEPOSITED` deposit or `PLAYER_DEPOSITED` withdrawal), then `SecondaryMarketRecoveryService.inspect()` reads remaining current/legacy ambiguous operations and internal/proven-in-flight liabilities without Vault or mutation. Bootstrap separately obtains the real escrow balance through the main-thread gateway, then compares it with `final liabilities + proven inbound - proven outbound`; uncertain external amounts are reported separately. Global/public readiness requires migration/recovery/symbol cache; `SecondaryTradingGate` opens cash/trade mutations only when there are no unresolved cash/legacy escrow operations and physical/internal/ledger totals reconcile. A mismatch keeps public market and read-only admin diagnosis available while cash, buy, sell and cancel return a Chinese maintenance message.

- [ ] **RED tests:** simulate disable at every cash-saga boundary. Assert epoch invalidation prevents new commands, new Vault legs and late Bukkit messages, but an already observed external result is still persisted as the next durable state or AMBIGUOUS before DB close. Queued pre-external operations become deterministic FAILED/cancelled only when provider-not-called is provable, otherwise AMBIGUOUS with withdrawal reserve retained; no nonterminal operation disappears. Verify startup completes durable final external stages locally without Economy calls, includes other proven inbound/outbound adjustments in expected physical balance, and never treats a zero difference as resolving AMBIGUOUS. Simulate reconciliation mismatch or any legacy/current AMBIGUOUS row and assert public `/stock market` plus `/stockadmin recovery cash` work while deposit/withdraw/buy/sell/cancel stay closed. Assert recovery inspection itself calls no Economy method, bootstrap balance measurement does so only on main, and SQL/matching share one executor.

- [ ] **Run RED:** `./gradlew.bat test --tests cn.blockeco.exchange.BlockecoPluginTest --tests cn.blockeco.exchange.PluginRuntimeTest --tests cn.blockeco.exchange.BootstrapCoordinatorTest --tests cn.blockeco.exchange.application.SecondaryMarketRecoveryServiceTest --tests cn.blockeco.exchange.paper.StockCommandTest` — expected failure: epoch, trading gate and secondary bootstrap checks absent.

- [ ] **Implementation:** construct one shared SQL cash repository, trading repository, cash service, matcher, query service and recovery inspector in `finishEnable`; pass the same executor everywhere. Extend startup ordering explicitly: existing capitalization recovery → IPO recovery → secondary local-only completion → secondary read-only inspection → main-thread real escrow balance → SQL confirmed/in-flight liability and ledger comparison → symbol refresh → global ready and conditional trading gate. Do not silently rewrite totals or infer that equal totals resolve an operation. Add `/stockadmin recovery cash`/`reconcile` as read-only reports containing operation ID, direction, last durable stage, reserved amount/reason, each final liability subtotal, proven inbound/outbound adjustment, physical balance, confirmed difference and uncertain external amount; no retry/refund action. On disable first invalidate epoch/close new-work gates, then cancel Bukkit tasks; allow known Vault results to enqueue their durable SQL result, forbid subsequent external legs, and run a bounded SQL quiesce that marks every remaining unprovable operation AMBIGUOUS before draining executor/closing DB. Epoch suppresses only new work and messages, never the final persistence required after an external effect. Document automated test/build, Paper+Vault/EssentialsX setup, and distinguish real-player steps not run.

- [ ] **GREEN / full verification:**
  - `./gradlew.bat clean test shadowJar` — expected pass and exactly one `build/libs/blockstock-*-all.jar`.
  - `./gradlew.bat test --tests cn.blockeco.exchange.architecture.DependencyRuleTest` — expected pass: domain/application retain no Bukkit/Vault dependency.
  - `./gradlew.bat runServer` — expected Paper startup with configured EssentialsX/Vault; record `/stock market`, deposit, buy/sell/partial/cancel/book, and reconciliation evidence only when actually performed.

- [ ] **Commit:** `git add src/main/java/cn/blockeco/exchange/application/SecondaryMarketRecoveryService.java src/main/java/cn/blockeco/exchange/application/SecuritiesCashService.java src/main/java/cn/blockeco/exchange/paper/SecondaryTradingGate.java src/main/java/cn/blockeco/exchange/paper/StockCommand.java src/main/java/cn/blockeco/exchange/BlockecoPlugin.java src/main/java/cn/blockeco/exchange/PluginRuntime.java src/main/java/cn/blockeco/exchange/StartupRecoveryGate.java src/main/java/cn/blockeco/exchange/paper/StockAdminConfigCommand.java src/test/java/cn/blockeco/exchange/BlockecoPluginTest.java src/test/java/cn/blockeco/exchange/PluginRuntimeTest.java src/test/java/cn/blockeco/exchange/BootstrapCoordinatorTest.java src/test/java/cn/blockeco/exchange/paper/StockCommandTest.java src/test/java/cn/blockeco/exchange/application/SecondaryMarketRecoveryServiceTest.java docs/operations/secondary-market-smoke.md README.md && git commit -m "feat: harden secondary market lifecycle"`

## Self-review

- **Coverage:** Task 1 introduces V007 and account/ledger separation; Task 2 enforces exact reservation/settlement/reconciliation; Task 3 implements the sole Vault cash boundary and terminal AMBIGUOUS handling; Task 4 covers integer GTC, price-time matching, partial fills, cancel and self-trade protection; Task 5 covers market/portfolio/orders/trades/five levels; Task 6 covers Chinese command/permission/nonblocking UX; Task 7 covers recovery, epoch shutdown and full verification.
- **Deliberate exclusions:** no K line/chart, monthly report, dividend, governance, repurchase/delisting, compensation payouts, or third-party asset adapters; no direct Vault move during trade matching.
- **Consistency check:** all mutable trading calls use the same SQL executor and caller connection; all Vault operations are intent-first and main-thread-only; all UI output returns to main thread and checks epoch/accepting. The public book is aggregated and ownership-private.
- **Placeholder scan:** no TBD/TODO or unspecified “appropriate handling”; each task has files, interface/invariants, RED command/tests, implementation, GREEN evidence and commit.

Plan complete and saved to `docs/superpowers/plans/2026-08-22-blockstock-secondary-trading-core.md`.
