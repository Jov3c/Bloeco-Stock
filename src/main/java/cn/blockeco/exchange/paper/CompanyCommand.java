package cn.blockeco.exchange.paper;

import cn.blockeco.exchange.application.*;
import cn.blockeco.exchange.domain.registration.RegistrationSaga;
import cn.blockeco.exchange.application.CapitalizationRecoveryRecord;
import cn.blockeco.exchange.ports.MainThreadExecutor;
import cn.blockeco.exchange.domain.money.Money;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Supplier;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

public final class CompanyCommand implements CommandExecutor {
    private final CompanyRegistrationService registration; private final CompanyQueryService queries; private final Messages messages; private final MainThreadExecutor mainThread; private final Supplier<CompanyCreationRules> rules; private final AssetBindingService assets; private final PrimaryOfferingService offerings;
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    private volatile boolean accepting = false;
    public CompanyCommand(CompanyRegistrationService registration, CompanyQueryService queries, Messages messages, MainThreadExecutor mainThread, CompanyCreationRules rules) { this(registration,queries,messages,mainThread,() -> rules,null,null); }
    public CompanyCommand(CompanyRegistrationService registration, CompanyQueryService queries, Messages messages, MainThreadExecutor mainThread, MutableCompanyCreationRules rules) { this(registration,queries,messages,mainThread,rules::current,null,null); }
    public CompanyCommand(CompanyRegistrationService registration, CompanyQueryService queries, Messages messages, MainThreadExecutor mainThread, CompanyCreationRules rules, AssetBindingService assets, PrimaryOfferingService offerings) { this(registration,queries,messages,mainThread,() -> rules,assets,offerings); }
    public CompanyCommand(CompanyRegistrationService registration, CompanyQueryService queries, Messages messages, MainThreadExecutor mainThread, Supplier<CompanyCreationRules> rules, AssetBindingService assets, PrimaryOfferingService offerings) { this.registration=registration; this.queries=queries; this.messages=messages; this.mainThread=mainThread; this.rules=rules; this.assets=assets; this.offerings=offerings; }
    public void setAccepting(boolean accepting) { this.accepting = accepting; }
    public static Optional<RegistrationRequest> parseCreate(UUID player, String[] args, CompanyCreationRules rules) {
        if (args.length < 4 || !"create".equalsIgnoreCase(args[0])) return Optional.empty();
        int percent; try { percent = Integer.parseInt(args[args.length - 1]); } catch (NumberFormatException e) { return Optional.empty(); }
        if (!rules.allowedDividendPercent().contains(percent)) return Optional.empty();
        Money paidInCapital; try { paidInCapital = Money.fromMajor(new BigDecimal(args[args.length - 2]), rules.scale()); } catch (ArithmeticException | NumberFormatException e) { return Optional.empty(); }
        if (!rules.acceptsPaidInCapital(paidInCapital)) return Optional.empty();
        String name = String.join(" ", Arrays.copyOfRange(args, 1, args.length - 2)).trim();
        if (name.length() >= 2 && name.startsWith("\"") && name.endsWith("\"")) name = name.substring(1, name.length() - 1).trim();
        return name.isBlank() ? Optional.empty() : Optional.of(new RegistrationRequest(player, name, paidInCapital, percent));
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) { messages.companyHelp(sender instanceof Player && sender.hasPermission("blockeco.company.create"), sender.hasPermission("blockeco.company.info"), sender.hasPermission("blockeco.admin.recovery"), rules.get()).forEach(sender::sendMessage); return true; }
        if (!accepting) { sender.sendMessage(messages.initializing()); return true; }
        if ("create".equalsIgnoreCase(args[0])) return create(sender, args);
        if ("info".equalsIgnoreCase(args[0])) return info(sender, args);
        if ("asset".equalsIgnoreCase(args[0])) return bindAsset(sender,args);
        if ("ipo".equalsIgnoreCase(args[0])) return ipo(sender,args);
        if ("recovery".equalsIgnoreCase(args[0])) {
            if (args.length == 2 && "list".equalsIgnoreCase(args[1])) return recovery(sender);
            if (!sender.hasPermission("blockeco.admin.recovery")) { sender.sendMessage(messages.noPermission()); return true; }
            sender.sendMessage(messages.usageRecovery()); return true;
        }
        return false;
    }
    private boolean bindAsset(CommandSender sender,String[] args) { if (!(sender instanceof Player player)) { sender.sendMessage(messages.playersOnly()); return true; } if(!player.hasPermission("blockeco.company.asset.bind")){player.sendMessage(messages.noPermission());return true;} if(!accepting||assets==null){player.sendMessage(messages.initializing());return true;} if(args.length!=4||!"bind".equalsIgnoreCase(args[1])){player.sendMessage(messages.usageAssetBind());return true;} queries.findByFounder(player.getUniqueId()).whenComplete((company,failure)->{if(failure!=null||company.isEmpty()){mainThread.submit(()->{player.sendMessage(messages.companyNotFound());return null;});return;} assets.bind(company.get().id(),player.getUniqueId(),args[2],args[3]).whenComplete((binding,error)->mainThread.submit(()->{player.sendMessage(error==null?messages.assetBound():messages.assetBindFailed());return null;}));}); return true; }
    private boolean announce(CommandSender sender,String[] args) { if (!(sender instanceof Player player)) { sender.sendMessage(messages.playersOnly()); return true; } if(!player.hasPermission("blockeco.company.ipo.announce")){player.sendMessage(messages.noPermission());return true;} if(!accepting||offerings==null){player.sendMessage(messages.initializing());return true;} if(args.length!=4||!"announce".equalsIgnoreCase(args[1])){player.sendMessage(messages.usageIpoAnnounce());return true;} CompanyCreationRules snapshot=rules.get(); Money target,price;try{target=Money.fromMajor(new BigDecimal(args[2]),snapshot.scale());price=Money.fromMajor(new BigDecimal(args[3]),snapshot.scale());}catch(RuntimeException e){player.sendMessage(messages.usageIpoAnnounce());return true;} queries.findByFounder(player.getUniqueId()).whenComplete((company,failure)->{if(failure!=null||company.isEmpty()){mainThread.submit(()->{player.sendMessage(messages.companyNotFound());return null;});return;} offerings.announce(company.get().id(),player.getUniqueId(),target,price).whenComplete((offer,error)->mainThread.submit(()->{player.sendMessage(error==null?messages.ipoAnnounced():messages.ipoAnnounceFailed());return null;}));}); return true; }
    private boolean ipo(CommandSender sender,String[] args){if(args.length>1&&"subscribe".equalsIgnoreCase(args[1]))return subscribe(sender,args);return announce(sender,args);}
    private boolean subscribe(CommandSender sender,String[] args){if(!(sender instanceof Player player)){sender.sendMessage(messages.playersOnly());return true;}if(!player.hasPermission("blockeco.company.ipo.subscribe")){player.sendMessage(messages.noPermission());return true;}if(offerings==null){player.sendMessage(messages.initializing());return true;}if(args.length!=4){player.sendMessage(messages.usageIpoSubscribe());return true;}UUID offering;long shares;try{offering=UUID.fromString(args[2]);shares=Long.parseLong(args[3]);if(shares<=0)throw new NumberFormatException();Math.multiplyExact(shares,1L);}catch(RuntimeException e){player.sendMessage(messages.usageIpoSubscribe());return true;}if(!inFlight.add(player.getUniqueId())){player.sendMessage(messages.duplicateRequest());return true;}player.sendMessage(messages.ipoProcessing());offerings.subscribe(player.getUniqueId(),offering,shares).whenComplete((result,error)->mainThread.submit(()->{inFlight.remove(player.getUniqueId());player.sendMessage(error==null?messages.ipoSubscriptionResult(result.status()):messages.ipoSubscriptionResult(SubscriptionResult.Status.PROVIDER_FAILURE));return null;}));return true;}
    private boolean create(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage(messages.playersOnly()); return true; }
        if (!player.hasPermission("blockeco.company.create")) { player.sendMessage(messages.noPermission()); return true; }
        if (!accepting) { player.sendMessage(messages.initializing()); return true; }
        CompanyCreationRules snapshot = rules.get();
        Optional<RegistrationRequest> request = parseCreate(player.getUniqueId(), args, snapshot);
        if (request.isEmpty()) { player.sendMessage(messages.usageCreate(snapshot)); return true; }
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
        if (args.length < 2) { sender.sendMessage(messages.usageInfo()); return true; }
        queries.findByName(String.join(" ", Arrays.copyOfRange(args, 1, args.length))).whenComplete((company, failure) -> mainThread.submit(() -> { sender.sendMessage(failure != null ? messages.lookupFailed() : company.map(c -> messages.companyInfo(c.displayName(), c.status())).orElse(messages.companyNotFound())); return null; }));
        return true;
    }
    private boolean recovery(CommandSender sender) {
        if (!sender.hasPermission("blockeco.admin.recovery")) { sender.sendMessage(messages.noPermission()); return true; }
        queries.recoveryList().thenCombine(queries.capitalizationRecoveryList(), RecoveryRecords::new).whenComplete((records, failure) -> mainThread.submit(() -> {
            if (failure != null) { sender.sendMessage(messages.recoveryLookupFailed()); return null; }
            if (records.registration().isEmpty() && records.capitalization().isEmpty()) sender.sendMessage(messages.noRecoveryRecords());
            for (RegistrationSaga saga : records.registration()) sender.sendMessage(messages.recoveryRecord(saga.id(), saga.founderId(), saga.totalWithdrawal().minorUnits(), saga.state(), saga.updatedAt(), saga.errorMessage()==null ? "" : saga.errorMessage()));
            for (CapitalizationRecoveryRecord record : records.capitalization()) { var operation = record.operation(); sender.sendMessage(messages.capitalizationRecoveryRecord(operation.id(), operation.companyId(), operation.playerId(), operation.amount().minorUnits(), operation.state(), record.reason())); }
            return null;
        }));
        return true;
    }
    private record RecoveryRecords(List<RegistrationSaga> registration, List<CapitalizationRecoveryRecord> capitalization) { }
    private net.kyori.adventure.text.Component resultMessage(RegistrationResult result) { return switch (result.status()) { case SUCCESS -> messages.registrationSuccess(); case INSUFFICIENT_FUNDS -> messages.insufficientFunds(); case DUPLICATE_NAME -> messages.duplicateName(); case REFUNDED_AFTER_FAILURE -> messages.refunded(); case RECOVERY_REQUIRED, PROVIDER_FAILURE -> messages.recoveryRequired(); }; }
}
