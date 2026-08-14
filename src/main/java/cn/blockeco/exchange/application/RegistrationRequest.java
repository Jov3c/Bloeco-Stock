package cn.blockeco.exchange.application;
import java.util.Objects;
import java.util.UUID;
public record RegistrationRequest(UUID founderId, String companyName, int dividendPercent) {
    public RegistrationRequest { Objects.requireNonNull(founderId); Objects.requireNonNull(companyName); }
}
