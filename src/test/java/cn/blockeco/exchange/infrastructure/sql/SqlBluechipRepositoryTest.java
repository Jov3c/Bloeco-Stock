package cn.blockeco.exchange.infrastructure.sql;

import static org.assertj.core.api.Assertions.assertThat;

import cn.blockeco.exchange.application.BluechipBootstrapService;
import cn.blockeco.exchange.application.BluechipBootstrapFundingService;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.paper.BluechipConfig;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class SqlBluechipRepositoryTest {
    @Test
    void findsSeededBluechipByItsAllocatedStockCodeWithItsFiniteBalances() throws Exception {
        var file = Files.createTempFile("blockstock-bluechip-repository-", ".db");
        try (Database database = new Database("jdbc:sqlite:" + file)) {
            database.migrate();
            SqlBluechipRepository repository = new SqlBluechipRepository(database.dataSource());
            UUID system = UUID.fromString("00000000-0000-0000-0000-000000000099");
            var records = new SqlBluechipBootstrapFundingRepository(database.dataSource());
            var funding = new BluechipBootstrapFundingService(system, records, database, new BluechipBootstrapFundingService.EscrowEconomy() {
                @Override public cn.blockeco.exchange.ports.EconomyGateway.Result withdraw(UUID player, Money amount) { return cn.blockeco.exchange.ports.EconomyGateway.Result.success("ok"); }
                @Override public cn.blockeco.exchange.ports.EconomyGateway.Result deposit(UUID player, Money amount) { return cn.blockeco.exchange.ports.EconomyGateway.Result.success("ok"); }
                @Override public cn.blockeco.exchange.ports.EconomyGateway.Result depositEscrow(Money amount) { return cn.blockeco.exchange.ports.EconomyGateway.Result.success("ok"); }
            }, new cn.blockeco.exchange.ports.MainThreadExecutor() { @Override public <T> java.util.concurrent.CompletionStage<T> submit(java.util.function.Supplier<T> work) { return java.util.concurrent.CompletableFuture.completedFuture(work.get()); } }, () -> Instant.parse("2026-08-24T00:00:00Z"));
            BluechipBootstrapService service = new BluechipBootstrapService(config(), system,
                    new SqlCompanyRepository(database.dataSource()), new SqlStockListingRepository(database.dataSource()), repository,
                    new SqlSecuritiesCashRepository(database.dataSource()), funding, records, database, Runnable::run, () -> Instant.parse("2026-08-24T00:00:00Z"));

            service.initializeMissing().toCompletableFuture().join();
            var seeded = repository.all().getFirst();

            assertThat(repository.findByStockCode(seeded.listing().stockCode())).contains(seeded);
            assertThat(seeded.fundCash()).isEqualTo(Money.ofMinor(10_000_000));
            assertThat(seeded.fundShares()).isEqualTo(100_000);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static BluechipConfig config() {
        YamlConfiguration yaml = new YamlConfiguration(); List<Map<String, Object>> entries = new ArrayList<>();
        for (int index = 0; index < 10; index++) { Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("code", "BC" + index); entry.put("display-name", "System Company " + index); entry.put("industry", "Industry " + index);
            entry.put("reference-price", "10.00"); entry.put("lower-bound", "8.00"); entry.put("upper-bound", "12.00"); entry.put("total-shares", 1_000_000L);
            entry.put("initial-fund-cash", "100000.00"); entry.put("initial-fund-shares", 100_000L); entry.put("spread-bps", 50); entry.put("event-sensitivity-bps", 100); entry.put("dividend-payout-bps", 2_000); entries.add(entry); }
        yaml.set("bluechips", entries); return BluechipConfig.load(yaml, 2);
    }
}
