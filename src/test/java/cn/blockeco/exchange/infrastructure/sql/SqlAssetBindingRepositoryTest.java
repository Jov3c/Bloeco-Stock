package cn.blockeco.exchange.infrastructure.sql;

import static org.assertj.core.api.Assertions.assertThat;

import cn.blockeco.exchange.application.Fixtures;
import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.finance.AssetBinding;
import cn.blockeco.exchange.domain.finance.AssetBindingState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SqlAssetBindingRepositoryTest {
    @Test void allActiveExcludesInactiveBindingsAndOrdersByCreationThenId() throws Exception {
        Path file = Files.createTempFile("active-bindings-", ".db");
        try (Database database = new Database("jdbc:sqlite:" + file)) {
            database.migrate(); CompanyId company = Fixtures.company(database, 100); SqlAssetBindingRepository repository = new SqlAssetBindingRepository(database.dataSource());
            AssetBinding first = binding(company, "first", AssetBindingState.ACTIVE, "00000000-0000-0000-0000-000000000002", "2026-08-28T10:00:00Z");
            AssetBinding second = binding(company, "second", AssetBindingState.ACTIVE, "00000000-0000-0000-0000-000000000003", "2026-08-28T10:00:00Z");
            AssetBinding revoked = binding(company, "revoked", AssetBindingState.REVOKED, "00000000-0000-0000-0000-000000000001", "2026-08-28T09:00:00Z");
            AssetBinding pending = binding(company, "pending", AssetBindingState.PENDING, "00000000-0000-0000-0000-000000000004", "2026-08-28T09:00:00Z");
            database.inTransaction(connection -> { repository.insertActive(connection, second); repository.insertActive(connection, pending); repository.insertActive(connection, first); repository.insertActive(connection, revoked); return null; });

            assertThat(repository.allActive()).containsExactly(first, second);
        } finally { Files.deleteIfExists(file); }
    }

    private static AssetBinding binding(CompanyId company, String adapter, AssetBindingState state, String id, String createdAt) {
        return new AssetBinding(UUID.fromString(id), company, adapter, adapter + "-key", UUID.randomUUID(), state, Instant.parse(createdAt));
    }
}
