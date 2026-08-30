package cn.blockeco.exchange.paper;

import cn.blockeco.exchange.application.CompanyQueryService;
import cn.blockeco.exchange.application.ShareIssuanceService;
import cn.blockeco.exchange.domain.governance.IssuanceProposalState;
import cn.blockeco.exchange.domain.governance.VoteChoice;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.ports.MainThreadExecutor;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
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
import org.bukkit.plugin.java.JavaPlugin;

/** Native inventory screens for issuance proposal, voting and securities-account subscription. */
public final class IssuanceGuiController implements Listener, CompanyGuiOpener {
    private final JavaPlugin plugin; private final ShareIssuanceService issuance; private final CompanyQueryService companies;
    private final MainThreadExecutor main; private final Executor worker; private final BooleanSupplier accepting; private final int scale;
    private final CompanyGuiOpener companyCenter; private final StockGuiOpener stockHome; private final StockGuiItemFactory items;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>(); private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    public IssuanceGuiController(JavaPlugin plugin, ShareIssuanceService issuance, CompanyQueryService companies,
                                 MainThreadExecutor main, Executor worker, BooleanSupplier accepting, int scale,
                                 CompanyGuiOpener companyCenter, StockGuiOpener stockHome) {
        this.plugin=Objects.requireNonNull(plugin,"plugin"); this.issuance=Objects.requireNonNull(issuance,"issuance"); this.companies=Objects.requireNonNull(companies,"companies");
        this.main=Objects.requireNonNull(main,"main"); this.worker=Objects.requireNonNull(worker,"worker"); this.accepting=Objects.requireNonNull(accepting,"accepting"); if(scale<0||scale>8) throw new IllegalArgumentException("scale"); this.scale=scale;
        this.companyCenter=companyCenter; this.stockHome=stockHome; this.items=new StockGuiItemFactory(plugin);
    }

    @Override public void open(Player player) { openFounder(player); }
    public void openFounder(Player player) { if (!ready(player)) return; openProposalForm(player); }
    /** External proposal-list queries may pass a server-derived view; clients never supply state or totals. */
    public void openProposal(Player player, ProposalView proposal) { if (!ready(player)) return; Session session=new Session(UUID.randomUUID(), proposal); sessions.put(player.getUniqueId(),session); render(player,session); }

    @EventHandler public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder) || !(event.getWhoClicked() instanceof Player player)) return;
        event.setCancelled(true); if (!current(player,holder.session) || event.getRawSlot()<0 || event.getRawSlot()>=event.getView().getTopInventory().getSize()) return;
        String action=items.action(event.getCurrentItem()); if(action==null) return;
        if ("back:company".equals(action)) { backCompany(player); return; }
        if ("back:stock".equals(action)) { if(stockHome!=null) stockHome.openHome(player); return; }
        if ("propose".equals(action)) { beginProposal(player, holder.session); return; }
        if (action.startsWith("vote:")) vote(player,holder.session,action.substring(5));
        else if ("subscribe".equals(action)) beginSubscription(player, holder.session);
    }
    @EventHandler public void onInventoryDrag(InventoryDragEvent event) { if(event.getInventory().getHolder() instanceof Holder) event.setCancelled(true); }
    @EventHandler public void onInventoryClose(InventoryCloseEvent event) { if(event.getInventory().getHolder() instanceof Holder holder && event.getPlayer() instanceof Player player) sessions.remove(player.getUniqueId(),holder.session); }

    private void openProposalForm(Player player) {
        Session session=new Session(UUID.randomUUID(),null); sessions.put(player.getUniqueId(),session);
        Inventory inventory=Bukkit.createInventory(new Holder(session),54,Component.text("BlockStock 增发与投票")); fill(inventory);
        put(inventory,22,Material.PAPER,"propose","发起增发提案","输入“新增股数,发行价”；将进入 12 小时公告期");
        put(inventory,49,Material.ARROW,"back:company","返回公司中心","返回上一级"); player.openInventory(inventory);
    }
    private void render(Player player, Session session) { Inventory inventory=Bukkit.createInventory(new Holder(session),54,Component.text("BlockStock 增发提案")); fill(inventory); for(Slot slot:proposalSlots(session.proposal,scale)) put(inventory,slot.slot,slot.material,slot.action,slot.name,slot.lore); player.openInventory(inventory); }
    private void vote(Player player, Session session, String choice) {
        VoteChoice vote; try { vote=VoteChoice.valueOf(choice); } catch(IllegalArgumentException ignored) { return; }
        if(!inFlight.add(player.getUniqueId())) return;
        Bukkit.getScheduler().runTask(plugin, () -> player.closeInventory());
        java.util.concurrent.CompletableFuture.runAsync(() -> issuance.vote(player.getUniqueId(),session.proposal.id,vote), worker).whenComplete((ok,error) -> main.submit(() -> { inFlight.remove(player.getUniqueId()); if(!player.isOnline()) return null; player.sendMessage(Component.text(error==null?"投票已记录。":"投票未完成，请刷新后重试。")); if(error==null) render(player,session); return null; }));
    }
    private void beginProposal(Player player, Session session) { TextInputGui.open(plugin,player,"新增股数,发行价",entered -> createProposal(player,session,entered)); }
    private void createProposal(Player player, Session session, String entered) {
        if(!inFlight.add(player.getUniqueId())) return; String[] values=entered.split(",",2);
        if(values.length!=2) { inFlight.remove(player.getUniqueId()); player.sendMessage(Component.text("请输入“新增股数,发行价”。")); return; }
        try { long shares=Long.parseLong(values[0].trim()); Money price=Money.fromMajor(new BigDecimal(values[1].trim()),scale);
            companies.findByFounder(player.getUniqueId()).thenCompose(company -> java.util.concurrent.CompletableFuture.supplyAsync(() -> issuance.propose(player.getUniqueId(),company.orElseThrow().id(),shares,price),worker)).whenComplete((proposal,error) -> main.submit(() -> { inFlight.remove(player.getUniqueId()); if(!player.isOnline()) return null; player.sendMessage(Component.text(error==null?"增发提案已发布，现处于公告期。":"增发提案未创建，请检查公司上市状态和输入金额。")); if(error==null) openFounder(player); return null; }));
        } catch(RuntimeException invalid) { inFlight.remove(player.getUniqueId()); player.sendMessage(Component.text("新增股数或发行价格式不正确。")); }
    }
    private void beginSubscription(Player player, Session session) {
        if (session.proposal == null || session.proposal.state != IssuanceProposalState.SUBSCRIBING) return;
        TextInputGui.open(plugin, player, "输入认购股数", entered -> subscribe(player, session, entered));
    }
    private void subscribe(Player player, Session session, String entered) {
        if(!inFlight.add(player.getUniqueId())) return;
        try { long shares=Long.parseLong(entered); if(shares<=0) throw new IllegalArgumentException("shares");
            java.util.concurrent.CompletableFuture.runAsync(() -> issuance.subscribe(player.getUniqueId(),session.proposal.id,shares,UUID.randomUUID().toString()),worker)
                    .whenComplete((ok,error) -> main.submit(() -> { inFlight.remove(player.getUniqueId()); if(!player.isOnline()) return null; player.sendMessage(Component.text(error==null?"认购已提交，资金已从证券账户预留。":"认购未完成，请检查证券账户余额后重试。")); if(error==null) render(player,session); return null; }));
        } catch(RuntimeException invalid) { inFlight.remove(player.getUniqueId()); player.sendMessage(Component.text("认购股数必须为正整数。")); }
    }
    private void backCompany(Player player) { sessions.remove(player.getUniqueId()); if(companyCenter!=null) companyCenter.open(player); else player.closeInventory(); }
    private boolean ready(Player player) { if(accepting.getAsBoolean()) return true; player.sendMessage(Component.text("BlockStock 正在初始化，请稍后再试。")); return false; }
    private boolean current(Player player, Session session) { return sessions.get(player.getUniqueId())==session; }
    private void fill(Inventory inventory) { for(int slot=0;slot<inventory.getSize();slot++) inventory.setItem(slot,items.filler()); }
    private void put(Inventory inventory,int slot,Material material,String action,String name,String lore) { inventory.setItem(slot,items.action(material,action,Component.text(name),List.of(Component.text(lore)))); }

    static List<Slot> proposalSlots(ProposalView proposal, int scale) {
        Objects.requireNonNull(proposal,"proposal"); String price=BigDecimal.valueOf(proposal.issuePrice.minorUnits(),scale).setScale(scale).toPlainString(); var slots=new ArrayList<Slot>();
        slots.add(new Slot(13,Material.PAPER,"noop",proposal.companyName+" 增发提案","新增 "+proposal.newShares+" 股；发行价 "+price));
        slots.add(new Slot(22,Material.BOOK,"noop","投票登记信息","登记日股份 "+proposal.recordShares+"；赞成 "+proposal.yesShares+"；反对 "+proposal.noShares+"；弃权 "+proposal.abstainShares));
        if(proposal.state==IssuanceProposalState.VOTING) { slots.add(new Slot(29,Material.LIME_WOOL,"vote:YES","赞成","按登记日股份计票")); slots.add(new Slot(31,Material.RED_WOOL,"vote:NO","反对","按登记日股份计票")); slots.add(new Slot(33,Material.GRAY_WOOL,"vote:ABSTAIN","弃权","按登记日股份计票")); }
        if(proposal.state==IssuanceProposalState.SUBSCRIBING) slots.add(new Slot(31,Material.EMERALD,"subscribe","认购新股","确认后从证券账户预留认购资金"));
        slots.add(new Slot(49,Material.ARROW,"back:company","返回公司中心","返回上一级")); return List.copyOf(slots);
    }

    record ProposalView(UUID id,String companyName,long newShares,Money issuePrice,IssuanceProposalState state,long recordShares,long effectiveShares,long yesShares,long noShares,long abstainShares) { ProposalView { Objects.requireNonNull(id,"id"); Objects.requireNonNull(companyName,"companyName"); Objects.requireNonNull(issuePrice,"issuePrice"); Objects.requireNonNull(state,"state"); } }
    record Slot(int slot,Material material,String action,String name,String lore) { }
    private record Session(UUID id,ProposalView proposal) { }
    private record Holder(Session session) implements InventoryHolder { @Override public Inventory getInventory(){return null;} }
}
