package cn.blockeco.exchange.application;

import static org.assertj.core.api.Assertions.assertThat;

import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.infrastructure.sql.Database;
import cn.blockeco.exchange.infrastructure.sql.SqlBluechipRepository;
import cn.blockeco.exchange.infrastructure.sql.SqlCompanyRepository;
import cn.blockeco.exchange.infrastructure.sql.SqlStockListingRepository;
import cn.blockeco.exchange.paper.BluechipConfig;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class MarketCandleServiceTest {
    @Test void closeWritesIdempotentModelPriceCandleWhenNoTradesOccurred() throws Exception {
        var file = Files.createTempFile("blockstock-candle-", ".db");
        try (var database = new Database("jdbc:sqlite:" + file)) {
            database.migrate(); var repository = new SqlBluechipRepository(database.dataSource());
            var clock = Instant.parse("2026-08-24T00:00:00Z");
            TestBluechipFixture.bootstrap(database, repository, config(), UUID.fromString("00000000-0000-0000-0000-000000000099"), clock);
            var service = new MarketCandleService(repository, database, Runnable::run);
            var day = LocalDate.of(2026, 8, 24);

            service.closeTradingDay(day).toCompletableFuture().join();
            service.closeTradingDay(day).toCompletableFuture().join();

            CompanyId company = repository.all().getFirst().companyId();
            var candle = repository.candle(company, day).orElseThrow();
            assertThat(candle.open().minorUnits()).isEqualTo(1_000);
            assertThat(candle.high().minorUnits()).isEqualTo(1_000);
            assertThat(candle.low().minorUnits()).isEqualTo(1_000);
            assertThat(candle.close().minorUnits()).isEqualTo(1_000);
            assertThat(candle.volumeShares()).isZero();
        } finally { Files.deleteIfExists(file); }
    }
    @Test void candleUsesActualTradeForAllOhlcValuesInsteadOfModelFallback() throws Exception {
        var file = Files.createTempFile("blockstock-candle-trade-", ".db");
        try (var database = new Database("jdbc:sqlite:" + file)) {
            database.migrate(); var repository = new SqlBluechipRepository(database.dataSource()); var now=Instant.parse("2026-08-24T00:00:00Z");
            TestBluechipFixture.bootstrap(database, repository, config(), UUID.fromString("00000000-0000-0000-0000-000000000099"), now);
            var company=repository.all().getFirst().companyId(); String code=repository.all().getFirst().listing().stockCode();
            database.inTransaction(c->{ String buy=UUID.randomUUID().toString(),sell=UUID.randomUUID().toString(); try(var order=c.prepareStatement("INSERT INTO stock_orders (id,company_id,stock_code,player_uuid,side,limit_price_minor,original_shares,remaining_shares,priority_sequence,reserved_cash_minor,filled_notional_minor,fee_charged_minor,fee_bps,accepted_at,state) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)");var s=c.prepareStatement("INSERT INTO stock_trades (id,company_id,stock_code,buy_order_id,sell_order_id,shares,price_minor,notional_minor,buyer_fee_minor,occurred_at) VALUES (?, ?, ?, ?, ?, 1, 1100, 1100, 0, ? )")){ for(int i=0;i<2;i++){order.setString(1,i==0?buy:sell);order.setString(2,company.value().toString());order.setString(3,code);order.setString(4,UUID.randomUUID().toString());order.setString(5,i==0?"BUY":"SELL");order.setLong(6,1100);order.setLong(7,1);order.setLong(8,1);order.setLong(9,i+1);order.setLong(10,i==0?1100:0);order.setLong(11,0);order.setLong(12,0);order.setInt(13,0);order.setString(14,now.toString());order.setString(15,"OPEN");order.executeUpdate();} s.setString(1,UUID.randomUUID().toString());s.setString(2,company.value().toString());s.setString(3,code);s.setString(4,buy);s.setString(5,sell);s.setString(6,now.toString());s.executeUpdate();}return null;});
            new MarketCandleService(repository,database,Runnable::run).closeTradingDay(LocalDate.of(2026,8,24)).toCompletableFuture().join();
            var candle=repository.candle(company,LocalDate.of(2026,8,24)).orElseThrow(); assertThat(candle.open().minorUnits()).isEqualTo(1100); assertThat(candle.high().minorUnits()).isEqualTo(1100); assertThat(candle.low().minorUnits()).isEqualTo(1100); assertThat(candle.close().minorUnits()).isEqualTo(1100);
        } finally { Files.deleteIfExists(file); }
    }
    private static BluechipConfig config() { var yaml = new YamlConfiguration(); java.util.List<java.util.Map<String,Object>> rows = new java.util.ArrayList<>(); for (int i=0;i<10;i++) { var row = new java.util.LinkedHashMap<String,Object>(); row.put("code", i == 0 ? "RDT" : "BC" + i); row.put("display-name", "System " + i); row.put("industry", "Industry " + i); row.put("reference-price", "10.00"); row.put("lower-bound", "8.00"); row.put("upper-bound", "12.00"); row.put("total-shares", 1_000_000L); row.put("initial-fund-cash", "100000.00"); row.put("initial-fund-shares", 100_000L); row.put("spread-bps",50); row.put("event-sensitivity-bps",100); row.put("dividend-payout-bps",2000); rows.add(row); } yaml.set("bluechips", rows); return BluechipConfig.load(yaml,2); }
}
