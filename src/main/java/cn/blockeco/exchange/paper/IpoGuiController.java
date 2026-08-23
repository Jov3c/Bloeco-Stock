package cn.blockeco.exchange.paper;

import cn.blockeco.exchange.application.CompanyQueryService;
import cn.blockeco.exchange.application.PrimaryOfferingService;
import cn.blockeco.exchange.application.SubscriptionResult;
import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.finance.PublicOfferingView;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.ports.MainThreadExecutor;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
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

/**
 * Vanilla-client IPO workflow.  It deliberately delegates all timing, ownership, asset and
 * monetary validation to {@link PrimaryOfferingService}; this class only owns an inventory
 * session and an explicit confirmation step.
 */
public final class IpoGuiController implements Listener, IpoGuiOpener {
    private static final int PAGE_SIZE = 45;
    private final JavaPlugin plugin;
    private final PrimaryOfferingService offerings;
    private final CompanyQueryService companies;
    private final MainThreadExecutor main;
    private final BooleanSupplier accepting;
    private final Messages messages;
    private final int scale;
    private final CompanyGuiOpener companyCenter;
    private final StockGuiOpener stockHome;
    private final StockGuiItemFactory items;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Set<UUID> replacing = ConcurrentHashMap.newKeySet();
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    public IpoGuiController(JavaPlugin plugin, PrimaryOfferingService offerings, CompanyQueryService companies,
                            MainThreadExecutor main, BooleanSupplier accepting, Messages messages, int scale,
                            CompanyGuiOpener companyCenter, StockGuiOpener stockHome) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.offerings = Objects.requireNonNull(offerings, "offerings");
        this.companies = Objects.requireNonNull(companies, "companies");
        this.main = Objects.requireNonNull(main, "main");
        this.accepting = Objects.requireNonNull(accepting, "accepting");
        this.messages = Objects.requireNonNull(messages, "messages");
        if (scale < 0 || scale > 8) throw new IllegalArgumentException("scale");
        this.scale = scale;
        this.companyCenter = companyCenter;
        this.stockHome = stockHome;
        this.items = new StockGuiItemFactory(plugin);
    }

    @Override public void openPublic(Player player) { openPublic(player, 0); }

    private void openPublic(Player player, int page) {
        if (!ready(player)) return;
        Session session = open(player, Page.PUBLIC, Math.max(0, page), null, null);
        loading(player, session, "正在加载公开 IPO…");
        offerings.listPublic(200).whenComplete((views, error) -> onMain(player, session, () -> {
            if (error != null) { player.sendMessage(messages.ipoPublicQueryFailed()); fallbackStock(player); return; }
            int start = Math.min(session.pageIndex() * PAGE_SIZE, views.size());
            int end = Math.min(start + PAGE_SIZE, views.size());
            Inventory inventory = inventory(session, "BlockStock 公开 IPO " + (session.pageIndex() + 1)); fill(inventory);
            if (views.isEmpty()) put(inventory, 22, Material.BARRIER, "noop", "暂无公开 IPO", "请稍后再来查看");
            for (int i = start; i < end; i++) {
                PublicOfferingView offer = views.get(i);
                put(inventory, i - start, Material.PAPER, "offer:" + offer.offeringId(),
                        offer.companyDisplayName() + " · " + state(offer),
                        "发行价 " + amount(offer.issuePrice()) + " | 可认购 " + offer.availableShares() + " 股");
            }
            put(inventory, 45, Material.ARROW, "public:prev", "上一页", "查看上一页");
            put(inventory, 49, Material.COMPASS, "back:stock", "交易所主页", "返回主菜单");
            put(inventory, 51, Material.NETHER_STAR, "founder", "我的公司 IPO", "发布或查看你公司的 IPO");
            if (end < views.size()) put(inventory, 53, Material.ARROW, "public:next", "下一页", "查看下一页");
            openInventory(player, inventory);
        }));
    }

    @Override public void openFounder(Player player) {
        if (!ready(player)) return;
        if (!player.hasPermission("blockeco.company.ipo.announce")) { player.sendMessage(messages.noPermission()); return; }
        Session session = open(player, Page.FOUNDER, 0, null, null);
        loading(player, session, "正在加载公司 IPO…");
        companies.findByFounder(player.getUniqueId()).whenComplete((company, error) -> onMain(player, session, () -> {
            if (error != null || company.isEmpty()) { player.sendMessage(error == null ? messages.companyNotFound() : messages.lookupFailed()); fallbackCompany(player); return; }
            Inventory inventory = inventory(session, "BlockStock IPO 管理"); fill(inventory);
            var value = company.get();
            put(inventory, 13, Material.NAME_TAG, "noop", value.displayName(), "状态：" + value.status());
            put(inventory, 29, Material.LIME_WOOL, "announce:" + value.id().value(), "发布 IPO", "输入募资目标与发行价；目标不可超过实缴资本的 5 倍");
            put(inventory, 33, Material.PAPER, "public", "公开 IPO", "查看所有公司的发行和认购进度");
            put(inventory, 49, Material.ARROW, "back:company", "返回公司中心", "返回公司管理");
            openInventory(player, inventory);
        }));
    }

    @EventHandler public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !matches(player, holder.session())) return;
        if (event.getRawSlot() < 0 || event.getRawSlot() >= event.getView().getTopInventory().getSize()) return;
        String action = items.action(event.getCurrentItem()); if (action == null) return;
        switch (action) {
            case "back:stock" -> fallbackStock(player);
            case "back:company" -> fallbackCompany(player);
            case "public" -> openPublic(player);
            case "founder" -> openFounder(player);
            case "public:prev" -> openPublic(player, Math.max(0, holder.session().pageIndex() - 1));
            case "public:next" -> openPublic(player, holder.session().pageIndex() + 1);
            case "confirm" -> confirm(player, holder.session());
            case "cancel" -> openPublic(player);
            case "input" -> { if (holder.session().page() == Page.INPUT && event.getRawSlot() == 2) consumeInput(player, holder.session(), event.getCurrentItem()); }
            default -> route(player, action);
        }
    }

    @EventHandler public void drag(InventoryDragEvent event) { if (event.getInventory().getHolder() instanceof Holder) event.setCancelled(true); }
    @EventHandler public void close(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player && event.getInventory().getHolder() instanceof Holder holder
                && !replacing.contains(player.getUniqueId())) sessions.remove(player.getUniqueId(), holder.session());
    }
    @EventHandler public void prepare(PrepareAnvilEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder) || holder.session().page() != Page.INPUT) return;
        ItemStack first = event.getInventory().getFirstItem(); if (first != null) event.setResult(first.clone());
        event.getView().setRepairCost(0);
    }

    private void route(Player player, String action) {
        if (action.startsWith("offer:")) {
            try { openOffer(player, UUID.fromString(action.substring("offer:".length()))); }
            catch (IllegalArgumentException ignored) { player.sendMessage(messages.publicIpoNotFound()); }
        } else if (action.startsWith("announce:")) {
            try { openInput(player, new Input(InputKind.TARGET, new CompanyId(UUID.fromString(action.substring(9))), null, 0, null)); }
            catch (IllegalArgumentException ignored) { player.sendMessage(messages.ipoAnnounceFailed()); }
        } else if (action.equals("subscribe")) {
            Session current = sessions.get(player.getUniqueId());
            if (current != null && current.draft() instanceof Offer offer) openInput(player, new Input(InputKind.SHARES, null, offer.offering(), 0, null));
        }
    }

    private void openOffer(Player player, UUID offeringId) {
        if (!ready(player)) return;
        Session session = open(player, Page.DETAIL, 0, null, null); loading(player, session, "正在加载 IPO…");
        offerings.findPublic(offeringId).whenComplete((view, error) -> onMain(player, session, () -> {
            if (error != null || view.isEmpty()) { player.sendMessage(error == null ? messages.publicIpoNotFound() : messages.ipoPublicQueryFailed()); openPublic(player); return; }
            PublicOfferingView offer = view.get();
            Session displayed = replace(player, session, Page.DETAIL, 0, new Offer(offer.offeringId()));
            Inventory inventory = inventory(displayed, "BlockStock IPO 详情"); fill(inventory);
            put(inventory, 13, Material.NAME_TAG, "noop", offer.companyDisplayName(), "状态：" + state(offer));
            put(inventory, 22, Material.GOLD_INGOT, "noop", "发行价 " + amount(offer.issuePrice()), "可认购 " + offer.availableShares() + " / 总计 " + offer.maximumShares() + " 股");
            put(inventory, 31, Material.CLOCK, "noop", "认购时间", "开放 " + time(offer.opensAt()) + " | 截止 " + time(offer.closesAt()));
            if (player.hasPermission("blockeco.company.ipo.subscribe")) put(inventory, 42, Material.LIME_WOOL, "subscribe", "认购", "输入股数，确认后从个人钱包扣款");
            put(inventory, 45, Material.ARROW, "public", "返回 IPO 列表", "返回公开 IPO");
            put(inventory, 49, Material.COMPASS, "back:stock", "交易所主页", "返回主菜单");
            openInventory(player, inventory);
        }));
    }

    private void openInput(Player player, Input input) {
        Session session = open(player, Page.INPUT, 0, null, input);
        String hint = switch (input.kind()) { case TARGET -> "输入募资目标"; case PRICE -> "输入每股发行价"; case SHARES -> "输入认购股数"; };
        Inventory inventory = Bukkit.createInventory(new Holder(session), InventoryType.ANVIL, Component.text(hint));
        inventory.setItem(0, items.action(Material.PAPER, "input", Component.text("输入"), List.of(Component.text(hint))));
        openInventory(player, inventory);
    }

    private void consumeInput(Player player, Session session, ItemStack result) {
        if (!(session.draft() instanceof Input input)) return;
        String text = PlainTextComponentSerializer.plainText().serialize(result.getItemMeta().displayName()).trim();
        try {
            if (input.kind() == InputKind.SHARES) {
                long shares = Long.parseLong(text); if (shares <= 0) throw new NumberFormatException();
                showConfirmation(player, new Subscribe(input.offering(), shares)); return;
            }
            Money value = Money.fromMajor(new BigDecimal(text), scale); if (value.minorUnits() <= 0) throw new NumberFormatException();
            if (input.kind() == InputKind.TARGET) { openInput(player, new Input(InputKind.PRICE, input.company(), null, 0, value)); return; }
            showConfirmation(player, new Announce(input.company(), input.previousMoney(), value));
        } catch (ArithmeticException | NumberFormatException exception) {
            player.sendMessage(Component.text(input.kind() == InputKind.SHARES ? "请输入正整数股数。" : "请输入有效的正金额。"));
        }
    }

    private void showConfirmation(Player player, Draft draft) {
        Session session = open(player, Page.CONFIRM, 0, null, draft); Inventory inventory = inventory(session, "确认 IPO 操作"); fill(inventory);
        String description = draft instanceof Announce announce ? "目标 " + amount(announce.target()) + "，发行价 " + amount(announce.price())
                : "认购 " + ((Subscribe) draft).shares() + " 股（将从个人钱包扣款）";
        put(inventory, 22, Material.BOOK, "noop", "请确认", description);
        put(inventory, 29, Material.LIME_WOOL, "confirm", "确认执行", "提交后由服务端账本处理");
        put(inventory, 33, Material.RED_WOOL, "cancel", "取消", "不执行任何操作");
        openInventory(player, inventory);
    }

    private void confirm(Player player, Session session) {
        if (!ready(player) || !(session.draft() instanceof Draft draft)) return;
        if (!inFlight.add(player.getUniqueId())) { player.sendMessage(messages.ipoProcessing()); return; }
        if (draft instanceof Announce announce) {
            offerings.announce(announce.company(), player.getUniqueId(), announce.target(), announce.price())
                    .whenComplete((offer, error) -> finish(player, session, error == null ? messages.ipoAnnounced() : messages.ipoAnnounceFailed(), true));
        } else {
            Subscribe subscribe = (Subscribe) draft;
            offerings.subscribe(player.getUniqueId(), subscribe.offering(), subscribe.shares()).whenComplete((result, error) ->
                    finish(player, session, error == null ? messages.ipoSubscriptionResult(result.status()) : messages.ipoSubscriptionResult(SubscriptionResult.Status.PROVIDER_FAILURE), false));
        }
    }
    private void finish(Player player, Session session, Component message, boolean company) {
        inFlight.remove(player.getUniqueId()); onMain(player, session, () -> { player.sendMessage(message); if (company) openFounder(player); else openPublic(player); });
    }

    private Session open(Player player, Page page, int pageIndex, String ignored, Draft draft) { return replace(player, null, page, pageIndex, draft); }
    private Session replace(Player player, Session expected, Page page, int pageIndex, Draft draft) {
        Session next = new Session(UUID.randomUUID(), player.getUniqueId(), page, pageIndex, draft); sessions.put(player.getUniqueId(), next); return next;
    }
    private boolean matches(Player player, Session session) { return session.owner().equals(player.getUniqueId()) && session.equals(sessions.get(player.getUniqueId())); }
    private void onMain(Player player, Session session, Runnable action) { main.submit(() -> { if (player.isOnline() && accepting.getAsBoolean() && matches(player, session)) action.run(); return null; }); }
    private boolean ready(Player player) { if (accepting.getAsBoolean()) return true; player.sendMessage(messages.initializing()); return false; }
    private void fallbackStock(Player player) { if (stockHome != null) stockHome.openHome(player); else player.closeInventory(); }
    private void fallbackCompany(Player player) { if (companyCenter != null) companyCenter.open(player); else fallbackStock(player); }
    private Inventory inventory(Session session, String title) { return Bukkit.createInventory(new Holder(session), 54, Component.text(title)); }
    private void openInventory(Player player, Inventory inventory) { replacing.add(player.getUniqueId()); try { player.openInventory(inventory); } finally { replacing.remove(player.getUniqueId()); } }
    private void loading(Player player, Session session, String name) { Inventory inv = inventory(session, "BlockStock IPO"); fill(inv); put(inv, 22, Material.CLOCK, "noop", name, "请稍候"); openInventory(player, inv); }
    private void fill(Inventory inventory) { for (int i = 0; i < inventory.getSize(); i++) inventory.setItem(i, items.filler()); }
    private void put(Inventory inventory, int slot, Material material, String action, String title, String lore) { inventory.setItem(slot, items.action(material, action, Component.text(title), List.of(Component.text(lore)))); }
    private String amount(Money value) { return value.toMajor(scale).setScale(scale).toPlainString(); }
    private String time(Instant instant) { return instant.toString().replace("T", " ").replace("Z", " UTC"); }
    private String state(PublicOfferingView offering) { return offering.state().name().equals("OPEN") ? "开放认购" : offering.state().name().equals("ANNOUNCED") ? "公告期（12 小时后开放）" : offering.state().name(); }

    private enum Page { PUBLIC, DETAIL, FOUNDER, INPUT, CONFIRM }
    private enum InputKind { TARGET, PRICE, SHARES }
    private sealed interface Draft permits Input, Announce, Subscribe, Offer { }
    private record Input(InputKind kind, CompanyId company, UUID offering, long unused, Money previousMoney) implements Draft { }
    private record Announce(CompanyId company, Money target, Money price) implements Draft { }
    private record Subscribe(UUID offering, long shares) implements Draft { }
    private record Offer(UUID offering) implements Draft { }
    private record Session(UUID id, UUID owner, Page page, int pageIndex, Draft draft) { }
    private record Holder(Session session) implements InventoryHolder { @Override public Inventory getInventory() { return null; } }
}
