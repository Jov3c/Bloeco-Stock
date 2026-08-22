package cn.blockeco.exchange.paper;

import cn.blockeco.exchange.domain.money.Money;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** One atomic source for rules that administrators may change while Paper is running. */
public final class MutableCompanyCreationRules {
    private final AtomicReference<CompanyCreationRules> current;
    public MutableCompanyCreationRules(CompanyCreationRules initial) { current = new AtomicReference<>(Objects.requireNonNull(initial)); }
    public CompanyCreationRules current() { return current.get(); }
    public void replaceMinimumCapital(Money minimumCapital) {
        Objects.requireNonNull(minimumCapital, "minimumCapital");
        current.updateAndGet(prior -> new CompanyCreationRules(prior.registrationFee(), minimumCapital, prior.scale(), prior.initialShares(), prior.allowedDividendPercent()));
    }
}
