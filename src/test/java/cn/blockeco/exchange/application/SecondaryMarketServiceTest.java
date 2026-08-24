package cn.blockeco.exchange.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.market.MarketSession;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.domain.trading.LimitOrder;
import cn.blockeco.exchange.infrastructure.sql.Database;
import cn.blockeco.exchange.infrastructure.sql.SqlSecuritiesCashRepository;
import cn.blockeco.exchange.infrastructure.sql.SqlSecondaryTradingRepository;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class SecondaryMarketServiceTest {
    @Test
    void rejects_stock_listing_row_when_company_is_not_listed_without_reserving_or_inserting() throws Exception {
        var file=Files.createTempFile("blockstock-not-listed-", ".db");
        try(var db=new Database("jdbc:sqlite:"+file)) {
            db.migrate(); CompanyId company=Fixtures.company(db,100); UUID buyer=UUID.randomUUID(); seedListingRow(db,company);
            var cash=new SqlSecuritiesCashRepository(db.dataSource()); db.inTransaction(c->{cash.creditAvailable(c,buyer,Money.ofMinor(100),Instant.EPOCH);return null;});
            var service=new SecondaryMarketService(new SqlSecondaryTradingRepository(db.dataSource(),cash),db,Runnable::run,()->Instant.EPOCH,0);
            assertThatThrownBy(()->service.placeBuy(buyer,"BS000001",5,Money.ofMinor(10)).toCompletableFuture().join())
                    .hasCauseInstanceOf(IllegalArgumentException.class).hasRootCauseMessage("stock is not listed");
            assertThat(cash.find(buyer).orElseThrow().available()).isEqualTo(Money.ofMinor(100));
            assertThat(cash.find(buyer).orElseThrow().reserved()).isEqualTo(Money.zero());
            assertThat(number(db,"SELECT COUNT(*) FROM stock_orders")).isZero();
            assertThat(number(db,"SELECT last_value FROM stock_order_sequence WHERE singleton=1")).isZero();
        } finally { Files.deleteIfExists(file); }
    }

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

    @Test
    void one_maker_fills_two_takers_and_transitions_partial_then_filled() throws Exception {
        var file=Files.createTempFile("blockstock-two-takers-", ".db");
        try(var db=new Database("jdbc:sqlite:"+file)) { db.migrate(); CompanyId company=Fixtures.company(db,100); UUID seller=UUID.randomUUID(),buyerOne=UUID.randomUUID(),buyerTwo=UUID.randomUUID(); seedListing(db,company);seedHolding(db,company,seller,10);
            var cash=new SqlSecuritiesCashRepository(db.dataSource()); db.inTransaction(c->{cash.creditAvailable(c,buyerOne,Money.ofMinor(100),Instant.EPOCH);cash.creditAvailable(c,buyerTwo,Money.ofMinor(100),Instant.EPOCH);return null;});
            var repository=new SqlSecondaryTradingRepository(db.dataSource(),cash);var service=new SecondaryMarketService(repository,db,Runnable::run,()->Instant.EPOCH,0);
            UUID maker=service.placeSell(seller,"BS000001",10,Money.ofMinor(9)).toCompletableFuture().join().order().id();
            service.placeBuy(buyerOne,"BS000001",4,Money.ofMinor(9)).toCompletableFuture().join();
            assertThat(repository.findOrder(maker).orElseThrow().state()).isEqualTo(LimitOrder.State.PARTIALLY_FILLED);
            assertThat(repository.findOrder(maker).orElseThrow().remainingShares()).isEqualTo(6);
            service.placeBuy(buyerTwo,"BS000001",6,Money.ofMinor(9)).toCompletableFuture().join();
            assertThat(repository.findOrder(maker).orElseThrow().state()).isEqualTo(LimitOrder.State.FILLED);
            assertThat(cash.find(seller).orElseThrow().available()).isEqualTo(Money.ofMinor(90));
        } finally { Files.deleteIfExists(file); }
    }

    @Test
    void taker_sweeps_two_maker_prices_with_cumulative_fee_and_exact_price_improvement_release() throws Exception {
        var file=Files.createTempFile("blockstock-sweep-", ".db");
        try(var db=new Database("jdbc:sqlite:"+file)) { db.migrate(); CompanyId company=Fixtures.company(db,100); UUID sellerOne=UUID.randomUUID(),sellerTwo=UUID.randomUUID(),buyer=UUID.randomUUID();seedListing(db,company);seedHolding(db,company,sellerOne,4);seedHolding(db,company,sellerTwo,6);
            var cash=new SqlSecuritiesCashRepository(db.dataSource());db.inTransaction(c->{cash.creditAvailable(c,buyer,Money.ofMinor(1_000),Instant.EPOCH);return null;});var repository=new SqlSecondaryTradingRepository(db.dataSource(),cash);var service=new SecondaryMarketService(repository,db,Runnable::run,()->Instant.EPOCH,100);
            service.placeSell(sellerOne,"BS000001",4,Money.ofMinor(9)).toCompletableFuture().join();service.placeSell(sellerTwo,"BS000001",6,Money.ofMinor(10)).toCompletableFuture().join();
            LimitOrder taker=service.placeBuy(buyer,"BS000001",10,Money.ofMinor(12)).toCompletableFuture().join().order();
            assertThat(taker.state()).isEqualTo(LimitOrder.State.FILLED);assertThat(taker.filledNotional()).isEqualTo(Money.ofMinor(96));assertThat(taker.feeCharged()).isEqualTo(Money.ofMinor(1));
            assertThat(cash.find(buyer).orElseThrow().available()).isEqualTo(Money.ofMinor(903));assertThat(cash.find(buyer).orElseThrow().reserved()).isEqualTo(Money.zero());assertThat(repository.compensationFund()).isEqualTo(Money.ofMinor(1));
        } finally { Files.deleteIfExists(file); }
    }

    @Test
    void owner_cancel_after_partial_fill_and_repeat_release_remaining_reserve_once() throws Exception {
        var file=Files.createTempFile("blockstock-partial-cancel-", ".db");
        try(var db=new Database("jdbc:sqlite:"+file)){db.migrate();CompanyId company=Fixtures.company(db,100);UUID seller=UUID.randomUUID(),buyer=UUID.randomUUID();seedListing(db,company);seedHolding(db,company,seller,5);var cash=new SqlSecuritiesCashRepository(db.dataSource());db.inTransaction(c->{cash.creditAvailable(c,buyer,Money.ofMinor(1_000),Instant.EPOCH);return null;});var repository=new SqlSecondaryTradingRepository(db.dataSource(),cash);var service=new SecondaryMarketService(repository,db,Runnable::run,()->Instant.EPOCH,0);
            service.placeSell(seller,"BS000001",5,Money.ofMinor(9)).toCompletableFuture().join();LimitOrder partial=service.placeBuy(buyer,"BS000001",10,Money.ofMinor(10)).toCompletableFuture().join().order();assertThat(partial.state()).isEqualTo(LimitOrder.State.PARTIALLY_FILLED);assertThat(cash.find(buyer).orElseThrow().reserved()).isEqualTo(Money.ofMinor(50));
            assertThat(service.cancel(buyer,partial.id()).toCompletableFuture().join().order().state()).isEqualTo(LimitOrder.State.CANCELLED);assertThat(cash.find(buyer).orElseThrow().available()).isEqualTo(Money.ofMinor(955));
            assertThat(service.cancel(buyer,partial.id()).toCompletableFuture().join().order().state()).isEqualTo(LimitOrder.State.CANCELLED);assertThat(cash.find(buyer).orElseThrow().available()).isEqualTo(Money.ofMinor(955));assertThat(cash.find(buyer).orElseThrow().reserved()).isEqualTo(Money.zero());
        }finally{Files.deleteIfExists(file);}
    }

    @Test
    void concurrent_submissions_are_serialized_to_unique_monotonic_priority() throws Exception {
        var file=Files.createTempFile("blockstock-concurrent-", ".db");var sql=Executors.newSingleThreadExecutor();var callers=Executors.newFixedThreadPool(2);
        try(var db=new Database("jdbc:sqlite:"+file)){db.migrate();CompanyId company=Fixtures.company(db,100);UUID one=UUID.randomUUID(),two=UUID.randomUUID();seedListing(db,company);var cash=new SqlSecuritiesCashRepository(db.dataSource());db.inTransaction(c->{cash.creditAvailable(c,one,Money.ofMinor(100),Instant.EPOCH);cash.creditAvailable(c,two,Money.ofMinor(100),Instant.EPOCH);return null;});var service=new SecondaryMarketService(new SqlSecondaryTradingRepository(db.dataSource(),cash),db,sql,()->Instant.EPOCH,0);var gate=new CountDownLatch(1);
            var first=java.util.concurrent.CompletableFuture.supplyAsync(()->{await(gate);return service.placeBuy(one,"BS000001",1,Money.ofMinor(10)).toCompletableFuture().join().order();},callers);var second=java.util.concurrent.CompletableFuture.supplyAsync(()->{await(gate);return service.placeBuy(two,"BS000001",1,Money.ofMinor(10)).toCompletableFuture().join().order();},callers);gate.countDown();List<Long> sequences=java.util.stream.Stream.of(first.join(),second.join()).map(LimitOrder::prioritySequence).sorted().toList();assertThat(sequences).containsExactly(1L,2L);
        }finally{callers.shutdownNow();sql.shutdownNow();Files.deleteIfExists(file);}
    }

    @Test
    void closedSessionReservesOrdersButDoesNotMatchUntilOpening() throws Exception {
        var file=Files.createTempFile("blockstock-closed-session-", ".db");
        try(var db=new Database("jdbc:sqlite:"+file)){db.migrate();CompanyId company=Fixtures.company(db,100);UUID seller=UUID.randomUUID(),buyer=UUID.randomUUID();seedListing(db,company);seedHolding(db,company,seller,10);var cash=new SqlSecuritiesCashRepository(db.dataSource());db.inTransaction(c->{cash.creditAvailable(c,buyer,Money.ofMinor(100),Instant.EPOCH);return null;});var repository=new SqlSecondaryTradingRepository(db.dataSource(),cash);
            var closed=new SecondaryMarketService(repository,db,Runnable::run,()->Instant.EPOCH,0,()->new MarketSession(false));
            closed.placeSell(seller,"BS000001",10,Money.ofMinor(9)).toCompletableFuture().join();closed.placeBuy(buyer,"BS000001",10,Money.ofMinor(10)).toCompletableFuture().join();
            assertThat(number(db,"SELECT COUNT(*) FROM stock_trades")).isZero();assertThat(cash.find(buyer).orElseThrow().reserved()).isEqualTo(Money.ofMinor(100));
            var open=new SecondaryMarketService(repository,db,Runnable::run,()->Instant.EPOCH,0,()->new MarketSession(true));
            assertThat(open.matchQueuedOrders().toCompletableFuture().join()).isEqualTo(1);assertThat(number(db,"SELECT COUNT(*) FROM stock_trades")).isEqualTo(1);
        }finally{Files.deleteIfExists(file);}
    }

    @Test
    void openingMatchesExistingOrdersByPriceThenAcceptedPriority() throws Exception {
        var file=Files.createTempFile("blockstock-opening-priority-", ".db");
        try(var db=new Database("jdbc:sqlite:"+file)){db.migrate();CompanyId company=Fixtures.company(db,100);UUID firstSeller=UUID.randomUUID(),secondSeller=UUID.randomUUID(),buyer=UUID.randomUUID();seedListing(db,company);seedHolding(db,company,firstSeller,10);seedHolding(db,company,secondSeller,10);var cash=new SqlSecuritiesCashRepository(db.dataSource());db.inTransaction(c->{cash.creditAvailable(c,buyer,Money.ofMinor(100),Instant.EPOCH);return null;});var repository=new SqlSecondaryTradingRepository(db.dataSource(),cash);var closed=new SecondaryMarketService(repository,db,Runnable::run,()->Instant.EPOCH,0,()->new MarketSession(false));
            UUID first=closed.placeSell(firstSeller,"BS000001",10,Money.ofMinor(9)).toCompletableFuture().join().order().id();UUID second=closed.placeSell(secondSeller,"BS000001",10,Money.ofMinor(9)).toCompletableFuture().join().order().id();closed.placeBuy(buyer,"BS000001",10,Money.ofMinor(10)).toCompletableFuture().join();
            new SecondaryMarketService(repository,db,Runnable::run,()->Instant.EPOCH,0,()->new MarketSession(true)).matchQueuedOrders().toCompletableFuture().join();
            assertThat(repository.findOrder(first).orElseThrow().state()).isEqualTo(LimitOrder.State.FILLED);assertThat(repository.findOrder(second).orElseThrow().state()).isEqualTo(LimitOrder.State.OPEN);
        }finally{Files.deleteIfExists(file);}
    }

    private static void seedListing(Database db, CompanyId c) { seedListingRow(db,c); db.inTransaction(x->{try(var s=x.prepareStatement("UPDATE companies SET status='LISTED' WHERE id=?")){s.setString(1,c.value().toString());s.executeUpdate();}return null;}); }
    private static void seedListingRow(Database db, CompanyId c) { db.inTransaction(x -> { try (var s=x.prepareStatement("INSERT INTO stock_listings (company_id,stock_code,issue_reference_price_minor,issued_shares,listed_at) VALUES (?,?,?,?,?)")) { s.setString(1,c.value().toString()); s.setString(2,"BS000001"); s.setLong(3,10); s.setLong(4,100); s.setString(5,Instant.EPOCH.toString()); s.executeUpdate(); } return null; }); }
    private static void seedHolding(Database db, CompanyId c, UUID p, long shares) { db.inTransaction(x -> { try (var s=x.prepareStatement("INSERT INTO share_holdings (company_id,holder_uuid,available_shares,reserved_shares) VALUES (?,?,?,0)")) { s.setString(1,c.value().toString());s.setString(2,p.toString());s.setLong(3,shares);s.executeUpdate(); } return null; }); }
    private static long number(Database db,String sql){try(var c=db.dataSource().getConnection();var s=c.prepareStatement(sql);var r=s.executeQuery()){r.next();return r.getLong(1);}catch(Exception e){throw new RuntimeException(e);}}
    private static void await(CountDownLatch latch){try{latch.await();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}}
}
