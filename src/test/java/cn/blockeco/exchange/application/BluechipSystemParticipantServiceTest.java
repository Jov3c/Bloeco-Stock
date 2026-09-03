package cn.blockeco.exchange.application;

import static org.assertj.core.api.Assertions.assertThat;

import cn.blockeco.exchange.domain.market.MarketSession;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.infrastructure.sql.Database;
import cn.blockeco.exchange.infrastructure.sql.SqlBluechipParticipantRepository;
import cn.blockeco.exchange.infrastructure.sql.SqlBluechipRepository;
import cn.blockeco.exchange.infrastructure.sql.SqlSecuritiesCashRepository;
import cn.blockeco.exchange.infrastructure.sql.SqlSecondaryTradingRepository;
import java.nio.file.Files;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class BluechipSystemParticipantServiceTest {
    private static final UUID MAKER = UUID.fromString("00000000-0000-0000-0000-000000000099");
    private static final UUID PARTICIPANT = UUID.fromString("00000000-0000-0000-0000-000000000077");
    private static final Instant OPEN = Instant.parse("2026-08-24T09:00:00Z");

    @Test
    void activity_selection_advances_every_eight_seconds() {
        assertThat(BluechipSystemParticipantService.activityStep(OPEN)).isNotEqualTo(
                BluechipSystemParticipantService.activityStep(OPEN.plusSeconds(8)));
    }

    @Test
    void openTickPlacesOneBoundedOrderFromDistinctParticipantThroughTheRealSettlementPath() throws Exception {
        try (Fixture fixture = Fixture.open(true)) {
            assertThat(fixture.participant.tick().toCompletableFuture().join()).isEqualTo(1);
            assertThat(fixture.orders.trades(PARTICIPANT, 10)).hasSize(1);
            assertThat(fixture.orders.trades(PARTICIPANT, 10).getFirst().shares()).isBetween(1L, 10L);
            assertThat(fixture.orders.trades(PARTICIPANT, 10).getFirst().side().name()).isIn("BUY", "SELL");
            assertThat(fixture.tradeOwners()).containsExactlyInAnyOrder(MAKER, PARTICIPANT);
        }
    }

    @Test
    void closedOrUnfundedParticipantPlacesNothing() throws Exception {
        try (Fixture closed = Fixture.closed()) {
            assertThat(closed.participant.tick().toCompletableFuture().join()).isZero();
            assertThat(closed.orders.trades(PARTICIPANT, 10)).isEmpty();
        }
        try (Fixture unfunded = Fixture.open(false)) {
            assertThat(unfunded.participant.tick().toCompletableFuture().join()).isZero();
            assertThat(unfunded.orders.trades(PARTICIPANT, 10)).isEmpty();
        }
    }

    private static final class Fixture implements AutoCloseable {
        final java.nio.file.Path file;
        final Database database;
        final SqlSecondaryTradingRepository orders;
        final BluechipSystemParticipantService participant;

        private Fixture(java.nio.file.Path file, Database database, SqlSecondaryTradingRepository orders, BluechipSystemParticipantService participant) {
            this.file = file; this.database = database; this.orders = orders; this.participant = participant;
        }

        static Fixture open(boolean fundParticipant) throws Exception { return create(new MarketSession(true), fundParticipant); }
        static Fixture closed() throws Exception { return create(new MarketSession(false), false); }

        private static Fixture create(MarketSession initialSession, boolean fundParticipant) throws Exception {
            var file = Files.createTempFile("blockstock-system-participant-", ".db");
            var database = new Database("jdbc:sqlite:" + file);
            database.migrate();
            var bluechips = new SqlBluechipRepository(database.dataSource());
            new BluechipBootstrapServiceTestSupport(database, bluechips, OPEN).initializeOne();
            var cash = new SqlSecuritiesCashRepository(database.dataSource());
            var orders = new SqlSecondaryTradingRepository(database.dataSource(), cash);
            var positions = bluechips.all();
            if (fundParticipant) database.inTransaction(connection -> {
                new SqlBluechipParticipantRepository().allocateOnce(connection, MAKER, PARTICIPANT, Money.ofMinor(10_000), 20, positions);
                return null;
            });
            var session = new AtomicReference<>(initialSession);
            var market = new SecondaryMarketService(orders, database, Runnable::run, () -> OPEN, 10, session::get);
            var maker = new BluechipMarketMakerService(bluechips, market, session::get, () -> OPEN);
            if (initialSession.acceptsMatching()) maker.refreshQuotes().toCompletableFuture().join();
            return new Fixture(file, database, orders, new BluechipSystemParticipantService(bluechips, orders, market, session::get, () -> OPEN, PARTICIPANT));
        }

        @Override public void close() throws Exception { database.close(); Files.deleteIfExists(file); }
        java.util.List<UUID> tradeOwners() {
            try (var connection = database.dataSource().getConnection(); var statement = connection.prepareStatement(
                    "SELECT buy_order.player_uuid, sell_order.player_uuid FROM stock_trades trade JOIN stock_orders buy_order ON buy_order.id = trade.buy_order_id JOIN stock_orders sell_order ON sell_order.id = trade.sell_order_id");
                 var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue(); return java.util.List.of(UUID.fromString(rows.getString(1)), UUID.fromString(rows.getString(2)));
            } catch (Exception exception) { throw new AssertionError(exception); }
        }
    }
}
