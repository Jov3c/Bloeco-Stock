package cn.blockeco.exchange.infrastructure.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.blockeco.exchange.application.BluechipBootstrapService;
import cn.blockeco.exchange.application.BluechipBootstrapFundingService;
import cn.blockeco.exchange.domain.bluechip.QuantDecision;
import cn.blockeco.exchange.domain.bluechip.QuantRiskState;
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
    void persistsQuantRiskAndAppendOnlyDecisionsWhileReadingOnlyActiveApplicableEvents() throws Exception {
        var file = Files.createTempFile("blockstock-bluechip-quant-", ".db");
        Instant now = Instant.parse("2026-08-31T10:00:00Z");
        try (Database database = new Database("jdbc:sqlite:" + file)) {
            database.migrate();
            SqlBluechipRepository repository = new SqlBluechipRepository(database.dataSource());

            database.inTransaction(connection -> {
                repository.saveQuantRisk(connection, new QuantRiskState("NOVA", 2, 3, now.plusSeconds(30), now));
                repository.recordQuantDecision(connection, new QuantDecision("decision-1", "NOVA", "BOOK_IMBALANCE", 6_500,
                        "BUY", 12, 8, 0, 2, now));
                return null;
            });
            insertEvent(database, "market", "MARKET", null, null, 100, now.minusSeconds(1), now.plusSeconds(60));
            insertEvent(database, "industry", "INDUSTRY", null, "Manufacturing", 25, now.minusSeconds(1), now.plusSeconds(60));
            insertEvent(database, "expired", "MARKET", null, null, 9_999, now.minusSeconds(60), now.minusSeconds(1));

            assertThat(repository.loadQuantRisk("NOVA")).contains(new QuantRiskState("NOVA", 2, 3, now.plusSeconds(30), now));
            assertThat(repository.quantDecisions("NOVA", 10)).containsExactly(new QuantDecision("decision-1", "NOVA", "BOOK_IMBALANCE", 6_500,
                    "BUY", 12, 8, 0, 2, now));
            assertThat(repository.activeEventImpactBps("NOVA", "Manufacturing", now)).isEqualTo(125);
        } finally { Files.deleteIfExists(file); }
    }
    @Test
    void repositoryRejectsCashFundMutationEvenWhenCalledOutsideTheAdminCommand() throws Exception {
        var file = Files.createTempFile("blockstock-bluechip-cash-", ".db");
        try (Database database = new Database("jdbc:sqlite:" + file)) {
            database.migrate(); var repository = new SqlBluechipRepository(database.dataSource());
            assertThatThrownBy(() -> database.inTransaction(c -> { repository.adjustFund(c, "UNKNOWN", "cash", 1, Instant.EPOCH); return null; }))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cash");
        } finally { Files.deleteIfExists(file); }
    }
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

    private static void insertEvent(Database database, String id, String scope, String companyId, String industry, int impact,
                                    Instant startsAt, Instant endsAt) throws Exception {
        try (var connection = database.dataSource().getConnection();
             var statement = connection.prepareStatement("INSERT INTO bluechip_events (id, scope, company_id, industry, headline, body, price_impact_bps, profit_impact_bps, starts_at, ends_at, state) VALUES (?, ?, ?, ?, 'test', 'test', ?, 0, ?, ?, 'ACTIVE')")) {
            statement.setString(1, id); statement.setString(2, scope); statement.setString(3, companyId); statement.setString(4, industry);
            statement.setInt(5, impact); statement.setString(6, startsAt.toString()); statement.setString(7, endsAt.toString()); statement.executeUpdate();
        }
    }
}
