package cn.blockeco.exchange.paper;

import cn.blockeco.exchange.application.PrimaryOfferingService;
import cn.blockeco.exchange.application.PublicStockQueryService;
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
    private final Set<UUID> subscriptionsInFlight = ConcurrentHashMap.newKeySet();
    private volatile boolean acceptingFlag;
    public StockCommand(PublicStockQueryService queries, PrimaryOfferingService offerings, MainThreadExecutor mainThread, BooleanSupplier accepting, Messages messages) {
        this.queries=queries; this.offerings=offerings; this.mainThread=mainThread; this.accepting=accepting; this.messages=messages;
    }
    @Override public void setAccepting(boolean accepting) { acceptingFlag=accepting; }
    private boolean ready() { return acceptingFlag && accepting.getAsBoolean(); }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || "help".equalsIgnoreCase(args[0])) { messages.stockHelp(sender).forEach(sender::sendMessage); return true; }
        if (!ready()) { sender.sendMessage(messages.initializing()); return true; }
        return switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
            case "market" -> market(sender, args);
            case "ipo" -> ipo(sender, args);
            case "info" -> info(sender, args);
            case "announcements" -> announcements(sender, args);
            case "subscribe" -> subscribe(sender, args);
            default -> { sender.sendMessage(messages.usageStock()); yield true; }
        };
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
        queries.resolveOpenOffering(key).whenComplete((offering,error)->mainThread.submit(()->{ if (!safe(sender)) { subscriptionsInFlight.remove(player.getUniqueId()); return null; } if(error!=null){subscriptionsInFlight.remove(player.getUniqueId());sender.sendMessage(messages.stockQueryFailed());return null;} if(offering.isEmpty()){subscriptionsInFlight.remove(player.getUniqueId());sender.sendMessage(messages.openIpoNotFound());return null;} offerings.subscribe(player.getUniqueId(),offering.get(),count).whenComplete((result,failure)->mainThread.submit(()->{subscriptionsInFlight.remove(player.getUniqueId());if(safe(sender)) sender.sendMessage(failure==null?messages.ipoSubscriptionResult(result.status()):messages.ipoSubscriptionResult(SubscriptionResult.Status.PROVIDER_FAILURE));return null;})); return null;})); return true;
    }
    private <T> void replies(CommandSender sender, CompletionStage<T> future, java.util.function.Function<T,List<Component>> success, Component failure) {
        future.whenComplete((value,error)->mainThread.submit(()->{if(!safe(sender)) return null; if(error!=null) sender.sendMessage(failure); else success.apply(value).forEach(sender::sendMessage); return null;}));
    }
    private static boolean safe(CommandSender sender) { return !(sender instanceof Player player) || player.isOnline(); }
    private int limit(String[] args, int fallback) { if(args.length==1)return fallback; if(args.length!=2)return -1; try{return checkedLimit(args[1]);}catch(NumberFormatException e){return -1;} }
    private static int checkedLimit(String text) { int limit=Integer.parseInt(text); if(limit<1||limit>50)throw new NumberFormatException(); return limit; }
    private static boolean numeric(String text) { return text.matches("[+-]?\\d+"); }
    private static String joined(String[] args, int start) { if(args.length<=start)return null; String value=String.join(" ",Arrays.copyOfRange(args,start,args.length)).trim(); return value.isEmpty()?null:value; }
}
