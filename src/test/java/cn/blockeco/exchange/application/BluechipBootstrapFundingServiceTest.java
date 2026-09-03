package cn.blockeco.exchange.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.infrastructure.sql.Database;
import cn.blockeco.exchange.infrastructure.sql.SqlBluechipBootstrapFundingRepository;
import java.nio.file.Files;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;

class BluechipBootstrapFundingServiceTest {
    @Test
    void preparedFundingMovesExistingReserveIntoEscrowBeforeLiquidityBecomesAvailable() throws Exception {
        var file = Files.createTempFile("blockstock-bluechip-direct-escrow-", ".db");
        try (var database = new Database("jdbc:sqlite:" + file)) {
            database.migrate();
            var system = UUID.fromString("00000000-0000-0000-0000-000000000099");
            var systemDeposits = new int[1]; var systemWithdrawals = new int[1]; var escrowDeposits = new int[1];
            var records = new SqlBluechipBootstrapFundingRepository(database.dataSource());
            var service = new BluechipBootstrapFundingService(system, records, database, new BluechipBootstrapFundingService.EscrowEconomy() {
                @Override public cn.blockeco.exchange.ports.EconomyGateway.Result withdraw(UUID player, Money amount) { systemWithdrawals[0]++; return cn.blockeco.exchange.ports.EconomyGateway.Result.success("unexpected"); }
                @Override public cn.blockeco.exchange.ports.EconomyGateway.Result deposit(UUID player, Money amount) { systemDeposits[0]++; return cn.blockeco.exchange.ports.EconomyGateway.Result.success("unexpected"); }
                @Override public cn.blockeco.exchange.ports.EconomyGateway.Result depositEscrow(Money amount) { escrowDeposits[0]++; return cn.blockeco.exchange.ports.EconomyGateway.Result.success("escrow deposited"); }
            }, immediateMain(), () -> Instant.parse("2026-08-24T00:00:00Z"));

            var funded = service.ensureEscrowFunded(Money.ofMinor(100));

            assertThat(escrowDeposits[0]).isEqualTo(1);
            assertThat(systemDeposits[0]).isZero();
            assertThat(systemWithdrawals[0]).isEqualTo(1);
            assertThat(funded.state()).isEqualTo(cn.blockeco.exchange.ports.BluechipBootstrapFundingRepository.State.ESCROW_DEPOSITED);
        } finally { Files.deleteIfExists(file); }
    }

    @Test
    void configuredReserveTreasuryIsDebitedInsteadOfTheBluechipMakerAccount() throws Exception {
        var file = Files.createTempFile("blockstock-bluechip-configured-reserve-", ".db");
        try (var database = new Database("jdbc:sqlite:" + file)) {
            database.migrate();
            var maker = UUID.fromString("00000000-0000-0000-0000-000000000099");
            var reserve = UUID.fromString("00000000-0000-0000-0000-000000000098");
            var debited = new java.util.concurrent.atomic.AtomicReference<UUID>(); var escrowDeposits = new int[1];
            var service = new BluechipBootstrapFundingService(maker, reserve, new SqlBluechipBootstrapFundingRepository(database.dataSource()), database,
                    new BluechipBootstrapFundingService.EscrowEconomy() {
                        @Override public cn.blockeco.exchange.ports.EconomyGateway.Result withdraw(UUID player, Money amount) { debited.set(player); return cn.blockeco.exchange.ports.EconomyGateway.Result.success("treasury debited"); }
                        @Override public cn.blockeco.exchange.ports.EconomyGateway.Result deposit(UUID player, Money amount) { return cn.blockeco.exchange.ports.EconomyGateway.Result.success("unexpected"); }
                        @Override public cn.blockeco.exchange.ports.EconomyGateway.Result depositEscrow(Money amount) { escrowDeposits[0]++; return cn.blockeco.exchange.ports.EconomyGateway.Result.success("escrow credited"); }
                    }, immediateMain(), () -> Instant.EPOCH);

            service.ensureEscrowFunded(Money.ofMinor(100));

            assertThat(debited.get()).isEqualTo(reserve);
            assertThat(debited.get()).isNotEqualTo(maker);
            assertThat(escrowDeposits[0]).isEqualTo(1);
        } finally { Files.deleteIfExists(file); }
    }

    @Test
    void insufficientReserveDoesNotOpenAFundingOperationOrCreditEscrow() throws Exception {
        var file = Files.createTempFile("blockstock-bluechip-reserve-insufficient-", ".db");
        try (var database = new Database("jdbc:sqlite:" + file)) {
            database.migrate(); var reserve = UUID.fromString("00000000-0000-0000-0000-000000000099");
            var records = new SqlBluechipBootstrapFundingRepository(database.dataSource()); var escrowDeposits = new int[1];
            var service = new BluechipBootstrapFundingService(reserve, records, database, new BluechipBootstrapFundingService.EscrowEconomy() {
                @Override public cn.blockeco.exchange.ports.EconomyGateway.Result withdraw(UUID player, Money amount) { return cn.blockeco.exchange.ports.EconomyGateway.Result.insufficientFunds("treasury balance is too low"); }
                @Override public cn.blockeco.exchange.ports.EconomyGateway.Result deposit(UUID player, Money amount) { return cn.blockeco.exchange.ports.EconomyGateway.Result.success("unexpected"); }
                @Override public cn.blockeco.exchange.ports.EconomyGateway.Result depositEscrow(Money amount) { escrowDeposits[0]++; return cn.blockeco.exchange.ports.EconomyGateway.Result.success("unexpected"); }
            }, immediateMain(), () -> Instant.EPOCH);

            assertThatThrownBy(() -> service.ensureEscrowFunded(Money.ofMinor(100)))
                    .hasMessageContaining("reserve treasury has insufficient funds");
            assertThat(escrowDeposits[0]).isZero();
            assertThat(records.find(UUID.nameUUIDFromBytes(("blockstock-bluechip-bootstrap-funding:" + reserve + ":100").getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .orElseThrow().state()).isEqualTo(cn.blockeco.exchange.ports.BluechipBootstrapFundingRepository.State.PREPARED);
        } finally { Files.deleteIfExists(file); }
    }

    @Test
    void fundingChainDoesNotSynchronouslyWaitForMainThreadWork() throws Exception {
        var file = Files.createTempFile("blockstock-bluechip-async-", ".db");
        try (var database = new Database("jdbc:sqlite:" + file)) {
            database.migrate(); var mainWork = new CompletableFuture<cn.blockeco.exchange.ports.EconomyGateway.Result>();
            var service = new BluechipBootstrapFundingService(UUID.randomUUID(), new SqlBluechipBootstrapFundingRepository(database.dataSource()), database,
                    new BluechipBootstrapFundingService.EscrowEconomy() {
                        @Override public cn.blockeco.exchange.ports.EconomyGateway.Result withdraw(UUID player, Money amount) { return cn.blockeco.exchange.ports.EconomyGateway.Result.success("ok"); }
                        @Override public cn.blockeco.exchange.ports.EconomyGateway.Result deposit(UUID player, Money amount) { return cn.blockeco.exchange.ports.EconomyGateway.Result.success("ok"); }
                        @Override public cn.blockeco.exchange.ports.EconomyGateway.Result depositEscrow(Money amount) { return cn.blockeco.exchange.ports.EconomyGateway.Result.success("ok"); }
                    }, new cn.blockeco.exchange.ports.MainThreadExecutor() { @Override public <T> java.util.concurrent.CompletionStage<T> submit(java.util.function.Supplier<T> work) { return (java.util.concurrent.CompletionStage<T>) mainWork; } }, () -> Instant.EPOCH);
            var background = java.util.concurrent.Executors.newSingleThreadExecutor();
            var pending = service.ensureEscrowFundedAsync(Money.ofMinor(100), background);
            assertThat(pending).isNotCompleted();
            mainWork.complete(cn.blockeco.exchange.ports.EconomyGateway.Result.success("ok"));
            assertThat(pending.toCompletableFuture().get()).isNotNull();
            background.shutdownNow();
        } finally { Files.deleteIfExists(file); }
    }
    @Test
    void uncertainEscrowDepositIsPersistedAndNeverIssuedAgainOnRestart() throws Exception {
        var file = Files.createTempFile("blockstock-bluechip-funding-", ".db");
        try (var database = new Database("jdbc:sqlite:" + file)) {
            database.migrate(); var calls = new int[1]; var system = UUID.fromString("00000000-0000-0000-0000-000000000099");
            var records = new SqlBluechipBootstrapFundingRepository(database.dataSource());
            var economy = new BluechipBootstrapFundingService.EscrowEconomy() {
                @Override public cn.blockeco.exchange.ports.EconomyGateway.Result withdraw(UUID player, Money amount) { return cn.blockeco.exchange.ports.EconomyGateway.Result.success("withdraw"); }
                @Override public cn.blockeco.exchange.ports.EconomyGateway.Result deposit(UUID player, Money amount) { return cn.blockeco.exchange.ports.EconomyGateway.Result.success("unexpected"); }
                @Override public cn.blockeco.exchange.ports.EconomyGateway.Result depositEscrow(Money amount) { calls[0]++; return cn.blockeco.exchange.ports.EconomyGateway.Result.providerFailure("provider timed out after accepting request"); }
            };
            var main = immediateMain();
            var service = new BluechipBootstrapFundingService(system, records, database, economy, main, () -> Instant.parse("2026-08-24T00:00:00Z"));

            assertThatThrownBy(() -> service.ensureEscrowFunded(Money.ofMinor(100))).hasMessageContaining("manual recovery");
            assertThatThrownBy(() -> service.ensureEscrowFunded(Money.ofMinor(100))).hasMessageContaining("manual recovery");

            assertThat(calls[0]).isEqualTo(1);
            assertThat(records.find(UUID.nameUUIDFromBytes(("blockstock-bluechip-bootstrap-funding:" + system + ":100").getBytes(java.nio.charset.StandardCharsets.UTF_8))).orElseThrow().state())
                    .isEqualTo(cn.blockeco.exchange.ports.BluechipBootstrapFundingRepository.State.AMBIGUOUS);
        } finally { Files.deleteIfExists(file); }
    }

    @Test
    void durableRequestedStateIsNeverRetriedAfterRestart() throws Exception {
        var file = Files.createTempFile("blockstock-bluechip-requested-", ".db");
        try (var database = new Database("jdbc:sqlite:" + file)) {
            database.migrate(); var calls = new int[1]; var system = UUID.fromString("00000000-0000-0000-0000-000000000099"); var amount = Money.ofMinor(100);
            UUID id = UUID.nameUUIDFromBytes(("blockstock-bluechip-bootstrap-funding:" + system + ":100").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var records = new SqlBluechipBootstrapFundingRepository(database.dataSource()); var now = Instant.parse("2026-08-24T00:00:00Z");
            database.inTransaction(c -> { records.prepare(c, new cn.blockeco.exchange.ports.BluechipBootstrapFundingRepository.Funding(id, system, amount, cn.blockeco.exchange.ports.BluechipBootstrapFundingRepository.State.PREPARED, "prepared", now, now)); records.transition(c, id, cn.blockeco.exchange.ports.BluechipBootstrapFundingRepository.State.PREPARED, cn.blockeco.exchange.ports.BluechipBootstrapFundingRepository.State.SOURCE_CREDIT_REQUESTED, "source credit requested", now); return null; });
            var economy = new BluechipBootstrapFundingService.EscrowEconomy() {
                @Override public cn.blockeco.exchange.ports.EconomyGateway.Result withdraw(UUID player, Money ignored) { calls[0]++; return cn.blockeco.exchange.ports.EconomyGateway.Result.success("unexpected"); }
                @Override public cn.blockeco.exchange.ports.EconomyGateway.Result deposit(UUID player, Money ignored) { calls[0]++; return cn.blockeco.exchange.ports.EconomyGateway.Result.success("unexpected"); }
                @Override public cn.blockeco.exchange.ports.EconomyGateway.Result depositEscrow(Money ignored) { calls[0]++; return cn.blockeco.exchange.ports.EconomyGateway.Result.success("unexpected"); }
            };
            var service = new BluechipBootstrapFundingService(system, records, database, economy, immediateMain(), () -> now);

            assertThatThrownBy(() -> service.ensureEscrowFunded(amount)).hasMessageContaining("manual recovery");
            assertThat(calls[0]).isZero();
        } finally { Files.deleteIfExists(file); }
    }

    @Test
    void legacySourceCreditedFundingIsNeverContinuedAutomatically() throws Exception {
        var file = Files.createTempFile("blockstock-bluechip-legacy-source-", ".db");
        try (var database = new Database("jdbc:sqlite:" + file)) {
            database.migrate(); var calls = new int[1]; var system = UUID.fromString("00000000-0000-0000-0000-000000000099"); var amount = Money.ofMinor(100);
            UUID id = UUID.nameUUIDFromBytes(("blockstock-bluechip-bootstrap-funding:" + system + ":100").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var records = new SqlBluechipBootstrapFundingRepository(database.dataSource()); var now = Instant.parse("2026-08-24T00:00:00Z");
            database.inTransaction(c -> { records.prepare(c, new cn.blockeco.exchange.ports.BluechipBootstrapFundingRepository.Funding(id, system, amount, cn.blockeco.exchange.ports.BluechipBootstrapFundingRepository.State.PREPARED, "prepared", now, now)); records.transition(c, id, cn.blockeco.exchange.ports.BluechipBootstrapFundingRepository.State.PREPARED, cn.blockeco.exchange.ports.BluechipBootstrapFundingRepository.State.SOURCE_CREDIT_REQUESTED, "legacy source credit requested", now); records.transition(c, id, cn.blockeco.exchange.ports.BluechipBootstrapFundingRepository.State.SOURCE_CREDIT_REQUESTED, cn.blockeco.exchange.ports.BluechipBootstrapFundingRepository.State.SOURCE_CREDITED, "legacy source credited", now); return null; });
            var service = new BluechipBootstrapFundingService(system, records, database, new BluechipBootstrapFundingService.EscrowEconomy() {
                @Override public cn.blockeco.exchange.ports.EconomyGateway.Result withdraw(UUID player, Money ignored) { calls[0]++; return cn.blockeco.exchange.ports.EconomyGateway.Result.success("unexpected"); }
                @Override public cn.blockeco.exchange.ports.EconomyGateway.Result deposit(UUID player, Money ignored) { calls[0]++; return cn.blockeco.exchange.ports.EconomyGateway.Result.success("unexpected"); }
                @Override public cn.blockeco.exchange.ports.EconomyGateway.Result depositEscrow(Money ignored) { calls[0]++; return cn.blockeco.exchange.ports.EconomyGateway.Result.success("unexpected"); }
            }, immediateMain(), () -> now);

            assertThatThrownBy(() -> service.ensureEscrowFunded(amount)).hasMessageContaining("manual recovery");
            assertThat(calls[0]).isZero();
        } finally { Files.deleteIfExists(file); }
    }

    private static cn.blockeco.exchange.ports.MainThreadExecutor immediateMain() {
        return new cn.blockeco.exchange.ports.MainThreadExecutor() { @Override public <T> java.util.concurrent.CompletionStage<T> submit(java.util.function.Supplier<T> work) { return java.util.concurrent.CompletableFuture.completedFuture(work.get()); } };
    }
}
