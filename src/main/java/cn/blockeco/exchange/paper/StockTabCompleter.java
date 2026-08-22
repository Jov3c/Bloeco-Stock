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
        if (args.length == 1) return filter(List.of("help", "market", "ipo", "info", "announcements", "subscribe"), args[0]);
        if (args.length == 2 && ("info".equalsIgnoreCase(args[0]) || "announcements".equalsIgnoreCase(args[0]) || "subscribe".equalsIgnoreCase(args[0]))) {
            List<String> values = new ArrayList<>();
            for (var symbol : cache.snapshot()) { values.add(symbol.companyName()); symbol.stockCode().ifPresent(values::add); }
            return filter(values, args[1]);
        }
        return List.of();
    }
    private List<String> filter(List<String> values, String prefix) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(normalized)).distinct().toList();
    }
}
