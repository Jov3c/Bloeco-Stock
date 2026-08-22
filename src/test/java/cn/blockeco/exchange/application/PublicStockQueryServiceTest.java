package cn.blockeco.exchange.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import cn.blockeco.exchange.domain.company.CompanyStatus;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.ports.PublicStockRepository;
import cn.blockeco.exchange.infrastructure.sql.Database;
import cn.blockeco.exchange.infrastructure.sql.SqlPublicStockRepository;
import cn.blockeco.exchange.domain.company.CompanyId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.List;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;

class PublicStockQueryServiceTest {
    @Test void every_read_returns_a_stage_and_defers_repository_work_to_the_sql_executor() {
        PublicStockRepository repository = mock(PublicStockRepository.class);
        PublicMarketRow row = new PublicMarketRow("红石工业", "BS000001", Money.ofMinor(10), Money.ofMinor(10000), 1000, CompanyStatus.LISTED);
        when(repository.market(any(),any())).thenReturn(List.of(row));
        when(repository.listOfferings(2)).thenReturn(List.of());
        when(repository.findInfo("红石工业")).thenReturn(Optional.empty());
        when(repository.findAnnouncements("红石工业", 2)).thenReturn(List.of());
        when(repository.findOpenOfferingByCompanyOrCode("红石工业")).thenReturn(Optional.empty());
        when(repository.symbols()).thenReturn(List.of());
        QueuedExecutor sqlExecutor = new QueuedExecutor();

        PublicStockQueryService service = new PublicStockQueryService(repository, sqlExecutor, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), ZoneOffset.UTC);

        CompletionStage<List<PublicMarketRow>> market = service.market();
        CompletionStage<?> ipo = service.ipo(2);
        CompletionStage<?> info = service.info("红石工业");
        CompletionStage<?> announcements = service.announcements("红石工业", 2);
        CompletionStage<?> resolve = service.resolveOpenOffering("红石工业");
        CompletionStage<?> symbols = service.symbols();
        assertThat(market).isNotNull(); assertThat(ipo).isNotNull(); assertThat(info).isNotNull(); assertThat(announcements).isNotNull(); assertThat(resolve).isNotNull(); assertThat(symbols).isNotNull();
        verifyNoInteractions(repository);
        assertThat(sqlExecutor.tasks).hasSize(6);

        sqlExecutor.runAll();

        assertThat(market).isCompletedWithValue(List.of(row));
        assertThat(ipo).isCompleted(); assertThat(info).isCompleted(); assertThat(announcements).isCompleted(); assertThat(resolve).isCompleted(); assertThat(symbols).isCompleted();
    }

    @Test void sql_public_queries_normalize_names_accept_codes_and_clamp_announcements() throws Exception {
        Path file = Files.createTempFile("public-stock-", ".db");
        try (Database database = new Database("jdbc:sqlite:" + file)) {
            database.migrate();
            CompanyId company = Fixtures.company(database, 100);
            UUID offering = UUID.randomUUID();
            database.inTransaction(connection -> {
                try (var listing = connection.prepareStatement("INSERT INTO stock_listings VALUES (?,?,?,?,?)");
                     var update = connection.prepareStatement("UPDATE companies SET status='LISTED' WHERE id=?");
                     var open = connection.prepareStatement("INSERT INTO primary_offerings VALUES (?,?,?,?,?,?,?,?,?)");
                     var announcement = connection.prepareStatement("INSERT INTO company_announcements VALUES (?,?,?,?,?)")) {
                    listing.setString(1, company.value().toString()); listing.setString(2, "BS000001"); listing.setLong(3, 10); listing.setLong(4, 1000); listing.setString(5, Instant.EPOCH.toString()); listing.executeUpdate();
                    update.setString(1, company.value().toString()); update.executeUpdate();
                    open.setString(1, offering.toString()); open.setString(2, company.value().toString()); open.setLong(3, 100); open.setLong(4, 10); open.setLong(5, 10); open.setString(6, Instant.EPOCH.toString()); open.setString(7, Instant.EPOCH.toString()); open.setString(8, Instant.MAX.toString()); open.setString(9, "OPEN"); open.executeUpdate();
                    for (int index = 1; index <= 50; index++) {
                        Instant announced = Instant.EPOCH.minusSeconds(index);
                        open.setString(1, UUID.randomUUID().toString()); open.setString(2, company.value().toString()); open.setLong(3, 100); open.setLong(4, 10); open.setLong(5, 10); open.setString(6, announced.toString()); open.setString(7, announced.toString()); open.setString(8, Instant.MAX.toString()); open.setString(9, "ANNOUNCED"); open.executeUpdate();
                    }
                    announcement.setString(1, UUID.randomUUID().toString()); announcement.setString(2, company.value().toString()); announcement.setString(3, offering.toString()); announcement.setString(4, "公开公告"); announcement.setString(5, Instant.EPOCH.toString()); announcement.executeUpdate();
                    announcement.setString(1, UUID.randomUUID().toString()); announcement.setString(2, company.value().toString()); announcement.setString(3, offering.toString()); announcement.setString(4, "最新公告"); announcement.setString(5, Instant.EPOCH.plusSeconds(1).toString()); announcement.executeUpdate();
                }
                return null;
            });
            SqlPublicStockRepository repository = new SqlPublicStockRepository(database.dataSource());
            assertThat(repository.market()).extracting(PublicMarketRow::stockCode, PublicMarketRow::marketCapitalization).containsExactly(org.assertj.core.groups.Tuple.tuple("BS000001", Money.ofMinor(10_000)));
            assertThat(repository.listOfferings(0)).hasSize(1);
            assertThat(repository.listOfferings(100)).hasSize(50);
            assertThat(repository.symbols()).containsExactly(new PublicStockSymbol("Ipo Test", Optional.of("BS000001")));
            assertThat(repository.findInfo("\u00a0IPO\u3000\u3000test\u00a0")).isPresent();
            assertThat(repository.findInfo("x")).isEmpty();
            assertThat(repository.findAnnouncements("x", 1)).isEmpty();
            assertThat(repository.findOpenOfferingByCompanyOrCode("x")).isEmpty();
            assertThat(repository.findInfo("bs000001")).isPresent();
            assertThat(repository.findOpenOfferingByCompanyOrCode("BS000001")).contains(offering);
            assertThat(repository.findAnnouncements("\u00a0IPO\u3000test\u00a0", 100)).extracting(PublicAnnouncement::body).containsExactly("最新公告", "公开公告");
            assertThat(repository.findAnnouncements("BS000001", 0)).hasSize(1);
        } finally { Files.deleteIfExists(file); }
    }

    @Test void market_no_trade_uses_issue_reference_without_writing() throws Exception {
        Path file=Files.createTempFile("market-no-trade-", ".db");
        try(Database db=new Database("jdbc:sqlite:"+file)){db.migrate();CompanyId company=Fixtures.company(db,100);db.inTransaction(c->{try(var u=c.prepareStatement("UPDATE companies SET status='LISTED' WHERE id=?");var l=c.prepareStatement("INSERT INTO stock_listings VALUES (?,?,?,?,?)")){u.setString(1,company.value().toString());u.executeUpdate();l.setString(1,company.value().toString());l.setString(2,"BS000001");l.setLong(3,10);l.setLong(4,1000);l.setString(5,Instant.EPOCH.toString());l.executeUpdate();}return null;});SqlPublicStockRepository repo=new SqlPublicStockRepository(db.dataSource());assertThat(repo.market()).singleElement().satisfies(r->{assertThat(r.latestPrice()).isEqualTo(Money.ofMinor(10));assertThat(r.change()).isEqualTo(Money.zero());assertThat(r.volume()).isZero();assertThat(r.turnover()).isEqualTo(Money.zero());assertThat(r.marketCapitalization()).isEqualTo(Money.ofMinor(10_000));});}finally{Files.deleteIfExists(file);}
    }

    @Test void market_uses_real_trades_for_shanghai_day_without_mutating_facts() throws Exception {
        Path file=Files.createTempFile("market-facts-", ".db");
        try(Database db=new Database("jdbc:sqlite:"+file)){db.migrate(); CompanyId listed=Fixtures.company(db,100); db.inTransaction(c->{try(var u=c.prepareStatement("UPDATE companies SET normalized_name='listed facts' WHERE id=?")){u.setString(1,listed.value().toString());u.executeUpdate();}return null;}); CompanyId hidden=Fixtures.company(db,100); UUID buyer=UUID.randomUUID(),seller=UUID.randomUUID();
            db.inTransaction(c->{list(c,listed,"BS000001",10,1000);try(var l=c.prepareStatement("INSERT INTO stock_listings VALUES (?,?,?,?,?)")){l.setString(1,hidden.value().toString());l.setString(2,"BS000099");l.setLong(3,99);l.setLong(4,1);l.setString(5,Instant.EPOCH.toString());l.executeUpdate();}addOrder(c,UUID.randomUUID(),listed,buyer,"BUY");addOrder(c,UUID.randomUUID(),listed,seller,"SELL");return null;});
            UUID oldBuy=UUID.randomUUID(),oldSell=UUID.randomUUID(),firstBuy=UUID.randomUUID(),firstSell=UUID.randomUUID(),laterBuy=UUID.randomUUID(),laterSell=UUID.randomUUID();
            db.inTransaction(c->{addOrder(c,oldBuy,listed,buyer,"BUY");addOrder(c,oldSell,listed,seller,"SELL");addOrder(c,firstBuy,listed,buyer,"BUY");addOrder(c,firstSell,listed,seller,"SELL");addOrder(c,laterBuy,listed,buyer,"BUY");addOrder(c,laterSell,listed,seller,"SELL");trade(c,UUID.randomUUID(),listed,oldBuy,oldSell,1,9,"2026-08-21T15:59:59.500Z");trade(c,UUID.randomUUID(),listed,firstBuy,firstSell,2,11,"2026-08-21T16:00:00Z");trade(c,UUID.randomUUID(),listed,laterBuy,laterSell,3,12,"2026-08-22T01:00:00Z");return null;});
            long before=count(db,"stock_orders")+count(db,"stock_trades"); SqlPublicStockRepository repo=new SqlPublicStockRepository(db.dataSource());
            var rows=repo.market(Instant.parse("2026-08-21T16:00:00Z"),Instant.parse("2026-08-22T16:00:00Z"));
            assertThat(rows).extracting(PublicMarketRow::stockCode).containsExactly("BS000001"); PublicMarketRow active=rows.getFirst();
            assertThat(active.latestPrice()).isEqualTo(Money.ofMinor(12));assertThat(active.change()).isEqualTo(Money.ofMinor(3));assertThat(active.volume()).isEqualTo(5);assertThat(active.turnover()).isEqualTo(Money.ofMinor(58));assertThat(active.marketCapitalization()).isEqualTo(Money.ofMinor(12_000));
            assertThat(count(db,"stock_orders")+count(db,"stock_trades")).isEqualTo(before);
            assertThat(repo.market()).isNotEmpty(); // Database uses maxPool=1: direct market() must not retain a connection while projecting.
        }finally{Files.deleteIfExists(file);}
    }

    private static long count(Database db,String table)throws Exception{try(var c=db.dataSource().getConnection();var s=c.prepareStatement("SELECT COUNT(*) FROM "+table);var r=s.executeQuery()){r.next();return r.getLong(1);}}
    private static void list(java.sql.Connection c,CompanyId id,String code,long reference,long shares)throws java.sql.SQLException{try(var u=c.prepareStatement("UPDATE companies SET status='LISTED' WHERE id=?");var l=c.prepareStatement("INSERT INTO stock_listings VALUES (?,?,?,?,?)")){u.setString(1,id.value().toString());u.executeUpdate();l.setString(1,id.value().toString());l.setString(2,code);l.setLong(3,reference);l.setLong(4,shares);l.setString(5,Instant.EPOCH.toString());l.executeUpdate();}}
    private static void addOrder(java.sql.Connection c,UUID id,CompanyId company,UUID player,String side)throws java.sql.SQLException{try(var s=c.prepareStatement("INSERT INTO stock_orders (id,company_id,stock_code,player_uuid,side,limit_price_minor,original_shares,remaining_shares,priority_sequence,reserved_cash_minor,filled_notional_minor,fee_charged_minor,fee_bps,accepted_at,state) VALUES (?,?,?,?,?,10,1,0,?,0,?,0,0,?,'FILLED')")){s.setString(1,id.toString());s.setString(2,company.value().toString());s.setString(3,"BS000001");s.setString(4,player.toString());s.setString(5,side);s.setLong(6,Math.abs(id.getLeastSignificantBits())+1);s.setLong(7,"BUY".equals(side)?1:0);s.setString(8,Instant.EPOCH.toString());s.executeUpdate();}}
    private static void trade(java.sql.Connection c,UUID id,CompanyId company,UUID buy,UUID sell,long shares,long price,String at)throws java.sql.SQLException{try(var s=c.prepareStatement("INSERT INTO stock_trades VALUES (?,?,?,?,?,?,?,?,?,?)")){s.setString(1,id.toString());s.setString(2,company.value().toString());s.setString(3,"BS000001");s.setString(4,buy.toString());s.setString(5,sell.toString());s.setLong(6,shares);s.setLong(7,price);s.setLong(8,shares*price);s.setLong(9,0);s.setString(10,at);s.executeUpdate();}}

    private static final class QueuedExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable task) { tasks.add(task); }
        void runAll() { while (!tasks.isEmpty()) tasks.remove().run(); }
    }
}
