package cn.blockeco.exchange.application;

import static org.assertj.core.api.Assertions.assertThat;

import cn.blockeco.exchange.infrastructure.sql.Database;
import cn.blockeco.exchange.infrastructure.sql.SqlBluechipRepository;
import cn.blockeco.exchange.paper.BluechipConfig;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class MarketChartQueryServiceTest {
    @Test void exposesOrderedThirtyMinuteIntradayPointsFromPersistedTradesInMarketTimezone() throws Exception {
        var file = Files.createTempFile("blockstock-intraday-chart-", ".db");
        try (var database = new Database("jdbc:sqlite:" + file)) {
            database.migrate(); var repository = new SqlBluechipRepository(database.dataSource());
            TestBluechipFixture.bootstrap(database, repository, config(), UUID.fromString("00000000-0000-0000-0000-000000000099"), Instant.parse("2026-08-24T00:00:00Z"));
            var bluechip = repository.all().getFirst();
            insertTrade(database, bluechip, "2026-08-24T00:05:00Z", 1_010, 3, 1);
            insertTrade(database, bluechip, "2026-08-24T00:25:00Z", 1_020, 4, 2);
            insertTrade(database, bluechip, "2026-08-24T00:31:00Z", 1_005, 5, 3);
            insertTrade(database, bluechip, "2026-08-24T12:01:00Z", 1_100, 6, 4); // after the 20:00 Shanghai close

            var chart = new MarketChartQueryService(repository, Runnable::run, Clock.fixed(Instant.parse("2026-08-24T04:00:00Z"), ZoneId.of("UTC")), ZoneId.of("Asia/Shanghai"))
                    .chart(bluechip.listing().stockCode()).toCompletableFuture().join().orElseThrow();

            assertThat(chart.intradayPoints()).extracting(MarketChart.IntradayPoint::label, MarketChart.IntradayPoint::close, MarketChart.IntradayPoint::volumeShares)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple("08:00", cn.blockeco.exchange.domain.money.Money.ofMinor(1_020), 7L),
                            org.assertj.core.groups.Tuple.tuple("08:30", cn.blockeco.exchange.domain.money.Money.ofMinor(1_005), 5L));
        } finally { Files.deleteIfExists(file); }
    }
    @Test void exposesLatestDailyKlineAndConfiguredZoneSessionSummaryForBluechip() throws Exception {
        var file = Files.createTempFile("blockstock-chart-", ".db");
        try (var database = new Database("jdbc:sqlite:" + file)) {
            database.migrate(); var repository = new SqlBluechipRepository(database.dataSource());
            TestBluechipFixture.bootstrap(database, repository, config(), UUID.fromString("00000000-0000-0000-0000-000000000099"), Instant.parse("2026-08-24T00:00:00Z"));
            var bluechip = repository.all().getFirst();
            database.inTransaction(c -> { repository.closeCandle(c, bluechip.companyId(), java.time.LocalDate.of(2026, 8, 23)); return null; });

            var chart = new MarketChartQueryService(repository, Runnable::run, Clock.fixed(Instant.parse("2026-08-24T12:00:00Z"), ZoneId.of("UTC")), ZoneId.of("Asia/Shanghai"))
                    .chart(bluechip.listing().stockCode()).toCompletableFuture().join().orElseThrow();

            assertThat(chart.dailyCandles()).hasSize(1);
            assertThat(chart.dailyCandles().getFirst().day()).isEqualTo(java.time.LocalDate.of(2026, 8, 23));
            assertThat(chart.sessionDay()).isEqualTo(java.time.LocalDate.of(2026, 8, 24));
            assertThat(chart.sessionSummary().close().minorUnits()).isEqualTo(1_000);
        } finally { Files.deleteIfExists(file); }
    }
    private static BluechipConfig config() { var yaml = new YamlConfiguration(); var rows = new java.util.ArrayList<java.util.Map<String,Object>>(); for (int i=0;i<10;i++) { var row = new java.util.LinkedHashMap<String,Object>(); row.put("code", i == 0 ? "RDT" : "BC" + i); row.put("display-name", "System " + i); row.put("industry", "Industry " + i); row.put("reference-price", "10.00"); row.put("lower-bound", "8.00"); row.put("upper-bound", "12.00"); row.put("total-shares", 1_000_000L); row.put("initial-fund-cash", "100000.00"); row.put("initial-fund-shares", 100_000L); row.put("spread-bps",50); row.put("event-sensitivity-bps",100); row.put("dividend-payout-bps",2000); rows.add(row); } yaml.set("bluechips", rows); return BluechipConfig.load(yaml,2); }
    private static void insertTrade(Database database, cn.blockeco.exchange.ports.BluechipRepository.BluechipCompany company, String occurredAt, long price, long shares, int priority) {
        database.inTransaction(connection -> {
            String buy = UUID.randomUUID().toString(), sell = UUID.randomUUID().toString();
            try (var order = connection.prepareStatement("INSERT INTO stock_orders (id,company_id,stock_code,player_uuid,side,limit_price_minor,original_shares,remaining_shares,priority_sequence,reserved_cash_minor,filled_notional_minor,fee_charged_minor,fee_bps,accepted_at,state) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)");
                 var trade = connection.prepareStatement("INSERT INTO stock_trades (id,company_id,stock_code,buy_order_id,sell_order_id,shares,price_minor,notional_minor,buyer_fee_minor,occurred_at) VALUES (?,?,?,?,?,?,?,?,?,?)")) {
                long notional = Math.multiplyExact(price, shares);
                insertOrder(order, buy, company, "BUY", price, shares, priority * 2L - 1, notional, occurredAt);
                insertOrder(order, sell, company, "SELL", price, shares, priority * 2L, 0, occurredAt);
                trade.setString(1, UUID.randomUUID().toString()); trade.setString(2, company.companyId().value().toString()); trade.setString(3, company.listing().stockCode()); trade.setString(4, buy); trade.setString(5, sell); trade.setLong(6, shares); trade.setLong(7, price); trade.setLong(8, notional); trade.setLong(9, 0); trade.setString(10, occurredAt); trade.executeUpdate();
            }
            return null;
        });
    }
    private static void insertOrder(java.sql.PreparedStatement order, String id, cn.blockeco.exchange.ports.BluechipRepository.BluechipCompany company, String side, long price, long shares, long sequence, long filledNotional, String acceptedAt) throws java.sql.SQLException {
        order.setString(1, id); order.setString(2, company.companyId().value().toString()); order.setString(3, company.listing().stockCode()); order.setString(4, UUID.randomUUID().toString()); order.setString(5, side); order.setLong(6, price); order.setLong(7, shares); order.setLong(8, 0); order.setLong(9, sequence); order.setLong(10, 0); order.setLong(11, filledNotional); order.setLong(12, 0); order.setInt(13, 0); order.setString(14, acceptedAt); order.setString(15, "FILLED"); order.executeUpdate();
    }
}
