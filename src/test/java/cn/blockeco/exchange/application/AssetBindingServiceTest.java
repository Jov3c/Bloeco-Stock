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
import org.junit.jupiter.api.Test;

class AssetBindingServiceTest {
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
