package cn.blockeco.exchange.infrastructure.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.blockeco.exchange.application.Fixtures;
import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.finance.SecuritiesCashAccount;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.domain.trading.LimitOrder;
import cn.blockeco.exchange.domain.trading.Trade;
import cn.blockeco.exchange.ports.SecondaryTradingRepository;
import java.nio.file.Files;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SqlSecondaryTradingRepositoryTest {
    @Test
    void best_crossing_maker_uses_price_then_allocated_priority_not_timestamp_or_uuid() throws Exception {
        var file=Files.createTempFile("blockstock-book-priority-", ".db");
        try(Database db=new Database("jdbc:sqlite:"+file)) { db.migrate(); CompanyId company=Fixtures.company(db,100); seedListing(db,company);
            UUID first=UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"), second=UUID.fromString("00000000-0000-0000-0000-000000000001"), buyer=UUID.randomUUID(); Instant now=Instant.EPOCH;
            seedHolding(db,company,first,10); seedHolding(db,company,second,10); var cash=new SqlSecuritiesCashRepository(db.dataSource());var trading=new SqlSecondaryTradingRepository(db.dataSource(),cash);
            db.inTransaction(c->{cash.creditAvailable(c,buyer,Money.ofMinor(1_000),now);return null;});
            LimitOrder makerOne=db.inTransaction(c->trading.reserveSell(c,order(company,first,LimitOrder.Side.SELL,9,10,0,now)));
            db.inTransaction(c->trading.reserveSell(c,order(company,second,LimitOrder.Side.SELL,9,10,0,now)));
            LimitOrder taker=db.inTransaction(c->trading.reserveBuy(c,order(company,buyer,LimitOrder.Side.BUY,10,10,0,now)));
            java.util.Optional<LimitOrder> best=db.inTransaction(c->trading.nextCrossingMaker(c,taker));
            assertThat(best).contains(makerOne);
        } finally { Files.deleteIfExists(file); }
    }

    @Test
    void repository_allocates_priority_inside_the_reservation_transaction() throws Exception {
        var file=Files.createTempFile("blockstock-priority-", ".db");
        try(Database db=new Database("jdbc:sqlite:"+file)) { db.migrate(); CompanyId company=Fixtures.company(db,100); seedListing(db,company); UUID player=UUID.randomUUID(); var cash=new SqlSecuritiesCashRepository(db.dataSource()); var trading=new SqlSecondaryTradingRepository(db.dataSource(),cash); Instant now=Instant.parse("2026-08-22T12:00:00Z"); db.inTransaction(c->{cash.creditAvailable(c,player,Money.ofMinor(1_000),now);return null;}); LimitOrder first=order(company,player,LimitOrder.Side.BUY,10,1,0,now), second=order(company,player,LimitOrder.Side.BUY,10,1,0,now); LimitOrder acceptedOne=db.inTransaction(c->trading.reserveBuy(c,first)); LimitOrder acceptedTwo=db.inTransaction(c->trading.reserveBuy(c,second)); assertThat(acceptedOne.prioritySequence()).isEqualTo(1);assertThat(acceptedTwo.prioritySequence()).isEqualTo(2); assertThatThrownBy(()->db.inTransaction(c->{trading.reserveBuy(c,order(company,player,LimitOrder.Side.BUY,10,1,0,now));throw new IllegalStateException("rollback");})).isInstanceOf(IllegalStateException.class); LimitOrder acceptedThree=db.inTransaction(c->trading.reserveBuy(c,order(company,player,LimitOrder.Side.BUY,10,1,0,now)));assertThat(acceptedThree.prioritySequence()).isEqualTo(3); }finally{Files.deleteIfExists(file);}
    }
    @Test
    void settlement_charges_cumulative_fee_credits_seller_and_releases_price_improvement() throws Exception {
        var file = Files.createTempFile("blockstock-secondary-", ".db");
        try (Database db = new Database("jdbc:sqlite:" + file)) {
            db.migrate();
            CompanyId company = Fixtures.company(db, 100);
            UUID buyer = UUID.randomUUID(), seller = UUID.randomUUID(); Instant now = Instant.parse("2026-08-22T12:00:00Z");
            seedListing(db, company); seedHolding(db, company, seller, 100);
            var cash = new SqlSecuritiesCashRepository(db.dataSource());
            var trading = new SqlSecondaryTradingRepository(db.dataSource(), cash);
            db.inTransaction(c -> { cash.creditAvailable(c, buyer, Money.ofMinor(2_000), now); return null; });
            LimitOrder buy = order(company, buyer, LimitOrder.Side.BUY, 12, 100, 100, now);
            LimitOrder sell = order(company, seller, LimitOrder.Side.SELL, 10, 100, 0, now);
            db.inTransaction(c -> { trading.reserveBuy(c, buy); trading.reserveSell(c, sell); return null; });
            assertThat(cash.find(buyer)).contains(new SecuritiesCashAccount(buyer, Money.ofMinor(788), Money.ofMinor(1_212)));
            Trade fill = new Trade(UUID.randomUUID(), company, "BS000001", buy.id(), sell.id(), 100, Money.ofMinor(10), Money.ofMinor(1_000), Money.ofMinor(10), now);
            db.inTransaction(c -> { trading.settleTrade(c, fill); return null; });
            assertThat(cash.find(buyer)).contains(new SecuritiesCashAccount(buyer, Money.ofMinor(990), Money.zero()));
            assertThat(cash.find(seller)).contains(new SecuritiesCashAccount(seller, Money.ofMinor(1_000), Money.zero()));
            assertThat(trading.compensationFund()).isEqualTo(Money.ofMinor(10));
            assertThat(holding(db, company, buyer)).containsExactly(100L, 0L);
            assertThat(holding(db, company, seller)).containsExactly(0L, 0L);
            assertThat(cash.reconcile(Money.ofMinor(2_100)).confirmedDifference()).isEqualTo(Money.zero());
        } finally { Files.deleteIfExists(file); }
    }

    @Test
    void split_fills_charge_the_same_cumulative_fee_and_release_each_price_improvement() throws Exception {
        var file=Files.createTempFile("blockstock-split-", ".db");
        try(Database db=new Database("jdbc:sqlite:"+file)){db.migrate();CompanyId company=Fixtures.company(db,100);UUID buyer=UUID.randomUUID(),seller=UUID.randomUUID();Instant now=Instant.parse("2026-08-22T12:00:00Z");seedListing(db,company);seedHolding(db,company,seller,100);var cash=new SqlSecuritiesCashRepository(db.dataSource());var trading=new SqlSecondaryTradingRepository(db.dataSource(),cash);db.inTransaction(c->{cash.creditAvailable(c,buyer,Money.ofMinor(2_000),now);return null;});LimitOrder buy=order(company,buyer,LimitOrder.Side.BUY,12,100,100,now),sell=order(company,seller,LimitOrder.Side.SELL,10,100,0,now);db.inTransaction(c->{trading.reserveBuy(c,buy);trading.reserveSell(c,sell);return null;}); Trade one=new Trade(UUID.randomUUID(),company,"BS000001",buy.id(),sell.id(),1,Money.ofMinor(10),Money.ofMinor(10),Money.ofMinor(1),now);db.inTransaction(c->{trading.settleTrade(c,one);return null;});assertThat(trading.findOrder(buy.id()).orElseThrow().reservedCash()).isEqualTo(Money.ofMinor(1_199));Trade rest=new Trade(UUID.randomUUID(),company,"BS000001",buy.id(),sell.id(),99,Money.ofMinor(10),Money.ofMinor(990),Money.ofMinor(9),now);db.inTransaction(c->{trading.settleTrade(c,rest);return null;});assertThat(cash.find(buyer)).contains(new SecuritiesCashAccount(buyer,Money.ofMinor(990),Money.zero()));assertThat(trading.compensationFund()).isEqualTo(Money.ofMinor(10));assertThat(cash.reconcile(Money.ofMinor(2_100)).confirmedDifference()).isEqualTo(Money.zero());}finally{Files.deleteIfExists(file);}
    }

    @Test
    void duplicate_trade_repeated_cancel_self_trade_and_ledger_drift_do_not_mask_state() throws Exception {
        var file=Files.createTempFile("blockstock-secondary-idempotency-", ".db");
        try(Database db=new Database("jdbc:sqlite:"+file)){db.migrate();CompanyId company=Fixtures.company(db,100);UUID buyer=UUID.randomUUID(),seller=UUID.randomUUID();Instant now=Instant.parse("2026-08-22T12:00:00Z");seedListing(db,company);seedHolding(db,company,seller,2);var cash=new SqlSecuritiesCashRepository(db.dataSource());var trading=new SqlSecondaryTradingRepository(db.dataSource(),cash);db.inTransaction(c->{cash.creditAvailable(c,buyer,Money.ofMinor(100),now);return null;});LimitOrder buy=order(company,buyer,LimitOrder.Side.BUY,10,1,0,now),sell=order(company,seller,LimitOrder.Side.SELL,10,1,0,now);db.inTransaction(c->{trading.reserveBuy(c,buy);trading.reserveSell(c,sell);return null;});Trade trade=new Trade(UUID.randomUUID(),company,"BS000001",buy.id(),sell.id(),1,Money.ofMinor(10),Money.ofMinor(10),Money.zero(),now);db.inTransaction(c->{trading.settleTrade(c,trade);return null;});assertThatThrownBy(()->db.inTransaction(c->{trading.settleTrade(c,trade);return null;})).isInstanceOf(SecondaryTradingRepository.OptimisticStateException.class);assertThat(cash.find(seller)).contains(new SecuritiesCashAccount(seller,Money.ofMinor(10),Money.zero()));LimitOrder cancellable=order(company,buyer,LimitOrder.Side.BUY,10,1,0,now);db.inTransaction(c->{trading.reserveBuy(c,cancellable);trading.cancelTakerForSelfTrade(c,cancellable.id());trading.cancelTakerForSelfTrade(c,cancellable.id());return null;});assertThat(cash.find(buyer)).contains(new SecuritiesCashAccount(buyer,Money.ofMinor(90),Money.zero()));assertThat(trading.findOrder(cancellable.id()).orElseThrow().state()).isEqualTo(LimitOrder.State.SELF_TRADE_PREVENTED);db.inTransaction(c->{try(var s=c.prepareStatement("UPDATE securities_cash_accounts SET available_minor=available_minor+1 WHERE player_uuid=?")){s.setString(1,seller.toString());s.executeUpdate();}return null;});assertThatThrownBy(()->cash.reconcile(Money.ofMinor(110))).isInstanceOf(IllegalStateException.class).hasMessageContaining("ledger");}finally{Files.deleteIfExists(file);}
    }

    private static LimitOrder order(CompanyId c, UUID player, LimitOrder.Side side, long price, long shares, int bps, Instant at) {
        Money filled = Money.zero(); Money fee = Money.zero(); Money reserve = side == LimitOrder.Side.BUY ? Money.ofMinor(price * shares + ((price * shares * bps + 9999) / 10000)) : Money.zero();
        return new LimitOrder(UUID.randomUUID(), c, "BS000001", player, side, Money.ofMinor(price), shares, shares, Math.abs(UUID.randomUUID().getLeastSignificantBits()) + 1, reserve, filled, fee, side == LimitOrder.Side.BUY ? bps : 0, at, LimitOrder.State.OPEN);
    }
    private static void seedListing(Database db, CompanyId c) { db.inTransaction(x -> { try(var s=x.prepareStatement("UPDATE companies SET status='LISTED' WHERE id=?")){s.setString(1,c.value().toString());s.executeUpdate();} try (var s=x.prepareStatement("INSERT INTO stock_listings (company_id,stock_code,issue_reference_price_minor,issued_shares,listed_at) VALUES (?,?,?,?,?)")) { s.setString(1,c.value().toString());s.setString(2,"BS000001");s.setLong(3,10);s.setLong(4,1000);s.setString(5,"2026-08-22T00:00:00Z");s.executeUpdate(); } try(var s=x.prepareStatement("INSERT INTO escrow_ledger_entries (id,liability_kind,company_id,player_uuid,amount_minor,operation_id,trade_id,occurred_at) VALUES (?,?,?,?,?,?,?,?)")){s.setString(1,UUID.randomUUID().toString());s.setString(2,"COMPANY_TREASURY");s.setString(3,c.value().toString());s.setNull(4,java.sql.Types.VARCHAR);s.setLong(5,100);s.setNull(6,java.sql.Types.VARCHAR);s.setNull(7,java.sql.Types.VARCHAR);s.setString(8,"2026-08-22T00:00:00Z");s.executeUpdate();} return null; }); }
    private static void seedHolding(Database db, CompanyId c, UUID p, long shares) { db.inTransaction(x -> { try (var s=x.prepareStatement("INSERT INTO share_holdings (company_id,holder_uuid,available_shares,reserved_shares) VALUES (?,?,?,0)")) { s.setString(1,c.value().toString());s.setString(2,p.toString());s.setLong(3,shares);s.executeUpdate(); } return null; }); }
    private static long[] holding(Database db, CompanyId c, UUID p) { try (var x=db.dataSource().getConnection();var s=x.prepareStatement("SELECT available_shares,reserved_shares FROM share_holdings WHERE company_id=? AND holder_uuid=?")) { s.setString(1,c.value().toString());s.setString(2,p.toString());try(var r=s.executeQuery()){r.next();return new long[]{r.getLong(1),r.getLong(2)};}} catch(Exception e){throw new RuntimeException(e);} }
}
