package cn.blockeco.exchange.domain.registration;

import cn.blockeco.exchange.domain.money.Money;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record RegistrationSaga(
        UUID id,
        UUID founderId,
        String companyNormalizedName,
        Money totalWithdrawal,
        RegistrationSagaState state,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt) {

    public RegistrationSaga {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(founderId, "founderId");
        Objects.requireNonNull(companyNormalizedName, "companyNormalizedName");
        Objects.requireNonNull(totalWithdrawal, "totalWithdrawal");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
