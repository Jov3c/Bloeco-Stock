package cn.blockeco.exchange.domain.finance;

import cn.blockeco.exchange.domain.company.CompanyId;
import cn.blockeco.exchange.domain.money.Money;
import java.util.Objects;

public record CompanyCashAccount(
        CompanyId companyId, Money cash, Money paidInCapital, Money retainedEarnings, Money reserved) {

    public CompanyCashAccount {
        Objects.requireNonNull(companyId, "companyId");
        Objects.requireNonNull(cash, "cash").requireNonNegative("cash");
        Objects.requireNonNull(paidInCapital, "paidInCapital").requireNonNegative("paidInCapital");
        Objects.requireNonNull(retainedEarnings, "retainedEarnings").requireNonNegative("retainedEarnings");
        Objects.requireNonNull(reserved, "reserved").requireNonNegative("reserved");
        if (reserved.minorUnits() > cash.minorUnits()) {
            throw new IllegalArgumentException("reserved must not exceed cash");
        }
    }
}
