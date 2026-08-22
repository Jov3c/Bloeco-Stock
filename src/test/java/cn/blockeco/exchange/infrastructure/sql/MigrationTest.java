package cn.blockeco.exchange.infrastructure.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MigrationTest {

    @Test
    void published_v001_fixture_is_byte_identical_to_the_current_v001_migration() throws Exception {
        assertThat(MigrationTest.class.getResourceAsStream("/legacy/V001-published.sql").readAllBytes())
                .isEqualTo(MigrationTest.class.getResourceAsStream("/db/migration/V001.sql").readAllBytes());
    }

    @Test
    void splits_sqlite_scripts_without_breaking_literals_comments_or_triggers() {
        String script = "-- ; comment\nCREATE TABLE t(v TEXT); /* ; block */ "
                + "INSERT INTO t VALUES ('a;''b'); INSERT INTO t VALUES (\"c;\"\"d\"); "
                + "CREATE TRIGGER tr AFTER INSERT ON t BEGIN INSERT INTO t VALUES ('trigger;'); UPDATE t SET v='x'; END;";

        assertThat(Database.splitStatements(script)).containsExactly(
                "-- ; comment\nCREATE TABLE t(v TEXT)",
                " /* ; block */ INSERT INTO t VALUES ('a;''b')",
                " INSERT INTO t VALUES (\"c;\"\"d\")",
                " CREATE TRIGGER tr AFTER INSERT ON t BEGIN INSERT INTO t VALUES ('trigger;'); UPDATE t SET v='x'; END");
    }

    @Test
    void upgrades_old_v001_without_changing_its_checksum_and_preserves_saga_data() throws Exception {
        Path file = Files.createTempFile("blockeco-v001-upgrade-", ".db");
        try (Database database = new Database("jdbc:sqlite:" + file)) {
            applyOldV001(database);
            try (Connection connection = database.dataSource().getConnection(); PreparedStatement statement = connection.prepareStatement("INSERT INTO registration_sagas VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                statement.setString(1, "11111111-1111-1111-1111-111111111111"); statement.setString(2, "22222222-2222-2222-2222-222222222222"); statement.setString(3, "old name"); statement.setLong(4, 1); statement.setString(5, "REFUNDED"); statement.setString(6, null); statement.setString(7, "2026-01-01T00:00:00Z"); statement.setString(8, "2026-01-01T00:00:00Z"); statement.executeUpdate();
            }
            database.migrate();
            try (Connection connection = database.dataSource().getConnection()) {
                assertThat(historyRows(connection, "V001")).isEqualTo(1);
                assertThat(historyRows(connection, "V002")).isEqualTo(1);
                assertThat(sagaRows(connection)).isEqualTo(1);
                try (PreparedStatement statement = connection.prepareStatement("INSERT INTO registration_sagas (id, founder_uuid, company_normalized_name, total_withdrawal_minor, state, error_message, created_at, updated_at) VALUES ('33333333-3333-3333-3333-333333333333','44444444-4444-4444-4444-444444444444','reserved',1,'REJECTED',NULL,'2026-01-01T00:00:00Z','2026-01-01T00:00:00Z')")) { statement.executeUpdate(); }
            }
        } finally { Files.deleteIfExists(file); }
    }

    @Test
    void refuses_v002_upgrade_with_duplicate_active_names_without_partially_migrating() throws Exception {
        Path file = Files.createTempFile("blockeco-v001-conflict-", ".db");
        try (Database database = new Database("jdbc:sqlite:" + file)) {
            applyOldV001(database);
            try (Connection connection = database.dataSource().getConnection(); PreparedStatement statement = connection.prepareStatement("INSERT INTO registration_sagas VALUES (?, ?, 'duplicate', 1, 'PREPARED', NULL, '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z')")) {
                for (String id : java.util.List.of("11111111-1111-1111-1111-111111111111", "33333333-3333-3333-3333-333333333333")) { statement.setString(1, id); statement.setString(2, "22222222-2222-2222-2222-222222222222"); statement.executeUpdate(); }
            }
            org.assertj.core.api.Assertions.assertThatThrownBy(database::migrate).isInstanceOf(IllegalStateException.class).hasMessageContaining("V002 migration conflict").hasMessageContaining("manual handling");
            try (Connection connection = database.dataSource().getConnection()) { assertThat(historyRows(connection, "V002")).isZero(); assertThat(sagaRows(connection)).isEqualTo(2); }
        } finally { Files.deleteIfExists(file); }
    }

    @Test
    void appliesV001OnceAndCreatesApplicationTables() throws Exception {
        Path databaseFile = Files.createTempFile("blockeco-migration-", ".db");
        try (Database database = new Database("jdbc:sqlite:" + databaseFile)) {
            database.migrate();
            database.migrate();

            try (Connection connection = database.dataSource().getConnection()) {
                assertThat(tableExists(connection, "schema_history")).isTrue();
                assertThat(tableExists(connection, "companies")).isTrue();
                assertThat(tableExists(connection, "registration_sagas")).isTrue();
                assertThat(tableExists(connection, "audit_events")).isTrue();
                assertThat(historyRows(connection, "V001")).isEqualTo(1);
            }
        } finally {
            Files.deleteIfExists(databaseFile);
        }
    }

    @Test
    void v003_creates_finance_tables_with_one_cash_account_and_holding_per_owner() throws Exception {
        Path databaseFile = Files.createTempFile("blockeco-v003-migration-", ".db");
        try (Database database = new Database("jdbc:sqlite:" + databaseFile)) {
            database.migrate();

            try (Connection connection = database.dataSource().getConnection()) {
                assertThat(tableExists(connection, "company_cash_accounts")).isTrue();
                assertThat(tableExists(connection, "share_holdings")).isTrue();
                assertThat(tableExists(connection, "asset_bindings")).isTrue();
                assertThat(tableExists(connection, "treasury_operations")).isTrue();
                assertThat(tableExists(connection, "company_announcements")).isTrue();
                assertThat(tableExists(connection, "primary_offerings")).isTrue();
                assertThat(tableExists(connection, "primary_subscriptions")).isTrue();
                assertThat(historyRows(connection, "V003")).isEqualTo(1);

                insertCompany(connection, "company-1");
                try (PreparedStatement cash = connection.prepareStatement("INSERT INTO company_cash_accounts VALUES (?, ?, ?, ?, ?)")) {
                    cash.setString(1, "company-1"); cash.setLong(2, 10); cash.setLong(3, 10); cash.setLong(4, 0); cash.setLong(5, 0); cash.executeUpdate();
                    assertThatThrownBy(cash::executeUpdate).isInstanceOf(Exception.class);
                }
                try (PreparedStatement holding = connection.prepareStatement("INSERT INTO share_holdings VALUES (?, ?, ?, ?)")) {
                    holding.setString(1, "company-1"); holding.setString(2, "holder-1"); holding.setLong(3, 1000); holding.setLong(4, 0); holding.executeUpdate();
                    assertThatThrownBy(holding::executeUpdate).isInstanceOf(Exception.class);
                }
                try (PreparedStatement binding = connection.prepareStatement("INSERT INTO asset_bindings VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                    binding.setString(1, "binding-1"); binding.setString(2, "company-1"); binding.setString(3, "lands"); binding.setString(4, "plot-a"); binding.setString(5, "owner-1"); binding.setString(6, "PENDING"); binding.setString(7, "2026-08-14T12:00:00Z"); binding.executeUpdate();
                    binding.setString(1, "binding-2"); assertThatThrownBy(binding::executeUpdate).isInstanceOf(Exception.class);
                }
                try (PreparedStatement offering = connection.prepareStatement("INSERT INTO primary_offerings VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                    offering.setString(1, "offering-1"); offering.setString(2, "company-1"); offering.setLong(3, 501); offering.setLong(4, 100); offering.setLong(5, 6); offering.setString(6, "2026-08-14T12:00:00Z"); offering.setString(7, "2026-08-15T00:00:00Z"); offering.setString(8, "2026-08-17T00:00:00Z"); offering.setString(9, "ANNOUNCED");
                    assertThatThrownBy(offering::executeUpdate).isInstanceOf(Exception.class);
                }
            }
        } finally {
            Files.deleteIfExists(databaseFile);
        }
    }

    @Test
    void v006_preflight_fails_before_schema_history_or_tables_change() throws Exception {
        Path databaseFile = Files.createTempFile("blockeco-v006-preflight-", ".db");
        try (Database database = new Database("jdbc:sqlite:" + databaseFile)) {
            applyMigrationsThroughV005(database);
            try (Connection connection = database.dataSource().getConnection()) {
                insertCompany(connection, "00000000-0000-0000-0000-000000000001", "LISTED");
            }

            assertThatThrownBy(database::migrate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("listed company lacks closed successful IPO");
            try (Connection connection = database.dataSource().getConnection()) {
                assertThat(historyRows(connection, "V006")).isZero();
                assertThat(tableExists(connection, "stock_listings")).isFalse();
            }
        } finally {
            Files.deleteIfExists(databaseFile);
        }
    }

    @Test
    void v006_backfills_listed_companies_in_creation_order_and_enforces_bounded_codes() throws Exception {
        Path databaseFile = Files.createTempFile("blockeco-v006-backfill-", ".db");
        try (Database database = new Database("jdbc:sqlite:" + databaseFile)) {
            applyMigrationsThroughV005(database);
            try (Connection connection = database.dataSource().getConnection()) {
                insertCompany(connection, "00000000-0000-0000-0000-000000000002", "LISTED", 1007,
                        Instant.parse("2026-08-13T00:00:00Z"));
                insertSuccessfulClosedOffering(connection, "00000000-0000-0000-0000-000000000002", "offering-2", 300, 7,
                        Instant.parse("2026-08-12T00:00:00Z"), Instant.parse("2026-08-15T00:00:00Z"));
                insertCompany(connection, "00000000-0000-0000-0000-000000000001", "LISTED", 1005,
                        Instant.parse("2026-08-14T00:00:00Z"));
                insertSuccessfulClosedOffering(connection, "00000000-0000-0000-0000-000000000001", "offering-1", 250, 5,
                        Instant.parse("2026-08-11T00:00:00Z"), Instant.parse("2026-08-14T00:00:00Z"));
            }

            database.migrate();

            try (Connection connection = database.dataSource().getConnection()) {
                assertThat(historyRows(connection, "V006")).isEqualTo(1);
                assertThat(listingCode(connection, "00000000-0000-0000-0000-000000000002")).isEqualTo("BS000001");
                assertThat(listingCode(connection, "00000000-0000-0000-0000-000000000001")).isEqualTo("BS000002");
                assertThat(listingPrice(connection, "00000000-0000-0000-0000-000000000001")).isEqualTo(250);
                assertThat(listingShares(connection, "00000000-0000-0000-0000-000000000002")).isEqualTo(1007);
                assertThat(sequenceValue(connection)).isEqualTo(2);
                assertThat(auditPayload(connection, "00000000-0000-0000-0000-000000000001"))
                        .contains("IPO_LISTING_BACKFILLED", "BS000002", "offering-1", "BACKFILL");

                try (PreparedStatement duplicate = connection.prepareStatement(
                        "INSERT INTO stock_listings VALUES (?, 'BS000001', 1, 1, ? )")) {
                    duplicate.setString(1, "00000000-0000-0000-0000-000000000003");
                    duplicate.setString(2, Instant.now().toString());
                    assertThatThrownBy(duplicate::executeUpdate).isInstanceOf(Exception.class);
                }
                try (PreparedStatement invalid = connection.prepareStatement(
                        "INSERT INTO stock_listings VALUES (?, 'BS000000', 1, 1, ? )")) {
                    invalid.setString(1, "00000000-0000-0000-0000-000000000004");
                    invalid.setString(2, Instant.now().toString());
                    assertThatThrownBy(invalid::executeUpdate).isInstanceOf(Exception.class);
                }
                try (PreparedStatement exhausted = connection.prepareStatement(
                        "UPDATE stock_code_sequence SET last_value = 1000000 WHERE singleton = 1")) {
                    assertThatThrownBy(exhausted::executeUpdate).isInstanceOf(Exception.class);
                }
            }
        } finally {
            Files.deleteIfExists(databaseFile);
        }
    }

    @Test
    void v006_uses_the_latest_announced_offering_when_close_times_tie() throws Exception {
        Path databaseFile = Files.createTempFile("blockeco-v006-source-order-", ".db");
        try (Database database = new Database("jdbc:sqlite:" + databaseFile)) {
            applyMigrationsThroughV005(database);
            try (Connection connection = database.dataSource().getConnection()) {
                String companyId = "00000000-0000-0000-0000-000000000003";
                Instant closesAt = Instant.parse("2026-08-20T00:00:00Z");
                insertCompany(connection, companyId, "LISTED", 1012, Instant.parse("2026-08-10T00:00:00Z"));
                insertSuccessfulClosedOffering(connection, companyId, "older-offering", 250, 5,
                        Instant.parse("2026-08-01T00:00:00Z"), closesAt);
                insertSuccessfulClosedOffering(connection, companyId, "newer-offering", 300, 7,
                        Instant.parse("2026-08-02T00:00:00Z"), closesAt);
            }

            database.migrate();

            try (Connection connection = database.dataSource().getConnection()) {
                assertThat(listingPrice(connection, "00000000-0000-0000-0000-000000000003")).isEqualTo(300);
                assertThat(listingShares(connection, "00000000-0000-0000-0000-000000000003")).isEqualTo(1012);
                assertThat(auditPayload(connection, "00000000-0000-0000-0000-000000000003"))
                        .contains("newer-offering", "\"issuedShares\":1012");
            }
        } finally {
            Files.deleteIfExists(databaseFile);
        }
    }

    @Test
    void v007_upgrades_a_real_v006_fixture_once_with_segregated_market_tables_and_opening_company_ledger() throws Exception {
        Path databaseFile = Files.createTempFile("blockeco-v007-migration-", ".db");
        try (Database database = new Database("jdbc:sqlite:" + databaseFile)) {
            applyMigrationsThroughV006(database);
            java.util.Map<String, String> publishedChecksums = new java.util.LinkedHashMap<>();
            try (Connection connection = database.dataSource().getConnection()) {
                for (int version = 1; version <= 6; version++) {
                    String name = "V00" + version;
                    publishedChecksums.put(name, checksum(connection, name));
                }
                insertCompany(connection, "00000000-0000-0000-0000-000000000007");
                try (PreparedStatement cash = connection.prepareStatement(
                        "INSERT INTO company_cash_accounts VALUES (?, ?, ?, ?, ?)")) {
                    cash.setString(1, "00000000-0000-0000-0000-000000000007");
                    cash.setLong(2, 1234); cash.setLong(3, 1234); cash.setLong(4, 0); cash.setLong(5, 0);
                    cash.executeUpdate();
                }
            }

            database.migrate();
            database.migrate();

            try (Connection connection = database.dataSource().getConnection()) {
                assertThat(historyRows(connection, "V007")).isEqualTo(1);
                for (java.util.Map.Entry<String, String> publishedChecksum : publishedChecksums.entrySet()) {
                    assertThat(checksum(connection, publishedChecksum.getKey()))
                            .isEqualTo(publishedChecksum.getValue());
                }
                for (String table : java.util.List.of("securities_cash_accounts", "compensation_fund",
                        "escrow_ledger_entries", "securities_cash_operations", "stock_order_sequence",
                        "stock_orders", "stock_trades")) {
                    assertThat(tableExists(connection, table)).as(table).isTrue();
                }
                for (String index : java.util.List.of("stock_orders_book", "stock_orders_player",
                        "stock_trades_time", "securities_cash_operations_player_active")) {
                    assertThat(indexExists(connection, index)).as(index).isTrue();
                }
                assertThat(singleLong(connection, "SELECT balance_minor FROM compensation_fund WHERE singleton = 1"))
                        .isZero();
                assertThat(singleLong(connection, "SELECT amount_minor FROM escrow_ledger_entries "
                        + "WHERE liability_kind = 'COMPANY_TREASURY' AND company_id = "
                        + "'00000000-0000-0000-0000-000000000007'"))
                        .isEqualTo(1234);
                assertThat(singleLong(connection, "SELECT COUNT(*) FROM escrow_ledger_entries "
                        + "WHERE liability_kind = 'COMPANY_TREASURY'"))
                        .isEqualTo(1);
            }
        } finally {
            Files.deleteIfExists(databaseFile);
        }
    }

    private boolean tableExists(Connection connection, String tableName) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            statement.setString(1, tableName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private boolean indexExists(Connection connection, String indexName) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'index' AND name = ?")) {
            statement.setString(1, indexName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private long singleLong(Connection connection, String sql) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql); ResultSet rows = statement.executeQuery()) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private String checksum(Connection connection, String version) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT checksum FROM schema_history WHERE version = ?")) {
            statement.setString(1, version);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        }
    }

    private int historyRows(Connection connection, String version) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM schema_history WHERE version = ?")) {
            statement.setString(1, version);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private int sagaRows(Connection connection) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM registration_sagas"); ResultSet rows = statement.executeQuery()) { rows.next(); return rows.getInt(1); }
    }

    private void insertCompany(Connection connection, String id) throws Exception {
        insertCompany(connection, id, "PENDING_ASSET_BINDING");
    }

    private void insertCompany(Connection connection, String id, String status) throws Exception {
        insertCompany(connection, id, status, 1000, Instant.parse("2026-08-14T12:00:00Z"));
    }

    private void insertCompany(Connection connection, String id, String status, long totalShares, Instant createdAt) throws Exception {
        try (PreparedStatement company = connection.prepareStatement("INSERT INTO companies VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            company.setString(1, id); company.setString(2, id); company.setString(3, "Test Company"); company.setString(4, "founder-1"); company.setString(5, status); company.setLong(6, 0); company.setLong(7, totalShares); company.setInt(8, 5000); company.setString(9, createdAt.toString()); company.setInt(10, 0); company.executeUpdate();
        }
    }

    private void applyMigrationsThroughV005(Database database) throws Exception {
        try (Connection connection = database.dataSource().getConnection()) {
            try (PreparedStatement history = connection.prepareStatement("CREATE TABLE schema_history (version TEXT PRIMARY KEY, checksum TEXT NOT NULL)")) {
                history.execute();
            }
            for (int version = 1; version <= 5; version++) {
                String name = "V00" + version;
                byte[] script = MigrationTest.class.getResourceAsStream("/db/migration/" + name + ".sql").readAllBytes();
                for (String sql : Database.splitStatements(new String(script, StandardCharsets.UTF_8))) {
                    if (!sql.isBlank()) try (PreparedStatement statement = connection.prepareStatement(sql)) { statement.execute(); }
                }
                try (PreparedStatement history = connection.prepareStatement("INSERT INTO schema_history(version, checksum) VALUES (?, ?)")) {
                    history.setString(1, name); history.setString(2, sha256(script)); history.executeUpdate();
                }
            }
        }
    }

    private void applyMigrationsThroughV006(Database database) throws Exception {
        try (Connection connection = database.dataSource().getConnection()) {
            try (PreparedStatement history = connection.prepareStatement("CREATE TABLE schema_history (version TEXT PRIMARY KEY, checksum TEXT NOT NULL)")) {
                history.execute();
            }
            for (int version = 1; version <= 6; version++) {
                String name = "V00" + version;
                byte[] script = MigrationTest.class.getResourceAsStream("/db/migration/" + name + ".sql").readAllBytes();
                for (String sql : Database.splitStatements(new String(script, StandardCharsets.UTF_8))) {
                    if (!sql.isBlank()) try (PreparedStatement statement = connection.prepareStatement(sql)) { statement.execute(); }
                }
                try (PreparedStatement history = connection.prepareStatement("INSERT INTO schema_history(version, checksum) VALUES (?, ?)")) {
                    history.setString(1, name); history.setString(2, sha256(script)); history.executeUpdate();
                }
            }
        }
    }

    private void insertSuccessfulClosedOffering(Connection connection, String companyId, String offeringId,
            long price, long shares, Instant announcedAt, Instant closedAt) throws Exception {
        try (PreparedStatement offering = connection.prepareStatement("INSERT INTO primary_offerings VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'CLOSED')")) {
            offering.setString(1, offeringId); offering.setString(2, companyId); offering.setLong(3, price * shares); offering.setLong(4, price); offering.setLong(5, shares);
            offering.setString(6, announcedAt.toString()); offering.setString(7, announcedAt.plusSeconds(3600).toString()); offering.setString(8, closedAt.toString()); offering.executeUpdate();
        }
        String subscriptionId = UUID.nameUUIDFromBytes((offeringId + "-subscription").getBytes(StandardCharsets.UTF_8)).toString();
        try (PreparedStatement subscription = connection.prepareStatement("INSERT INTO primary_subscriptions VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            subscription.setString(1, subscriptionId); subscription.setString(2, offeringId); subscription.setString(3, companyId); subscription.setString(4, "player-1"); subscription.setLong(5, shares); subscription.setLong(6, price * shares); subscription.setString(7, subscriptionId); subscription.setString(8, closedAt.toString()); subscription.executeUpdate();
        }
        try (PreparedStatement operation = connection.prepareStatement("INSERT INTO treasury_operations VALUES (?, ?, ?, ?, ?, 'COMPLETED', ?, ?)")) {
            operation.setString(1, subscriptionId); operation.setString(2, companyId); operation.setString(3, "player-1"); operation.setLong(4, price * shares); operation.setString(5, subscriptionId); operation.setString(6, closedAt.toString()); operation.setString(7, closedAt.toString()); operation.executeUpdate();
        }
    }

    private String listingCode(Connection connection, String companyId) throws Exception { return listingValue(connection, companyId, "stock_code"); }
    private long listingPrice(Connection connection, String companyId) throws Exception { return Long.parseLong(listingValue(connection, companyId, "issue_reference_price_minor")); }
    private long listingShares(Connection connection, String companyId) throws Exception { return Long.parseLong(listingValue(connection, companyId, "issued_shares")); }
    private String listingValue(Connection connection, String companyId, String column) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("SELECT " + column + " FROM stock_listings WHERE company_id = ?")) {
            statement.setString(1, companyId); try (ResultSet rows = statement.executeQuery()) { rows.next(); return rows.getString(1); }
        }
    }
    private long sequenceValue(Connection connection) throws Exception { try (PreparedStatement statement = connection.prepareStatement("SELECT last_value FROM stock_code_sequence WHERE singleton = 1"); ResultSet rows = statement.executeQuery()) { rows.next(); return rows.getLong(1); } }
    private String auditPayload(Connection connection, String companyId) throws Exception { try (PreparedStatement statement = connection.prepareStatement("SELECT event_type || payload_json FROM audit_events WHERE company_id = ?")) { statement.setString(1, companyId); try (ResultSet rows = statement.executeQuery()) { rows.next(); return rows.getString(1); } } }

    private void applyOldV001(Database database) throws Exception {
        byte[] script = MigrationTest.class.getResourceAsStream("/legacy/V001-published.sql").readAllBytes();
        try (Connection connection = database.dataSource().getConnection()) {
            try (PreparedStatement history = connection.prepareStatement("CREATE TABLE schema_history (version TEXT PRIMARY KEY, checksum TEXT NOT NULL)")) { history.execute(); }
            for (String sql : Database.splitStatements(new String(script, StandardCharsets.UTF_8))) if (!sql.isBlank()) try (PreparedStatement statement = connection.prepareStatement(sql)) { statement.execute(); }
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO schema_history(version, checksum) VALUES ('V001', ?)")) { statement.setString(1, sha256(script)); statement.executeUpdate(); }
        }
    }
    private String sha256(byte[] bytes) throws Exception { byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes); StringBuilder result = new StringBuilder(); for (byte value : digest) result.append(String.format("%02x", value)); return result.toString(); }
}
