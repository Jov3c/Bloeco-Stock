package cn.blockeco.exchange.application;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Owns periodic player-company event ingestion and monthly-report checks. */
public final class CompanyFinanceSchedulers {
    private final Repeater repeater;
    private final Runnable ingest;
    private final Runnable reports;
    private final Duration ingestionPeriod;
    private final List<Cancellation> jobs = new ArrayList<>();

    public CompanyFinanceSchedulers(Repeater repeater, Runnable ingest, Runnable reports) {
        this(repeater, ingest, reports, Duration.ofMinutes(5));
    }

    public CompanyFinanceSchedulers(Repeater repeater, Runnable ingest, Runnable reports, Duration ingestionPeriod) {
        this.repeater = Objects.requireNonNull(repeater, "repeater");
        this.ingest = Objects.requireNonNull(ingest, "ingest");
        this.reports = Objects.requireNonNull(reports, "reports");
        this.ingestionPeriod = Objects.requireNonNull(ingestionPeriod, "ingestionPeriod");
        if (ingestionPeriod.isZero() || ingestionPeriod.isNegative()) throw new IllegalArgumentException("ingestionPeriod must be positive");
    }

    public synchronized void start() {
        if (!jobs.isEmpty()) return;
        jobs.add(repeater.repeat(ingest, Duration.ZERO, ingestionPeriod));
        jobs.add(repeater.repeat(reports, Duration.ZERO, Duration.ofHours(1)));
    }

    public synchronized void stop() { jobs.forEach(Cancellation::cancel); jobs.clear(); }
    public synchronized boolean started() { return !jobs.isEmpty(); }

    @FunctionalInterface public interface Repeater { Cancellation repeat(Runnable task, Duration initialDelay, Duration period); }
    @FunctionalInterface public interface Cancellation { void cancel(); }
}
