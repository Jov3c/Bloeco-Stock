package cn.blockeco.exchange.paper;

import java.util.List;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/** Stateless permission-aware completion for the company command. */
public final class CompanyTabCompleter implements TabCompleter {
    private final CompanyCreationRules rules;
    public CompanyTabCompleter(CompanyCreationRules rules) { this.rules = rules; }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) { return complete(sender, args); }
    public List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            java.util.ArrayList<String> choices = new java.util.ArrayList<>();
            if (sender instanceof Player && sender.hasPermission("blockeco.company.create")) choices.add("create");
            if (sender.hasPermission("blockeco.company.info")) choices.add("info");
            if (sender.hasPermission("blockeco.admin.recovery")) choices.add("recovery");
            return filter(choices, args[0]);
        }
        if (args.length >= 4 && "create".equalsIgnoreCase(args[0]) && sender instanceof Player && sender.hasPermission("blockeco.company.create"))
            return filter(rules.allowedDividendPercent().stream().map(String::valueOf).toList(), args[args.length - 1]);
        if (args.length == 2 && "recovery".equalsIgnoreCase(args[0]) && sender.hasPermission("blockeco.admin.recovery")) return filter(List.of("list"), args[1]);
        return List.of();
    }
    private List<String> filter(List<String> choices, String prefix) { return List.copyOf(choices.stream().filter(choice -> choice.regionMatches(true, 0, prefix, 0, prefix.length())).toList()); }
}
