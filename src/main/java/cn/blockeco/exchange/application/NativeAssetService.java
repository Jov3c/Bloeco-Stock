package cn.blockeco.exchange.application;

import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.finance.NativeAsset;
import cn.blockeco.exchange.ports.AppClock;
import cn.blockeco.exchange.ports.AssetCatalogAdapter;
import cn.blockeco.exchange.ports.NativeAssetRepository;
import cn.blockeco.exchange.ports.TransactionRunner;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/** Durable native assets and their ownership verification adapter. */
public final class NativeAssetService implements AssetCatalogAdapter {
    public static final String ADAPTER_ID = "blockstock-native";
    private final NativeAssetRepository assets; private final TransactionRunner transactions; private final Executor executor; private final AppClock clock;
    public NativeAssetService(NativeAssetRepository assets, TransactionRunner transactions, Executor executor, AppClock clock) { this.assets=Objects.requireNonNull(assets);this.transactions=Objects.requireNonNull(transactions);this.executor=Objects.requireNonNull(executor);this.clock=Objects.requireNonNull(clock); }
    public CompletionStage<NativeAsset> create(CompanyId company, UUID founder, String name) { return CompletableFuture.supplyAsync(()->{ NativeAsset asset=new NativeAsset(UUID.randomUUID(),company,founder,name.trim(),clock.now());transactions.inTransaction(c->{assets.insert(c,asset);return null;});return asset;},executor); }
    @Override public String id() { return ADAPTER_ID; }
    @Override public java.util.List<AssetChoice> listOwned(UUID requester, String search, int limit) { String needle=search==null?"":search.trim().toLowerCase(java.util.Locale.ROOT); return assets.listOwned(requester,Math.max(1,Math.min(45,limit))).stream().filter(asset->asset.name().toLowerCase(java.util.Locale.ROOT).contains(needle)).map(asset->new AssetChoice(asset.externalKey(),asset.name(),"原生经营资产（自动收益未接入）")).toList(); }
    @Override public Verification verify(UUID requester, String externalKey) { try { NativeAsset asset=assets.find(UUID.fromString(externalKey)).orElse(null); return asset!=null&&asset.founderId().equals(requester)?new Verification(true,asset.founderId(),""):new Verification(false,null,"native asset is not owned by requester"); } catch(IllegalArgumentException ignored) { return new Verification(false,null,"invalid native asset key"); } }
}
