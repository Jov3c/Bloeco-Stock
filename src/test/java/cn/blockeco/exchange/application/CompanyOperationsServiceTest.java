package cn.blockeco.exchange.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.finance.AssetBinding;
import cn.blockeco.exchange.domain.finance.AssetBindingState;
import cn.blockeco.exchange.domain.finance.OperatingEventKind;
import cn.blockeco.exchange.domain.finance.VerifiedOperatingEvent;
import cn.blockeco.exchange.infrastructure.sql.Database;
import cn.blockeco.exchange.infrastructure.sql.SqlAssetBindingRepository;
import cn.blockeco.exchange.infrastructure.sql.SqlCompanyOperationsRepository;
import cn.blockeco.exchange.ports.CompanyOperatingEventSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CompanyOperationsServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-28T12:00:00Z");

    @Test void acceptsOnlyEventsFromTheBindingAdapterAndDoesNotRepeatAnExternalKey() throws Exception {
        try (Fixture fixture = Fixture.create()) {
            CompanyOperatingEventSource shop = source("shop", List.of(
                    event("shop", "sale-1", 40, NOW.minusSeconds(10)),
                    event("other", "ignored", 20, NOW.minusSeconds(5))));
            CompanyOperationsService service = fixture.service(List.of(shop));

            assertThat(service.ingestDueEvents().toCompletableFuture().join())
                    .isEqualTo(new CompanyOperationsService.IngestionResult(1, 0, 1, 0));
            assertThat(service.ingestDueEvents().toCompletableFuture().join())
                    .isEqualTo(new CompanyOperationsService.IngestionResult(0, 1, 1, 0));
            assertThat(fixture.snapshot()).isEqualTo(new cn.blockeco.exchange.ports.CompanyOperationsRepository.FinancialSnapshot(
                    fixture.company, 140, 40, 0, 40, 0));
        }
    }

    @Test void oneFailingOptionalSourceDoesNotStopAnotherSource() throws Exception {
        try (Fixture fixture = Fixture.create()) {
            AssetBinding workingBinding = fixture.binding("working", NOW.minusSeconds(2));
            fixture.binding("broken", NOW.minusSeconds(1));
            CompanyOperatingEventSource working = source("working", List.of(event("working", "sale-1", 40, NOW.minusSeconds(10))));
            CompanyOperatingEventSource broken = new CompanyOperatingEventSource() {
                public String adapterId() { return "broken"; }
                public List<VerifiedOperatingEvent> readSince(AssetBinding binding, Instant after, Instant through) { throw new IllegalStateException("optional plugin unavailable"); }
            };

            assertThat(fixture.service(List.of(working, broken)).ingestDueEvents().toCompletableFuture().join())
                    .isEqualTo(new CompanyOperationsService.IngestionResult(1, 0, 0, 1));
            assertThat(fixture.snapshot()).isEqualTo(new cn.blockeco.exchange.ports.CompanyOperationsRepository.FinancialSnapshot(
                    fixture.company, 140, 40, 0, 40, 0));
            assertThat(workingBinding.adapterId()).isEqualTo("working");
        }
    }

    @Test void rejectsWrongAdapterNegativeAmountAndFutureEvent() throws Exception {
        try (Fixture fixture = Fixture.create()) {
            CompanyOperatingEventSource source = source("shop", List.of(
                    event("other", "wrong-adapter", 10, NOW.minusSeconds(1)),
                    event("shop", "future", 10, NOW.plusSeconds(1))));

            assertThatIllegalArgumentException().isThrownBy(() -> event("shop", "negative", -1, NOW.minusSeconds(1)));
            assertThat(fixture.service(List.of(source)).ingestDueEvents().toCompletableFuture().join())
                    .isEqualTo(new CompanyOperationsService.IngestionResult(0, 0, 2, 0));
            assertThat(fixture.snapshot()).isEqualTo(new cn.blockeco.exchange.ports.CompanyOperationsRepository.FinancialSnapshot(
                    fixture.company, 100, 0, 0, 0, 0));
        }
    }

    private static CompanyOperatingEventSource source(String adapterId, List<VerifiedOperatingEvent> events) {
        return new CompanyOperatingEventSource() {
            public String adapterId() { return adapterId; }
            public List<VerifiedOperatingEvent> readSince(AssetBinding binding, Instant after, Instant through) { return events; }
        };
    }

    private static VerifiedOperatingEvent event(String adapterId, String key, long amount, Instant occurredAt) {
        return new VerifiedOperatingEvent(adapterId, key, OperatingEventKind.INCOME, amount, occurredAt, "completed sale");
    }

    private static final class Fixture implements AutoCloseable {
        private final Path file; private final Database database; private final CompanyId company;
        private Fixture(Path file, Database database, CompanyId company) { this.file = file; this.database = database; this.company = company; }
        static Fixture create() throws Exception {
            Path file = Files.createTempFile("company-operations-service-", ".db");
            Database database = new Database("jdbc:sqlite:" + file); database.migrate();
            return new Fixture(file, database, Fixtures.company(database, 100));
        }
        AssetBinding binding(String adapterId, Instant createdAt) {
            AssetBinding binding = new AssetBinding(UUID.randomUUID(), company, adapterId, adapterId + "-1", UUID.randomUUID(), AssetBindingState.ACTIVE, createdAt);
            database.inTransaction(connection -> { new SqlAssetBindingRepository(database.dataSource()).insertActive(connection, binding); return null; });
            return binding;
        }
        CompanyOperationsService service(List<CompanyOperatingEventSource> sources) {
            if (new SqlAssetBindingRepository(database.dataSource()).allActive().isEmpty()) binding("shop", NOW.minusSeconds(3));
            return new CompanyOperationsService(new SqlAssetBindingRepository(database.dataSource()), new SqlCompanyOperationsRepository(database.dataSource()), database, sources, () -> NOW);
        }
        cn.blockeco.exchange.ports.CompanyOperationsRepository.FinancialSnapshot snapshot() { return new SqlCompanyOperationsRepository(database.dataSource()).snapshot(company).orElseThrow(); }
        public void close() throws Exception { database.close(); Files.deleteIfExists(file); }
    }
}
