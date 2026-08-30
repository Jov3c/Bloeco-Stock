package cn.blockeco.exchange.domain.governance;

/** A Vault outcome is never retried automatically after AMBIGUOUS. */
public enum PayoutOperationState {
    PREPARED,
    EXTERNAL_DEBIT_CONFIRMED,
    COMPLETED,
    FAILED,
    AMBIGUOUS,
    CANCELLED
}
