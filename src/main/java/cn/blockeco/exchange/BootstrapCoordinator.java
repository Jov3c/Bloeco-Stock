package cn.blockeco.exchange;

import cn.blockeco.exchange.ports.MainThreadExecutor;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

/** Moves background bootstrap completion back to the primary thread exactly once. */
final class BootstrapCoordinator<T> {
    private final MainThreadExecutor main; private final Function<T, Boolean> ready; private final Consumer<Throwable> failed; private final Consumer<T> cleanup;
    private final AtomicBoolean accepting = new AtomicBoolean(); private final AtomicBoolean stopped = new AtomicBoolean(); private final AtomicBoolean terminal = new AtomicBoolean(); private final AtomicBoolean cleaned = new AtomicBoolean();
    BootstrapCoordinator(MainThreadExecutor main, Function<T, Boolean> ready, Consumer<Throwable> failed, Consumer<T> cleanup) { this.main=main; this.ready=ready; this.failed=failed; this.cleanup=cleanup; }
    void coordinate(CompletionStage<T> background) { background.whenComplete((value, error) -> { if (stopped.get()) { clean(value); return; } main.submit(() -> { synchronized (this) { if (error != null) { fail(error); return null; } try { if (stopped.get() || !ready.apply(value)) { clean(value); if (!stopped.get()) fail(new IllegalStateException("bootstrap wiring failed")); } else accepting.set(true); } catch (Throwable failure) { clean(value); fail(failure); } } return null; }); }); }
    boolean accepting() { return accepting.get(); }
    synchronized void stop() { accepting.set(false); stopped.set(true); }
    private void fail(Throwable error) { accepting.set(false); if (terminal.compareAndSet(false, true)) failed.accept(error); }
    private void clean(T value) { if (value != null && cleaned.compareAndSet(false, true)) cleanup.accept(value); }
}
