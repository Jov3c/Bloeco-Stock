package cn.blockeco.exchange.paper;

import java.util.List;
import java.util.Objects;
import cn.blockeco.exchange.ports.CompanyOperationsRepository;
import cn.blockeco.exchange.application.CompanyQueryService;
import cn.blockeco.exchange.ports.AppClock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.Executor;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.java.JavaPlugin;

/** Read-only native inventory page.  Revenue remains event-source driven; it never invents native income. */
public final class CompanyFinanceGui implements CompanyGuiOpener, Listener {
    private final JavaPlugin plugin; private final CompanyGuiOpener companyCenter; private final StockGuiItemFactory items; private final CompanyQueryService companies; private final CompanyOperationsRepository finance; private final AppClock clock; private final ZoneId zone; private final Executor executor;
    private final ConcurrentHashMap<UUID, FinanceView> views = new ConcurrentHashMap<>();
    public CompanyFinanceGui(JavaPlugin plugin, CompanyGuiOpener companyCenter) { this(plugin,companyCenter,null,null,null,null,null); }
    public CompanyFinanceGui(JavaPlugin plugin, CompanyGuiOpener companyCenter, CompanyQueryService companies, CompanyOperationsRepository finance, AppClock clock, ZoneId zone, Executor executor) { this.plugin=Objects.requireNonNull(plugin);this.companyCenter=Objects.requireNonNull(companyCenter);this.items=new StockGuiItemFactory(plugin);this.companies=companies;this.finance=finance;this.clock=clock;this.zone=zone;this.executor=executor; }
    @Override public void open(Player player) {
        if (companies != null) { openLoaded(player); return; }
        openInventory(player, null);
    }
    private void openLoaded(Player player) {
        companies.findByFounder(player.getUniqueId()).thenCompose(company -> company.map(value -> java.util.concurrent.CompletableFuture.supplyAsync(() -> { var dashboard=finance.financeDashboard(value.id(), clock.now(), zone); return new FinanceView(dashboard.snapshot(), dashboard.nextDividendAt(), dashboard.recentReports()); }, executor)).orElseGet(() -> java.util.concurrent.CompletableFuture.failedFuture(new IllegalArgumentException("company not found")))).whenComplete((view,error)-> Bukkit.getScheduler().runTask(plugin,()->{
            if (!player.isOnline()) return;
            if(error!=null) { player.sendMessage(Component.text("财务数据将在公司上市后提供；上市后开始分红。")); companyCenter.open(player); return; }
            openInventory(player,view);
        }));
    }
    private void openInventory(Player player, FinanceView view) {
        if(view!=null) views.put(player.getUniqueId(),view);
        Inventory inventory=Bukkit.createInventory(null,54,Component.text("Bloeco-Stock 财务与分红"));
        for(int i=0;i<inventory.getSize();i++) inventory.setItem(i,items.filler());
        String companyLore=view==null?"可用公司资金、留存收益与累计亏损":mainPageText(view);
        put(inventory,11,Material.GOLD_INGOT,"公司账户",companyLore);
        put(inventory,13,Material.PAPER,"本月经营",view==null?"收入、成本与净利润（按服务器时区）":historyPageText(view));
        put(inventory,15,Material.EMERALD,"证券账户","股东分红进入证券账户，不进入个人钱包");
        put(inventory,29,Material.CHEST,"个人钱包","个人钱包与公司账户、证券账户独立");
        put(inventory,31,Material.YELLOW_WOOL,"原生资产","已绑定，尚未接入自动收益来源");
        if(view!=null) inventory.setItem(33,items.action(Material.BOOK,"history",Component.text("最近 6 份月报"),List.of(Component.text("点击查看月度收入、成本与净利润"))));
        inventory.setItem(49,items.action(Material.ARROW,"back",Component.text("返回公司中心"),List.of(Component.text("返回上一级"))));
        player.openInventory(inventory);
    }
    private void put(Inventory i,int slot,Material material,String title,String lore){i.setItem(slot,items.action(material,"noop",Component.text(title),List.of(Component.text(lore))));}
    @EventHandler public void click(InventoryClickEvent event) { boolean main=Component.text("Bloeco-Stock 财务与分红").equals(event.getView().title()), history=Component.text("Bloeco-Stock 财报历史").equals(event.getView().title()); if (!main&&!history) return; event.setCancelled(true); if (!(event.getWhoClicked() instanceof Player player)) return; String action=items.action(event.getCurrentItem()); if ("back".equals(action)) companyCenter.open(player); else if("history".equals(action)){FinanceView view=views.get(player.getUniqueId());if(view!=null)openHistory(player,view);} else if("back:finance".equals(action))openInventory(player,views.get(player.getUniqueId())); }
    @EventHandler public void drag(InventoryDragEvent event) { if (Component.text("Bloeco-Stock 财务与分红").equals(event.getView().title())||Component.text("Bloeco-Stock 财报历史").equals(event.getView().title())) event.setCancelled(true); }
    private void openHistory(Player player, FinanceView view) { Inventory inventory=Bukkit.createInventory(null,54,Component.text("Bloeco-Stock 财报历史"));for(int i=0;i<54;i++)inventory.setItem(i,items.filler());int slot=0;for(var report:view.reports().stream().limit(6).toList()) put(inventory,slot++,Material.PAPER,report.periodStart().toString(),"收入 "+report.income()+"｜成本 "+report.expense()+"｜净利润 "+report.netProfit());inventory.setItem(49,items.action(Material.ARROW,"back:finance",Component.text("返回财务与分红"),List.of(Component.text("返回上一页"))));player.openInventory(inventory); }
    public static String mainPageText() { return "公司账户｜证券账户｜个人钱包｜返回公司中心"; }
    public static String mainPageText(FinanceView view) { var s=view.snapshot();return "公司账户｜可用公司资金："+s.cash()+"｜留存收益："+s.retainedEarnings()+"｜累计亏损："+s.accumulatedLoss()+"｜本月收入："+s.income()+"｜本月成本："+s.expense()+"｜本月净利润："+(s.income()-s.expense())+"｜下次分红："+view.nextDividendAt()+"｜证券账户｜个人钱包｜返回公司中心"; }
    public static String historyPageText(FinanceView view) { return "最近 6 份月报｜"+view.reports().stream().limit(6).map(r->r.periodStart()+" 收入"+r.income()+" 成本"+r.expense()+" 净利"+r.netProfit()).collect(java.util.stream.Collectors.joining("｜")); }
    public static String nativeBindingNotice() { return "已绑定，尚未接入自动收益来源"; }
    public record FinanceView(CompanyOperationsRepository.FinancialSnapshot snapshot, Instant nextDividendAt, List<CompanyOperationsRepository.MonthlyReport> reports) { public FinanceView { Objects.requireNonNull(snapshot);Objects.requireNonNull(nextDividendAt);reports=List.copyOf(reports); } }
}
