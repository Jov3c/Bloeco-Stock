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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class Database implements AutoCloseable, TransactionRunner {

    private static final String[] MIGRATIONS = {"V001", "V002", "V003", "V004", "V005", "V006", "V007"};
    private final HikariDataSource dataSource;
    private final MigrationPrecondition precondition;

    public Database(String jdbcUrl) {
        this(jdbcUrl, Database::verifyMigrationPrecondition);
    }

    public Database(String jdbcUrl, MigrationPrecondition precondition) {
        HikariConfig configuration = new HikariConfig();
        configuration.setJdbcUrl(jdbcUrl);
        configuration.setMaximumPoolSize(1);
        configuration.setConnectionInitSql("PRAGMA foreign_keys=ON");
        this.dataSource = new HikariDataSource(configuration);
        this.precondition = Objects.requireNonNull(precondition, "precondition");
    }

    public HikariDataSource dataSource() {
        return dataSource;
    }

    public void migrate() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            enableForeignKeys(connection);
            createSchemaHistory(connection);
            for (String version : MIGRATIONS) {
                byte[] script = readMigration(version);
                String checksum = checksum(script);
                if (isApplied(connection, version, checksum)) continue;
                precondition.verify(connection, version);
                boolean originalAutoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
                try {
                    executeStatements(connection, new String(script, StandardCharsets.UTF_8));
                    try (PreparedStatement statement = connection.prepareStatement("INSERT INTO schema_history(version, checksum) VALUES (?, ?)")) {
                        statement.setString(1, version);
                        statement.setString(2, checksum);
                        statement.executeUpdate();
                    }
                    connection.commit();
                } catch (SQLException exception) {
                    connection.rollback();
                    if ("V002".equals(version) && isActiveSagaReservationConflict(exception)) {
                        throw new IllegalStateException("V002 migration conflict: duplicate active registration saga name; manual handling is required", exception);
                    }
                    throw exception;
                } finally {
                    connection.setAutoCommit(originalAutoCommit);
                }
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
        for (String statementSql : splitStatements(script)) {
            if (!statementSql.isBlank()) {
                try (PreparedStatement statement = connection.prepareStatement(statementSql)) {
                    statement.execute();
                }
            }
        }
    }

    static List<String> splitStatements(String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean single = false, doubleQuoted = false, lineComment = false, blockComment = false;
        int triggerDepth = 0;
        StringBuilder word = new StringBuilder();
        for (int i = 0; i < script.length(); i++) {
            char c = script.charAt(i), next = i + 1 < script.length() ? script.charAt(i + 1) : '\0';
            current.append(c);
            if (lineComment) { if (c == '\n') lineComment = false; continue; }
            if (blockComment) { if (c == '*' && next == '/') { current.append(next); i++; blockComment = false; } continue; }
            if (!single && !doubleQuoted && c == '-' && next == '-') { current.append(next); i++; lineComment = true; continue; }
            if (!single && !doubleQuoted && c == '/' && next == '*') { current.append(next); i++; blockComment = true; continue; }
            if (!doubleQuoted && c == '\'') { if (single && next == '\'') { current.append(next); i++; } else single = !single; continue; }
            if (!single && c == '"') { if (doubleQuoted && next == '"') { current.append(next); i++; } else doubleQuoted = !doubleQuoted; continue; }
            if (single || doubleQuoted) continue;
            if (Character.isLetter(c)) { word.append(Character.toUpperCase(c)); continue; }
            if (!word.isEmpty()) {
                String token = word.toString(); word.setLength(0);
                if ("CREATE".equals(token)) { /* trigger determined by following token */ }
                else if ("TRIGGER".equals(token) && current.toString().toUpperCase().contains("CREATE")) triggerDepth = 1;
                else if ("BEGIN".equals(token) && triggerDepth > 0) triggerDepth++;
                else if ("END".equals(token) && triggerDepth > 1) triggerDepth--;
            }
            if (c == ';' && triggerDepth <= 1) { current.setLength(current.length() - 1); if (!current.toString().isBlank()) statements.add(current.toString()); current.setLength(0); triggerDepth = 0; }
        }
        if (!current.toString().isBlank()) statements.add(current.toString());
        return statements;
    }

    private static void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }

    private static boolean isActiveSagaReservationConflict(SQLException exception) {
        String message = exception.getMessage();
        return message != null && message.contains("registration_sagas.company_normalized_name");
    }

    private static void verifyMigrationPrecondition(Connection connection, String version) throws SQLException {
        if (!"V006".equals(version)) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT c.id
                FROM companies c
                WHERE c.status = 'LISTED'
                  AND NOT EXISTS (
                    SELECT 1
                    FROM primary_offerings po
                    JOIN primary_subscriptions ps ON ps.offering_id = po.id
                    JOIN treasury_operations t ON t.id = ps.id AND t.state = 'COMPLETED'
                    WHERE po.company_id = c.id AND po.state = 'CLOSED'
                  )
                LIMIT 1
                """)) {
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) {
                    throw new IllegalStateException("listed company lacks closed successful IPO: " + rows.getString(1));
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM companies WHERE status = 'LISTED'")) {
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next() && rows.getLong(1) > 999999) {
                    throw new IllegalStateException("stock code sequence exhausted during V006 backfill");
                }
            }
        }
    }
}
