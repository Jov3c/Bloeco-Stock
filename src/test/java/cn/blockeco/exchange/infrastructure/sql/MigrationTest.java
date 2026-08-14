package cn.blockeco.exchange.infrastructure.sql;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import org.junit.jupiter.api.Test;

class MigrationTest {

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
}
