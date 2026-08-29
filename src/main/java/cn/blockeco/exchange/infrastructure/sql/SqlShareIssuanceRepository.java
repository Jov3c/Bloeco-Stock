package cn.blockeco.exchange.infrastructure.sql;

import cn.blockeco.exchange.domain.governance.IssuanceProposal;
import cn.blockeco.exchange.domain.governance.VoteChoice;
import cn.blockeco.exchange.ports.ShareIssuanceRepository;
import cn.blockeco.exchange.ports.SecuritiesCashRepository;
import cn.blockeco.exchange.domain.money.Money;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/** Persists issuance governance facts; callers own all write transaction boundaries. */
public final class SqlShareIssuanceRepository implements ShareIssuanceRepository {
    private final DataSource dataSource;
    public SqlShareIssuanceRepository(DataSource dataSource) { this.dataSource = Objects.requireNonNull(dataSource, "dataSource"); }

    @Override public void createProposal(Connection connection, IssuanceProposal proposal, Instant recordedAt) throws SQLException {
        requireTransaction(connection); Objects.requireNonNull(proposal, "proposal"); Objects.requireNonNull(recordedAt, "recordedAt"); requireEligibleCompany(connection, proposal);
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO issuance_proposals (id,company_id,proposer_uuid,new_shares,issue_price_minor,announced_at,state) VALUES (?,?,?,?,?,?,?)")) {
            insert.setString(1, proposal.id().toString()); insert.setString(2, proposal.companyId().value().toString()); insert.setString(3, proposal.proposerUuid().toString()); insert.setLong(4, proposal.newShares()); insert.setLong(5, proposal.issuePriceMinor()); insert.setString(6, proposal.announcedAt().toString()); insert.setString(7, proposal.state().name()); insert.executeUpdate();
        }
        try (PreparedStatement snapshot = connection.prepareStatement("INSERT INTO issuance_record_snapshots (proposal_id,voter_uuid,shares) SELECT ?,holder_uuid,available_shares+reserved_shares FROM share_holdings WHERE company_id=? AND available_shares+reserved_shares>0")) {
            snapshot.setString(1, proposal.id().toString()); snapshot.setString(2, proposal.companyId().value().toString()); snapshot.executeUpdate();
        }
        String announcementId = proposal.id() + ":ISSUANCE_PROPOSED";
        try (PreparedStatement announcement = connection.prepareStatement("INSERT INTO company_announcements (id,company_id,offering_id,body,created_at) VALUES (?,?,NULL,?,?)")) {
            announcement.setString(1, announcementId); announcement.setString(2, proposal.companyId().value().toString()); announcement.setString(3, "ISSUANCE_PROPOSED: proposalId=" + proposal.id() + ", newShares=" + proposal.newShares() + ", issuePriceMinor=" + proposal.issuePriceMinor()); announcement.setString(4, recordedAt.toString()); announcement.executeUpdate();
        }
        try (PreparedStatement audit = connection.prepareStatement("INSERT INTO audit_events (event_id,company_id,actor_uuid,event_type,payload_json,occurred_at) VALUES (?,?,?,?,?,?)")) {
            audit.setString(1, announcementId); audit.setString(2, proposal.companyId().value().toString()); audit.setString(3, proposal.proposerUuid().toString()); audit.setString(4, "ISSUANCE_PROPOSED"); audit.setString(5, "{\"proposalId\":\"" + proposal.id() + "\",\"companyId\":\"" + proposal.companyId().value() + "\",\"newShares\":" + proposal.newShares() + ",\"issuePriceMinor\":" + proposal.issuePriceMinor() + "}"); audit.setString(6, recordedAt.toString()); audit.executeUpdate();
        }
    }

    @Override public long castVote(Connection connection, UUID proposalId, UUID voter, VoteChoice choice, Instant votedAt) throws SQLException {
        requireTransaction(connection); Objects.requireNonNull(proposalId, "proposalId"); Objects.requireNonNull(voter, "voter"); Objects.requireNonNull(choice, "choice"); Objects.requireNonNull(votedAt, "votedAt");
        long weight = snapshotWeight(connection, proposalId, voter);
        if (weight == 0) throw new IllegalArgumentException("voter has no record-date shares");
        try (PreparedStatement vote = connection.prepareStatement("INSERT INTO issuance_votes (proposal_id,voter_uuid,choice,snapshot_shares,voted_at) VALUES (?,?,?,?,?) ON CONFLICT(proposal_id,voter_uuid) DO UPDATE SET choice=excluded.choice,voted_at=excluded.voted_at")) {
            vote.setString(1, proposalId.toString()); vote.setString(2, voter.toString()); vote.setString(3, choice.name()); vote.setLong(4, weight); vote.setString(5, votedAt.toString()); vote.executeUpdate();
        }
        return weight;
    }

    @Override public Subscription subscribe(Connection connection, UUID holder, UUID proposalId, long shares, String correlationKey, Instant subscribedAt) throws SQLException {
        requireTransaction(connection); Objects.requireNonNull(holder, "holder"); Objects.requireNonNull(proposalId, "proposalId"); Objects.requireNonNull(correlationKey, "correlationKey"); Objects.requireNonNull(subscribedAt, "subscribedAt");
        if (shares <= 0) throw new IllegalArgumentException("shares must be positive");
        Subscription existing = subscription(connection, proposalId, correlationKey);
        if (existing != null) return existing;
        long price = issuePrice(connection, proposalId); long reserved = Math.multiplyExact(shares, price); UUID id = UUID.randomUUID();
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO issuance_subscriptions (id,proposal_id,subscriber_uuid,shares,reserved_cash_minor,correlation_key,created_at) VALUES (?,?,?,?,?,?,?)")) {
            statement.setString(1, id.toString()); statement.setString(2, proposalId.toString()); statement.setString(3, holder.toString()); statement.setLong(4, shares); statement.setLong(5, reserved); statement.setString(6, correlationKey); statement.setString(7, subscribedAt.toString()); statement.executeUpdate();
        }
        return new Subscription(id, proposalId, holder, shares, reserved);
    }

    @Override public void settleSubscription(Connection connection, UUID proposalId, SecuritiesCashRepository cash, Instant settledAt) throws SQLException {
        requireTransaction(connection); Objects.requireNonNull(proposalId, "proposalId"); Objects.requireNonNull(cash, "cash"); Objects.requireNonNull(settledAt, "settledAt");
        Proposal proposal = proposal(connection, proposalId);
        if ("CLOSED".equals(proposal.state)) return;
        if (!"SUBSCRIBING".equals(proposal.state)) throw new IllegalStateException("proposal is not open for subscriptions");
        long remaining = proposal.newShares;
        long raised = 0;
        for (Subscription subscription : subscriptions(connection, proposalId)) {
            long filled = Math.min(remaining, subscription.shares());
            long spent = Math.multiplyExact(filled, proposal.issuePriceMinor);
            long released = Math.subtractExact(subscription.reservedCashMinor(), spent);
            if (released > 0) cash.release(connection, subscription.holder(), Money.ofMinor(released));
            settleSpentCash(connection, subscription.holder(), spent);
            ledger(connection, "SECURITIES_CASH", null, subscription.holder(), -spent, settledAt);
            if (filled > 0) creditHolding(connection, proposal.companyId, subscription.holder(), filled);
            insertSettlement(connection, subscription.id(), filled, settledAt);
            remaining = Math.subtractExact(remaining, filled); raised = Math.addExact(raised, spent);
        }
        long issued = Math.subtractExact(proposal.newShares, remaining);
        updateCompany(connection, proposal.companyId, issued, raised, settledAt);
        try (PreparedStatement close = connection.prepareStatement("UPDATE issuance_proposals SET state='CLOSED' WHERE id=? AND state='SUBSCRIBING'")) { close.setString(1, proposalId.toString()); if (close.executeUpdate() != 1) throw new IllegalStateException("proposal close state conflict"); }
        appendCloseFacts(connection, proposalId, proposal.companyId, issued, raised, settledAt);
    }

    @Override public VoteTally tally(UUID proposalId) { Objects.requireNonNull(proposalId, "proposalId"); try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement("SELECT COALESCE((SELECT SUM(shares) FROM issuance_record_snapshots WHERE proposal_id=?),0),COALESCE(SUM(snapshot_shares),0),COALESCE(SUM(CASE WHEN choice='YES' THEN snapshot_shares ELSE 0 END),0),COALESCE(SUM(CASE WHEN choice='NO' THEN snapshot_shares ELSE 0 END),0),COALESCE(SUM(CASE WHEN choice='ABSTAIN' THEN snapshot_shares ELSE 0 END),0) FROM issuance_votes WHERE proposal_id=?")) { statement.setString(1, proposalId.toString()); statement.setString(2, proposalId.toString()); try (ResultSet rows = statement.executeQuery()) { rows.next(); return new VoteTally(rows.getLong(1), rows.getLong(2), rows.getLong(3), rows.getLong(4), rows.getLong(5)); } } catch (SQLException exception) { throw new IllegalStateException("could not tally issuance votes", exception); } }
    @Override public long snapshotWeight(UUID proposalId, UUID voter) { try (Connection connection = dataSource.getConnection()) { return snapshotWeight(connection, proposalId, voter); } catch (SQLException exception) { throw new IllegalStateException("could not read issuance snapshot", exception); } }

    private static long snapshotWeight(Connection connection, UUID proposalId, UUID voter) throws SQLException { try (PreparedStatement statement = connection.prepareStatement("SELECT shares FROM issuance_record_snapshots WHERE proposal_id=? AND voter_uuid=?")) { statement.setString(1, proposalId.toString()); statement.setString(2, voter.toString()); try (ResultSet rows = statement.executeQuery()) { return rows.next() ? rows.getLong(1) : 0; } } }
    private static Subscription subscription(Connection connection, UUID proposalId, String correlationKey) throws SQLException { try (PreparedStatement statement = connection.prepareStatement("SELECT id,subscriber_uuid,shares,reserved_cash_minor FROM issuance_subscriptions WHERE proposal_id=? AND correlation_key=?")) { statement.setString(1, proposalId.toString()); statement.setString(2, correlationKey); try (ResultSet rows = statement.executeQuery()) { return rows.next() ? new Subscription(UUID.fromString(rows.getString(1)), proposalId, UUID.fromString(rows.getString(2)), rows.getLong(3), rows.getLong(4)) : null; } } }
    private static long issuePrice(Connection connection, UUID proposalId) throws SQLException { try (PreparedStatement statement = connection.prepareStatement("SELECT issue_price_minor FROM issuance_proposals WHERE id=?")) { statement.setString(1, proposalId.toString()); try (ResultSet rows = statement.executeQuery()) { if (!rows.next()) throw new IllegalArgumentException("proposal is missing"); return rows.getLong(1); } } }
    private static Proposal proposal(Connection connection, UUID proposalId) throws SQLException { try (PreparedStatement statement = connection.prepareStatement("SELECT company_id,new_shares,issue_price_minor,state FROM issuance_proposals WHERE id=?")) { statement.setString(1, proposalId.toString()); try (ResultSet rows = statement.executeQuery()) { if (!rows.next()) throw new IllegalArgumentException("proposal is missing"); return new Proposal(rows.getString(1), rows.getLong(2), rows.getLong(3), rows.getString(4)); } } }
    private static java.util.List<Subscription> subscriptions(Connection connection, UUID proposalId) throws SQLException { try (PreparedStatement statement = connection.prepareStatement("SELECT id,subscriber_uuid,shares,reserved_cash_minor FROM issuance_subscriptions WHERE proposal_id=? ORDER BY created_at,rowid")) { statement.setString(1, proposalId.toString()); try (ResultSet rows = statement.executeQuery()) { java.util.List<Subscription> result = new java.util.ArrayList<>(); while (rows.next()) result.add(new Subscription(UUID.fromString(rows.getString(1)), proposalId, UUID.fromString(rows.getString(2)), rows.getLong(3), rows.getLong(4))); return result; } } }
    private static void settleSpentCash(Connection c, UUID holder, long spent) throws SQLException { if (spent == 0) return; try (PreparedStatement statement = c.prepareStatement("UPDATE securities_cash_accounts SET reserved_minor=reserved_minor-? WHERE player_uuid=? AND reserved_minor>=?")) { statement.setLong(1, spent); statement.setString(2, holder.toString()); statement.setLong(3, spent); if (statement.executeUpdate() != 1) throw new IllegalStateException("subscription cash reserve missing"); } }
    private static void creditHolding(Connection c, String company, UUID holder, long shares) throws SQLException { try (PreparedStatement statement = c.prepareStatement("INSERT INTO share_holdings (company_id,holder_uuid,available_shares,reserved_shares) VALUES (?,?,?,0) ON CONFLICT(company_id,holder_uuid) DO UPDATE SET available_shares=available_shares+excluded.available_shares")) { statement.setString(1, company); statement.setString(2, holder.toString()); statement.setLong(3, shares); statement.executeUpdate(); } }
    private static void insertSettlement(Connection c, UUID subscription, long shares, Instant at) throws SQLException { try (PreparedStatement statement = c.prepareStatement("INSERT INTO issuance_subscription_settlements (subscription_id,settled_shares,settled_at) VALUES (?,?,?)")) { statement.setString(1, subscription.toString()); statement.setLong(2, shares); statement.setString(3, at.toString()); statement.executeUpdate(); } }
    private static void updateCompany(Connection c, String company, long issued, long raised, Instant at) throws SQLException { long oldTotal = scalar(c, "SELECT total_shares FROM companies WHERE id=?", company); long oldCash = scalar(c, "SELECT cash_minor FROM company_cash_accounts WHERE company_id=?", company); long total = Math.addExact(oldTotal, issued), cash = Math.addExact(oldCash, raised); try (PreparedStatement companies = c.prepareStatement("UPDATE companies SET total_shares=? WHERE id=?"); PreparedStatement account = c.prepareStatement("UPDATE company_cash_accounts SET cash_minor=?,paid_in_capital_minor=paid_in_capital_minor+? WHERE company_id=?"); PreparedStatement listing = c.prepareStatement("UPDATE stock_listings SET issued_shares=? WHERE company_id=?")) { companies.setLong(1, total); companies.setString(2, company); companies.executeUpdate(); account.setLong(1, cash); account.setLong(2, raised); account.setString(3, company); if (account.executeUpdate()!=1) throw new IllegalStateException("company cash account missing"); listing.setLong(1, total); listing.setString(2, company); if (listing.executeUpdate()!=1) throw new IllegalStateException("stock listing missing"); } ledger(c, "COMPANY_TREASURY", company, null, raised, at); }
    private static long scalar(Connection c, String sql, String id) throws SQLException { try (PreparedStatement statement = c.prepareStatement(sql)) { statement.setString(1, id); try (ResultSet rows = statement.executeQuery()) { if (!rows.next()) throw new IllegalStateException("settlement projection missing"); return rows.getLong(1); } } }
    private static void ledger(Connection c, String kind, String company, UUID holder, long amount, Instant at) throws SQLException { if (amount == 0) return; try (PreparedStatement statement = c.prepareStatement("INSERT INTO escrow_ledger_entries (id,liability_kind,company_id,player_uuid,amount_minor,operation_id,trade_id,occurred_at) VALUES (?,?,?,?,?,?,?,?)")) { statement.setString(1, UUID.randomUUID().toString()); statement.setString(2, kind); statement.setString(3, company); statement.setString(4, holder == null ? null : holder.toString()); statement.setLong(5, amount); statement.setString(6, null); statement.setString(7, null); statement.setString(8, at.toString()); statement.executeUpdate(); } }
    private static void appendCloseFacts(Connection c, UUID proposal, String company, long issued, long raised, Instant at) throws SQLException { String id = proposal + ":ISSUANCE_CLOSED"; try (PreparedStatement announcement = c.prepareStatement("INSERT INTO company_announcements (id,company_id,offering_id,body,created_at) VALUES (?,?,NULL,?,?)"); PreparedStatement audit = c.prepareStatement("INSERT INTO audit_events (event_id,company_id,actor_uuid,event_type,payload_json,occurred_at) VALUES (?,?,NULL,?,?,?)")) { announcement.setString(1, id); announcement.setString(2, company); announcement.setString(3, "ISSUANCE_CLOSED: proposalId=" + proposal + ", issuedShares=" + issued + ", raisedMinor=" + raised); announcement.setString(4, at.toString()); announcement.executeUpdate(); audit.setString(1, id); audit.setString(2, company); audit.setString(3, "ISSUANCE_CLOSED"); audit.setString(4, "{\"proposalId\":\"" + proposal + "\",\"issuedShares\":" + issued + ",\"raisedMinor\":" + raised + "}"); audit.setString(5, at.toString()); audit.executeUpdate(); } }
    private record Proposal(String companyId, long newShares, long issuePriceMinor, String state) { }
    private static void requireEligibleCompany(Connection connection, IssuanceProposal proposal) throws SQLException { try (PreparedStatement statement = connection.prepareStatement("SELECT c.founder_uuid,c.status,EXISTS(SELECT 1 FROM stock_listings sl WHERE sl.company_id=c.id),EXISTS(SELECT 1 FROM bluechip_companies bc WHERE bc.company_id=c.id) FROM companies c WHERE c.id=?")) { statement.setString(1, proposal.companyId().value().toString()); try (ResultSet rows = statement.executeQuery()) { if (!rows.next()) throw new IllegalArgumentException("company is missing"); if (rows.getInt(4) != 0) throw new IllegalArgumentException("bluechip companies cannot issue shares"); if (!"LISTED".equals(rows.getString(2)) || rows.getInt(3) == 0 || !proposal.proposerUuid().toString().equals(rows.getString(1))) throw new IllegalArgumentException("only listed company founders can issue shares"); } } }
    private static void requireTransaction(Connection connection) throws SQLException { if (connection == null || connection.getAutoCommit()) throw new IllegalStateException("caller-owned transaction connection required"); }
}
