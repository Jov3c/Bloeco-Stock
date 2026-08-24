package cn.blockeco.exchange.domain.market;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Objects;

/** The daily matching window is [08:00, 20:00) in the configured market zone. */
public record MarketSession(boolean acceptsMatching) {
    private static final LocalTime OPENS_AT = LocalTime.of(8, 0);
    private static final LocalTime CLOSES_AT = LocalTime.of(20, 0);

    public static MarketSession at(Instant now, ZoneId zone) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(zone, "zone");
        LocalTime localTime = now.atZone(zone).toLocalTime();
        return new MarketSession(!localTime.isBefore(OPENS_AT) && localTime.isBefore(CLOSES_AT));
    }
}
