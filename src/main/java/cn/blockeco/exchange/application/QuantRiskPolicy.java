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
        Objects.requireNonNull(price); Objects.requireNonNull(risk);
        if (desired <= 0 || price.minorUnits() <= 0 || risk.riskLevel() >= 3 || risk.consecutiveLosses() >= 3) return 0;
        int capBps = switch (risk.riskLevel()) { case 0 -> maximumOrderBps; case 1 -> Math.max(1, maximumOrderBps / 2); case 2 -> Math.max(1, maximumOrderBps / 4); default -> 0; };
        long affordable = buying
                ? Math.floorDiv(Math.floorDiv(Math.multiplyExact(Math.max(0, availableCashMinor), capBps), 10_000L), price.minorUnits())
                : Math.floorDiv(Math.multiplyExact(Math.max(0, availableShares), capBps), 10_000L);
        return Math.max(0, Math.min(desired, affordable));
    }

    public QuantRiskState afterResult(QuantRiskState current, long realizedPnlMinor, Instant now) {
        Objects.requireNonNull(current); Objects.requireNonNull(now);
        if (realizedPnlMinor >= 0) return new QuantRiskState(current.stockCode(), Math.max(0, current.riskLevel() - 1), 0, now, now);
        int losses = Math.addExact(current.consecutiveLosses(), 1);
        int level = Math.min(3, losses / 3);
        return new QuantRiskState(current.stockCode(), level, losses, now.plusSeconds(cooldownSeconds), now);
    }
}
