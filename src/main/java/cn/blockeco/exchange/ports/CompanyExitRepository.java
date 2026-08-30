package cn.blockeco.exchange.ports;

import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.governance.CompanyGovernanceAction;
import cn.blockeco.exchange.domain.governance.CompanyPayoutOperation;
import cn.blockeco.exchange.domain.governance.GovernanceActionState;
import cn.blockeco.exchange.domain.governance.OrderReleaseProgress;
import cn.blockeco.exchange.domain.governance.PayoutOperationState;
import cn.blockeco.exchange.domain.governance.CompanyExitSnapshot;
import cn.blockeco.exchange.domain.governance.CompanyLiquidationClaim;
import cn.blockeco.exchange.domain.company.CompanyStatus;
import cn.blockeco.exchange.ports.SecuritiesCashRepository;
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
    /** Read model for the shareholder buyback page; terminal actions are intentionally omitted. */
    List<CompanyGovernanceAction> activeBuybacks(CompanyId companyId);
    void createPayout(Connection connection, CompanyPayoutOperation payout) throws SQLException;
    boolean transitionPayout(Connection connection, UUID payoutId, PayoutOperationState expected, PayoutOperationState next, String detail, Instant updatedAt) throws SQLException;
    /** Reserves authoritative company cash; it never reads legacy companies.treasury_minor. */
    boolean reserveCompanyCash(Connection connection, CompanyId companyId, long amountMinor) throws SQLException;
    /**
     * Reserves cash for a founder cash-out while preserving paid-in capital and positive
     * retained earnings: cash - reserved - paid-in - max(retained earnings, 0) >= amount.
     */
    boolean reserveFounderCashOut(Connection connection, CompanyId companyId, long amountMinor) throws SQLException;
    boolean releaseCompanyCash(Connection connection, CompanyId companyId, long amountMinor) throws SQLException;
    /** Final internal debit only after the external Vault deposit is durably confirmed. */
    boolean completePayout(Connection connection, UUID payoutId, Instant completedAt) throws SQLException;
    /**
     * Accepts a shareholder's voluntary sale exactly once, credits the seller's segregated
     * securities account, and transfers the shares into the company's internal treasury holding.
     */
    boolean acceptBuyback(Connection connection, UUID actionId, CompanyId companyId, UUID shareholderId, long shares,
                          String correlationKey, SecuritiesCashRepository securitiesCash,
                          Instant acceptedAt) throws SQLException;
    List<CompanyPayoutOperation> recoverablePayouts(int limit);
    List<UUID> activeOrderIds(Connection connection, CompanyId companyId, UUID afterOrderId, int limit) throws SQLException;
    void recordOrderReleaseProgress(Connection connection, UUID actionId, UUID lastReleasedOrderId, long releasedOrders, boolean complete, Instant updatedAt) throws SQLException;
    Optional<OrderReleaseProgress> orderReleaseProgress(UUID actionId);
    Optional<OrderReleaseProgress> orderReleaseProgress(Connection connection, UUID actionId) throws SQLException;
    boolean transitionCompanyStatus(Connection connection, CompanyId companyId, CompanyStatus expected, CompanyStatus next) throws SQLException;
    boolean hasCompanyStatus(Connection connection, CompanyId companyId, CompanyStatus status) throws SQLException;
    List<CompanyExitSnapshot> createExitSnapshots(Connection connection, UUID actionId, CompanyId companyId, Instant snapshottedAt) throws SQLException;
    List<CompanyExitSnapshot> exitSnapshots(UUID actionId);
    List<CompanyLiquidationClaim> createLiquidationClaims(Connection connection, UUID actionId, long pricePerShareMinor, Instant createdAt) throws SQLException;
    List<CompanyLiquidationClaim> liquidationClaims(UUID actionId);
    List<CompanyLiquidationClaim> liquidationClaims(Connection connection, UUID actionId) throws SQLException;
    /** Credits one pending claim and applies its already-fixed company/fund contributions exactly once. */
    boolean creditLiquidationClaim(Connection connection, UUID actionId, UUID holderId, SecuritiesCashRepository securitiesCash, Instant creditedAt) throws SQLException;
}
