package cn.blockeco.exchange.infrastructure.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.blockeco.exchange.application.Fixtures;
import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.governance.IssuanceProposal;
import cn.blockeco.exchange.domain.governance.VoteChoice;
import cn.blockeco.exchange.ports.ShareIssuanceRepository;
import java.nio.file.Files;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SqlShareIssuanceRepositoryTest {
    @Test
    void proposal_snapshots_available_and_reserved_shares_at_record_date() throws Exception {
        var file = Files.createTempFile("issuance-snapshot-", ".db");
        try (Database db = new Database("jdbc:sqlite:" + file)) {
            db.migrate();
            CompanyId company = listedPlayerCompany(db);
            UUID founder = Fixtures.founder(db, company), holder = UUID.randomUUID();
            seedHolding(db, company, founder, 80, 20);
            seedHolding(db, company, holder, 30, 7);
            ShareIssuanceRepository repository = new SqlShareIssuanceRepository(db.dataSource());
            IssuanceProposal proposal = proposal(company, founder);

            db.inTransaction(connection -> { repository.createProposal(connection, proposal, proposal.announcedAt()); return null; });
            db.inTransaction(connection -> { changeHolding(connection, company, holder, 0, 999); return null; });

            assertThat(repository.tally(proposal.id()).snapshotShares()).isEqualTo(137);
            assertThat(repository.snapshotWeight(proposal.id(), founder)).isEqualTo(100);
            assertThat(repository.snapshotWeight(proposal.id(), holder)).isEqualTo(37);
            assertThat(count(db, "SELECT COUNT(*) FROM company_announcements WHERE id=?", proposal.id() + ":ISSUANCE_PROPOSED")).isEqualTo(1);
            assertThat(count(db, "SELECT COUNT(*) FROM audit_events WHERE event_id=?", proposal.id() + ":ISSUANCE_PROPOSED")).isEqualTo(1);
        } finally { Files.deleteIfExists(file); }
    }

    @Test
    void later_vote_replaces_earlier_choice_without_changing_snapshot_weight() throws Exception {
        var file = Files.createTempFile("issuance-vote-", ".db");
        try (Database db = new Database("jdbc:sqlite:" + file)) {
            db.migrate();
            CompanyId company = listedPlayerCompany(db); UUID founder = Fixtures.founder(db, company);
            seedHolding(db, company, founder, 20, 5);
            ShareIssuanceRepository repository = new SqlShareIssuanceRepository(db.dataSource());
            IssuanceProposal proposal = proposal(company, founder);
            db.inTransaction(connection -> { repository.createProposal(connection, proposal, proposal.announcedAt()); return null; });

            long firstWeight = db.inTransaction(connection -> repository.castVote(connection, proposal.id(), founder, VoteChoice.YES, proposal.announcedAt()));
            assertThat(firstWeight).isEqualTo(25);
            db.inTransaction(connection -> { changeHolding(connection, company, founder, 1_000, 0); return null; });
            long replacementWeight = db.inTransaction(connection -> repository.castVote(connection, proposal.id(), founder, VoteChoice.NO, proposal.announcedAt().plusSeconds(1)));
            assertThat(replacementWeight).isEqualTo(25);

            assertThat(repository.tally(proposal.id())).extracting(ShareIssuanceRepository.VoteTally::effectiveShares, ShareIssuanceRepository.VoteTally::yesShares, ShareIssuanceRepository.VoteTally::noShares, ShareIssuanceRepository.VoteTally::abstainShares).containsExactly(25L, 0L, 25L, 0L);
            assertThat(count(db, "SELECT COUNT(*) FROM issuance_votes WHERE proposal_id=? AND voter_uuid=?", proposal.id().toString(), founder.toString())).isEqualTo(1);
        } finally { Files.deleteIfExists(file); }
    }

    @Test
    void bluechip_and_unlisted_company_cannot_create_proposal() throws Exception {
        var file = Files.createTempFile("issuance-eligibility-", ".db");
        try (Database db = new Database("jdbc:sqlite:" + file)) {
            db.migrate();
            CompanyId listed = listedPlayerCompany(db); UUID founder = Fixtures.founder(db, listed);
            CompanyId unlisted = unlistedPlayerCompany(db); UUID unlistedFounder = Fixtures.founder(db, unlisted);
            makeBluechip(db, listed);
            ShareIssuanceRepository repository = new SqlShareIssuanceRepository(db.dataSource());

            assertThatThrownBy(() -> db.inTransaction(connection -> { repository.createProposal(connection, proposal(listed, founder), Instant.EPOCH); return null; })).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("bluechip");
            assertThatThrownBy(() -> db.inTransaction(connection -> { repository.createProposal(connection, proposal(unlisted, unlistedFounder), Instant.EPOCH); return null; })).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("listed");
        } finally { Files.deleteIfExists(file); }
    }

    @Test
    void company_marked_listed_without_stock_listing_cannot_create_proposal() throws Exception {
        var file = Files.createTempFile("issuance-listing-", ".db");
        try (Database db = new Database("jdbc:sqlite:" + file)) {
            db.migrate();
            CompanyId company = Fixtures.company(db, 100); UUID founder = Fixtures.founder(db, company);
            db.inTransaction(connection -> {
                try (var statement = connection.prepareStatement("UPDATE companies SET status='LISTED' WHERE id=?")) {
                    statement.setString(1, company.value().toString());
                    statement.executeUpdate();
                }
                return null;
            });
            ShareIssuanceRepository repository = new SqlShareIssuanceRepository(db.dataSource());

            assertThatThrownBy(() -> db.inTransaction(connection -> {
                repository.createProposal(connection, proposal(company, founder), Instant.EPOCH);
                return null;
            })).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("listed");
        } finally { Files.deleteIfExists(file); }
    }

    @Test
    void record_date_snapshots_and_vote_weights_cannot_be_mutated_by_direct_sql() throws Exception {
        var file = Files.createTempFile("issuance-immutable-", ".db");
        try (Database db = new Database("jdbc:sqlite:" + file)) {
            db.migrate();
            CompanyId company = listedPlayerCompany(db); UUID founder = Fixtures.founder(db, company);
            seedHolding(db, company, founder, 20, 5);
            ShareIssuanceRepository repository = new SqlShareIssuanceRepository(db.dataSource());
            IssuanceProposal proposal = proposal(company, founder);
            db.inTransaction(connection -> { repository.createProposal(connection, proposal, proposal.announcedAt()); return null; });
            db.inTransaction(connection -> repository.castVote(connection, proposal.id(), founder, VoteChoice.YES, proposal.announcedAt()));

            assertThatThrownBy(() -> db.inTransaction(connection -> execute(connection, "UPDATE issuance_record_snapshots SET shares=999 WHERE proposal_id=? AND voter_uuid=?", proposal.id().toString(), founder.toString()))).isInstanceOf(IllegalStateException.class).hasStackTraceContaining("issuance record snapshots are immutable");
            assertThatThrownBy(() -> db.inTransaction(connection -> execute(connection, "DELETE FROM issuance_record_snapshots WHERE proposal_id=? AND voter_uuid=?", proposal.id().toString(), founder.toString()))).isInstanceOf(IllegalStateException.class).hasStackTraceContaining("issuance record snapshots are immutable");
            assertThatThrownBy(() -> db.inTransaction(connection -> execute(connection, "UPDATE issuance_votes SET snapshot_shares=999 WHERE proposal_id=? AND voter_uuid=?", proposal.id().toString(), founder.toString()))).isInstanceOf(IllegalStateException.class).hasStackTraceContaining("issuance vote snapshot shares are immutable");

            long replacementWeight = db.inTransaction(connection -> repository.castVote(connection, proposal.id(), founder, VoteChoice.NO, proposal.announcedAt().plusSeconds(1)));
            assertThat(replacementWeight).isEqualTo(25);
            assertThat(repository.tally(proposal.id())).extracting(ShareIssuanceRepository.VoteTally::effectiveShares, ShareIssuanceRepository.VoteTally::noShares).containsExactly(25L, 25L);
        } finally { Files.deleteIfExists(file); }
    }

    @Test
    void subscription_correlation_key_is_unique_per_proposal_but_distinguishes_valid_requests() throws Exception {
        var file = Files.createTempFile("issuance-subscription-key-", ".db");
        try (Database db = new Database("jdbc:sqlite:" + file)) {
            db.migrate();
            CompanyId company = listedPlayerCompany(db); UUID founder = Fixtures.founder(db, company);
            IssuanceProposal proposal = proposal(company, founder);
            ShareIssuanceRepository repository = new SqlShareIssuanceRepository(db.dataSource());
            db.inTransaction(connection -> { repository.createProposal(connection, proposal, proposal.announcedAt()); return null; });

            db.inTransaction(connection -> insertSubscription(connection, UUID.randomUUID(), proposal.id(), founder, "request-1"));
            assertThatThrownBy(() -> db.inTransaction(connection -> insertSubscription(connection, UUID.randomUUID(), proposal.id(), founder, "request-1"))).isInstanceOf(IllegalStateException.class).hasMessageContaining("database transaction failed");
            db.inTransaction(connection -> insertSubscription(connection, UUID.randomUUID(), proposal.id(), founder, "request-2"));

            assertThat(count(db, "SELECT COUNT(*) FROM issuance_subscriptions WHERE proposal_id=?", proposal.id().toString())).isEqualTo(2);
        } finally { Files.deleteIfExists(file); }
    }

    private static IssuanceProposal proposal(CompanyId company, UUID founder) { return new IssuanceProposal(UUID.randomUUID(), company, founder, 100, 10, Instant.parse("2026-08-30T12:00:00Z")); }
    private static CompanyId listedPlayerCompany(Database db) throws Exception { CompanyId company = Fixtures.company(db, 100); db.inTransaction(c -> { try (var s = c.prepareStatement("UPDATE companies SET status='LISTED' WHERE id=?")) { s.setString(1, company.value().toString()); s.executeUpdate(); } try (var s = c.prepareStatement("INSERT INTO stock_listings (company_id,stock_code,issue_reference_price_minor,issued_shares,listed_at) VALUES (?,?,?,?,?)")) { s.setString(1, company.value().toString()); s.setString(2, "BS" + String.format("%06d", Math.floorMod(company.value().hashCode(), 999999) + 1)); s.setLong(3, 10); s.setLong(4, 1000); s.setString(5, Instant.EPOCH.toString()); s.executeUpdate(); } return null; }); return company; }
    private static CompanyId unlistedPlayerCompany(Database db) { CompanyId company = new CompanyId(UUID.randomUUID()); UUID founder = UUID.randomUUID(); db.inTransaction(c -> { try (var s = c.prepareStatement("INSERT INTO companies (id,normalized_name,display_name,founder_uuid,status,treasury_minor,total_shares,dividend_basis_points,created_at) VALUES (?,?,?,?,?,?,?,?,?)")) { s.setString(1, company.value().toString()); s.setString(2, "unlisted-" + company.value()); s.setString(3, "Unlisted"); s.setString(4, founder.toString()); s.setString(5, "PENDING_ASSET_BINDING"); s.setLong(6, 100); s.setLong(7, 1000); s.setInt(8, 5000); s.setString(9, Instant.EPOCH.toString()); s.executeUpdate(); } try (var s = c.prepareStatement("INSERT INTO company_cash_accounts (company_id,cash_minor,paid_in_capital_minor,retained_earnings_minor,reserved_minor) VALUES (?,?,?,?,?)")) { s.setString(1, company.value().toString()); s.setLong(2, 100); s.setLong(3, 100); s.setLong(4, 0); s.setLong(5, 0); s.executeUpdate(); } return null; }); return company; }
    private static void seedHolding(Database db, CompanyId company, UUID holder, long available, long reserved) { db.inTransaction(c -> { try (var s = c.prepareStatement("INSERT INTO share_holdings (company_id,holder_uuid,available_shares,reserved_shares) VALUES (?,?,?,?)")) { s.setString(1, company.value().toString()); s.setString(2, holder.toString()); s.setLong(3, available); s.setLong(4, reserved); s.executeUpdate(); } return null; }); }
    private static void changeHolding(java.sql.Connection c, CompanyId company, UUID holder, long available, long reserved) throws java.sql.SQLException { try (var s = c.prepareStatement("UPDATE share_holdings SET available_shares=?,reserved_shares=? WHERE company_id=? AND holder_uuid=?")) { s.setLong(1, available); s.setLong(2, reserved); s.setString(3, company.value().toString()); s.setString(4, holder.toString()); s.executeUpdate(); } }
    private static void makeBluechip(Database db, CompanyId company) { db.inTransaction(c -> { try (var s = c.prepareStatement("INSERT INTO bluechip_companies (company_id,industry,system_account_uuid,lower_price_minor,upper_price_minor,model_price_minor,spread_bps,event_sensitivity_bps,payout_bps,next_event_at,next_dividend_at) VALUES (?, 'Test', ?, 1, 3, 2, 0, 0, 0, ?, ?)")) { s.setString(1, company.value().toString()); s.setString(2, UUID.randomUUID().toString()); s.setString(3, Instant.EPOCH.toString()); s.setString(4, Instant.EPOCH.toString()); s.executeUpdate(); } return null; }); }
    private static Void execute(java.sql.Connection connection, String sql, String... parameters) throws java.sql.SQLException { try (var statement = connection.prepareStatement(sql)) { for (int i = 0; i < parameters.length; i++) statement.setString(i + 1, parameters[i]); statement.executeUpdate(); return null; } }
    private static Void insertSubscription(java.sql.Connection connection, UUID id, UUID proposalId, UUID subscriber, String correlationKey) throws java.sql.SQLException { try (var statement = connection.prepareStatement("INSERT INTO issuance_subscriptions (id,proposal_id,subscriber_uuid,shares,reserved_cash_minor,correlation_key,created_at) VALUES (?,?,?,?,?,?,?)")) { statement.setString(1, id.toString()); statement.setString(2, proposalId.toString()); statement.setString(3, subscriber.toString()); statement.setLong(4, 1); statement.setLong(5, 10); statement.setString(6, correlationKey); statement.setString(7, Instant.EPOCH.toString()); statement.executeUpdate(); return null; } }
    private static long count(Database db, String sql, String... parameters) { try (var c = db.dataSource().getConnection(); var s = c.prepareStatement(sql)) { for (int i = 0; i < parameters.length; i++) s.setString(i + 1, parameters[i]); try (var r = s.executeQuery()) { r.next(); return r.getLong(1); } } catch (Exception e) { throw new RuntimeException(e); } }
}
