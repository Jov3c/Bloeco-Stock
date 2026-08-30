package cn.blockeco.exchange.application;

import java.util.Objects;
import java.util.function.Consumer;

/** Polls the issuance state machine without coupling the application service to Paper. */
public final class IssuanceLifecycleScheduler {
    public interface RepeatingScheduler { Cancellation everyMinute(Runnable task); }
    public interface Cancellation { void cancel(); }

    private final RepeatingScheduler scheduler;
    private final Runnable advance;
    private final Consumer<Throwable> failures;
    private Cancellation cancellation;

    public IssuanceLifecycleScheduler(RepeatingScheduler scheduler, Runnable advance, Consumer<Throwable> failures) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.advance = Objects.requireNonNull(advance, "advance");
        this.failures = Objects.requireNonNull(failures, "failures");
    }

    public void start() {
        if (cancellation == null) cancellation = scheduler.everyMinute(() -> {
            try { advance.run(); } catch (RuntimeException failure) { failures.accept(failure); }
        });
    }

    public void stop() { if (cancellation != null) { cancellation.cancel(); cancellation = null; } }
    public boolean started() { return cancellation != null; }
}
