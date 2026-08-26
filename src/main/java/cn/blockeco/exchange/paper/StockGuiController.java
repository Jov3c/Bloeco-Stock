package cn.blockeco.exchange.paper;

import cn.blockeco.exchange.application.SecondaryMarketQueryService;
import cn.blockeco.exchange.application.SecondaryMarketService;
import cn.blockeco.exchange.application.SecuritiesCashService;
import cn.blockeco.exchange.application.PortfolioView;
import cn.blockeco.exchange.application.PublicMarketRow;
import cn.blockeco.exchange.application.MarketNewsItem;
import cn.blockeco.exchange.application.PublicStockQueryService;
import cn.blockeco.exchange.application.PublicStockInfo;
import cn.blockeco.exchange.application.MarketChart;
import cn.blockeco.exchange.application.MarketChartQueryService;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.domain.trading.LimitOrder;
import cn.blockeco.exchange.ports.MainThreadExecutor;
import java.math.BigDecimal;
import java.util.List;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/** Vanilla-client BlockStock menu. It only renders and delegates; services remain the ledger authority. */
public final class StockGuiController implements Listener, StockGuiOpener {
    private final Map<UUID, StockGuiSession> sessions = new ConcurrentHashMap<>();
    private final Set<UUID> mutationsInFlight = ConcurrentHashMap.newKeySet();
    private final Set<UUID> inventoryReplacements = ConcurrentHashMap.newKeySet();
    private final StockGuiItemFactory items;
    private final JavaPlugin plugin;
    private final SecondaryMarketQueryService queries;
    private final SecuritiesCashService cash;
    private final SecondaryMarketService trading;
    private final MainThreadExecutor mainThread;
    private final BooleanSupplier accepting;
    private final BooleanSupplier mutationsOpen;
    private final Messages messages;
    private final int currencyScale;
    private final CompanyGuiOpener companyGui;
    private volatile IpoGuiOpener ipoGui;
    private volatile PublicStockQueryService publicQueries;
    private volatile MarketChartQueryService chartQueries;

    public StockGuiController(JavaPlugin plugin, SecondaryMarketQueryService queries, SecuritiesCashService cash,
                              SecondaryMarketService trading, MainThreadExecutor mainThread, BooleanSupplier accepting,
                              BooleanSupplier mutationsOpen, Messages messages, int currencyScale) {
        this(plugin,queries,cash,trading,mainThread,accepting,mutationsOpen,messages,currencyScale,null,null);
    }
    public StockGuiController(JavaPlugin plugin, SecondaryMarketQueryService queries, SecuritiesCashService cash,
                              SecondaryMarketService trading, MainThreadExecutor mainThread, BooleanSupplier accepting,
                              BooleanSupplier mutationsOpen, Messages messages, int currencyScale, CompanyGuiOpener companyGui) {
        this(plugin,queries,cash,trading,mainThread,accepting,mutationsOpen,messages,currencyScale,companyGui,null);
    }
    public StockGuiController(JavaPlugin plugin, SecondaryMarketQueryService queries, SecuritiesCashService cash,
                              SecondaryMarketService trading, MainThreadExecutor mainThread, BooleanSupplier accepting,
                              BooleanSupplier mutationsOpen, Messages messages, int currencyScale, CompanyGuiOpener companyGui, IpoGuiOpener ipoGui) {
        if (currencyScale < 0 || currencyScale > 8) throw new IllegalArgumentException("currencyScale");
        this.plugin = Objects.requireNonNull(plugin, "plugin"); this.items = new StockGuiItemFactory(this.plugin); this.queries = Objects.requireNonNull(queries, "queries");
        this.cash = Objects.requireNonNull(cash, "cash"); this.trading = Objects.requireNonNull(trading, "trading");
        this.mainThread = Objects.requireNonNull(mainThread, "mainThread"); this.accepting = Objects.requireNonNull(accepting, "accepting");
        this.mutationsOpen = Objects.requireNonNull(mutationsOpen, "mutationsOpen"); this.messages = Objects.requireNonNull(messages, "messages"); this.currencyScale = currencyScale; this.companyGui=companyGui; this.ipoGui=ipoGui;
    }

    private StockGuiController() { plugin = null; items = null; queries = null; cash = null; trading = null; mainThread = null; accepting = () -> true; mutationsOpen = () -> true; messages = null; currencyScale = 2; companyGui=null; ipoGui=null; }

    static StockGuiController forSessionTests() { return new StockGuiController(); }

    /** Completes the cyclic GUI wiring after the IPO controller is constructed. */
    public void attachIpoGui(IpoGuiOpener opener) { this.ipoGui = Objects.requireNonNull(opener, "opener"); }
    public void attachPublicQueries(PublicStockQueryService opener) { this.publicQueries = Objects.requireNonNull(opener, "queries"); }
    /** Connects daily/intraday candle reads after the exchange services are constructed. */
    public void attachChartQueries(MarketChartQueryService queries) { this.chartQueries = Objects.requireNonNull(queries, "queries"); }

    StockGuiSession openSession(UUID player, StockGuiSession.Page page, int pageIndex, String stockCode, StockGuiSession.Draft draft) {
        StockGuiSession prior = sessions.get(player);
        StockGuiSession next = prior == null ? StockGuiSession.open(player).next(page, pageIndex, stockCode, draft) : prior.next(page, pageIndex, stockCode, draft);
        sessions.put(player, next); return next;
    }

    boolean matches(UUID player, UUID session) {
        StockGuiSession current = sessions.get(player);
        return current != null && current.belongsTo(player) && current.id().equals(session);
    }

    StockGuiSession.Page currentPage(UUID player) {
        StockGuiSession current = sessions.get(player);
        return current == null ? null : current.page();
    }

    boolean beginSubmission(UUID player, UUID confirmationId) {
        StockGuiSession confirmation = sessions.get(player);
        if (confirmation == null || !confirmation.belongsTo(player) || !confirmation.id().equals(confirmationId)
                || confirmation.page() != StockGuiSession.Page.CONFIRM) return false;
        StockGuiSession submitting = confirmation.next(StockGuiSession.Page.SUBMITTING, 0, confirmation.stockCode(), confirmation.draft());
        return sessions.replace(player, confirmation, submitting);
    }

    void beginInventoryReplacement(UUID player) { inventoryReplacements.add(player); }
    void endInventoryReplacement(UUID player) { inventoryReplacements.remove(player); }
    boolean shouldClearOnClose(UUID player, UUID session) { return !inventoryReplacements.contains(player) && matches(player, session); }

    public void openHome(Player player) {
        if (!accepting.getAsBoolean()) { player.sendMessage(messages.initializing()); return; }
        StockGuiSession session = openSession(player.getUniqueId(), StockGuiSession.Page.HOME, 0, null, null);
        Inventory inventory = Bukkit.createInventory(new Holder(session), 54, Component.text("BlockStock 交易所"));
        fill(inventory);
        put(inventory, 11, Material.COMPASS, "market", "市场行情", "查看所有已上市公司和五档盘口");
        put(inventory, 13, Material.GOLD_INGOT, "cash", "证券账户", "查看可用与冻结资金，转入或转出");
        put(inventory, 15, Material.CHEST, "portfolio", "我的持仓", "查看你持有的股票");
        put(inventory, 20, Material.NETHER_STAR, "company", "公司中心", "创建、资产绑定、IPO 与公告");
        put(inventory, 29, Material.WRITABLE_BOOK, "orders", "我的委托", "查看和撤销自己的委托");
        put(inventory, 31, Material.EMERALD, "trades", "成交记录", "查看自己的最近成交");
        put(inventory, 33, Material.PAPER, "news", "市场快讯", "查看最近五条蓝筹市场公告");
        put(inventory, 49, Material.BARRIER, "close", "关闭", "关闭交易所");
        openInventory(player, inventory);
    }

    @EventHandler public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !matches(player.getUniqueId(), holder.session().id())) return;
        if (event.getRawSlot() < 0 || event.getRawSlot() >= event.getView().getTopInventory().getSize()) return;
        String action = items.action(event.getCurrentItem()); if (action == null) return;
        switch (action) {
            case "close" -> closeInventory(player);
            case "help" -> messages.stockHelp(player).forEach(player::sendMessage);
            case "ipo" -> { if (ipoGui == null) player.sendMessage(messages.marketUnavailable()); else ipoGui.openPublic(player); }
            case "news" -> openNews(player);
            case "market" -> openMarket(player, 0);
            case "cash" -> openCash(player);
            case "company" -> { if(companyGui==null)player.sendMessage(messages.marketUnavailable());else companyGui.open(player); }
            case "portfolio" -> openPortfolio(player);
            case "orders" -> openOrders(player);
            case "trades" -> openTrades(player);
            case "cash:deposit" -> openInput(player, new StockGuiSession.InputDraft(StockGuiSession.InputKind.CASH_AMOUNT, true, null, null, 0));
            case "cash:withdraw" -> openInput(player, new StockGuiSession.InputDraft(StockGuiSession.InputKind.CASH_AMOUNT, false, null, null, 0));
            case "back:home" -> openHome(player);
            case "back:market" -> openMarket(player, 0);
            case "market:next" -> openMarket(player, holder.session().pageIndex() + 1);
            case "market:prev" -> openMarket(player, Math.max(0, holder.session().pageIndex() - 1));
            case "chart:intraday" -> openDetail(player, holder.session().stockCode(), StockGuiSession.ChartMode.INTRADAY);
            case "chart:daily" -> openDetail(player, holder.session().stockCode(), StockGuiSession.ChartMode.DAILY);
            case "detail:buy" -> openInput(player, new StockGuiSession.InputDraft(StockGuiSession.InputKind.ORDER_SHARES, false, holder.session().stockCode(), LimitOrder.Side.BUY, 0));
            case "detail:sell" -> openInput(player, new StockGuiSession.InputDraft(StockGuiSession.InputKind.ORDER_SHARES, false, holder.session().stockCode(), LimitOrder.Side.SELL, 0));
            case "confirm" -> confirm(player, holder.session());
            case "cancel" -> openHome(player);
            case "result:detail" -> { if (holder.session().stockCode() == null) openHome(player); else openDetail(player, holder.session().stockCode()); }
            default -> routeDynamic(player, action);
        }
    }

    @EventHandler public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Holder) event.setCancelled(true);
    }

    @EventHandler public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder) || !(event.getPlayer() instanceof Player player)) return;
        if (shouldClearOnClose(player.getUniqueId(), holder.session().id())) sessions.remove(player.getUniqueId(), holder.session());
    }

    private void routeDynamic(Player player, String action) {
        if (action.startsWith("detail:")) { openDetail(player, action.substring("detail:".length())); return; }
        if (action.startsWith("cancel-order:")) { try { showConfirmation(player, new StockGuiSession.CancelOrder(UUID.fromString(action.substring(13)))); } catch (IllegalArgumentException ignored) { player.sendMessage(messages.stockQueryFailed()); } }
    }

    private void openMarket(Player player, int page) {
        if (!ready(player)) return;
        StockGuiSession session = openSession(player.getUniqueId(), StockGuiSession.Page.MARKET, Math.max(0, page), null, null);
        loading(player, session, "正在加载市场…");
        queries.market().whenComplete((rows, error) -> onMain(player, session, () -> {
            if (error != null) { player.sendMessage(messages.stockQueryFailed()); openHome(player); return; }
            int start = Math.min(session.pageIndex() * 45, rows.size()); int end = Math.min(start + 45, rows.size());
            Inventory inv = inventory(session, "BlockStock 市场 " + (session.pageIndex() + 1)); fill(inv);
            if (rows.isEmpty()) put(inv, 22, Material.BARRIER, "back:home", "暂无上市股票", "返回主菜单");
            for (int i = start; i < end; i++) { PublicMarketRow r = rows.get(i); put(inv, i - start, Material.PAPER, "detail:" + r.stockCode(), r.stockCode() + " " + r.companyName(), "现价 " + amount(r.latestPrice()) + "  涨跌 " + amount(r.change()) + "  量 " + r.volume()); }
            put(inv, 45, Material.ARROW, "market:prev", "上一页", "返回上一页"); put(inv, 49, Material.BARRIER, "back:home", "主菜单", "返回交易所主页");
            if (end < rows.size()) put(inv, 53, Material.ARROW, "market:next", "下一页", "查看下一页"); openInventory(player, inv);
        }));
    }

    private void openDetail(Player player, String code) { openDetail(player, code, StockGuiSession.ChartMode.INTRADAY); }

    private void openDetail(Player player, String code, StockGuiSession.ChartMode chartMode) {
        if (!ready(player)) return;
        StockGuiSession session = openSession(player.getUniqueId(), StockGuiSession.Page.DETAIL, 0, code, null).withChartMode(chartMode);
        sessions.put(player.getUniqueId(), session);
        loading(player, session, "正在加载 " + code + "…");
        queries.book(code, 5).whenComplete((book, error) -> onMain(player, session, () -> {
            if (error != null) { player.sendMessage(messages.stockQueryFailed()); openMarket(player, 0); return; }
            queries.market().thenCombine(queries.portfolio(player.getUniqueId()), (rows, portfolio) -> detailStats(rows, portfolio, code))
                    .whenComplete((stats, statsError) -> onMain(player, session, () -> loadDetailInfo(player, session, code, book,
                            statsError == null ? stats : DetailStats.empty())));
        }));
    }

    private void loadDetailInfo(Player player, StockGuiSession session, String code, SecondaryMarketQueryService.OrderBook book, DetailStats stats) {
        PublicStockQueryService source = publicQueries;
        if (source == null) { renderDetail(player, session, code, book, null, null, stats); return; }
        source.info(code).whenComplete((info, infoError) -> onMain(player, session, () -> {
                PublicStockInfo result = infoError == null ? info.orElse(null) : null;
                MarketChartQueryService charts = chartQueries;
                if (charts == null) { renderDetail(player, session, code, book, result, null, stats); return; }
                charts.chart(code).whenComplete((chart, chartError) -> onMain(player, session, () -> renderDetail(player, session, code, book, result, chartError == null ? chart.orElse(null) : null, stats)));
        }));
    }

    private void renderDetail(Player player, StockGuiSession session, String code, SecondaryMarketQueryService.OrderBook book, PublicStockInfo info, MarketChart chart, DetailStats stats) {
        Inventory inv = inventory(session, "BlockStock " + code); detailFrame(inv);
        String title = detailTitle(info == null ? code : info.companyName(), code);
        String detail = info != null && info.marketState().isPresent()
                ? "行业 " + info.industry().orElse("未分类") + " · 流动性" + (info.marketState().get().liquidityDegraded() ? "受限" : "正常")
                : "五档盘口（买卖均为匿名聚合）";
        put(inv, 4, Material.NETHER_STAR, "noop", title, "BlockStock 原生交易终端 · " + detail);
        put(inv, 10, Material.NAME_TAG, "noop", title, detail);
        List<String> cards = detailCardLabels(stats.latestPrice(), stats.change(), stats.holdingShares(), stats.turnover());
        put(inv, 11, Material.GOLD_INGOT, "noop", cards.get(0), "今日最新成交价");
        put(inv, 12, stats.change().minorUnits() >= 0 ? Material.LIME_DYE : Material.RED_DYE, "noop", cards.get(1), "相对开盘价");
        put(inv, 13, Material.CHEST, "noop", cards.get(2), "可用与冻结持仓合计");
        put(inv, 14, Material.EMERALD, "noop", cards.get(3), "今日成交额");
        put(inv, 15, Material.CLOCK, "noop", session.chartMode() == StockGuiSession.ChartMode.INTRADAY ? "分时线" : "日K线", "点击图表切换视图");
        put(inv, 16, Material.PAPER, "noop", "市场状态", info != null && info.marketState().isPresent() ? "流动性" + (info.marketState().get().liquidityDegraded() ? "受限" : "正常") : "公开市场");
        for (int i = 0; i < 5; i++) if (i < book.asks().size()) put(inv, 19 + i, Material.RED_STAINED_GLASS_PANE, "noop", orderBookLabels(book, currencyScale).get(i), book.asks().get(i).shares() + " 股");
        for (int i = 0; i < 5; i++) if (i < book.bids().size()) put(inv, 24 + i, Material.LIME_STAINED_GLASS_PANE, "noop", orderBookLabels(book, currencyScale).get(5 + i), book.bids().get(i).shares() + " 股");
        List<String> lore = chart == null ? List.of("暂无图表数据") : chartLore(chart, currencyScale, session.chartMode());
        List<Material> raster = chart == null ? List.of(Material.GRAY_STAINED_GLASS_PANE, Material.GRAY_STAINED_GLASS_PANE, Material.GRAY_STAINED_GLASS_PANE, Material.GRAY_STAINED_GLASS_PANE, Material.GRAY_STAINED_GLASS_PANE, Material.GRAY_STAINED_GLASS_PANE, Material.GRAY_STAINED_GLASS_PANE) : chartRaster(chart, session.chartMode());
        for (int i = 0; i < raster.size(); i++) put(inv, 29 + i, raster.get(i), i == 0 ? (session.chartMode() == StockGuiSession.ChartMode.INTRADAY ? "chart:daily" : "chart:intraday") : "noop", i == 0 ? (session.chartMode() == StockGuiSession.ChartMode.INTRADAY ? "切换日K线" : "切换分时线") : "图表", lore);
        put(inv, 37, Material.PAPER, "noop", "今日成交量", chart == null ? "暂无" : chart.sessionSummary().volumeShares() + " 股"); put(inv, 38, Material.GOLD_INGOT, "noop", "补偿基金", "异常交易请联系管理员申请补偿");
        put(inv, 40, Material.LIME_WOOL, "detail:buy", "买入", "输入股数和限价后确认"); put(inv, 41, Material.RED_WOOL, "detail:sell", "卖出", "输入股数和限价后确认");
        put(inv, 42, Material.BOOK, "help", "交易帮助", "查看交易规则与补偿说明"); put(inv, 43, Material.ARROW, "back:market", "返回市场", "返回列表"); openInventory(player, inv);
    }

    private void detailFrame(Inventory inventory) {
        fill(inventory);
        for (int slot = 0; slot <= 8; slot++) inventory.setItem(slot, items.action(Material.BLACK_STAINED_GLASS_PANE, "noop", Component.text(" "), List.of()));
        for (int slot = 45; slot <= 53; slot++) inventory.setItem(slot, items.action(Material.BLACK_STAINED_GLASS_PANE, "noop", Component.text(" "), List.of()));
    }

    private void openNews(Player player) {
        if (!ready(player)) return;
        PublicStockQueryService source = publicQueries;
        if (source == null) { player.sendMessage(messages.marketUnavailable()); return; }
        StockGuiSession session = openSession(player.getUniqueId(), StockGuiSession.Page.NEWS, 0, null, null); loading(player, session, "正在加载市场快讯…");
        source.recentNews(5).whenComplete((news, error) -> onMain(player, session, () -> {
            if (error != null) { player.sendMessage(messages.stockQueryFailed()); openHome(player); return; }
            Inventory inv = inventory(session, "BlockStock 市场快讯"); fill(inv); int slot = 10;
            for (MarketNewsItem item : news) { put(inv, slot++, Material.PAPER, "noop", item.headline(), item.body()); }
            if (news.isEmpty()) put(inv, 22, Material.BARRIER, "noop", "暂无市场快讯", "事件、分红和流动性公告会显示在这里");
            put(inv, 49, Material.ARROW, "back:home", "返回", "主菜单"); openInventory(player, inv);
        }));
    }

    private void openCash(Player player) { openPrivate(player, StockGuiSession.Page.CASH, "证券账户", view -> { Inventory inv = inventory(sessions.get(player.getUniqueId()), "BlockStock 证券账户"); fill(inv); put(inv, 13, Material.GOLD_INGOT, "noop", "可用资金 " + amount(view.availableCash()), "冻结资金 " + amount(view.reservedCash())); put(inv, 29, Material.LIME_WOOL, "cash:deposit", "转入证券账户", "从个人钱包转入"); put(inv, 33, Material.RED_WOOL, "cash:withdraw", "转出个人钱包", "从证券账户转出"); put(inv, 49, Material.ARROW, "back:home", "返回", "主菜单"); openInventory(player, inv); }); }
    private void openPortfolio(Player player) { openPrivate(player, StockGuiSession.Page.PORTFOLIO, "我的持仓", view -> { Inventory inv=inventory(sessions.get(player.getUniqueId()),"BlockStock 我的持仓"); fill(inv); int i=0; for(var h:view.holdings()) if(i<45) put(inv,i++,Material.CHEST,"detail:"+h.stockCode(),h.stockCode()+" "+h.companyName(),"可用 "+h.availableShares()+" 冻结 "+h.reservedShares()+" 最新 "+amount(h.latestPrice())); if(i==0)put(inv,22,Material.BARRIER,"noop","当前没有持仓",""); put(inv,49,Material.ARROW,"back:home","返回","主菜单");openInventory(player, inv); }); }
    private void openOrders(Player player) { if(!permission(player,"blockeco.stock.orders"))return; StockGuiSession s=openSession(player.getUniqueId(),StockGuiSession.Page.ORDERS,0,null,null); loading(player,s,"正在加载委托…");queries.orders(player.getUniqueId(),50).whenComplete((rows,error)->onMain(player,s,()->{if(error!=null){player.sendMessage(messages.stockQueryFailed());return;}Inventory inv=inventory(s,"BlockStock 我的委托");fill(inv);int i=0;for(var o:rows)if(i<45)put(inv,i++,Material.WRITABLE_BOOK,"cancel-order:"+o.id(),o.stockCode()+" "+(o.side()==LimitOrder.Side.BUY?"买":"卖"),"限价 "+amount(o.limitPrice())+" 剩余 "+o.remainingShares()+" 状态 "+o.state());if(i==0)put(inv,22,Material.BARRIER,"noop","当前没有委托","");put(inv,49,Material.ARROW,"back:home","返回","主菜单");openInventory(player, inv);})); }
    private void openTrades(Player player) { if(!permission(player,"blockeco.stock.trades"))return; StockGuiSession s=openSession(player.getUniqueId(),StockGuiSession.Page.TRADES,0,null,null); loading(player,s,"正在加载成交…");queries.trades(player.getUniqueId(),50).whenComplete((rows,error)->onMain(player,s,()->{if(error!=null){player.sendMessage(messages.stockQueryFailed());return;}Inventory inv=inventory(s,"BlockStock 成交记录");fill(inv);int i=0;for(var t:rows)if(i<45)put(inv,i++,Material.EMERALD,"noop",t.stockCode()+" "+(t.side()==LimitOrder.Side.BUY?"买入":"卖出"),t.shares()+" 股 @ "+amount(t.price())+" 手续费 "+amount(t.fee()));if(i==0)put(inv,22,Material.BARRIER,"noop","当前没有成交","");put(inv,49,Material.ARROW,"back:home","返回","主菜单");openInventory(player, inv);})); }

    private void openPrivate(Player player, StockGuiSession.Page page, String loading, java.util.function.Consumer<PortfolioView> renderer) { if(!permission(player,"blockeco.stock."+(page==StockGuiSession.Page.CASH?"cash":"portfolio")))return;StockGuiSession s=openSession(player.getUniqueId(),page,0,null,null);loading(player,s,"正在加载"+loading+"…");queries.portfolio(player.getUniqueId()).whenComplete((view,error)->onMain(player,s,()->{if(error!=null){player.sendMessage(messages.stockQueryFailed());return;}renderer.accept(view);})); }

    private void fill(Inventory inventory) { for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, items.filler()); }
    private void openInventory(Player player, Inventory inventory) { GuiTransitions.defer(action -> Bukkit.getScheduler().runTask(plugin, action), () -> { if (!player.isOnline()) return; beginInventoryReplacement(player.getUniqueId()); try { player.openInventory(inventory); } finally { endInventoryReplacement(player.getUniqueId()); } }); }
    private void closeInventory(Player player) { GuiTransitions.defer(action -> Bukkit.getScheduler().runTask(plugin, action), player::closeInventory); }
    private Inventory inventory(StockGuiSession session, String title) { return Bukkit.createInventory(new Holder(session), 54, Component.text(title)); }
    private void loading(Player player, StockGuiSession session, String text) { Inventory inv=inventory(session,"BlockStock"); fill(inv); put(inv,22,Material.CLOCK,"noop",text,"请稍候，不要关闭此页面"); openInventory(player, inv); }
    private boolean ready(Player player) { if(accepting.getAsBoolean())return true;player.sendMessage(messages.initializing());return false; }
    private boolean permission(Player player,String permission) { if(player.hasPermission(permission))return true;player.sendMessage(messages.noPermission());return false; }
    private void onMain(Player player, StockGuiSession session, Runnable work) { mainThread.submit(()->{if(player.isOnline()&&accepting.getAsBoolean()&&matches(player.getUniqueId(),session.id()))work.run();return null;}); }
    private String amount(Money value) { return BigDecimal.valueOf(value.minorUnits(),currencyScale).setScale(currencyScale).toPlainString(); }

    private void openInput(Player player, StockGuiSession.InputDraft draft) {
        if ((draft.kind()==StockGuiSession.InputKind.CASH_AMOUNT && !permission(player,"blockeco.stock.cash")) || (draft.kind()!=StockGuiSession.InputKind.CASH_AMOUNT && !permission(player,"blockeco.stock.trade"))) return;
        StockGuiSession session=openSession(player.getUniqueId(),StockGuiSession.Page.INPUT,0,draft.stockCode(),draft);
        String title=draft.kind()==StockGuiSession.InputKind.ORDER_SHARES?"输入正整数股数":draft.kind()==StockGuiSession.InputKind.ORDER_PRICE?"输入限价":"输入金额";
        TextInputGui.open(plugin,player,title,text->handleInput(player,session,text));
    }

    private void handleInput(Player player, StockGuiSession session, String text) {
        if(!(session.draft() instanceof StockGuiSession.InputDraft draft))return;
        try {
            if(draft.kind()==StockGuiSession.InputKind.ORDER_SHARES){long shares=Long.parseLong(text);if(shares<=0)throw new NumberFormatException();openInput(player,new StockGuiSession.InputDraft(StockGuiSession.InputKind.ORDER_PRICE,false,draft.stockCode(),draft.side(),shares));return;}
            Money money=Money.fromMajor(new BigDecimal(text),currencyScale);if(money.minorUnits()<=0)throw new NumberFormatException();
            if(draft.kind()==StockGuiSession.InputKind.CASH_AMOUNT)showConfirmation(player,new StockGuiSession.CashTransfer(draft.deposit(),money)); else showConfirmation(player,new StockGuiSession.LimitOrderDraft(draft.stockCode(),draft.side(),draft.shares(),money));
        }catch(ArithmeticException|NumberFormatException ex){player.sendMessage(draft.kind()==StockGuiSession.InputKind.ORDER_SHARES?messages.invalidShares():messages.invalidStockMoney());}
    }

    private void showConfirmation(Player player, StockGuiSession.Draft draft) { String stockCode=draft instanceof StockGuiSession.LimitOrderDraft order?order.stockCode():null;StockGuiSession session=openSession(player.getUniqueId(),StockGuiSession.Page.CONFIRM,0,stockCode,draft);Inventory inv=inventory(session,"确认执行");fill(inv);put(inv,22,Material.BOOK,"noop","请确认",describe(draft));put(inv,29,Material.LIME_WOOL,"confirm","确认执行","执行后将提交给交易账本");put(inv,33,Material.RED_WOOL,"cancel","取消","不执行任何操作");openInventory(player, inv); }
    private String describe(StockGuiSession.Draft draft) { return switch(draft){case StockGuiSession.CashTransfer c->(c.deposit()?"转入 ":"转出 ")+amount(c.amount());case StockGuiSession.LimitOrderDraft o->(o.side()==LimitOrder.Side.BUY?"买入 ":"卖出 ")+o.stockCode()+" "+o.shares()+" 股 @ "+amount(o.limitPrice());case StockGuiSession.CancelOrder c->"撤销你的委托";default->"";}; }
    private void confirm(Player player, StockGuiSession confirmation) {
        StockGuiSession.Draft draft=confirmation.draft(); if(draft==null)return;
        if(!mutationsOpen.getAsBoolean()){player.sendMessage(messages.marketUnavailable());return;}
        if(!mutationsInFlight.add(player.getUniqueId())) { player.sendMessage(Component.text("交易正在处理中，请勿重复提交。")); return; }
        if(!beginSubmission(player.getUniqueId(),confirmation.id())) { mutationsInFlight.remove(player.getUniqueId()); return; }
        StockGuiSession submitting=sessions.get(player.getUniqueId());
        if(submitting==null) { mutationsInFlight.remove(player.getUniqueId()); return; }
        loading(player,submitting,"正在提交，请勿重复操作");
        if(draft instanceof StockGuiSession.CashTransfer c){(c.deposit()?cash.deposit(player.getUniqueId(),c.amount()):cash.withdraw(player.getUniqueId(),c.amount())).whenComplete((r,e)->completeMutation(player,submitting,e==null?messages.cashResult(r):messages.stockQueryFailed()));return;}
        if(draft instanceof StockGuiSession.LimitOrderDraft o){(o.side()==LimitOrder.Side.BUY?trading.placeBuy(player.getUniqueId(),o.stockCode(),o.shares(),o.limitPrice()):trading.placeSell(player.getUniqueId(),o.stockCode(),o.shares(),o.limitPrice())).whenComplete((r,e)->completeMutation(player,submitting,e==null?messages.orderResult(r):messages.stockQueryFailed()));return;}
        if(draft instanceof StockGuiSession.CancelOrder c){trading.cancel(player.getUniqueId(),c.orderId()).whenComplete((r,e)->completeMutation(player,submitting,e==null?messages.orderResult(r):messages.stockQueryFailed()));return;}
        mutationsInFlight.remove(player.getUniqueId());
    }
    private void completeMutation(Player player, StockGuiSession session, Component outcome) { mutationsInFlight.remove(player.getUniqueId()); onMain(player,session,()->showResult(player,session,outcome)); }
    private void showResult(Player player, StockGuiSession submitting, Component outcome) { StockGuiSession result=submitting.next(StockGuiSession.Page.RESULT,0,submitting.stockCode(),null);if(!sessions.replace(player.getUniqueId(),submitting,result))return;Inventory inv=inventory(result,"提交结果");fill(inv);inv.setItem(22,items.action(Material.PAPER,"noop",outcome,List.of()));put(inv,29,Material.ARROW,"result:detail","返回详情","查看相关详情");put(inv,33,Material.COMPASS,"back:market","返回市场","查看市场行情");openInventory(player,inv); }
    private void put(Inventory inventory, int slot, Material material, String action, String name, String lore) {
        inventory.setItem(slot, items.action(material, action, Component.text(name), List.of(Component.text(lore))));
    }
    private void put(Inventory inventory, int slot, Material material, String action, String name, List<String> lore) {
        inventory.setItem(slot, items.action(material, action, Component.text(name), lore.stream().<Component>map(Component::text).toList()));
    }
    static String detailTitle(String companyName, String code) { return companyName + " · " + code; }
    static List<String> detailCardLabels(Money latest, Money change, long holding, Money turnover) {
        return List.of("最新 " + display(latest, 2), "涨跌 " + display(change, 2), "我的持仓 " + holding + " 股", "今日成交额 " + display(turnover, 2));
    }
    static List<String> detailActions() { return List.of("chart:intraday", "chart:daily", "detail:buy", "detail:sell", "help", "back:market"); }
    static List<String> orderBookLabels(SecondaryMarketQueryService.OrderBook book, int scale) {
        var labels = new java.util.ArrayList<String>();
        for (int i = 0; i < 5; i++) labels.add(i < book.asks().size() ? "卖" + (i + 1) + " " + display(book.asks().get(i).price(), scale) : "卖" + (i + 1) + " 暂无");
        for (int i = 0; i < 5; i++) labels.add(i < book.bids().size() ? "买" + (i + 1) + " " + display(book.bids().get(i).price(), scale) : "买" + (i + 1) + " 暂无");
        return List.copyOf(labels);
    }
    static List<Material> chartRaster(MarketChart chart, StockGuiSession.ChartMode mode) {
        var raster = new java.util.ArrayList<Material>();
        if (mode == StockGuiSession.ChartMode.DAILY) {
            for (var candle : chart.dailyCandles()) raster.add(candle.close().minorUnits() >= candle.open().minorUnits() ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE);
        } else {
            long prior = chart.intradayPoints().isEmpty() ? 0 : chart.intradayPoints().getFirst().close().minorUnits();
            for (var point : chart.intradayPoints()) { raster.add(point.close().minorUnits() >= prior ? Material.CYAN_STAINED_GLASS_PANE : Material.BLUE_STAINED_GLASS_PANE); prior = point.close().minorUnits(); }
        }
        while (raster.size() < 7) raster.add(Material.BLACK_STAINED_GLASS_PANE);
        return List.copyOf(raster.subList(0, 7));
    }
    private static DetailStats detailStats(List<PublicMarketRow> rows, PortfolioView portfolio, String code) {
        PublicMarketRow market = rows.stream().filter(row -> row.stockCode().equals(code)).findFirst().orElse(null);
        long holding = portfolio.holdings().stream().filter(row -> row.stockCode().equals(code)).mapToLong(row -> row.availableShares() + row.reservedShares()).sum();
        return market == null ? new DetailStats(Money.zero(), Money.zero(), Money.zero(), holding) : new DetailStats(market.latestPrice(), market.change(), market.turnover(), holding);
    }
    static List<String> chartLore(MarketChart chart, int scale) {
        var s=chart.sessionSummary(); var result=new java.util.ArrayList<String>();
        result.add("分时线 · " + chart.sessionDay());
        if (chart.intradayPoints().isEmpty()) result.add("走势 暂无成交");
        else { result.add("走势 " + sparkline(chart.intradayPoints())); for (var point : chart.intradayPoints()) result.add(point.label() + " " + display(point.close(), scale) + " · " + point.volumeShares() + "股"); }
        result.add("开 " + display(s.open(),scale) + "  高 " + display(s.high(),scale) + "  低 " + display(s.low(),scale) + "  现 " + display(s.close(),scale));
        result.add("成交量 " + s.volumeShares() + " 股"); result.add("日K线（最近 " + chart.dailyCandles().size() + " 日）");
        for (var day:chart.dailyCandles()) result.add(day.day().format(DateTimeFormatter.ofPattern("MM-dd")) + " 开" + display(day.open(),scale) + " 高" + display(day.high(),scale) + " 低" + display(day.low(),scale) + " 收" + display(day.close(),scale) + " 量" + day.volumeShares());
        return List.copyOf(result);
    }
    static List<String> chartLore(MarketChart chart, int scale, StockGuiSession.ChartMode mode) {
        Objects.requireNonNull(mode, "mode");
        var s = chart.sessionSummary(); var result = new java.util.ArrayList<String>();
        if (mode == StockGuiSession.ChartMode.INTRADAY) {
            result.add("分时线");
            result.add("交易日 " + chart.sessionDay());
            if (chart.intradayPoints().isEmpty()) result.add("走势 暂无成交");
            else { result.add("走势"); result.add(sparkline(chart.intradayPoints())); for (var point : chart.intradayPoints()) { result.add(point.label()); result.add(display(point.close(), scale) + " · " + point.volumeShares() + "股"); } }
            result.add("开 " + display(s.open(), scale) + "  高 " + display(s.high(), scale) + " 低 " + display(s.low(), scale) + " 现 " + display(s.close(), scale));
            result.add("成交量 " + s.volumeShares() + " 股");
        } else {
            result.add("日K线");
            for (var day : chart.dailyCandles()) {
                result.add(day.day().format(DateTimeFormatter.ofPattern("MM-dd")) + " 开" + display(day.open(), scale) + " 高" + display(day.high(), scale) + " 低" + display(day.low(), scale) + " 收" + display(day.close(), scale));
                result.add("量" + day.volumeShares());
            }
        }
        return List.copyOf(result);
    }
    private static String sparkline(List<MarketChart.IntradayPoint> points) { long low=points.stream().mapToLong(point -> point.close().minorUnits()).min().orElse(0); long high=points.stream().mapToLong(point -> point.close().minorUnits()).max().orElse(low); String levels="▁▂▃▄▅▆▇█"; StringBuilder line=new StringBuilder(); for(var point:points){int level=high==low?0:(int)Math.round((point.close().minorUnits()-low)*7.0/(high-low));line.append(levels.charAt(Math.max(0,Math.min(7,level))));}return line.toString(); }
    private static String display(Money value,int scale) { return BigDecimal.valueOf(value.minorUnits(),scale).setScale(scale).toPlainString(); }

    private record DetailStats(Money latestPrice, Money change, Money turnover, long holdingShares) {
        private static DetailStats empty() { return new DetailStats(Money.zero(), Money.zero(), Money.zero(), 0); }
    }

    private record Holder(StockGuiSession session) implements InventoryHolder {
        @Override public Inventory getInventory() { return null; }
    }
}
