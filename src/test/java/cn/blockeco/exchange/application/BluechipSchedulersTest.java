package cn.blockeco.exchange.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BluechipSchedulersTest {
    @Test void startSchedulesEveryBluechipJobAndStopCancelsThem() {
        List<BluechipSchedulers.Cancellation> cancellations = new ArrayList<>();
        List<java.time.Duration> periods = new ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger cancelled = new java.util.concurrent.atomic.AtomicInteger();
        BluechipSchedulers schedulers = new BluechipSchedulers((task, initial, period) -> {
            periods.add(period);
            cancellations.add(cancelled::incrementAndGet);
            return cancellations.getLast();
        }, () -> { }, () -> { }, () -> { }, () -> { }, () -> { }, () -> { });

        schedulers.start();
        schedulers.stop();

        assertThat(periods).containsExactly(
                java.time.Duration.ofMinutes(1), java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(8),
                java.time.Duration.ofMinutes(5), java.time.Duration.ofMinutes(1), java.time.Duration.ofDays(1));
        assertThat(cancellations).hasSize(6);
        assertThat(cancelled).hasValue(6);
        assertThat(schedulers.started()).isFalse();
    }
}
