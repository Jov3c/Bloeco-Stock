package cn.blockeco.exchange.paper;

import cn.blockeco.exchange.application.*;
import cn.blockeco.exchange.domain.registration.RegistrationSaga;
import cn.blockeco.exchange.ports.MainThreadExecutor;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public final class CompanyCommand implements CommandExecutor {
    private final CompanyRegistrationService registration; private final CompanyQueryService queries; private final Messages messages; private final MainThreadExecutor mainThread;
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    private volatile boolean accepting = false;
    public CompanyCommand(CompanyRegistrationService registration, CompanyQueryService queries, Messages messages, MainThreadExecutor mainThread) { this.registration=registration; this.queries=queries; this.messages=messages; this.mainThread=mainThread; }
    public void setAccepting(boolean accepting) { this.accepting = accepting; }
    public static Optional<RegistrationRequest> parseCreate(UUID player, String[] args) {
        if (args.length < 3 || !"create".equalsIgnoreCase(args[0])) return Optional.empty();
        int percent; try { percent = Integer.parseInt(args[args.length - 1]); } catch (NumberFormatException e) { return Optional.empty(); }
        if (percent != 30 && percent != 50 && percent != 70) return Optional.empty();
        String name = String.join(" ", Arrays.copyOfRange(args, 1, args.length - 1)).trim();
        if (name.length() >= 2 && name.startsWith("\"") && name.endsWith("\"")) name = name.substring(1, name.length() - 1).trim();
        return name.isBlank() ? Optional.empty() : Optional.of(new RegistrationRequest(player, name, percent));
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) return false;
        if ("create".equalsIgnoreCase(args[0])) return create(sender, args);
        if ("info".equalsIgnoreCase(args[0])) return info(sender, args);
        if (args.length == 2 && "recovery".equalsIgnoreCase(args[0]) && "list".equalsIgnoreCase(args[1])) return recovery(sender);
        return false;
    }
    private boolean create(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage(messages.playersOnly()); return true; }
        if (!player.hasPermission("blockeco.company.create")) { player.sendMessage(messages.noPermission()); return true; }
        if (!accepting) { player.sendMessage(messages.initializing()); return true; }
        Optional<RegistrationRequest> request = parseCreate(player.getUniqueId(), args);
        if (request.isEmpty()) { player.sendMessage(messages.usageCreate()); return true; }
        if (!inFlight.add(player.getUniqueId())) { player.sendMessage(messages.duplicateRequest()); return true; }
        player.sendMessage(messages.processing());
        registration.register(request.get()).whenComplete((result, failure) -> mainThread.submit(() -> {
            inFlight.remove(player.getUniqueId());
            player.sendMessage(failure == null ? resultMessage(result) : messages.registrationFailed());
            return null;
        }));
        return true;
    }
    private boolean info(CommandSender sender, String[] args) {
        if (!sender.hasPermission("blockeco.company.info")) { sender.sendMessage(messages.noPermission()); return true; }
        if (args.length < 2) return false;
        queries.findByName(String.join(" ", Arrays.copyOfRange(args, 1, args.length))).whenComplete((company, failure) -> mainThread.submit(() -> { sender.sendMessage(failure != null ? messages.lookupFailed() : company.map(c -> messages.companyInfo(c.displayName(), c.status())).orElse(messages.companyNotFound())); return null; }));
        return true;
    }
    private boolean recovery(CommandSender sender) {
        if (!sender.hasPermission("blockeco.admin.recovery")) { sender.sendMessage(messages.noPermission()); return true; }
        queries.recoveryList().whenComplete((records, failure) -> mainThread.submit(() -> {
            if (failure != null) { sender.sendMessage(messages.recoveryLookupFailed()); return null; }
            if (records.isEmpty()) sender.sendMessage(messages.noRecoveryRecords());
            for (RegistrationSaga saga : records) sender.sendMessage(messages.recoveryRecord(saga.id(), saga.founderId(), saga.totalWithdrawal().minorUnits(), saga.state(), saga.updatedAt(), saga.errorMessage()==null ? "" : saga.errorMessage()));
            return null;
        }));
        return true;
    }
    private net.kyori.adventure.text.Component resultMessage(RegistrationResult result) { return switch (result.status()) { case SUCCESS -> messages.registrationSuccess(); case INSUFFICIENT_FUNDS -> messages.insufficientFunds(); case DUPLICATE_NAME -> messages.duplicateName(); case REFUNDED_AFTER_FAILURE -> messages.refunded(); case RECOVERY_REQUIRED, PROVIDER_FAILURE -> messages.recoveryRequired(); }; }
}
