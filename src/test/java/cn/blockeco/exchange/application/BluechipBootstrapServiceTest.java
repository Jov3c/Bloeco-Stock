package cn.blockeco.exchange.application;

import static org.assertj.core.api.Assertions.assertThat;

import cn.blockeco.exchange.domain.company.Company;
import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.company.CompanyStatus;
import cn.blockeco.exchange.domain.company.DividendRate;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.infrastructure.sql.Database;
import cn.blockeco.exchange.infrastructure.sql.SqlBluechipRepository;
import cn.blockeco.exchange.infrastructure.sql.SqlBluechipBootstrapFundingRepository;
import cn.blockeco.exchange.infrastructure.sql.SqlBluechipParticipantRepository;
import cn.blockeco.exchange.infrastructure.sql.SqlCompanyFinanceRepository;
import cn.blockeco.exchange.infrastructure.sql.SqlCompanyRepository;
import cn.blockeco.exchange.infrastructure.sql.SqlSecuritiesCashRepository;
import cn.blockeco.exchange.infrastructure.sql.SqlStockListingRepository;
import cn.blockeco.exchange.paper.BluechipConfig;
import java.nio.file.Files;
import java.time.Instant;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class BluechipBootstrapServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-24T00:00:00Z");
    private static final UUID SYSTEM_ACCOUNT = UUID.fromString("00000000-0000-0000-0000-000000000099");
    private static final UUID PARTICIPANT_ACCOUNT = UUID.fromString("00000000-0000-0000-0000-000000000077");

    @Test
    void initializesExactlyTenListingsWithFiniteFundCashAndInventory() throws Exception {
        var file = Files.createTempFile("blockstock-bluechip-bootstrap-", ".db");
        try (Database database = migratedDatabase(file)) {
            BluechipConfig config = config();
            SqlBluechipRepository repository = new SqlBluechipRepository(database.dataSource());
            BluechipBootstrapService service = service(database, repository, config);

            BluechipBootstrapResult result = service.initializeMissing().toCompletableFuture().join();

            assertThat(result.createdCompanies()).isEqualTo(10);
            assertThat(repository.all()).hasSize(10);
            var first = repository.all().getFirst();
            assertThat(first.fundShares()).isEqualTo(config.definitions().getFirst().initialFundShares());
            assertThat(first.fundCash()).isEqualTo(Money.ofMinor(config.definitions().getFirst().initialFundCash()));
            assertThat(first.systemAccountId()).isEqualTo(SYSTEM_ACCOUNT);
            assertThat(first.listing().stockCode()).isEqualTo(config.definitions().getFirst().code());
            assertThat(repository.all()).allSatisfy(bluechip -> {
                var definition = config.definitions().stream()
                        .filter(candidate -> candidate.code().equals(bluechip.listing().stockCode()))
                        .findFirst().orElseThrow();
                assertThat(bluechip.listing().issuedShares()).isEqualTo(definition.initialFundShares());
                assertThat(sumHeldShares(database, bluechip.companyId())).isEqualTo(bluechip.listing().issuedShares());
                assertThat(companyTotalShares(database, bluechip.companyId())).isEqualTo(definition.totalShares());
            });
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void bluechipsUseTheirConfiguredTickersWithoutConsumingThePlayerListingSequence() throws Exception {
        var file = Files.createTempFile("blockstock-bluechip-tickers-", ".db");
        try (Database database = migratedDatabase(file)) {
            BluechipConfig config = config();
            SqlBluechipRepository bluechips = new SqlBluechipRepository(database.dataSource());
            service(database, bluechips, config).initializeMissing().toCompletableFuture().join();

            assertThat(bluechips.all()).extracting(company -> company.listing().stockCode())
                    .containsExactlyInAnyOrderElementsOf(config.definitions().stream().map(definition -> definition.code()).toList());
            assertThat(stockSequence(database)).isZero();

            var playerCompany = new CompanyId(UUID.randomUUID());
            database.inTransaction(connection -> {
                new SqlCompanyRepository(database.dataSource()).insert(connection, Company.rehydrate(playerCompany, "普通玩家公司",
                        Company.normalizeName("普通玩家公司"), UUID.randomUUID(), Money.zero(), 1_000, DividendRate.FIFTY,
                        CompanyStatus.LISTED, NOW));
                assertThat(new SqlStockListingRepository(database.dataSource())
                        .allocate(connection, playerCompany, Money.ofMinor(100), 1_000, NOW).stockCode()).isEqualTo("BS000001");
                return null;
            });
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void reinitializationConvertsAnUnusedLegacyBluechipCodeAndItsInitializationAudit() throws Exception {
        var file = Files.createTempFile("blockstock-bluechip-legacy-ticker-", ".db");
        try (Database database = migratedDatabase(file)) {
            BluechipConfig config = config();
            SqlBluechipRepository bluechips = new SqlBluechipRepository(database.dataSource());
            BluechipBootstrapService service = service(database, bluechips, config);
            service.initializeMissing().toCompletableFuture().join();
            var first = bluechips.all().getFirst();
            database.inTransaction(connection -> {
                try (PreparedStatement listing = connection.prepareStatement("UPDATE stock_listings SET stock_code='BS000001', issued_shares=? WHERE company_id=?");
                     PreparedStatement audit = connection.prepareStatement("UPDATE audit_events SET payload_json=REPLACE(payload_json, ?, 'BS000001') WHERE company_id=?")) {
                    listing.setLong(1, config.definitions().getFirst().totalShares()); listing.setString(2, first.companyId().value().toString()); listing.executeUpdate();
                    audit.setString(1, config.definitions().getFirst().code()); audit.setString(2, first.companyId().value().toString()); audit.executeUpdate();
                }
                return null;
            });

            assertThat(service.initializeMissing().toCompletableFuture().join().createdCompanies()).isZero();
            assertThat(bluechips.findByCompanyId(first.companyId()).orElseThrow().listing().stockCode()).isEqualTo(config.definitions().getFirst().code());
            assertThat(bluechips.findByCompanyId(first.companyId()).orElseThrow().listing().issuedShares())
                    .isEqualTo(config.definitions().getFirst().initialFundShares());
            assertThat(initializationAudit(database, first.companyId())).contains(config.definitions().getFirst().code()).doesNotContain("BS000001");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void reinitializationFailsClosedWhenLegacyBluechipHasAnOrderHistory() throws Exception {
        var file = Files.createTempFile("blockstock-bluechip-legacy-order-", ".db");
        try (Database database = migratedDatabase(file)) {
            BluechipConfig config = config();
            SqlBluechipRepository bluechips = new SqlBluechipRepository(database.dataSource());
            BluechipBootstrapService service = service(database, bluechips, config);
            service.initializeMissing().toCompletableFuture().join();
            var first = bluechips.all().getFirst();
            database.inTransaction(connection -> {
                try (PreparedStatement listing = connection.prepareStatement("UPDATE stock_listings SET stock_code='BS000001', issued_shares=? WHERE company_id=?");
                     PreparedStatement order = connection.prepareStatement("INSERT INTO stock_orders (id,company_id,stock_code,player_uuid,side,limit_price_minor,original_shares,remaining_shares,priority_sequence,reserved_cash_minor,filled_notional_minor,fee_charged_minor,fee_bps,accepted_at,state) VALUES (?,?,'BS000001',?,'BUY',100,1,1,1,100,0,0,0,?,'OPEN')")) {
                    listing.setLong(1, config.definitions().getFirst().totalShares()); listing.setString(2, first.companyId().value().toString()); listing.executeUpdate();
                    order.setString(1, UUID.randomUUID().toString()); order.setString(2, first.companyId().value().toString()); order.setString(3, UUID.randomUUID().toString()); order.setString(4, NOW.toString()); order.executeUpdate();
                }
                return null;
            });

            assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> service.initializeMissing().toCompletableFuture().join()))
                    .hasMessageContaining("manual handling because orders or trades exist");
            assertThat(bluechips.findByCompanyId(first.companyId()).orElseThrow().listing().stockCode()).isEqualTo("BS000001");
            assertThat(bluechips.findByCompanyId(first.companyId()).orElseThrow().listing().issuedShares())
                    .isEqualTo(config.definitions().getFirst().totalShares());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void reinitializationCorrectsPreReleaseAuthorizedShareListingWhenNoOrdersExist() throws Exception {
        var file = Files.createTempFile("blockstock-bluechip-legacy-issued-", ".db");
        try (Database database = migratedDatabase(file)) {
            BluechipConfig config = config();
            SqlBluechipRepository bluechips = new SqlBluechipRepository(database.dataSource());
            BluechipBootstrapService service = service(database, bluechips, config);
            service.initializeMissing().toCompletableFuture().join();
            var first = bluechips.all().getFirst();
            database.inTransaction(connection -> {
                try (PreparedStatement listing = connection.prepareStatement("UPDATE stock_listings SET issued_shares=? WHERE company_id=?")) {
                    listing.setLong(1, config.definitions().getFirst().totalShares()); listing.setString(2, first.companyId().value().toString()); listing.executeUpdate();
                }
                return null;
            });

            assertThat(service.initializeMissing().toCompletableFuture().join().createdCompanies()).isZero();
            assertThat(bluechips.findByCompanyId(first.companyId()).orElseThrow().listing().issuedShares())
                    .isEqualTo(config.definitions().getFirst().initialFundShares());
        } finally { Files.deleteIfExists(file); }
    }

    @Test
    void reinitializationFailsClosedForPreReleaseIssuedShareMismatchAfterOrdersExist() throws Exception {
        var file = Files.createTempFile("blockstock-bluechip-legacy-issued-order-", ".db");
        try (Database database = migratedDatabase(file)) {
            BluechipConfig config = config();
            SqlBluechipRepository bluechips = new SqlBluechipRepository(database.dataSource());
            BluechipBootstrapService service = service(database, bluechips, config);
            service.initializeMissing().toCompletableFuture().join();
            var first = bluechips.all().getFirst();
            database.inTransaction(connection -> {
                try (PreparedStatement listing = connection.prepareStatement("UPDATE stock_listings SET issued_shares=? WHERE company_id=?");
                     PreparedStatement order = connection.prepareStatement("INSERT INTO stock_orders (id,company_id,stock_code,player_uuid,side,limit_price_minor,original_shares,remaining_shares,priority_sequence,reserved_cash_minor,filled_notional_minor,fee_charged_minor,fee_bps,accepted_at,state) VALUES (?,?,?,?, 'BUY',100,1,1,1,100,0,0,0,?,'OPEN')")) {
                    listing.setLong(1, config.definitions().getFirst().totalShares()); listing.setString(2, first.companyId().value().toString()); listing.executeUpdate();
                    order.setString(1, UUID.randomUUID().toString()); order.setString(2, first.companyId().value().toString()); order.setString(3, first.listing().stockCode()); order.setString(4, UUID.randomUUID().toString()); order.setString(5, NOW.toString()); order.executeUpdate();
                }
                return null;
            });

            assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> service.initializeMissing().toCompletableFuture().join()))
                    .hasMessageContaining("issued-share migration requires manual handling because orders or trades exist");
            assertThat(bluechips.findByCompanyId(first.companyId()).orElseThrow().listing().issuedShares())
                    .isEqualTo(config.definitions().getFirst().totalShares());
        } finally { Files.deleteIfExists(file); }
    }

    @Test
    void initialSystemLiquidityIsBackedByTheEscrowLedger() throws Exception {
        var file = Files.createTempFile("blockstock-bluechip-escrow-ledger-", ".db");
        try (Database database = migratedDatabase(file)) {
            BluechipBootstrapService service = service(database, new SqlBluechipRepository(database.dataSource()), config());

            service.initializeMissing().toCompletableFuture().join();

            long seededCash = systemCash(database);
            assertThat(new SqlSecuritiesCashRepository(database.dataSource())
                    .reconcile(Money.ofMinor(seededCash)).confirmedDifference()).isEqualTo(Money.zero());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void recoveryDoesNotTreatInitializedBluechipsAsLegacyCompaniesWithoutFinance() throws Exception {
        var file = Files.createTempFile("blockstock-bluechip-recovery-", ".db");
        try (Database database = migratedDatabase(file)) {
            BluechipBootstrapService bootstrap = service(database, new SqlBluechipRepository(database.dataSource()), config());
            bootstrap.initializeMissing().toCompletableFuture().join();
            RecordingEscrow escrow = new RecordingEscrow();
            CompanyCapitalizationService recovery = new CompanyCapitalizationService(
                    new SqlCompanyFinanceRepository(database.dataSource()), new cn.blockeco.exchange.infrastructure.sql.SqlAuditLog(),
                    database, escrow, new cn.blockeco.exchange.ports.MainThreadExecutor() {
                        @Override public <T> java.util.concurrent.CompletionStage<T> submit(java.util.function.Supplier<T> work) {
                            return java.util.concurrent.CompletableFuture.completedFuture(work.get());
                        }
                    }, Runnable::run, () -> NOW);

            assertThat(new SqlCompanyFinanceRepository(database.dataSource()).findLegacyCompaniesWithoutFinance()).isEmpty();
            assertThat(recovery.recoverPendingCapitalizations().toCompletableFuture().join()).isZero();
            assertThat(escrow.escrowDeposits).isZero();
            assertThat(count(database, "SELECT COUNT(*) FROM treasury_operations")).isZero();
            assertThat(count(database, "SELECT COUNT(*) FROM treasury_operations WHERE state = 'AMBIGUOUS'")).isZero();
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void secondInitializationCreatesNothingAndDoesNotTouchPlayerCompany() throws Exception {
        var file = Files.createTempFile("blockstock-bluechip-idempotent-", ".db");
        try (Database database = migratedDatabase(file)) {
            var companies = new SqlCompanyRepository(database.dataSource());
            Company playerCompany = Company.rehydrate(new CompanyId(UUID.randomUUID()), "玩家矿业", Company.normalizeName("玩家矿业"),
                    UUID.randomUUID(), Money.zero(), 1_000, DividendRate.FIFTY, CompanyStatus.PENDING_ASSET_BINDING, NOW);
            database.inTransaction(connection -> { companies.insert(connection, playerCompany); return null; });
            SqlBluechipRepository repository = new SqlBluechipRepository(database.dataSource());
            BluechipBootstrapService service = service(database, repository, config());

            service.initializeMissing().toCompletableFuture().join();

            assertThat(service.initializeMissing().toCompletableFuture().join().createdCompanies()).isZero();
            assertThat(companies.findById(playerCompany.id()).orElseThrow().displayName()).isEqualTo("玩家矿业");
            assertThat(repository.all()).allSatisfy(company -> {
                assertThat(company.fundCash().minorUnits()).isPositive();
                assertThat(company.fundShares()).isPositive();
            });
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void participantBootstrapTransferIsIdempotentAndConservesCashAndShares() throws Exception {
        var file = Files.createTempFile("blockstock-bluechip-participant-bootstrap-", ".db");
        try (Database database = migratedDatabase(file)) {
            SqlBluechipRepository repository = new SqlBluechipRepository(database.dataSource());
            BluechipConfig config = config();
            BluechipBootstrapService service = participantService(database, repository, config);

            service.initializeMissing().toCompletableFuture().join();
            long cashAfterFirst = totalSecuritiesCash(database);
            long participantCashAfterFirst = securitiesCash(database, PARTICIPANT_ACCOUNT);
            var sharesAfterFirst = repository.all().stream().mapToLong(company -> heldShares(database, company.companyId(), PARTICIPANT_ACCOUNT)).sum();

            assertThat(cashAfterFirst).isEqualTo(config.definitions().stream().mapToLong(definition -> definition.initialFundCash()).sum());
            assertThat(count(database, "SELECT COUNT(*) FROM escrow_ledger_entries WHERE liability_kind = 'SECURITIES_CASH'"))
                    .isEqualTo(1);
            assertThat(repository.all()).allSatisfy(company -> assertThat(sumHeldShares(database, company.companyId()))
                    .isEqualTo(company.listing().issuedShares()));

            service.initializeMissing().toCompletableFuture().join();

            assertThat(totalSecuritiesCash(database)).isEqualTo(cashAfterFirst);
            assertThat(securitiesCash(database, PARTICIPANT_ACCOUNT)).isEqualTo(participantCashAfterFirst);
            assertThat(repository.all()).allSatisfy(company -> assertThat(heldShares(database, company.companyId(), PARTICIPANT_ACCOUNT)).isEqualTo(20));
            assertThat(repository.all().stream().mapToLong(company -> heldShares(database, company.companyId(), PARTICIPANT_ACCOUNT)).sum()).isEqualTo(sharesAfterFirst);
            assertThat(count(database, "SELECT COUNT(*) FROM bluechip_system_participant_allocations")).isEqualTo(1);
        } finally { Files.deleteIfExists(file); }
    }

    @Test
    void reinitializationAcceptsTradedFundBalancesAndDoesNotMintCashAgain() throws Exception {
        var file = Files.createTempFile("blockstock-bluechip-live-fund-", ".db");
        try (Database database = migratedDatabase(file)) {
            SqlBluechipRepository repository = new SqlBluechipRepository(database.dataSource());
            BluechipBootstrapService service = service(database, repository, config());
            service.initializeMissing().toCompletableFuture().join();
            long cashAfterSeed = systemCash(database);
            var first = repository.all().getFirst();
            database.inTransaction(connection -> {
                try (PreparedStatement holding = connection.prepareStatement("UPDATE share_holdings SET available_shares = available_shares - 7 WHERE company_id = ? AND holder_uuid = ?")) {
                    holding.setString(1, first.companyId().value().toString()); holding.setString(2, SYSTEM_ACCOUNT.toString()); holding.executeUpdate();
                }
                try (PreparedStatement cash = connection.prepareStatement("UPDATE securities_cash_accounts SET available_minor = available_minor - 500 WHERE player_uuid = ?")) {
                    cash.setString(1, SYSTEM_ACCOUNT.toString()); cash.executeUpdate();
                }
                try (PreparedStatement audit = connection.prepareStatement("INSERT INTO bluechip_fund_audit (id, company_id, operation, cash_delta_minor, shares_delta, occurred_at) VALUES (?, ?, 'BLUECHIP_TRADE', -500, -7, ?)")) {
                    audit.setString(1, UUID.randomUUID().toString()); audit.setString(2, first.companyId().value().toString()); audit.setString(3, NOW.toString()); audit.executeUpdate();
                }
                return null;
            });

            assertThat(service.initializeMissing().toCompletableFuture().join().createdCompanies()).isZero();
            assertThat(systemCash(database)).isEqualTo(cashAfterSeed - 500);
            assertThat(repository.all().getFirst().fundShares()).isEqualTo(first.fundShares() - 7);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void refusesCodeChangedConfigurationBeforeCreatingAnotherBluechip() throws Exception {
        var file = Files.createTempFile("blockstock-bluechip-code-change-", ".db");
        try (Database database = migratedDatabase(file)) {
            SqlBluechipRepository repository = new SqlBluechipRepository(database.dataSource());
            service(database, repository, config()).initializeMissing().toCompletableFuture().join();

            assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> service(database, repository, config("CHANGED")).initializeMissing().toCompletableFuture().join()))
                    .hasMessageContaining("bluechip metadata does not match configuration");
            assertThat(repository.all()).hasSize(10);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void refusesExtraPersistedBluechipMetadataBeforeCreatingAnything() throws Exception {
        var file = Files.createTempFile("blockstock-bluechip-extra-", ".db");
        try (Database database = migratedDatabase(file)) {
            SqlBluechipRepository repository = new SqlBluechipRepository(database.dataSource());
            BluechipBootstrapService service = service(database, repository, config());
            service.initializeMissing().toCompletableFuture().join();
            insertExtraBluechipMetadata(database);

            assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> service.initializeMissing().toCompletableFuture().join()))
                    .hasMessageContaining("bluechip metadata does not match configuration");
            assertThat(repository.bluechipCompanyIds()).hasSize(11);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private BluechipBootstrapService service(Database database, SqlBluechipRepository repository, BluechipConfig config) {
        return service(database, repository, config, null);
    }
    private BluechipBootstrapService participantService(Database database, SqlBluechipRepository repository, BluechipConfig config) {
        return service(database, repository, config, PARTICIPANT_ACCOUNT);
    }
    private BluechipBootstrapService service(Database database, SqlBluechipRepository repository, BluechipConfig config, UUID participant) {
        var fundingRecords = new SqlBluechipBootstrapFundingRepository(database.dataSource());
        var funding = new BluechipBootstrapFundingService(SYSTEM_ACCOUNT, fundingRecords, database, new BluechipBootstrapFundingService.EscrowEconomy() {
            @Override public cn.blockeco.exchange.ports.EconomyGateway.Result withdraw(UUID player, Money amount) { return cn.blockeco.exchange.ports.EconomyGateway.Result.success("withdrawn"); }
            @Override public cn.blockeco.exchange.ports.EconomyGateway.Result deposit(UUID player, Money amount) { return cn.blockeco.exchange.ports.EconomyGateway.Result.success("deposited"); }
            @Override public cn.blockeco.exchange.ports.EconomyGateway.Result depositEscrow(Money amount) { return cn.blockeco.exchange.ports.EconomyGateway.Result.success("escrow deposited"); }
        }, new cn.blockeco.exchange.ports.MainThreadExecutor() { @Override public <T> java.util.concurrent.CompletionStage<T> submit(java.util.function.Supplier<T> work) { return java.util.concurrent.CompletableFuture.completedFuture(work.get()); } }, () -> NOW);
        return participant == null ? new BluechipBootstrapService(config, SYSTEM_ACCOUNT, new SqlCompanyRepository(database.dataSource()),
                new SqlStockListingRepository(database.dataSource()), repository, new SqlSecuritiesCashRepository(database.dataSource()), funding, fundingRecords,
                database, Runnable::run, () -> NOW) : new BluechipBootstrapService(config, SYSTEM_ACCOUNT, new SqlCompanyRepository(database.dataSource()),
                new SqlStockListingRepository(database.dataSource()), repository, new SqlSecuritiesCashRepository(database.dataSource()), funding, fundingRecords,
                database, Runnable::run, () -> NOW, participant, new SqlBluechipParticipantRepository());
    }

    private static long totalSecuritiesCash(Database database) { return count(database, "SELECT COALESCE(SUM(available_minor + reserved_minor), 0) FROM securities_cash_accounts"); }
    private static long securitiesCash(Database database, UUID account) {
        try (var connection = database.dataSource().getConnection(); var statement = connection.prepareStatement("SELECT available_minor + reserved_minor FROM securities_cash_accounts WHERE player_uuid = ?")) {
            statement.setString(1, account.toString()); try (var rows = statement.executeQuery()) { assertThat(rows.next()).isTrue(); return rows.getLong(1); }
        } catch (Exception exception) { throw new AssertionError(exception); }
    }
    private static long heldShares(Database database, CompanyId companyId, UUID holder) {
        try (var connection = database.dataSource().getConnection(); var statement = connection.prepareStatement("SELECT available_shares + reserved_shares FROM share_holdings WHERE company_id = ? AND holder_uuid = ?")) {
            statement.setString(1, companyId.value().toString()); statement.setString(2, holder.toString());
            try (var rows = statement.executeQuery()) { return rows.next() ? rows.getLong(1) : 0; }
        } catch (Exception exception) { throw new AssertionError(exception); }
    }

    private static Database migratedDatabase(java.nio.file.Path file) throws Exception {
        Database database = new Database("jdbc:sqlite:" + file);
        database.migrate();
        return database;
    }

    private static long systemCash(Database database) {
        try (var connection = database.dataSource().getConnection(); var statement = connection.prepareStatement("SELECT available_minor FROM securities_cash_accounts WHERE player_uuid = ?")) {
            statement.setString(1, SYSTEM_ACCOUNT.toString()); try (var rows = statement.executeQuery()) { assertThat(rows.next()).isTrue(); return rows.getLong(1); }
        } catch (Exception exception) { throw new AssertionError(exception); }
    }

    private static long stockSequence(Database database) {
        try (var connection = database.dataSource().getConnection(); var statement = connection.prepareStatement("SELECT last_value FROM stock_code_sequence WHERE singleton = 1"); ResultSet rows = statement.executeQuery()) {
            assertThat(rows.next()).isTrue();
            return rows.getLong(1);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static long sumHeldShares(Database database, CompanyId companyId) {
        try (var connection = database.dataSource().getConnection(); var statement = connection.prepareStatement(
                "SELECT COALESCE(SUM(available_shares + reserved_shares), 0) FROM share_holdings WHERE company_id = ?")) {
            statement.setString(1, companyId.value().toString());
            try (var rows = statement.executeQuery()) { assertThat(rows.next()).isTrue(); return rows.getLong(1); }
        } catch (Exception exception) { throw new AssertionError(exception); }
    }

    private static long companyTotalShares(Database database, CompanyId companyId) {
        try (var connection = database.dataSource().getConnection(); var statement = connection.prepareStatement(
                "SELECT total_shares FROM companies WHERE id = ?")) {
            statement.setString(1, companyId.value().toString());
            try (var rows = statement.executeQuery()) { assertThat(rows.next()).isTrue(); return rows.getLong(1); }
        } catch (Exception exception) { throw new AssertionError(exception); }
    }

    private static String initializationAudit(Database database, CompanyId companyId) {
        try (var connection = database.dataSource().getConnection(); var statement = connection.prepareStatement("SELECT payload_json FROM audit_events WHERE company_id=? AND event_type='BLUECHIP_INITIALIZED'")) {
            statement.setString(1, companyId.value().toString());
            try (ResultSet rows = statement.executeQuery()) { assertThat(rows.next()).isTrue(); return rows.getString(1); }
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static long count(Database database, String sql) {
        try (var connection = database.dataSource().getConnection(); var statement = connection.prepareStatement(sql); var rows = statement.executeQuery()) {
            assertThat(rows.next()).isTrue();
            return rows.getLong(1);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static void insertExtraBluechipMetadata(Database database) {
        UUID id = UUID.randomUUID();
        database.inTransaction(connection -> {
            try (PreparedStatement company = connection.prepareStatement("INSERT INTO companies (id, normalized_name, display_name, founder_uuid, status, treasury_minor, total_shares, dividend_basis_points, created_at) VALUES (?, 'extra bluechip', 'Extra Bluechip', ?, 'LISTED', 0, 1000, 5000, ?)");
                 PreparedStatement listing = connection.prepareStatement("INSERT INTO stock_listings (company_id, stock_code, issue_reference_price_minor, issued_shares, listed_at) VALUES (?, 'BS999999', 1, 1000, ?)");
                 PreparedStatement metadata = connection.prepareStatement("INSERT INTO bluechip_companies (company_id, industry, system_account_uuid, lower_price_minor, upper_price_minor, model_price_minor, spread_bps, event_sensitivity_bps, payout_bps, next_event_at, next_dividend_at) VALUES (?, 'Extra', ?, 1, 3, 2, 0, 0, 0, ?, ?)")) {
                company.setString(1, id.toString()); company.setString(2, UUID.randomUUID().toString()); company.setString(3, NOW.toString()); company.executeUpdate();
                listing.setString(1, id.toString()); listing.setString(2, NOW.toString()); listing.executeUpdate();
                metadata.setString(1, id.toString()); metadata.setString(2, SYSTEM_ACCOUNT.toString()); metadata.setString(3, NOW.toString()); metadata.setString(4, NOW.toString()); metadata.executeUpdate();
            }
            return null;
        });
    }

    private static BluechipConfig config() { return config("BC0"); }
    private static BluechipConfig config(String firstCode) {
        YamlConfiguration yaml = new YamlConfiguration();
        List<Map<String, Object>> entries = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("code", index == 0 ? firstCode : "BC" + index);
            entry.put("display-name", "System Company " + index);
            entry.put("industry", "Industry " + index);
            entry.put("reference-price", "10.00"); entry.put("lower-bound", "8.00"); entry.put("upper-bound", "12.00");
            entry.put("total-shares", 1_000_000L); entry.put("initial-fund-cash", "100000.00"); entry.put("initial-fund-shares", 100_000L);
            entry.put("spread-bps", 50); entry.put("event-sensitivity-bps", 100); entry.put("dividend-payout-bps", 2_000);
            entries.add(entry);
        }
        yaml.set("bluechips", entries);
        return BluechipConfig.load(yaml, 2);
    }

    private static final class RecordingEscrow implements cn.blockeco.exchange.ports.TreasuryEscrowGateway {
        int escrowDeposits;
        @Override public cn.blockeco.exchange.ports.EconomyGateway.Result withdrawPlayer(UUID playerId, Money amount, UUID operationId) { return cn.blockeco.exchange.ports.EconomyGateway.Result.success(""); }
        @Override public cn.blockeco.exchange.ports.EconomyGateway.Result depositEscrow(Money amount, UUID operationId) { escrowDeposits++; return cn.blockeco.exchange.ports.EconomyGateway.Result.success(""); }
        @Override public cn.blockeco.exchange.ports.EconomyGateway.Result withdrawEscrow(Money amount, UUID operationId) { return cn.blockeco.exchange.ports.EconomyGateway.Result.success(""); }
        @Override public cn.blockeco.exchange.ports.EconomyGateway.Result refundPlayer(UUID playerId, Money amount, UUID operationId) { return cn.blockeco.exchange.ports.EconomyGateway.Result.success(""); }
    }
}
