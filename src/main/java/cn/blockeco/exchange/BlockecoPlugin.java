package cn.blockeco.exchange;

import cn.blockeco.exchange.application.CompanyQueryService;
import cn.blockeco.exchange.application.CompanyRegistrationService;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.infrastructure.sql.Database;
import cn.blockeco.exchange.infrastructure.sql.SqlAuditLog;
import cn.blockeco.exchange.infrastructure.sql.SqlCompanyRepository;
import cn.blockeco.exchange.infrastructure.sql.SqlRegistrationSagaRepository;
import cn.blockeco.exchange.infrastructure.vault.VaultEconomyGateway;
import cn.blockeco.exchange.paper.CompanyCommand;
import cn.blockeco.exchange.paper.Messages;
import cn.blockeco.exchange.paper.PaperMainThread;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.plugin.java.JavaPlugin;

public final class BlockecoPlugin extends JavaPlugin {
    private ExecutorService sqlExecutor;
    private Database database;
    private CompanyCommand command;

    @Override public void onEnable() {
        saveDefaultConfig();
        installInitializingCommand();
        try { validateConfiguration(); if (!getDataFolder().exists() && !getDataFolder().mkdirs()) throw new IllegalStateException("cannot create plugin data folder"); }
        catch (RuntimeException failure) { failEnable("Invalid Blockeco configuration: " + failure.getMessage()); return; }
        sqlExecutor = Executors.newSingleThreadExecutor(r -> { Thread thread = new Thread(r, "Blockeco-SQL"); thread.setDaemon(true); return thread; });
        Path file = getDataFolder().toPath().resolve(getConfig().getString("database.file", "blockeco.db"));
        java.util.concurrent.CompletableFuture.supplyAsync(() -> { Database db = new Database("jdbc:sqlite:" + file); try { db.migrate(); return db; } catch (Exception e) { db.close(); throw new IllegalStateException("SQLite migration failed", e); } }, sqlExecutor)
                .whenComplete((db, failure) -> getServer().getScheduler().runTask(this, () -> finishEnable(db, failure)));
    }

    private void finishEnable(Database db, Throwable failure) {
        if (failure != null) { failEnable("Blockeco initialization failed: " + failure.getMessage()); return; }
        var economyRegistration = getServer().getServicesManager().getRegistration(Economy.class);
        if (economyRegistration == null || economyRegistration.getProvider() == null) { db.close(); failEnable("Vault economy provider is unavailable"); return; }
        database = db;
        var companies = new SqlCompanyRepository(db.dataSource()); var sagas = new SqlRegistrationSagaRepository(db.dataSource());
        var mainThread = new PaperMainThread(this);
        int scale = getConfig().getInt("currency.scale");
        var registration = new CompanyRegistrationService(companies, sagas, new SqlAuditLog(), db, new VaultEconomyGateway(getServer(), scale), mainThread, sqlExecutor, Instant::now, configuredMoney("company.registration-fee", scale), configuredMoney("company.minimum-capital", scale));
        command = new CompanyCommand(registration, new CompanyQueryService(companies, sagas, sqlExecutor), new Messages(getConfig().getConfigurationSection("messages")), this);
        var companyCommand = getCommand("company");
        if (companyCommand == null) { database.close(); failEnable("company command is missing from plugin.yml"); return; }
        companyCommand.setExecutor(command);
        command.setAccepting(true);
        registration.recoverStaleRegistrations(Instant.now().minus(Duration.ofMinutes(5))).whenComplete((count, recoveryFailure) -> getServer().getScheduler().runTask(this, () -> getLogger().info("Blockeco ready; stale registration records scanned=" + (recoveryFailure == null ? count : "failed"))));
    }

    /** Takes ownership of the command before asynchronous migration begins. */
    private void installInitializingCommand() {
        var companyCommand = getCommand("company");
        if (companyCommand == null) { failEnable("company command is missing from plugin.yml"); return; }
        Messages messages = new Messages(getConfig().getConfigurationSection("messages"));
        companyCommand.setExecutor((sender, command, label, args) -> { sender.sendMessage(messages.initializing()); return true; });
    }

    @Override public void onDisable() {
        if (command != null) command.setAccepting(false);
        if (sqlExecutor != null) { sqlExecutor.shutdown(); try { sqlExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
        if (database != null) database.close();
    }

    private void validateConfiguration() {
        int scale = getConfig().getInt("currency.scale", -1); if (scale < 0 || scale > 8) throw new IllegalArgumentException("currency.scale must be between 0 and 8");
        positive("company.registration-fee", scale); positive("company.minimum-capital", scale);
    }
    private void positive(String path, int scale) { if (configuredMoney(path, scale).minorUnits() <= 0) throw new IllegalArgumentException(path + " must be positive"); }
    private Money configuredMoney(String path, int scale) { return Money.fromMajor(new BigDecimal(getConfig().getString(path)), scale); }
    private void failEnable(String message) { getLogger().severe(message); getServer().getPluginManager().disablePlugin(this); }
}
