package cn.blockeco.exchange.domain.finance;

import cn.blockeco.exchange.domain.company.CompanyId;
import java.util.Objects;
import java.util.UUID;

public record ShareHolding(CompanyId companyId, UUID holderId, long availableShares, long reservedShares) {

    public ShareHolding {
        Objects.requireNonNull(companyId, "companyId");
        Objects.requireNonNull(holderId, "holderId");
        if (availableShares < 0 || reservedShares < 0) {
            throw new IllegalArgumentException("share balances must not be negative");
        }
    }
}
