package cn.blockeco.exchange.application;

import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.finance.AssetBinding;
import cn.blockeco.exchange.domain.finance.AssetBindingState;
import cn.blockeco.exchange.ports.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

public final class AssetBindingService {
    private final AssetBindingRepository repository; private final TransactionRunner transactions; private final Supplier<? extends Collection<CompanyAssetAdapter>> adapterLookup; private final AppClock clock; private final MainThreadExecutor providerThread;
    public AssetBindingService(AssetBindingRepository repository, TransactionRunner transactions, Collection<CompanyAssetAdapter> adapters, AppClock clock) { this(repository, transactions, fixedLookup(adapters), clock, null); }
    public AssetBindingService(AssetBindingRepository repository, TransactionRunner transactions, Supplier<? extends Collection<CompanyAssetAdapter>> adapterLookup, AppClock clock) { this(repository, transactions, adapterLookup, clock, null); }
    public AssetBindingService(AssetBindingRepository repository, TransactionRunner transactions, Collection<CompanyAssetAdapter> adapters, AppClock clock, MainThreadExecutor providerThread) { this(repository, transactions, fixedLookup(adapters), clock, providerThread); }
    public AssetBindingService(AssetBindingRepository repository, TransactionRunner transactions, Supplier<? extends Collection<CompanyAssetAdapter>> adapterLookup, AppClock clock, MainThreadExecutor providerThread) { this.repository=repository; this.transactions=transactions; this.adapterLookup=adapterLookup; this.clock=clock; this.providerThread=providerThread; }
    private static Supplier<Collection<CompanyAssetAdapter>> fixedLookup(Collection<CompanyAssetAdapter> adapters) { Map<String,CompanyAssetAdapter> map=new HashMap<>(); adapters.forEach(a->{ if(map.put(a.id(),a)!=null) throw new IllegalArgumentException("duplicate asset adapter"); }); Collection<CompanyAssetAdapter> fixed=List.copyOf(map.values()); return () -> fixed; }
    public CompletionStage<AssetBinding> bind(CompanyId companyId, UUID requester, String adapterId, String externalKey) {
        java.util.function.Supplier<CompanyAssetAdapter.Verification> verify = () -> {
            CompanyAssetAdapter adapter=adapterLookup.get().stream().filter(candidate -> candidate.id().equals(adapterId)).findFirst().orElseThrow(()->new IllegalArgumentException("asset adapter unavailable"));
            return adapter.verify(requester, externalKey);
        };
        CompletionStage<CompanyAssetAdapter.Verification> verification = providerThread == null
                ? CompletableFuture.supplyAsync(verify) : providerThread.submit(verify);
        return verification.thenCompose(verified -> CompletableFuture.supplyAsync(() -> {
            if(!verified.ownedByRequester() || !requester.equals(verified.ownerId())) throw new IllegalArgumentException("asset ownership verification failed: "+verified.diagnostic());
            AssetBinding binding=new AssetBinding(UUID.randomUUID(),companyId,adapterId,externalKey,requester,AssetBindingState.ACTIVE,clock.now());
            transactions.inTransaction(c->{ repository.insertActive(c,binding); return null; }); return binding;
        }));
    }
    public CompletionStage<Long> activeCount(CompanyId companyId) { return CompletableFuture.supplyAsync(()->repository.activeCount(companyId)); }
}
