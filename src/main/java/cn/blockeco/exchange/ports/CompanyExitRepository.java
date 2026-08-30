package cn.blockeco.exchange.ports;

import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.governance.CompanyGovernanceAction;
import cn.blockeco.exchange.domain.governance.CompanyPayoutOperation;
import cn.blockeco.exchange.domain.governance.GovernanceActionState;
import cn.blockeco.exchange.domain.governance.OrderReleaseProgress;
import cn.blockeco.exchange.domain.governance.PayoutOperationState;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Durable exit facts. Every mutation belongs to the caller's transaction. */
public interface CompanyExitRepository {
    void createAction(Connection connection, CompanyGovernanceAction action, String announcementBody, Instant recordedAt) throws SQLException;
    boolean transitionAction(Connection connection, UUID actionId, GovernanceActionState expected, GovernanceActionState next, Instant updatedAt) throws SQLException;
    Optional<CompanyGovernanceAction> findAction(UUID actionId);
    void createPayout(Connection connection, CompanyPayoutOperation payout) throws SQLException;
    boolean transitionPayout(Connection connection, UUID payoutId, PayoutOperationState expected, PayoutOperationState next, String detail, Instant updatedAt) throws SQLException;
    List<CompanyPayoutOperation> recoverablePayouts(int limit);
    List<UUID> activeOrderIds(Connection connection, CompanyId companyId, UUID afterOrderId, int limit) throws SQLException;
    void recordOrderReleaseProgress(Connection connection, UUID actionId, UUID lastReleasedOrderId, long releasedOrders, boolean complete, Instant updatedAt) throws SQLException;
    Optional<OrderReleaseProgress> orderReleaseProgress(UUID actionId);
}
