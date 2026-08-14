package cn.blockeco.exchange.ports;

import cn.blockeco.exchange.domain.audit.AuditEvent;
import java.sql.Connection;
import java.sql.SQLException;

public interface AuditLog {

    void append(Connection connection, AuditEvent event) throws SQLException;
}
