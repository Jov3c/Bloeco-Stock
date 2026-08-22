package cn.blockeco.exchange.infrastructure.sql;

import java.sql.Connection;
import java.sql.SQLException;

@FunctionalInterface
public interface MigrationPrecondition {
    void verify(Connection connection, String version) throws SQLException;
}
