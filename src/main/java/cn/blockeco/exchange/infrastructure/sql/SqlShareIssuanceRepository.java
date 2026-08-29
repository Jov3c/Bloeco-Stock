package cn.blockeco.exchange.infrastructure.sql;

import cn.blockeco.exchange.domain.governance.IssuanceProposal;
import cn.blockeco.exchange.domain.governance.VoteChoice;
import cn.blockeco.exchange.ports.ShareIssuanceRepository;
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

    @Override public VoteTally tally(UUID proposalId) { Objects.requireNonNull(proposalId, "proposalId"); try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement("SELECT COALESCE((SELECT SUM(shares) FROM issuance_record_snapshots WHERE proposal_id=?),0),COALESCE(SUM(snapshot_shares),0),COALESCE(SUM(CASE WHEN choice='YES' THEN snapshot_shares ELSE 0 END),0),COALESCE(SUM(CASE WHEN choice='NO' THEN snapshot_shares ELSE 0 END),0),COALESCE(SUM(CASE WHEN choice='ABSTAIN' THEN snapshot_shares ELSE 0 END),0) FROM issuance_votes WHERE proposal_id=?")) { statement.setString(1, proposalId.toString()); statement.setString(2, proposalId.toString()); try (ResultSet rows = statement.executeQuery()) { rows.next(); return new VoteTally(rows.getLong(1), rows.getLong(2), rows.getLong(3), rows.getLong(4), rows.getLong(5)); } } catch (SQLException exception) { throw new IllegalStateException("could not tally issuance votes", exception); } }
    @Override public long snapshotWeight(UUID proposalId, UUID voter) { try (Connection connection = dataSource.getConnection()) { return snapshotWeight(connection, proposalId, voter); } catch (SQLException exception) { throw new IllegalStateException("could not read issuance snapshot", exception); } }

    private static long snapshotWeight(Connection connection, UUID proposalId, UUID voter) throws SQLException { try (PreparedStatement statement = connection.prepareStatement("SELECT shares FROM issuance_record_snapshots WHERE proposal_id=? AND voter_uuid=?")) { statement.setString(1, proposalId.toString()); statement.setString(2, voter.toString()); try (ResultSet rows = statement.executeQuery()) { return rows.next() ? rows.getLong(1) : 0; } } }
    private static void requireEligibleCompany(Connection connection, IssuanceProposal proposal) throws SQLException { try (PreparedStatement statement = connection.prepareStatement("SELECT c.founder_uuid,c.status,EXISTS(SELECT 1 FROM stock_listings sl WHERE sl.company_id=c.id),EXISTS(SELECT 1 FROM bluechip_companies bc WHERE bc.company_id=c.id) FROM companies c WHERE c.id=?")) { statement.setString(1, proposal.companyId().value().toString()); try (ResultSet rows = statement.executeQuery()) { if (!rows.next()) throw new IllegalArgumentException("company is missing"); if (rows.getInt(4) != 0) throw new IllegalArgumentException("bluechip companies cannot issue shares"); if (!"LISTED".equals(rows.getString(2)) || rows.getInt(3) == 0 || !proposal.proposerUuid().toString().equals(rows.getString(1))) throw new IllegalArgumentException("only listed company founders can issue shares"); } } }
    private static void requireTransaction(Connection connection) throws SQLException { if (connection == null || connection.getAutoCommit()) throw new IllegalStateException("caller-owned transaction connection required"); }
}
