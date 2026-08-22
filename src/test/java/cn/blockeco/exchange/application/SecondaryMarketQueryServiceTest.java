package cn.blockeco.exchange.application;

import static org.assertj.core.api.Assertions.assertThat;

import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.domain.trading.LimitOrder;
import cn.blockeco.exchange.infrastructure.sql.Database;
import cn.blockeco.exchange.infrastructure.sql.SqlSecondaryTradingRepository;
import cn.blockeco.exchange.infrastructure.sql.SqlSecuritiesCashRepository;
import cn.blockeco.exchange.infrastructure.sql.SqlPublicStockRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SecondaryMarketQueryServiceTest {
    @Test void book_limits_and_sorts_anonymous_price_levels() throws Exception {
        Path file=Files.createTempFile("secondary-book-", ".db");
        try(Database db=new Database("jdbc:sqlite:"+file)) { db.migrate(); CompanyId company=Fixtures.company(db,100);seedListed(db,company);SqlSecuritiesCashRepository cash=new SqlSecuritiesCashRepository(db.dataSource());SqlSecondaryTradingRepository repo=new SqlSecondaryTradingRepository(db.dataSource(),cash);
            for(long price=1;price<=6;price++){UUID seller=UUID.randomUUID();seedHolding(db,company,seller,2);long p=price;db.inTransaction(c->{repo.reserveSell(c,order(company,seller,LimitOrder.Side.SELL,p,1));return null;});}
            SecondaryMarketQueryService service=new SecondaryMarketQueryService(repo,new SqlPublicStockRepository(db.dataSource()),Runnable::run,Clock.systemUTC(),ZoneId.of("UTC"));
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
            SecondaryMarketQueryService service=new SecondaryMarketQueryService(repo, new SqlPublicStockRepository(db.dataSource()), Runnable::run, Clock.fixed(Instant.parse("2026-08-22T12:00:00Z"), ZoneId.of("Asia/Shanghai")), ZoneId.of("Asia/Shanghai"));
            PortfolioView portfolio=service.portfolio(owner).toCompletableFuture().join();
            assertThat(portfolio.availableCash()).isEqualTo(Money.ofMinor(1_000));
            assertThat(portfolio.holdings()).extracting(SecondaryMarketRow::availableShares, SecondaryMarketRow::reservedShares).containsExactly(org.assertj.core.groups.Tuple.tuple(20L,10L));
            assertThat(service.book("BS000001", 50).toCompletableFuture().join().asks()).containsExactly(new OrderBookLevel(Money.ofMinor(12),30));
            assertThat(service.orders(owner, 50).toCompletableFuture().join()).extracting(OrderView::id).doesNotContain(otherOrder.id());
        } finally { Files.deleteIfExists(file); }
    }
    @Test void personal_history_orders_by_instant_before_applying_limit() throws Exception {
        Path file=Files.createTempFile("secondary-history-", ".db");
        try(Database db=new Database("jdbc:sqlite:"+file)) { db.migrate(); CompanyId company=Fixtures.company(db,100); UUID owner=UUID.randomUUID(); seedListed(db,company);
            UUID whole=UUID.fromString("00000000-0000-0000-0000-000000000001"); UUID fractional=UUID.fromString("00000000-0000-0000-0000-000000000002");
            db.inTransaction(c->{insertSellOrder(c,whole,company,owner,"2026-08-22T10:00:00Z",1);insertSellOrder(c,fractional,company,owner,"2026-08-22T10:00:00.500Z",2);return null;});
            SqlSecuritiesCashRepository cash=new SqlSecuritiesCashRepository(db.dataSource()); SqlSecondaryTradingRepository repo=new SqlSecondaryTradingRepository(db.dataSource(),cash);
            assertThat(repo.orders(owner,1)).extracting(OrderView::id).containsExactly(fractional);
        } finally { Files.deleteIfExists(file); }
    }
    @Test void portfolio_without_cash_uses_latest_trade_by_instant() throws Exception {
        Path file=Files.createTempFile("secondary-portfolio-latest-", ".db");
        try(Database db=new Database("jdbc:sqlite:"+file)){db.migrate();CompanyId company=Fixtures.company(db,100);UUID holder=UUID.randomUUID(),buyer=UUID.randomUUID(),seller=UUID.randomUUID();seedListed(db,company);seedHolding(db,company,holder,4);
            db.inTransaction(c->{UUID b1=UUID.randomUUID(),s1=UUID.randomUUID(),b2=UUID.randomUUID(),s2=UUID.randomUUID();insertFilledOrder(c,b1,company,buyer,"BUY",10);insertFilledOrder(c,s1,company,seller,"SELL",11);insertFilledOrder(c,b2,company,buyer,"BUY",12);insertFilledOrder(c,s2,company,seller,"SELL",13);insertTrade(c,UUID.randomUUID(),company,b1,s1,10,1,0,"2026-08-22T10:00:00Z");insertTrade(c,UUID.randomUUID(),company,b2,s2,12,1,0,"2026-08-22T10:00:00.500Z");return null;});
            var repo=new SqlSecondaryTradingRepository(db.dataSource(),new SqlSecuritiesCashRepository(db.dataSource()));PortfolioView view=repo.portfolio(holder);
            assertThat(view.availableCash()).isEqualTo(Money.zero());assertThat(view.reservedCash()).isEqualTo(Money.zero());assertThat(view.holdings()).singleElement().satisfies(h->assertThat(h.latestPrice()).isEqualTo(Money.ofMinor(12)));
        }finally{Files.deleteIfExists(file);}
    }
    @Test void portfolio_and_public_market_share_canonical_uuid_tie_break() throws Exception {
        Path file=Files.createTempFile("secondary-latest-tie-", ".db");
        try(Database db=new Database("jdbc:sqlite:"+file)){db.migrate();CompanyId company=Fixtures.company(db,100);UUID holder=UUID.randomUUID(),buyer=UUID.randomUUID(),seller=UUID.randomUUID();seedListed(db,company);seedHolding(db,company,holder,1);
            db.inTransaction(c->{UUID b1=UUID.randomUUID(),s1=UUID.randomUUID(),b2=UUID.randomUUID(),s2=UUID.randomUUID();insertFilledOrder(c,b1,company,buyer,"BUY",20);insertFilledOrder(c,s1,company,seller,"SELL",21);insertFilledOrder(c,b2,company,buyer,"BUY",22);insertFilledOrder(c,s2,company,seller,"SELL",23);insertTrade(c,UUID.fromString("70000000-0000-0000-0000-000000000000"),company,b1,s1,7,1,0,"2026-08-22T10:00:00Z");insertTrade(c,UUID.fromString("80000000-0000-0000-0000-000000000000"),company,b2,s2,8,1,0,"2026-08-22T10:00:00Z");return null;});
            var portfolioRepository=new SqlSecondaryTradingRepository(db.dataSource(),new SqlSecuritiesCashRepository(db.dataSource()));var publicRepository=new SqlPublicStockRepository(db.dataSource());
            assertThat(portfolioRepository.portfolio(holder).holdings()).singleElement().extracting(SecondaryMarketRow::latestPrice).isEqualTo(Money.ofMinor(8));
            assertThat(publicRepository.market(Instant.parse("2026-08-22T00:00:00Z"),Instant.parse("2026-08-23T00:00:00Z"))).singleElement().extracting(PublicMarketRow::latestPrice).isEqualTo(Money.ofMinor(8));
        }finally{Files.deleteIfExists(file);}
    }
    @Test void book_aggregates_both_sides_limits_to_five_and_hides_unlisted_companies() throws Exception {
        Path file=Files.createTempFile("secondary-book-complete-", ".db");try(Database db=new Database("jdbc:sqlite:"+file)){db.migrate();CompanyId company=Fixtures.company(db,100);seedListed(db,company);UUID player=UUID.randomUUID();
            db.inTransaction(c->{long sequence=1;for(long price=10;price<=15;price++)insertOpenOrder(c,UUID.randomUUID(),company,player,"BUY",price,1,sequence++);insertOpenOrder(c,UUID.randomUUID(),company,player,"BUY",15,4,sequence++);for(long price=20;price<=25;price++)insertOpenOrder(c,UUID.randomUUID(),company,player,"SELL",price,1,sequence++);insertOpenOrder(c,UUID.randomUUID(),company,player,"SELL",20,8,sequence);return null;});
            var repo=new SqlSecondaryTradingRepository(db.dataSource(),new SqlSecuritiesCashRepository(db.dataSource()));assertThat(repo.bids("BS000001",50)).containsExactly(new OrderBookLevel(Money.ofMinor(15),5),new OrderBookLevel(Money.ofMinor(14),1),new OrderBookLevel(Money.ofMinor(13),1),new OrderBookLevel(Money.ofMinor(12),1),new OrderBookLevel(Money.ofMinor(11),1));assertThat(repo.asks("BS000001",50)).containsExactly(new OrderBookLevel(Money.ofMinor(20),9),new OrderBookLevel(Money.ofMinor(21),1),new OrderBookLevel(Money.ofMinor(22),1),new OrderBookLevel(Money.ofMinor(23),1),new OrderBookLevel(Money.ofMinor(24),1));
            db.inTransaction(c->{try(var s=c.prepareStatement("UPDATE companies SET status='DELISTING' WHERE id=?")){s.setString(1,company.value().toString());s.executeUpdate();}return null;});assertThat(repo.bids("BS000001",5)).isEmpty();assertThat(repo.asks("BS000001",5)).isEmpty();
        }finally{Files.deleteIfExists(file);}
    }
    @Test void book_fails_closed_on_aggregated_share_overflow() throws Exception {Path file=Files.createTempFile("secondary-book-overflow-", ".db");try(Database db=new Database("jdbc:sqlite:"+file)){db.migrate();CompanyId company=Fixtures.company(db,100);seedListed(db,company);UUID player=UUID.randomUUID();db.inTransaction(c->{insertOpenOrder(c,UUID.randomUUID(),company,player,"BUY",1,Long.MAX_VALUE,1);insertOpenOrder(c,UUID.randomUUID(),company,player,"BUY",1,1,2);return null;});var repo=new SqlSecondaryTradingRepository(db.dataSource(),new SqlSecuritiesCashRepository(db.dataSource()));assertThatThrownBy(()->repo.bids("BS000001",5)).isInstanceOf(ArithmeticException.class);}finally{Files.deleteIfExists(file);}}
    @Test void trade_history_is_private_stable_and_clamped_after_instant_sorting() throws Exception {Path file=Files.createTempFile("secondary-trades-", ".db");try(Database db=new Database("jdbc:sqlite:"+file)){db.migrate();CompanyId company=Fixtures.company(db,100);seedListed(db,company);UUID owner=UUID.randomUUID(),other=UUID.randomUUID(),stranger=UUID.randomUUID();UUID ownerBuy=UUID.randomUUID(),ownerSell=UUID.randomUUID(),otherBuy=UUID.randomUUID(),otherSell=UUID.randomUUID();db.inTransaction(c->{insertFilledOrder(c,ownerBuy,company,owner,"BUY",1);insertFilledOrder(c,ownerSell,company,owner,"SELL",2);insertFilledOrder(c,otherBuy,company,other,"BUY",3);insertFilledOrder(c,otherSell,company,other,"SELL",4);for(int i=0;i<51;i++)insertTrade(c,UUID.randomUUID(),company,ownerBuy,otherSell,10,1,1,"2026-08-22T09:59:"+String.format("%02d",i%50)+"Z");insertTrade(c,UUID.fromString("00000000-0000-0000-0000-000000000001"),company,ownerBuy,otherSell,11,1,1,"2026-08-22T10:00:00Z");insertTrade(c,UUID.fromString("00000000-0000-0000-0000-000000000002"),company,ownerBuy,otherSell,12,1,1,"2026-08-22T10:00:00.500Z");insertTrade(c,UUID.randomUUID(),company,otherBuy,ownerSell,9,1,0,"2026-08-22T10:00:00.250Z");insertTrade(c,UUID.randomUUID(),company,otherBuy,otherSell,8,1,0,"2026-08-22T11:00:00Z");return null;});var repo=new SqlSecondaryTradingRepository(db.dataSource(),new SqlSecuritiesCashRepository(db.dataSource()));assertThat(repo.trades(owner,0)).singleElement().satisfies(t->{assertThat(t.price()).isEqualTo(Money.ofMinor(12));assertThat(t.side()).isEqualTo(LimitOrder.Side.BUY);assertThat(t.fee()).isEqualTo(Money.ofMinor(1));});assertThat(repo.trades(owner,100)).hasSize(50);assertThat(repo.trades(stranger,50)).isEmpty();assertThat(repo.trades(owner,50)).anySatisfy(t->{assertThat(t.side()).isEqualTo(LimitOrder.Side.SELL);assertThat(t.fee()).isEqualTo(Money.zero());});assertThat(List.of(TradeView.class.getRecordComponents())).extracting(java.lang.reflect.RecordComponent::getName).noneMatch(n->n.contains("player")||n.contains("order")||n.contains("counterparty"));}finally{Files.deleteIfExists(file);}}
    @Test void market_is_deferred_and_uses_shanghai_day_bounds() {var publicStocks=mock(cn.blockeco.exchange.ports.PublicStockRepository.class);when(publicStocks.market(any(),any())).thenReturn(List.of());var trading=mock(cn.blockeco.exchange.ports.SecondaryTradingRepository.class);QueuedExecutor executor=new QueuedExecutor();var service=new SecondaryMarketQueryService(trading,publicStocks,executor,Clock.fixed(Instant.parse("2026-08-22T12:00:00Z"),ZoneId.of("Asia/Shanghai")),ZoneId.of("Asia/Shanghai"));var stage=service.market();verifyNoInteractions(publicStocks);assertThat(stage.toCompletableFuture()).isNotDone();executor.runAll();org.mockito.Mockito.verify(publicStocks).market(Instant.parse("2026-08-21T16:00:00Z"),Instant.parse("2026-08-22T16:00:00Z"));assertThat(stage).isCompletedWithValue(List.of());}
    private static void insertSellOrder(java.sql.Connection c,UUID id,CompanyId company,UUID player,String acceptedAt,long priority)throws java.sql.SQLException {try(var s=c.prepareStatement("INSERT INTO stock_orders (id,company_id,stock_code,player_uuid,side,limit_price_minor,original_shares,remaining_shares,priority_sequence,reserved_cash_minor,filled_notional_minor,fee_charged_minor,fee_bps,accepted_at,state) VALUES (?,?,?,?, 'SELL',10,1,1,?,0,0,0,0,?,'OPEN')")){s.setString(1,id.toString());s.setString(2,company.value().toString());s.setString(3,"BS000001");s.setString(4,player.toString());s.setLong(5,priority);s.setString(6,acceptedAt);s.executeUpdate();}}
    private static void insertFilledOrder(java.sql.Connection c,UUID id,CompanyId company,UUID player,String side,long priority)throws java.sql.SQLException{try(var s=c.prepareStatement("INSERT INTO stock_orders (id,company_id,stock_code,player_uuid,side,limit_price_minor,original_shares,remaining_shares,priority_sequence,reserved_cash_minor,filled_notional_minor,fee_charged_minor,fee_bps,accepted_at,state) VALUES (?,?,?,?,?,12,1,0,?,0,?,0,0,?,'FILLED')")){s.setString(1,id.toString());s.setString(2,company.value().toString());s.setString(3,"BS000001");s.setString(4,player.toString());s.setString(5,side);s.setLong(6,priority);s.setLong(7,"BUY".equals(side)?1:0);s.setString(8,Instant.EPOCH.toString());s.executeUpdate();}}
    private static void insertTrade(java.sql.Connection c,UUID id,CompanyId company,UUID buy,UUID sell,long price,long shares,long fee,String at)throws java.sql.SQLException{try(var s=c.prepareStatement("INSERT INTO stock_trades VALUES (?,?,?,?,?,?,?,?,?,?)")){s.setString(1,id.toString());s.setString(2,company.value().toString());s.setString(3,"BS000001");s.setString(4,buy.toString());s.setString(5,sell.toString());s.setLong(6,shares);s.setLong(7,price);s.setLong(8,Math.multiplyExact(price,shares));s.setLong(9,fee);s.setString(10,at);s.executeUpdate();}}
    private static void insertOpenOrder(java.sql.Connection c,UUID id,CompanyId company,UUID player,String side,long price,long shares,long priority)throws java.sql.SQLException{try(var s=c.prepareStatement("INSERT INTO stock_orders (id,company_id,stock_code,player_uuid,side,limit_price_minor,original_shares,remaining_shares,priority_sequence,reserved_cash_minor,filled_notional_minor,fee_charged_minor,fee_bps,accepted_at,state) VALUES (?,?,?,?,?,?,?,?,?,?,0,0,0,?,'OPEN')")){s.setString(1,id.toString());s.setString(2,company.value().toString());s.setString(3,"BS000001");s.setString(4,player.toString());s.setString(5,side);s.setLong(6,price);s.setLong(7,shares);s.setLong(8,shares);s.setLong(9,priority);s.setLong(10,"BUY".equals(side)?Math.multiplyExact(price,shares):0);s.setString(11,Instant.EPOCH.toString());s.executeUpdate();}}
    private static LimitOrder order(CompanyId c, UUID p, LimitOrder.Side side, long price, long shares) { return new LimitOrder(UUID.randomUUID(),c,"BS000001",p,side,Money.ofMinor(price),shares,shares,1,side==LimitOrder.Side.BUY?Money.ofMinor(price*shares):Money.zero(),Money.zero(),Money.zero(),0,Instant.EPOCH,LimitOrder.State.OPEN); }
    private static void seedListed(Database db,CompanyId c){db.inTransaction(x->{try(var s=x.prepareStatement("UPDATE companies SET status='LISTED' WHERE id=?")){s.setString(1,c.value().toString());s.executeUpdate();}try(var s=x.prepareStatement("INSERT INTO stock_listings VALUES (?,?,?,?,?)")){s.setString(1,c.value().toString());s.setString(2,"BS000001");s.setLong(3,10);s.setLong(4,1000);s.setString(5,Instant.EPOCH.toString());s.executeUpdate();}return null;});}
    private static void seedCash(Database db,UUID p,long v){db.inTransaction(x->{try(var s=x.prepareStatement("INSERT INTO securities_cash_accounts VALUES (?,?,0)")){s.setString(1,p.toString());s.setLong(2,v);s.executeUpdate();}return null;});}
    private static void seedHolding(Database db,CompanyId c,UUID p,long v){db.inTransaction(x->{try(var s=x.prepareStatement("INSERT INTO share_holdings VALUES (?,?,?,0)")){s.setString(1,c.value().toString());s.setString(2,p.toString());s.setLong(3,v);s.executeUpdate();}return null;});}
    private static final class QueuedExecutor implements Executor {private final ArrayDeque<Runnable> tasks=new ArrayDeque<>();public void execute(Runnable task){tasks.add(task);}void runAll(){while(!tasks.isEmpty())tasks.remove().run();}}
}
