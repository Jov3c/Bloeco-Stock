# BlockStock 全 GUI 与资产绑定 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 普通玩家只需 `/stock` 即可通过原版 GUI 创建和管理公司、绑定原生或可选外部资产、发布 IPO、认购并交易。

**Architecture:** 扩展 application 的资产适配器为可枚举的可选目录能力；原生资产由 BlockStock 自身持久化并验证创建者归属。Paper GUI 以分离的公司中心控制器调用既有服务，所有财务操作复用事务、Vault saga 和会话门禁。

**Tech Stack:** Java 21、Paper 1.21.4 API、SQLite、Adventure、JUnit 5、Mockito；可选 Lands、Residence、QuickShop、Shopkeepers softdepend。

**Spec:** `docs/superpowers/specs/2026-08-23-blockstock-all-gui-asset-binding-design.md`

## Global Constraints

- 客户端零安装；普通玩家流程不要求文字命令。
- 外部资产插件为可选增强：缺失、授权不可用或 API 故障不得阻止 BlockStock 启动、原生绑定、IPO 或交易。
- 禁止下载、嵌入或绕过商业插件许可证；仅在服务器已有合法 JAR 时启用对应适配器。
- 所有外部归属均在确认前复核；不得展示其他玩家资产。
- GUI 确认前无扣款、绑定、IPO 或订单副作用。

---

### Task 1: 原生资产与可枚举适配器应用模型

**Files:**
- Modify: `src/main/java/cn/blockeco/exchange/ports/CompanyAssetAdapter.java`
- Create: `src/main/java/cn/blockeco/exchange/ports/AssetCatalogAdapter.java`
- Create: `src/main/java/cn/blockeco/exchange/application/NativeAssetService.java`
- Create: `src/main/java/cn/blockeco/exchange/domain/finance/NativeAsset.java`
- Modify: `src/main/java/cn/blockeco/exchange/ports/AssetBindingRepository.java`
- Modify: `src/main/java/cn/blockeco/exchange/infrastructure/sql/SqlAssetBindingRepository.java`
- Test: `src/test/java/cn/blockeco/exchange/application/NativeAssetServiceTest.java`

**Interfaces:** `AssetCatalogAdapter.listOwned(UUID requester, String search, int limit)` returns safe `AssetChoice(externalKey, displayName, type)`; `NativeAssetService.create(company, founder, name)` creates an owner-bound `blockstock-native` key then uses the normal binding path.

- [ ] **Step 1: Write the failing ownership test**

```java
@Test void native_asset_belongs_only_to_the_company_founder() {
    var asset = service.create(companyId, founder, "红石工厂").toCompletableFuture().join();
    assertThat(adapter.verify(founder, asset.externalKey()).ownedByRequester()).isTrue();
    assertThat(adapter.verify(otherPlayer, asset.externalKey()).ownedByRequester()).isFalse();
}
```

- [ ] **Step 2: Verify RED**

Run: `./gradlew.bat test --tests cn.blockeco.exchange.application.NativeAssetServiceTest`

Expected: FAIL because native assets do not exist.

- [ ] **Step 3: Implement native asset persistence and verification**

Add a migration/table with immutable asset UUID, company UUID, founder UUID, name and creation time. Use a native adapter registered unconditionally; limit names and reject duplicates within one company.

- [ ] **Step 4: Verify GREEN and commit**

Run: `./gradlew.bat test --tests cn.blockeco.exchange.application.NativeAssetServiceTest`

```bash
git add src/main/java/cn/blockeco/exchange src/test/java/cn/blockeco/exchange/application/NativeAssetServiceTest.java
git commit -m "feat: add native company assets"
```

### Task 2: Optional provider adapters and lifecycle registration

**Files:**
- Create: `src/main/java/cn/blockeco/exchange/paper/OptionalAssetAdapterLoader.java`
- Create: `src/main/java/cn/blockeco/exchange/paper/adapter/LandsAssetAdapter.java`
- Create: `src/main/java/cn/blockeco/exchange/paper/adapter/ResidenceAssetAdapter.java`
- Create: `src/main/java/cn/blockeco/exchange/paper/adapter/QuickShopAssetAdapter.java`
- Create: `src/main/java/cn/blockeco/exchange/paper/adapter/ShopkeepersAssetAdapter.java`
- Modify: `src/main/java/cn/blockeco/exchange/BlockecoPlugin.java`
- Modify: `src/main/resources/plugin.yml`
- Test: `src/test/java/cn/blockeco/exchange/paper/OptionalAssetAdapterLoaderTest.java`

**Interfaces:** loader registers the native adapter always; registers third-party adapter only after plugin name/version/API lookup succeeds. Each adapter returns only owned entries and uses no direct plugin data-file reads.

- [ ] **Step 1: Write the failing missing-provider test**

```java
@Test void missing_optional_plugins_leave_native_binding_available() {
    loader.registerAvailable(registry, pluginManagerWithoutProviders());
    assertThat(registry.snapshot()).extracting(CompanyAssetAdapter::id).contains("blockstock-native")
        .doesNotContain("lands", "residence", "quickshop", "shopkeepers");
}
```

- [ ] **Step 2: Verify RED**

Run: `./gradlew.bat test --tests cn.blockeco.exchange.paper.OptionalAssetAdapterLoaderTest`

Expected: FAIL because loader is absent.

- [ ] **Step 3: Implement isolated adapters and verify GREEN**

Use `softdepend`; no class linkage to optional APIs at BlockStock class load. Where a provider lacks stable sales-revenue events, register verification/catalog support only and display “自动收益未接入”.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/cn/blockeco/exchange/paper src/main/java/cn/blockeco/exchange/BlockecoPlugin.java src/main/resources/plugin.yml src/test/java/cn/blockeco/exchange/paper/OptionalAssetAdapterLoaderTest.java
git commit -m "feat: add optional asset adapters"
```

### Task 3: 公司中心与绑定 GUI

**Files:**
- Create: `src/main/java/cn/blockeco/exchange/paper/CompanyGuiController.java`
- Create: `src/main/java/cn/blockeco/exchange/paper/CompanyGuiSession.java`
- Modify: `src/main/java/cn/blockeco/exchange/paper/StockGuiController.java`
- Modify: `src/main/java/cn/blockeco/exchange/paper/StockGuiItemFactory.java`
- Test: `src/test/java/cn/blockeco/exchange/paper/CompanyGuiSessionTest.java`
- Test: `src/test/java/cn/blockeco/exchange/paper/CompanyGuiControllerTest.java`

**Interfaces:** Stock home action `company` opens the company center. The binding chooser consumes `AssetCatalogAdapter` entries or native-create input and calls `AssetBindingService.bind` only from a confirmation session.

- [ ] **Step 1: Write the failing confirmation test**

```java
@Test void binding_draft_has_no_side_effect_until_the_green_confirmation() {
    controller.selectAsset(player, nativeChoice);
    verifyNoInteractions(bindingService);
    controller.confirmCurrentDraft(player);
    verify(bindingService).bind(companyId, player.getUniqueId(), "blockstock-native", nativeChoice.externalKey());
}
```

- [ ] **Step 2: Verify RED**

Run: `./gradlew.bat test --tests cn.blockeco.exchange.paper.CompanyGuiControllerTest`

Expected: FAIL because company GUI is absent.

- [ ] **Step 3: Implement and verify GREEN**

Render company center, owned-company page, bound assets, available providers, native asset creation, provider catalog/search, and confirmation pages. Apply the existing inventory replacement/session safety pattern to every page and cancel all inventory movement events.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/cn/blockeco/exchange/paper src/test/java/cn/blockeco/exchange/paper/CompanyGuiSessionTest.java src/test/java/cn/blockeco/exchange/paper/CompanyGuiControllerTest.java
git commit -m "feat: add company asset binding gui"
```

### Task 4: GUI company creation, IPO and public subscription

**Files:**
- Modify: `src/main/java/cn/blockeco/exchange/paper/CompanyGuiController.java`
- Modify: `src/main/java/cn/blockeco/exchange/paper/StockGuiController.java`
- Modify: `src/main/java/cn/blockeco/exchange/paper/Messages.java`
- Modify: `src/main/resources/config.yml`
- Test: `src/test/java/cn/blockeco/exchange/paper/CompanyGuiControllerTest.java`

**Interfaces:** creation uses `CompanyRegistrationService.register`; IPO uses `PrimaryOfferingService.announce`; subscription uses existing durable `subscribe`. All input occurs through original anvil GUI and confirmation pages.

- [ ] **Step 1: Write failing IPO draft test**

```java
@Test void ipo_announcement_is_not_sent_until_confirmation() {
    controller.completeIpoDraft(player, Money.ofMinor(500_000), Money.ofMinor(100));
    verifyNoInteractions(offerings);
    controller.confirmCurrentDraft(player);
    verify(offerings).announce(companyId, player.getUniqueId(), Money.ofMinor(500_000), Money.ofMinor(100));
}
```

- [ ] **Step 2: Verify RED, implement and verify GREEN**

Run: `./gradlew.bat test --tests cn.blockeco.exchange.paper.CompanyGuiControllerTest`

Expected before implementation: FAIL.

Render creation wizard, dividend choices, asset-required IPO wizard, IPO browse/detail/subscribe pages, and Chinese validation/recovery responses. Retain 12-hour announcement and two-day subscription windows in the service layer.

Run: `./gradlew.bat test --tests cn.blockeco.exchange.paper.CompanyGuiControllerTest`

Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/cn/blockeco/exchange/paper/CompanyGuiController.java src/main/java/cn/blockeco/exchange/paper/StockGuiController.java src/main/java/cn/blockeco/exchange/paper/Messages.java src/main/resources/config.yml src/test/java/cn/blockeco/exchange/paper/CompanyGuiControllerTest.java
git commit -m "feat: add company and ipo gui workflows"
```

### Task 5: Documentation, full verification and server deployment

**Files:**
- Modify: `README.md`
- Modify: `src/main/resources/plugin.yml`
- Test: relevant existing and new tests

- [ ] **Step 1: Document the single GUI entry and optional integrations**

State that `/stock` is the player workflow; list native binding and the optional provider behavior/licensing requirement.

- [ ] **Step 2: Run full verification**

Run: `./gradlew.bat clean test shadowJar --no-build-cache --no-daemon`

Expected: exit 0, no test failure, and the all-in-one JAR under `build/libs`.

- [ ] **Step 3: Start Paper and verify startup**

Run: `./gradlew.bat runServer`

Expected: BlockStock logs ready whether optional providers are present or absent; no linkage error.

- [ ] **Step 4: Commit**

```bash
git add README.md src/main/resources/plugin.yml
git commit -m "docs: explain gui company workflows"
```

