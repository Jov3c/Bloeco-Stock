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
import cn.blockeco.exchange.paper.CompanyCreationRules;
import cn.blockeco.exchange.paper.MutableCompanyCreationRules;
import cn.blockeco.exchange.infrastructure.sql.SqlAuditLog;
import cn.blockeco.exchange.infrastructure.sql.SqlCompanyRepository;
import cn.blockeco.exchange.infrastructure.sql.SqlCompanyFinanceRepository;
import cn.blockeco.exchange.infrastructure.sql.SqlRegistrationSagaRepository;
import cn.blockeco.exchange.ports.CompanyFinanceRepository;
import cn.blockeco.exchange.ports.TreasuryEscrowGateway;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
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
    void registration_withdraws_once_then_escrows_only_capital_before_creating_company_finance() throws Exception {
        Path file = Files.createTempFile("blockeco-registration-escrow-", ".db");
        try (Database database = new Database("jdbc:sqlite:" + file)) {
            database.migrate();
            Instant now = Instant.parse("2026-08-14T12:00:00Z");
            ProgrammableEconomy economy = new ProgrammableEconomy();
            RecordingRegistrationEscrow escrow = new RecordingRegistrationEscrow();
            CompanyFinanceRepository finance = new SqlCompanyFinanceRepository(database.dataSource());
            CompanyRegistrationService service = new CompanyRegistrationService(
                    new SqlCompanyRepository(database.dataSource()), new SqlRegistrationSagaRepository(database.dataSource(), () -> now),
                    new SqlAuditLog(), database, economy, directMain(), Runnable::run, () -> now,
                    Money.ofMinor(100), Money.ofMinor(1_000), finance, escrow, 2_000);

            RegistrationResult result = service.register(new RegistrationRequest(UUID.randomUUID(), "Escrow Guild", Money.ofMinor(1_500), 50)).toCompletableFuture().join();

            assertThat(result.status()).isEqualTo(RegistrationResult.Status.SUCCESS);
            assertThat(economy.calls).isEqualTo(1);
            assertThat(economy.withdrawn()).isEqualTo(Money.ofMinor(1_600));
            assertThat(escrow.deposits).isEqualTo(1);
            assertThat(escrow.deposited).isEqualTo(Money.ofMinor(1_500));
            try (Connection c = database.dataSource().getConnection(); var statement = c.prepareStatement("SELECT cash_minor, paid_in_capital_minor, (SELECT total_shares FROM companies), (SELECT available_shares FROM share_holdings) FROM company_cash_accounts"); var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getLong(1)).isEqualTo(1_500); assertThat(rows.getLong(2)).isEqualTo(1_500);
                assertThat(rows.getLong(3)).isEqualTo(2_000); assertThat(rows.getLong(4)).isEqualTo(2_000);
            }
        } finally { Files.deleteIfExists(file); }
    }

    @Test
    void escrow_failure_leaves_no_company_or_finance_records() throws Exception {
        Path file = Files.createTempFile("blockeco-registration-escrow-failure-", ".db");
        try (Database database = new Database("jdbc:sqlite:" + file)) {
            database.migrate();
            Instant now = Instant.parse("2026-08-14T12:00:00Z");
            RecordingRegistrationEscrow escrow = new RecordingRegistrationEscrow(); escrow.depositResult = EconomyGateway.Result.providerFailure("timeout");
            CompanyRegistrationService service = new CompanyRegistrationService(
                    new SqlCompanyRepository(database.dataSource()), new SqlRegistrationSagaRepository(database.dataSource(), () -> now),
                    new SqlAuditLog(), database, new ProgrammableEconomy(), directMain(), Runnable::run, () -> now,
                    Money.ofMinor(100), Money.ofMinor(1_000), new SqlCompanyFinanceRepository(database.dataSource()), escrow);

            RegistrationResult result = service.register(new RegistrationRequest(UUID.randomUUID(), "Unescrowed Guild", Money.ofMinor(1_500), 50)).toCompletableFuture().join();

            assertThat(result.status()).isEqualTo(RegistrationResult.Status.RECOVERY_REQUIRED);
            try (Connection c = database.dataSource().getConnection(); var statement = c.prepareStatement("SELECT (SELECT COUNT(*) FROM companies), (SELECT COUNT(*) FROM company_cash_accounts), (SELECT COUNT(*) FROM share_holdings)"); var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isZero(); assertThat(rows.getInt(2)).isZero(); assertThat(rows.getInt(3)).isZero();
            }
        } finally { Files.deleteIfExists(file); }
    }

    @Test
    void safe_registration_rejects_missing_capital_before_vault() throws Exception {
        Path file = Files.createTempFile("blockeco-registration-missing-capital-", ".db");
        try (Database database = new Database("jdbc:sqlite:" + file)) {
            database.migrate();
            ProgrammableEconomy economy = new ProgrammableEconomy();
            Instant now = Instant.parse("2026-08-14T12:00:00Z");
            CompanyRegistrationService service = new CompanyRegistrationService(new SqlCompanyRepository(database.dataSource()), new SqlRegistrationSagaRepository(database.dataSource(), () -> now), new SqlAuditLog(), database, economy, directMain(), Runnable::run, () -> now, Money.ofMinor(100), Money.ofMinor(1_000), new SqlCompanyFinanceRepository(database.dataSource()), new RecordingRegistrationEscrow());

            RegistrationResult result = service.register(new RegistrationRequest(UUID.randomUUID(), "No Capital", Money.zero(), 50)).toCompletableFuture().join();

            assertThat(result.status()).isEqualTo(RegistrationResult.Status.PROVIDER_FAILURE);
            assertThat(economy.calls).isZero();
        } finally { Files.deleteIfExists(file); }
    }

    @Test
    void live_minimum_changes_apply_before_vault_to_the_next_registration_only() throws Exception {
        Path file = Files.createTempFile("blockeco-live-minimum-", ".db");
        try (Database database = new Database("jdbc:sqlite:" + file)) {
            database.migrate(); Instant now = Instant.parse("2026-08-22T01:02:03Z"); ProgrammableEconomy economy = new ProgrammableEconomy();
            MutableCompanyCreationRules live = new MutableCompanyCreationRules(new CompanyCreationRules(Money.ofMinor(100), Money.ofMinor(1_000), 2, 1_000, List.of(50)));
            CompanyRegistrationService service = new CompanyRegistrationService(new SqlCompanyRepository(database.dataSource()), new SqlRegistrationSagaRepository(database.dataSource(), () -> now), new SqlAuditLog(), database, economy, directMain(), Runnable::run, () -> now, Money.ofMinor(100), live::current, new SqlCompanyFinanceRepository(database.dataSource()), new RecordingRegistrationEscrow(), 1_000);
            live.replaceMinimumCapital(Money.ofMinor(500));
            assertThat(service.register(new RegistrationRequest(UUID.randomUUID(), "Raised", Money.ofMinor(499), 50)).toCompletableFuture().join().status()).isEqualTo(RegistrationResult.Status.PROVIDER_FAILURE);
            assertThat(economy.calls).isZero();
            live.replaceMinimumCapital(Money.ofMinor(400));
            assertThat(service.register(new RegistrationRequest(UUID.randomUUID(), "Lowered", Money.ofMinor(499), 50)).toCompletableFuture().join().status()).isEqualTo(RegistrationResult.Status.SUCCESS);
            assertThat(economy.calls).isEqualTo(1);
        } finally { Files.deleteIfExists(file); }
    }

    @Test
    void in_flight_registration_keeps_the_minimum_snapshot_taken_before_vault() throws Exception {
        Path file = Files.createTempFile("blockeco-live-minimum-snapshot-", ".db");
        try (Database database = new Database("jdbc:sqlite:" + file)) {
            database.migrate(); Instant now = Instant.parse("2026-08-22T01:02:03Z"); CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            BlockingEconomy economy = new BlockingEconomy(entered, release); MutableCompanyCreationRules live = new MutableCompanyCreationRules(new CompanyCreationRules(Money.ofMinor(100), Money.ofMinor(1_000), 2, 1_000, List.of(50)));
            MainThreadExecutor asyncMain = new MainThreadExecutor() { @Override public <T> CompletionStage<T> submit(Supplier<T> work) { return CompletableFuture.supplyAsync(work); } };
            CompanyRegistrationService service = new CompanyRegistrationService(new SqlCompanyRepository(database.dataSource()), new SqlRegistrationSagaRepository(database.dataSource(), () -> now), new SqlAuditLog(), database, economy, asyncMain, Runnable::run, () -> now, Money.ofMinor(100), live::current, null, null, 1_000);
            var result = service.register(new RegistrationRequest(UUID.randomUUID(), "Snapshot", 50));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue(); live.replaceMinimumCapital(Money.ofMinor(5_000)); release.countDown();
            assertThat(result.toCompletableFuture().join().status()).isEqualTo(RegistrationResult.Status.SUCCESS);
            assertThat(economy.withdrawn).isEqualTo(Money.ofMinor(1_100));
        } finally { Files.deleteIfExists(file); }
    }

    @Test
    void escrow_deposited_name_reservation_is_reported_as_duplicate_before_vault() throws Exception {
        Path file = Files.createTempFile("blockeco-escrow-name-reservation-", ".db");
        try (Database database = new Database("jdbc:sqlite:" + file)) {
            database.migrate();
            Instant now = Instant.parse("2026-08-14T12:00:00Z");
            SqlRegistrationSagaRepository sagas = new SqlRegistrationSagaRepository(database.dataSource(), () -> now);
            database.inTransaction(c -> { sagas.save(c, new RegistrationSaga(UUID.randomUUID(), UUID.randomUUID(), "escrow guild", Money.ofMinor(1_600), RegistrationSagaState.ESCROW_DEPOSITED, null, now, now)); return null; });
            ProgrammableEconomy economy = new ProgrammableEconomy();
            CompanyRegistrationService service = new CompanyRegistrationService(new SqlCompanyRepository(database.dataSource()), sagas, new SqlAuditLog(), database, economy, directMain(), Runnable::run, () -> now, Money.ofMinor(100), Money.ofMinor(1_000), new SqlCompanyFinanceRepository(database.dataSource()), new RecordingRegistrationEscrow());

            RegistrationResult result = service.register(new RegistrationRequest(UUID.randomUUID(), "Escrow Guild", Money.ofMinor(1_500), 50)).toCompletableFuture().join();

            assertThat(result.status()).isEqualTo(RegistrationResult.Status.DUPLICATE_NAME);
            assertThat(economy.calls).isZero();
        } finally { Files.deleteIfExists(file); }
    }

    @Test
    void recovery_marks_escrow_backed_withdrawal_ambiguous_without_another_vault_action() throws Exception {
        Path file = Files.createTempFile("blockeco-escrow-withdrawn-recovery-", ".db");
        try (Database database = new Database("jdbc:sqlite:" + file)) {
            database.migrate();
            Instant now = Instant.parse("2026-08-14T12:00:00Z");
            SqlRegistrationSagaRepository sagas = new SqlRegistrationSagaRepository(database.dataSource(), () -> now);
            RegistrationSaga saga = new RegistrationSaga(UUID.randomUUID(), UUID.randomUUID(), "interrupted escrow", Money.ofMinor(1_600), RegistrationSagaState.WITHDRAWN, null, now.minusSeconds(10), now.minusSeconds(10), true);
            database.inTransaction(c -> { sagas.save(c, saga); return null; });
            ProgrammableEconomy economy = new ProgrammableEconomy();
            CompanyRegistrationService service = new CompanyRegistrationService(new SqlCompanyRepository(database.dataSource()), sagas, new SqlAuditLog(), database, economy, directMain(), Runnable::run, () -> now, Money.ofMinor(100), Money.ofMinor(1_000), new SqlCompanyFinanceRepository(database.dataSource()), new RecordingRegistrationEscrow());

            assertThat(service.recoverStaleRegistrations(now).toCompletableFuture().join()).isEqualTo(1);
            assertThat(economy.calls).isZero();
            try (Connection c = database.dataSource().getConnection(); var statement = c.prepareStatement("SELECT state FROM registration_sagas"); var rows = statement.executeQuery()) { assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("AMBIGUOUS"); }
        } finally { Files.deleteIfExists(file); }
    }

    @Test
    void completed_registration_uses_requested_paid_in_capital() {
        RegistrationFixture fixture = RegistrationFixture.standard();
        Money paidInCapital = Money.ofMinor(1_234_567);

        RegistrationResult result = fixture.service.register(new RegistrationRequest(UUID.randomUUID(), "Capital Guild", paidInCapital, 50)).toCompletableFuture().join();

        assertThat(result.status()).isEqualTo(RegistrationResult.Status.SUCCESS);
        assertThat(fixture.economy().withdrawn()).isEqualTo(Money.ofMinor(1_334_567));
        assertThat(fixture.savedCompany().treasury()).isEqualTo(paidInCapital);
    }

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
    void registration_audits_each_successful_saga_transition_in_order() throws Exception {
        Path file = Files.createTempFile("blockeco-registration-audit-", ".db");
        try (Database database = new Database("jdbc:sqlite:" + file)) {
            database.migrate();
            Instant now = Instant.parse("2026-08-14T12:00:00Z");
            CompanyRegistrationService service = new CompanyRegistrationService(
                    new SqlCompanyRepository(database.dataSource()), new SqlRegistrationSagaRepository(database.dataSource(), () -> now),
                    new SqlAuditLog(), database, new ProgrammableEconomy(), directMain(), Runnable::run, () -> now, Money.ofMinor(1), Money.ofMinor(2));
            assertThat(service.register(new RegistrationRequest(UUID.randomUUID(), "Audit Guild", 50)).toCompletableFuture().join().status())
                    .isEqualTo(RegistrationResult.Status.SUCCESS);
            try (Connection connection = database.dataSource().getConnection(); var statement = connection.prepareStatement("SELECT event_type, payload_json FROM audit_events ORDER BY sequence"); var rows = statement.executeQuery()) {
                java.util.List<String> types = new java.util.ArrayList<>();
                while (rows.next()) { types.add(rows.getString(1)); if (!"COMPANY_REGISTERED".equals(rows.getString(1))) assertThat(rows.getString(2)).contains("sagaId", "fromState", "toState", "totalWithdrawalMinor", "reason"); }
                assertThat(types).containsExactly("COMPANY_REGISTRATION_PREPARED", "COMPANY_REGISTRATION_WITHDRAWN", "COMPANY_REGISTERED", "COMPANY_REGISTRATION_COMPLETED");
            }
        } finally { Files.deleteIfExists(file); }
    }

    @Test
    void startup_recovery_audits_ambiguous_and_refund_required_transitions_with_real_sqlite() throws Exception {
        Path file = Files.createTempFile("blockeco-registration-recovery-audit-", ".db");
        Instant then = Instant.parse("2026-08-14T12:00:00Z");
        try (Database database = new Database("jdbc:sqlite:" + file)) {
            database.migrate();
            SqlRegistrationSagaRepository sagas = new SqlRegistrationSagaRepository(database.dataSource(), () -> then);
            RegistrationSaga prepared = new RegistrationSaga(UUID.randomUUID(), UUID.randomUUID(), "prepared", Money.ofMinor(3), RegistrationSagaState.PREPARED, null, then.minusSeconds(10), then.minusSeconds(10));
            RegistrationSaga withdrawn = new RegistrationSaga(UUID.randomUUID(), UUID.randomUUID(), "withdrawn", Money.ofMinor(4), RegistrationSagaState.WITHDRAWN, null, then.minusSeconds(10), then.minusSeconds(10));
            database.inTransaction(c -> { sagas.save(c, prepared); sagas.save(c, withdrawn); return null; });
            CompanyRegistrationService service = new CompanyRegistrationService(new SqlCompanyRepository(database.dataSource()), sagas, new SqlAuditLog(), database, new ProgrammableEconomy(), directMain(), Runnable::run, () -> then, Money.ofMinor(1), Money.ofMinor(2));
            assertThat(service.recoverStaleRegistrations(then).toCompletableFuture().join()).isEqualTo(2);
            try (Connection connection = database.dataSource().getConnection(); var statement = connection.prepareStatement("SELECT event_type, payload_json FROM audit_events ORDER BY sequence"); var rows = statement.executeQuery()) {
                java.util.List<String> types = new java.util.ArrayList<>(); while (rows.next()) { types.add(rows.getString(1)); assertThat(rows.getString(2)).contains("fromState", "toState", "reason"); }
                assertThat(types).containsExactly("COMPANY_REGISTRATION_AMBIGUOUS", "COMPANY_REGISTRATION_REFUND_REQUIRED");
            }
        } finally { Files.deleteIfExists(file); }
    }

    @Test
    void sql_completion_failure_and_refund_outcomes_are_audited_with_real_sqlite() throws Exception {
        Path file = Files.createTempFile("blockeco-registration-refund-audit-", ".db");
        Instant now = Instant.parse("2026-08-14T12:00:00Z");
        try (Database database = new Database("jdbc:sqlite:" + file)) {
            database.migrate();
            SqlRegistrationSagaRepository sagas = new SqlRegistrationSagaRepository(database.dataSource(), () -> now);
            CompanyRepository failingCompanies = new CompanyRepository() {
                @Override public void insert(Connection c, Company company) { throw new IllegalStateException("completion failure"); }
                @Override public Optional<Company> findById(CompanyId id) { return Optional.empty(); }
                @Override public Optional<Company> findByNormalizedName(String name) { return Optional.empty(); }
            };
            ProgrammableEconomy economy = new ProgrammableEconomy();
            CompanyRegistrationService service = new CompanyRegistrationService(failingCompanies, sagas, new SqlAuditLog(), database, economy, directMain(), Runnable::run, () -> now, Money.ofMinor(1), Money.ofMinor(2));
            assertThat(service.register(new RegistrationRequest(UUID.randomUUID(), "Refund Guild", 50)).toCompletableFuture().join().status()).isEqualTo(RegistrationResult.Status.REFUNDED_AFTER_FAILURE);
            try (Connection connection = database.dataSource().getConnection(); var statement = connection.prepareStatement("SELECT event_type FROM audit_events ORDER BY sequence"); var rows = statement.executeQuery()) {
                java.util.List<String> types = new java.util.ArrayList<>(); while (rows.next()) types.add(rows.getString(1));
                assertThat(types).containsExactly("COMPANY_REGISTRATION_PREPARED", "COMPANY_REGISTRATION_WITHDRAWN", "COMPANY_REGISTRATION_REFUND_REQUIRED", "COMPANY_REGISTRATION_REFUNDED");
            }
        } finally { Files.deleteIfExists(file); }
    }

    @Test
    void audit_failure_rolls_back_the_paired_prepared_saga_state() throws Exception {
        Path file = Files.createTempFile("blockeco-registration-audit-rollback-", ".db");
        Instant now = Instant.parse("2026-08-14T12:00:00Z");
        try (Database database = new Database("jdbc:sqlite:" + file)) {
            database.migrate();
            CompanyRegistrationService service = new CompanyRegistrationService(
                    new SqlCompanyRepository(database.dataSource()), new SqlRegistrationSagaRepository(database.dataSource(), () -> now),
                    (connection, event) -> { throw new java.sql.SQLException("audit unavailable"); }, database,
                    new ProgrammableEconomy(), directMain(), Runnable::run, () -> now, Money.ofMinor(1), Money.ofMinor(2));
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.register(new RegistrationRequest(UUID.randomUUID(), "Atomic Guild", 50)).toCompletableFuture().join())
                    .hasCauseInstanceOf(IllegalStateException.class);
            try (Connection connection = database.dataSource().getConnection(); var statement = connection.prepareStatement("SELECT COUNT(*) FROM registration_sagas"); var rows = statement.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isZero(); }
        } finally { Files.deleteIfExists(file); }
    }

    @Test
    void failed_refund_required_audit_does_not_attempt_deposit_or_fake_a_recovery_transition() throws Exception {
        Path file = Files.createTempFile("blockeco-refund-audit-failure-", ".db");
        Instant now = Instant.parse("2026-08-14T12:00:00Z");
        try (Database database = new Database("jdbc:sqlite:" + file)) {
            database.migrate();
            ProgrammableEconomy economy = new ProgrammableEconomy();
            CompanyRepository failingCompanies = new CompanyRepository() {
                @Override public void insert(Connection c, Company company) { throw new IllegalStateException("completion failure"); }
                @Override public Optional<Company> findById(CompanyId id) { return Optional.empty(); }
                @Override public Optional<Company> findByNormalizedName(String name) { return Optional.empty(); }
            };
            AuditLog audits = (connection, event) -> { if (event.eventType().equals("COMPANY_REGISTRATION_REFUND_REQUIRED")) throw new java.sql.SQLException("audit down"); new SqlAuditLog().append(connection, event); };
            CompanyRegistrationService service = new CompanyRegistrationService(failingCompanies, new SqlRegistrationSagaRepository(database.dataSource(), () -> now), audits, database, economy, directMain(), Runnable::run, () -> now, Money.ofMinor(1), Money.ofMinor(2));
            RegistrationResult result = service.register(new RegistrationRequest(UUID.randomUUID(), "Audit Fail", 50)).toCompletableFuture().join();
            assertThat(result.status()).isEqualTo(RegistrationResult.Status.RECOVERY_REQUIRED);
            assertThat(result.message()).contains("refund not attempted");
            assertThat(economy.deposited()).isEqualTo(Money.zero());
            try (Connection c = database.dataSource().getConnection(); var st = c.prepareStatement("SELECT state FROM registration_sagas"); var rows = st.executeQuery()) { assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("WITHDRAWN"); }
            try (Connection c = database.dataSource().getConnection(); var st = c.prepareStatement("SELECT event_type FROM audit_events WHERE event_type LIKE 'COMPANY_REGISTRATION_REFUND%'"); var rows = st.executeQuery()) { assertThat(rows.next()).isFalse(); }
        } finally { Files.deleteIfExists(file); }
    }

    @Test
    void expected_state_conflict_rolls_back_without_writing_an_audit_event() throws Exception {
        Path file = Files.createTempFile("blockeco-state-conflict-", ".db");
        Instant now = Instant.parse("2026-08-14T12:00:00Z");
        try (Database database = new Database("jdbc:sqlite:" + file)) {
            database.migrate();
            SqlRegistrationSagaRepository sagas = new SqlRegistrationSagaRepository(database.dataSource(), () -> now);
            RegistrationSaga saga = new RegistrationSaga(UUID.randomUUID(), UUID.randomUUID(), "conflict", Money.ofMinor(3), RegistrationSagaState.WITHDRAWN, null, now, now);
            database.inTransaction(c -> { sagas.save(c, saga); return null; });
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> database.inTransaction(c -> { sagas.transition(c, saga.id(), RegistrationSagaState.PREPARED, RegistrationSagaState.REFUND_REQUIRED, "wrong predecessor"); new SqlAuditLog().append(c, new AuditEvent(UUID.randomUUID(), Optional.empty(), Optional.of(saga.founderId()), "SHOULD_NOT_EXIST", Map.of("x", 1), now)); return null; })).isInstanceOf(IllegalStateException.class);
            try (Connection c = database.dataSource().getConnection(); var state = c.prepareStatement("SELECT state FROM registration_sagas"); var rows = state.executeQuery()) { rows.next(); assertThat(rows.getString(1)).isEqualTo("WITHDRAWN"); }
            try (Connection c = database.dataSource().getConnection(); var audit = c.prepareStatement("SELECT COUNT(*) FROM audit_events"); var rows = audit.executeQuery()) { rows.next(); assertThat(rows.getInt(1)).isZero(); }
        } finally { Files.deleteIfExists(file); }
    }

    private static MainThreadExecutor directMain() { return new MainThreadExecutor() { @Override public <T> CompletionStage<T> submit(Supplier<T> work) { return CompletableFuture.completedFuture(work.get()); } }; }

    @Test
    void company_query_uses_the_same_unicode_name_normalization_as_registration() {
        Company stored = Company.register(new CompanyId(UUID.randomUUID()), "Red Stone", UUID.randomUUID(), Money.zero(), cn.blockeco.exchange.domain.company.DividendRate.FIFTY, Instant.parse("2026-08-14T12:00:00Z"));
        CompanyRepository companies = new CompanyRepository() {
            @Override public void insert(Connection c, Company company) { }
            @Override public Optional<Company> findById(CompanyId id) { return Optional.empty(); }
            @Override public Optional<Company> findByNormalizedName(String name) { return stored.normalizedName().equals(name) ? Optional.of(stored) : Optional.empty(); }
        };
        CompanyQueryService queries = new CompanyQueryService(companies, new RegistrationSagaRepository() {
            @Override public void save(Connection c, RegistrationSaga saga) { }
            @Override public java.util.List<RegistrationSaga> findPreparedBefore(Instant cutoff) { return java.util.List.of(); }
            @Override public java.util.List<RegistrationSaga> findWithdrawnBefore(Instant cutoff) { return java.util.List.of(); }
            @Override public void transition(Connection c, UUID id, RegistrationSagaState expected, RegistrationSagaState state, String error) { }
        }, Runnable::run);

        assertThat(queries.findByName("\u2003Red   Stone\u00a0").toCompletableFuture().join()).contains(stored);
    }

    @Test
    void configured_fee_and_capital_determine_withdrawal_and_treasury() {
        RegistrationFixture fixture = RegistrationFixture.withAmounts(Money.ofMinor(321), Money.ofMinor(4_567));

        RegistrationResult result = fixture.register("Blue Stone", 50);

        assertThat(result.status()).isEqualTo(RegistrationResult.Status.SUCCESS);
        assertThat(fixture.economy().withdrawn()).isEqualTo(Money.ofMinor(4_888));
        assertThat(fixture.savedCompany().treasury()).isEqualTo(Money.ofMinor(4_567));
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
                    new SqlCompanyRepository(database.dataSource()), new SqlRegistrationSagaRepository(database.dataSource(), () -> Instant.parse("2026-08-14T12:00:00Z")),
                    new SqlAuditLog(), database, economy, main, Runnable::run, () -> Instant.parse("2026-08-14T12:00:00Z"), Money.ofMinor(100_000), Money.ofMinor(1_000_000));
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

        private RegistrationFixture() { this(Money.ofMinor(100_000), Money.ofMinor(1_000_000)); }
        private RegistrationFixture(Money fee, Money capital) {
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
                @Override public void transition(Connection ignored, UUID id, RegistrationSagaState expected, RegistrationSagaState state, String error) {
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
                    Runnable::run, () -> now, fee, capital);
        }

        static RegistrationFixture standard() { return new RegistrationFixture(); }
        static RegistrationFixture withAmounts(Money fee, Money capital) { return new RegistrationFixture(fee, capital); }
        RegistrationResult register(String name, int dividendRate) {
            return service.register(new RegistrationRequest(UUID.fromString("9fbb8514-5773-41c4-aa72-6e6a1e47f5c2"), name, dividendRate)).toCompletableFuture().join();
        }
        Company savedCompany() { return company.values().iterator().next(); }
        RegistrationSaga savedSaga() { return sagas.values().iterator().next(); }
        Company company(String name) { return Company.register(new CompanyId(UUID.randomUUID()), name, UUID.randomUUID(), Money.zero(), cn.blockeco.exchange.domain.company.DividendRate.FIFTY, now); }
        ProgrammableEconomy economy() { return economy; }
    }

    private static final class RecordingRegistrationEscrow implements TreasuryEscrowGateway {
        private int deposits;
        private Money deposited = Money.zero();
        private EconomyGateway.Result depositResult = EconomyGateway.Result.success("");
        @Override public EconomyGateway.Result withdrawPlayer(UUID playerId, Money amount, UUID operationId) { throw new AssertionError("registration must not withdraw capital a second time"); }
        @Override public EconomyGateway.Result depositEscrow(Money amount, UUID operationId) { deposits++; deposited = amount; return depositResult; }
        @Override public EconomyGateway.Result withdrawEscrow(Money amount, UUID operationId) { throw new AssertionError("automatic compensation is unsafe"); }
        @Override public EconomyGateway.Result refundPlayer(UUID playerId, Money amount, UUID operationId) { throw new AssertionError("automatic compensation is unsafe"); }
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
        private final CountDownLatch entered; private final CountDownLatch release; private int withdrawCalls; private Money withdrawn = Money.zero();
        private BlockingEconomy(CountDownLatch entered, CountDownLatch release) { this.entered = entered; this.release = release; }
        @Override public Result withdraw(UUID player, Money amount) {
            withdrawCalls++; withdrawn = amount; entered.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout"); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
            return Result.success("");
        }
        @Override public Result deposit(UUID player, Money amount) { return Result.success(""); }
    }
}
