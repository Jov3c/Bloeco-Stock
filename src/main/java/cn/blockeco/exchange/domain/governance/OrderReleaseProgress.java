package cn.blockeco.exchange.domain.governance;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record OrderReleaseProgress(UUID actionId, Optional<UUID> lastReleasedOrderId, long releasedOrders,
        boolean complete, Instant updatedAt) {
    public OrderReleaseProgress {
        Objects.requireNonNull(actionId, "actionId"); Objects.requireNonNull(lastReleasedOrderId, "lastReleasedOrderId"); Objects.requireNonNull(updatedAt, "updatedAt");
        if (releasedOrders < 0) throw new IllegalArgumentException("released order count cannot be negative");
    }
}
