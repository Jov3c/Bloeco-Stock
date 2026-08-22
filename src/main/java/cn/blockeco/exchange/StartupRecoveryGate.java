package cn.blockeco.exchange;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.Function;
import java.util.function.BiConsumer;

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

    /** Runs database-only recoveries in order; readiness is published only after both succeed. */
    <C, I> void start(String preflightFailure, Supplier<? extends CompletionStage<C>> capitalizations,
                      Function<C, ? extends CompletionStage<I>> ipoSubscriptions, BiConsumer<C, I> onRecovered) {
        if (preflightFailure != null) { fail("托管账户启动前检查失败：" + preflightFailure); return; }
        try {
            capitalizations.get().whenComplete((capitalization, capitalizationFailure) -> {
                if (capitalizationFailure != null) { fail("遗留公司资本恢复失败：" + detail(capitalizationFailure)); return; }
                try {
                    ipoSubscriptions.apply(capitalization).whenComplete((ipo, ipoFailure) -> {
                        if (ipoFailure != null) { fail("IPO 认购恢复失败：" + detail(ipoFailure)); return; }
                        try { onRecovered.accept(capitalization, ipo); ready.set(true); }
                        catch (RuntimeException callbackFailure) { fail("启动就绪处理失败：" + detail(callbackFailure)); }
                    });
                } catch (RuntimeException startFailure) { fail("IPO 认购恢复无法启动：" + detail(startFailure)); }
            });
        } catch (RuntimeException startFailure) { fail("遗留公司资本恢复无法启动：" + detail(startFailure)); }
    }

    /** Extends recovery without publishing readiness between local-only recovery phases. */
    <C, I, S> void start(String preflightFailure, Supplier<? extends CompletionStage<C>> capitalizations,
                         Function<C, ? extends CompletionStage<I>> ipoSubscriptions,
                         Function<I, ? extends CompletionStage<S>> secondary,
                         java.util.function.Consumer<S> onRecovered) {
        if (preflightFailure != null) { fail("托管账户启动前检查失败：" + preflightFailure); return; }
        try { capitalizations.get().whenComplete((c,cf) -> { if(cf!=null){fail("遗留公司资本恢复失败："+detail(cf));return;} try { ipoSubscriptions.apply(c).whenComplete((i,ifail)->{if(ifail!=null){fail("IPO 认购恢复失败："+detail(ifail));return;} try { secondary.apply(i).whenComplete((s,sfail)->{if(sfail!=null){fail("证券现金恢复失败："+detail(sfail));return;} try {onRecovered.accept(s);ready.set(true);}catch(RuntimeException e){fail("启动就绪处理失败："+detail(e));}});}catch(RuntimeException e){fail("证券现金恢复无法启动："+detail(e));}});}catch(RuntimeException e){fail("IPO 认购恢复无法启动："+detail(e));}}); } catch(RuntimeException e){fail("遗留公司资本恢复无法启动："+detail(e));}
    }

    /** Full async startup chain; readiness is published only after the final symbol stage completes. */
    <C,I,S,B> void startFull(String preflightFailure, Supplier<? extends CompletionStage<C>> capitalizations,
            Function<C,? extends CompletionStage<I>> ipo, Function<I,? extends CompletionStage<S>> secondary,
            Function<S,? extends CompletionStage<B>> balanceAndReconcile,
            Function<B,? extends CompletionStage<Void>> symbols, Consumer<B> onReady) {
        if(preflightFailure!=null){fail("托管账户启动前检查失败："+preflightFailure);return;}
        CompletionStage<B> chain;
        try { chain=capitalizations.get().thenCompose(ipo).thenCompose(secondary).thenCompose(balanceAndReconcile); }
        catch(RuntimeException failure){fail("启动恢复无法启动："+detail(failure));return;}
        chain.thenCompose(snapshot->{try{return symbols.apply(snapshot).thenApply(v->snapshot);}catch(RuntimeException failure){return java.util.concurrent.CompletableFuture.failedFuture(failure);}})
            .whenComplete((snapshot,failure)->{if(failure!=null){fail("启动恢复失败："+detail(failure));return;}try{onReady.accept(snapshot);ready.set(true);}catch(RuntimeException callbackFailure){fail("启动就绪处理失败："+detail(callbackFailure));}});
    }

    boolean ready() { return ready.get(); }
    boolean accepting() { return ready.get() && failure == null; }
    String failure() { return failure; }
    private void fail(String value) { failure = value; failed.accept(value); }
    private static String detail(Throwable failure) { return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage(); }
}
