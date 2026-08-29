package cn.blockeco.exchange.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.governance.IssuanceProposalState;
import cn.blockeco.exchange.domain.governance.VoteChoice;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.infrastructure.sql.Database;
import cn.blockeco.exchange.infrastructure.sql.SqlShareIssuanceRepository;
import cn.blockeco.exchange.infrastructure.sql.SqlSecuritiesCashRepository;
import cn.blockeco.exchange.ports.AppClock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ShareIssuanceServiceTest {
    @Test void onlyFounderMayProposeAndOnlySnapshotHolderMayVote() throws Exception {
        Path file = Files.createTempFile("issuance-service-", ".db");
        try (Database db = new Database("jdbc:sqlite:" + file)) {
            db.migrate();
            CompanyId company = listedCompany(db); UUID founder = Fixtures.founder(db, company);
            seedHolding(db, company, founder, 60); UUID outsider = UUID.randomUUID();
            MutableClock clock = new MutableClock(); ShareIssuanceService service = service(db, clock);

            assertThatThrownBy(() -> service.propose(outsider, company, 10, Money.ofMinor(5)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("founders");
            var proposal = service.propose(founder, company, 10, Money.ofMinor(5));
            assertThat(proposal.announcedAt()).isEqualTo(clock.now());
            assertThat(proposal.state()).isEqualTo(IssuanceProposalState.ANNOUNCED);

            clock.advance(Duration.ofHours(12));
            assertThat(service.advanceDueProposals()).containsExactly(proposal.id());
            assertThat(state(db, proposal.id())).isEqualTo(IssuanceProposalState.VOTING);
            assertThatThrownBy(() -> service.vote(outsider, proposal.id(), VoteChoice.YES))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("record-date");
            assertThat(service.vote(founder, proposal.id(), VoteChoice.YES)).isEqualTo(60);
            clock.advance(Duration.ofDays(2));
            assertThatThrownBy(() -> service.vote(founder, proposal.id(), VoteChoice.NO))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("not open");
        } finally { Files.deleteIfExists(file); }
    }

    @Test void proposalBecomesApprovedOnlyAtBothVoteThresholds() throws Exception {
        Path file = Files.createTempFile("issuance-threshold-", ".db");
        try (Database db = new Database("jdbc:sqlite:" + file)) {
            db.migrate(); CompanyId company = listedCompany(db); UUID founder = Fixtures.founder(db, company);
            UUID holder = UUID.randomUUID(); seedHolding(db, company, founder, 20); seedHolding(db, company, holder, 80);
            MutableClock clock = new MutableClock(); ShareIssuanceService service = service(db, clock);
            var proposal = service.propose(founder, company, 10, Money.ofMinor(5));

            clock.advance(Duration.ofHours(12)); service.advanceDueProposals();
            service.vote(founder, proposal.id(), VoteChoice.YES); // yes/effective is 100%, but 20% participation.
            clock.advance(Duration.ofDays(2));
            assertThat(service.advanceDueProposals()).containsExactly(proposal.id());
            assertThat(state(db, proposal.id())).isEqualTo(IssuanceProposalState.REJECTED);
            assertThat(service.advanceDueProposals()).isEmpty();

            var approved = service.propose(founder, company, 10, Money.ofMinor(5));
            clock.advance(Duration.ofHours(12)); service.advanceDueProposals();
            service.vote(founder, approved.id(), VoteChoice.YES); service.vote(holder, approved.id(), VoteChoice.NO);
            clock.advance(Duration.ofDays(2)); service.advanceDueProposals();
            assertThat(state(db, approved.id())).isEqualTo(IssuanceProposalState.REJECTED); // 20% yes of 100% effective.

            var passing = service.propose(founder, company, 10, Money.ofMinor(5));
            clock.advance(Duration.ofHours(12)); service.advanceDueProposals();
            service.vote(founder, passing.id(), VoteChoice.YES); service.vote(holder, passing.id(), VoteChoice.YES);
            clock.advance(Duration.ofDays(2)); service.advanceDueProposals();
            assertThat(state(db, passing.id())).isEqualTo(IssuanceProposalState.APPROVED);
        } finally { Files.deleteIfExists(file); }
    }

    @Test void rejectedProposalLeavesCompanyCashHoldingsAndTotalSharesUnchanged() throws Exception {
        Path file = Files.createTempFile("issuance-rejection-", ".db");
        try (Database db = new Database("jdbc:sqlite:" + file)) {
            db.migrate(); CompanyId company = listedCompany(db); UUID founder = Fixtures.founder(db, company);
            seedHolding(db, company, founder, 100); MutableClock clock = new MutableClock(); ShareIssuanceService service = service(db, clock);
            long cashBefore = number(db, "SELECT cash_minor FROM company_cash_accounts WHERE company_id=?", company.value().toString());
            long sharesBefore = number(db, "SELECT total_shares FROM companies WHERE id=?", company.value().toString());
            long holdingBefore = holding(db, company, founder);
            var proposal = service.propose(founder, company, 10, Money.ofMinor(5));
            clock.advance(Duration.ofHours(12)); service.advanceDueProposals(); service.vote(founder, proposal.id(), VoteChoice.NO);
            clock.advance(Duration.ofDays(2)); service.advanceDueProposals();

            assertThat(state(db, proposal.id())).isEqualTo(IssuanceProposalState.REJECTED);
            assertThat(number(db, "SELECT cash_minor FROM company_cash_accounts WHERE company_id=?", company.value().toString())).isEqualTo(cashBefore);
            assertThat(number(db, "SELECT total_shares FROM companies WHERE id=?", company.value().toString())).isEqualTo(sharesBefore);
            assertThat(holding(db, company, founder)).isEqualTo(holdingBefore);
        } finally { Files.deleteIfExists(file); }
    }

    @Test void subscriptionReservesSecuritiesCashAndDuplicateKeyDoesNotDoubleReserve() throws Exception {
        Path file = Files.createTempFile("issuance-subscribe-cash-", ".db");
        try (Database db = new Database("jdbc:sqlite:" + file)) {
            db.migrate(); CompanyId company = listedCompany(db); UUID founder = Fixtures.founder(db, company), holder = UUID.randomUUID();
            MutableClock clock = new MutableClock(); ShareIssuanceService service = service(db, clock); var proposal = subscribingProposal(db, service, founder, company, clock);
            SqlSecuritiesCashRepository cash = new SqlSecuritiesCashRepository(db.dataSource());
            db.inTransaction(c -> { cash.creditAvailable(c, holder, Money.ofMinor(100), clock.now()); return null; });

            service.subscribe(holder, proposal.id(), 6, "subscribe-1");
            service.subscribe(holder, proposal.id(), 6, "subscribe-1");

            assertThat(cash.find(holder)).hasValueSatisfying(account -> {
                assertThat(account.available().minorUnits()).isEqualTo(40);
                assertThat(account.reserved().minorUnits()).isEqualTo(60);
            });
            assertThat(number(db, "SELECT COUNT(*) FROM issuance_subscriptions WHERE proposal_id=?", proposal.id().toString())).isEqualTo(1);
        } finally { Files.deleteIfExists(file); }
    }

    @Test void subscriptionCorrelationReplayRejectsDifferentHolderOrShareCount() throws Exception {
        Path file = Files.createTempFile("issuance-subscribe-replay-", ".db");
        try (Database db = new Database("jdbc:sqlite:" + file)) {
            db.migrate(); CompanyId company = listedCompany(db); UUID founder = Fixtures.founder(db, company), holder = UUID.randomUUID(), other = UUID.randomUUID();
            MutableClock clock = new MutableClock(); ShareIssuanceService service = service(db, clock); var proposal = subscribingProposal(db, service, founder, company, clock);
            SqlSecuritiesCashRepository cash = new SqlSecuritiesCashRepository(db.dataSource());
            db.inTransaction(c -> { cash.creditAvailable(c, holder, Money.ofMinor(100), clock.now()); cash.creditAvailable(c, other, Money.ofMinor(100), clock.now()); return null; });
            service.subscribe(holder, proposal.id(), 2, "replay-key");

            assertThatThrownBy(() -> service.subscribe(other, proposal.id(), 2, "replay-key")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("correlation");
            assertThatThrownBy(() -> service.subscribe(holder, proposal.id(), 3, "replay-key")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("correlation");
            assertThat(cash.find(other)).hasValueSatisfying(a -> { assertThat(a.available().minorUnits()).isEqualTo(100); assertThat(a.reserved().minorUnits()).isZero(); });
        } finally { Files.deleteIfExists(file); }
    }

    @Test void settlementHoldingOverflowRollsBackCashAndShareProjections() throws Exception {
        Path file = Files.createTempFile("issuance-holding-overflow-", ".db");
        try (Database db = new Database("jdbc:sqlite:" + file)) {
            db.migrate(); CompanyId company = listedCompany(db); UUID founder = Fixtures.founder(db, company), holder = UUID.randomUUID();
            MutableClock clock = new MutableClock(); ShareIssuanceService service = service(db, clock); var proposal = subscribingProposal(db, service, founder, company, clock, 1, 1);
            SqlSecuritiesCashRepository cash = new SqlSecuritiesCashRepository(db.dataSource()); seedHolding(db, company, holder, Long.MAX_VALUE); db.inTransaction(c -> { cash.creditAvailable(c, holder, Money.ofMinor(1), clock.now()); return null; });
            service.subscribe(holder, proposal.id(), 1, "overflow-holding");

            assertThatThrownBy(() -> service.closeSubscription(proposal.id())).isInstanceOf(ArithmeticException.class);
            assertThat(holding(db, company, holder)).isEqualTo(Long.MAX_VALUE);
            assertThat(cash.find(holder)).hasValueSatisfying(a -> { assertThat(a.available().minorUnits()).isZero(); assertThat(a.reserved().minorUnits()).isEqualTo(1); });
            assertThat(state(db, proposal.id())).isEqualTo(IssuanceProposalState.SUBSCRIBING);
        } finally { Files.deleteIfExists(file); }
    }

    @Test void settlementPaidInCapitalOverflowRollsBackCashAndShareProjections() throws Exception {
        Path file = Files.createTempFile("issuance-capital-overflow-", ".db");
        try (Database db = new Database("jdbc:sqlite:" + file)) {
            db.migrate(); CompanyId company = listedCompany(db); UUID founder = Fixtures.founder(db, company), holder = UUID.randomUUID();
            MutableClock clock = new MutableClock(); ShareIssuanceService service = service(db, clock); var proposal = subscribingProposal(db, service, founder, company, clock, 1, 1);
            SqlSecuritiesCashRepository cash = new SqlSecuritiesCashRepository(db.dataSource()); db.inTransaction(c -> { try (PreparedStatement statement = c.prepareStatement("UPDATE company_cash_accounts SET paid_in_capital_minor=? WHERE company_id=?")) { statement.setLong(1, Long.MAX_VALUE); statement.setString(2, company.value().toString()); statement.executeUpdate(); } cash.creditAvailable(c, holder, Money.ofMinor(1), clock.now()); return null; });
            service.subscribe(holder, proposal.id(), 1, "overflow-capital");

            assertThatThrownBy(() -> service.closeSubscription(proposal.id())).isInstanceOf(ArithmeticException.class);
            assertThat(number(db, "SELECT COUNT(*) FROM share_holdings WHERE company_id=? AND holder_uuid=?", company.value().toString(), holder.toString())).isZero();
            assertThat(cash.find(holder)).hasValueSatisfying(a -> { assertThat(a.available().minorUnits()).isZero(); assertThat(a.reserved().minorUnits()).isEqualTo(1); });
            assertThat(state(db, proposal.id())).isEqualTo(IssuanceProposalState.SUBSCRIBING);
        } finally { Files.deleteIfExists(file); }
    }

    @Test void closeSettlesCapacityThenReleasesUnfilledCashAndReconcilesLedgers() throws Exception {
        Path file = Files.createTempFile("issuance-close-cash-", ".db");
        try (Database db = new Database("jdbc:sqlite:" + file)) {
            db.migrate(); CompanyId company = listedCompany(db); UUID founder = Fixtures.founder(db, company), first = UUID.randomUUID(), second = UUID.randomUUID();
            seedHolding(db, company, founder, 1_000);
            MutableClock clock = new MutableClock(); ShareIssuanceService service = service(db, clock); var proposal = subscribingProposal(db, service, founder, company, clock, 10, 5);
            SqlSecuritiesCashRepository cash = new SqlSecuritiesCashRepository(db.dataSource());
            db.inTransaction(c -> { cash.creditAvailable(c, first, Money.ofMinor(100), clock.now()); cash.creditAvailable(c, second, Money.ofMinor(100), clock.now()); return null; });
            service.subscribe(first, proposal.id(), 7, "first"); service.subscribe(second, proposal.id(), 7, "second");

            service.closeSubscription(proposal.id()); service.closeSubscription(proposal.id());

            assertThat(cash.find(first)).hasValueSatisfying(a -> { assertThat(a.available().minorUnits()).isEqualTo(65); assertThat(a.reserved().minorUnits()).isZero(); });
            assertThat(cash.find(second)).hasValueSatisfying(a -> { assertThat(a.available().minorUnits()).isEqualTo(85); assertThat(a.reserved().minorUnits()).isZero(); });
            assertThat(holding(db, company, first)).isEqualTo(7); assertThat(holding(db, company, second)).isEqualTo(3);
            assertThat(number(db, "SELECT total_shares FROM companies WHERE id=?", company.value().toString())).isEqualTo(1_010);
            assertThat(number(db, "SELECT COALESCE(SUM(available_shares + reserved_shares),0) FROM share_holdings WHERE company_id=?", company.value().toString())).isEqualTo(1_010);
            assertThat(number(db, "SELECT cash_minor FROM company_cash_accounts WHERE company_id=?", company.value().toString())).isEqualTo(150);
            assertThat(number(db, "SELECT COALESCE(SUM(amount_minor),0) FROM escrow_ledger_entries WHERE liability_kind='SECURITIES_CASH'")).isEqualTo(150);
            assertThat(number(db, "SELECT COALESCE(SUM(amount_minor),0) FROM escrow_ledger_entries WHERE liability_kind='COMPANY_TREASURY' AND company_id=?", company.value().toString())).isEqualTo(150);
            assertThat(cash.reconcile(Money.ofMinor(300))).isNotNull();
        } finally { Files.deleteIfExists(file); }
    }

    @Test void closingProposalUpdatesPublicIssuedSharesWithCompanyTotalShares() throws Exception {
        Path file = Files.createTempFile("issuance-close-listing-", ".db");
        try (Database db = new Database("jdbc:sqlite:" + file)) {
            db.migrate(); CompanyId company = listedCompany(db); UUID founder = Fixtures.founder(db, company), holder = UUID.randomUUID();
            MutableClock clock = new MutableClock(); ShareIssuanceService service = service(db, clock); var proposal = subscribingProposal(db, service, founder, company, clock, 5, 2);
            SqlSecuritiesCashRepository cash = new SqlSecuritiesCashRepository(db.dataSource()); db.inTransaction(c -> { cash.creditAvailable(c, holder, Money.ofMinor(10), clock.now()); return null; });
            service.subscribe(holder, proposal.id(), 5, "all"); service.closeSubscription(proposal.id());

            assertThat(number(db, "SELECT issued_shares FROM stock_listings WHERE company_id=?", company.value().toString())).isEqualTo(1_005);
            assertThat(number(db, "SELECT issued_shares FROM stock_listings WHERE company_id=?", company.value().toString())).isEqualTo(number(db, "SELECT total_shares FROM companies WHERE id=?", company.value().toString()));
        } finally { Files.deleteIfExists(file); }
    }

    private static ShareIssuanceService service(Database db, AppClock clock) { return new ShareIssuanceService(new SqlShareIssuanceRepository(db.dataSource()), new SqlSecuritiesCashRepository(db.dataSource()), db, clock); }
    private static cn.blockeco.exchange.domain.governance.IssuanceProposal subscribingProposal(Database db, ShareIssuanceService service, UUID founder, CompanyId company, MutableClock clock) { return subscribingProposal(db, service, founder, company, clock, 10, 10); }
    private static cn.blockeco.exchange.domain.governance.IssuanceProposal subscribingProposal(Database db, ShareIssuanceService service, UUID founder, CompanyId company, MutableClock clock, long shares, long price) { var proposal = service.propose(founder, company, shares, Money.ofMinor(price)); db.inTransaction(c -> { try (PreparedStatement statement = c.prepareStatement("UPDATE issuance_proposals SET state='SUBSCRIBING' WHERE id=?")) { statement.setString(1, proposal.id().toString()); statement.executeUpdate(); } return null; }); return proposal; }
    private static CompanyId listedCompany(Database db) throws Exception { CompanyId company = Fixtures.company(db, 100); UUID founder = Fixtures.founder(db, company); Fixtures.activeAsset(db, company, founder); db.inTransaction(c -> { try (PreparedStatement update = c.prepareStatement("UPDATE companies SET status='LISTED' WHERE id=?"); PreparedStatement listing = c.prepareStatement("INSERT INTO stock_listings (company_id, stock_code, issue_reference_price_minor, issued_shares, listed_at) VALUES (?, ?, ?, ?, ?)"); PreparedStatement ledger = c.prepareStatement("INSERT INTO escrow_ledger_entries (id,liability_kind,company_id,player_uuid,amount_minor,operation_id,trade_id,occurred_at) VALUES (?, 'COMPANY_TREASURY', ?, NULL, 100, NULL, NULL, ?)")) { update.setString(1, company.value().toString()); update.executeUpdate(); listing.setString(1, company.value().toString()); listing.setString(2, "BS" + String.format("%06d", Math.floorMod(company.value().hashCode(), 999999) + 1)); listing.setLong(3, 10); listing.setLong(4, 1000); listing.setString(5, Instant.EPOCH.toString()); listing.executeUpdate(); ledger.setString(1, "test-company-opening-" + company.value()); ledger.setString(2, company.value().toString()); ledger.setString(3, Instant.EPOCH.toString()); ledger.executeUpdate(); } return null; }); return company; }
    private static void seedHolding(Database db, CompanyId company, UUID holder, long shares) { db.inTransaction(c -> { try (PreparedStatement statement = c.prepareStatement("INSERT INTO share_holdings (company_id,holder_uuid,available_shares,reserved_shares) VALUES (?,?,?,0)")) { statement.setString(1, company.value().toString()); statement.setString(2, holder.toString()); statement.setLong(3, shares); statement.executeUpdate(); } return null; }); }
    private static IssuanceProposalState state(Database db, UUID id) { return IssuanceProposalState.valueOf(text(db, "SELECT state FROM issuance_proposals WHERE id=?", id.toString())); }
    private static long holding(Database db, CompanyId company, UUID holder) { return number(db, "SELECT available_shares + reserved_shares FROM share_holdings WHERE company_id=? AND holder_uuid=?", company.value().toString(), holder.toString()); }
    private static long number(Database db, String sql, String... values) { return Long.parseLong(text(db, sql, values)); }
    private static String text(Database db, String sql, String... values) { try (var connection = db.dataSource().getConnection(); var statement = connection.prepareStatement(sql)) { for (int index = 0; index < values.length; index++) statement.setString(index + 1, values[index]); try (var rows = statement.executeQuery()) { rows.next(); return rows.getString(1); } } catch (Exception exception) { throw new IllegalStateException(exception); } }
    private static final class MutableClock implements AppClock { private Instant instant = Instant.parse("2026-08-30T00:00:00Z"); @Override public Instant now() { return instant; } void advance(Duration duration) { instant = instant.plus(duration); } }
}
