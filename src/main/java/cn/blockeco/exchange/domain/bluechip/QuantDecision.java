package cn.blockeco.exchange.domain.bluechip;

import java.time.Instant;
import java.util.Objects;

/** Append-only strategy decision audit; it is not a privileged settlement record. */
public record QuantDecision(String id, String stockCode, String signal, int confidenceBps, String action,
                            long requestedShares, long filledShares, long realizedPnlMinor, int riskLevel, Instant decidedAt) {
    public QuantDecision {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("decision id is required");
        if (stockCode == null || stockCode.isBlank()) throw new IllegalArgumentException("stock code is required");
        if (signal == null || signal.isBlank()) throw new IllegalArgumentException("signal is required");
        if (confidenceBps < 0 || confidenceBps > 10_000) throw new IllegalArgumentException("confidence must be between 0 and 10000");
        if (action == null || action.isBlank()) throw new IllegalArgumentException("action is required");
        if (requestedShares < 0 || filledShares < 0 || filledShares > requestedShares) throw new IllegalArgumentException("invalid requested or filled shares");
        if (riskLevel < 0 || riskLevel > 3) throw new IllegalArgumentException("risk level must be between 0 and 3");
        Objects.requireNonNull(decidedAt, "decidedAt");
    }
}
