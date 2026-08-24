package cn.blockeco.exchange.domain.trading;

import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.finance.StockListing;
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
        if (!StockListing.isValidStockCode(stockCode)) throw new IllegalArgumentException("stockCode must be a bounded stock code");
        Objects.requireNonNull(buyOrderId, "buyOrderId");
        Objects.requireNonNull(sellOrderId, "sellOrderId");
        if (buyOrderId.equals(sellOrderId)) throw new IllegalArgumentException("buyOrderId and sellOrderId must differ");
        if (shares <= 0) throw new IllegalArgumentException("shares must be positive");
        Objects.requireNonNull(price, "price");
        if (price.minorUnits() <= 0) throw new IllegalArgumentException("price must be positive");
        Objects.requireNonNull(notional, "notional");
        if (notional.minorUnits() <= 0) throw new IllegalArgumentException("notional must be positive");
        Objects.requireNonNull(buyerFee, "buyerFee").requireNonNegative("buyerFee");
        if (notional.minorUnits() != Math.multiplyExact(price.minorUnits(), shares)) {
            throw new IllegalArgumentException("notional must equal price times shares");
        }
        if (buyerFee.minorUnits() > notional.minorUnits()) {
            throw new IllegalArgumentException("buyerFee must not exceed notional");
        }
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

}
