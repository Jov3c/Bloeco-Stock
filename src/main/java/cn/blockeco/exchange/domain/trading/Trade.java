package cn.blockeco.exchange.domain.trading;

import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.money.Money;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable settled fill. Counterparty identity is derivable only by internal order joins. */
public record Trade(
        UUID id,
        CompanyId companyId,
        String stockCode,
        UUID buyOrderId,
        UUID sellOrderId,
        long shares,
        Money price,
        Money notional,
        Money buyerFee,
        Instant occurredAt) {
    public Trade {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(companyId, "companyId");
        if (stockCode == null || !stockCode.matches("BS\\d{6}")) throw new IllegalArgumentException("stockCode must be a stock code");
        Objects.requireNonNull(buyOrderId, "buyOrderId");
        Objects.requireNonNull(sellOrderId, "sellOrderId");
        if (shares <= 0) throw new IllegalArgumentException("shares must be positive");
        Objects.requireNonNull(price, "price");
        if (price.minorUnits() <= 0) throw new IllegalArgumentException("price must be positive");
        Objects.requireNonNull(notional, "notional");
        if (notional.minorUnits() <= 0) throw new IllegalArgumentException("notional must be positive");
        Objects.requireNonNull(buyerFee, "buyerFee").requireNonNegative("buyerFee");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
