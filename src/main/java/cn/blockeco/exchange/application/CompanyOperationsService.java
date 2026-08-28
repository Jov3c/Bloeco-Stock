package cn.blockeco.exchange.application;

import cn.blockeco.exchange.domain.finance.AssetBinding;
import cn.blockeco.exchange.domain.finance.VerifiedOperatingEvent;
import cn.blockeco.exchange.ports.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

public final class CompanyOperationsService {
    private final AssetBindingRepository bindings; private final CompanyOperationsRepository operations; private final TransactionRunner transactions;
    private final Supplier<? extends Collection<CompanyOperatingEventSource>> sources; private final AppClock clock; private final MainThreadExecutor mainThread; private final Executor sqlExecutor;

    public CompanyOperationsService(AssetBindingRepository bindings, CompanyOperationsRepository operations, TransactionRunner transactions, Collection<CompanyOperatingEventSource> sources, AppClock clock) { this(bindings, operations, transactions, () -> sources, clock, null); }
    public CompanyOperationsService(AssetBindingRepository bindings, CompanyOperationsRepository operations, TransactionRunner transactions, Supplier<? extends Collection<CompanyOperatingEventSource>> sources, AppClock clock, MainThreadExecutor mainThread) { this(bindings,operations,transactions,sources,clock,mainThread,ForkJoinPool.commonPool()); }
    public CompanyOperationsService(AssetBindingRepository bindings, CompanyOperationsRepository operations, TransactionRunner transactions, Supplier<? extends Collection<CompanyOperatingEventSource>> sources, AppClock clock, MainThreadExecutor mainThread, Executor sqlExecutor) {
        this.bindings=Objects.requireNonNull(bindings); this.operations=Objects.requireNonNull(operations); this.transactions=Objects.requireNonNull(transactions); this.sources=Objects.requireNonNull(sources); this.clock=Objects.requireNonNull(clock); this.mainThread=mainThread; this.sqlExecutor=Objects.requireNonNull(sqlExecutor);
    }

    public CompletionStage<IngestionResult> ingestDueEvents() {
        Instant through = clock.now();
        return CompletableFuture.supplyAsync(bindings::allActive).thenCompose(active -> {
            Map<String, CompanyOperatingEventSource> byAdapter = new HashMap<>();
            for (CompanyOperatingEventSource source : sources.get()) byAdapter.putIfAbsent(source.adapterId(), source);
            MutableResult result = new MutableResult(); CompletionStage<MutableResult> chain = CompletableFuture.completedFuture(result);
            for (AssetBinding binding : active) {
                CompanyOperatingEventSource source = byAdapter.get(binding.adapterId());
                if (source != null) chain = chain.thenCompose(current -> ingestBinding(source, binding, through, current));
            }
            return chain.thenApply(MutableResult::freeze);
        });
    }

    private CompletionStage<MutableResult> ingestBinding(CompanyOperatingEventSource source, AssetBinding binding, Instant through, MutableResult result) {
        Supplier<List<VerifiedOperatingEvent>> read = () -> source.readSince(binding, Instant.EPOCH, through);
        CompletionStage<List<VerifiedOperatingEvent>> events = mainThread == null ? CompletableFuture.supplyAsync(read) : mainThread.submit(read);
        return events.handle((readEvents, failure) -> {
            if (failure != null) { result.sourceFailures++; return result; }
            CompletableFuture.runAsync(() -> { for (VerifiedOperatingEvent event : readEvents) record(binding, source, event, through, result); }, sqlExecutor).join();
            return result;
        });
    }

    private void record(AssetBinding binding, CompanyOperatingEventSource source, VerifiedOperatingEvent event, Instant through, MutableResult result) {
        if (event == null || !source.adapterId().equals(binding.adapterId()) || !binding.adapterId().equals(event.adapterId()) || event.externalEventKey().isBlank() || event.amount() <= 0 || event.occurredAt().isAfter(through)) { result.rejected++; return; }
        try {
            CompanyOperationsRepository.RecordResult recorded = transactions.inTransaction(connection -> operations.record(connection, binding, event, through));
            if (recorded == CompanyOperationsRepository.RecordResult.RECORDED) result.accepted++; else result.duplicate++;
        } catch (RuntimeException failure) { result.rejected++; }
    }

    public record IngestionResult(long accepted, long duplicate, long rejected, long sourceFailures) { }
    private static final class MutableResult { long accepted, duplicate, rejected, sourceFailures; IngestionResult freeze() { return new IngestionResult(accepted, duplicate, rejected, sourceFailures); } }
}
