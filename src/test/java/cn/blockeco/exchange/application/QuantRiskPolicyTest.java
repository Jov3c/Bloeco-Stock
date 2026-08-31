package cn.blockeco.exchange.application;

import static org.assertj.core.api.Assertions.assertThat;

import cn.blockeco.exchange.domain.bluechip.QuantRiskState;
import cn.blockeco.exchange.domain.money.Money;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class QuantRiskPolicyTest {
    private static final Instant NOW = Instant.parse("2026-08-31T10:00:00Z");
    private final QuantRiskPolicy policy = new QuantRiskPolicy(200, 120);

    @Test
    void capsBuyOrdersToTwoPercentOfFiniteCashAtRiskLevelZero() {
        long shares = policy.orderShares(100, 10_000, 0, Money.ofMinor(100), new QuantRiskState("NOVA", 0, 0, NOW, NOW), true);

        assertThat(shares).isEqualTo(2);
    }

    @Test
    void pausesAfterThreeConsecutiveLossesAndLetsAProfitReduceRisk() {
        QuantRiskState start = new QuantRiskState("NOVA", 0, 2, NOW, NOW);

        QuantRiskState paused = policy.afterResult(start, -1, NOW);
        QuantRiskState recovered = policy.afterResult(paused, 1, NOW.plusSeconds(121));

        assertThat(paused).isEqualTo(new QuantRiskState("NOVA", 1, 3, NOW.plusSeconds(120), NOW));
        assertThat(policy.orderShares(10, 10_000, 20, Money.ofMinor(100), paused, true)).isZero();
        assertThat(recovered).isEqualTo(new QuantRiskState("NOVA", 0, 0, NOW.plusSeconds(121), NOW.plusSeconds(121)));
    }
}
