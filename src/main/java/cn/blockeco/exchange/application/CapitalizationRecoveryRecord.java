package cn.blockeco.exchange.application;

import cn.blockeco.exchange.domain.finance.TreasuryOperation;
import java.util.Objects;

/** Read-only administrator view of an externally ambiguous capitalization. */
public record CapitalizationRecoveryRecord(TreasuryOperation operation, String reason) {
    public CapitalizationRecoveryRecord { Objects.requireNonNull(operation, "operation"); reason = reason == null ? "" : reason; }
}
