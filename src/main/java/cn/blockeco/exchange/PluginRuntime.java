package cn.blockeco.exchange;

import cn.blockeco.exchange.paper.CommandAcceptanceGate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Owns shutdown from pre-migration initialization through the ready runtime. */
final class PluginRuntime {
    private final Object lock = new Object();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicBoolean databaseClosed = new AtomicBoolean();
    private final AtomicLong epoch = new AtomicLong(1L);
    private java.util.List<CommandAcceptanceGate> gates = java.util.List.of();
    private BootstrapCoordinator<?> bootstrap;
    private ExecutorService executor;
    private AutoCloseable database;
    private java.util.function.Supplier<? extends java.util.concurrent.CompletionStage<Void>> financialQuiesce;

    PluginRuntime() { }
    PluginRuntime(CommandAcceptanceGate command, BootstrapCoordinator<?> bootstrap, ExecutorService executor, AutoCloseable database) { this.gates = java.util.List.of(command); this.bootstrap = bootstrap; this.executor = executor; this.database = database; }
    PluginRuntime(CommandAcceptanceGate command) { this.gates = java.util.List.of(command); }

    boolean accepting() { return !stopped.get(); }
    long captureEpoch() { return epoch.get(); }
    boolean isAccepting(long capturedEpoch) { return !stopped.get() && epoch.get() == capturedEpoch; }
    void attachBootstrap(BootstrapCoordinator<?> value) { boolean stop; synchronized (lock) { bootstrap = value; stop = stopped.get(); } if (stop) value.stop(); }
    void attachExecutor(ExecutorService value) { boolean stop; synchronized (lock) { executor = value; stop = stopped.get(); } if (stop) shutdown(value); }
    void attachFinancialQuiesce(java.util.function.Supplier<? extends java.util.concurrent.CompletionStage<Void>> value) { synchronized(lock){financialQuiesce=value;} }
    /** Claims a pool as soon as it exists, before any potentially-blocking migration work. */
    void attachDatabase(AutoCloseable value) {
        synchronized (lock) {
            if (!stopped.get()) { database = value; return; }
        }
        closeDatabase(value);
    }
    boolean attachReady(CommandAcceptanceGate value, AutoCloseable db) { return attachReady(java.util.List.of(value), db); }
    boolean attachReady(java.util.List<? extends CommandAcceptanceGate> values, AutoCloseable db) {
        synchronized (lock) {
            gates = java.util.List.copyOf(values);
            if (!stopped.get()) { database = db; gates.forEach(gate -> gate.setAccepting(true)); return true; }
        }
        values.forEach(gate -> gate.setAccepting(false));
        closeDatabase(db);
        return false;
    }
    void stop() {
        if (!stopped.compareAndSet(false, true)) return;
        epoch.incrementAndGet();
        java.util.List<CommandAcceptanceGate> currentGates; BootstrapCoordinator<?> currentBootstrap; ExecutorService currentExecutor; AutoCloseable currentDatabase; java.util.function.Supplier<? extends java.util.concurrent.CompletionStage<Void>> currentQuiesce;
        synchronized (lock) { currentGates = gates; currentBootstrap = bootstrap; currentExecutor = executor; currentDatabase = database; currentQuiesce=financialQuiesce; }
        currentGates.forEach(gate -> gate.setAccepting(false));
        if (currentBootstrap != null) currentBootstrap.stop();
        if(currentQuiesce!=null){try{java.util.concurrent.CompletionStage<Void> pending=currentQuiesce.get();if(pending!=null&&!pending.toCompletableFuture().isDone()){pending.whenComplete((v,e)->finishShutdown(currentExecutor,currentDatabase));return;}}catch(RuntimeException ignored){/* fall through: no external work was registered */}}
        finishShutdown(currentExecutor,currentDatabase);
    }
    private void finishShutdown(ExecutorService currentExecutor,AutoCloseable currentDatabase){if (currentExecutor == null || shutdown(currentExecutor)) closeDatabase(currentDatabase); else observeTerminationThenClose(currentExecutor,currentDatabase);}
    void closeDatabase(AutoCloseable value) {
        if (value != null && databaseClosed.compareAndSet(false, true)) try { value.close(); } catch (Exception e) { throw new IllegalStateException("could not close Bloeco-Stock database", e); }
    }
    private boolean shutdown(ExecutorService value) {
        value.shutdown();
        try { if (value.awaitTermination(5, TimeUnit.SECONDS)) return true; value.shutdownNow(); return value.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException e) { value.shutdownNow(); Thread.currentThread().interrupt(); return false; }
    }
    private void observeTerminationThenClose(ExecutorService value, AutoCloseable db) { Thread observer = new Thread(() -> { try { if (value.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)) closeDatabase(db); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); } }, "Bloeco-Stock-SQL-Termination"); observer.setDaemon(true); observer.start(); }
}
