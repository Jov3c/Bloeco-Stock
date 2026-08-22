package cn.blockeco.exchange.application;

import static org.assertj.core.api.Assertions.assertThat;

import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.domain.trading.LimitOrder;
import cn.blockeco.exchange.infrastructure.sql.Database;
import cn.blockeco.exchange.infrastructure.sql.SqlSecondaryTradingRepository;
import cn.blockeco.exchange.infrastructure.sql.SqlSecuritiesCashRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;

class SecondaryMarketQueryServiceTest {
    @Test void book_limits_and_sorts_anonymous_price_levels() throws Exception {
        Path file=Files.createTempFile("secondary-book-", ".db");
        try(Database db=new Database("jdbc:sqlite:"+file)) { db.migrate(); CompanyId company=Fixtures.company(db,100);seedListed(db,company);SqlSecuritiesCashRepository cash=new SqlSecuritiesCashRepository(db.dataSource());SqlSecondaryTradingRepository repo=new SqlSecondaryTradingRepository(db.dataSource(),cash);
            for(long price=1;price<=6;price++){UUID seller=UUID.randomUUID();seedHolding(db,company,seller,2);long p=price;db.inTransaction(c->{repo.reserveSell(c,order(company,seller,LimitOrder.Side.SELL,p,1));return null;});}
            SecondaryMarketQueryService service=new SecondaryMarketQueryService(repo,Runnable::run,Clock.systemUTC(),ZoneId.of("UTC"));
            assertThat(service.book("BS000001",50).toCompletableFuture().join().asks()).extracting(OrderBookLevel::price).containsExactly(Money.ofMinor(1),Money.ofMinor(2),Money.ofMinor(3),Money.ofMinor(4),Money.ofMinor(5));
        }finally{Files.deleteIfExists(file);}
    }
    @Test void portfolio_and_book_are_private_and_book_is_aggregated() throws Exception {
        Path file=Files.createTempFile("secondary-query-", ".db");
        try (Database db=new Database("jdbc:sqlite:"+file)) {
            db.migrate(); CompanyId company=Fixtures.company(db, 100); UUID owner=UUID.randomUUID(), other=UUID.randomUUID();
            seedListed(db, company); seedCash(db, owner, 1_000); seedHolding(db, company, owner, 30); seedHolding(db, company, other, 30);
            SqlSecuritiesCashRepository cash=new SqlSecuritiesCashRepository(db.dataSource()); SqlSecondaryTradingRepository repo=new SqlSecondaryTradingRepository(db.dataSource(), cash);
            LimitOrder otherOrder=order(company, other, LimitOrder.Side.SELL, 12, 20);
            db.inTransaction(c->{ repo.reserveSell(c, order(company, owner, LimitOrder.Side.SELL, 12, 10)); repo.reserveSell(c, otherOrder); return null; });
            SecondaryMarketQueryService service=new SecondaryMarketQueryService(repo, Runnable::run, Clock.fixed(Instant.parse("2026-08-22T12:00:00Z"), ZoneId.of("Asia/Shanghai")), ZoneId.of("Asia/Shanghai"));
            PortfolioView portfolio=service.portfolio(owner).toCompletableFuture().join();
            assertThat(portfolio.availableCash()).isEqualTo(Money.ofMinor(1_000));
            assertThat(portfolio.holdings()).extracting(SecondaryMarketRow::availableShares, SecondaryMarketRow::reservedShares).containsExactly(org.assertj.core.groups.Tuple.tuple(20L,10L));
            assertThat(service.book("BS000001", 50).toCompletableFuture().join().asks()).containsExactly(new OrderBookLevel(Money.ofMinor(12),30));
            assertThat(service.orders(owner, 50).toCompletableFuture().join()).extracting(OrderView::id).doesNotContain(otherOrder.id());
        } finally { Files.deleteIfExists(file); }
    }
    private static LimitOrder order(CompanyId c, UUID p, LimitOrder.Side side, long price, long shares) { return new LimitOrder(UUID.randomUUID(),c,"BS000001",p,side,Money.ofMinor(price),shares,shares,1,side==LimitOrder.Side.BUY?Money.ofMinor(price*shares):Money.zero(),Money.zero(),Money.zero(),0,Instant.EPOCH,LimitOrder.State.OPEN); }
    private static void seedListed(Database db,CompanyId c){db.inTransaction(x->{try(var s=x.prepareStatement("UPDATE companies SET status='LISTED' WHERE id=?")){s.setString(1,c.value().toString());s.executeUpdate();}try(var s=x.prepareStatement("INSERT INTO stock_listings VALUES (?,?,?,?,?)")){s.setString(1,c.value().toString());s.setString(2,"BS000001");s.setLong(3,10);s.setLong(4,1000);s.setString(5,Instant.EPOCH.toString());s.executeUpdate();}return null;});}
    private static void seedCash(Database db,UUID p,long v){db.inTransaction(x->{try(var s=x.prepareStatement("INSERT INTO securities_cash_accounts VALUES (?,?,0)")){s.setString(1,p.toString());s.setLong(2,v);s.executeUpdate();}return null;});}
    private static void seedHolding(Database db,CompanyId c,UUID p,long v){db.inTransaction(x->{try(var s=x.prepareStatement("INSERT INTO share_holdings VALUES (?,?,?,0)")){s.setString(1,c.value().toString());s.setString(2,p.toString());s.setLong(3,v);s.executeUpdate();}return null;});}
}
