# BlockStock Listing Codes and Public Market Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** 增加不可变顺序股票代码与 IPO 发行价参考，并以全公开、无阻塞的中文 /stock 命令展示它们，不重做已完成的安全认购 saga。

**Architecture:** HEAD f7e5dc2 已完成 typed subscription saga、容量并发安全、IPO 公开发现、完整公告、关闭上市状态、启动恢复和管理员恢复查询。仅新增 V006 listing 事实、关闭时原子分配、异步 read model、缓存补全和 Paper command。旧 LISTED 数据先经 Java migration preflight 验证，再由 V006 SQL 确定性回填。

**Tech Stack:** Java 21, Paper 1.21.4, Vault, SQLite/HikariCP, JUnit 5, AssertJ, Mockito, 原版客户端。

**Spec:** docs/superpowers/specs/2026-08-14-blockstock-exchange-and-company-finance-design.md.

## Global Constraints

- 不改/重写现有 saga、容量、startup recovery 或 admin recovery query；subscribe 只委托既有 PrimaryOfferingService。
- 仅做 V006、listing allocation/backfill、公开 market/ipo/info/announcements 和 /stock。无二级订单簿、成交价/K线、portfolio、分红/月报、GUI、第三方适配。
- Paper 1.21.4/Java21/Vault/原版客户端；无新运行时依赖/NMS/CraftBukkit。金额为 Money/long minor units，无 double。
- V001–V005 冻结，仅增加 V006。
- CLOSED 且至少一笔 COMPLETED 的 IPO 在同一 SQL transaction 内分配 listing、LISTED 和审计；0 股保持不上市。
- 代码只允许 BS000001 到 BS999999。条件更新 sequence 的 last_value<999999；耗尽应抛 typed failure，中文诊断，且回滚 listing/status。
- V006 回填 LISTED 公司按 created_at,id；来源为最新 CLOSED+COMPLETED IPO，按 closes_at,announced_at,id 倒序。缺源或超过 999999 必须在 V006 前的 Java 只读 preflight 报错，V006 不进 schema_history。
- market 必有公司名/代码/参考价/市值/已发行股数/状态，且每个价显示 参考价（暂无成交）。
- market|ipo|info|announcements 对所有人公开。subscribe 需要玩家+blockeco.stock.subscribe；首发用公司名、后续可用代码解析当前 OPEN offering。
- Tab 补全只读 AtomicReference 不可变快照，绝不 SQL、join/get 或阻塞 Paper 主线程。

---

## File Structure

- V006.sql, Database.java, MigrationPrecondition.java：schema/Java preflight/确定性回填。
- StockListing.java, StockListingRepository.java, SqlStockListingRepository.java：上市事实和限号分配。
- PublicStockRepository.java, SqlPublicStockRepository.java, PublicStockQueryService.java, Public* records：异步公开读取；不污染 PrimaryOfferingRepository。
- PublicStockSymbolCache.java, StockCommand.java, StockTabCompleter.java：缓存补全和 Paper 边界。
- CommandAcceptanceGate.java, PluginRuntime.java, CompanyCommand.java, BlockecoPlugin.java：多命令 readiness gate、首刷/关闭刷新/周期兜底。
- tests, README.md, docs/operations/ipo-listing-and-stock-smoke.md：TDD 与诚实 smoke。

### Task 1: V006, listing domain and Java migration preflight

**Files:**
- Create: src/main/resources/db/migration/V006.sql
- Create: src/main/java/cn/blockeco/exchange/domain/finance/StockListing.java
- Create: src/main/java/cn/blockeco/exchange/infrastructure/sql/MigrationPrecondition.java
- Create: src/main/java/cn/blockeco/exchange/ports/StockListingRepository.java
- Modify: src/main/java/cn/blockeco/exchange/infrastructure/sql/Database.java
- Modify: src/test/java/cn/blockeco/exchange/domain/finance/FinanceValuesTest.java
- Modify: src/test/java/cn/blockeco/exchange/infrastructure/sql/MigrationTest.java

**Interfaces:**
- record StockListing(CompanyId companyId, String stockCode, Money issueReferencePrice, long issuedShares, Instant listedAt); validates exactly BS + six ASCII digits, numeric range 1..999999, positive price/shares.
- interface MigrationPrecondition { void verify(Connection connection, String version) throws SQLException; }.
- Database(String jdbcUrl, MigrationPrecondition precondition); existing constructor supplies Database::verifyMigrationPrecondition.
- Optional<StockListing> findByCompany(CompanyId); Optional<StockListing> findByCode(String); StockListing allocate(Connection,CompanyId,Money,long,Instant) throws SQLException.

- [ ] **Step 1: Write failing tests**

~~~java
@Test void listing_rejects_zero_or_seven_digit_code() {
    assertThat(new StockListing(COMPANY, "BS999999", Money.ofMinor(250), 1002, NOW).stockCode()).isEqualTo("BS999999");
    assertThatThrownBy(() -> new StockListing(COMPANY, "BS1000000", Money.ofMinor(1), 1, NOW))
        .isInstanceOf(IllegalArgumentException.class);
}
@Test void v006_preflight_fails_before_schema_history_or_tables_change() throws Exception {
    Database database = v005DatabaseWithListedCompanyAndNoSuccessfulClosedOffering();
    assertThatThrownBy(database::migrate).hasMessageContaining("listed company lacks closed successful IPO");
    assertThat(schemaVersions(database)).doesNotContain("V006");
    assertThat(tableExists(database, "stock_listings")).isFalse();
}
~~~

- [ ] **Step 2: Run RED**

Run: ./gradlew.bat test --tests cn.blockeco.exchange.domain.finance.FinanceValuesTest --tests cn.blockeco.exchange.infrastructure.sql.MigrationTest

Expected: FAIL because V006/domain/precondition seam do not exist.

- [ ] **Step 3: Implement**

V006 creates stock_code_sequence(singleton PK CHECK singleton=1,last_value CHECK BETWEEN 0 AND 999999), initially (1,0), and stock_listings(company_id PK FK, stock_code UNIQUE six-digit check, issue_reference_price_minor>0, issued_shares>0, listed_at), plus code/market indexes. In Database.migrate, after checksum/isApplied but before V006 transaction/script/history insert, call MigrationPrecondition.verify. Default verifier uses read-only prepared statements to reject a LISTED company with no described source using exact text listed company lacks closed successful IPO, and rejects count>999999 using stock code sequence exhausted during V006 backfill. Thus no non-portable SQLite RAISE dependency. V006 backfills deterministic row_number codes/reference/shares/close time and emits IPO_LISTING_BACKFILLED audit payload: companyId, offeringId, stockCode, issueReferencePriceMinor, issuedShares, source BACKFILL; sequence ends at inserted count.

- [ ] **Step 4: Run GREEN**

Run: ./gradlew.bat test --tests cn.blockeco.exchange.domain.finance.FinanceValuesTest --tests cn.blockeco.exchange.infrastructure.sql.MigrationTest

Expected: PASS frozen checksums, V006 once, preflight rollback/no version advance, bounded deterministic backfill and audit.

- [ ] **Step 5: Commit**

~~~powershell
git add src/main/resources/db/migration/V006.sql src/main/java/cn/blockeco/exchange/domain/finance/StockListing.java src/main/java/cn/blockeco/exchange/infrastructure/sql/MigrationPrecondition.java src/main/java/cn/blockeco/exchange/ports/StockListingRepository.java src/main/java/cn/blockeco/exchange/infrastructure/sql/Database.java src/test/java/cn/blockeco/exchange/domain/finance/FinanceValuesTest.java src/test/java/cn/blockeco/exchange/infrastructure/sql/MigrationTest.java
git commit -m "feat: add bounded stock listing migration"
~~~

### Task 2: Atomic IPO-close allocation, audit and sequence concurrency

**Files:**
- Create: src/main/java/cn/blockeco/exchange/infrastructure/sql/SqlStockListingRepository.java
- Create: src/main/java/cn/blockeco/exchange/infrastructure/sql/StockCodeExhaustedException.java
- Modify: src/main/java/cn/blockeco/exchange/infrastructure/sql/SqlPrimaryOfferingRepository.java
- Modify: src/main/java/cn/blockeco/exchange/ports/PrimaryOfferingRepository.java
- Modify: src/test/java/cn/blockeco/exchange/infrastructure/sql/SqlPrimaryOfferingRepositoryTest.java

**Interfaces:**
- allocate executes UPDATE stock_code_sequence SET last_value=last_value+1 WHERE singleton=1 AND last_value<999999; a zero update throws StockCodeExhaustedException with 股票代码已分配完毕，IPO关闭未完成上市。 It reads/formats BS%06d and inserts listing in the same Connection.
- PrimaryOfferingRepository closeExpired/closeIfExhausted signatures stay unchanged; name/code lookup does not belong here.

- [ ] **Step 1: Write failing tests**

~~~java
@Test void close_writes_listing_status_and_reference_audit_in_one_transaction() {
    completeSubscription(openOffering, BUYER, 2);
    database.inTransaction(c -> { repository.closeExpired(c, openOffering.closesAt()); return null; });
    assertThat(listings.findByCompany(COMPANY)).contains(new StockListing(
        COMPANY, "BS000001", openOffering.issuePrice(), 1002, openOffering.closesAt()));
    assertThat(company(COMPANY).status()).isEqualTo(CompanyStatus.LISTED);
    assertThat(auditPayload("IPO_LISTED")).contains("stockCode", "BS000001",
        "issueReferencePriceMinor", "issuedShares", "source", "IPO_CLOSE");
}
@Test void concurrent_successful_closes_allocate_unique_consecutive_codes() throws Exception {
    completeAndCloseTwoDifferentOfferingsConcurrently();
    assertThat(listings.allCodes()).containsExactlyInAnyOrder("BS000001", "BS000002");
}
@Test void exhausted_sequence_rolls_back_listing_and_status() {
    setSequence(999999);
    assertThatThrownBy(this::closeSuccessfulOffering).isInstanceOf(StockCodeExhaustedException.class);
    assertThat(listings.findByCompany(COMPANY)).isEmpty();
    assertThat(company(COMPANY).status()).isNotEqualTo(CompanyStatus.LISTED);
}
~~~

- [ ] **Step 2: Run RED**

Run: ./gradlew.bat test --tests cn.blockeco.exchange.infrastructure.sql.SqlPrimaryOfferingRepositoryTest

Expected: FAIL because existing close only updates status.

- [ ] **Step 3: Implement**

Refactor only successful existing close paths. In their current transaction count COMPLETED subscriptions; zero retains CLOSED/no listing. Positive path uses total_shares after issuance, allocate, status LISTED and IPO_LISTED audit together. Payload includes offeringId/code/reference minor/issued shares/actor empty/source IPO_CLOSE. company PK makes repeats no-op. Exhaustion rolls back close/listing/status and reaches current scheduler failure logger with Chinese message; never claims a false listing. Backfill audit source BACKFILL makes reference/current and upgrade audit independently queryable.

- [ ] **Step 4: Run GREEN**

Run: ./gradlew.bat test --tests cn.blockeco.exchange.infrastructure.sql.SqlPrimaryOfferingRepositoryTest

Expected: PASS expiry/sell-out, unique concurrent codes, repeat safety, reference audit, zero sale, 999999 rollback and backfill/current audit sources.

- [ ] **Step 5: Commit**

~~~powershell
git add src/main/java/cn/blockeco/exchange/infrastructure/sql/SqlStockListingRepository.java src/main/java/cn/blockeco/exchange/infrastructure/sql/StockCodeExhaustedException.java src/main/java/cn/blockeco/exchange/infrastructure/sql/SqlPrimaryOfferingRepository.java src/main/java/cn/blockeco/exchange/ports/PrimaryOfferingRepository.java src/test/java/cn/blockeco/exchange/infrastructure/sql/SqlPrimaryOfferingRepositoryTest.java
git commit -m "feat: allocate bounded stock codes on IPO close"
~~~

### Task 3: Fully asynchronous public read service and non-blocking symbol cache

**Files:**
- Create: src/main/java/cn/blockeco/exchange/ports/PublicStockRepository.java
- Create: src/main/java/cn/blockeco/exchange/infrastructure/sql/SqlPublicStockRepository.java
- Create: src/main/java/cn/blockeco/exchange/application/PublicStockQueryService.java
- Create: src/main/java/cn/blockeco/exchange/application/PublicMarketRow.java
- Create: src/main/java/cn/blockeco/exchange/application/PublicStockInfo.java
- Create: src/main/java/cn/blockeco/exchange/application/PublicAnnouncement.java
- Create: src/main/java/cn/blockeco/exchange/application/PublicStockSymbol.java
- Create: src/main/java/cn/blockeco/exchange/paper/PublicStockSymbolCache.java
- Modify: src/main/java/cn/blockeco/exchange/application/IpoLifecycleScheduler.java
- Test: src/test/java/cn/blockeco/exchange/application/PublicStockQueryServiceTest.java
- Create: src/test/java/cn/blockeco/exchange/paper/PublicStockSymbolCacheTest.java
- Modify: src/test/java/cn/blockeco/exchange/application/IpoLifecycleSchedulerTest.java

**Interfaces:**
- exact PublicStockQueryService reads:
  - CompletionStage<List<PublicMarketRow>> market()
  - CompletionStage<List<PublicOfferingView>> ipo(int limit)
  - CompletionStage<Optional<PublicStockInfo>> info(String companyNameOrCode)
  - CompletionStage<List<PublicAnnouncement>> announcements(String companyNameOrCode, int limit)
  - CompletionStage<Optional<UUID>> resolveOpenOffering(String companyNameOrCode)
  - CompletionStage<List<PublicStockSymbol>> symbols()
- PublicStockRepository defines synchronous SQL counterparts, including findOpenOfferingByCompanyOrCode; it is not PrimaryOfferingRepository.
- record PublicStockSymbol(String companyName, Optional<String> stockCode).
- PublicStockSymbolCache snapshot(): List<PublicStockSymbol>; refresh(PublicStockQueryService): CompletionStage<Void>; AtomicReference starts immutable List.of().

- [ ] **Step 1: Write failing tests**

~~~java
@Test void every_read_is_completion_stage_dispatched_to_sql_executor() {
    assertThat(service.market()).isCompletedWithValueContaining(MARKET_ROW);
    verify(sqlExecutor).execute(any(Runnable.class));
}
@Test void cache_refresh_is_async_and_snapshot_never_queries_or_blocks() {
    cache.refresh(service); completeSymbols(List.of(new PublicStockSymbol("红石工业", Optional.of("BS000001"))));
    assertThat(cache.snapshot()).containsExactly(new PublicStockSymbol("红石工业", Optional.of("BS000001")));
    verifyNoInteractions(sqlRepository);
}
@Test void lifecycle_success_requests_refresh_but_failure_does_not() {
    scheduler.start(); timerTask.run(); completeCloseSuccessfully();
    verify(cache).refresh(service);
}
~~~

- [ ] **Step 2: Run RED**

Run: ./gradlew.bat test --tests cn.blockeco.exchange.application.PublicStockQueryServiceTest --tests cn.blockeco.exchange.paper.PublicStockSymbolCacheTest --tests cn.blockeco.exchange.application.IpoLifecycleSchedulerTest

Expected: FAIL service/cache/callback absent.

- [ ] **Step 3: Implement**

SqlPublicStockRepository uses prepared listing/company/announcement joins; market code-sorts and Math.multiplyExact(reference minor, shares), info resolves normalized name or uppercased code, announcement limit 1..50 newest-first. IPO delegates existing PublicOfferingView model. First IPO has no code and resolves by company; later offer accepts code via listing join. Public records expose no subscriber, holder, escrow or saga field.

Every service method uses CompletableFuture.supplyAsync(repository call,sqlExecutor), never direct List/Optional. Cache.refresh attaches callback to symbols and AtomicReference.set(List.copyOf(result)); no join/get. StockTabCompleter only snapshot. Extend IpoLifecycleScheduler with close-success Consumer<Void>; success triggers cache refresh, failure keeps current error path. BlockecoPlugin adds periodic async refresh fallback.

- [ ] **Step 4: Run GREEN**

Run: ./gradlew.bat test --tests cn.blockeco.exchange.application.PublicStockQueryServiceTest --tests cn.blockeco.exchange.paper.PublicStockSymbolCacheTest --tests cn.blockeco.exchange.application.IpoLifecycleSchedulerTest

Expected: PASS asynchronous signatures/SQL dispatch, public data, first-name/later-code resolve, cache no SQL/blocking, lifecycle and periodic refresh.

- [ ] **Step 5: Commit**

~~~powershell
git add src/main/java/cn/blockeco/exchange/ports/PublicStockRepository.java src/main/java/cn/blockeco/exchange/infrastructure/sql/SqlPublicStockRepository.java src/main/java/cn/blockeco/exchange/application/PublicStockQueryService.java src/main/java/cn/blockeco/exchange/application/PublicMarketRow.java src/main/java/cn/blockeco/exchange/application/PublicStockInfo.java src/main/java/cn/blockeco/exchange/application/PublicAnnouncement.java src/main/java/cn/blockeco/exchange/application/PublicStockSymbol.java src/main/java/cn/blockeco/exchange/paper/PublicStockSymbolCache.java src/main/java/cn/blockeco/exchange/application/IpoLifecycleScheduler.java src/test/java/cn/blockeco/exchange/application/PublicStockQueryServiceTest.java src/test/java/cn/blockeco/exchange/paper/PublicStockSymbolCacheTest.java src/test/java/cn/blockeco/exchange/application/IpoLifecycleSchedulerTest.java
git commit -m "feat: add async public stock queries"
~~~

### Task 4: Gated /stock Paper UX, wiring and honest smoke

**Files:**
- Create: src/main/java/cn/blockeco/exchange/paper/CommandAcceptanceGate.java
- Create: src/main/java/cn/blockeco/exchange/paper/StockCommand.java
- Create: src/main/java/cn/blockeco/exchange/paper/StockTabCompleter.java
- Modify: src/main/java/cn/blockeco/exchange/paper/CompanyCommand.java
- Modify: src/main/java/cn/blockeco/exchange/PluginRuntime.java
- Modify: src/main/java/cn/blockeco/exchange/BlockecoPlugin.java
- Modify: src/main/java/cn/blockeco/exchange/paper/Messages.java
- Modify: src/main/resources/plugin.yml
- Modify: src/main/resources/config.yml
- Test: src/test/java/cn/blockeco/exchange/paper/StockCommandTest.java
- Create: src/test/java/cn/blockeco/exchange/paper/StockTabCompleterTest.java
- Modify: src/test/java/cn/blockeco/exchange/PluginRuntimeTest.java
- Modify: src/test/java/cn/blockeco/exchange/BlockecoPluginTest.java
- Modify: README.md
- Create: docs/operations/ipo-listing-and-stock-smoke.md

**Interfaces:**
- interface CommandAcceptanceGate { void setAccepting(boolean accepting); }; CompanyCommand/StockCommand implement it.
- PluginRuntime boolean attachReady(List<? extends CommandAcceptanceGate> gates, AutoCloseable database); stop flips every gate false.
- StockCommand(PublicStockQueryService queries, PrimaryOfferingService offerings, MainThreadExecutor mainThread, BooleanSupplier accepting, Messages messages).
- StockTabCompleter(PublicStockSymbolCache cache), no service/repository/executor member.
- /stock [help]|market|ipo [数量]|info <公司名|代码>|announcements <公司名|代码> [数量]|subscribe <公司名|代码> <股数>.

- [ ] **Step 1: Write failing tests**

~~~java
@Test void not_ready_non_help_command_only_initializes_without_query_or_vault() {
    when(accepting.getAsBoolean()).thenReturn(false);
    command.onCommand(player, STOCK, "stock", new String[]{"market"});
    assertThat(sent(player)).contains("BlockStock 正在初始化，请稍后再试。");
    verifyNoInteractions(queries, offerings);
}
@Test void tab_completion_reads_cache_only_on_main_thread() {
    cache.replaceForTest(List.of(new PublicStockSymbol("红石工业", Optional.of("BS000001"))));
    assertThat(completer.onTabComplete(player, STOCK, "stock", new String[]{"info",""})).contains("红石工业", "BS000001");
    verifyNoInteractions(queries, sqlExecutor);
}
@Test void runtime_readies_and_stops_both_command_gates() {
    assertThat(runtime.attachReady(List.of(companyGate,stockGate),database)).isTrue();
    verify(companyGate).setAccepting(true); verify(stockGate).setAccepting(true);
    runtime.stop(); verify(companyGate).setAccepting(false); verify(stockGate).setAccepting(false);
}
~~~

- [ ] **Step 2: Run RED**

Run: ./gradlew.bat test --tests cn.blockeco.exchange.paper.StockCommandTest --tests cn.blockeco.exchange.paper.StockTabCompleterTest --tests cn.blockeco.exchange.PluginRuntimeTest --tests cn.blockeco.exchange.BlockecoPluginTest

Expected: FAIL generic gates/stock edge absent.

- [ ] **Step 3: Implement**

Declare stock and Chinese messages/permission blockeco.stock.subscribe default true, retaining old permissions. Install it accepting=false before migration. Root/help is static before ready; every non-help/root subcommand checks BooleanSupplier first and replies initializing with zero query/Vault/SQL access. After migration/recovery success build repo/service/cache, await only async cache.refresh completion on SQL executor, then attachReady(List.of(companyCommand,stockCommand),database). Thus first snapshot exists before ready. Lifecycle success/periodic fallback refresh cache asynchronously; disable cancels refresh/lifecycle then gates.

Read replies return through main thread. subscribe validates positive Long, resolves existing OPEN offering and delegates existing service only. Add Chinese 参考价（暂无成交）, empty market, invalid shares and capacity exhaustion copy. Tab roots and name/code suggestions only use cache.

Docs: current server may have zero listings; automated build plus console /stock market empty public result is executable. Real closed IPO requires authenticated players/Vault provider; unless evidence is captured mark it 未执行, not passed.

- [ ] **Step 4: Run GREEN and smoke**

Run: ./gradlew.bat test --tests cn.blockeco.exchange.paper.StockCommandTest --tests cn.blockeco.exchange.paper.StockTabCompleterTest --tests cn.blockeco.exchange.PluginRuntimeTest --tests cn.blockeco.exchange.BlockecoPluginTest --tests cn.blockeco.exchange.paper.MessagesTest

Expected: PASS gates, no-ready access, public Chinese reads, subscription delegation, cache-only tab, initial/close/periodic refresh.

Run: ./gradlew.bat clean test shadowJar

Expected: PASS and build/libs/blockstock-*-all.jar exists.

Run: ./gradlew.bat runServer

Expected: startup. Record console empty-market result when no listing; record authenticated closed-IPO smoke 未执行 absent real evidence.

- [ ] **Step 5: Commit**

~~~powershell
git add src/main/java/cn/blockeco/exchange/paper/CommandAcceptanceGate.java src/main/java/cn/blockeco/exchange/paper/StockCommand.java src/main/java/cn/blockeco/exchange/paper/StockTabCompleter.java src/main/java/cn/blockeco/exchange/paper/CompanyCommand.java src/main/java/cn/blockeco/exchange/PluginRuntime.java src/main/java/cn/blockeco/exchange/BlockecoPlugin.java src/main/java/cn/blockeco/exchange/paper/Messages.java src/main/resources/plugin.yml src/main/resources/config.yml src/test/java/cn/blockeco/exchange/paper/StockCommandTest.java src/test/java/cn/blockeco/exchange/paper/StockTabCompleterTest.java src/test/java/cn/blockeco/exchange/PluginRuntimeTest.java src/test/java/cn/blockeco/exchange/BlockecoPluginTest.java README.md docs/operations/ipo-listing-and-stock-smoke.md
git commit -m "feat: expose public stock command safely"
~~~

## Self-review

- **Coverage:** V006 preflight is Java, not unreliable SQLite RAISE. Close allocation covers code/reference/backfill audits, upper bound and concurrent uniqueness. All public query reads are CompletionStage and all tabs cache-only. Command gates prevent unready dependency access and smoke distinguishes executable empty market from unexecuted real IPO.
- **Type/file consistency:** PublicStockRepository owns name/code open lookup; PrimaryOfferingRepository remains close-only. PublicStockQueryService has only CompletionStage read signatures. Cache only consumes symbols asynchronously. Both Paper commands implement CommandAcceptanceGate.
- **Placeholder scan:** No TODO/TBD/similar-to wording. Each task supplies files, interfaces, actual Java test skeleton, RED/GREEN and commit.

Plan complete and saved to docs/superpowers/plans/2026-08-22-blockstock-listing-codes-and-public-market.md.
