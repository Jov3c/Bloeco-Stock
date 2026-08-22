package cn.blockeco.exchange.domain.trading;

import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.money.Money;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Persisted GTC limit order. Monetary values are snapshots, not live configuration. */
public record LimitOrder(
        UUID id,
        CompanyId companyId,
        String stockCode,
        UUID playerId,
        Side side,
        Money limitPrice,
        long originalShares,
        long remainingShares,
        long prioritySequence,
        Money reservedCash,
        Money filledNotional,
        Money feeCharged,
        int feeBps,
        Instant acceptedAt,
        State state) {
    public enum Side { BUY, SELL }
    public enum State { OPEN, PARTIALLY_FILLED, FILLED, CANCELLED, SELF_TRADE_PREVENTED }

    public LimitOrder {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(companyId, "companyId");
        if (stockCode == null || !stockCode.matches("BS\\d{6}")) throw new IllegalArgumentException("stockCode must be a stock code");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(limitPrice, "limitPrice");
        if (limitPrice.minorUnits() <= 0) throw new IllegalArgumentException("limitPrice must be positive");
        if (originalShares <= 0) throw new IllegalArgumentException("originalShares must be positive");
        if (remainingShares < 0 || remainingShares > originalShares) throw new IllegalArgumentException("remainingShares must be between zero and originalShares");
        if (prioritySequence <= 0) throw new IllegalArgumentException("prioritySequence must be positive");
        Objects.requireNonNull(reservedCash, "reservedCash").requireNonNegative("reservedCash");
        Objects.requireNonNull(filledNotional, "filledNotional").requireNonNegative("filledNotional");
        Objects.requireNonNull(feeCharged, "feeCharged").requireNonNegative("feeCharged");
        if (feeBps < 0 || feeBps > 10_000) throw new IllegalArgumentException("feeBps must be between 0 and 10000");
        Objects.requireNonNull(acceptedAt, "acceptedAt");
        Objects.requireNonNull(state, "state");
        if ((state == State.OPEN || state == State.PARTIALLY_FILLED) && remainingShares == 0) {
            throw new IllegalArgumentException("remainingShares must be positive for an active order");
        }
        if (state == State.FILLED && remainingShares != 0) {
            throw new IllegalArgumentException("filled order must have no remainingShares");
        }
    }
}
