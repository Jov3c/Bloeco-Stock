package cn.blockeco.exchange.paper;

import cn.blockeco.exchange.application.PrimaryOfferingService;
import cn.blockeco.exchange.application.PublicStockQueryService;
import cn.blockeco.exchange.application.SecondaryMarketQueryService;
import cn.blockeco.exchange.application.SecondaryMarketService;
import cn.blockeco.exchange.application.SecuritiesCashService;
import cn.blockeco.exchange.application.OrderPlacementResult;
import cn.blockeco.exchange.domain.money.Money;
import java.math.BigDecimal;
import cn.blockeco.exchange.application.SubscriptionResult;
import cn.blockeco.exchange.domain.finance.PublicOfferingView;
import cn.blockeco.exchange.ports.MainThreadExecutor;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Public read-only market command; subscription delegates to the existing durable IPO saga. */
public final class StockCommand implements CommandExecutor, CommandAcceptanceGate {
    private final PublicStockQueryService queries; private final PrimaryOfferingService offerings; private final MainThreadExecutor mainThread; private final BooleanSupplier accepting; private final Messages messages;
    private final SecuritiesCashService cash; private final SecondaryMarketService trading; private final SecondaryMarketQueryService secondaryQueries; private final int currencyScale; private final BooleanSupplier mutationsOpen;
    private final StockGuiOpener gui;
    private final Set<UUID> subscriptionsInFlight = ConcurrentHashMap.newKeySet();
    private volatile boolean acceptingFlag;
    public StockCommand(PublicStockQueryService queries, PrimaryOfferingService offerings, MainThreadExecutor mainThread, BooleanSupplier accepting, Messages messages) {
        this(queries,offerings,null,null,null,mainThread,accepting,accepting,messages,2,null);
    }
    public StockCommand(PublicStockQueryService queries, PrimaryOfferingService offerings, SecuritiesCashService cash, SecondaryMarketService trading, SecondaryMarketQueryService secondaryQueries, MainThreadExecutor mainThread, BooleanSupplier accepting, Messages messages, int currencyScale) {
        this(queries, offerings, cash, trading, secondaryQueries, mainThread, accepting, accepting, messages, currencyScale, null);
    }
    public StockCommand(PublicStockQueryService queries, PrimaryOfferingService offerings, SecuritiesCashService cash, SecondaryMarketService trading, SecondaryMarketQueryService secondaryQueries, MainThreadExecutor mainThread, BooleanSupplier accepting, BooleanSupplier mutationsOpen, Messages messages, int currencyScale) {
        this(queries, offerings, cash, trading, secondaryQueries, mainThread, accepting, mutationsOpen, messages, currencyScale, null);
    }
    public StockCommand(PublicStockQueryService queries, PrimaryOfferingService offerings, SecuritiesCashService cash, SecondaryMarketService trading, SecondaryMarketQueryService secondaryQueries, MainThreadExecutor mainThread, BooleanSupplier accepting, BooleanSupplier mutationsOpen, Messages messages, int currencyScale, StockGuiOpener gui) {
        this.queries=queries; this.offerings=offerings; this.cash=cash; this.trading=trading; this.secondaryQueries=secondaryQueries; this.mainThread=mainThread; this.accepting=accepting; this.mutationsOpen=mutationsOpen; this.messages=messages; this.gui=gui; if(currencyScale<0||currencyScale>8)throw new IllegalArgumentException("currencyScale"); this.currencyScale=currencyScale;
    }
    @Override public void setAccepting(boolean accepting) { acceptingFlag=accepting; }
    private boolean ready() { return acceptingFlag && accepting.getAsBoolean(); }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || "gui".equalsIgnoreCase(args[0])) { if (!(sender instanceof Player player)) { sender.sendMessage(messages.playersOnly()); return true; } if (!ready()) { player.sendMessage(messages.initializing()); return true; } if (gui == null) { player.sendMessage(messages.marketUnavailable()); return true; } gui.openHome(player); return true; }
        if ("help".equalsIgnoreCase(args[0])) { messages.stockHelp(sender).forEach(sender::sendMessage); return true; }
        if (!ready()) { sender.sendMessage(messages.initializing()); return true; }
        try { return switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
            case "market" -> market(sender, args);
            case "ipo" -> ipo(sender, args);
            case "info" -> info(sender, args);
            case "announcements" -> announcements(sender, args);
            case "subscribe" -> subscribe(sender, args);
            case "cash" -> cash(sender,args);
            case "deposit" -> transfer(sender,args,true);
            case "withdraw" -> transfer(sender,args,false);
            case "buy" -> order(sender,args,true);
            case "sell" -> order(sender,args,false);
            case "cancel" -> cancel(sender,args);
            case "portfolio" -> portfolio(sender,args);
            case "orders" -> history(sender,args,true);
            case "trades" -> history(sender,args,false);
            case "book" -> book(sender,args);
            default -> { sender.sendMessage(messages.usageStock()); yield true; }
        }; } catch (RuntimeException failure) { sender.sendMessage(messages.stockQueryFailed()); return true; }
    }
    private boolean market(CommandSender sender, String[] args) {
        if (args.length != 1) { sender.sendMessage(messages.usageStock()); return true; }
        replies(sender, queries.market(), rows -> rows.isEmpty() ? List.of(messages.marketEmpty()) : rows.stream().map(messages::marketRow).toList(), messages.stockQueryFailed()); return true;
    }
    private boolean ipo(CommandSender sender, String[] args) {
        int limit = limit(args, 10); if (limit < 0) { sender.sendMessage(messages.usageStockIpo()); return true; }
        replies(sender, queries.ipo(limit), rows -> rows.isEmpty() ? List.of(messages.ipoEmpty()) : rows.stream().map(messages::stockIpoRow).toList(), messages.stockQueryFailed()); return true;
    }
    private boolean info(CommandSender sender, String[] args) {
        String key = joined(args, 1); if (key == null) { sender.sendMessage(messages.usageStockInfo()); return true; }
        replies(sender, queries.info(key), result -> List.of(result.map(messages::stockInfo).orElse(messages.stockNotFound())), messages.stockQueryFailed()); return true;
    }
    private boolean announcements(CommandSender sender, String[] args) {
        if (args.length < 2) { sender.sendMessage(messages.usageStockAnnouncements()); return true; }
        int last = args.length - 1; int limit = 10;
        if (args.length >= 3 && numeric(args[last])) { try { limit = checkedLimit(args[last]); } catch (NumberFormatException e) { sender.sendMessage(messages.usageStockAnnouncements()); return true; } last--; }
        String key = String.join(" ", Arrays.copyOfRange(args, 1, last + 1)).trim(); if (key.isEmpty()) { sender.sendMessage(messages.usageStockAnnouncements()); return true; }
        int queryLimit = limit;
        replies(sender, queries.announcements(key, queryLimit), rows -> rows.isEmpty() ? List.of(messages.announcementsEmpty()) : rows.stream().map(messages::announcement).toList(), messages.stockQueryFailed()); return true;
    }
    private boolean subscribe(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage(messages.playersOnly()); return true; }
        if (!player.hasPermission("blockeco.stock.subscribe")) { player.sendMessage(messages.noPermission()); return true; }
        if (args.length < 3) { player.sendMessage(messages.usageStockSubscribe()); return true; }
        long shares; try { shares=Long.parseLong(args[args.length-1]); if (shares<=0) throw new NumberFormatException(); } catch (NumberFormatException e) { player.sendMessage(messages.invalidShares()); return true; }
        String key=String.join(" ",Arrays.copyOfRange(args,1,args.length-1)).trim(); if(key.isEmpty()){player.sendMessage(messages.usageStockSubscribe());return true;}
        if (!subscriptionsInFlight.add(player.getUniqueId())) { player.sendMessage(messages.ipoProcessing()); return true; }
        long count=shares;
        queries.resolveOpenOffering(key).whenComplete((offering,error)->mainThread.submit(()->{ if (!ready() || !safe(sender)) { subscriptionsInFlight.remove(player.getUniqueId()); return null; } if(error!=null){subscriptionsInFlight.remove(player.getUniqueId());sender.sendMessage(messages.stockQueryFailed());return null;} if(offering.isEmpty()){subscriptionsInFlight.remove(player.getUniqueId());sender.sendMessage(messages.openIpoNotFound());return null;} offerings.subscribe(player.getUniqueId(),offering.get(),count).whenComplete((result,failure)->mainThread.submit(()->{subscriptionsInFlight.remove(player.getUniqueId());if(ready()&&safe(sender)) sender.sendMessage(failure==null?messages.ipoSubscriptionResult(result.status()):messages.ipoSubscriptionResult(SubscriptionResult.Status.PROVIDER_FAILURE));return null;})); return null;})); return true;
    }
    private boolean cash(CommandSender sender,String[] args) { Player player=player(sender,"blockeco.stock.cash"); if(player==null)return true; if(args.length!=1){player.sendMessage(messages.usageStockCash());return true;} if(secondaryQueries==null){player.sendMessage(messages.marketUnavailable());return true;} replies(player,secondaryQueries.portfolio(player.getUniqueId()),v->List.of(messages.cash(v)),messages.stockQueryFailed());return true; }
    private boolean transfer(CommandSender sender,String[] args,boolean deposit) { Player player=player(sender,"blockeco.stock.cash");if(player==null)return true;if(!mutationsAllowed(player))return true;if(args.length!=2){player.sendMessage(deposit?messages.usageStockDeposit():messages.usageStockWithdraw());return true;}Money amount=money(args[1],player);if(amount==null)return true;if(cash==null){player.sendMessage(messages.marketUnavailable());return true;}replies(player,deposit?cash.deposit(player.getUniqueId(),amount):cash.withdraw(player.getUniqueId(),amount),r->List.of(messages.cashResult(r)),messages.stockQueryFailed());return true; }
    private boolean order(CommandSender sender,String[] args,boolean buy) { Player player=player(sender,"blockeco.stock.trade");if(player==null)return true;if(!mutationsAllowed(player))return true;if(args.length!=4){player.sendMessage(messages.usageStockOrder(buy));return true;}long shares=shares(args[2],player);if(shares<0)return true;Money price=money(args[3],player);if(price==null)return true;if(trading==null){player.sendMessage(messages.marketUnavailable());return true;}replies(player,buy?trading.placeBuy(player.getUniqueId(),args[1],shares,price):trading.placeSell(player.getUniqueId(),args[1],shares,price),r->List.of(messages.orderResult(r)),messages.stockQueryFailed());return true; }
    private boolean cancel(CommandSender sender,String[] args) { Player player=player(sender,"blockeco.stock.trade");if(player==null)return true;if(!mutationsAllowed(player))return true;if(args.length!=2){player.sendMessage(messages.usageStockCancel());return true;}UUID id;try{id=UUID.fromString(args[1]);}catch(IllegalArgumentException e){player.sendMessage(messages.usageStockCancel());return true;}if(trading==null){player.sendMessage(messages.marketUnavailable());return true;}replies(player,trading.cancel(player.getUniqueId(),id),r->List.of(messages.orderResult(r)),messages.stockQueryFailed());return true; }
    private boolean portfolio(CommandSender sender,String[] args) { Player player=player(sender,"blockeco.stock.portfolio");if(player==null)return true;if(args.length!=1){player.sendMessage(messages.usageStockPortfolio());return true;}if(secondaryQueries==null){player.sendMessage(messages.marketUnavailable());return true;}replies(player,secondaryQueries.portfolio(player.getUniqueId()),messages::portfolio,messages.stockQueryFailed());return true; }
    private boolean history(CommandSender sender,String[] args,boolean orders) { Player player=player(sender,orders?"blockeco.stock.orders":"blockeco.stock.trades");if(player==null)return true;int max=limit(args,10);if(max<0){player.sendMessage(orders?messages.usageStockOrders():messages.usageStockTrades());return true;}if(secondaryQueries==null){player.sendMessage(messages.marketUnavailable());return true;}if(orders)replies(player,secondaryQueries.orders(player.getUniqueId(),max),r->r.isEmpty()?List.of(messages.ordersEmpty()):r.stream().map(messages::order).toList(),messages.stockQueryFailed());else replies(player,secondaryQueries.trades(player.getUniqueId(),max),r->r.isEmpty()?List.of(messages.tradesEmpty()):r.stream().map(messages::trade).toList(),messages.stockQueryFailed());return true; }
    private boolean book(CommandSender sender,String[] args) { if(args.length!=2){sender.sendMessage(messages.usageStockBook());return true;}if(secondaryQueries==null){sender.sendMessage(messages.marketUnavailable());return true;}replies(sender,secondaryQueries.book(args[1],5),messages::book,messages.stockQueryFailed());return true; }
    private Player player(CommandSender sender,String permission) { if(!(sender instanceof Player player)){sender.sendMessage(messages.playersOnly());return null;}if(!player.hasPermission(permission)){player.sendMessage(messages.noPermission());return null;}return player; }
    private boolean mutationsAllowed(Player player) { if (mutationsOpen.getAsBoolean()) return true; player.sendMessage(messages.marketUnavailable()); return false; }
    private Money money(String text,Player player) { try { Money value=Money.fromMajor(new BigDecimal(text),currencyScale);if(value.minorUnits()<=0)throw new NumberFormatException();return value;} catch(ArithmeticException|NumberFormatException e){player.sendMessage(messages.invalidStockMoney());return null;} }
    private long shares(String text,Player player) { try {long value=Long.parseLong(text);if(value<=0)throw new NumberFormatException();return value;}catch(NumberFormatException e){player.sendMessage(messages.invalidShares());return -1;} }
    private <T> void replies(CommandSender sender, CompletionStage<T> future, java.util.function.Function<T,List<Component>> success, Component failure) {
        future.whenComplete((value,error)->mainThread.submit(()->{if(!ready()||!safe(sender)) return null; if(error!=null) sender.sendMessage(failure); else success.apply(value).forEach(sender::sendMessage); return null;}));
    }
    private static boolean safe(CommandSender sender) { return !(sender instanceof Player player) || player.isOnline(); }
    private int limit(String[] args, int fallback) { if(args.length==1)return fallback; if(args.length!=2)return -1; try{return checkedLimit(args[1]);}catch(NumberFormatException e){return -1;} }
    private static int checkedLimit(String text) { int limit=Integer.parseInt(text); if(limit<1||limit>50)throw new NumberFormatException(); return limit; }
    private static boolean numeric(String text) { return text.matches("[+-]?\\d+"); }
    private static String joined(String[] args, int start) { if(args.length<=start)return null; String value=String.join(" ",Arrays.copyOfRange(args,start,args.length)).trim(); return value.isEmpty()?null:value; }
}
