package cn.blockeco.exchange.application;

import static org.assertj.core.api.Assertions.assertThat;

import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.market.MarketSession;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.infrastructure.sql.Database;
import cn.blockeco.exchange.infrastructure.sql.SqlSecuritiesCashRepository;
import cn.blockeco.exchange.infrastructure.sql.SqlSecondaryTradingRepository;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class MarketSessionServiceTest {
    @Test
    void reopeningAfterRestartMatchesQueuedOrdersOnlyOnceForTheTradingDay() throws Exception {
        var file=Files.createTempFile("blockstock-session-transition-", ".db");
        try(var db=new Database("jdbc:sqlite:"+file)){db.migrate();CompanyId company=Fixtures.company(db,100);UUID seller=UUID.randomUUID(),buyer=UUID.randomUUID();seedListing(db,company);seedHolding(db,company,seller,10);var cash=new SqlSecuritiesCashRepository(db.dataSource());db.inTransaction(c->{cash.creditAvailable(c,buyer,Money.ofMinor(100),Instant.EPOCH);return null;});var repository=new SqlSecondaryTradingRepository(db.dataSource(),cash);var session=new AtomicReference<>(new MarketSession(false));var clock=(cn.blockeco.exchange.ports.AppClock)()->Instant.parse("2026-08-24T00:00:00Z");var market=new SecondaryMarketService(repository,db,Runnable::run,clock,0,session::get);
            market.placeSell(seller,"BS000001",10,Money.ofMinor(9)).toCompletableFuture().join();market.placeBuy(buyer,"BS000001",10,Money.ofMinor(10)).toCompletableFuture().join();session.set(new MarketSession(true));
            assertThat(new MarketSessionService(market,repository,db,Runnable::run,clock,ZoneId.of("Asia/Shanghai"),session::get).onSessionTransition().toCompletableFuture().join()).isEqualTo(1);
            assertThat(new MarketSessionService(market,repository,db,Runnable::run,clock,ZoneId.of("Asia/Shanghai"),session::get).onSessionTransition().toCompletableFuture().join()).isZero();
        }finally{Files.deleteIfExists(file);}
    }

    private static void seedListing(Database db, CompanyId c) { db.inTransaction(x -> { try (var s=x.prepareStatement("INSERT INTO stock_listings (company_id,stock_code,issue_reference_price_minor,issued_shares,listed_at) VALUES (?,?,?,?,?)")) { s.setString(1,c.value().toString());s.setString(2,"BS000001");s.setLong(3,10);s.setLong(4,100);s.setString(5,Instant.EPOCH.toString());s.executeUpdate(); } return null; }); db.inTransaction(x->{try(var s=x.prepareStatement("UPDATE companies SET status='LISTED' WHERE id=?")){s.setString(1,c.value().toString());s.executeUpdate();}return null;}); }
    private static void seedHolding(Database db, CompanyId c, UUID p, long shares) { db.inTransaction(x -> { try (var s=x.prepareStatement("INSERT INTO share_holdings (company_id,holder_uuid,available_shares,reserved_shares) VALUES (?,?,?,0)")) { s.setString(1,c.value().toString());s.setString(2,p.toString());s.setLong(3,shares);s.executeUpdate(); } return null; }); }
}
