package cn.blockeco.exchange.domain.registration;

public enum RegistrationSagaState {
    PREPARED,
    WITHDRAWN,
    ESCROW_DEPOSITED,
    COMPLETED,
    REFUND_REQUIRED,
    REFUNDED,
    AMBIGUOUS,
    REJECTED
}
