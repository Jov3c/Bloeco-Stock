package cn.blockeco.exchange.application;

import cn.blockeco.exchange.domain.finance.SecuritiesCashDirection;
import cn.blockeco.exchange.domain.finance.SecuritiesCashOperationState;
import cn.blockeco.exchange.domain.money.Money;
import java.util.UUID;

public record SecuritiesCashRecoveryRecord(UUID operationId, UUID playerId, Money amount,
        SecuritiesCashDirection direction, SecuritiesCashOperationState state,
        SecuritiesCashOperationState lastConfirmedStage, String detail) { }
