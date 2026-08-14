package cn.blockeco.exchange.domain.finance;

public enum TreasuryOperationState {
    PREPARED,
    PLAYER_WITHDRAWN,
    ESCROW_DEPOSITED,
    COMPLETED,
    REFUND_REQUIRED,
    REFUNDED,
    AMBIGUOUS
}
