package cn.blockeco.exchange.domain.finance;

import cn.blockeco.exchange.domain.company.CompanyId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AssetBinding(
        UUID id,
        CompanyId companyId,
        String adapterId,
        String externalKey,
        UUID verifiedOwner,
        AssetBindingState state,
        Instant createdAt) {

    public AssetBinding {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(companyId, "companyId");
        requireNonBlank(adapterId, "adapterId");
        requireNonBlank(externalKey, "externalKey");
        Objects.requireNonNull(verifiedOwner, "verifiedOwner");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
