package cn.blockeco.exchange.application;

import static org.assertj.core.api.Assertions.assertThat;

import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.market.MarketSession;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.infrastructure.sql.Database;
import cn.blockeco.exchange.infrastructure.sql.SqlBluechipRepository;
import cn.blockeco.exchange.infrastructure.sql.SqlSecuritiesCashRepository;
import cn.blockeco.exchange.infrastructure.sql.SqlSecondaryTradingRepository;
import cn.blockeco.exchange.ports.BluechipRepository;
import java.nio.file.Files;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BluechipMarketMakerServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-24T09:00:00Z");

    @Test
    void openSessionCreatesAtMostFiveBidAndFiveAskOrdersPerBluechip() throws Exception {
        var file = Files.createTempFile("blockstock-maker-", ".db");
        try (var database = new Database("jdbc:sqlite:" + file)) {
            database.migrate();
            var fixture = fixture(database);

            BluechipMarketMakerService.QuoteRefreshResult result = fixture.maker.refreshQuotes().toCompletableFuture().join();

            assertThat(systemOrders(database, fixture.bluechip, "BUY")).hasSize(5);
            assertThat(systemOrders(database, fixture.bluechip, "SELL")).hasSize(5);
            assertThat(result.liquidityDegradedStockCodes()).isEmpty();
        } finally { Files.deleteIfExists(file); }
    }

    @Test
    void emptyFundCashRemovesBidsWithoutCreatingMoneyAndRecordsProtectionChange() throws Exception {
        var file = Files.createTempFile("blockstock-maker-empty-cash-", ".db");
        try (var database = new Database("jdbc:sqlite:" + file)) {
            database.migrate();
            var fixture = fixture(database);
            fixture.maker.refreshQuotes().toCompletableFuture().join();
            fixture.maker.cancelSystemQuotesAtClose().toCompletableFuture().join();
            setFundCash(database, fixture.bluechip.systemAccountId(), Money.zero());

            var result = fixture.maker.refreshQuotes().toCompletableFuture().join();

            assertThat(systemOrders(database, fixture.bluechip, "BUY")).isEmpty();
            assertThat(fixture.market.availableCash(fixture.bluechip.systemAccountId())).isEqualTo(Money.zero());
            assertThat(result.liquidityDegradedStockCodes()).contains(fixture.bluechip.listing().stockCode());
            assertThat(auditEvents(database, fixture.bluechip.companyId(), "BLUECHIP_LIQUIDITY_DEGRADED")).isEqualTo(1);
        } finally { Files.deleteIfExists(file); }
    }

    @Test
    void refreshAndCloseCancelOnlySystemQuotesAndLeavePlayerGtcOrderOpen() throws Exception {
        var file = Files.createTempFile("blockstock-maker-player-", ".db");
        try (var database = new Database("jdbc:sqlite:" + file)) {
            database.migrate();
            var fixture = fixture(database);
            UUID player = UUID.randomUUID();
            var cash = new SqlSecuritiesCashRepository(database.dataSource());
            database.inTransaction(connection -> { cash.creditAvailable(connection, player, Money.ofMinor(100), NOW); return null; });
            var playerOrder = fixture.market.placeBuy(player, fixture.bluechip.listing().stockCode(), 1, Money.ofMinor(1)).toCompletableFuture().join().order();
            fixture.maker.refreshQuotes().toCompletableFuture().join();

            assertThat(fixture.maker.cancelSystemQuotesAtClose().toCompletableFuture().join()).isEqualTo(100);

            assertThat(systemOrders(database, fixture.bluechip, "BUY")).isEmpty();
            assertThat(systemOrders(database, fixture.bluechip, "SELL")).isEmpty();
            assertThat(fixture.orders.findOrder(playerOrder.id()).orElseThrow().state().name()).isEqualTo("OPEN");
        } finally { Files.deleteIfExists(file); }
    }

    private static Fixture fixture(Database database) {
        var repository = new SqlBluechipRepository(database.dataSource());
        var bootstrap = new BluechipBootstrapServiceTestSupport(database, repository, NOW);
        bootstrap.initializeOne();
        BluechipRepository.BluechipCompany bluechip = repository.all().getFirst();
        var cash = new SqlSecuritiesCashRepository(database.dataSource());
        var orders = new SqlSecondaryTradingRepository(database.dataSource(), cash);
        var market = new SecondaryMarketService(orders, database, Runnable::run, () -> NOW, 100, () -> new MarketSession(true));
        return new Fixture(repository, orders, bluechip, market,
                new BluechipMarketMakerService(repository, market, () -> new MarketSession(true), () -> NOW));
    }

    private static java.util.List<String> systemOrders(Database database, BluechipRepository.BluechipCompany bluechip, String side) {
        try (var connection = database.dataSource().getConnection(); var statement = connection.prepareStatement("SELECT id FROM stock_orders WHERE player_uuid = ? AND stock_code = ? AND side = ? AND state IN ('OPEN', 'PARTIALLY_FILLED')")) {
            statement.setString(1, bluechip.systemAccountId().toString()); statement.setString(2, bluechip.listing().stockCode()); statement.setString(3, side);
            try (var rows = statement.executeQuery()) { var ids = new java.util.ArrayList<String>(); while (rows.next()) ids.add(rows.getString(1)); return ids; }
        } catch (Exception exception) { throw new AssertionError(exception); }
    }

    private static void setFundCash(Database database, UUID system, Money amount) {
        database.inTransaction(connection -> { try (var statement = connection.prepareStatement("UPDATE securities_cash_accounts SET available_minor = ?, reserved_minor = 0 WHERE player_uuid = ?")) { statement.setLong(1, amount.minorUnits()); statement.setString(2, system.toString()); statement.executeUpdate(); } return null; });
    }

    private static long auditEvents(Database database, CompanyId company, String type) {
        try (var connection = database.dataSource().getConnection(); var statement = connection.prepareStatement("SELECT COUNT(*) FROM audit_events WHERE company_id = ? AND event_type = ?")) {
            statement.setString(1, company.value().toString()); statement.setString(2, type); try (var rows = statement.executeQuery()) { rows.next(); return rows.getLong(1); }
        } catch (Exception exception) { throw new AssertionError(exception); }
    }

    private record Fixture(SqlBluechipRepository repository, SqlSecondaryTradingRepository orders, BluechipRepository.BluechipCompany bluechip,
                           SecondaryMarketService market, BluechipMarketMakerService maker) { }
}
