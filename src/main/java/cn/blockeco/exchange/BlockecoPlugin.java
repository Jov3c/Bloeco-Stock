package cn.blockeco.exchange;

import cn.blockeco.exchange.application.CompanyQueryService;
import cn.blockeco.exchange.application.CompanyRegistrationService;
import cn.blockeco.exchange.application.CompanyCapitalizationService;
import cn.blockeco.exchange.application.AssetBindingService;
import cn.blockeco.exchange.application.PrimaryOfferingService;
import cn.blockeco.exchange.application.IpoLifecycleScheduler;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.infrastructure.sql.Database;
import cn.blockeco.exchange.infrastructure.sql.SqlAuditLog;
import cn.blockeco.exchange.infrastructure.sql.SqlCompanyRepository;
import cn.blockeco.exchange.infrastructure.sql.SqlCompanyFinanceRepository;
import cn.blockeco.exchange.infrastructure.sql.SqlRegistrationSagaRepository;
import cn.blockeco.exchange.infrastructure.sql.SqlAssetBindingRepository;
import cn.blockeco.exchange.infrastructure.sql.SqlPrimaryOfferingRepository;
import cn.blockeco.exchange.infrastructure.vault.VaultEconomyGateway;
import cn.blockeco.exchange.infrastructure.vault.VaultTreasuryEscrowGateway;
import cn.blockeco.exchange.paper.CompanyCommand;
import cn.blockeco.exchange.paper.CompanyCreationRules;
import cn.blockeco.exchange.paper.CompanyTabCompleter;
import cn.blockeco.exchange.paper.Messages;
import cn.blockeco.exchange.paper.PaperMainThread;
import cn.blockeco.exchange.paper.MigrationResult;
import cn.blockeco.exchange.paper.PluginDataDirectoryMigrator;
import cn.blockeco.exchange.ports.AppClock;
import cn.blockeco.exchange.ports.CompanyAssetAdapterRegistry;
import cn.blockeco.exchange.infrastructure.CompanyAssetAdapterRegistryImpl;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.ServicePriority;

public final class BlockecoPlugin extends JavaPlugin {
    private ExecutorService sqlExecutor;
    private Database database;
    private CompanyCommand command;
    private BootstrapCoordinator<Database> bootstrap;
    private CompanyCreationRules creationRules;
    private final PluginRuntime runtime = new PluginRuntime();
    private final CompanyAssetAdapterRegistry assetAdapterRegistry = new CompanyAssetAdapterRegistryImpl();
    private IpoLifecycleScheduler ipoLifecycle;

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
        catch (RuntimeException failure) { failEnable("Invalid BlockStock configuration: " + failure.getMessage()); return; }
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
        var registration = new CompanyRegistrationService(companies, sagas, new SqlAuditLog(), db, economy, mainThread, sqlExecutor, clock, configuredMoney("company.registration-fee", scale), configuredMoney("company.minimum-capital", scale), finance, escrow, creationRules.initialShares());
        getServer().getServicesManager().register(CompanyAssetAdapterRegistry.class, assetAdapterRegistry, this, ServicePriority.Normal);
        var assetBindings = new AssetBindingService(new SqlAssetBindingRepository(db.dataSource()), db, () -> {
            CompanyAssetAdapterRegistry registry = getServer().getServicesManager().load(CompanyAssetAdapterRegistry.class);
            return registry == null ? java.util.List.of() : registry.snapshot();
        }, clock);
        var primaryOfferings = new PrimaryOfferingService(new SqlPrimaryOfferingRepository(db.dataSource()), db, escrow, sqlExecutor, clock);
        command = new CompanyCommand(registration, new CompanyQueryService(companies, sagas, new SqlCompanyFinanceRepository(db.dataSource()), sqlExecutor), new Messages(getConfig().getConfigurationSection("messages")), mainThread, creationRules, assetBindings, primaryOfferings);
        var companyCommand = getCommand("company");
        if (companyCommand == null) throw new IllegalStateException("company command is missing from plugin.yml");
        companyCommand.setExecutor(command);
        var capitalizations = new CompanyCapitalizationService(finance, new SqlAuditLog(), db, escrow, mainThread, sqlExecutor, clock);
        new StartupRecoveryGate(failure -> getServer().getScheduler().runTask(this, () -> failEnable(startupFailureMessage(new IllegalStateException(failure))))).start(escrowFailure, capitalizations::recoverPendingCapitalizations, recovered -> getServer().getScheduler().runTask(this, () -> {
            if (!runtime.attachReady(command, database)) return;
            registration.recoverStaleRegistrations(Instant.now().minus(Duration.ofMinutes(5))).whenComplete((count, staleFailure) -> getServer().getScheduler().runTask(this, () -> getLogger().info("BlockStock ready; legacy capitalizations recovered=" + recovered + "; stale registration records scanned=" + (staleFailure == null ? count : "failed"))));
            ipoLifecycle = new IpoLifecycleScheduler(task -> { var bukkitTask=getServer().getScheduler().runTaskTimerAsynchronously(this,task,20L,1200L); return bukkitTask::cancel; }, clock::now, primaryOfferings, failure -> getLogger().warning("IPO 状态调度失败，将在下个周期重试: " + failure.getMessage())); ipoLifecycle.start();
        }));
        return true;
    }

    /** Takes ownership of the command before asynchronous migration begins. */
    private boolean installInitializingCommand() {
        var companyCommand = getCommand("company");
        if (companyCommand == null) { failEnable("company command is missing from plugin.yml"); return false; }
        Messages messages = new Messages(getConfig().getConfigurationSection("messages"));
        companyCommand.setExecutor((sender, command, label, args) -> { sender.sendMessage(messages.initializing()); return runtime.accepting(); });
        companyCommand.setTabCompleter(new CompanyTabCompleter(creationRules));
        return true;
    }

    @Override public void onDisable() {
        if (ipoLifecycle != null) ipoLifecycle.stop();
        getServer().getServicesManager().unregisterAll(this);
        runtime.stop();
    }

    private void validateConfiguration() {
        int scale = getConfig().getInt("currency.scale", -1); if (scale < 0 || scale > 8) throw new IllegalArgumentException("currency.scale must be between 0 and 8");
        positive("company.registration-fee", scale); positive("company.minimum-capital", scale);
        try { if (new java.util.UUID(0, 0).equals(java.util.UUID.fromString(getConfig().getString("company.treasury-escrow-uuid")))) throw new IllegalArgumentException("company.treasury-escrow-uuid must not be zero"); }
        catch (IllegalArgumentException failure) { throw new IllegalArgumentException("company.treasury-escrow-uuid must be a non-zero UUID", failure); }
        creationRules = new CompanyCreationRules(configuredMoney("company.registration-fee", scale), configuredMoney("company.minimum-capital", scale), scale, getConfig().getInt("company.initial-shares"), getConfig().getIntegerList("company.allowed-dividend-percent"));
    }
    private void positive(String path, int scale) { if (configuredMoney(path, scale).minorUnits() <= 0) throw new IllegalArgumentException(path + " must be positive"); }
    private Money configuredMoney(String path, int scale) { return Money.fromMajor(new BigDecimal(getConfig().getString(path)), scale); }
    static String startupFailureMessage(Throwable failure) { String detail = failure.getMessage(); return "BlockStock 启动失败：" + (detail == null || detail.isBlank() ? "启动过程中发生未知异常（" + failure.getClass().getSimpleName() + "）" : detail); }
    private void failEnable(String message) { getLogger().severe(message); getServer().getPluginManager().disablePlugin(this); }
}
