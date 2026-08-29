package cn.blockeco.exchange.ports;

import cn.blockeco.exchange.domain.governance.IssuanceProposal;
import cn.blockeco.exchange.domain.governance.VoteChoice;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

public interface ShareIssuanceRepository {
    void createProposal(Connection connection, IssuanceProposal proposal, Instant recordedAt) throws SQLException;
    long castVote(Connection connection, UUID proposalId, UUID voter, VoteChoice choice, Instant votedAt) throws SQLException;
    VoteTally tally(UUID proposalId);
    long snapshotWeight(UUID proposalId, UUID voter);
    record VoteTally(long snapshotShares, long effectiveShares, long yesShares, long noShares, long abstainShares) { }
}
