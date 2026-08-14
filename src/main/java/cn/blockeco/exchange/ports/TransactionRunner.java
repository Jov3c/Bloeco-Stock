package cn.blockeco.exchange.ports;

import java.sql.Connection;
import java.sql.SQLException;

public interface TransactionRunner {

    <T> T inTransaction(SqlWork<T> work);

    @FunctionalInterface
    interface SqlWork<T> {
        T execute(Connection connection) throws SQLException;
    }
}
