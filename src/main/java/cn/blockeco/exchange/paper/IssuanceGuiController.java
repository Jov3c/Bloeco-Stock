package cn.blockeco.exchange.paper;

import cn.blockeco.exchange.application.CompanyQueryService;
import cn.blockeco.exchange.application.ShareIssuanceService;
import cn.blockeco.exchange.domain.company.Company;
import cn.blockeco.exchange.domain.governance.IssuanceProposalState;
import cn.blockeco.exchange.domain.governance.VoteChoice;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.ports.MainThreadExecutor;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
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

/** Public, vanilla-client governance screens.  All mutations remain server-side. */
public final class IssuanceGuiController implements Listener, CompanyGuiOpener {
    private static final Duration ANNOUNCEMENT = Duration.ofHours(12), VOTING = Duration.ofDays(2), SUBSCRIPTION = Duration.ofDays(2);
    private final JavaPlugin plugin; private final ShareIssuanceService issuance; private final CompanyQueryService companies;
    private final MainThreadExecutor main; private final Executor worker; private final BooleanSupplier accepting; private final int scale;
    private final CompanyGuiOpener companyCenter; private final StockGuiOpener stockHome; private final StockGuiItemFactory items;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>(); private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    private final Set<UUID> replacing = ConcurrentHashMap.newKeySet();

    public IssuanceGuiController(JavaPlugin plugin, ShareIssuanceService issuance, CompanyQueryService companies, MainThreadExecutor main,
                                 Executor worker, BooleanSupplier accepting, int scale, CompanyGuiOpener companyCenter, StockGuiOpener stockHome) {
        this.plugin=Objects.requireNonNull(plugin); this.issuance=Objects.requireNonNull(issuance); this.companies=Objects.requireNonNull(companies);
        this.main=Objects.requireNonNull(main); this.worker=Objects.requireNonNull(worker); this.accepting=Objects.requireNonNull(accepting);
        if(scale<0||scale>8) throw new IllegalArgumentException("scale"); this.scale=scale; this.companyCenter=companyCenter; this.stockHome=stockHome; this.items=new StockGuiItemFactory(plugin);
    }

    /** Company centre opens the founder page; the exchange opens the public list. */
    @Override public void open(Player player) { openFounder(player); }
    public void openPublic(Player player) { openPublic(player, 0); }
    public void openFounder(Player player) {
        if (!ready(player)) return;
        Session session = putSession(player, Page.FOUNDER, null, null); Inventory inventory=inventory(session,"BlockStock 增发管理"); fill(inventory);
        put(inventory,11,Material.BOOK,"noop","增发流程","公告 12 小时 → 股东投票 2 天 → 新股认购 2 天");
        put(inventory,22,Material.PAPER,"propose","发起增发提案","输入“新增股数,发行价”，之后需等待股东投票");
        put(inventory,31,Material.WRITABLE_BOOK,"public","查看全部增发提案","所有玩家都可查看、投票和认购");
        put(inventory,49,Material.ARROW,"back:company","返回公司中心","返回上一级"); openInventory(player,inventory);
    }
    private void openPublic(Player player,int page) {
        if(!ready(player)) return;
        Session session=putSession(player,Page.PUBLIC,null,null,page); loading(player,session,"正在加载全部增发提案…");
        CompletableFuture.supplyAsync(issuance::listOpenProposals,worker).whenComplete((views,error)->onMain(player,session,()->{
            if(error!=null){ player.sendMessage(Component.text("增发提案加载失败，请稍后重试。")); backStock(player); return; }
            int start=Math.min(page*45,views.size()), end=Math.min(start+45,views.size()); Session shown=putSession(player,Page.PUBLIC,null,null,page);
            Inventory inv=inventory(shown,"BlockStock 增发市场 " +(page+1)); fill(inv);
            if(views.isEmpty()) put(inv,22,Material.BARRIER,"noop","暂无进行中的增发","公司提案会在公告期显示于此");
            for(int i=start;i<end;i++){ ProposalView view=ProposalView.from(views.get(i)); put(inv,i-start,Material.PAPER,"proposal:"+view.id,"【"+status(view.state)+"】"+view.companyName,"新增 "+view.newShares+" 股｜稀释 "+dilution(view)+"｜截止 "+deadline(view)); }
            put(inv,45,Material.ARROW,"public:prev","上一页","查看上一页"); put(inv,49,Material.COMPASS,"back:stock","交易所主页","返回主菜单");
            if(end<views.size()) put(inv,53,Material.ARROW,"public:next","下一页","查看下一页"); openInventory(player,inv);
        }));
    }
    private void openProposal(Player player,UUID proposalId) {
        Session current=sessions.get(player.getUniqueId()); if(current==null)return; loading(player,current,"正在加载增发详情…");
        CompletableFuture.supplyAsync(issuance::listOpenProposals,worker).whenComplete((views,error)->onMain(player,current,()->{
            ProposalView view=error==null?views.stream().map(ProposalView::from).filter(item->item.id.equals(proposalId)).findFirst().orElse(null):null;
            if(view==null){player.sendMessage(Component.text("该提案已结束或不存在。"));openPublic(player,0);return;} renderProposal(player,putSession(player,Page.DETAIL,view,null));
        }));
    }
    private void renderProposal(Player player,Session session) { Inventory inv=inventory(session,"BlockStock 增发详情");fill(inv);for(Slot slot:proposalSlots(session.proposal,scale))put(inv,slot.slot,slot.material,slot.action,slot.name,slot.lore);put(inv,45,Material.ARROW,"public","返回提案列表","查看所有公司提案");put(inv,49,Material.COMPASS,"back:stock","交易所主页","返回主菜单");openInventory(player,inv); }

    @EventHandler public void onInventoryClick(InventoryClickEvent event) {
        if(!(event.getView().getTopInventory().getHolder() instanceof Holder holder)||!(event.getWhoClicked() instanceof Player player))return;
        event.setCancelled(true); if(!matches(player,holder.session)||event.getRawSlot()<0||event.getRawSlot()>=event.getView().getTopInventory().getSize())return;
        String action=items.action(event.getCurrentItem());if(action==null)return;
        switch(action){
            case "back:company" -> backCompany(player); case "back:stock" -> backStock(player); case "public" -> openPublic(player,0);
            case "public:prev" -> openPublic(player,previousPage(holder.session.pageIndex)); case "public:next" -> openPublic(player,nextPage(holder.session.pageIndex)); case "propose" -> beginProposal(player,holder.session);
            case "confirm" -> confirm(player,holder.session); case "cancel" -> cancelConfirmation(player,holder.session);
            default -> route(player,holder.session,action);
        }
    }
    @EventHandler public void onInventoryDrag(InventoryDragEvent event){if(event.getInventory().getHolder() instanceof Holder)event.setCancelled(true);}
    @EventHandler public void onInventoryClose(InventoryCloseEvent event){if(event.getInventory().getHolder() instanceof Holder holder&&event.getPlayer() instanceof Player player&&!replacing.contains(player.getUniqueId())&&matches(player,holder.session))sessions.remove(player.getUniqueId(),holder.session);}
    private void route(Player player,Session session,String action){
        if(action.startsWith("proposal:")){try{openProposal(player,UUID.fromString(action.substring(9)));}catch(IllegalArgumentException ignored){}}
        else if(action.startsWith("vote:")){try{showConfirmation(player,session.proposal,new VoteDraft(VoteChoice.valueOf(action.substring(5))));}catch(IllegalArgumentException ignored){}}
        else if("subscribe".equals(action)) beginSubscription(player,session);
    }
    private void beginProposal(Player player,Session ignored){beginInput(player,()->TextInputGui.open(plugin,player,"新增股数,发行价",entered->createProposal(player,entered)));}
    private void createProposal(Player player,String entered){
        String[] values=entered.split(",",2); if(values.length!=2){player.sendMessage(Component.text("请输入“新增股数,发行价”。"));return;}
        try{long shares=Long.parseLong(values[0].trim()); Money price=Money.fromMajor(new BigDecimal(values[1].trim()),scale); showConfirmation(player,null,new ProposeDraft(shares,price));}catch(RuntimeException invalid){player.sendMessage(Component.text("新增股数或发行价格式不正确。"));}
    }
    private void beginSubscription(Player player,Session session){if(session.proposal==null||session.proposal.state!=IssuanceProposalState.SUBSCRIBING)return;beginInput(player,()->TextInputGui.open(plugin,player,"输入认购股数",entered->{try{long shares=Long.parseLong(entered);if(shares<=0)throw new NumberFormatException();showConfirmation(player,session.proposal,new SubscribeDraft(shares));}catch(NumberFormatException invalid){player.sendMessage(Component.text("认购股数必须为正整数。"));}}));}
    private void showConfirmation(Player player,ProposalView proposal,Draft draft){Session session=putSession(player,Page.CONFIRM,proposal,draft);Inventory inv=inventory(session,"确认增发操作");fill(inv);String text=switch(draft){case VoteDraft vote -> "确认投票："+voteLabel(vote.choice)+"。按登记日持股计票。";case SubscribeDraft subscribe -> "确认认购 "+subscribe.shares+" 股，预计冻结 "+amount(Money.ofMinor(Math.multiplyExact(proposal.issuePrice.minorUnits(),subscribe.shares)))+"。";case ProposeDraft propose -> "确认发起增发：新增 "+propose.shares+" 股，发行价 "+amount(propose.price)+"。";};put(inv,22,Material.BOOK,"noop","请确认",text);put(inv,29,Material.LIME_WOOL,"confirm","确认提交","提交后不可通过双击重复执行");put(inv,33,Material.RED_WOOL,"cancel","取消","返回且不执行任何操作");openInventory(player,inv);}
    private void confirm(Player player,Session session){if(!ready(player)||session.draft==null||!inFlight.add(player.getUniqueId()))return;Draft draft=session.draft;
        CompletionStage<?> operation = draft instanceof ProposeDraft proposal
                ? composeFounderProposal(companies.findByFounder(player.getUniqueId()), company -> issuance.propose(player.getUniqueId(),company.id(),proposal.shares,proposal.price), worker)
                : CompletableFuture.runAsync(()->{if(draft instanceof VoteDraft vote)issuance.vote(player.getUniqueId(),session.proposal.id,vote.choice);else {SubscribeDraft subscribe=(SubscribeDraft)draft;issuance.subscribe(player.getUniqueId(),session.proposal.id,subscribe.shares,UUID.randomUUID().toString());}},worker);
        operation.whenComplete((ok,error)->main.submit(()->{inFlight.remove(player.getUniqueId());if(!player.isOnline()||!matches(player,session))return null;player.sendMessage(Component.text(error==null?success(draft):"操作未完成，请刷新后重试。"));if(draft instanceof ProposeDraft)openFounder(player);else openProposal(player,session.proposal.id);return null;})); }
    static <T> CompletionStage<T> composeFounderProposal(CompletionStage<Optional<Company>> lookup, Function<Company,T> proposal, Executor worker) { return lookup.thenCompose(found -> found.<CompletionStage<T>>map(company -> CompletableFuture.supplyAsync(() -> proposal.apply(company), worker)).orElseGet(() -> CompletableFuture.failedFuture(new IllegalStateException("company missing")))); }
    private static String voteLabel(VoteChoice choice){return switch(choice){case YES->"赞成";case NO->"反对";case ABSTAIN->"弃权";};}
    private String success(Draft draft){return draft instanceof VoteDraft?"投票已记录。":draft instanceof SubscribeDraft?"认购已提交，资金已从证券账户预留。":"增发提案已发布，现处于公告期。";}
    private void cancelConfirmation(Player player,Session session){if(session.draft instanceof ProposeDraft)openFounder(player);else if(session.proposal!=null)renderProposal(player,putSession(player,Page.DETAIL,session.proposal,null));else openPublic(player,0);}
    private Session putSession(Player player,Page page,ProposalView proposal,Draft draft){return putSession(player,page,proposal,draft,0);}
    private Session putSession(Player player,Page page,ProposalView proposal,Draft draft,int pageIndex){Session session=new Session(UUID.randomUUID(),player.getUniqueId(),page,pageIndex,proposal,draft);sessions.put(player.getUniqueId(),session);return session;}
    private boolean matches(Player player,Session session){return session.owner.equals(player.getUniqueId())&&session.equals(sessions.get(player.getUniqueId()));}
    private void onMain(Player player,Session expected,Runnable action){main.submit(()->{if(player.isOnline()&&accepting.getAsBoolean()&&matches(player,expected))action.run();return null;});}
    private boolean ready(Player player){if(accepting.getAsBoolean())return true;player.sendMessage(Component.text("BlockStock 正在初始化，请稍后再试。"));return false;}
    private void backCompany(Player player){sessions.remove(player.getUniqueId());if(companyCenter!=null)companyCenter.open(player);else player.closeInventory();} private void backStock(Player player){sessions.remove(player.getUniqueId());if(stockHome!=null)stockHome.openHome(player);else player.closeInventory();}
    private Inventory inventory(Session session,String title){return Bukkit.createInventory(new Holder(session),54,Component.text(title));} private void loading(Player player,Session session,String title){Inventory inv=inventory(session,"BlockStock 增发市场");fill(inv);put(inv,22,Material.CLOCK,"noop",title,"请稍候");openInventory(player,inv);} private void openInventory(Player player,Inventory inv){GuiTransitions.defer(action->Bukkit.getScheduler().runTask(plugin,action),()->{if(!player.isOnline())return;replacing.add(player.getUniqueId());try{player.openInventory(inv);}finally{replacing.remove(player.getUniqueId());}});} private void beginInput(Player player,Runnable open){replacing.add(player.getUniqueId());open.run();Bukkit.getScheduler().runTask(plugin,()->replacing.remove(player.getUniqueId()));} private void fill(Inventory inv){for(int slot=0;slot<inv.getSize();slot++)inv.setItem(slot,items.filler());} private void put(Inventory inv,int slot,Material material,String action,String name,String lore){inv.setItem(slot,items.action(material,action,Component.text(name),List.of(Component.text(lore))));}
    static List<Slot> proposalSlots(ProposalView proposal,int scale){Objects.requireNonNull(proposal);List<Slot> slots=new ArrayList<>();slots.add(new Slot(13,Material.PAPER,"noop",proposal.companyName+" 增发提案","状态："+status(proposal.state)+"｜截止 "+deadline(proposal)));slots.add(new Slot(22,Material.BOOK,"noop","发行与稀释","新增 "+proposal.newShares+" 股；发行价 "+amount(proposal.issuePrice,scale)+"；稀释 "+dilution(proposal)));slots.add(new Slot(23,Material.CLOCK,"noop","投票登记信息","登记日股份 "+proposal.recordShares+"；赞成 "+proposal.yesShares+"；反对 "+proposal.noShares+"；弃权 "+proposal.abstainShares));if(proposal.state==IssuanceProposalState.VOTING){slots.add(new Slot(29,Material.LIME_WOOL,"vote:YES","赞成","点击后进入确认页"));slots.add(new Slot(31,Material.RED_WOOL,"vote:NO","反对","点击后进入确认页"));slots.add(new Slot(33,Material.GRAY_WOOL,"vote:ABSTAIN","弃权","点击后进入确认页"));}if(proposal.state==IssuanceProposalState.SUBSCRIBING)slots.add(new Slot(31,Material.EMERALD,"subscribe","认购新股","输入股数后进入确认页，资金由证券账户预留"));return List.copyOf(slots);}
    static String status(IssuanceProposalState state){return switch(state){case ANNOUNCED->"公告期";case VOTING->"投票中";case APPROVED->"投票通过，待开放认购";case SUBSCRIBING->"认购中";default->state.name();};} static String deadline(ProposalView view){Instant time=switch(view.state){case ANNOUNCED->view.announcedAt.plus(ANNOUNCEMENT);case VOTING->view.announcedAt.plus(ANNOUNCEMENT).plus(VOTING);case APPROVED, SUBSCRIBING->view.announcedAt.plus(ANNOUNCEMENT).plus(VOTING).plus(SUBSCRIPTION);default->view.announcedAt;};return DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai")).format(time)+"（服务器时间）";} static String dilution(ProposalView view){long total=Math.addExact(view.totalShares,view.newShares);return total==0?"0.0%":BigDecimal.valueOf(view.newShares).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(total),1,java.math.RoundingMode.HALF_UP).toPlainString()+"%";} static String amount(Money money,int scale){return money.toMajor(scale).setScale(scale).toPlainString();} private String amount(Money money){return amount(money,scale);}
    record ProposalView(UUID id,String companyName,long newShares,Money issuePrice,IssuanceProposalState state,Instant announcedAt,long totalShares,long recordShares,long effectiveShares,long yesShares,long noShares,long abstainShares){static ProposalView from(ShareIssuanceService.ProposalView source){return new ProposalView(source.id(),source.companyName(),source.newShares(),source.issuePrice(),source.state(),source.announcedAt(),source.totalShares(),source.recordShares(),source.effectiveShares(),source.yesShares(),source.noShares(),source.abstainShares());}}
    static int previousPage(int page){return Math.max(0,page-1);} static int nextPage(int page){return Math.addExact(page,1);}
    record Slot(int slot,Material material,String action,String name,String lore){} private enum Page{PUBLIC,DETAIL,FOUNDER,CONFIRM} private sealed interface Draft permits VoteDraft,SubscribeDraft,ProposeDraft{} private record VoteDraft(VoteChoice choice)implements Draft{} private record SubscribeDraft(long shares)implements Draft{} private record ProposeDraft(long shares,Money price)implements Draft{} private record Session(UUID id,UUID owner,Page page,int pageIndex,ProposalView proposal,Draft draft){} private record Holder(Session session)implements InventoryHolder{@Override public Inventory getInventory(){return null;}}
}
