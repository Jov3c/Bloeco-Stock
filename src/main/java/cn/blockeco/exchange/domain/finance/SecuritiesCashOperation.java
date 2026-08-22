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
        validateStage(direction, state, lastConfirmedExternalStage);
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    private static void validateStage(SecuritiesCashDirection direction, SecuritiesCashOperationState state,
            SecuritiesCashOperationState lastConfirmedExternalStage) {
        boolean valid = switch (direction) {
            case DEPOSIT -> switch (state) {
                case PREPARED, FAILED -> lastConfirmedExternalStage == null;
                case PLAYER_WITHDRAWN -> lastConfirmedExternalStage == SecuritiesCashOperationState.PLAYER_WITHDRAWN;
                case ESCROW_DEPOSITED, COMPLETED -> lastConfirmedExternalStage == SecuritiesCashOperationState.ESCROW_DEPOSITED;
                case AMBIGUOUS -> lastConfirmedExternalStage == null
                        || lastConfirmedExternalStage == SecuritiesCashOperationState.PLAYER_WITHDRAWN
                        || lastConfirmedExternalStage == SecuritiesCashOperationState.ESCROW_DEPOSITED;
                default -> false;
            };
            case WITHDRAW -> switch (state) {
                case PREPARED, FAILED -> lastConfirmedExternalStage == null;
                case ESCROW_WITHDRAWN -> lastConfirmedExternalStage == SecuritiesCashOperationState.ESCROW_WITHDRAWN;
                case PLAYER_DEPOSITED, COMPLETED -> lastConfirmedExternalStage == SecuritiesCashOperationState.PLAYER_DEPOSITED;
                case AMBIGUOUS -> lastConfirmedExternalStage == null
                        || lastConfirmedExternalStage == SecuritiesCashOperationState.ESCROW_WITHDRAWN
                        || lastConfirmedExternalStage == SecuritiesCashOperationState.PLAYER_DEPOSITED;
                default -> false;
            };
        };
        if (!valid) {
            throw new IllegalArgumentException("state and lastConfirmedExternalStage do not match direction");
        }
    }
}
