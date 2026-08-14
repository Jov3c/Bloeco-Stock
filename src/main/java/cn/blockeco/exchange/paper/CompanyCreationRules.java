package cn.blockeco.exchange.paper;

import cn.blockeco.exchange.domain.money.Money;
import java.util.List;
import java.util.Objects;

/** Immutable, validated creation rules shared by parsing, help, and completion. */
public record CompanyCreationRules(Money registrationFee, Money minimumCapital, int scale,
                                   int initialShares, List<Integer> allowedDividendPercent) {
    public CompanyCreationRules {
        Objects.requireNonNull(registrationFee, "registrationFee");
        Objects.requireNonNull(minimumCapital, "minimumCapital");
        allowedDividendPercent = List.copyOf(Objects.requireNonNull(allowedDividendPercent, "allowedDividendPercent"));
        if (allowedDividendPercent.isEmpty() || allowedDividendPercent.stream().anyMatch(value -> value == null || value < 1 || value > 100)
                || allowedDividendPercent.stream().distinct().count() != allowedDividendPercent.size()) {
            throw new IllegalArgumentException("allowed dividend percentages must be unique values from 1 to 100");
        }
    }

    public Money totalRequired() { return registrationFee.plus(minimumCapital); }
    public boolean acceptsPaidInCapital(Money paidInCapital) { return paidInCapital.minorUnits() > 0 && paidInCapital.minorUnits() >= minimumCapital.minorUnits(); }
    public String registrationFeeMajor() { return registrationFee.toMajor(scale).toPlainString(); }
    public String minimumCapitalMajor() { return minimumCapital.toMajor(scale).toPlainString(); }
    public String totalRequiredMajor() { return totalRequired().toMajor(scale).toPlainString(); }
    public String dividendChoices() { return allowedDividendPercent.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining("|")); }
}
