package cn.blockeco.exchange.domain.market;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class MarketSessionTest {
    @Test
    void sessionIsOpenAtEightAndClosedAtTwentyInConfiguredZone() {
        ZoneId zone = ZoneId.of("Asia/Shanghai");

        assertThat(MarketSession.at(Instant.parse("2026-08-24T00:00:00Z"), zone).acceptsMatching()).isTrue();
        assertThat(MarketSession.at(Instant.parse("2026-08-24T12:00:00Z"), zone).acceptsMatching()).isFalse();
    }
}
