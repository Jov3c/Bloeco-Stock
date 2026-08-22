package cn.blockeco.exchange;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Keeps command acceptance closed until escrow preflight and recovery have both completed. */
final class StartupRecoveryGate {
    private final AtomicBoolean ready = new AtomicBoolean();
    private final Consumer<String> failed;
    private volatile String failure;

    StartupRecoveryGate() { this(ignored -> { }); }
    StartupRecoveryGate(Consumer<String> failed) { this.failed = failed; }

    void start(String preflightFailure, Supplier<? extends CompletionStage<Integer>> recovery) {
        start(preflightFailure, recovery, ignored -> { });
    }

    void start(String preflightFailure, Supplier<? extends CompletionStage<Integer>> recovery, Consumer<Integer> onRecovered) {
        if (preflightFailure != null) { fail("托管账户启动前检查失败：" + preflightFailure); return; }
        try {
            recovery.get().whenComplete((recovered, error) -> {
                if (error != null) { fail("遗留公司资本恢复失败：" + detail(error)); return; }
                try { onRecovered.accept(recovered); ready.set(true); }
                catch (RuntimeException callbackFailure) { fail("启动就绪处理失败：" + detail(callbackFailure)); }
            });
        } catch (RuntimeException startFailure) { fail("遗留公司资本恢复无法启动：" + detail(startFailure)); }
    }

    boolean ready() { return ready.get(); }
    boolean accepting() { return ready.get() && failure == null; }
    String failure() { return failure; }
    private void fail(String value) { failure = value; failed.accept(value); }
    private static String detail(Throwable failure) { return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage(); }
}
