package cn.blockeco.exchange;

import cn.blockeco.exchange.ports.MainThreadExecutor;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Moves background bootstrap completion back to the primary thread exactly once. */
final class BootstrapCoordinator<T> {
    private final MainThreadExecutor main; private final Consumer<T> ready; private final Consumer<Throwable> failed;
    private final AtomicBoolean accepting = new AtomicBoolean(); private final AtomicBoolean stopped = new AtomicBoolean();
    BootstrapCoordinator(MainThreadExecutor main, Consumer<T> ready, Consumer<Throwable> failed) { this.main=main; this.ready=ready; this.failed=failed; }
    void coordinate(CompletionStage<T> background) { background.whenComplete((value, error) -> main.submit(() -> { if (error != null) { if (stopped.compareAndSet(false, true)) failed.accept(error); return null; } if (!stopped.get()) { ready.accept(value); accepting.set(true); } return null; })); }
    boolean accepting() { return accepting.get(); }
    void stop() { accepting.set(false); stopped.set(true); }
}
