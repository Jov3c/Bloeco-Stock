package cn.blockeco.exchange.application;
import cn.blockeco.exchange.domain.money.Money;
import java.util.Objects;
import java.util.UUID;
public record RegistrationRequest(UUID founderId, String companyName, Money paidInCapital, int dividendPercent) {
    public RegistrationRequest { Objects.requireNonNull(founderId); Objects.requireNonNull(companyName); Objects.requireNonNull(paidInCapital); }
    public RegistrationRequest(UUID founderId, String companyName, int dividendPercent) { this(founderId, companyName, Money.zero(), dividendPercent); }
}
