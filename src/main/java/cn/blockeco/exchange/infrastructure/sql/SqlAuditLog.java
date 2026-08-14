package cn.blockeco.exchange.infrastructure.sql;

import cn.blockeco.exchange.domain.audit.AuditEvent;
import cn.blockeco.exchange.ports.AuditLog;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;
import java.util.TreeMap;

public final class SqlAuditLog implements AuditLog {

    private static final String INSERT = """
            INSERT INTO audit_events (event_id, company_id, actor_uuid, event_type, payload_json, occurred_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

    @Override
    public void append(Connection connection, AuditEvent event) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
            statement.setString(1, event.eventId().toString());
            statement.setString(2, event.companyId().map(id -> id.value().toString()).orElse(null));
            statement.setString(3, event.actorId().map(Object::toString).orElse(null));
            statement.setString(4, event.eventType());
            statement.setString(5, encodeJson(event.payload()));
            statement.setString(6, event.occurredAt().toString());
            statement.executeUpdate();
        }
    }

    private static String encodeJson(Map<String, Object> payload) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : new TreeMap<>(payload).entrySet()) {
            if (!first) {
                json.append(',');
            }
            appendString(json, entry.getKey());
            json.append(':');
            appendValue(json, entry.getValue());
            first = false;
        }
        return json.append('}').toString();
    }

    private static void appendValue(StringBuilder json, Object value) {
        if (value instanceof String string) {
            appendString(json, string);
        } else if (value instanceof Boolean || value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long || value instanceof java.math.BigInteger
                || value instanceof java.math.BigDecimal) {
            json.append(value);
        } else if (value instanceof Float floating && Float.isFinite(floating)) {
            json.append(floating);
        } else if (value instanceof Double floating && Double.isFinite(floating)) {
            json.append(floating);
        } else {
            throw new IllegalArgumentException("audit payload values must be strings, numbers, or booleans");
        }
    }

    private static void appendString(StringBuilder json, String value) {
        json.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (character < 0x20) {
                        json.append(String.format("\\u%04x", (int) character));
                    } else {
                        json.append(character);
                    }
                }
            }
        }
        json.append('"');
    }
}
