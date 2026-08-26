package cn.blockeco.exchange.infrastructure.sql;

import cn.blockeco.exchange.domain.money.Money;
import cn.blockeco.exchange.ports.BluechipParticipantRepository;
import cn.blockeco.exchange.ports.BluechipRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Durable, conservation-preserving initial transfer from a bluechip maker to its participant. */
public final class SqlBluechipParticipantRepository implements BluechipParticipantRepository {
    @Override public boolean allocateOnce(Connection connection, UUID makerAccountId, UUID participantAccountId, Money cash,
                                          long sharesPerCompany, List<BluechipRepository.BluechipCompany> bluechips) throws SQLException {
        requireTransaction(connection); Objects.requireNonNull(makerAccountId); Objects.requireNonNull(participantAccountId);
        Objects.requireNonNull(cash); Objects.requireNonNull(bluechips);
        if (makerAccountId.equals(participantAccountId)) throw new IllegalArgumentException("participant must differ from bluechip maker");
        if (cash.minorUnits() <= 0 || sharesPerCompany <= 0 || bluechips.isEmpty()) throw new IllegalArgumentException("participant allocation must be positive");
        try (PreparedStatement marker = connection.prepareStatement(
                "INSERT INTO bluechip_system_participant_allocations (participant_uuid, maker_uuid, cash_minor, shares_per_company) VALUES (?, ?, ?, ?) ON CONFLICT(participant_uuid) DO NOTHING")) {
            marker.setString(1, participantAccountId.toString()); marker.setString(2, makerAccountId.toString());
            marker.setLong(3, cash.minorUnits()); marker.setLong(4, sharesPerCompany);
            if (marker.executeUpdate() == 0) return false;
        }
        debitCash(connection, makerAccountId, cash.minorUnits()); creditCash(connection, participantAccountId, cash.minorUnits());
        for (BluechipRepository.BluechipCompany bluechip : bluechips) {
            if (!makerAccountId.equals(bluechip.systemAccountId())) throw new IllegalArgumentException("bluechip maker does not match participant allocation maker");
            debitShares(connection, bluechip.companyId().value().toString(), makerAccountId, sharesPerCompany);
            creditShares(connection, bluechip.companyId().value().toString(), participantAccountId, sharesPerCompany);
        }
        return true;
    }

    private static void debitCash(Connection connection, UUID account, long amount) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE securities_cash_accounts SET available_minor = available_minor - ? WHERE player_uuid = ? AND available_minor >= ?")) {
            statement.setLong(1, amount); statement.setString(2, account.toString()); statement.setLong(3, amount);
            if (statement.executeUpdate() != 1) throw new IllegalStateException("bluechip maker cash is insufficient for participant allocation");
        }
    }
    private static void creditCash(Connection connection, UUID account, long amount) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO securities_cash_accounts (player_uuid, available_minor, reserved_minor) VALUES (?, ?, 0) ON CONFLICT(player_uuid) DO UPDATE SET available_minor = available_minor + excluded.available_minor WHERE securities_cash_accounts.available_minor <= ?")) {
            statement.setString(1, account.toString()); statement.setLong(2, amount); statement.setLong(3, Math.subtractExact(Long.MAX_VALUE, amount));
            if (statement.executeUpdate() != 1) throw new ArithmeticException("participant cash allocation overflows");
        }
    }
    private static void debitShares(Connection connection, String company, UUID account, long shares) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE share_holdings SET available_shares = available_shares - ? WHERE company_id = ? AND holder_uuid = ? AND available_shares >= ?")) {
            statement.setLong(1, shares); statement.setString(2, company); statement.setString(3, account.toString()); statement.setLong(4, shares);
            if (statement.executeUpdate() != 1) throw new IllegalStateException("bluechip maker shares are insufficient for participant allocation");
        }
    }
    private static void creditShares(Connection connection, String company, UUID account, long shares) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO share_holdings (company_id, holder_uuid, available_shares, reserved_shares) VALUES (?, ?, ?, 0) ON CONFLICT(company_id, holder_uuid) DO UPDATE SET available_shares = available_shares + excluded.available_shares WHERE share_holdings.available_shares <= ?")) {
            statement.setString(1, company); statement.setString(2, account.toString()); statement.setLong(3, shares); statement.setLong(4, Math.subtractExact(Long.MAX_VALUE, shares));
            if (statement.executeUpdate() != 1) throw new ArithmeticException("participant share allocation overflows");
        }
    }
    private static void requireTransaction(Connection connection) throws SQLException { if (connection == null || connection.getAutoCommit()) throw new IllegalStateException("caller-owned transaction connection required"); }
}
