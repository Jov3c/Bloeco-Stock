package cn.blockeco.exchange.domain.finance;

public enum SecuritiesCashOperationState {
    PREPARED,
    PLAYER_WITHDRAWN,
    ESCROW_DEPOSITED,
    ESCROW_WITHDRAWN,
    PLAYER_DEPOSITED,
    COMPLETED,
    FAILED,
    AMBIGUOUS;

    public boolean isConfirmedExternalStage() {
        return this == PLAYER_WITHDRAWN || this == ESCROW_DEPOSITED
                || this == ESCROW_WITHDRAWN || this == PLAYER_DEPOSITED;
    }
}
