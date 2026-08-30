package cn.blockeco.exchange.infrastructure.sql;

import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.governance.CompanyGovernanceAction;
import cn.blockeco.exchange.domain.governance.CompanyPayoutOperation;
import cn.blockeco.exchange.domain.governance.GovernanceActionState;
import cn.blockeco.exchange.domain.governance.GovernanceActionType;
import cn.blockeco.exchange.domain.governance.OrderReleaseProgress;
import cn.blockeco.exchange.domain.governance.PayoutOperationState;
import cn.blockeco.exchange.ports.CompanyExitRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/** SQL persistence for announced exit actions and recoverable external company payouts. */
public final class SqlCompanyExitRepository implements CompanyExitRepository {
    private final DataSource dataSource;

    public SqlCompanyExitRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public void createAction(Connection connection, CompanyGovernanceAction action, String announcementBody, Instant recordedAt) throws SQLException {
        requireTransaction(connection);
        Objects.requireNonNull(action, "action"); Objects.requireNonNull(announcementBody, "announcementBody"); Objects.requireNonNull(recordedAt, "recordedAt");
        requirePlayerCompany(connection, action.companyId());
        String payload = actionPayload(action);
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO company_governance_actions
                (id,company_id,actor_uuid,action_type,amount_minor,price_per_share_minor,announced_at,executable_at,state,correlation_key,payload_json,updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """)) {
            statement.setString(1, action.id().toString()); statement.setString(2, action.companyId().value().toString()); statement.setString(3, action.actorUuid().toString());
            statement.setString(4, action.type().name()); statement.setLong(5, action.amountMinor()); statement.setLong(6, action.pricePerShareMinor());
            statement.setString(7, action.announcedAt().toString()); statement.setString(8, action.executableAt().toString()); statement.setString(9, action.state().name());
            statement.setString(10, action.correlationKey()); statement.setString(11, payload); statement.setString(12, recordedAt.toString()); statement.executeUpdate();
        }
        String factId = action.id() + ":GOVERNANCE_ANNOUNCED";
        try (PreparedStatement announcement = connection.prepareStatement("INSERT INTO company_announcements (id,company_id,offering_id,body,created_at) VALUES (?,?,NULL,?,?)");
             PreparedStatement audit = connection.prepareStatement("INSERT INTO audit_events (event_id,company_id,actor_uuid,event_type,payload_json,occurred_at) VALUES (?,?,?,?,?,?)")) {
            announcement.setString(1, factId); announcement.setString(2, action.companyId().value().toString()); announcement.setString(3, announcementBody); announcement.setString(4, recordedAt.toString()); announcement.executeUpdate();
            audit.setString(1, factId); audit.setString(2, action.companyId().value().toString()); audit.setString(3, action.actorUuid().toString()); audit.setString(4, "COMPANY_GOVERNANCE_ANNOUNCED"); audit.setString(5, payload); audit.setString(6, recordedAt.toString()); audit.executeUpdate();
        }
    }

    @Override
    public boolean transitionAction(Connection connection, UUID actionId, GovernanceActionState expected, GovernanceActionState next, Instant updatedAt) throws SQLException {
        requireTransaction(connection); Objects.requireNonNull(actionId, "actionId"); Objects.requireNonNull(expected, "expected"); Objects.requireNonNull(next, "next"); Objects.requireNonNull(updatedAt, "updatedAt");
        try (PreparedStatement statement = connection.prepareStatement("UPDATE company_governance_actions SET state=?,updated_at=? WHERE id=? AND state=?")) {
            statement.setString(1, next.name()); statement.setString(2, updatedAt.toString()); statement.setString(3, actionId.toString()); statement.setString(4, expected.name()); return statement.executeUpdate() == 1;
        }
    }

    @Override
    public Optional<CompanyGovernanceAction> findAction(UUID actionId) {
        Objects.requireNonNull(actionId, "actionId");
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement("SELECT * FROM company_governance_actions WHERE id=?")) {
            statement.setString(1, actionId.toString()); try (ResultSet rows = statement.executeQuery()) { return rows.next() ? Optional.of(action(rows)) : Optional.empty(); }
        } catch (SQLException exception) { throw new IllegalStateException("could not read governance action", exception); }
    }

    @Override
    public void createPayout(Connection connection, CompanyPayoutOperation payout) throws SQLException {
        requireTransaction(connection); Objects.requireNonNull(payout, "payout");
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO company_payout_operations
                (id,company_id,governance_action_id,recipient_uuid,amount_minor,correlation_key,state,detail,created_at,updated_at)
                SELECT ?,?,?,?,?,?,?,?,?,?
                WHERE EXISTS (SELECT 1 FROM company_governance_actions WHERE id=? AND company_id=?)
                """)) {
            statement.setString(1, payout.id().toString()); statement.setString(2, payout.companyId().value().toString()); statement.setString(3, payout.governanceActionId().toString());
            statement.setString(4, payout.recipientUuid().toString()); statement.setLong(5, payout.amountMinor()); statement.setString(6, payout.correlationKey()); statement.setString(7, payout.state().name());
            statement.setString(8, payout.detail()); statement.setString(9, payout.createdAt().toString()); statement.setString(10, payout.updatedAt().toString());
            statement.setString(11, payout.governanceActionId().toString()); statement.setString(12, payout.companyId().value().toString());
            if (statement.executeUpdate() != 1) throw new IllegalArgumentException("payout action and company do not match");
        }
    }

    @Override
    public boolean transitionPayout(Connection connection, UUID payoutId, PayoutOperationState expected, PayoutOperationState next, String detail, Instant updatedAt) throws SQLException {
        requireTransaction(connection); Objects.requireNonNull(payoutId, "payoutId"); Objects.requireNonNull(expected, "expected"); Objects.requireNonNull(next, "next"); Objects.requireNonNull(updatedAt, "updatedAt");
        try (PreparedStatement statement = connection.prepareStatement("UPDATE company_payout_operations SET state=?,detail=?,updated_at=? WHERE id=? AND state=?")) {
            statement.setString(1, next.name()); statement.setString(2, detail); statement.setString(3, updatedAt.toString()); statement.setString(4, payoutId.toString()); statement.setString(5, expected.name()); return statement.executeUpdate() == 1;
        }
    }

    @Override
    public List<CompanyPayoutOperation> recoverablePayouts(int limit) {
        int bound = Math.max(1, Math.min(100, limit));
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement("SELECT * FROM company_payout_operations WHERE state IN ('PREPARED','EXTERNAL_DEBIT_CONFIRMED','AMBIGUOUS') ORDER BY updated_at,id LIMIT ?")) {
            statement.setInt(1, bound); try (ResultSet rows = statement.executeQuery()) { List<CompanyPayoutOperation> payouts = new ArrayList<>(); while (rows.next()) payouts.add(payout(rows)); return List.copyOf(payouts); }
        } catch (SQLException exception) { throw new IllegalStateException("could not read recoverable company payouts", exception); }
    }

    @Override
    public List<UUID> activeOrderIds(Connection connection, CompanyId companyId, UUID afterOrderId, int limit) throws SQLException {
        requireTransaction(connection); Objects.requireNonNull(companyId, "companyId"); int bound = Math.max(1, Math.min(200, limit));
        String sql = afterOrderId == null
                ? "SELECT id FROM stock_orders WHERE company_id=? AND state IN ('OPEN','PARTIALLY_FILLED') ORDER BY id LIMIT ?"
                : "SELECT id FROM stock_orders WHERE company_id=? AND state IN ('OPEN','PARTIALLY_FILLED') AND id>? ORDER BY id LIMIT ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, companyId.value().toString()); if (afterOrderId == null) statement.setInt(2, bound); else { statement.setString(2, afterOrderId.toString()); statement.setInt(3, bound); }
            try (ResultSet rows = statement.executeQuery()) { List<UUID> ids = new ArrayList<>(); while (rows.next()) ids.add(UUID.fromString(rows.getString(1))); return List.copyOf(ids); }
        }
    }

    @Override
    public void recordOrderReleaseProgress(Connection connection, UUID actionId, UUID lastReleasedOrderId, long releasedOrders, boolean complete, Instant updatedAt) throws SQLException {
        requireTransaction(connection); Objects.requireNonNull(actionId, "actionId"); Objects.requireNonNull(updatedAt, "updatedAt");
        if (releasedOrders < 0) throw new IllegalArgumentException("released order count cannot be negative");
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO company_order_release_progress (governance_action_id,last_released_order_id,released_orders,complete,updated_at)
                VALUES (?,?,?,?,?)
                ON CONFLICT(governance_action_id) DO UPDATE SET
                  last_released_order_id=excluded.last_released_order_id,
                  released_orders=excluded.released_orders,
                  complete=excluded.complete,
                  updated_at=excluded.updated_at
                WHERE company_order_release_progress.released_orders <= excluded.released_orders
                  AND company_order_release_progress.complete = 0
                """)) {
            statement.setString(1, actionId.toString()); statement.setString(2, lastReleasedOrderId == null ? null : lastReleasedOrderId.toString()); statement.setLong(3, releasedOrders); statement.setInt(4, complete ? 1 : 0); statement.setString(5, updatedAt.toString());
            statement.executeUpdate();
        }
    }

    @Override
    public Optional<OrderReleaseProgress> orderReleaseProgress(UUID actionId) {
        Objects.requireNonNull(actionId, "actionId");
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement("SELECT last_released_order_id,released_orders,complete,updated_at FROM company_order_release_progress WHERE governance_action_id=?")) {
            statement.setString(1, actionId.toString()); try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty(); String last = rows.getString(1);
                return Optional.of(new OrderReleaseProgress(actionId, Optional.ofNullable(last).map(UUID::fromString), rows.getLong(2), rows.getInt(3) != 0, Instant.parse(rows.getString(4))));
            }
        } catch (SQLException exception) { throw new IllegalStateException("could not read order release progress", exception); }
    }

    private static CompanyGovernanceAction action(ResultSet row) throws SQLException {
        return new CompanyGovernanceAction(UUID.fromString(row.getString("id")), new CompanyId(UUID.fromString(row.getString("company_id"))), UUID.fromString(row.getString("actor_uuid")),
                GovernanceActionType.valueOf(row.getString("action_type")), row.getLong("amount_minor"), row.getLong("price_per_share_minor"), Instant.parse(row.getString("announced_at")),
                Instant.parse(row.getString("executable_at")), GovernanceActionState.valueOf(row.getString("state")), row.getString("correlation_key"));
    }

    private static CompanyPayoutOperation payout(ResultSet row) throws SQLException {
        return new CompanyPayoutOperation(UUID.fromString(row.getString("id")), new CompanyId(UUID.fromString(row.getString("company_id"))), UUID.fromString(row.getString("governance_action_id")),
                UUID.fromString(row.getString("recipient_uuid")), row.getLong("amount_minor"), row.getString("correlation_key"), PayoutOperationState.valueOf(row.getString("state")),
                Instant.parse(row.getString("created_at")), Instant.parse(row.getString("updated_at")), row.getString("detail"));
    }

    private static void requirePlayerCompany(Connection connection, CompanyId companyId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM companies c WHERE c.id=? AND NOT EXISTS (SELECT 1 FROM bluechip_companies b WHERE b.company_id=c.id)")) {
            statement.setString(1, companyId.value().toString()); try (ResultSet rows = statement.executeQuery()) { if (!rows.next()) throw new IllegalArgumentException("player company is missing or is a bluechip"); }
        }
    }

    private static String actionPayload(CompanyGovernanceAction action) {
        return "{\"actionId\":\"" + action.id() + "\",\"companyId\":\"" + action.companyId().value() + "\",\"type\":\"" + action.type() + "\",\"amountMinor\":" + action.amountMinor() + ",\"pricePerShareMinor\":" + action.pricePerShareMinor() + ",\"executableAt\":\"" + action.executableAt() + "\"}";
    }

    private static void requireTransaction(Connection connection) throws SQLException {
        if (connection == null || connection.getAutoCommit()) throw new IllegalStateException("caller-owned transaction connection required");
    }
}
