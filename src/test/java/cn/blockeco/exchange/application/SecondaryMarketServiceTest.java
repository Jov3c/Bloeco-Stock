package cn.blockeco.exchange.application;

import static org.assertj.core.api.Assertions.assertThat;

import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.domain.trading.LimitOrder;
import cn.blockeco.exchange.infrastructure.sql.Database;
import cn.blockeco.exchange.infrastructure.sql.SqlSecuritiesCashRepository;
import cn.blockeco.exchange.infrastructure.sql.SqlSecondaryTradingRepository;
import java.nio.file.Files;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SecondaryMarketServiceTest {
    @Test
    void matches_at_maker_price_in_price_time_order_even_when_timestamps_are_equal() throws Exception {
        var file = Files.createTempFile("blockstock-matcher-", ".db");
        try (var db = new Database("jdbc:sqlite:" + file)) {
            db.migrate();
            CompanyId company = Fixtures.company(db, 100);
            UUID sellerOne = UUID.randomUUID(), sellerTwo = UUID.randomUUID(), buyer = UUID.randomUUID();
            seedListing(db, company); seedHolding(db, company, sellerOne, 10); seedHolding(db, company, sellerTwo, 10);
            var cash = new SqlSecuritiesCashRepository(db.dataSource());
            db.inTransaction(c -> { cash.creditAvailable(c, buyer, Money.ofMinor(1_000), Instant.EPOCH); return null; });
            var repository = new SqlSecondaryTradingRepository(db.dataSource(), cash);
            var service = new SecondaryMarketService(repository, db, Runnable::run, () -> Instant.EPOCH, 100);

            UUID first = service.placeSell(sellerOne, "BS000001", 10, Money.ofMinor(9)).toCompletableFuture().join().order().id();
            UUID second = service.placeSell(sellerTwo, "BS000001", 10, Money.ofMinor(9)).toCompletableFuture().join().order().id();
            var result = service.placeBuy(buyer, "BS000001", 10, Money.ofMinor(10)).toCompletableFuture().join();

            assertThat(result.order().state()).isEqualTo(LimitOrder.State.FILLED);
            assertThat(repository.findOrder(first).orElseThrow().state()).isEqualTo(LimitOrder.State.FILLED);
            assertThat(repository.findOrder(second).orElseThrow().state()).isEqualTo(LimitOrder.State.OPEN);
            assertThat(cash.find(sellerOne).orElseThrow().available()).isEqualTo(Money.ofMinor(90));
        } finally { Files.deleteIfExists(file); }
    }

    @Test
    void self_cross_cancels_only_taker_remaining_and_leaves_maker_open() throws Exception {
        var file = Files.createTempFile("blockstock-self-trade-", ".db");
        try (var db = new Database("jdbc:sqlite:" + file)) {
            db.migrate(); CompanyId company = Fixtures.company(db, 100); UUID player = UUID.randomUUID();
            seedListing(db, company); seedHolding(db, company, player, 20);
            var cash = new SqlSecuritiesCashRepository(db.dataSource());
            db.inTransaction(c -> { cash.creditAvailable(c, player, Money.ofMinor(1_000), Instant.EPOCH); return null; });
            var repository = new SqlSecondaryTradingRepository(db.dataSource(), cash);
            var service = new SecondaryMarketService(repository, db, Runnable::run, () -> Instant.EPOCH, 0);
            UUID maker = service.placeSell(player, "BS000001", 10, Money.ofMinor(10)).toCompletableFuture().join().order().id();
            var taker = service.placeBuy(player, "BS000001", 10, Money.ofMinor(10)).toCompletableFuture().join().order();
            assertThat(taker.state()).isEqualTo(LimitOrder.State.SELF_TRADE_PREVENTED);
            assertThat(repository.findOrder(maker).orElseThrow().state()).isEqualTo(LimitOrder.State.OPEN);
            assertThat(cash.find(player).orElseThrow().reserved()).isEqualTo(Money.zero());
        } finally { Files.deleteIfExists(file); }
    }

    private static void seedListing(Database db, CompanyId c) { db.inTransaction(x -> { try (var s=x.prepareStatement("INSERT INTO stock_listings (company_id,stock_code,issue_reference_price_minor,issued_shares,listed_at) VALUES (?,?,?,?,?)")) { s.setString(1,c.value().toString()); s.setString(2,"BS000001"); s.setLong(3,10); s.setLong(4,100); s.setString(5,Instant.EPOCH.toString()); s.executeUpdate(); } return null; }); }
    private static void seedHolding(Database db, CompanyId c, UUID p, long shares) { db.inTransaction(x -> { try (var s=x.prepareStatement("INSERT INTO share_holdings (company_id,holder_uuid,available_shares,reserved_shares) VALUES (?,?,?,0)")) { s.setString(1,c.value().toString());s.setString(2,p.toString());s.setLong(3,shares);s.executeUpdate(); } return null; }); }
}
