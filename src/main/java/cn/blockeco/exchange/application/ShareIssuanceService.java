package cn.blockeco.exchange.application;

import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.governance.IssuanceProposal;
import cn.blockeco.exchange.domain.governance.IssuanceProposalState;
import cn.blockeco.exchange.domain.governance.VoteChoice;
import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.ports.AppClock;
import cn.blockeco.exchange.ports.ShareIssuanceRepository;
import cn.blockeco.exchange.ports.SecuritiesCashRepository;
import cn.blockeco.exchange.ports.TransactionRunner;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Runs the announcement and shareholder-voting phases of a share issuance. */
public final class ShareIssuanceService {
    private static final Duration ANNOUNCEMENT_PERIOD = Duration.ofHours(12);
    private static final Duration VOTING_PERIOD = Duration.ofDays(2);

    private final ShareIssuanceRepository repository;
    private final SecuritiesCashRepository cash;
    private final TransactionRunner transactions;
    private final AppClock clock;

    public ShareIssuanceService(ShareIssuanceRepository repository, TransactionRunner transactions, AppClock clock) {
        this(repository, null, transactions, clock);
    }

    public ShareIssuanceService(ShareIssuanceRepository repository, SecuritiesCashRepository cash, TransactionRunner transactions, AppClock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.cash = cash;
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ShareIssuanceRepository.Subscription subscribe(UUID holder, UUID proposalId, long shares, String correlationKey) {
        Objects.requireNonNull(holder, "holder"); Objects.requireNonNull(proposalId, "proposalId"); Objects.requireNonNull(correlationKey, "correlationKey");
        if (shares <= 0) throw new IllegalArgumentException("shares must be positive");
        if (cash == null) throw new IllegalStateException("securities cash repository is required for subscriptions");
        return transactions.inTransaction(connection -> {
            Proposal proposal = proposal(connection, proposalId);
            if (proposal.state != IssuanceProposalState.SUBSCRIBING) throw new IllegalStateException("proposal is not open for subscriptions");
            long reserved = Math.multiplyExact(shares, proposal.issuePriceMinor);
            ShareIssuanceRepository.Subscription existing = subscription(connection, proposalId, correlationKey);
            if (existing != null) return existing;
            cash.reserve(connection, holder, Money.ofMinor(reserved));
            try { return repository.subscribe(connection, holder, proposalId, shares, correlationKey, clock.now()); }
            catch (RuntimeException | SQLException failure) { cash.release(connection, holder, Money.ofMinor(reserved)); throw failure; }
        });
    }

    public void closeSubscription(UUID proposalId) {
        Objects.requireNonNull(proposalId, "proposalId");
        if (cash == null) throw new IllegalStateException("securities cash repository is required for subscriptions");
        transactions.inTransaction(connection -> { repository.settleSubscription(connection, proposalId, cash, clock.now()); return null; });
    }

    public IssuanceProposal propose(UUID founder, CompanyId company, long shares, Money price) {
        Objects.requireNonNull(founder, "founder");
        Objects.requireNonNull(company, "company");
        Objects.requireNonNull(price, "price");
        Instant announcedAt = clock.now();
        IssuanceProposal proposal = new IssuanceProposal(UUID.randomUUID(), company, founder, shares, price.minorUnits(), announcedAt);
        transactions.inTransaction(connection -> { repository.createProposal(connection, proposal, announcedAt); return null; });
        return proposal;
    }

    public long vote(UUID voter, UUID proposalId, VoteChoice choice) {
        Objects.requireNonNull(voter, "voter");
        Objects.requireNonNull(proposalId, "proposalId");
        Objects.requireNonNull(choice, "choice");
        Instant now = clock.now();
        return transactions.inTransaction(connection -> {
            Proposal proposal = proposal(connection, proposalId);
            Instant votingOpens = proposal.announcedAt.plus(ANNOUNCEMENT_PERIOD);
            Instant votingCloses = votingOpens.plus(VOTING_PERIOD);
            if (proposal.state != IssuanceProposalState.VOTING || now.isBefore(votingOpens) || !now.isBefore(votingCloses)) {
                throw new IllegalStateException("proposal is not open for voting");
            }
            return repository.castVote(connection, proposalId, voter, choice, now);
        });
    }

    public List<UUID> advanceDueProposals() {
        Instant now = clock.now();
        return transactions.inTransaction(connection -> advanceDueProposals(connection, now));
    }

    private List<UUID> advanceDueProposals(Connection connection, Instant now) throws SQLException {
        List<UUID> changed = new ArrayList<>();
        for (Proposal proposal : proposalsInState(connection, IssuanceProposalState.ANNOUNCED)) {
            if (!now.isBefore(proposal.announcedAt.plus(ANNOUNCEMENT_PERIOD))
                    && transition(connection, proposal.id, IssuanceProposalState.ANNOUNCED, IssuanceProposalState.VOTING)) {
                changed.add(proposal.id);
            }
        }
        for (Proposal proposal : proposalsInState(connection, IssuanceProposalState.VOTING)) {
            if (!now.isBefore(proposal.announcedAt.plus(ANNOUNCEMENT_PERIOD).plus(VOTING_PERIOD))) {
                ShareIssuanceRepository.VoteTally tally = tally(connection, proposal.id);
                IssuanceProposalState result = passes(tally) ? IssuanceProposalState.APPROVED : IssuanceProposalState.REJECTED;
                if (transition(connection, proposal.id, IssuanceProposalState.VOTING, result)) changed.add(proposal.id);
            }
        }
        return List.copyOf(changed);
    }

    private static boolean passes(ShareIssuanceRepository.VoteTally tally) {
        if (tally.effectiveShares() <= 0 || tally.snapshotShares() <= 0) return false;
        return BigInteger.valueOf(tally.yesShares()).multiply(BigInteger.TWO)
                        .compareTo(BigInteger.valueOf(tally.effectiveShares())) >= 0
                && BigInteger.valueOf(tally.effectiveShares()).multiply(BigInteger.TEN)
                        .compareTo(BigInteger.valueOf(tally.snapshotShares()).multiply(BigInteger.valueOf(3))) >= 0;
    }

    private static List<Proposal> proposalsInState(Connection connection, IssuanceProposalState state) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT id, announced_at FROM issuance_proposals WHERE state=?")) {
            statement.setString(1, state.name());
            try (ResultSet rows = statement.executeQuery()) {
                List<Proposal> proposals = new ArrayList<>();
                while (rows.next()) proposals.add(new Proposal(UUID.fromString(rows.getString(1)), Instant.parse(rows.getString(2))));
                return proposals;
            }
        }
    }

    private static Proposal proposal(Connection connection, UUID proposalId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT state, announced_at, issue_price_minor FROM issuance_proposals WHERE id=?")) {
            statement.setString(1, proposalId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalArgumentException("proposal is missing");
                return new Proposal(proposalId, Instant.parse(rows.getString(2)), IssuanceProposalState.valueOf(rows.getString(1)), rows.getLong(3));
            }
        }
    }

    private static ShareIssuanceRepository.Subscription subscription(Connection connection, UUID proposalId, String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT id,subscriber_uuid,shares,reserved_cash_minor FROM issuance_subscriptions WHERE proposal_id=? AND correlation_key=?")) {
            statement.setString(1, proposalId.toString()); statement.setString(2, key);
            try (ResultSet rows = statement.executeQuery()) { return rows.next() ? new ShareIssuanceRepository.Subscription(UUID.fromString(rows.getString(1)), proposalId, UUID.fromString(rows.getString(2)), rows.getLong(3), rows.getLong(4)) : null; }
        }
    }

    private static boolean transition(Connection connection, UUID proposalId, IssuanceProposalState expected, IssuanceProposalState target) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE issuance_proposals SET state=? WHERE id=? AND state=?")) {
            statement.setString(1, target.name()); statement.setString(2, proposalId.toString()); statement.setString(3, expected.name());
            return statement.executeUpdate() == 1;
        }
    }

    private static ShareIssuanceRepository.VoteTally tally(Connection connection, UUID proposalId) throws SQLException {
        String sql = "SELECT COALESCE((SELECT SUM(shares) FROM issuance_record_snapshots WHERE proposal_id=?),0),"
                + "COALESCE(SUM(snapshot_shares),0),"
                + "COALESCE(SUM(CASE WHEN choice='YES' THEN snapshot_shares ELSE 0 END),0),"
                + "COALESCE(SUM(CASE WHEN choice='NO' THEN snapshot_shares ELSE 0 END),0),"
                + "COALESCE(SUM(CASE WHEN choice='ABSTAIN' THEN snapshot_shares ELSE 0 END),0) FROM issuance_votes WHERE proposal_id=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, proposalId.toString()); statement.setString(2, proposalId.toString());
            try (ResultSet rows = statement.executeQuery()) { rows.next(); return new ShareIssuanceRepository.VoteTally(rows.getLong(1), rows.getLong(2), rows.getLong(3), rows.getLong(4), rows.getLong(5)); }
        }
    }

    private record Proposal(UUID id, Instant announcedAt, IssuanceProposalState state, long issuePriceMinor) {
        private Proposal(UUID id, Instant announcedAt) { this(id, announcedAt, null, 0); }
    }
}
