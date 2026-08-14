package cn.blockeco.exchange.application;

import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.finance.AssetBinding;
import cn.blockeco.exchange.domain.finance.AssetBindingState;
import cn.blockeco.exchange.ports.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

public final class AssetBindingService {
    private final AssetBindingRepository repository; private final TransactionRunner transactions; private final Map<String, CompanyAssetAdapter> adapters; private final AppClock clock;
    public AssetBindingService(AssetBindingRepository repository, TransactionRunner transactions, Collection<CompanyAssetAdapter> adapters, AppClock clock) { this.repository=repository; this.transactions=transactions; this.clock=clock; Map<String,CompanyAssetAdapter> map=new HashMap<>(); adapters.forEach(a->{ if(map.put(a.id(),a)!=null) throw new IllegalArgumentException("duplicate asset adapter"); }); this.adapters=Map.copyOf(map); }
    public CompletionStage<AssetBinding> bind(CompanyId companyId, UUID requester, String adapterId, String externalKey) { return CompletableFuture.supplyAsync(() -> { CompanyAssetAdapter adapter=Optional.ofNullable(adapters.get(adapterId)).orElseThrow(()->new IllegalArgumentException("asset adapter unavailable")); CompanyAssetAdapter.Verification verified=adapter.verify(requester, externalKey); if(!verified.ownedByRequester() || !requester.equals(verified.ownerId())) throw new IllegalArgumentException("asset ownership verification failed: "+verified.diagnostic()); AssetBinding binding=new AssetBinding(UUID.randomUUID(),companyId,adapterId,externalKey,requester,AssetBindingState.ACTIVE,clock.now()); transactions.inTransaction(c->{ repository.insertActive(c,binding); return null; }); return binding; }); }
    public CompletionStage<Long> activeCount(CompanyId companyId) { return CompletableFuture.supplyAsync(()->repository.activeCount(companyId)); }
}
