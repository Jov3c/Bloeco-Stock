package cn.blockeco.exchange.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.blockeco.exchange.domain.company.Company;
import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.company.DividendRate;
import cn.blockeco.exchange.domain.company.CompanyStatus;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.infrastructure.sql.Database;
import cn.blockeco.exchange.infrastructure.sql.SqlCompanyExitRepository;
import cn.blockeco.exchange.infrastructure.sql.SqlCompanyRepository;
import cn.blockeco.exchange.infrastructure.sql.SqlSecuritiesCashRepository;
import cn.blockeco.exchange.ports.CompanyPayoutGateway;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CompanyCapitalActionServiceTest {
    private static final Instant START = Instant.parse("2026-08-30T12:00:00Z");

    @Test
    void onlyListedCompanyFounderCanAnnounceCapitalAction() throws Exception {
        try (Fixture f = Fixture.create()) {
            assertThatThrownBy(() -> f.service.announceBuyback(UUID.randomUUID(), f.company.id(), Money.ofMinor(70), Money.ofMinor(7), "buyback:unauthorized"))
                    .isInstanceOf(IllegalArgumentException.class);
            f.setStatus(CompanyStatus.PENDING_ASSET_BINDING);
            assertThatThrownBy(() -> f.service.announceBuyback(f.founder, f.company.id(), Money.ofMinor(70), Money.ofMinor(7), "buyback:unlisted"))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void actionCannotExecuteBeforeItsTwelveHourAnnouncementPeriod() throws Exception {
        try (Fixture f = Fixture.create()) {
            UUID action = f.service.announceBuyback(f.founder, f.company.id(), Money.ofMinor(70), Money.ofMinor(7), "buyback:wait");

            assertThatThrownBy(() -> f.service.execute(f.founder, action)).isInstanceOf(IllegalStateException.class);
            assertThat(f.cash()).containsExactly(100L, 0L);

            f.now = START.plusSeconds(43_200);
            f.service.execute(f.founder, action);
            assertThat(f.cash()).containsExactly(100L, 70L);
        }
    }

    @Test
    void executingBuybackAcceptsVoluntarySaleOnlyOnceAndCreditsSecuritiesCash() throws Exception {
        try (Fixture f = Fixture.create()) {
            UUID shareholder = UUID.randomUUID();
            f.holding(shareholder, 10);
            UUID action = f.service.announceBuyback(f.founder, f.company.id(), Money.ofMinor(70), Money.ofMinor(7), "buyback:accept");
            f.now = START.plusSeconds(43_200); f.service.execute(f.founder, action);

            assertThat(f.service.acceptBuyback(shareholder, action, 10, "seller:one")).isTrue();
            assertThat(f.service.acceptBuyback(shareholder, action, 10, "seller:one")).isFalse();
            assertThat(f.cash()).containsExactly(30L, 0L);
            assertThat(f.securitiesCash(shareholder)).isEqualTo(70L);
        }
    }

    @Test
    void knownPayoutFailureReleasesReserveAndCancelsAction() throws Exception {
        try (Fixture f = Fixture.create()) {
            f.gateway.result = CompanyPayoutGateway.Result.knownFailure("Vault unavailable");
            UUID action = f.service.announceFounderCashOut(f.founder, f.company.id(), Money.ofMinor(70), "cashout:failure");
            f.now = START.plusSeconds(43_200);

            assertThat(f.service.execute(f.founder, action)).isEqualTo(CompanyCapitalActionService.ExecutionResult.FAILED);
            assertThat(f.cash()).containsExactly(100L, 0L);
            assertThat(f.payoutState()).isEqualTo("FAILED");
            assertThat(f.actionState(action)).isEqualTo("CANCELLED");
            assertThat(f.gateway.calls).isEqualTo(1);
        }
    }

    @Test
    void unknownPayoutNeverReplaysVaultAndRemainsRecoverable() throws Exception {
        try (Fixture f = Fixture.create()) {
            f.gateway.result = CompanyPayoutGateway.Result.unknown("Vault timeout");
            UUID action = f.service.announceFounderCashOut(f.founder, f.company.id(), Money.ofMinor(70), "cashout:unknown");
            f.now = START.plusSeconds(43_200);

            assertThat(f.service.execute(f.founder, action)).isEqualTo(CompanyCapitalActionService.ExecutionResult.AMBIGUOUS);
            assertThat(f.cash()).containsExactly(100L, 70L);
            assertThat(f.payoutState()).isEqualTo("AMBIGUOUS");
            assertThat(f.service.recoverablePayouts()).hasSize(1);
            assertThat(f.gateway.calls).isEqualTo(1);
            assertThatThrownBy(() -> f.service.execute(f.founder, action)).isInstanceOf(IllegalStateException.class);
            assertThat(f.gateway.calls).isEqualTo(1);
        }
    }

    @Test
    void confirmedPayoutDebitsAuthoritativeCashOnlyAfterExternalConfirmation() throws Exception {
        try (Fixture f = Fixture.create()) {
            UUID action = f.service.announceFounderCashOut(f.founder, f.company.id(), Money.ofMinor(70), "cashout:success");
            f.now = START.plusSeconds(43_200);

            assertThat(f.service.execute(f.founder, action)).isEqualTo(CompanyCapitalActionService.ExecutionResult.COMPLETED);
            assertThat(f.cash()).containsExactly(30L, 0L);
            assertThat(f.payoutState()).isEqualTo("COMPLETED");
            assertThat(f.actionState(action)).isEqualTo("EXECUTED");
        }
    }

    @Test
    void founderCashOutCannotConsumePaidInCapitalProtection() throws Exception {
        try (Fixture f = Fixture.create()) {
            f.protectCapital(100);
            UUID action = f.service.announceFounderCashOut(f.founder, f.company.id(), Money.ofMinor(1), "cashout:protected-capital");
            f.now = START.plusSeconds(43_200);

            assertThatThrownBy(() -> f.service.execute(f.founder, action)).isInstanceOf(IllegalStateException.class);
            assertThat(f.cash()).containsExactly(100L, 0L);
            assertThat(f.gateway.calls).isZero();
        }
    }

    @Test
    void founderCashOutIsRejectedAtAnnouncementWhenAdministratorLimitIsDisabledOrExceeded() throws Exception {
        try (Fixture f = Fixture.create()) {
            f.service = new CompanyCapitalActionService(new SqlCompanyRepository(f.database.dataSource()), new SqlCompanyExitRepository(f.database.dataSource()),
                    new SqlSecuritiesCashRepository(f.database.dataSource()), f.database, f.gateway, () -> f.now, () -> 0L);
            assertThatThrownBy(() -> f.service.announceFounderCashOut(f.founder, f.company.id(), Money.ofMinor(1), "cashout:disabled"))
                    .isInstanceOf(IllegalStateException.class);
            f.service = new CompanyCapitalActionService(new SqlCompanyRepository(f.database.dataSource()), new SqlCompanyExitRepository(f.database.dataSource()),
                    new SqlSecuritiesCashRepository(f.database.dataSource()), f.database, f.gateway, () -> f.now, () -> 50L);
            assertThatThrownBy(() -> f.service.announceFounderCashOut(f.founder, f.company.id(), Money.ofMinor(51), "cashout:over-limit"))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Path file; final Database database; final Company company; final UUID founder; final RecordingGateway gateway = new RecordingGateway();
        Instant now = START; CompanyCapitalActionService service;
        private Fixture(Path file, Database database, Company company) {
            this.file = file; this.database = database; this.company = company; this.founder = company.founderId();
            service = new CompanyCapitalActionService(new SqlCompanyRepository(database.dataSource()), new SqlCompanyExitRepository(database.dataSource()),
                    new SqlSecuritiesCashRepository(database.dataSource()), database, gateway, () -> now);
        }
        static Fixture create() throws Exception {
            Path file = Files.createTempFile("blockstock-capital-action-", ".db"); Database database = new Database("jdbc:sqlite:" + file); database.migrate();
            Company company = Company.rehydrate(new CompanyId(UUID.randomUUID()), "Capital Action", "capital action", UUID.randomUUID(), Money.ofMinor(999_999), 1_000, DividendRate.FIFTY, CompanyStatus.LISTED, START);
            database.inTransaction(c -> { insertCompany(c, company); insertCash(c, company.id(), 100); insertListing(c, company.id()); return null; });
            return new Fixture(file, database, company);
        }
        void setStatus(CompanyStatus status) { database.inTransaction(c -> { try (PreparedStatement s = c.prepareStatement("UPDATE companies SET status=? WHERE id=?")) { s.setString(1, status.name()); s.setString(2, company.id().value().toString()); s.executeUpdate(); } return null; }); }
        void protectCapital(long paidInCapital) { database.inTransaction(c -> { try (PreparedStatement s = c.prepareStatement("UPDATE company_cash_accounts SET paid_in_capital_minor=? WHERE company_id=?")) { s.setLong(1, paidInCapital); s.setString(2, company.id().value().toString()); s.executeUpdate(); } return null; }); }
        void holding(UUID player, long shares) { database.inTransaction(c -> { try (PreparedStatement s = c.prepareStatement("INSERT INTO share_holdings (company_id,holder_uuid,available_shares,reserved_shares) VALUES (?,?,?,0)")) { s.setString(1, company.id().value().toString()); s.setString(2, player.toString()); s.setLong(3, shares); s.executeUpdate(); } return null; }); }
        long[] cash() throws Exception { try (Connection c = database.dataSource().getConnection(); PreparedStatement s = c.prepareStatement("SELECT cash_minor,reserved_minor FROM company_cash_accounts WHERE company_id=?")) { s.setString(1, company.id().value().toString()); var rows = s.executeQuery(); rows.next(); return new long[]{rows.getLong(1), rows.getLong(2)}; } }
        long securitiesCash(UUID player) throws Exception { try (Connection c = database.dataSource().getConnection(); PreparedStatement s = c.prepareStatement("SELECT available_minor FROM securities_cash_accounts WHERE player_uuid=?")) { s.setString(1, player.toString()); var rows = s.executeQuery(); rows.next(); return rows.getLong(1); } }
        String payoutState() throws Exception { return string("SELECT state FROM company_payout_operations"); }
        String actionState(UUID id) throws Exception { return string("SELECT state FROM company_governance_actions WHERE id='" + id + "'"); }
        String string(String query) throws Exception { try (Connection c = database.dataSource().getConnection(); PreparedStatement s = c.prepareStatement(query); var rows = s.executeQuery()) { rows.next(); return rows.getString(1); } }
        static void insertCompany(Connection c, Company x) throws java.sql.SQLException { try (PreparedStatement s = c.prepareStatement("INSERT INTO companies (id,normalized_name,display_name,founder_uuid,status,treasury_minor,total_shares,dividend_basis_points,created_at) VALUES (?,?,?,?,?,?,?,?,?)")) { s.setString(1,x.id().value().toString());s.setString(2,x.normalizedName());s.setString(3,x.displayName());s.setString(4,x.founderId().toString());s.setString(5,x.status().name());s.setLong(6,x.treasury().minorUnits());s.setLong(7,x.totalShares());s.setInt(8,x.dividendRate().basisPoints());s.setString(9,x.createdAt().toString());s.executeUpdate(); } }
        static void insertCash(Connection c, CompanyId id, long cash) throws java.sql.SQLException { try (PreparedStatement s = c.prepareStatement("INSERT INTO company_cash_accounts (company_id,cash_minor,paid_in_capital_minor,retained_earnings_minor,reserved_minor,accumulated_loss_minor) VALUES (?,?,0,0,0,0)")) { s.setString(1,id.value().toString());s.setLong(2,cash);s.executeUpdate(); } }
        static void insertListing(Connection c, CompanyId id) throws java.sql.SQLException { try (PreparedStatement s = c.prepareStatement("INSERT INTO stock_listings (company_id,stock_code,issue_reference_price_minor,issued_shares,listed_at) VALUES (?,'CA000001',1,1000,?)")) {s.setString(1,id.value().toString());s.setString(2,START.toString());s.executeUpdate();} }
        @Override public void close() throws Exception { database.close(); Files.deleteIfExists(file); }
    }
    private static final class RecordingGateway implements CompanyPayoutGateway { int calls; Result result = Result.success("ok"); @Override public Result depositFounder(UUID recipient, Money amount, UUID operationId) { calls++; return result; } }
}
