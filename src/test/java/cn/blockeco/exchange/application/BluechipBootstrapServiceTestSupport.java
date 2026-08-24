package cn.blockeco.exchange.application;

import cn.blockeco.exchange.infrastructure.sql.Database;
import cn.blockeco.exchange.infrastructure.sql.SqlBluechipRepository;
import cn.blockeco.exchange.infrastructure.sql.SqlCompanyRepository;
import cn.blockeco.exchange.infrastructure.sql.SqlStockListingRepository;
import cn.blockeco.exchange.paper.BluechipConfig;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;

/** Test-only one-company bluechip bootstrap fixture. */
final class BluechipBootstrapServiceTestSupport {
    private static final UUID SYSTEM = UUID.fromString("00000000-0000-0000-0000-000000000099");
    private final Database database; private final SqlBluechipRepository bluechips; private final Instant now;
    BluechipBootstrapServiceTestSupport(Database database, SqlBluechipRepository bluechips, Instant now) { this.database = database; this.bluechips = bluechips; this.now = now; }
    void initializeOne() {
        // Bootstrap validates a complete ten-company configuration, so seed the standard set and use its first company.
        new BluechipBootstrapService(config(), SYSTEM, new SqlCompanyRepository(database.dataSource()), new SqlStockListingRepository(database.dataSource()), bluechips, database, Runnable::run, () -> now).initializeMissing().toCompletableFuture().join();
    }
    private static BluechipConfig config() {
        var yaml = new YamlConfiguration(); var entries = new java.util.ArrayList<Map<String, Object>>();
        for (int index = 0; index < 10; index++) {
            Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("code", "BC" + index); entry.put("display-name", "System " + index); entry.put("industry", "Industry");
            entry.put("reference-price", "10.00"); entry.put("lower-bound", "8.00"); entry.put("upper-bound", "12.00");
            entry.put("total-shares", 1_000L); entry.put("initial-fund-cash", "1000.00"); entry.put("initial-fund-shares", 100L);
            entry.put("spread-bps", 50); entry.put("event-sensitivity-bps", 100); entry.put("dividend-payout-bps", 2_000);
            entries.add(entry);
        }
        yaml.set("bluechips", entries); return BluechipConfig.load(yaml, 2);
    }
}
