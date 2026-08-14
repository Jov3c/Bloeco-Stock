package cn.blockeco.exchange.domain.registration;

public enum RegistrationSagaState {
    PREPARED,
    WITHDRAWN,
    COMPLETED,
    REFUND_REQUIRED,
    REFUNDED,
    AMBIGUOUS
}
