package cn.blockeco.exchange.application;

import static org.assertj.core.api.Assertions.assertThat;

import cn.blockeco.exchange.domain.market.MarketSession;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.infrastructure.sql.Database;
import cn.blockeco.exchange.infrastructure.sql.SqlBluechipParticipantRepository;
import cn.blockeco.exchange.infrastructure.sql.SqlBluechipRepository;
import cn.blockeco.exchange.infrastructure.sql.SqlSecuritiesCashRepository;
import cn.blockeco.exchange.infrastructure.sql.SqlSecondaryTradingRepository;
import cn.blockeco.exchange.paper.BluechipQuantConfig;
import java.nio.file.Files;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class BluechipQuantStrategyServiceTest {
    private static final UUID MAKER = UUID.fromString("00000000-0000-0000-0000-000000000099");
    private static final UUID PARTICIPANT = UUID.fromString("00000000-0000-0000-0000-000000000077");
    private static final UUID BOOK_BUYER = UUID.fromString("00000000-0000-0000-0000-000000000055");
    private static final Instant OPEN = Instant.parse("2026-08-31T09:00:00Z");

    @Test
    void activityStepAdvancesEverySecond() {
        assertThat(BluechipQuantStrategyService.activityStep(OPEN))
                .isNotEqualTo(BluechipQuantStrategyService.activityStep(OPEN.plusSeconds(1)));
    }

    @Test
    void oneStrategyBucketUsesOneFiniteOrdinaryOrderAndLeavesCompensationFundUntouched() throws Exception {
        try (Fixture fixture = Fixture.open()) {
            long initialFund = fixture.orders.compensationFund().minorUnits();

            int placed = fixture.strategy.tick().toCompletableFuture().join()
                    + fixture.strategy.tick().toCompletableFuture().join();

            assertThat(placed).isEqualTo(1);
            assertThat(fixture.orders.trades(PARTICIPANT, 10)).hasSize(1);
            assertThat(fixture.quantDecisionCount()).isEqualTo(1);
            assertThat(fixture.orders.compensationFund().minorUnits()).isGreaterThanOrEqualTo(initialFund);
            assertThat(fixture.market.availableCash(PARTICIPANT).minorUnits()).isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    void depletedSystemParticipantRegainsOperatingLiquidityBeforeItsNextQuantDecision() throws Exception {
        try (Fixture fixture = Fixture.open()) {
            fixture.depleteParticipant();

            int placed = fixture.strategy.tick().toCompletableFuture().join();

            assertThat(placed).isBetween(0, 1);
            assertThat(fixture.market.availableCash(PARTICIPANT).minorUnits()).isGreaterThan(0);
            assertThat(fixture.participantShares()).isGreaterThan(0L);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final java.nio.file.Path file; final Database database; final SqlBluechipRepository bluechips;
        final SqlSecondaryTradingRepository orders; final SecondaryMarketService market; final BluechipQuantStrategyService strategy;

        private Fixture(java.nio.file.Path file, Database database, SqlBluechipRepository bluechips, SqlSecondaryTradingRepository orders,
                        SecondaryMarketService market, BluechipQuantStrategyService strategy) {
            this.file = file; this.database = database; this.bluechips = bluechips; this.orders = orders; this.market = market; this.strategy = strategy;
        }

        static Fixture open() throws Exception {
            var file = Files.createTempFile("blockstock-quant-strategy-", ".db");
            var database = new Database("jdbc:sqlite:" + file); database.migrate();
            var bluechips = new SqlBluechipRepository(database.dataSource());
            new BluechipBootstrapServiceTestSupport(database, bluechips, OPEN).initializeOne();
            var cash = new SqlSecuritiesCashRepository(database.dataSource());
            var orders = new SqlSecondaryTradingRepository(database.dataSource(), cash);
            var session = new AtomicReference<>(new MarketSession(true));
            var market = new SecondaryMarketService(orders, database, Runnable::run, () -> OPEN, 10, session::get);
            var seededBluechips = bluechips.all();
            database.inTransaction(connection -> {
                new SqlBluechipParticipantRepository().allocateOnce(connection, MAKER, PARTICIPANT, Money.ofMinor(1_000_000), 20, seededBluechips);
                cash.creditAvailable(connection, BOOK_BUYER, Money.ofMinor(10_000_000), OPEN);
                return null;
            });
            new BluechipMarketMakerService(bluechips, market, session::get, () -> OPEN).refreshQuotes().toCompletableFuture().join();
            for (var bluechip : seededBluechips) {
                market.placeBuy(BOOK_BUYER, bluechip.listing().stockCode(), 900, Money.ofMinor(1_000)).toCompletableFuture().join();
            }
            var strategy = new BluechipQuantStrategyService(bluechips, orders, market, database, Runnable::run, session::get, () -> OPEN,
                    PARTICIPANT, new SqlBluechipParticipantRepository(), new BluechipQuantConfig(6_500, 200, 120), new QuantSignalPolicy(), new QuantRiskPolicy(200, 120));
            return new Fixture(file, database, bluechips, orders, market, strategy);
        }

        @Override public void close() throws Exception { database.close(); Files.deleteIfExists(file); }
        int quantDecisionCount() { return bluechips.all().stream().mapToInt(bluechip -> bluechips.quantDecisions(bluechip.listing().stockCode(), 10).size()).sum(); }
        void depleteParticipant() {
            database.inTransaction(connection -> {
                try (var cash = connection.prepareStatement("UPDATE securities_cash_accounts SET available_minor = 0, reserved_minor = 0 WHERE player_uuid = ?");
                     var shares = connection.prepareStatement("UPDATE share_holdings SET available_shares = 0, reserved_shares = 0 WHERE holder_uuid = ?")) {
                    cash.setString(1, PARTICIPANT.toString()); cash.executeUpdate();
                    shares.setString(1, PARTICIPANT.toString()); shares.executeUpdate();
                }
                return null;
            });
        }
        long participantShares() {
            try (var connection = database.dataSource().getConnection(); var statement = connection.prepareStatement("SELECT COALESCE(SUM(available_shares), 0) FROM share_holdings WHERE holder_uuid = ?")) {
                statement.setString(1, PARTICIPANT.toString());
                try (var rows = statement.executeQuery()) { rows.next(); return rows.getLong(1); }
            } catch (Exception exception) { throw new AssertionError(exception); }
        }
    }
}
