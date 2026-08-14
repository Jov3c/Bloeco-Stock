package cn.blockeco.exchange.infrastructure.sql;

import cn.blockeco.exchange.ports.TransactionRunner;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public final class Database implements AutoCloseable, TransactionRunner {

    private static final String V001 = "V001";
    private final HikariDataSource dataSource;

    public Database(String jdbcUrl) {
        HikariConfig configuration = new HikariConfig();
        configuration.setJdbcUrl(jdbcUrl);
        configuration.setMaximumPoolSize(1);
        configuration.setConnectionInitSql("PRAGMA foreign_keys=ON");
        this.dataSource = new HikariDataSource(configuration);
    }

    public HikariDataSource dataSource() {
        return dataSource;
    }

    public void migrate() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            enableForeignKeys(connection);
            createSchemaHistory(connection);
            byte[] script = readMigration(V001);
            String checksum = checksum(script);
            if (isApplied(connection, V001, checksum)) {
                return;
            }

            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                executeStatements(connection, new String(script, StandardCharsets.UTF_8));
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO schema_history(version, checksum) VALUES (?, ?)")) {
                    statement.setString(1, V001);
                    statement.setString(2, checksum);
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        }
    }

    @Override
    public <T> T inTransaction(SqlWork<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = work.execute(connection);
                connection.commit();
                return result;
            } catch (Exception exception) {
                rollback(connection, exception);
                if (exception instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new IllegalStateException("database transaction failed", exception);
            } finally {
                try {
                    connection.setAutoCommit(originalAutoCommit);
                } catch (SQLException exception) {
                    throw new IllegalStateException("could not restore auto-commit", exception);
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("could not open database transaction", exception);
        }
    }

    @Override
    public void close() {
        dataSource.close();
    }

    private static void enableForeignKeys(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("PRAGMA foreign_keys=ON")) {
            statement.execute();
        }
    }

    private static void createSchemaHistory(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "CREATE TABLE IF NOT EXISTS schema_history (version TEXT PRIMARY KEY, checksum TEXT NOT NULL)")) {
            statement.execute();
        }
    }

    private static boolean isApplied(Connection connection, String version, String checksum) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT checksum FROM schema_history WHERE version = ?")) {
            statement.setString(1, version);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return false;
                }
                if (!checksum.equals(resultSet.getString(1))) {
                    throw new IllegalStateException("migration checksum mismatch for " + version);
                }
                return true;
            }
        }
    }

    private static byte[] readMigration(String version) {
        String resource = "/db/migration/" + version + ".sql";
        try (InputStream input = Database.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("missing migration resource " + resource);
            }
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new IllegalStateException("cannot read migration " + resource, exception);
        }
    }

    private static String checksum(byte[] migration) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(migration);
            StringBuilder hexadecimal = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hexadecimal.append(String.format("%02x", value));
            }
            return hexadecimal.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void executeStatements(Connection connection, String script) throws SQLException {
        for (String statementSql : script.split(";")) {
            if (!statementSql.isBlank()) {
                try (PreparedStatement statement = connection.prepareStatement(statementSql)) {
                    statement.execute();
                }
            }
        }
    }

    private static void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }
}
