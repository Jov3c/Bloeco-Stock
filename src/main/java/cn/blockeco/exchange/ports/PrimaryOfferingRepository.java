package cn.blockeco.exchange.ports;

import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.finance.PrimaryOffering;
import cn.blockeco.exchange.domain.finance.TreasuryOperation;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PrimaryOfferingRepository {
    record SubscriptionPreparation(TreasuryOperation operation, boolean newlyPrepared) { }
    void announce(Connection connection, PrimaryOffering offering) throws SQLException;
    Optional<PrimaryOffering> find(UUID offeringId);
    long paidInCapital(CompanyId companyId);
    boolean hasActiveAsset(CompanyId companyId);
    boolean isFounder(CompanyId companyId, UUID founder);
    SubscriptionPreparation prepareSubscription(Connection connection, UUID subscriptionId, PrimaryOffering offering, UUID subscriber, long shares, Instant now) throws SQLException;
    void markWithdrawn(Connection connection, UUID subscriptionId, Instant now) throws SQLException;
    void markEscrowDeposited(Connection connection, UUID subscriptionId, Instant now) throws SQLException;
    void completeSubscription(Connection connection, UUID subscriptionId, Instant now) throws SQLException;
    void markAmbiguous(Connection connection, UUID subscriptionId, String expectedState, Instant now) throws SQLException;
    void cancelPrepared(Connection connection, UUID subscriptionId, Instant now) throws SQLException;
    void closeExpired(Connection connection, Instant now) throws SQLException;
}
