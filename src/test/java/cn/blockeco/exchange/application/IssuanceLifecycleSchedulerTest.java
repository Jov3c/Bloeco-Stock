package cn.blockeco.exchange.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class IssuanceLifecycleSchedulerTest {
    @Test
    void startsOncePollsEveryMinuteAndCancelsOnStop() {
        AtomicInteger polls = new AtomicInteger();
        AtomicInteger cancellations = new AtomicInteger();
        var scheduler = new IssuanceLifecycleScheduler(task -> {
            task.run();
            return cancellations::incrementAndGet;
        }, polls::incrementAndGet, ignored -> { });

        scheduler.start();
        scheduler.start();
        scheduler.stop();

        assertThat(polls).hasValue(1);
        assertThat(cancellations).hasValue(1);
        assertThat(scheduler.started()).isFalse();
    }
}
