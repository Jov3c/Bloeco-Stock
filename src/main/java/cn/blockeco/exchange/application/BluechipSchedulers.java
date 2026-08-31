package cn.blockeco.exchange.application;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Owns the server jobs which advance the persisted bluechip market.  The quote job may chain a bounded participant tick after refresh. */
public final class BluechipSchedulers {
    private final Repeater repeater; private final Runnable session; private final Runnable quotes; private final Runnable participants; private final Runnable events; private final Runnable candles; private final Runnable dividends;
    private final List<Cancellation> jobs = new ArrayList<>();
    public BluechipSchedulers(Repeater repeater, Runnable session, Runnable quotes, Runnable participants, Runnable events, Runnable candles, Runnable dividends) {
        this.repeater=Objects.requireNonNull(repeater);this.session=Objects.requireNonNull(session);this.quotes=Objects.requireNonNull(quotes);this.participants=Objects.requireNonNull(participants);this.events=Objects.requireNonNull(events);this.candles=Objects.requireNonNull(candles);this.dividends=Objects.requireNonNull(dividends);
    }
    public synchronized void start() { if (!jobs.isEmpty()) return; jobs.add(repeater.repeat(session, Duration.ZERO, Duration.ofMinutes(1))); jobs.add(repeater.repeat(quotes, Duration.ZERO, Duration.ofSeconds(1))); jobs.add(repeater.repeat(participants, Duration.ofSeconds(2), Duration.ofSeconds(8))); jobs.add(repeater.repeat(events, Duration.ZERO, Duration.ofMinutes(5))); jobs.add(repeater.repeat(candles, Duration.ofMinutes(1), Duration.ofMinutes(1))); jobs.add(repeater.repeat(dividends, Duration.ofMinutes(1), Duration.ofDays(1))); }
    public synchronized void stop() { jobs.forEach(Cancellation::cancel); jobs.clear(); }
    public synchronized boolean started() { return !jobs.isEmpty(); }
    @FunctionalInterface public interface Repeater { Cancellation repeat(Runnable task, Duration initialDelay, Duration period); }
    @FunctionalInterface public interface Cancellation { void cancel(); }
}
