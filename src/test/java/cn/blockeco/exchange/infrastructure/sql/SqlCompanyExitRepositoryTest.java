package cn.blockeco.exchange.infrastructure.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.governance.CompanyGovernanceAction;
import cn.blockeco.exchange.domain.governance.GovernanceActionState;
import cn.blockeco.exchange.domain.governance.GovernanceActionType;
import cn.blockeco.exchange.domain.governance.PayoutOperationState;
import cn.blockeco.exchange.domain.governance.CompanyPayoutOperation;
import cn.blockeco.exchange.ports.CompanyExitRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SqlCompanyExitRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-08-30T12:00:00Z");

    @Test
    void persistsAnImmutableGovernanceAnnouncementWithItsAuditFact() throws Exception {
        try (Fixture fixture = Fixture.create()) {
            CompanyExitRepository repository = new SqlCompanyExitRepository(fixture.database.dataSource());
            CompanyGovernanceAction action = fixture.action(GovernanceActionType.FOUNDER_CASH_OUT);

            fixture.database.inTransaction(connection -> {
                repository.createAction(connection, action, "创始人申请套现", NOW);
                return null;
            });

            assertThat(fixture.count("company_governance_actions")).isEqualTo(1);
            assertThat(fixture.count("company_announcements")).isEqualTo(1);
            assertThat(fixture.count("audit_events")).isEqualTo(1);
            assertThatThrownBy(() -> fixture.updateActionPayload(action.id()))
                    .isInstanceOf(Exception.class);
        }
    }

    @Test
    void changesGovernanceActionOnlyFromItsExpectedState() throws Exception {
        try (Fixture fixture = Fixture.create()) {
            CompanyExitRepository repository = new SqlCompanyExitRepository(fixture.database.dataSource());
            CompanyGovernanceAction action = fixture.action(GovernanceActionType.VOLUNTARY_DELIST);
            fixture.database.inTransaction(connection -> { repository.createAction(connection, action, "退市公告", NOW); return null; });

            boolean wrongState = fixture.database.inTransaction(connection -> repository.transitionAction(
                    connection, action.id(), GovernanceActionState.EXECUTION_READY, GovernanceActionState.EXECUTING, NOW));
            boolean expectedState = fixture.database.inTransaction(connection -> repository.transitionAction(
                    connection, action.id(), GovernanceActionState.ANNOUNCED, GovernanceActionState.EXECUTION_READY, NOW));

            assertThat(wrongState).isFalse();
            assertThat(expectedState).isTrue();
            assertThat(repository.findAction(action.id()).orElseThrow().state()).isEqualTo(GovernanceActionState.EXECUTION_READY);
        }
    }

    @Test
    void retainsUnknownPayoutForManualRecoveryAndRejectsDuplicateCorrelationKeys() throws Exception {
        try (Fixture fixture = Fixture.create()) {
            CompanyExitRepository repository = new SqlCompanyExitRepository(fixture.database.dataSource());
            CompanyGovernanceAction action = fixture.action(GovernanceActionType.FOUNDER_CASH_OUT);
            CompanyPayoutOperation payout = new CompanyPayoutOperation(UUID.randomUUID(), fixture.companyId, action.id(),
                    UUID.randomUUID(), 70, "vault:cashout:1", PayoutOperationState.PREPARED, NOW, NOW, null);
            fixture.database.inTransaction(connection -> { repository.createAction(connection, action, "套现公告", NOW); repository.createPayout(connection, payout); return null; });

            boolean moved = fixture.database.inTransaction(connection -> repository.transitionPayout(connection, payout.id(),
                    PayoutOperationState.PREPARED, PayoutOperationState.AMBIGUOUS, "Vault 超时", NOW));
            assertThat(moved).isTrue();
            assertThat(repository.recoverablePayouts(10)).extracting(CompanyPayoutOperation::id).containsExactly(payout.id());

            CompanyPayoutOperation duplicate = new CompanyPayoutOperation(UUID.randomUUID(), fixture.companyId, action.id(),
                    UUID.randomUUID(), 1, "vault:cashout:1", PayoutOperationState.PREPARED, NOW, NOW, null);
            assertThatThrownBy(() -> fixture.database.inTransaction(connection -> { repository.createPayout(connection, duplicate); return null; }))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void pagesActiveCompanyOrdersAndPersistsReleaseProgress() throws Exception {
        try (Fixture fixture = Fixture.create()) {
            CompanyExitRepository repository = new SqlCompanyExitRepository(fixture.database.dataSource());
            CompanyGovernanceAction action = fixture.action(GovernanceActionType.VOLUNTARY_DELIST);
            fixture.database.inTransaction(connection -> { repository.createAction(connection, action, "退市公告", NOW); fixture.insertOrder(connection, "00000000-0000-0000-0000-000000000021", 1); fixture.insertOrder(connection, "00000000-0000-0000-0000-000000000022", 2); return null; });

            List<UUID> firstPage = fixture.database.inTransaction(connection -> repository.activeOrderIds(connection, fixture.companyId, null, 1));
            fixture.database.inTransaction(connection -> { repository.recordOrderReleaseProgress(connection, action.id(), firstPage.getFirst(), 1, false, NOW); return null; });
            List<UUID> secondPage = fixture.database.inTransaction(connection -> repository.activeOrderIds(connection, fixture.companyId, firstPage.getFirst(), 2));

            assertThat(firstPage).hasSize(1);
            assertThat(secondPage).hasSize(1).doesNotContain(firstPage.getFirst());
            assertThat(repository.orderReleaseProgress(action.id()).orElseThrow().releasedOrders()).isEqualTo(1);
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final Path file; private final Database database; private final CompanyId companyId; private final UUID founder;
        private Fixture(Path file, Database database, CompanyId companyId, UUID founder) { this.file = file; this.database = database; this.companyId = companyId; this.founder = founder; }
        static Fixture create() throws Exception {
            Path file = Files.createTempFile("blockeco-exit-", ".db"); Database database = new Database("jdbc:sqlite:" + file); database.migrate();
            CompanyId company = new CompanyId(UUID.randomUUID()); UUID founder = UUID.randomUUID();
            database.inTransaction(connection -> {
                try (PreparedStatement statement = connection.prepareStatement("INSERT INTO companies VALUES (?, ?, ?, ?, 'LISTED', 0, 1000, 5000, ?, 0)")) {
                    statement.setString(1, company.value().toString()); statement.setString(2, "exit-company"); statement.setString(3, "Exit Company"); statement.setString(4, founder.toString()); statement.setString(5, NOW.toString()); statement.executeUpdate();
                }
                try (PreparedStatement account = connection.prepareStatement("INSERT INTO company_cash_accounts (company_id,cash_minor,paid_in_capital_minor,retained_earnings_minor,reserved_minor,accumulated_loss_minor) VALUES (?,100,100,0,0,0)")) { account.setString(1, company.value().toString()); account.executeUpdate(); }
                try (PreparedStatement listing = connection.prepareStatement("INSERT INTO stock_listings (company_id,stock_code,issue_reference_price_minor,issued_shares,listed_at) VALUES (?, 'EX000001', 1, 1000, ?)")) { listing.setString(1, company.value().toString()); listing.setString(2, NOW.toString()); listing.executeUpdate(); }
                return null;
            });
            return new Fixture(file, database, company, founder);
        }
        CompanyGovernanceAction action(GovernanceActionType type) { return new CompanyGovernanceAction(UUID.randomUUID(), companyId, founder, type, 70, 7, NOW, NOW.plusSeconds(43_200), GovernanceActionState.ANNOUNCED, "exit:action:" + UUID.randomUUID()); }
        void updateActionPayload(UUID id) throws Exception { try (Connection connection = database.dataSource().getConnection(); PreparedStatement statement = connection.prepareStatement("UPDATE company_governance_actions SET payload_json = '{}' WHERE id = ?")) { statement.setString(1, id.toString()); statement.executeUpdate(); } }
        void insertOrder(Connection connection, String id, long sequence) throws java.sql.SQLException { try (PreparedStatement statement = connection.prepareStatement("INSERT INTO stock_orders (id,company_id,stock_code,player_uuid,side,limit_price_minor,original_shares,remaining_shares,priority_sequence,reserved_cash_minor,filled_notional_minor,fee_charged_minor,fee_bps,accepted_at,state) VALUES (?,?,'EX000001',?,'SELL',1,1,1,?,0,0,0,0,?,'OPEN')")) { statement.setString(1, id); statement.setString(2, companyId.value().toString()); statement.setString(3, UUID.randomUUID().toString()); statement.setLong(4, sequence); statement.setString(5, NOW.toString()); statement.executeUpdate(); } }
        long count(String table) throws Exception { try (Connection connection = database.dataSource().getConnection(); PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM " + table); var rows = statement.executeQuery()) { rows.next(); return rows.getLong(1); } }
        @Override public void close() throws Exception { database.close(); Files.deleteIfExists(file); }
    }
}
