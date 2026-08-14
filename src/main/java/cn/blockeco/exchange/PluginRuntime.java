package cn.blockeco.exchange;

import cn.blockeco.exchange.paper.CompanyCommand;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns ready runtime shutdown ordering. */
final class PluginRuntime {
    private final CompanyCommand command; private final BootstrapCoordinator<?> bootstrap; private final ExecutorService executor; private final AutoCloseable database; private final AtomicBoolean stopped = new AtomicBoolean();
    PluginRuntime(CompanyCommand command, BootstrapCoordinator<?> bootstrap, ExecutorService executor, AutoCloseable database) { this.command=command; this.bootstrap=bootstrap; this.executor=executor; this.database=database; }
    void stop() { if (!stopped.compareAndSet(false, true)) return; command.setAccepting(false); bootstrap.stop(); executor.shutdown(); try { executor.awaitTermination(5, TimeUnit.SECONDS); database.close(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); close(); } catch (Exception e) { throw new IllegalStateException("could not close Blockeco runtime", e); } }
    private void close() { try { database.close(); } catch (Exception ignored) { } }
}
