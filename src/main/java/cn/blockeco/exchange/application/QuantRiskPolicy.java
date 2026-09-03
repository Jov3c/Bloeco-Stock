package cn.blockeco.exchange.application;

import cn.blockeco.exchange.domain.bluechip.QuantRiskState;
import cn.blockeco.exchange.domain.money.Money;
import java.time.Instant;
import java.util.Objects;

/** Caps ordinary system orders and creates a cooldown after a losing sequence. */
public final class QuantRiskPolicy {
    private final int maximumOrderBps; private final int cooldownSeconds;

    public QuantRiskPolicy(int maximumOrderBps, int cooldownSeconds) {
        if (maximumOrderBps < 1 || maximumOrderBps > 500) throw new IllegalArgumentException("maximum order bps must be between 1 and 500");
        if (cooldownSeconds < 1 || cooldownSeconds > 3_600) throw new IllegalArgumentException("cooldown seconds must be between 1 and 3600");
        this.maximumOrderBps = maximumOrderBps; this.cooldownSeconds = cooldownSeconds;
    }

    public long orderShares(long desired, long availableCashMinor, long availableShares, Money price, QuantRiskState risk, boolean buying) {
        return orderShares(desired, availableCashMinor, availableShares, price, 0, risk, buying);
    }

    public long orderShares(long desired, long availableCashMinor, long availableShares, Money price, int buyerFeeBps, QuantRiskState risk, boolean buying) {
        Objects.requireNonNull(price); Objects.requireNonNull(risk);
        if (desired <= 0 || price.minorUnits() <= 0 || risk.riskLevel() >= 3 || risk.consecutiveLosses() >= 3) return 0;
        int capBps = switch (risk.riskLevel()) { case 0 -> maximumOrderBps; case 1 -> Math.max(1, maximumOrderBps / 2); case 2 -> Math.max(1, maximumOrderBps / 4); default -> 0; };
        if (!buying) {
            long affordable = Math.floorDiv(Math.multiplyExact(Math.max(0, availableShares), capBps), 10_000L);
            return Math.min(desired, availableShares > 0 ? Math.max(1L, affordable) : 0L);
        }
        long cash = Math.max(0, availableCashMinor);
        long oneShareCost = buyCost(1, price, buyerFeeBps);
        if (cash < oneShareCost) return 0;
        long percentageBudget = Math.floorDiv(Math.multiplyExact(cash, capBps), 10_000L);
        long permittedBudget = Math.min(cash, Math.max(percentageBudget, oneShareCost));
        long low = 1;
        long high = Math.min(desired, Math.floorDiv(cash, price.minorUnits()));
        long accepted = 0;
        while (low <= high) {
            long candidate = low + Math.floorDiv(high - low, 2);
            if (buyCost(candidate, price, buyerFeeBps) <= permittedBudget) { accepted = candidate; low = candidate + 1; }
            else high = candidate - 1;
        }
        return accepted;
    }

    private static long buyCost(long shares, Money price, int buyerFeeBps) {
        long notional = Math.multiplyExact(shares, price.minorUnits());
        return Math.addExact(notional, cn.blockeco.exchange.domain.trading.FeePolicy.cumulativeFee(Money.ofMinor(notional), buyerFeeBps).minorUnits());
    }

    public QuantRiskState afterResult(QuantRiskState current, long realizedPnlMinor, Instant now) {
        Objects.requireNonNull(current); Objects.requireNonNull(now);
        if (realizedPnlMinor >= 0) return new QuantRiskState(current.stockCode(), Math.max(0, current.riskLevel() - 1), 0, now, now);
        int losses = Math.addExact(current.consecutiveLosses(), 1);
        int level = Math.min(3, losses / 3);
        return new QuantRiskState(current.stockCode(), level, losses, now.plusSeconds(cooldownSeconds), now);
    }
}
