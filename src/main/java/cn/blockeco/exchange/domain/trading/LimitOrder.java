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
        if (!isValidStockCode(stockCode)) throw new IllegalArgumentException("stockCode must be a bounded stock code");
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
        boolean sharesMatchState = switch (state) {
            case OPEN -> remainingShares == originalShares;
            case PARTIALLY_FILLED -> remainingShares > 0 && remainingShares < originalShares;
            case FILLED -> remainingShares == 0;
            case CANCELLED, SELF_TRADE_PREVENTED -> remainingShares > 0;
        };
        if (!sharesMatchState) throw new IllegalArgumentException("remainingShares do not match order state");
        if (side == Side.BUY) {
            boolean active = state == State.OPEN || state == State.PARTIALLY_FILLED;
            Money expectedChargedFee = FeePolicy.cumulativeFee(filledNotional, feeBps);
            if (!feeCharged.equals(expectedChargedFee)) {
                throw new IllegalArgumentException("feeCharged must equal the cumulative fee for filledNotional");
            }
            if (active && !reservedCash.equals(requiredActiveReserve(remainingShares, limitPrice, filledNotional, feeCharged, feeBps))) {
                throw new IllegalArgumentException("reservedCash does not match buy order state");
            }
            if (!active && reservedCash.minorUnits() != 0) throw new IllegalArgumentException("reservedCash must be zero for terminal buy order");
            boolean hasFilledShares = remainingShares < originalShares;
            if ((hasFilledShares && filledNotional.minorUnits() <= 0)
                    || (!hasFilledShares && filledNotional.minorUnits() != 0)) {
                throw new IllegalArgumentException("filledNotional does not match filled shares");
            }
        } else if (reservedCash.minorUnits() != 0 || filledNotional.minorUnits() != 0
                || feeCharged.minorUnits() != 0 || feeBps != 0) {
            throw new IllegalArgumentException("sell orders must not contain buy money fields");
        }
    }

    private static Money requiredActiveReserve(long remainingShares, Money limitPrice, Money filledNotional,
            Money feeCharged, int feeBps) {
        Money remainingNotional = Money.ofMinor(Math.multiplyExact(remainingShares, limitPrice.minorUnits()));
        Money worstCaseNotional = filledNotional.plus(remainingNotional);
        Money remainingFee = FeePolicy.cumulativeFee(worstCaseNotional, feeBps).minus(feeCharged);
        return remainingNotional.plus(remainingFee);
    }

    private static boolean isValidStockCode(String stockCode) {
        return stockCode != null && stockCode.matches("BS\\d{6}")
                && Integer.parseInt(stockCode.substring(2)) >= 1;
    }
}
