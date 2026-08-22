package cn.blockeco.exchange.application;

import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.finance.TreasuryOperationState;
import cn.blockeco.exchange.domain.money.Money;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Read-only administrator projection; it intentionally contains no escrow correlation key. */
public record IpoSubscriptionRecoveryRecord(UUID subscriptionId, UUID offeringId, CompanyId companyId,
                                            UUID playerId, long shares, Money amount,
                                            TreasuryOperationState state, Instant updatedAt, String reason) {
    public IpoSubscriptionRecoveryRecord {
        Objects.requireNonNull(subscriptionId); Objects.requireNonNull(offeringId); Objects.requireNonNull(companyId);
        Objects.requireNonNull(playerId); if (shares <= 0) throw new IllegalArgumentException("shares must be positive");
        Objects.requireNonNull(amount); Objects.requireNonNull(state); Objects.requireNonNull(updatedAt);
        reason = reason == null || reason.isBlank() ? "启动恢复：需人工核对 IPO 认购状态。" : reason;
    }
}
