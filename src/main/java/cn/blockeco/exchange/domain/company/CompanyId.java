package cn.blockeco.exchange.domain.company;

import java.util.Objects;
import java.util.UUID;

public record CompanyId(UUID value) {

    public CompanyId {
        Objects.requireNonNull(value, "value");
    }
}
