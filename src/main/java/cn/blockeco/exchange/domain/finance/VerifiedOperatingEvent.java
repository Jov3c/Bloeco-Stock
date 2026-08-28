package cn.blockeco.exchange.domain.finance;

import java.time.Instant;
import java.util.Objects;

public record VerifiedOperatingEvent(
        String adapterId,
        String externalEventKey,
        OperatingEventKind kind,
        long amount,
        Instant occurredAt,
        String displaySummary) {

    public VerifiedOperatingEvent {
        requireNonBlank(adapterId, "adapterId");
        requireNonBlank(externalEventKey, "externalEventKey");
        Objects.requireNonNull(kind, "kind");
        if (amount <= 0) throw new IllegalArgumentException("amount must be positive");
        Objects.requireNonNull(occurredAt, "occurredAt");
        requireNonBlank(displaySummary, "displaySummary");
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }
}
