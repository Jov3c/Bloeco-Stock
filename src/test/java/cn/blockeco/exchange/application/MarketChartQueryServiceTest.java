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
}
