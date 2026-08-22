package cn.blockeco.exchange.domain.finance;

import cn.blockeco.exchange.domain.money.Money;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Durable intent and last proven stage for a non-idempotent Vault transfer. */
public record SecuritiesCashOperation(
        UUID id,
        UUID playerId,
        Money amount,
        SecuritiesCashDirection direction,
        SecuritiesCashOperationState state,
        SecuritiesCashOperationState lastConfirmedExternalStage,
        String detail,
        Instant createdAt,
        Instant updatedAt) {
    public SecuritiesCashOperation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(amount, "amount");
        if (amount.minorUnits() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(state, "state");
        if (lastConfirmedExternalStage != null && !lastConfirmedExternalStage.isConfirmedExternalStage()) {
            throw new IllegalArgumentException("lastConfirmedExternalStage must be an external stage");
        }
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
