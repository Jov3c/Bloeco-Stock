package cn.blockeco.exchange.paper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

/** Completes only from the immutable, already-loaded public symbol snapshot. */
public final class StockTabCompleter implements TabCompleter {
    private final PublicStockSymbolCache cache;
    public StockTabCompleter(PublicStockSymbolCache cache) { this.cache = cache; }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return filter(literals(sender), args[0]);
        if (args.length == 2 && symbolArgument(sender,args[0])) {
            List<String> values = new ArrayList<>();
            for (var symbol : cache.snapshot()) { values.add(symbol.companyName()); symbol.stockCode().ifPresent(values::add); }
            return filter(values, args[1]);
        }
        return List.of();
    }
    private static boolean symbolArgument(CommandSender sender,String literal) { if("info".equalsIgnoreCase(literal)||"announcements".equalsIgnoreCase(literal)||"book".equalsIgnoreCase(literal))return true; if("subscribe".equalsIgnoreCase(literal))return sender.hasPermission("blockeco.stock.subscribe"); return ("buy".equalsIgnoreCase(literal)||"sell".equalsIgnoreCase(literal))&&sender.hasPermission("blockeco.stock.trade"); }
    private static List<String> literals(CommandSender sender) { ArrayList<String> values=new ArrayList<>(List.of("help","market","ipo","info","announcements","book")); if(sender.hasPermission("blockeco.stock.subscribe"))values.add("subscribe"); if(sender.hasPermission("blockeco.stock.cash"))values.addAll(List.of("cash","deposit","withdraw")); if(sender.hasPermission("blockeco.stock.trade"))values.addAll(List.of("buy","sell","cancel")); if(sender.hasPermission("blockeco.stock.portfolio"))values.add("portfolio"); if(sender.hasPermission("blockeco.stock.orders"))values.add("orders"); if(sender.hasPermission("blockeco.stock.trades"))values.add("trades"); return List.copyOf(values); }
    private List<String> filter(List<String> values, String prefix) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(normalized)).distinct().toList();
    }
}
