package cn.blockeco.exchange.application;

import static org.assertj.core.api.Assertions.assertThat;

import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.finance.NativeAsset;
import cn.blockeco.exchange.ports.NativeAssetRepository;
import cn.blockeco.exchange.ports.TransactionRunner;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;

class NativeAssetServiceTest {
    @Test void native_asset_belongs_only_to_the_company_founder() {
        UUID founder = UUID.randomUUID();
        NativeAssetService service = new NativeAssetService(new MemoryAssets(), directTransactions(), Runnable::run, () -> Instant.parse("2026-08-23T00:00:00Z"));

        NativeAsset asset = service.create(new CompanyId(UUID.randomUUID()), founder, "红石工厂").toCompletableFuture().join();

        assertThat(service.verify(founder, asset.externalKey()).ownedByRequester()).isTrue();
        assertThat(service.verify(UUID.randomUUID(), asset.externalKey()).ownedByRequester()).isFalse();
    }

    private static TransactionRunner directTransactions() { return new TransactionRunner() { @Override public <T> T inTransaction(SqlWork<T> work) { try { return work.execute(null); } catch (Exception e) { throw new IllegalStateException(e); } } }; }
    private static final class MemoryAssets implements NativeAssetRepository {
        private final Map<UUID, NativeAsset> values = new java.util.HashMap<>();
        @Override public void insert(java.sql.Connection connection, NativeAsset asset) { values.put(asset.id(), asset); }
        @Override public Optional<NativeAsset> find(UUID id) { return Optional.ofNullable(values.get(id)); }
    }
}
