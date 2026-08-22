package cn.blockeco.exchange.paper;

import cn.blockeco.exchange.domain.audit.AuditEvent;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.ports.AppClock;
import cn.blockeco.exchange.ports.AuditLog;
import cn.blockeco.exchange.ports.MainThreadExecutor;
import cn.blockeco.exchange.ports.TransactionRunner;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/** Administrative runtime configuration for the single mutable company creation rule. */
public final class StockAdminConfigCommand implements CommandExecutor, TabCompleter {
    static final String PERMISSION = "blockstock.admin.config";
    private final MutableCompanyCreationRules rules; private final ConfigStore config; private final AuditLog audit;
    public interface ConfigStore { void persistMinimumCapital(String value) throws java.io.IOException; }
    private final TransactionRunner transactions; private final Executor sql; private final AppClock clock; private final Messages messages; private final MainThreadExecutor main;
    public StockAdminConfigCommand(MutableCompanyCreationRules rules, ConfigStore config, AuditLog audit, TransactionRunner transactions, Executor sql, AppClock clock, Messages messages) { this(rules, config, audit, transactions, sql, clock, messages, new MainThreadExecutor() { @Override public <T> java.util.concurrent.CompletionStage<T> submit(java.util.function.Supplier<T> work) { return java.util.concurrent.CompletableFuture.completedFuture(work.get()); } }); }
    public StockAdminConfigCommand(MutableCompanyCreationRules rules, ConfigStore config, AuditLog audit, TransactionRunner transactions, Executor sql, AppClock clock, Messages messages, MainThreadExecutor main) { this.rules=rules; this.config=config; this.audit=audit; this.transactions=transactions; this.sql=sql; this.clock=clock; this.messages=messages; this.main=main; }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PERMISSION)) { sender.sendMessage(messages.noPermission()); return true; }
        if (args.length == 1 && "config".equalsIgnoreCase(args[0])) { sender.sendMessage(messages.minimumCapital(rules.current())); return true; }
        if (args.length != 3 || !"config".equalsIgnoreCase(args[0]) || !"min-capital".equalsIgnoreCase(args[1])) { sender.sendMessage(messages.usageStockAdminConfig()); return true; }
        CompanyCreationRules before = rules.current(); Money next;
        try { next = Money.fromMajor(new BigDecimal(args[2]), before.scale()); } catch (RuntimeException failure) { sender.sendMessage(messages.invalidMinimumCapital()); return true; }
        if (next.minorUnits() <= 0) { sender.sendMessage(messages.invalidMinimumCapital()); return true; }
        try { config.persistMinimumCapital(next.toMajor(before.scale()).toPlainString()); }
        catch (Exception failure) { sender.sendMessage(messages.minimumCapitalSaveFailed()); return true; }
        rules.replaceMinimumCapital(next);
        sender.sendMessage(messages.minimumCapitalSaved(next.toMajor(before.scale()).toPlainString()));
        UUID actor = sender instanceof Player player ? player.getUniqueId() : null;
        AuditEvent event = new AuditEvent(UUID.randomUUID(), Optional.empty(), Optional.ofNullable(actor), "ADMIN_MINIMUM_CAPITAL_CHANGED", Map.of("oldMinor", before.minimumCapital().minorUnits(), "newMinor", next.minorUnits(), "actor", actor == null ? "CONSOLE" : actor.toString(), "source", actor == null ? "CONSOLE" : "PLAYER"), clock.now());
        sql.execute(() -> { try { transactions.inTransaction(connection -> { audit.append(connection, event); return null; }); } catch (RuntimeException failure) { main.submit(() -> { sender.sendMessage(messages.minimumCapitalAuditFailed()); return null; }); } });
        return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) { return complete(sender, args); }
    List<String> complete(CommandSender sender, String[] args) {
        if (!sender.hasPermission(PERMISSION)) return List.of();
        if (args.length == 1) return filter(List.of("config"), args[0]);
        if (args.length == 2 && "config".equalsIgnoreCase(args[0])) return filter(List.of("min-capital"), args[1]);
        return List.of();
    }
    private static List<String> filter(List<String> values, String prefix) { return values.stream().filter(value -> value.regionMatches(true, 0, prefix, 0, prefix.length())).toList(); }
}
