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
import java.util.concurrent.CompletionStage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import net.kyori.adventure.text.Component;

/** Administrative runtime configuration for the single mutable company creation rule. */
public final class StockAdminConfigCommand implements CommandExecutor, TabCompleter {
    static final String PERMISSION = "blockstock.admin.config";
    static final String RECOVERY_PERMISSION = "blockeco.admin.recovery";
    private final MutableCompanyCreationRules rules; private final ConfigStore config; private final AuditLog audit;
    public interface ConfigStore { void persistMinimumCapital(String value) throws java.io.IOException; default void persistFounderCashOutMaximum(String value) throws java.io.IOException { throw new UnsupportedOperationException("cash-out limit persistence unavailable"); } }
    private final TransactionRunner transactions; private final Executor sql; private final AppClock clock; private final Messages messages; private final MainThreadExecutor main; private final RecoveryInspector recovery;
    public interface RecoveryInspector { CompletionStage<cn.blockeco.exchange.application.SecondaryMarketRecoveryService.RecoverySnapshot> inspect(); }
    public StockAdminConfigCommand(MutableCompanyCreationRules rules, ConfigStore config, AuditLog audit, TransactionRunner transactions, Executor sql, AppClock clock, Messages messages) { this(rules, config, audit, transactions, sql, clock, messages, new MainThreadExecutor() { @Override public <T> java.util.concurrent.CompletionStage<T> submit(java.util.function.Supplier<T> work) { return java.util.concurrent.CompletableFuture.completedFuture(work.get()); } }, null); }
    public StockAdminConfigCommand(MutableCompanyCreationRules rules, ConfigStore config, AuditLog audit, TransactionRunner transactions, Executor sql, AppClock clock, Messages messages, MainThreadExecutor main) { this(rules, config, audit, transactions, sql, clock, messages, main, null); }
    public StockAdminConfigCommand(MutableCompanyCreationRules rules, ConfigStore config, AuditLog audit, TransactionRunner transactions, Executor sql, AppClock clock, Messages messages, MainThreadExecutor main, RecoveryInspector recovery) { this.rules=rules; this.config=config; this.audit=audit; this.transactions=transactions; this.sql=sql; this.clock=clock; this.messages=messages; this.main=main; this.recovery=recovery; }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && ("recovery".equalsIgnoreCase(args[0]) || "reconcile".equalsIgnoreCase(args[0]))) return recovery(sender, args);
        if (!sender.hasPermission(PERMISSION)) { sender.sendMessage(messages.noPermission()); return true; }
        if (args.length == 1 && "config".equalsIgnoreCase(args[0])) { sender.sendMessage(messages.minimumCapital(rules.current())); return true; }
        if (args.length == 3 && "config".equalsIgnoreCase(args[0]) && "cashout-limit".equalsIgnoreCase(args[1])) return updateCashOutLimit(sender, args[2]);
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
    private boolean updateCashOutLimit(CommandSender sender, String raw) {
        Money next;
        try { next = Money.fromMajor(new BigDecimal(raw), rules.current().scale()); } catch (RuntimeException invalid) { sender.sendMessage(Component.text("单次创始人套现上限必须是非负金额，0 表示禁用。")); return true; }
        if (next.minorUnits() < 0) { sender.sendMessage(Component.text("单次创始人套现上限必须是非负金额，0 表示禁用。")); return true; }
        try { config.persistFounderCashOutMaximum(next.toMajor(rules.current().scale()).toPlainString()); }
        catch (Exception failure) { sender.sendMessage(Component.text("套现上限保存失败，当前规则未改变。")); return true; }
        sender.sendMessage(Component.text("单次创始人套现上限已更新为 " + next.toMajor(rules.current().scale()).toPlainString() + "（0 表示禁用）。")); return true;
    }
    private boolean recovery(CommandSender sender, String[] args) {
        if (!sender.hasPermission(RECOVERY_PERMISSION)) { sender.sendMessage(messages.noPermission()); return true; }
        boolean valid = (args.length == 1 && "reconcile".equalsIgnoreCase(args[0]))
                || (args.length == 2 && "recovery".equalsIgnoreCase(args[0]) && "cash".equalsIgnoreCase(args[1]));
        if (!valid) { sender.sendMessage(messages.usageStockAdminRecovery()); return true; }
        if (recovery == null) { sender.sendMessage(messages.recoveryUnavailable()); return true; }
        try {
            recovery.inspect().whenComplete((snapshot, failure) -> main.submit(() -> {
                if (failure != null) sender.sendMessage(messages.recoveryUnavailable());
                else messages.secondaryRecovery(snapshot).forEach(sender::sendMessage);
                return null;
            }));
        } catch (RuntimeException failure) { sender.sendMessage(messages.recoveryUnavailable()); }
        return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) { return complete(sender, args); }
    List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            java.util.ArrayList<String> roots=new java.util.ArrayList<>();
            if(sender.hasPermission(PERMISSION)) roots.add("config");
            if(sender.hasPermission(RECOVERY_PERMISSION)) { roots.add("recovery"); roots.add("reconcile"); }
            return filter(roots, args[0]);
        }
        if (args.length == 2 && "recovery".equalsIgnoreCase(args[0])) return sender.hasPermission(RECOVERY_PERMISSION) ? filter(List.of("cash"), args[1]) : List.of();
        if (!sender.hasPermission(PERMISSION)) return List.of();
        if (args.length == 2 && "config".equalsIgnoreCase(args[0])) return filter(List.of("min-capital", "cashout-limit"), args[1]);
        return List.of();
    }
    private static List<String> filter(List<String> values, String prefix) { return values.stream().filter(value -> value.regionMatches(true, 0, prefix, 0, prefix.length())).toList(); }
}
