package cn.blockeco.exchange;

import cn.blockeco.exchange.paper.CompanyCommand;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns shutdown from pre-migration initialization through the ready runtime. */
final class PluginRuntime {
    private final Object lock = new Object();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicBoolean databaseClosed = new AtomicBoolean();
    private CompanyCommand command;
    private BootstrapCoordinator<?> bootstrap;
    private ExecutorService executor;
    private AutoCloseable database;

    PluginRuntime() { }
    PluginRuntime(CompanyCommand command, BootstrapCoordinator<?> bootstrap, ExecutorService executor, AutoCloseable database) { this.command = command; this.bootstrap = bootstrap; this.executor = executor; this.database = database; }
    PluginRuntime(CompanyCommand command) { this.command = command; }

    boolean accepting() { return !stopped.get(); }
    void attachBootstrap(BootstrapCoordinator<?> value) { boolean stop; synchronized (lock) { bootstrap = value; stop = stopped.get(); } if (stop) value.stop(); }
    void attachExecutor(ExecutorService value) { boolean stop; synchronized (lock) { executor = value; stop = stopped.get(); } if (stop) shutdown(value); }
    /** Claims a pool as soon as it exists, before any potentially-blocking migration work. */
    void attachDatabase(AutoCloseable value) {
        synchronized (lock) {
            if (!stopped.get()) { database = value; return; }
        }
        closeDatabase(value);
    }
    boolean attachReady(CompanyCommand value, AutoCloseable db) {
        synchronized (lock) {
            command = value;
            if (!stopped.get()) { database = db; value.setAccepting(true); return true; }
        }
        closeDatabase(db);
        return false;
    }
    void stop() {
        if (!stopped.compareAndSet(false, true)) return;
        CompanyCommand currentCommand; BootstrapCoordinator<?> currentBootstrap; ExecutorService currentExecutor; AutoCloseable currentDatabase;
        synchronized (lock) { currentCommand = command; currentBootstrap = bootstrap; currentExecutor = executor; currentDatabase = database; }
        if (currentCommand != null) currentCommand.setAccepting(false);
        if (currentBootstrap != null) currentBootstrap.stop();
        closeDatabase(currentDatabase);
        if (currentExecutor != null) shutdown(currentExecutor);
    }
    void closeDatabase(AutoCloseable value) {
        if (value != null && databaseClosed.compareAndSet(false, true)) try { value.close(); } catch (Exception e) { throw new IllegalStateException("could not close Blockeco database", e); }
    }
    private void shutdown(ExecutorService value) {
        value.shutdown();
        try { value.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
