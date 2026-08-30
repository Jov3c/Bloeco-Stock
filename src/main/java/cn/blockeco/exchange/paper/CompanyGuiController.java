package cn.blockeco.exchange.paper;

import cn.blockeco.exchange.application.AssetBindingService;
import cn.blockeco.exchange.application.CompanyQueryService;
import cn.blockeco.exchange.application.CompanyRegistrationService;
import cn.blockeco.exchange.application.NativeAssetService;
import cn.blockeco.exchange.application.RegistrationRequest;
import cn.blockeco.exchange.application.RegistrationResult;
import cn.blockeco.exchange.domain.company.Company;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.ports.MainThreadExecutor;
import cn.blockeco.exchange.ports.AssetCatalogAdapter;
import java.math.BigDecimal;
import java.util.List;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Objects;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Player-facing company workflow; mutations are owner-bound and confirmed twice. */
public final class CompanyGuiController implements CompanyGuiOpener, Listener {
    private final StockGuiItemFactory items;
    private final CompanyQueryService companies;
    private final CompanyRegistrationService registration;
    private final NativeAssetService nativeAssets;
    private final AssetBindingService bindings;
    private final Supplier<? extends Collection<AssetCatalogAdapter>> catalogs;
    private final Supplier<CompanyCreationRules> rules;
    private final Executor worker;
    private final MainThreadExecutor main;
    private final BooleanSupplier accepting;
    private final Messages messages;
    private volatile IpoGuiOpener ipoGui;
    private volatile CompanyGuiOpener financeGui;
    private volatile CompanyGuiOpener issuanceGui;
    private final ConcurrentHashMap<UUID, CompanyGuiSession> sessions = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> inventoryReplacements = ConcurrentHashMap.newKeySet();
    private final java.util.Set<UUID> mutationsInFlight = ConcurrentHashMap.newKeySet();

    public CompanyGuiController(JavaPlugin plugin, CompanyQueryService companies, CompanyRegistrationService registration,
                                NativeAssetService nativeAssets, AssetBindingService bindings,
                                Supplier<CompanyCreationRules> rules, Supplier<? extends Collection<AssetCatalogAdapter>> catalogs,
                                Executor worker, MainThreadExecutor main,
                                BooleanSupplier accepting, Messages messages) {
        this.items = new StockGuiItemFactory(Objects.requireNonNull(plugin, "plugin"));
        this.companies = Objects.requireNonNull(companies, "companies");
        this.registration = Objects.requireNonNull(registration, "registration");
        this.nativeAssets = Objects.requireNonNull(nativeAssets, "nativeAssets");
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.rules = Objects.requireNonNull(rules, "rules");
        this.catalogs = Objects.requireNonNull(catalogs, "catalogs");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.main = Objects.requireNonNull(main, "main");
        this.accepting = Objects.requireNonNull(accepting, "accepting");
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    @Override public void open(Player player) { if (ready(player)) openHome(player); }

    /** Completes the cyclic company/IPO GUI wiring after both controllers are constructed. */
    public void attachIpoGui(IpoGuiOpener opener) { this.ipoGui = Objects.requireNonNull(opener, "opener"); }
    /** Optional finance page wiring; keeping it optional preserves the existing company centre during startup. */
    public void attachFinanceGui(CompanyGuiOpener opener) { this.financeGui = Objects.requireNonNull(opener, "opener"); }
    /** Connects the founder-only issuance entry after governance services are available. */
    public void attachIssuanceGui(CompanyGuiOpener opener) { this.issuanceGui = Objects.requireNonNull(opener, "opener"); }

    private void openHome(Player player) {
        if (!ready(player)) return;
        CompanyGuiSession session = openSession(player.getUniqueId(), CompanyGuiSession.Page.HOME, null);
        loading(player, session, "正在加载公司中心…");
        companies.findByFounder(player.getUniqueId()).whenComplete((company, error) -> onMain(player, session, () -> {
            Inventory inventory = chest(session, "BlockStock 公司中心"); fill(inventory);
            if (error != null) put(inventory, 22, Material.BARRIER, "back:home", "查询失败", "请稍后重试");
            else if (company.isEmpty()) {
                put(inventory, 22, Material.NETHER_STAR, "create:start", "创建公司", "按步骤填写名称、注册资本和分红比例");
                put(inventory, 31, Material.BOOK, "noop", "创建规则", createRulesLore(rules.get()));
            } else {
                Company value = company.get();
                put(inventory, 13, Material.NAME_TAG, "noop", value.displayName(), "状态：" + displayCompanyState(value));
                put(inventory, 29, Material.CHEST, "assets", "资产管理", "创建原生资产并确认绑定");
                put(inventory, 31, Material.PAPER, "ipo:founder", "IPO 管理", "发布、查看和认购 IPO");
                put(inventory, 35, Material.WRITABLE_BOOK, "issuance", "增发与投票", "发起增发提案并查看股东投票");
                put(inventory, 33, Material.BOOK, "finance", "财务与分红", "查看公司账户、财报与分红信息");
            }
            put(inventory, 49, Material.BARRIER, "close", "关闭", "关闭公司中心"); openInventory(player, inventory);
        }));
    }

    @EventHandler public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !matches(player.getUniqueId(), holder.session().id())) return;
        if (event.getRawSlot() < 0 || event.getRawSlot() >= event.getView().getTopInventory().getSize()) return;
        String action = items.action(event.getCurrentItem()); if (action == null) return;
        switch (action) {
            case "close" -> player.closeInventory(); case "back:home" -> openHome(player); case "create:start" -> beginCompanyName(player);
            case "assets" -> openAssets(player); case "asset:create-native" -> beginNativeAssetName(player);
            case "ipo:founder" -> openFounderIpo(player);
            case "issuance" -> openIssuance(player);
            case "finance" -> openFinance(player);
            case "input" -> handleInput(player, holder, event.getCurrentItem()); case "confirm:create" -> confirmCompany(player, holder.session());
            case "confirm:create-native" -> confirmNativeAsset(player, holder.session()); case "confirm:bind" -> confirmBinding(player, holder.session());
            case "cancel" -> openHome(player); default -> routeDynamic(player, action, holder.session());
        }
    }

    @EventHandler public void onInventoryDrag(InventoryDragEvent event) { if (event.getInventory().getHolder() instanceof Holder) event.setCancelled(true); }
    @EventHandler public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder) || !(event.getPlayer() instanceof Player player)) return;
        UUID playerId = player.getUniqueId(); if (!inventoryReplacements.contains(playerId) && matches(playerId, holder.session().id())) sessions.remove(playerId, holder.session());
    }
    @EventHandler public void onPrepareAnvil(PrepareAnvilEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder)) return;
        if (holder.session().page() != CompanyGuiSession.Page.CREATE_NAME && holder.session().page() != CompanyGuiSession.Page.CREATE_CAPITAL && holder.session().page() != CompanyGuiSession.Page.CREATE_NATIVE_ASSET) return;
        ItemStack first = event.getInventory().getFirstItem(); if (first != null) event.setResult(first.clone()); event.getView().setRepairCost(0);
    }

    private void routeDynamic(Player player, String action, CompanyGuiSession session) {
        if (action.startsWith("dividend:")) {
            try {
                int percent = Integer.parseInt(action.substring("dividend:".length()));
                if (!(session.draft() instanceof CompanyGuiSession.CompanyDraft draft) || !rules.get().allowedDividendPercent().contains(percent)) return;
                showConfirmation(player, CompanyGuiSession.Page.CONFIRM_CREATE, new CompanyGuiSession.CompanyDraft(draft.name(), draft.capital(), percent));
            } catch (NumberFormatException ignored) { }
            return;
        }
        if (action.startsWith("bind:")) {
            String[] parts = action.split(":", 3);
            if (parts.length != 3) return;
            try {
                String adapterId = decode(parts[1]); String key = decode(parts[2]);
                if (!adapterId.isBlank() && !key.isBlank()) showConfirmation(player, CompanyGuiSession.Page.CONFIRM_BIND,
                        new CompanyGuiSession.AssetBindingDraft(adapterId, key, "已选资产"));
            } catch (IllegalArgumentException ignored) { }
        }
    }

    private void beginCompanyName(Player player) { if (permission(player, "blockeco.company.create")) openInput(player, CompanyGuiSession.Page.CREATE_NAME, null, "输入公司名称", "请改名为 2–24 个字符的公司名"); }
    private void openIssuance(Player player) { CompanyGuiOpener opener = issuanceGui; if (opener != null) opener.open(player); else player.sendMessage(Component.text("增发页面正在初始化，请稍后再试。")); }
    private void openFinance(Player player) { CompanyGuiOpener opener = financeGui; if (opener != null) opener.open(player); else player.sendMessage(Component.text("财务页面正在初始化，请稍后再试。")); }
    private void beginNativeAssetName(Player player) { if (permission(player, "blockeco.company.asset.bind")) openInput(player, CompanyGuiSession.Page.CREATE_NATIVE_ASSET, null, "输入资产名称", "请改名为 1–32 个字符的资产名"); }
    private void openFounderIpo(Player player) { IpoGuiOpener opener = ipoGui; if (opener == null) player.sendMessage(messages.marketUnavailable()); else opener.openFounder(player); }

    private void handleInput(Player player, Holder holder, ItemStack result) {
        String entered = plainName(result); if (entered == null) return;
        switch (holder.session().page()) {
            case CREATE_NAME -> {
                if (!permission(player, "blockeco.company.create")) return;
                try { Company.normalizeName(entered); openInput(player, CompanyGuiSession.Page.CREATE_CAPITAL, new CompanyGuiSession.CompanyDraft(entered, Money.zero(), 0), "输入实缴资本", "请输入不低于 " + rules.get().minimumCapitalMajor() + " 的金额"); }
                catch (RuntimeException invalid) { player.sendMessage(Component.text("公司名称需为 2–24 个字符。")); }
            }
            case CREATE_CAPITAL -> {
                if (!permission(player, "blockeco.company.create") || !(holder.session().draft() instanceof CompanyGuiSession.CompanyDraft draft)) return;
                try { CompanyCreationRules snapshot = rules.get(); Money capital = Money.fromMajor(new BigDecimal(entered), snapshot.scale()); if (!snapshot.acceptsPaidInCapital(capital)) throw new IllegalArgumentException("capital"); openDividendPicker(player, new CompanyGuiSession.CompanyDraft(draft.name(), capital, 0)); }
                catch (RuntimeException invalid) { player.sendMessage(Component.text("实缴资本不足或金额格式不正确。")); }
            }
            case CREATE_NATIVE_ASSET -> {
                if (!permission(player, "blockeco.company.asset.bind")) return;
                String name = entered.trim(); if (name.isBlank() || name.length() > 32) { player.sendMessage(Component.text("资产名称需为 1–32 个字符。")); return; }
                showConfirmation(player, CompanyGuiSession.Page.CONFIRM_CREATE_NATIVE_ASSET, new CompanyGuiSession.NativeAssetDraft(name));
            }
            default -> { }
        }
    }

    private void openDividendPicker(Player player, CompanyGuiSession.CompanyDraft draft) {
        CompanyGuiSession session = openSession(player.getUniqueId(), CompanyGuiSession.Page.CREATE_DIVIDEND, draft); Inventory inventory = chest(session, "选择分红比例"); fill(inventory);
        int[] slots = {20, 22, 24, 30, 32, 34}; List<Integer> choices = rules.get().allowedDividendPercent();
        for (int index = 0; index < choices.size() && index < slots.length; index++) { int percent = choices.get(index); put(inventory, slots[index], Material.GOLD_INGOT, "dividend:" + percent, percent + "% 分红", "确认后进入注册确认页面"); }
        put(inventory, 49, Material.ARROW, "back:home", "取消", "返回公司中心，不会扣款"); openInventory(player, inventory);
    }

    private void openAssets(Player player) {
        if (!permission(player, "blockeco.company.asset.bind") || !ready(player)) return;
        CompanyGuiSession session = openSession(player.getUniqueId(), CompanyGuiSession.Page.ASSETS, null); loading(player, session, "正在加载原生资产…");
        CompletableFuture<List<CatalogChoice>> nativeChoices = CompletableFuture.supplyAsync(
                () -> nativeAssets.listOwned(player.getUniqueId(), "", 45).stream()
                        .map(choice -> new CatalogChoice(NativeAssetService.ADAPTER_ID, choice)).toList(), worker);
        companies.findByFounder(player.getUniqueId()).thenCombine(
                nativeChoices.thenCombine(main.submit(() -> listExternalCatalogAssets(player.getUniqueId())), this::combineChoices),
                AssetPage::new)
                .whenComplete((page, failure) -> onMain(player, session, () -> {
                    if (failure != null || page.company().isEmpty()) { player.sendMessage(failure == null ? messages.companyNotFound() : messages.lookupFailed()); openHome(player); return; }
                    Inventory inventory = chest(session, "BlockStock 资产管理"); fill(inventory); put(inventory, 45, Material.NETHER_STAR, "asset:create-native", "创建原生资产", "不依赖外部插件；收益初始为零");
                    int slot = 0; for (var choice : page.choices()) { if (slot >= 45) break; put(inventory, slot++, Material.CHEST,
                            "bind:" + encode(choice.adapterId()) + ":" + encode(choice.choice().externalKey()), choice.choice().displayName(),
                            choice.choice().type() + "；点击后再次确认归属并绑定"); }
                    if (slot == 0) put(inventory, 22, Material.BARRIER, "noop", "暂无可绑定资产", "先创建原生经营资产，或安装已适配的外部插件");
                    put(inventory, 49, Material.ARROW, "back:home", "返回公司中心", "返回上一级"); openInventory(player, inventory);
                }));
    }

    private void showConfirmation(Player player, CompanyGuiSession.Page page, CompanyGuiSession.Draft draft) {
        CompanyGuiSession session = openSession(player.getUniqueId(), page, draft); Inventory inventory = chest(session, "确认执行"); fill(inventory);
        put(inventory, 22, Material.BOOK, "noop", "请确认", describe(draft));
        String confirm = switch (page) { case CONFIRM_CREATE -> "confirm:create"; case CONFIRM_CREATE_NATIVE_ASSET -> "confirm:create-native"; case CONFIRM_BIND -> "confirm:bind"; default -> "noop"; };
        put(inventory, 29, Material.LIME_WOOL, confirm, "确认执行", "确认前不会扣款或写入资产"); put(inventory, 33, Material.RED_WOOL, "cancel", "取消", "不执行任何操作"); openInventory(player, inventory);
    }

    private void confirmCompany(Player player, CompanyGuiSession session) {
        if (!permission(player, "blockeco.company.create") || !(session.draft() instanceof CompanyGuiSession.CompanyDraft draft)) return;
        runOnce(player, () -> companies.findByFounder(player.getUniqueId()).thenCompose(existing -> existing.isPresent()
                ? CompletableFuture.completedFuture(RegistrationResult.of(RegistrationResult.Status.DUPLICATE_NAME, "founder already has a company"))
                : registration.register(new RegistrationRequest(player.getUniqueId(), draft.name(), draft.capital(), draft.dividendPercent())))
                .whenComplete((result, failure) -> complete(player, session, failure == null ? registrationMessage(result) : messages.registrationFailed(), this::openHome)));
    }
    private void confirmNativeAsset(Player player, CompanyGuiSession session) {
        if (!permission(player, "blockeco.company.asset.bind") || !(session.draft() instanceof CompanyGuiSession.NativeAssetDraft draft)) return;
        runOnce(player, () -> companies.findByFounder(player.getUniqueId()).thenCompose(company -> company.<java.util.concurrent.CompletionStage<cn.blockeco.exchange.domain.finance.NativeAsset>>map(value -> nativeAssets.create(value.id(), player.getUniqueId(), draft.name())).orElseGet(() -> CompletableFuture.failedFuture(new IllegalStateException("company missing"))))
                .whenComplete((asset, failure) -> complete(player, session, failure == null ? Component.text("原生资产已创建。请在资产列表中确认绑定。") : messages.assetBindFailed(), this::openAssets)));
    }
    private void confirmBinding(Player player, CompanyGuiSession session) {
        if (!permission(player, "blockeco.company.asset.bind") || !(session.draft() instanceof CompanyGuiSession.AssetBindingDraft draft)) return;
        runOnce(player, () -> companies.findByFounder(player.getUniqueId()).thenCompose(company -> company.<java.util.concurrent.CompletionStage<cn.blockeco.exchange.domain.finance.AssetBinding>>map(value -> bindings.bind(value.id(), player.getUniqueId(), draft.adapterId(), draft.externalKey())).orElseGet(() -> CompletableFuture.failedFuture(new IllegalStateException("company missing"))))
                .whenComplete((binding, failure) -> complete(player, session, failure == null ? messages.assetBound() : messages.assetBindFailed(), this::openAssets)));
    }

    private void runOnce(Player player, Runnable operation) { if (!ready(player) || !mutationsInFlight.add(player.getUniqueId())) { player.sendMessage(Component.text("操作正在处理中，请勿重复提交。")); return; } operation.run(); }
    private void complete(Player player, CompanyGuiSession session, Component outcome, java.util.function.Consumer<Player> next) { mutationsInFlight.remove(player.getUniqueId()); onMain(player, session, () -> { player.sendMessage(outcome); next.accept(player); }); }
    private void openInput(Player player, CompanyGuiSession.Page page, CompanyGuiSession.Draft draft, String title, String hint) { CompanyGuiSession session = openSession(player.getUniqueId(), page, draft); Inventory inventory = Bukkit.createInventory(new Holder(session), InventoryType.ANVIL, Component.text(title)); inventory.setItem(0, items.action(Material.PAPER, "input", Component.text("输入"), List.of(Component.text(hint)))); openInventory(player, inventory); }
    private CompanyGuiSession openSession(UUID player, CompanyGuiSession.Page page, CompanyGuiSession.Draft draft) { CompanyGuiSession next = CompanyGuiSession.open(player).next(page, draft); sessions.put(player, next); return next; }
    boolean matches(UUID player, UUID session) { CompanyGuiSession current = sessions.get(player); return current != null && current.belongsTo(player) && current.id().equals(session); }
    private boolean ready(Player player) { if (accepting.getAsBoolean()) return true; player.sendMessage(messages.initializing()); return false; }
    private boolean permission(Player player, String permission) { if (player.hasPermission(permission)) return true; player.sendMessage(messages.noPermission()); return false; }
    private void onMain(Player player, CompanyGuiSession session, Runnable work) { main.submit(() -> { if (player.isOnline() && accepting.getAsBoolean() && matches(player.getUniqueId(), session.id())) work.run(); return null; }); }
    private void openInventory(Player player, Inventory inventory) { UUID id = player.getUniqueId(); inventoryReplacements.add(id); try { player.openInventory(inventory); } finally { inventoryReplacements.remove(id); } }
    private Inventory chest(CompanyGuiSession session, String title) { return Bukkit.createInventory(new Holder(session), 54, Component.text(title)); }
    private void loading(Player player, CompanyGuiSession session, String text) { Inventory inventory = chest(session, "BlockStock 公司中心"); fill(inventory); put(inventory, 22, Material.CLOCK, "noop", text, "请稍候，不要关闭当前页面"); openInventory(player, inventory); }
    private void fill(Inventory inventory) { for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, items.filler()); }
    private void put(Inventory inventory, int slot, Material material, String action, String name, String lore) { inventory.setItem(slot, items.action(material, action, Component.text(name), List.of(Component.text(lore)))); }
    private static String plainName(ItemStack item) { if (item == null || !item.hasItemMeta() || item.getItemMeta().displayName() == null) return null; return PlainTextComponentSerializer.plainText().serialize(item.getItemMeta().displayName()).trim(); }
    private List<CatalogChoice> listExternalCatalogAssets(UUID player) {
        List<CatalogChoice> choices = new ArrayList<>();
        for (AssetCatalogAdapter adapter : catalogs.get()) {
            if (NativeAssetService.ADAPTER_ID.equals(adapter.id())) continue;
            try {
                for (AssetCatalogAdapter.AssetChoice choice : adapter.listOwned(player, "", 45)) {
                    if (choices.size() >= 45) return List.copyOf(choices);
                    choices.add(new CatalogChoice(adapter.id(), choice));
                }
            } catch (RuntimeException ignored) { /* optional provider unavailable; do not break native assets */ }
        }
        return List.copyOf(choices);
    }
    private List<CatalogChoice> combineChoices(List<CatalogChoice> nativeChoices, List<CatalogChoice> externalChoices) {
        List<CatalogChoice> result = new ArrayList<>(nativeChoices);
        for (CatalogChoice choice : externalChoices) {
            if (result.size() >= 45) break;
            result.add(choice);
        }
        return List.copyOf(result);
    }
    private static String encode(String value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
    private static String decode(String value) { return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8); }
    private static String createRulesLore(CompanyCreationRules rules) { return "创建费 " + rules.registrationFeeMajor() + "；最低资本 " + rules.minimumCapitalMajor() + "；分红 " + rules.dividendChoices() + "%"; }
    private static String displayCompanyState(Company company) { return "PENDING_ASSET_BINDING".equals(company.status().name()) ? "待绑定资产" : company.status().name(); }
    private String describe(CompanyGuiSession.Draft draft) { return switch (draft) { case CompanyGuiSession.CompanyDraft company -> "创建「" + company.name() + "」；资本 " + company.capital().toMajor(rules.get().scale()).toPlainString() + "；分红 " + company.dividendPercent() + "%"; case CompanyGuiSession.NativeAssetDraft asset -> "创建原生经营资产「" + asset.name() + "」；不会自动产生收益"; case CompanyGuiSession.AssetBindingDraft asset -> "绑定「" + asset.displayName() + "」；确认时会再次校验归属"; }; }
    private Component registrationMessage(RegistrationResult result) { return switch (result.status()) { case SUCCESS -> messages.registrationSuccess(); case INSUFFICIENT_FUNDS -> messages.insufficientFunds(); case DUPLICATE_NAME -> messages.duplicateName(); case REFUNDED_AFTER_FAILURE -> messages.refunded(); case PROVIDER_FAILURE, RECOVERY_REQUIRED -> messages.recoveryRequired(); }; }
    private record AssetPage(java.util.Optional<Company> company, List<CatalogChoice> choices) { }
    private record CatalogChoice(String adapterId, AssetCatalogAdapter.AssetChoice choice) { }
    private record Holder(CompanyGuiSession session) implements InventoryHolder { @Override public Inventory getInventory() { return null; } }
}
