package cn.blockeco.exchange.ports;

import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.finance.PrimaryOffering;
import cn.blockeco.exchange.domain.finance.TreasuryOperation;
import cn.blockeco.exchange.domain.finance.PublicOfferingView;
import java.util.List;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import cn.blockeco.exchange.application.IpoSubscriptionRecoveryRecord;
import cn.blockeco.exchange.application.IpoSubscriptionRecoverySummary;

public interface PrimaryOfferingRepository {
    enum SubscriptionPreparationRejection { NOT_OPEN, SOLD_OUT, INVALID }
    final class SubscriptionPreparationRejectedException extends RuntimeException {
        private final SubscriptionPreparationRejection rejection;
        public SubscriptionPreparationRejectedException(SubscriptionPreparationRejection rejection, String message) { super(message); this.rejection = rejection; }
        public SubscriptionPreparationRejectedException(SubscriptionPreparationRejection rejection, String message, Throwable cause) { super(message, cause); this.rejection = rejection; }
        public SubscriptionPreparationRejection rejection() { return rejection; }
    }
    record SubscriptionPreparation(TreasuryOperation operation, boolean newlyPrepared) { }
    void announce(Connection connection, PrimaryOffering offering) throws SQLException;
    Optional<PrimaryOffering> find(UUID offeringId);
    List<PublicOfferingView> listPublic(int limit);
    Optional<PublicOfferingView> findPublic(UUID offeringId);
    long paidInCapital(CompanyId companyId);
    boolean hasActiveAsset(CompanyId companyId);
    boolean isFounder(CompanyId companyId, UUID founder);
    SubscriptionPreparation prepareSubscription(Connection connection, UUID subscriptionId, PrimaryOffering offering, UUID subscriber, long shares, Instant now) throws SQLException;
    void markWithdrawn(Connection connection, UUID subscriptionId, Instant now) throws SQLException;
    void markEscrowDeposited(Connection connection, UUID subscriptionId, Instant now) throws SQLException;
    void completeSubscription(Connection connection, UUID subscriptionId, Instant now) throws SQLException;
    void markAmbiguous(Connection connection, UUID subscriptionId, String externalStage, String reason, Instant now) throws SQLException;
    void cancelPrepared(Connection connection, UUID subscriptionId, Instant now) throws SQLException;
    void closeExpired(Connection connection, Instant now) throws SQLException;
    IpoSubscriptionRecoverySummary recoverSubscriptionsAtStartup(Connection connection, Instant now) throws SQLException;
    List<IpoSubscriptionRecoveryRecord> findAmbiguousSubscriptions();
}
