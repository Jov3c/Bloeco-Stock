package cn.blockeco.exchange.domain.audit;

import cn.blockeco.exchange.domain.company.CompanyId;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record AuditEvent(
        UUID eventId,
        Optional<CompanyId> companyId,
        Optional<UUID> actorId,
        String eventType,
        Map<String, Object> payload,
        Instant occurredAt) {

    public AuditEvent {
        Objects.requireNonNull(eventId, "eventId");
        companyId = Objects.requireNonNull(companyId, "companyId");
        actorId = Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(eventType, "eventType");
        payload = Map.copyOf(Objects.requireNonNull(payload, "payload"));
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
