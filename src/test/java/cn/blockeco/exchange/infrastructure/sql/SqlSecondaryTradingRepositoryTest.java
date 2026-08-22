package cn.blockeco.exchange.infrastructure.sql;

import static org.assertj.core.api.Assertions.assertThat;

import cn.blockeco.exchange.application.Fixtures;
import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.finance.SecuritiesCashAccount;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.domain.trading.LimitOrder;
import cn.blockeco.exchange.domain.trading.Trade;
import java.nio.file.Files;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SqlSecondaryTradingRepositoryTest {
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
        } finally { Files.deleteIfExists(file); }
    }

    private static LimitOrder order(CompanyId c, UUID player, LimitOrder.Side side, long price, long shares, int bps, Instant at) {
        Money filled = Money.zero(); Money fee = Money.zero(); Money reserve = side == LimitOrder.Side.BUY ? Money.ofMinor(price * shares + ((price * shares * bps + 9999) / 10000)) : Money.zero();
        return new LimitOrder(UUID.randomUUID(), c, "BS000001", player, side, Money.ofMinor(price), shares, shares, Math.abs(UUID.randomUUID().getLeastSignificantBits()) + 1, reserve, filled, fee, side == LimitOrder.Side.BUY ? bps : 0, at, LimitOrder.State.OPEN);
    }
    private static void seedListing(Database db, CompanyId c) { db.inTransaction(x -> { try (var s=x.prepareStatement("INSERT INTO stock_listings (company_id,stock_code,issue_reference_price_minor,issued_shares,listed_at) VALUES (?,?,?,?,?)")) { s.setString(1,c.value().toString());s.setString(2,"BS000001");s.setLong(3,10);s.setLong(4,1000);s.setString(5,"2026-08-22T00:00:00Z");s.executeUpdate(); } return null; }); }
    private static void seedHolding(Database db, CompanyId c, UUID p, long shares) { db.inTransaction(x -> { try (var s=x.prepareStatement("INSERT INTO share_holdings (company_id,holder_uuid,available_shares,reserved_shares) VALUES (?,?,?,0)")) { s.setString(1,c.value().toString());s.setString(2,p.toString());s.setLong(3,shares);s.executeUpdate(); } return null; }); }
    private static long[] holding(Database db, CompanyId c, UUID p) { try (var x=db.dataSource().getConnection();var s=x.prepareStatement("SELECT available_shares,reserved_shares FROM share_holdings WHERE company_id=? AND holder_uuid=?")) { s.setString(1,c.value().toString());s.setString(2,p.toString());try(var r=s.executeQuery()){r.next();return new long[]{r.getLong(1),r.getLong(2)};}} catch(Exception e){throw new RuntimeException(e);} }
}
