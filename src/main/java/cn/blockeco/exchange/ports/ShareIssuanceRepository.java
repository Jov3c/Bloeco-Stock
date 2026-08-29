package cn.blockeco.exchange.ports;

import cn.blockeco.exchange.domain.governance.IssuanceProposal;
import cn.blockeco.exchange.domain.governance.VoteChoice;
import cn.blockeco.exchange.ports.SecuritiesCashRepository;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

public interface ShareIssuanceRepository {
    void createProposal(Connection connection, IssuanceProposal proposal, Instant recordedAt) throws SQLException;
    long castVote(Connection connection, UUID proposalId, UUID voter, VoteChoice choice, Instant votedAt) throws SQLException;
    VoteTally tally(UUID proposalId);
    long snapshotWeight(UUID proposalId, UUID voter);
    Subscription subscribe(Connection connection, UUID holder, UUID proposalId, long shares, String correlationKey, Instant subscribedAt) throws SQLException;
    void settleSubscription(Connection connection, UUID proposalId, SecuritiesCashRepository cash, Instant settledAt) throws SQLException;
    record Subscription(UUID id, UUID proposalId, UUID holder, long shares, long reservedCashMinor) { }
    record VoteTally(long snapshotShares, long effectiveShares, long yesShares, long noShares, long abstainShares) { }
}
