package cn.blockeco.exchange.domain.bluechip;

import java.time.Instant;
import java.util.Objects;

/** Durable risk brake for the finite bluechip system participant. */
public record QuantRiskState(String stockCode, int riskLevel, int consecutiveLosses, Instant cooldownUntil, Instant updatedAt) {
    public QuantRiskState {
        if (stockCode == null || stockCode.isBlank()) throw new IllegalArgumentException("stock code is required");
        if (riskLevel < 0 || riskLevel > 3) throw new IllegalArgumentException("risk level must be between 0 and 3");
        if (consecutiveLosses < 0) throw new IllegalArgumentException("consecutive losses must not be negative");
        Objects.requireNonNull(cooldownUntil, "cooldownUntil");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
