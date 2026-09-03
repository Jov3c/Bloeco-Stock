package cn.blockeco.exchange.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.finance.AssetBindingState;
import cn.blockeco.exchange.infrastructure.sql.Database;
import cn.blockeco.exchange.infrastructure.sql.SqlAssetBindingRepository;
import cn.blockeco.exchange.ports.CompanyAssetAdapter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class AssetBindingServiceTest {
    @Test void verifies_provider_ownership_on_the_injected_main_thread() throws Exception {
        Path file = Files.createTempFile("asset-binding-thread-", ".db");
        try (Database db = new Database("jdbc:sqlite:" + file)) {
            db.migrate(); CompanyId company = Fixtures.company(db, 100_000); UUID owner = UUID.randomUUID();
            AtomicBoolean providerThread = new AtomicBoolean(false);
            CompanyAssetAdapter adapter = new CompanyAssetAdapter() {
                public String id() { return "test"; }
                public Verification verify(UUID requester, String key) {
                    if (!providerThread.get()) throw new IllegalStateException("provider API called off main thread");
                    return new Verification(true, owner, "ok");
                }
            };
            var main = new cn.blockeco.exchange.ports.MainThreadExecutor() {
                @Override public <T> java.util.concurrent.CompletionStage<T> submit(java.util.function.Supplier<T> work) {
                    providerThread.set(true);
                    try { return CompletableFuture.completedFuture(work.get()); }
                    catch (RuntimeException failure) { return CompletableFuture.failedFuture(failure); }
                    finally { providerThread.set(false); }
                }
            };
            AssetBindingService service = new AssetBindingService(new SqlAssetBindingRepository(db.dataSource()), db,
                    List.of(adapter), () -> Instant.parse("2026-08-14T12:00:00Z"), main);

            assertThat(service.bind(company, owner, "test", "mine").toCompletableFuture().join().state()).isEqualTo(AssetBindingState.ACTIVE);
        } finally { Files.deleteIfExists(file); }
    }

    @Test void binds_only_assets_verified_for_the_requester() throws Exception {
        Path file = Files.createTempFile("asset-binding-", ".db");
        try (Database db = new Database("jdbc:sqlite:" + file)) {
            db.migrate(); CompanyId company = Fixtures.company(db, 100_000);
            UUID owner = UUID.randomUUID();
            CompanyAssetAdapter adapter = new CompanyAssetAdapter() {
                public String id() { return "test"; }
                public Verification verify(UUID requester, String key) { return new Verification(key.equals("mine"), owner, "not owner"); }
            };
            AssetBindingService service = new AssetBindingService(new SqlAssetBindingRepository(db.dataSource()), db, List.of(adapter), () -> Instant.parse("2026-08-14T12:00:00Z"));

            assertThatThrownBy(() -> service.bind(company, UUID.randomUUID(), "test", "mine").toCompletableFuture().join()).hasCauseInstanceOf(IllegalArgumentException.class);
            assertThat(service.bind(company, owner, "test", "mine").toCompletableFuture().join().state()).isEqualTo(AssetBindingState.ACTIVE);
            assertThat(service.activeCount(company).toCompletableFuture().join()).isEqualTo(1);
        } finally { Files.deleteIfExists(file); }
    }
}
