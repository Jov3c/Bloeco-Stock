package cn.blockeco.exchange;

import cn.blockeco.exchange.application.CompanyQueryService;
import cn.blockeco.exchange.application.CompanyRegistrationService;
import cn.blockeco.exchange.application.CompanyCapitalizationService;
import cn.blockeco.exchange.application.AssetBindingService;
import cn.blockeco.exchange.application.PrimaryOfferingService;
import cn.blockeco.exchange.application.SecuritiesCashService;
import cn.blockeco.exchange.application.SecondaryMarketRecoveryService;
import cn.blockeco.exchange.application.SecondaryMarketQueryService;
import cn.blockeco.exchange.application.SecondaryMarketService;
import cn.blockeco.exchange.application.IpoLifecycleScheduler;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.infrastructure.sql.Database;
import cn.blockeco.exchange.infrastructure.sql.SqlAuditLog;
import cn.blockeco.exchange.infrastructure.sql.SqlCompanyRepository;
import cn.blockeco.exchange.infrastructure.sql.SqlCompanyFinanceRepository;
import cn.blockeco.exchange.infrastructure.sql.SqlRegistrationSagaRepository;
import cn.blockeco.exchange.infrastructure.sql.SqlAssetBindingRepository;
import cn.blockeco.exchange.infrastructure.sql.SqlPrimaryOfferingRepository;
import cn.blockeco.exchange.infrastructure.sql.SqlPublicStockRepository;
import cn.blockeco.exchange.infrastructure.sql.SqlSecondaryTradingRepository;
import cn.blockeco.exchange.infrastructure.sql.SqlSecuritiesCashRepository;
import cn.blockeco.exchange.infrastructure.vault.VaultEconomyGateway;
import cn.blockeco.exchange.infrastructure.vault.VaultSecuritiesCashGateway;
import cn.blockeco.exchange.infrastructure.vault.VaultTreasuryEscrowGateway;
import cn.blockeco.exchange.paper.CompanyCommand;
import cn.blockeco.exchange.paper.CompanyCreationRules;
import cn.blockeco.exchange.paper.MutableCompanyCreationRules;
import cn.blockeco.exchange.paper.StockAdminConfigCommand;
import cn.blockeco.exchange.paper.FileConfigStore;
import cn.blockeco.exchange.paper.CompanyTabCompleter;
import cn.blockeco.exchange.paper.Messages;
import cn.blockeco.exchange.paper.PaperMainThread;
import cn.blockeco.exchange.paper.MigrationResult;
import cn.blockeco.exchange.paper.PluginDataDirectoryMigrator;
import cn.blockeco.exchange.paper.PublicStockSymbolCache;
import cn.blockeco.exchange.paper.StockCommand;
import cn.blockeco.exchange.paper.StockTabCompleter;
import cn.blockeco.exchange.paper.SecondaryTradingGate;
import cn.blockeco.exchange.ports.AppClock;
import cn.blockeco.exchange.ports.CompanyAssetAdapterRegistry;
import cn.blockeco.exchange.infrastructure.CompanyAssetAdapterRegistryImpl;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.ServicePriority;

public final class BlockecoPlugin extends JavaPlugin {
    private ExecutorService sqlExecutor;
    private Database database;
    private CompanyCommand command;
    private StockCommand stockCommand;
    private BootstrapCoordinator<Database> bootstrap;
    private MutableCompanyCreationRules creationRules;
    private final PluginRuntime runtime = new PluginRuntime();
    private final CompanyAssetAdapterRegistry assetAdapterRegistry = new CompanyAssetAdapterRegistryImpl();
    private IpoLifecycleScheduler ipoLifecycle;
    private SecondaryTradingGate secondaryTradingGate;

    @Override public void onEnable() {
        try {
            MigrationResult migration = new PluginDataDirectoryMigrator(java.nio.file.Files::move)
                .migrate(getDataFolder().toPath().getParent());
            if (migration == MigrationResult.MIGRATED) getLogger().info("BlockStock data directory migrated from BlockecoExchange.");
            if (migration == MigrationResult.SKIPPED_TARGET_EXISTS) getLogger().warning("BlockStock data directory migration skipped because the target already exists.");
        } catch (java.io.IOException failure) {
            failEnable("BlockStock 数据目录迁移失败: " + failure.getMessage());
            return;
        }
        saveDefaultConfig();
        try { validateConfiguration(); if (!getDataFolder().exists() && !getDataFolder().mkdirs()) throw new IllegalStateException("cannot create plugin data folder"); }
        catch (RuntimeException failure) { failEnable(configurationFailureMessage(failure)); return; }
        if (!installInitializingCommand()) return;
        sqlExecutor = Executors.newSingleThreadExecutor(r -> { Thread thread = new Thread(r, "BlockStock-SQL"); thread.setDaemon(true); return thread; });
        runtime.attachExecutor(sqlExecutor);
        Path file = getDataFolder().toPath().resolve(getConfig().getString("database.file", "blockeco.db"));
        bootstrap = new BootstrapCoordinator<>(new PaperMainThread(this), this::finishEnable, failure -> failEnable(startupFailureMessage(failure)), runtime::closeDatabase);
        runtime.attachBootstrap(bootstrap);
        bootstrap.coordinate(java.util.concurrent.CompletableFuture.supplyAsync(() -> { Database db = new Database("jdbc:sqlite:" + file); runtime.attachDatabase(db); try { db.migrate(); return db; } catch (Exception e) { runtime.closeDatabase(db); throw new IllegalStateException("SQLite migration failed", e); } }, sqlExecutor));
    }

    private boolean finishEnable(Database db) {
        var economyRegistration = getServer().getServicesManager().getRegistration(Economy.class);
        if (!VaultProviderResolver.isAvailable(economyRegistration)) throw new IllegalStateException("Vault 经济提供方不可用");
        var escrowId = java.util.UUID.fromString(getConfig().getString("company.treasury-escrow-uuid"));
        String escrowFailure = VaultProviderResolver.escrowPreflightFailure(economyRegistration.getProvider(), getServer().getOfflinePlayer(escrowId), escrowId);
        database = db;
        AppClock clock = Instant::now;
        var companies = new SqlCompanyRepository(db.dataSource()); var sagas = new SqlRegistrationSagaRepository(db.dataSource(), clock);
        var mainThread = new PaperMainThread(this);
        int scale = getConfig().getInt("currency.scale");
        var economy = new VaultEconomyGateway(getServer(), scale);
        var finance = new SqlCompanyFinanceRepository(db.dataSource());
        var escrow = new VaultTreasuryEscrowGateway(economy, mainThread, escrowId);
        var registration = new CompanyRegistrationService(companies, sagas, new SqlAuditLog(), db, economy, mainThread, sqlExecutor, clock, creationRules.current().registrationFee(), creationRules::current, finance, escrow, creationRules.current().initialShares());
        getServer().getServicesManager().register(CompanyAssetAdapterRegistry.class, assetAdapterRegistry, this, ServicePriority.Normal);
        var assetBindings = new AssetBindingService(new SqlAssetBindingRepository(db.dataSource()), db, () -> {
            CompanyAssetAdapterRegistry registry = getServer().getServicesManager().load(CompanyAssetAdapterRegistry.class);
            return registry == null ? java.util.List.of() : registry.snapshot();
        }, clock);
        var ipoRepository = new SqlPrimaryOfferingRepository(db.dataSource());
        var primaryOfferings = new PrimaryOfferingService(ipoRepository, db, escrow, sqlExecutor, clock);
        var messages = new Messages(getConfig());
        command = new CompanyCommand(registration, new CompanyQueryService(companies, sagas, new SqlCompanyFinanceRepository(db.dataSource()), sqlExecutor), messages, mainThread, creationRules::current, assetBindings, primaryOfferings);
        var companyCommand = getCommand("company");
        if (companyCommand == null) throw new IllegalStateException(missingCompanyCommandMessage());
        companyCommand.setExecutor(command);
        var adminCommand = getCommand("stockadmin");
        if (adminCommand == null) throw new IllegalStateException("BlockStock 命令注册失败：未在 plugin.yml 中声明 stockadmin 命令");
        var capitalizations = new CompanyCapitalizationService(finance, new SqlAuditLog(), db, escrow, mainThread, sqlExecutor, clock);
        int feeBps = getConfig().getInt("market.fee-bps");
        ZoneId marketZone = ZoneId.of(getConfig().getString("market.time-zone"));
        var publicRepository = new SqlPublicStockRepository(db.dataSource());
        var publicQueries = new cn.blockeco.exchange.application.PublicStockQueryService(publicRepository, sqlExecutor, Clock.systemUTC(), marketZone);
        var cashRepository = new SqlSecuritiesCashRepository(db.dataSource());
        var tradingRepository = new SqlSecondaryTradingRepository(db.dataSource(), cashRepository);
        var cashGateway = new VaultSecuritiesCashGateway(economy, mainThread, escrowId);
        secondaryTradingGate = new SecondaryTradingGate();
        var cashService = new SecuritiesCashService(cashRepository, db, cashGateway, sqlExecutor, clock, Duration.ofSeconds(15), secondaryTradingGate::mutationsOpen);
        var secondaryMarket = new SecondaryMarketService(tradingRepository, db, sqlExecutor, clock, feeBps);
        var secondaryQueries = new SecondaryMarketQueryService(tradingRepository, publicRepository, sqlExecutor, Clock.systemUTC(), marketZone);
        var secondaryRecovery = new SecondaryMarketRecoveryService(cashRepository, () -> {
            var legacy = new java.util.ArrayList<SecondaryMarketRecoveryService.LegacyRecoveryIssue>();
            finance.findAmbiguousCapitalizations().forEach(record -> legacy.add(new SecondaryMarketRecoveryService.LegacyRecoveryIssue(
                    "CAPITALIZATION", record.operation().id(), record.operation().amount(), record.operation().state().name(), "", record.reason())));
            ipoRepository.findAmbiguousSubscriptions().forEach(record -> legacy.add(new SecondaryMarketRecoveryService.LegacyRecoveryIssue(
                    "IPO", record.subscriptionId(), record.amount(), record.state().name(), "", record.reason())));
            return List.copyOf(legacy);
        }, sqlExecutor);
        runtime.attachFinancialQuiesce(cashService::quiesce);
        var adminConfig = new StockAdminConfigCommand(creationRules, new FileConfigStore(getConfig(), getDataFolder().toPath().resolve("config.yml")), new SqlAuditLog(), db, sqlExecutor, clock, messages, mainThread,
                () -> cashGateway.escrowBalance().thenCompose(secondaryRecovery::inspect));
        adminCommand.setExecutor(adminConfig); adminCommand.setTabCompleter(adminConfig);
        var symbols = new PublicStockSymbolCache();
        stockCommand = new StockCommand(publicQueries, primaryOfferings, cashService, secondaryMarket, secondaryQueries,
                mainThread, runtime::accepting, secondaryTradingGate::mutationsOpen, messages, scale);
        var stock = getCommand("stock");
        if (stock == null) throw new IllegalStateException("BlockStock 命令注册失败：未在 plugin.yml 中声明 stock 命令");
        stock.setExecutor(stockCommand); stock.setTabCompleter(new StockTabCompleter(symbols));
        new StartupRecoveryGate(failure -> getServer().getScheduler().runTask(this, () -> failEnable(startupFailureMessage(new IllegalStateException(failure))))).startFull(
                escrowFailure,
                capitalizations::recoverPendingCapitalizations,
                ignored -> primaryOfferings.recoverSubscriptionsAtStartup(),
                ignored -> cashService.recoverDurableFinalStages(),
                ignored -> cashGateway.escrowBalance().thenCompose(secondaryRecovery::inspect),
                snapshot -> attachStockAfterInitialRefresh(symbols, publicQueries, mainThread, runtime,
                        java.util.List.of(command, stockCommand, secondaryTradingGate), database,
                        refreshFailure -> failEnable(startupFailureMessage(new IllegalStateException("股票代码缓存初始化失败", refreshFailure))))
                        .thenRun(() -> secondaryTradingGate.setMutationsOpen(!snapshot.mutationsBlocked())),
                snapshot -> getServer().getScheduler().runTask(this, () -> {
                    registration.recoverStaleRegistrations(Instant.now().minus(Duration.ofMinutes(5))).whenComplete((count, staleFailure) ->
                            getServer().getScheduler().runTask(this, () -> getLogger().info("BlockStock ready; secondary trading="
                                    + (snapshot.mutationsBlocked() ? "maintenance" : "open") + "; stale registration records scanned="
                                    + (staleFailure == null ? count : "failed"))));
                    ipoLifecycle = new IpoLifecycleScheduler(task -> { var bukkitTask=getServer().getScheduler().runTaskTimerAsynchronously(this,task,20L,1200L); return bukkitTask::cancel; }, clock::now, primaryOfferings, failure -> getLogger().warning("IPO 状态调度失败，将在下个周期重试: " + failure.getMessage()), ignoredClose -> refreshSymbolsIfAccepting(runtime, symbols, publicQueries, error -> getLogger().warning("股票代码缓存刷新失败，将在下个周期重试: " + error.getMessage())));
                    ipoLifecycle.start();
                }));
        return true;
    }

    /** Takes ownership of the command before asynchronous migration begins. */
    private boolean installInitializingCommand() {
        var companyCommand = getCommand("company");
        if (companyCommand == null) { failEnable(missingCompanyCommandMessage()); return false; }
        Messages messages = new Messages(getConfig());
        companyCommand.setExecutor((sender, command, label, args) -> { sender.sendMessage(messages.initializing()); return runtime.accepting(); });
        companyCommand.setTabCompleter(new CompanyTabCompleter(creationRules));
        var stock = getCommand("stock");
        if (stock == null) { failEnable("BlockStock 命令注册失败：未在 plugin.yml 中声明 stock 命令"); return false; }
        stockCommand = new StockCommand(null, null, new PaperMainThread(this), runtime::accepting, messages);
        stock.setExecutor(stockCommand);
        stock.setTabCompleter(new StockTabCompleter(new PublicStockSymbolCache()));
        return true;
    }

    @Override public void onDisable() {
        if (ipoLifecycle != null) ipoLifecycle.stop();
        getServer().getServicesManager().unregisterAll(this);
        runtime.stop();
    }

    private void validateConfiguration() {
        int scale = getConfig().getInt("currency.scale", -1); if (scale < 0 || scale > 8) throw new IllegalArgumentException("currency.scale must be between 0 and 8");
        validateMarketConfiguration(getConfig().getInt("market.fee-bps", -1), getConfig().getString("market.time-zone"));
        positive("company.registration-fee", scale); positive("company.minimum-capital", scale);
        try { if (new java.util.UUID(0, 0).equals(java.util.UUID.fromString(getConfig().getString("company.treasury-escrow-uuid")))) throw new IllegalArgumentException("company.treasury-escrow-uuid must not be zero"); }
        catch (IllegalArgumentException failure) { throw new IllegalArgumentException("company.treasury-escrow-uuid must be a non-zero UUID", failure); }
        creationRules = new MutableCompanyCreationRules(new CompanyCreationRules(configuredMoney("company.registration-fee", scale), configuredMoney("company.minimum-capital", scale), scale, getConfig().getInt("company.initial-shares"), getConfig().getIntegerList("company.allowed-dividend-percent")));
    }
    private void positive(String path, int scale) { if (configuredMoney(path, scale).minorUnits() <= 0) throw new IllegalArgumentException(path + " must be positive"); }
    private Money configuredMoney(String path, int scale) { return Money.fromMajor(new BigDecimal(getConfig().getString(path)), scale); }
    static String startupFailureMessage(Throwable failure) { String detail = failure.getMessage(); return "BlockStock 启动失败：" + (detail == null || detail.isBlank() ? "启动过程中发生未知异常（" + failure.getClass().getSimpleName() + "）" : detail); }
    static ZoneId validateMarketConfiguration(int feeBps, String zoneId) {
        if (feeBps < 0 || feeBps > 10_000) throw new IllegalArgumentException("market.fee-bps must be between 0 and 10000");
        try { return ZoneId.of(zoneId); }
        catch (RuntimeException failure) { throw new IllegalArgumentException("market.time-zone must be a valid ZoneId", failure); }
    }
    static String configurationFailureMessage(Throwable failure) { String detail = failure.getMessage(); return "BlockStock 配置无效" + (detail == null || detail.isBlank() ? "" : "（附加信息：" + detail + "）"); }
    static String missingCompanyCommandMessage() { return "BlockStock 命令注册失败：未在 plugin.yml 中声明 company 命令"; }
    static CompletionStage<Void> attachStockAfterInitialRefresh(PublicStockSymbolCache cache, cn.blockeco.exchange.application.PublicStockQueryService queries, cn.blockeco.exchange.ports.MainThreadExecutor main, PluginRuntime runtime, java.util.List<? extends cn.blockeco.exchange.paper.CommandAcceptanceGate> gates, AutoCloseable database, Consumer<Throwable> failed) {
        return cache.refresh(queries).handle((ignored, error) -> main.<Void>submit(() -> { if (!runtime.accepting()) return null; if (error != null) { failed.accept(error); throw new java.util.concurrent.CompletionException(error); } runtime.attachReady(gates, database); return null; })).thenCompose(stage -> stage);
    }
    static void refreshSymbolsIfAccepting(PluginRuntime runtime, PublicStockSymbolCache cache, cn.blockeco.exchange.application.PublicStockQueryService queries, Consumer<Throwable> failed) { if (!runtime.accepting()) return; try { cache.refresh(queries).whenComplete((ignored, error) -> { if (error != null && runtime.accepting()) failed.accept(error); }); } catch (RuntimeException error) { if (runtime.accepting()) failed.accept(error); } }
    private void failEnable(String message) { getLogger().severe(message); getServer().getPluginManager().disablePlugin(this); }
}
