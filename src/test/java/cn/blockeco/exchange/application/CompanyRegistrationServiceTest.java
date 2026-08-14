package cn.blockeco.exchange.application;

import static org.assertj.core.api.Assertions.assertThat;

import cn.blockeco.exchange.domain.audit.AuditEvent;
import cn.blockeco.exchange.domain.company.Company;
import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.domain.registration.RegistrationSaga;
import cn.blockeco.exchange.domain.registration.RegistrationSagaState;
import cn.blockeco.exchange.ports.AuditLog;
import cn.blockeco.exchange.ports.CompanyRepository;
import cn.blockeco.exchange.ports.EconomyGateway;
import cn.blockeco.exchange.ports.MainThreadExecutor;
import cn.blockeco.exchange.ports.RegistrationSagaRepository;
import cn.blockeco.exchange.ports.TransactionRunner;
import cn.blockeco.exchange.infrastructure.sql.Database;
import cn.blockeco.exchange.infrastructure.sql.SqlAuditLog;
import cn.blockeco.exchange.infrastructure.sql.SqlCompanyRepository;
import cn.blockeco.exchange.infrastructure.sql.SqlRegistrationSagaRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class CompanyRegistrationServiceTest {

    @Test
    void successful_registration_puts_only_capital_in_treasury() {
        RegistrationFixture fixture = RegistrationFixture.standard();
        RegistrationResult result = fixture.register("Red Stone", 50);

        assertThat(result.status()).isEqualTo(RegistrationResult.Status.SUCCESS);
        assertThat(fixture.economy().withdrawn()).isEqualTo(Money.ofMinor(1_100_000));
        assertThat(fixture.savedCompany().treasury()).isEqualTo(Money.ofMinor(1_000_000));
        assertThat(fixture.savedSaga().state()).isEqualTo(RegistrationSagaState.COMPLETED);
        assertThat(fixture.sqlCalls).isGreaterThan(0);
        assertThat(fixture.economy.mainThreadCalls).isEqualTo(1);
    }

    @Test
    void insufficient_funds_creates_no_company() {
        RegistrationFixture fixture = RegistrationFixture.standard();
        fixture.economy.withdrawResult = EconomyGateway.Result.insufficientFunds("balance too low");

        RegistrationResult result = fixture.register("Red Stone", 50);

        assertThat(result.status()).isEqualTo(RegistrationResult.Status.INSUFFICIENT_FUNDS);
        assertThat(fixture.company).isEmpty();
        assertThat(fixture.savedSaga().state()).isEqualTo(RegistrationSagaState.REJECTED);
    }

    @Test
    void duplicate_name_performs_zero_economy_calls() {
        RegistrationFixture fixture = RegistrationFixture.standard();
        fixture.company.put("red stone", fixture.company("Red Stone"));

        RegistrationResult result = fixture.register("Red Stone", 50);

        assertThat(result.status()).isEqualTo(RegistrationResult.Status.DUPLICATE_NAME);
        assertThat(fixture.economy.calls).isZero();
    }

    @Test
    void sql_failure_after_withdrawal_deposits_the_full_amount() {
        RegistrationFixture fixture = RegistrationFixture.standard();
        fixture.failCompanyInsert = true;

        RegistrationResult result = fixture.register("Red Stone", 50);

        assertThat(result.status()).isEqualTo(RegistrationResult.Status.REFUNDED_AFTER_FAILURE);
        assertThat(fixture.economy.withdrawn()).isEqualTo(Money.ofMinor(1_100_000));
        assertThat(fixture.economy.deposited()).isEqualTo(Money.ofMinor(1_100_000));
        assertThat(fixture.savedSaga().state()).isEqualTo(RegistrationSagaState.REFUNDED);
    }

    @Test
    void failed_compensation_ends_in_refund_required() {
        RegistrationFixture fixture = RegistrationFixture.standard();
        fixture.failCompanyInsert = true;
        fixture.economy.depositResult = EconomyGateway.Result.providerFailure("provider down");

        RegistrationResult result = fixture.register("Red Stone", 50);

        assertThat(result.status()).isEqualTo(RegistrationResult.Status.RECOVERY_REQUIRED);
        assertThat(fixture.savedSaga().state()).isEqualTo(RegistrationSagaState.REFUND_REQUIRED);
    }

    @Test
    void recovery_marks_stale_prepared_saga_ambiguous_without_touching_money() {
        RegistrationFixture fixture = RegistrationFixture.standard();
        UUID sagaId = UUID.randomUUID();
        fixture.sagas.put(sagaId, new RegistrationSaga(sagaId, UUID.randomUUID(), "crash window", Money.ofMinor(1_100_000), RegistrationSagaState.PREPARED, null, fixture.now.minusSeconds(60), fixture.now.minusSeconds(60)));

        assertThat(fixture.service.recoverStaleRegistrations(fixture.now).toCompletableFuture().join()).isEqualTo(1);

        assertThat(fixture.sagas.get(sagaId).state()).isEqualTo(RegistrationSagaState.AMBIGUOUS);
        assertThat(fixture.economy.calls).isZero();
    }

    @Test
    void sqlite_name_reservation_rejects_second_request_before_any_second_withdrawal() throws Exception {
        Path file = Files.createTempFile("blockeco-registration-reservation-", ".db");
        try (Database database = new Database("jdbc:sqlite:" + file)) {
            database.migrate();
            CountDownLatch firstAtVault = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            BlockingEconomy economy = new BlockingEconomy(firstAtVault, releaseFirst);
            MainThreadExecutor main = new MainThreadExecutor() {
                @Override public <T> CompletionStage<T> submit(Supplier<T> work) {
                    return CompletableFuture.supplyAsync(work);
                }
            };
            CompanyRegistrationService service = new CompanyRegistrationService(
                    new SqlCompanyRepository(database.dataSource()), new SqlRegistrationSagaRepository(database.dataSource()),
                    new SqlAuditLog(), database, economy, main, Runnable::run, () -> Instant.parse("2026-08-14T12:00:00Z"));
            RegistrationRequest request = new RegistrationRequest(UUID.randomUUID(), "Reserved Name", 50);

            CompletionStage<RegistrationResult> first = service.register(request);
            assertThat(firstAtVault.await(5, TimeUnit.SECONDS)).isTrue();
            RegistrationResult second = service.register(new RegistrationRequest(UUID.randomUUID(), "  reserved   name ", 50)).toCompletableFuture().join();

            assertThat(second.status()).isEqualTo(RegistrationResult.Status.DUPLICATE_NAME);
            assertThat(economy.withdrawCalls).isEqualTo(1);
            releaseFirst.countDown();
            assertThat(first.toCompletableFuture().join().status()).isEqualTo(RegistrationResult.Status.SUCCESS);
        } finally { Files.deleteIfExists(file); }
    }

    private static final class RegistrationFixture {
        private final Map<String, Company> company = new HashMap<>();
        private final Map<UUID, RegistrationSaga> sagas = new HashMap<>();
        private final ProgrammableEconomy economy = new ProgrammableEconomy();
        private final Instant now = Instant.parse("2026-08-14T12:00:00Z");
        private boolean failCompanyInsert;
        private int sqlCalls;
        private final CompanyRegistrationService service;

        private RegistrationFixture() {
            CompanyRepository companies = new CompanyRepository() {
                @Override public void insert(Connection ignored, Company value) {
                    if (failCompanyInsert) throw new IllegalStateException("database unavailable");
                    company.put(value.normalizedName(), value);
                }
                @Override public Optional<Company> findById(CompanyId id) {
                    return company.values().stream().filter(candidate -> candidate.id().equals(id)).findFirst();
                }
                @Override public Optional<Company> findByNormalizedName(String name) { return Optional.ofNullable(company.get(name)); }
            };
            RegistrationSagaRepository sagaRepository = new RegistrationSagaRepository() {
                @Override public void save(Connection ignored, RegistrationSaga saga) { sagas.put(saga.id(), saga); }
                @Override public java.util.List<RegistrationSaga> findPreparedBefore(Instant cutoff) { return sagas.values().stream().filter(saga -> saga.state() == RegistrationSagaState.PREPARED && saga.updatedAt().isBefore(cutoff)).toList(); }
                @Override public java.util.List<RegistrationSaga> findWithdrawnBefore(Instant cutoff) { return sagas.values().stream().filter(saga -> saga.state() == RegistrationSagaState.WITHDRAWN && saga.updatedAt().isBefore(cutoff)).toList(); }
                @Override public void transition(Connection ignored, UUID id, RegistrationSagaState state, String error) {
                    RegistrationSaga prior = sagas.get(id);
                    sagas.put(id, new RegistrationSaga(id, prior.founderId(), prior.companyNormalizedName(), prior.totalWithdrawal(), state, error, prior.createdAt(), now));
                }
            };
            AuditLog audit = (ignored, event) -> { };
            TransactionRunner transactions = new TransactionRunner() {
                @Override public <T> T inTransaction(SqlWork<T> work) {
                    sqlCalls++;
                    try { return work.execute(null); }
                    catch (Exception e) { throw e instanceof RuntimeException runtime ? runtime : new IllegalStateException(e); }
                }
            };
            MainThreadExecutor mainThread = new MainThreadExecutor() {
                @Override public <T> CompletionStage<T> submit(Supplier<T> work) {
                    economy.mainThreadCalls++;
                    return CompletableFuture.completedFuture(work.get());
                }
            };
            service = new CompanyRegistrationService(companies, sagaRepository, audit, transactions, economy, mainThread,
                    Runnable::run, () -> now);
        }

        static RegistrationFixture standard() { return new RegistrationFixture(); }
        RegistrationResult register(String name, int dividendRate) {
            return service.register(new RegistrationRequest(UUID.fromString("9fbb8514-5773-41c4-aa72-6e6a1e47f5c2"), name, dividendRate)).toCompletableFuture().join();
        }
        Company savedCompany() { return company.values().iterator().next(); }
        RegistrationSaga savedSaga() { return sagas.values().iterator().next(); }
        Company company(String name) { return Company.register(new CompanyId(UUID.randomUUID()), name, UUID.randomUUID(), Money.zero(), cn.blockeco.exchange.domain.company.DividendRate.FIFTY, now); }
        ProgrammableEconomy economy() { return economy; }
    }

    private static final class ProgrammableEconomy implements EconomyGateway {
        private EconomyGateway.Result withdrawResult = EconomyGateway.Result.success("");
        private EconomyGateway.Result depositResult = EconomyGateway.Result.success("");
        private Money withdrawn = Money.zero(); private Money deposited = Money.zero(); private int calls; private int mainThreadCalls;
        @Override public Result withdraw(UUID player, Money amount) { calls++; withdrawn = amount; return withdrawResult; }
        @Override public Result deposit(UUID player, Money amount) { calls++; deposited = amount; return depositResult; }
        Money withdrawn() { return withdrawn; } Money deposited() { return deposited; }
    }

    private static final class BlockingEconomy implements EconomyGateway {
        private final CountDownLatch entered; private final CountDownLatch release; private int withdrawCalls;
        private BlockingEconomy(CountDownLatch entered, CountDownLatch release) { this.entered = entered; this.release = release; }
        @Override public Result withdraw(UUID player, Money amount) {
            withdrawCalls++; entered.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout"); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
            return Result.success("");
        }
        @Override public Result deposit(UUID player, Money amount) { return Result.success(""); }
    }
}
