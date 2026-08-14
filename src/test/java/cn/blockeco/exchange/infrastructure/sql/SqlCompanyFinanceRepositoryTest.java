package cn.blockeco.exchange.infrastructure.sql;

import static org.assertj.core.api.Assertions.assertThat;

import cn.blockeco.exchange.domain.audit.AuditEvent;
import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.finance.CompanyCashAccount;
import cn.blockeco.exchange.domain.finance.ShareHolding;
import cn.blockeco.exchange.domain.finance.TreasuryOperation;
import cn.blockeco.exchange.domain.finance.TreasuryOperationState;
import cn.blockeco.exchange.domain.money.Money;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SqlCompanyFinanceRepositoryTest {
    @Test
    void stores_capitalization_and_returns_unsettled_operations() throws Exception {
        var file = Files.createTempFile("blockstock-finance-", ".db");
        try (Database database = new Database("jdbc:sqlite:" + file)) {
            database.migrate();
            var repository = new SqlCompanyFinanceRepository(database.dataSource());
            var companyId = new CompanyId(UUID.randomUUID()); var player = UUID.randomUUID(); var operationId = UUID.randomUUID(); var now = Instant.parse("2026-08-14T12:00:00Z");
            database.inTransaction(c -> { try (var s = c.prepareStatement("INSERT INTO companies (id, normalized_name, display_name, founder_uuid, status, treasury_minor, total_shares, dividend_basis_points, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) { s.setString(1, companyId.value().toString()); s.setString(2, "finance guild"); s.setString(3, "Finance Guild"); s.setString(4, player.toString()); s.setString(5, "PENDING_ASSET_BINDING"); s.setLong(6, 7); s.setLong(7, 1_000); s.setInt(8, 5_000); s.setString(9, now.toString()); s.executeUpdate(); } return null; });
            TreasuryOperation operation = new TreasuryOperation(operationId, companyId, player, Money.ofMinor(7), operationId.toString(), TreasuryOperationState.ESCROW_DEPOSITED, now, now);
            database.inTransaction(c -> { repository.prepare(c, new TreasuryOperation(operationId, companyId, player, Money.ofMinor(7), operationId.toString(), TreasuryOperationState.PREPARED, now, now), new AuditEvent(UUID.randomUUID(), Optional.of(companyId), Optional.of(player), "COMPANY_CAPITALIZATION_PREPARED", Map.of("operationId", operationId.toString()), now)); repository.transition(c, operationId, TreasuryOperationState.PREPARED, TreasuryOperationState.PLAYER_WITHDRAWN, new AuditEvent(UUID.randomUUID(), Optional.of(companyId), Optional.of(player), "COMPANY_CAPITALIZATION_PLAYER_WITHDRAWN", Map.of("operationId", operationId.toString()), now)); repository.transition(c, operationId, TreasuryOperationState.PLAYER_WITHDRAWN, TreasuryOperationState.ESCROW_DEPOSITED, new AuditEvent(UUID.randomUUID(), Optional.of(companyId), Optional.of(player), "COMPANY_CAPITALIZATION_ESCROW_DEPOSITED", Map.of("operationId", operationId.toString()), now)); repository.createCapitalization(c, new CompanyCashAccount(companyId, Money.ofMinor(7), Money.ofMinor(7), Money.zero(), Money.zero()), new ShareHolding(companyId, player, 1_000, 0), operation, new AuditEvent(UUID.randomUUID(), Optional.of(companyId), Optional.of(player), "COMPANY_CAPITALIZATION_COMPLETED", Map.of("operationId", operationId.toString()), now)); return null; });
            assertThat(repository.findUnsettledOperations()).isEmpty();
        } finally { Files.deleteIfExists(file); }
    }

    @Test
    void exposes_ambiguous_capitalization_with_audited_reason_for_administrator_recovery() throws Exception {
        var file = Files.createTempFile("blockstock-finance-ambiguous-", ".db");
        try (Database database = new Database("jdbc:sqlite:" + file)) {
            database.migrate(); var repository = new SqlCompanyFinanceRepository(database.dataSource());
            var companyId = new CompanyId(UUID.randomUUID()); var player = UUID.randomUUID(); var operationId = UUID.randomUUID(); var now = Instant.parse("2026-08-14T12:00:00Z");
            database.inTransaction(c -> { try (var s = c.prepareStatement("INSERT INTO companies (id, normalized_name, display_name, founder_uuid, status, treasury_minor, total_shares, dividend_basis_points, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) { s.setString(1, companyId.value().toString()); s.setString(2, "ambiguous guild"); s.setString(3, "Ambiguous Guild"); s.setString(4, player.toString()); s.setString(5, "PENDING_ASSET_BINDING"); s.setLong(6, 12); s.setLong(7, 1_000); s.setInt(8, 5_000); s.setString(9, now.toString()); s.executeUpdate(); } var operation = new TreasuryOperation(operationId, companyId, player, Money.ofMinor(12), operationId.toString(), TreasuryOperationState.PREPARED, now, now); repository.prepare(c, operation, new AuditEvent(UUID.randomUUID(), Optional.of(companyId), Optional.of(player), "COMPANY_CAPITALIZATION_PREPARED", Map.of("operationId", operationId.toString()), now)); repository.transition(c, operationId, TreasuryOperationState.PREPARED, TreasuryOperationState.AMBIGUOUS, new AuditEvent(UUID.randomUUID(), Optional.of(companyId), Optional.of(player), "COMPANY_CAPITALIZATION_AMBIGUOUS", Map.of("operationId", operationId.toString(), "reason", "Vault response timeout"), now)); return null; });

            assertThat(repository.findAmbiguousCapitalizations()).singleElement().satisfies(record -> {
                assertThat(record.operation().id()).isEqualTo(operationId);
                assertThat(record.operation().companyId()).isEqualTo(companyId);
                assertThat(record.operation().playerId()).isEqualTo(player);
                assertThat(record.operation().amount()).isEqualTo(Money.ofMinor(12));
                assertThat(record.operation().state()).isEqualTo(TreasuryOperationState.AMBIGUOUS);
                assertThat(record.reason()).isEqualTo("Vault response timeout");
            });
        } finally { Files.deleteIfExists(file); }
    }
}
