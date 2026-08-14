package cn.blockeco.exchange.infrastructure.sql;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
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
                try (PreparedStatement statement = connection.prepareStatement("INSERT INTO registration_sagas VALUES ('33333333-3333-3333-3333-333333333333','44444444-4444-4444-4444-444444444444','reserved',1,'REJECTED',NULL,'2026-01-01T00:00:00Z','2026-01-01T00:00:00Z')")) { statement.executeUpdate(); }
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

    private boolean tableExists(Connection connection, String tableName) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            statement.setString(1, tableName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
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
