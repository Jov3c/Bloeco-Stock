package cn.blockeco.exchange.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BluechipSchedulersTest {
    @Test void startSchedulesEveryBluechipJobAndStopCancelsThem() {
        List<BluechipSchedulers.Cancellation> cancellations = new ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger cancelled = new java.util.concurrent.atomic.AtomicInteger();
        BluechipSchedulers schedulers = new BluechipSchedulers((task, initial, period) -> {
            cancellations.add(cancelled::incrementAndGet);
            return cancellations.getLast();
        }, () -> { }, () -> { }, () -> { }, () -> { }, () -> { });

        schedulers.start();
        schedulers.stop();

        assertThat(cancellations).hasSize(5);
        assertThat(cancelled).hasValue(5);
        assertThat(schedulers.started()).isFalse();
    }
}
