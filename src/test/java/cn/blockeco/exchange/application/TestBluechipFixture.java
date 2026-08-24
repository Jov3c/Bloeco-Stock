package cn.blockeco.exchange.application;

import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.infrastructure.sql.Database;
import cn.blockeco.exchange.infrastructure.sql.SqlBluechipRepository;
import cn.blockeco.exchange.infrastructure.sql.SqlBluechipBootstrapFundingRepository;
import cn.blockeco.exchange.infrastructure.sql.SqlCompanyRepository;
import cn.blockeco.exchange.infrastructure.sql.SqlSecuritiesCashRepository;
import cn.blockeco.exchange.infrastructure.sql.SqlStockListingRepository;
import cn.blockeco.exchange.paper.BluechipConfig;
import cn.blockeco.exchange.ports.BluechipRepository;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;

final class TestBluechipFixture {
    private static final UUID SYSTEM = UUID.fromString("00000000-0000-0000-0000-000000000099");
    private TestBluechipFixture() { }

    static Database migratedDatabase(Path file) throws Exception {
        var database = new Database("jdbc:sqlite:" + file);
        database.migrate();
        return database;
    }

    static void seed(Database database, SqlBluechipRepository repository, Instant now) {
        bootstrap(database, repository, config(), SYSTEM, now);
    }
    static void bootstrap(Database database, SqlBluechipRepository repository, BluechipConfig config, UUID system, Instant now) {
        var records = new SqlBluechipBootstrapFundingRepository(database.dataSource());
        var funding = new BluechipBootstrapFundingService(system, records, database, new BluechipBootstrapFundingService.EscrowEconomy() {
            @Override public cn.blockeco.exchange.ports.EconomyGateway.Result withdraw(UUID player, cn.blockeco.exchange.domain.money.Money amount) { return cn.blockeco.exchange.ports.EconomyGateway.Result.success("ok"); }
            @Override public cn.blockeco.exchange.ports.EconomyGateway.Result deposit(UUID player, cn.blockeco.exchange.domain.money.Money amount) { return cn.blockeco.exchange.ports.EconomyGateway.Result.success("ok"); }
            @Override public cn.blockeco.exchange.ports.EconomyGateway.Result depositEscrow(cn.blockeco.exchange.domain.money.Money amount) { return cn.blockeco.exchange.ports.EconomyGateway.Result.success("ok"); }
        }, new cn.blockeco.exchange.ports.MainThreadExecutor() { @Override public <T> java.util.concurrent.CompletionStage<T> submit(java.util.function.Supplier<T> work) { return java.util.concurrent.CompletableFuture.completedFuture(work.get()); } }, () -> now);
        new BluechipBootstrapService(config, system, new SqlCompanyRepository(database.dataSource()), new SqlStockListingRepository(database.dataSource()), repository,
                new SqlSecuritiesCashRepository(database.dataSource()), funding, records, database, Runnable::run, () -> now).initializeMissing().toCompletableFuture().join();
    }

    static UUID addExternalHolder(Database database, BluechipRepository.BluechipCompany company, long shares) {
        UUID holder = UUID.randomUUID();
        database.inTransaction(connection -> {
            try (PreparedStatement decrease = connection.prepareStatement("UPDATE share_holdings SET available_shares = available_shares - ? WHERE company_id = ? AND holder_uuid = ?");
                 PreparedStatement insert = connection.prepareStatement("INSERT INTO share_holdings (company_id, holder_uuid, available_shares, reserved_shares) VALUES (?, ?, ?, 0)")) {
                decrease.setLong(1, shares); decrease.setString(2, company.companyId().value().toString()); decrease.setString(3, SYSTEM.toString());
                if (decrease.executeUpdate() != 1) throw new AssertionError("system holding was not seeded");
                insert.setString(1, company.companyId().value().toString()); insert.setString(2, holder.toString()); insert.setLong(3, shares); insert.executeUpdate();
            }
            return null;
        });
        return holder;
    }

    static long securitiesCash(Database database, UUID account) {
        try (var connection = database.dataSource().getConnection(); var statement = connection.prepareStatement("SELECT available_minor FROM securities_cash_accounts WHERE player_uuid = ?")) {
            statement.setString(1, account.toString());
            try (var rows = statement.executeQuery()) { return rows.next() ? rows.getLong(1) : 0; }
        } catch (Exception exception) { throw new AssertionError(exception); }
    }

    static long count(Database database, String sql) {
        try (var connection = database.dataSource().getConnection(); var statement = connection.prepareStatement(sql); var rows = statement.executeQuery()) {
            return rows.next() ? rows.getLong(1) : 0;
        } catch (Exception exception) { throw new AssertionError(exception); }
    }

    static CompanyId createListedPlayerCompany(Database database, Instant listedAt, UUID holder, long retainedEarnings) {
        CompanyId id = new CompanyId(UUID.randomUUID());
        database.inTransaction(connection -> {
            try (PreparedStatement company = connection.prepareStatement("INSERT INTO companies (id, normalized_name, display_name, founder_uuid, status, treasury_minor, total_shares, dividend_basis_points, created_at) VALUES (?, ?, ?, ?, 'LISTED', 0, 1000, 5000, ?)");
                 PreparedStatement listing = connection.prepareStatement("INSERT INTO stock_listings (company_id, stock_code, issue_reference_price_minor, issued_shares, listed_at) VALUES (?, 'BS999999', 100, 1000, ?)");
                 PreparedStatement cash = connection.prepareStatement("INSERT INTO company_cash_accounts (company_id, cash_minor, paid_in_capital_minor, retained_earnings_minor, reserved_minor) VALUES (?, ?, 0, ?, 0)");
                 PreparedStatement holding = connection.prepareStatement("INSERT INTO share_holdings (company_id, holder_uuid, available_shares, reserved_shares) VALUES (?, ?, 1000, 0)")) {
                company.setString(1, id.value().toString()); company.setString(2, "dividend-player-" + id.value()); company.setString(3, "Dividend Player"); company.setString(4, UUID.randomUUID().toString()); company.setString(5, listedAt.toString()); company.executeUpdate();
                listing.setString(1, id.value().toString()); listing.setString(2, listedAt.toString()); listing.executeUpdate();
                cash.setString(1, id.value().toString()); cash.setLong(2, retainedEarnings); cash.setLong(3, retainedEarnings); cash.executeUpdate();
                holding.setString(1, id.value().toString()); holding.setString(2, holder.toString()); holding.executeUpdate();
            }
            return null;
        });
        return id;
    }

    static long companyCash(Database database, CompanyId company) { return financeValue(database, company, "cash_minor"); }
    static long retainedEarnings(Database database, CompanyId company) { return financeValue(database, company, "retained_earnings_minor"); }
    private static long financeValue(Database database, CompanyId company, String column) {
        try (var connection = database.dataSource().getConnection(); var statement = connection.prepareStatement("SELECT " + column + " FROM company_cash_accounts WHERE company_id = ?")) {
            statement.setString(1, company.value().toString()); try (var rows = statement.executeQuery()) { return rows.next() ? rows.getLong(1) : 0; }
        } catch (Exception exception) { throw new AssertionError(exception); }
    }

    private static BluechipConfig config() {
        YamlConfiguration yaml = new YamlConfiguration();
        List<Map<String, Object>> entries = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("code", "BC" + index); entry.put("display-name", "System Company " + index); entry.put("industry", "Industry " + index);
            entry.put("reference-price", "10.00"); entry.put("lower-bound", "8.00"); entry.put("upper-bound", "12.00"); entry.put("total-shares", 1_000_000L);
            entry.put("initial-fund-cash", "100000.00"); entry.put("initial-fund-shares", 100_000L); entry.put("spread-bps", 50); entry.put("event-sensitivity-bps", 100); entry.put("dividend-payout-bps", 2_000);
            entries.add(entry);
        }
        yaml.set("bluechips", entries);
        return BluechipConfig.load(yaml, 2);
    }
}
