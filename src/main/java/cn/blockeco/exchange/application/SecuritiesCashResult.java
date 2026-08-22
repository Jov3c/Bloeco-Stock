package cn.blockeco.exchange.application;

import cn.blockeco.exchange.domain.finance.SecuritiesCashOperationState;
import java.util.UUID;

/** User-facing outcome; ambiguous means the Vault provider was called and must not be retried. */
public record SecuritiesCashResult(UUID operationId, SecuritiesCashOperationState state, String detail) {
    public boolean completed() { return state == SecuritiesCashOperationState.COMPLETED; }
}
